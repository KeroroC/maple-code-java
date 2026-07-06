package com.maplecode.permission;

import java.io.PrintStream;

public final class PrintStreamOutputSink implements OutputSink {
    private final PrintStream out;

    public PrintStreamOutputSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void println(String line) {
        out.println(line);
    }
}
