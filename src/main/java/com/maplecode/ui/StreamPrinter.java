package com.maplecode.ui;

import java.io.PrintStream;

public final class StreamPrinter {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";

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

    /** 打印空行，用于分隔对话轮次 */
    public void newline() {
        out.println();
    }
}
