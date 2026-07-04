package com.maplecode.agent;

import com.maplecode.error.ProviderException;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AgentLoop {

    private final LlmProvider provider;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ChatSession session;
    private AgentConfig config;
    private volatile boolean cancelled;

    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config) {
        this.provider = provider;
        this.registry = registry;
        this.executor = executor;
        this.session = session;
        this.config = config;
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

        while (iteration < config.maxIterations()) {
            if (cancelled) {
                finalStop = StopReason.USER_CANCELLED;
                finalDetail = "user cancelled";
                break;
            }

            sink.accept(new AgentEvent.IterationStart(iteration));
            ResponseCollector col = new ResponseCollector(sink, registry);

            var req = new ChatRequest(
                "test-model", null,
                new ArrayList<>(),  // placeholder: Task 15 replaces with session.toRequest
                null, registry.all());

            try {
                provider.stream(req, col);
            } catch (ProviderException e) {
                sink.accept(new AgentEvent.AgentStop(StopReason.PROVIDER_ERROR, e.getMessage()));
                return;
            }

            sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
                col.toolUses().stream().map(ResponseCollector.ToolUse::id).toList(),
                col.usage()));

            // For now: single-turn skeleton. Break after first response.
            if (!col.text().isEmpty()) {
                session.appendAssistant(List.of(new ContentBlock.TextBlock(col.text().toString())));
            }
            finalStop = col.stopReason();
            finalDetail = "assistant finished";
            break;  // Single-turn skeleton: Task 14 adds multi-turn loop
        }

        if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
            finalStop = StopReason.MAX_ITERATIONS;
            finalDetail = "reached iteration cap: " + config.maxIterations();
        }

        sink.accept(new AgentEvent.AgentStop(finalStop, finalDetail));
    }
}
