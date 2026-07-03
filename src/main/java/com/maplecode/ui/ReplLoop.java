package com.maplecode.ui;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.session.ChatSession;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class ReplLoop {

    private final AppConfig config;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ChatSession session = new ChatSession();

    public ReplLoop(AppConfig config, LlmProvider provider, StreamPrinter printer, LineReader reader) {
        this.config = config;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider) throws java.io.IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        return new ReplLoop(config, provider, new StreamPrinter(System.out), reader);
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                input = readMultiline();
            } catch (UserInterruptException e) {
                continue;
            } catch (RuntimeException e) {
                // JLine 在 Ctrl+D 时返回 null；这里兜底其他运行时异常
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

            session.appendUser(trimmed);
            ChatRequest req = session.toRequest(config.model(), config.systemPrompt(), config.thinking());
            StringBuilder textBuf = new StringBuilder();
            try {
                provider.stream(req, chunk -> {
                    switch (chunk) {
                        case StreamChunk.TextDelta d       -> { printer.write(d.text()); textBuf.append(d.text()); }
                        case StreamChunk.ThinkingDelta d   -> printer.writeThinking(d.text());
                        case StreamChunk.MessageStart s    -> { /* 空操作 */ }
                        case StreamChunk.MessageEnd e      -> printer.endAssistant();
                        case StreamChunk.Error e           -> printer.error(e.code() + ": " + e.message());
                        case StreamChunk.ToolUseStart d    -> { /* TODO Task 22 */ }
                        case StreamChunk.ToolUseDelta d    -> { /* TODO Task 22 */ }
                        case StreamChunk.ToolUseEnd d      -> { /* TODO Task 22 */ }
                    }
                });
                if (textBuf.length() > 0) session.appendAssistant(textBuf.toString());
            } catch (ProviderException e) {
                printer.error("request failed: " + e.getMessage());
            }
            printer.newline();
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
        // 去掉末尾的换行（如果有）
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }
}