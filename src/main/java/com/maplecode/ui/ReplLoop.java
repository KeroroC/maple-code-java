package com.maplecode.ui;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.config.AppConfig;
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
    private final ChatSession session;
    private final AgentLoop agent;
    private AgentConfig agentConfig;

    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry) {
        this.appConfig = appConfig;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = new ToolExecutor(registry);
        this.session = new ChatSession();
        this.agentConfig = AgentConfig.fromAppConfig(appConfig);
        this.agent = new AgentLoop(provider, registry, executor, session, agentConfig);
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws java.io.IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        return new ReplLoop(config, provider, new StreamPrinter(System.out), reader, registry);
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/tools 列出工具，/plan 规划，/do 执行计划，/cancel 取消，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                input = readMultiline();
            } catch (UserInterruptException e) {
                // Ctrl-C during input: cancel any running agent
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
                printer.info("history cleared");
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
                agentConfig = agentConfig.withPlanMode(PlanMode.PLAN);
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
                agentConfig = agentConfig.withPlanMode(PlanMode.NORMAL);
                agent.updateConfig(agentConfig);
                agent.run(planText, printer);
                printer.newline();
                continue;
            }

            // /cancel
            if (trimmed.equals("/cancel")) {
                agent.cancel();
                agentConfig = agentConfig.withPlanMode(PlanMode.NORMAL);
                agent.updateConfig(agentConfig);
                printer.info("cancelled");
                continue;
            }

            // Normal turn: delegate to AgentLoop
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
            printer.info("- " + t.name() + ": " + t.description());
        }
    }

    private String readMultiline() {
        String first;
        try {
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
