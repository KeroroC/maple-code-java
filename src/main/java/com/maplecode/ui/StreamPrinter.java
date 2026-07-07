package com.maplecode.ui;

import com.maplecode.agent.AgentEvent;
import com.maplecode.compression.CompressionResult;
import com.maplecode.provider.TokenUsage;

import java.io.PrintStream;
import java.util.function.Consumer;

public final class StreamPrinter implements Consumer<AgentEvent> {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";
    private static final String GREEN = "\033[32m";

    private final PrintStream out;

    /** 使用标准输出创建 StreamPrinter */
    public StreamPrinter() {
        this(System.out);
    }

    /** 指定输出流创建 StreamPrinter，便于测试时注入 */
    public StreamPrinter(PrintStream out) {
        this.out = out;
    }

    /** 用粗体打印启动横幅，后面空一行 */
    public void banner(String text) {
        out.println(BOLD + text + RESET);
        out.println();
    }

    /** 空操作——由 REPL 自行追踪助手回复的开始 */
    public void startAssistant() {
        // 空操作 —— 由 REPL 自行追踪
    }

    /** 逐块输出助手文本片段，每次写入后立即 flush 以实现逐字流式显示 */
    public void write(String text) {
        out.print(text);
        out.flush();
    }

    /** 用灰色输出思考过程片段，与正文视觉区分 */
    public void writeThinking(String text) {
        out.print(DIM + text + RESET);
        out.flush();
    }

    /** 一条助手回复结束，打印换行 */
    public void endAssistant() {
        out.println();
    }

    /** 用红色输出错误信息，前面带 ✗ 符号 */
    public void error(String message) {
        out.println(RED + "✗ " + message + RESET);
    }

    /** 普通文本输出，无颜色无格式 */
    public void info(String message) {
        out.println(message);
    }

    /** 打印 token 用量统计，cache 字段仅在 >0 时显示 */
    public void usage(TokenUsage u) {
        if (u == null) return;
        StringBuilder sb = new StringBuilder("[usage: input=").append(u.inputTokens())
            .append(" out=").append(u.outputTokens());
        if (u.cacheCreationTokens() > 0)
            sb.append(" cache_create=").append(u.cacheCreationTokens());
        if (u.cacheReadTokens() > 0)
            sb.append(" cache_read=").append(u.cacheReadTokens());
        sb.append("]");
        info(sb.toString());
    }

    /** 打印空行，用于分隔对话轮次 */
    public void newline() {
        out.println();
    }

    /** 工具开始。灰字行：⚙ read_file /tmp/x */
    public void toolStart(String name, String argSummary) {
        if (argSummary == null || argSummary.isEmpty()) {
            out.println(DIM + "⚙ " + name + RESET);
        } else {
            out.println(DIM + "⚙ " + name + " " + argSummary + RESET);
        }
        out.flush();
    }

    /** 工具结束。绿字 ✓ 或红字 ✗ */
    public void toolEnd(String name, boolean success, String errorDetail) {
        if (success) {
            out.println(GREEN + "✓ " + name + RESET);
        } else {
            String msg = errorDetail == null || errorDetail.isEmpty() ? "" : ": " + errorDetail;
            // 多行错误只取第一行，避免刷屏
            int nl = msg.indexOf('\n');
            if (nl > 0) msg = msg.substring(0, nl);
            out.println(RED + "✗ " + name + msg + RESET);
        }
        out.flush();
    }

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.TextDelta d -> write(d.text());
            case AgentEvent.ThinkingDelta d -> writeThinking(d.text());
            case AgentEvent.ToolCallStart s -> toolStart(s.name(), s.argSummary());
            case AgentEvent.ToolResult r -> toolEnd(r.name(), !r.isError(), r.isError() ? r.content() : null);
            case AgentEvent.IterationStart i -> { /* 静默 */ }
            case AgentEvent.IterationEnd i -> { /* 静默 */ }
            case AgentEvent.BatchStart b -> { /* 静默 */ }
            case AgentEvent.BatchEnd b -> { /* 静默 */ }
            case AgentEvent.ToolCallEnd e -> { /* 静默 */ }
            case AgentEvent.AgentStop s -> info("[agent stopped: " + s.reason() + "]");
            case AgentEvent.CompressionApplied c -> {
                System.err.println("[compression] applied: " + renderResult(c.result()));
            }
        }
    }

    /**
     * ReplLoop /compress 命令显式调用：打印结果到 stdout。
     */
    public void compressionResult(CompressionResult r) {
        out.println(renderResult(r));
    }

    private String renderResult(CompressionResult r) {
        return switch (r) {
            case CompressionResult.Noop n -> "[compression] noop: below threshold";
            case CompressionResult.ChangedOffloadOnly o ->
                "[compression] offloaded " + o.offloadedCount() + " tool result(s)";
            case CompressionResult.ChangedFull f ->
                "[compression] full compression: offloaded " + f.offloadedCount()
                    + ", summary covered ~" + f.summaryInputTokens() + " input tokens";
            case CompressionResult.FailedOffload f ->
                "[compression] offload failed: " + f.reason();
            case CompressionResult.FailedSummary f ->
                "[compression] summary failed (" + f.consecutiveFailures() + " consecutive): " + f.reason();
            case CompressionResult.SkippedCircuitOpen s ->
                "[compression] circuit open (" + s.consecutiveFailures() + " failures); auto-compress disabled this session";
        };
    }
}
