package com.maplecode.agent;

import com.maplecode.compression.CompressionCoordinator;
import com.maplecode.compression.CompressionResult;
import com.maplecode.compression.CompressionTrigger;
import com.maplecode.error.ProviderException;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.PromptAssembler;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.provider.TokenUsage;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AgentLoop {

    private final LlmProvider provider;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ChatSession session;
    private AgentConfig config;
    private final Consumer<TokenUsage> usageSink;
    private final CompressionCoordinator coord;  // nullable
    private volatile boolean cancelled;

    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config, Consumer<TokenUsage> usageSink,
                     CompressionCoordinator coord) {
        this.provider = provider;
        this.registry = registry;
        this.executor = executor;
        this.session = session;
        this.config = config;
        this.usageSink = usageSink;
        this.coord = coord;
    }

    /** 6 参重载（coord=null），保留调用兼容性。 */
    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config, Consumer<TokenUsage> usageSink) {
        this(provider, registry, executor, session, config, usageSink, null);
    }

    /** 5 参重载（usageSink=null, coord=null），保留测试路径。 */
    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config) {
        this(provider, registry, executor, session, config, null, null);
    }

    public void cancel() {
        cancelled = true;
    }

    public ChatSession session() {
        return session;
    }

    public void updateConfig(AgentConfig config) {
        this.config = config;
    }

    public void run(String userInput, Consumer<AgentEvent> sink) {
        session.appendUserText(userInput);
        int iteration = 0;
        int consecutiveUnknown = 0;
        StopReason finalStop = StopReason.END_TURN;
        String finalDetail = "assistant finished";

        // PLAN mode: build a read-only executor for defense-in-depth
        final ToolExecutor effectiveExecutor;
        if (config.planMode() == PlanMode.PLAN) {
            var readOnlyReg = new ToolRegistry(
                registry.all().stream()
                    .filter(t -> registry.isReadOnly(t.name()))
                    .toList());
            effectiveExecutor = new ToolExecutor(readOnlyReg);
        } else {
            effectiveExecutor = executor;
        }

        while (iteration < config.maxIterations()) {
            if (cancelled) {
                finalStop = StopReason.USER_CANCELLED;
                finalDetail = "user cancelled";
                break;
            }

            if (coord != null && iteration > 0) {
                var outcome = coord.beforeRequest(session, CompressionTrigger.AUTO, coord.lastSeenUsage());
                if (outcome.result() instanceof CompressionResult.ChangedOffloadOnly
                    || outcome.result() instanceof CompressionResult.ChangedFull) {
                    session.replaceAll(outcome.newMessages());
                    sink.accept(new AgentEvent.CompressionApplied(outcome.result()));
                }
                // SKIPPED_CIRCUIT_OPEN / FAILED_* / NOOP 静默继续
            }

            sink.accept(new AgentEvent.IterationStart(iteration));
            ResponseCollector col = new ResponseCollector(sink, registry, usageSink);

            // Wire layer: PLAN mode only exposes read-only tools to the model
            var tools = (config.planMode() == PlanMode.PLAN)
                ? registry.readOnly()
                : registry.all();
            var sysBlocks = config.systemBlocks();
            var req = session.toRequest(config.model(), sysBlocks, config.thinking(), tools);

            // PLAN 模式下追加 reminder（不持久化到 session）
            var reminderForm = PlanModeReminder.decide(
                config.planMode(), config.reminderState(), iteration);
            if (reminderForm != PlanModeReminder.Form.NONE) {
                String body = (reminderForm == PlanModeReminder.Form.FULL)
                    ? PlanModeReminder.renderFull()
                    : PlanModeReminder.renderBrief();
                req = new PromptAssembler().attachReminder(req, body);
                if (reminderForm == PlanModeReminder.Form.FULL) {
                    config = config.withReminderState(
                        config.reminderState().afterFull(iteration));
                }
            }

            try {
                provider.stream(req, col);
            } catch (ProviderException e) {
                sink.accept(new AgentEvent.AgentStop(StopReason.PROVIDER_ERROR, e.getMessage()));
                return;
            }

            sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
                col.toolUses().stream().map(ResponseCollector.ToolUse::id).toList(),
                col.usage()));

            if (col.stopReason() == StopReason.TOOL_USE && !col.toolUses().isEmpty()) {
                // Build assistant content: text (if any) + all ToolUseBlocks
                var assistantBlocks = new ArrayList<ContentBlock>();
                if (!col.text().isEmpty()) {
                    assistantBlocks.add(new ContentBlock.TextBlock(col.text().toString()));
                }
                for (var u : col.toolUses()) {
                    assistantBlocks.add(new ContentBlock.ToolUseBlock(u.id(), u.name(), u.input()));
                }
                session.appendAssistant(assistantBlocks);

                // Partition by safety and execute
                var batch = Batch.partition(col.toolUses(), registry);
                sink.accept(new AgentEvent.BatchStart(batch.safe().size(), batch.unsafe().size()));

                var results = new ArrayList<ToolResultPayload>();

                // Safe tools: parallel execution
                batch.safe().parallelStream().forEach(u -> {
                    var r = executeOne(u, effectiveExecutor);
                    synchronized (results) {
                        results.add(new ToolResultPayload(u.id(), r));
                    }
                    sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), r.isError(), r.content()));
                });

                // Unsafe tools: serial execution
                for (var u : batch.unsafe()) {
                    var r = executeOne(u, effectiveExecutor);
                    results.add(new ToolResultPayload(u.id(), r));
                    sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), r.isError(), r.content()));
                }

                int failed = (int) results.stream().filter(r -> r.result().isError()).count();
                sink.accept(new AgentEvent.BatchEnd(results.size(), failed));

                // Append all tool results as a single user message
                var resultBlocks = new ArrayList<ContentBlock>();
                for (var r : results) {
                    resultBlocks.add(new ContentBlock.ToolResultBlock(
                        r.toolUseId(), r.result().content(), r.result().isError()));
                }
                session.appendUser(resultBlocks);

                // Track consecutive unknown tools
                int unknownThisBatch = 0;
                for (var r : results) {
                    if (r.result().isError() && r.result().content() != null
                        && r.result().content().startsWith("Unknown tool:")) {
                        unknownThisBatch++;
                    } else {
                        unknownThisBatch = 0;
                        break;
                    }
                }
                if (unknownThisBatch == 0) {
                    consecutiveUnknown = 0;
                } else {
                    consecutiveUnknown += unknownThisBatch;
                }
                if (consecutiveUnknown >= config.maxConsecutiveUnknown()) {
                    finalStop = StopReason.CONSECUTIVE_UNKNOWN;
                    finalDetail = "unknown tool called " + config.maxConsecutiveUnknown() + " times in a row";
                    break;
                }

                iteration++;
                continue;
            }

            // Non-tool response: append text and stop
            if (!col.text().isEmpty()) {
                session.appendAssistant(List.of(new ContentBlock.TextBlock(col.text().toString())));
            }

            finalStop = col.stopReason();
            finalDetail = "assistant finished";
            break;
        }

        if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
            finalStop = StopReason.MAX_ITERATIONS;
            finalDetail = "reached iteration cap: " + config.maxIterations();
        }

        sink.accept(new AgentEvent.AgentStop(finalStop, finalDetail));
    }

    private ToolResult executeOne(ResponseCollector.ToolUse u, ToolExecutor exec) {
        try {
            return exec.run(u.name(), u.input());
        } catch (Exception e) {
            return ToolResult.error("internal error: " + e.getClass().getSimpleName());
        }
    }

    private record ToolResultPayload(String toolUseId, ToolResult result) {}
}
