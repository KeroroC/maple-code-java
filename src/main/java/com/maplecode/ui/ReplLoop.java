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
import com.maplecode.provider.TokenUsage;
import com.maplecode.session.ChatSession;
import com.maplecode.session.archive.SessionArchive;
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
    private final SessionArchive sessionArchive;  // nullable
    private final CompactCoordinator coord;  // nullable
    private final com.maplecode.memory.MemoryManager memoryManager;  // nullable
    private final StatusBar statusBar;  // nullable
    private volatile TokenUsage lastTokenUsage;  // volatile 为防御性措施，当前 usageSink 和 updateStatusBar 均在主线程

    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    SessionArchive sessionArchive, CompactCoordinator coord,
                    com.maplecode.memory.MemoryManager memoryManager,
                    StatusBar statusBar) {
        this.appConfig = appConfig;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = executor;
        this.engine = engine;
        this.session = new ChatSession();
        this.agentConfig = agentConfig;
        this.sessionArchive = sessionArchive;
        this.coord = coord;
        this.memoryManager = memoryManager;
        this.statusBar = statusBar;
        java.util.function.Consumer<com.maplecode.provider.TokenUsage> usageSink = coord != null
            ? u -> { printer.usage(u); coord.recordUsage(u); lastTokenUsage = u; }
            : u -> { printer.usage(u); lastTokenUsage = u; };
        this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
                usageSink, coord);
    }

    /** 8 参数向后兼容构造器（sessionArchive=null, coord=null, memoryManager=null, statusBar=null）。 */
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig, null, null, null, null);
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws java.io.IOException {
        throw new UnsupportedOperationException("use App.main with explicit AgentConfig");
    }

    private void updateStatusBar(String mode) {
        if (statusBar == null) return;
        statusBar.update(new StatusBar.StatusState(
            appConfig.model(), lastTokenUsage, mode,
            abbreviateHome(System.getProperty("user.dir"))));
    }

    private static String abbreviateHome(String path) {
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private String renderMode() {
        String planPart = agentConfig.planMode() == PlanMode.PLAN ? "plan" : "normal";
        String permPart = engine.mode().name().toLowerCase();
        if ("default".equals(permPart)) return planPart;
        return planPart + ":" + permPart;
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/new 新会话，/resume 恢复会话，/compact 压缩上下文，/tools 列出工具，/mode 权限模式，/plan 规划，/do 执行计划，/cancel 取消，/memory 记忆管理，\"\"\" 开始多行输入");
        // 初始化状态栏
        if (statusBar != null) {
            updateStatusBar(renderMode());
            reader.getTerminal().handle(Terminal.Signal.WINCH, sig -> statusBar.resize());  // JLine 内部通过 pump 线程排队 puts()，信号处理器中安全
        }
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
                lastTokenUsage = null;
                updateStatusBar(renderMode());
                printer.info("history cleared");
                continue;
            }

            // /new
            if (trimmed.equals("/new")) {
                if (sessionArchive != null) {
                    String archived = sessionArchive.save(agent.session());
                    if (archived != null) {
                        printer.info("Archived current session (" + agent.session().size() + " messages).");
                    }
                }
                agent.session().clear();
                if (coord != null) coord.resetCounter();
                lastTokenUsage = null;
                updateStatusBar(renderMode());
                printer.info("New session started.");
                continue;
            }

            // /resume
            if (trimmed.equals("/resume") || trimmed.startsWith("/resume ")) {
                if (sessionArchive == null) {
                    printer.error("session archive not available");
                    continue;
                }
                String arg = trimmed.length() > 8 ? trimmed.substring(8).trim() : "";
                if (arg.isEmpty()) {
                    var recent = sessionArchive.listRecent(10);
                    if (recent.isEmpty()) {
                        printer.info("(no archived sessions)");
                        continue;
                    }
                    printer.info("Recent sessions:");
                    for (int i = 0; i < recent.size(); i++) {
                        var meta = recent.get(i);
                        String relTime = formatRelativeTime(meta.lastActivity());
                        printer.info("  [" + (i + 1) + "] " + meta.id()
                            + " (" + meta.messageCount() + " messages, " + relTime + ")");
                    }
                    String selection;
                    try {
                        selection = reader.readLine("Select [1-" + recent.size() + "]: ");
                    } catch (Exception e) {
                        continue;
                    }
                    if (selection == null) continue;
                    int idx;
                    try {
                        idx = Integer.parseInt(selection.trim());
                    } catch (NumberFormatException e) {
                        printer.error("invalid selection");
                        continue;
                    }
                    if (idx < 1 || idx > recent.size()) {
                        printer.error("invalid selection");
                        continue;
                    }
                    var chosen = recent.get(idx - 1);
                    try {
                        var loaded = sessionArchive.load(chosen.id());
                        agent.session().clear();
                        if (coord != null) coord.resetCounter();
                        agent.session().replaceAll(loaded);
                        printer.info("Restored " + loaded.size() + " messages from archive.");
                    } catch (Exception e) {
                        printer.error("failed to load session: " + e.getMessage());
                    }
                } else {
                    try {
                        var loaded = sessionArchive.load(arg);
                        agent.session().clear();
                        if (coord != null) coord.resetCounter();
                        agent.session().replaceAll(loaded);
                        printer.info("Restored " + loaded.size() + " messages from archive.");
                    } catch (Exception e) {
                        printer.error("failed to load session: " + e.getMessage());
                    }
                }
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
                updateStatusBar(renderMode());
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
                updateStatusBar(renderMode());
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
                updateStatusBar(renderMode());
                continue;
            }

            // /cancel
            if (trimmed.equals("/cancel")) {
                agent.cancel();
                agentConfig = agentConfig.withPlanMode(PlanMode.NORMAL)
                    .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
                agent.updateConfig(agentConfig);
                printer.info("cancelled");
                updateStatusBar(renderMode());
                continue;
            }

            // /memory
            if (trimmed.equals("/memory list")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else {
                    printer.info(memoryManager.listMemories());
                }
                continue;
            }
            if (trimmed.equals("/memory clear")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else {
                    memoryManager.clearAll();
                    printer.info("all memories cleared");
                }
                continue;
            }
            if (trimmed.equals("/memory extract")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else if (appConfig.memoryConfig() == null || !appConfig.memoryConfig().enabled()) {
                    printer.error("memory not enabled in config");
                } else {
                    int maxCtx = appConfig.memoryConfig().maxContextMessages();
                    memoryManager.extractSync(agent.session().recentMessages(maxCtx));
                    printer.info("memory extraction completed");
                }
                continue;
            }

            // 普通对话：委托给 AgentLoop
            agent.run(trimmed, printer);
            updateStatusBar(renderMode());
            // 记忆提取：异步，不阻塞用户交互
            if (memoryManager != null && appConfig.memoryConfig() != null && appConfig.memoryConfig().enabled()) {
                int maxCtx = appConfig.memoryConfig().maxContextMessages();
                memoryManager.extractAsync(agent.session().recentMessages(maxCtx));
            }
            printer.newline();
        }

        // 退出前自动存档
        if (sessionArchive != null) {
            String archived = sessionArchive.save(session);
            if (archived != null) {
                printer.info("Session archived: " + archived);
            }
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
            first = reader.readLine("╭─ > ");
        } catch (UserInterruptException e) {
            throw e;
        }
        if (first == null) return null;
        if (!first.equals("\"\"\"")) return first;
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine("│ ");
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

    private String formatRelativeTime(java.time.Instant instant) {
        java.time.Duration d = java.time.Duration.between(instant, java.time.Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
