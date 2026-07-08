package com.maplecode.ui;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;
import com.maplecode.config.AppConfig;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class ReplLoop {

    private final AppConfig appConfig;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final PermissionEngine engine;
    private final ChatSession session;
    private final AgentLoop agent;
    private AgentConfig agentConfig;
    private final CompactCoordinator coord;  // nullable

    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    CompactCoordinator coord) {
        this.appConfig = appConfig;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = executor;
        this.engine = engine;
        this.session = new ChatSession();
        this.agentConfig = agentConfig;
        this.coord = coord;
        this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
                printer::usage, coord);
    }

    /** 8 参数向后兼容构造器（coord=null）。 */
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig, null);
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws java.io.IOException {
        throw new UnsupportedOperationException("use App.main with explicit AgentConfig");
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/compact 压缩上下文，/tools 列出工具，/mode 权限模式，/plan 规划，/do 执行计划，/cancel 取消，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                // 阻塞式读取用户输入
                input = readMultiline();
            } catch (UserInterruptException e) {
                // 输入时 Ctrl-C：取消正在运行的 agent
                agent.cancel();
                printer.info("(interrupted)");
                continue;
            } catch (RuntimeException e) {
                break;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            // /exit
            if (trimmed.equals("/exit")) break;

            // /clear
            if (trimmed.equals("/clear")) {
                agent.session().clear();
                if (coord != null) coord.resetCounter();
                printer.info("history cleared");
                continue;
            }

            // /compact
            if (trimmed.equals("/compact")) {
                if (coord == null) {
                    printer.error("compact not enabled");
                    continue;
                }
                var usage = coord.lastSeenUsage();
                var outcome = coord.beforeRequest(agent.session(), CompactTrigger.MANUAL, usage);
                if (outcome.result() instanceof CompactResult.ChangedOffloadOnly
                    || outcome.result() instanceof CompactResult.ChangedFull) {
                    agent.session().replaceAll(outcome.newMessages());
                }
                printer.compactResult(outcome.result());
                continue;
            }

            // /tools
            if (trimmed.equals("/tools")) {
                printTools();
                continue;
            }

            // /plan <query>
            if (trimmed.startsWith("/plan ")) {
                String query = trimmed.substring(6).trim();
                if (query.isEmpty()) {
                    printer.error("/plan requires a query");
                    continue;
                }
                agentConfig = agentConfig.withPlanMode(PlanMode.PLAN)
                    .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
                agent.updateConfig(agentConfig);
                agent.run(query, printer);
                printer.newline();
                continue;
            }

            // /do
            if (trimmed.equals("/do")) {
                if (agentConfig.planMode() != PlanMode.PLAN) {
                    printer.error("not in plan mode");
                    continue;
                }
                String planText = lastAssistantText();
                if (planText == null) {
                    printer.error("no plan to execute");
                    continue;
                }
                agent.session().clear();
                agentConfig = agentConfig.withPlanMode(PlanMode.NORMAL)
                    .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
                agent.updateConfig(agentConfig);
                agent.run(planText, printer);
                printer.newline();
                continue;
            }

            // /mode
            if (trimmed.equals("/mode") || trimmed.startsWith("/mode ")) {
                String arg = trimmed.length() > 5 ? trimmed.substring(6).trim() : "";
                switch (arg) {
                    case "strict", "default", "permissive" -> {
                        engine.setMode(PermissionMode.valueOf(arg.toUpperCase()));
                        printer.info("mode -> " + arg);
                    }
                    case "" -> printer.info("current mode: " + engine.mode());
                    default  -> printer.error("/mode <strict|default|permissive>");
                }
                continue;
            }

            // /cancel
            if (trimmed.equals("/cancel")) {
                agent.cancel();
                agentConfig = agentConfig.withPlanMode(PlanMode.NORMAL)
                    .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
                agent.updateConfig(agentConfig);
                printer.info("cancelled");
                continue;
            }

            // 普通对话：委托给 AgentLoop
            agent.run(trimmed, printer);
            printer.newline();
        }
    }

    private String lastAssistantText() {
        var session = agent.session();
        for (int i = session.size() - 1; i >= 0; i--) {
            var msg = session.get(i);
            if (msg.role() != ChatMessage.Role.ASSISTANT) continue;
            for (var block : msg.blocks()) {
                if (block instanceof ContentBlock.TextBlock t) {
                    return t.text();
                }
            }
        }
        return null;
    }

    private void printTools() {
        var tools = registry.all();
        if (tools.isEmpty()) {
            printer.info("(no tools registered)");
            return;
        }
        for (var t : tools) {
            String header = "- " + t.name() + ": ";
            String[] lines = t.description().split("\n", -1);
            printer.info(header + lines[0]);
            String indent = " ".repeat(header.length());
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    printer.info("");   // 段落分隔走真正空行，避免尾随空格
                } else {
                    printer.info(indent + lines[i]);
                }
            }
        }
    }

    private String readMultiline() {
        String first;
        try {
            // 阻塞调用
            first = reader.readLine("> ");
        } catch (UserInterruptException e) {
            throw e;
        }
        if (first == null) return null;
        if (!first.equals("\"\"\"")) return first;
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine("... ");
            } catch (UserInterruptException e) {
                throw e;
            }
            if (line == null) return null;
            if (line.equals("\"\"\"")) break;
            sb.append(line).append('\n');
        }
        String result = sb.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
