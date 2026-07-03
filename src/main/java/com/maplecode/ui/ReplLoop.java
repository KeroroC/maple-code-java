package com.maplecode.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReplLoop {

    private final AppConfig config;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ChatSession session = new ChatSession();
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ToolContext toolCtx;

    public ReplLoop(AppConfig config, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry) {
        this.config = config;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = new ToolExecutor(registry);
        this.toolCtx = ToolContext.defaults(Path.of(System.getProperty("user.dir")));
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws java.io.IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        return new ReplLoop(config, provider, new StreamPrinter(System.out), reader, registry);
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/tools 列出工具，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                input = readMultiline();
            } catch (UserInterruptException e) {
                continue;
            } catch (RuntimeException e) {
                break;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("/exit")) break;
            if (trimmed.equals("/clear")) {
                session.clear();
                printer.info("history cleared");
                continue;
            }
            if (trimmed.equals("/tools")) {
                printTools();
                continue;
            }

            session.appendUserText(trimmed);
            runOneTurn();
            printer.newline();
        }
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

    /**
     * 单轮工具调用循环：模型发 tool_use → 执行 → 回灌 → 模型再回话 → 结束。
     * v2 严格 1 个 tool_use；多则报错不修改 session。
     */
    private void runOneTurn() {
        while (true) {
            TurnAccumulator acc = new TurnAccumulator();
            ChatRequest req = session.toRequest(
                config.model(), config.systemPrompt(), config.thinking(), registry.all());
            try {
                provider.stream(req, chunk -> handleChunk(chunk, acc));
            } catch (ProviderException e) {
                printer.error("request failed: " + e.getMessage());
                return;
            }

            if (acc.stopReason != StreamChunk.StopReason.TOOL_USE) {
                if (!acc.text.isEmpty()) {
                    session.appendAssistant(List.of(new ContentBlock.TextBlock(acc.text)));
                }
                return;
            }

            if (acc.toolUses.size() != 1) {
                printer.error("expected exactly 1 tool_use, got " + acc.toolUses.size());
                return;
            }

            var tu = acc.toolUses.get(0);
            // 把 assistant 这一轮的 text + tool_use 都写入 session
            var assistantBlocks = new ArrayList<ContentBlock>();
            if (!acc.text.isEmpty()) {
                assistantBlocks.add(new ContentBlock.TextBlock(acc.text));
            }
            assistantBlocks.add(new ContentBlock.ToolUseBlock(tu.id(), tu.name(), tu.input()));
            session.appendAssistant(assistantBlocks);

            // 跑工具
            var result = executor.run(tu.name(), tu.input());
            printer.toolEnd(tu.name(), !result.isError(), result.isError() ? result.content() : null);

            // 回灌 tool_result
            session.appendUser(List.of(new ContentBlock.ToolResultBlock(
                tu.id(), result.content(), result.isError())));
            // 继续 while 让模型看到结果再回话
        }
    }

    private void handleChunk(StreamChunk chunk, TurnAccumulator acc) {
        switch (chunk) {
            case StreamChunk.TextDelta d -> {
                printer.write(d.text());
                acc.text.append(d.text());
            }
            case StreamChunk.ThinkingDelta d -> printer.writeThinking(d.text());
            case StreamChunk.ToolUseStart d -> {
                printer.toolStart(d.name(), argSummary(d.name(), acc));
                acc.pendingToolId = d.id();
                acc.pendingToolName = d.name();
            }
            case StreamChunk.ToolUseDelta d -> {
                acc.pendingToolId = d.id();
                acc.pendingToolJson.append(d.partialJson());
            }
            case StreamChunk.ToolUseEnd d -> {
                acc.toolUses.add(new PendingToolUse(d.id(), d.name(), d.input()));
                acc.pendingToolId = null;
                acc.pendingToolName = null;
                acc.pendingToolJson.setLength(0);
            }
            case StreamChunk.MessageStart s -> { /* 空 */ }
            case StreamChunk.MessageEnd e -> acc.stopReason = e.reason();
            case StreamChunk.Error e -> printer.error(e.code() + ": " + e.message());
        }
    }

    /** 从累积的 partialJson 里抽 path/command/pattern 等做状态行摘要。 */
    private String argSummary(String toolName, TurnAccumulator acc) {
        String partial = acc.pendingToolJson.toString();
        if (partial.isEmpty()) return "";
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(partial);
            var path = node.path("path");
            if (!path.isMissingNode()) return path.asText();
            var cmd = node.path("command");
            if (!cmd.isMissingNode()) return cmd.asText();
            var pattern = node.path("pattern");
            if (!pattern.isMissingNode()) return pattern.asText();
        } catch (Exception ignored) {}
        return partial.length() > 40 ? partial.substring(0, 40) + "..." : partial;
    }

    private static class TurnAccumulator {
        StringBuilder text = new StringBuilder();
        List<PendingToolUse> toolUses = new ArrayList<>();
        StreamChunk.StopReason stopReason;
        String pendingToolId;
        String pendingToolName;
        StringBuilder pendingToolJson = new StringBuilder();
    }

    private record PendingToolUse(String id, String name, JsonNode input) {}

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
