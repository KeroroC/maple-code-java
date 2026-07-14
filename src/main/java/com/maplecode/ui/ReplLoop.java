package com.maplecode.ui;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentEvent;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.command.*;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PromptSection;
import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;
import com.maplecode.config.AppConfig;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk.StopReason;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    private final CommandRegistry cmdRegistry;
    private final EscapeController escapeController;  // nullable
    private volatile TokenUsage lastTokenUsage;  // volatile 为防御性措施，当前 usageSink 和 updateStatusBar 均在主线程

    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    SessionArchive sessionArchive, CompactCoordinator coord,
                    com.maplecode.memory.MemoryManager memoryManager, StatusBar statusBar,
                    CommandRegistry cmdRegistry, EscapeController escapeController,
                    List<PromptSection> sections, DynamicContext env) {
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
        this.cmdRegistry = cmdRegistry;
        this.escapeController = escapeController;
        java.util.function.Consumer<com.maplecode.provider.TokenUsage> usageSink = coord != null
            ? u -> { printer.usage(u); coord.recordUsage(u); lastTokenUsage = u; }
            : u -> { printer.usage(u); lastTokenUsage = u; };
        this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
                usageSink, coord, sections, env);
    }

    /** 14 参数向后兼容构造器（sections=null, env=null）。 */
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    SessionArchive sessionArchive, CompactCoordinator coord,
                    com.maplecode.memory.MemoryManager memoryManager, StatusBar statusBar,
                    CommandRegistry cmdRegistry, EscapeController escapeController) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig,
             sessionArchive, coord, memoryManager, statusBar, cmdRegistry, escapeController, null, null);
    }

    /** 8 参数向后兼容构造器（sessionArchive=null, coord=null, memoryManager=null, statusBar=null, cmdRegistry=null, escapeController=null, sections=null, env=null）。 */
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig,
             null, null, null, null, null, null, null, null);
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

    private class CommandContextImpl implements CommandContext {
        @Override
        public void sendMessage(String message) { printer.info(message); }

        @Override
        public void sendError(String message) { printer.error(message); }

        @Override
        public void sendToAgent(String prompt) {
            runAgent(prompt);
        }

        @Override
        public void setPlanMode(PlanMode mode) {
            agentConfig = agentConfig.withPlanMode(mode)
                .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
            agent.updateConfig(agentConfig);
        }

        @Override
        public PlanMode getPlanMode() { return agentConfig.planMode(); }

        @Override
        public PermissionMode getPermissionMode() { return engine.mode(); }

        @Override
        public void setPermissionMode(PermissionMode mode) { engine.setMode(mode); }

        @Override
        public TokenUsage getTokenUsage() { return lastTokenUsage; }

        @Override
        public void updateStatusBar() {
            if (statusBar != null) {
                ReplLoop.this.updateStatusBar(renderMode());
            }
        }

        @Override
        public String readLine(String prompt) { return reader.readLine(prompt); }

        @Override
        public ChatSession getSession() { return agent.session(); }

        @Override
        public AgentConfig getAgentConfig() { return agentConfig; }
    }

    private StopReason runAgent(String prompt) {
        AtomicReference<StopReason> finalStop = new AtomicReference<>();
        Consumer<AgentEvent> sink = event -> {
            if (escapeController != null) {
                if (event instanceof AgentEvent.IterationStart) {
                    escapeController.startAgentStreaming(agent::cancel);
                } else if (event instanceof AgentEvent.BatchStart
                    || event instanceof AgentEvent.AgentStop) {
                    escapeController.stopAgentStreaming();
                }
            }
            if (event instanceof AgentEvent.AgentStop stop) {
                finalStop.set(stop.reason());
            }
            printer.accept(event);
        };
        try {
            agent.run(prompt, sink);
        } finally {
            if (escapeController != null) escapeController.stopAgentStreaming();
        }
        return finalStop.get();
    }

    public void run() {
        printer.banner("MapleCode — 输入 /help 查看可用命令");

        if (statusBar != null) {
            updateStatusBar(renderMode());
            reader.getTerminal().handle(Terminal.Signal.WINCH, sig -> statusBar.resize());
        }

        CommandContextImpl commandContext = new CommandContextImpl();

        try {
            while (true) {
                String input = readMultiline();
                if (input == null) break;

                String trimmed = input.trim();
                if (trimmed.isEmpty()) continue;

                // ── 分流器 ──
                if (CommandParser.isCommand(trimmed)) {
                    String name = CommandParser.parseName(trimmed);
                    String args = CommandParser.parseArgs(trimmed);
                    java.util.Optional<Command> cmd = cmdRegistry.lookup(name);

                    if (cmd.isPresent()) {
                        try {
                            cmd.get().execute(args, commandContext);
                        } catch (ExitReplException e) {
                            break;
                        }
                    } else {
                        printer.error("未知命令: /" + name + "。输入 /help 查看可用命令。");
                    }
                } else {
                    StopReason stopReason = runAgent(trimmed);
                    if (memoryManager != null && stopReason != StopReason.USER_CANCELLED) {
                        memoryManager.extractAsync(agent.session().recentMessages(20));
                    }
                }
            }
        } catch (UserInterruptException e) {
            agent.cancel();
        }

        // 退出清理
        if (sessionArchive != null) {
            sessionArchive.save(agent.session());
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

    String readMultiline() {
        String first = reader.readLine("> ");
        if (first == null) return null;
        if (!first.equals("\"\"\"")) return first;
        if (escapeController != null) escapeController.beginMultiline();
        try {
            StringBuilder sb = new StringBuilder();
            while (true) {
                String line = reader.readLine("... ");
                if (escapeController != null
                    && escapeController.consumeMultilineAbort()) return "";
                if (line == null) return null;
                if (line.equals("\"\"\"")) break;
                sb.append(line).append('\n');
            }
            if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
            return sb.toString();
        } finally {
            if (escapeController != null) escapeController.endMultiline();
        }
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
