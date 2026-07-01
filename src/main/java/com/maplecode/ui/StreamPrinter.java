package com.maplecode.ui;

import java.io.PrintStream;

public final class StreamPrinter {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";

    private final PrintStream out;

    public StreamPrinter() {
        this(System.out);
    }

    public StreamPrinter(PrintStream out) {
        this.out = out;
    }

    public void banner(String text) {
        out.println(BOLD + text + RESET);
        out.println();
    }

    public void startAssistant() {
        // 空操作 —— 由 REPL 自行追踪
    }

    public void write(String text) {
        out.print(text);
        out.flush();
    }

    public void writeThinking(String text) {
        out.print(DIM + text + RESET);
        out.flush();
    }

    public void endAssistant() {
        out.println();
    }

    public void error(String message) {
        out.println(RED + "✗ " + message + RESET);
    }

    public void info(String message) {
        out.println(message);
    }

    public void newline() {
        out.println();
    }
}
