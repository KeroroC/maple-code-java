package com.maplecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.mcp.rpc.McpConnectionException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Stdio implements McpTransport {

    private static final ObjectMapper M = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String prefix;
    private volatile Consumer<JsonNode> inbound;

    public Stdio(List<String> commandAndArgs, String nameForDiagnostics,
                 Path stderrRedirect) throws IOException {
        if (commandAndArgs == null || commandAndArgs.isEmpty())
            throw new IllegalArgumentException("command must not be empty");
        var pb = new ProcessBuilder(commandAndArgs).redirectErrorStream(false);
        this.process = pb.start();
        this.prefix = nameForDiagnostics + ":stderr] ";
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stderrThread = startStderrForward(process.getErrorStream(), stderrRedirect);
        this.readerThread = startReader(process.getInputStream());
    }

    @Override
    public void onInbound(Consumer<JsonNode> inbound) {
        if (this.inbound != null)
            throw new IllegalStateException("onInbound already set");
        this.inbound = inbound;
    }

    @Override
    public CompletableFuture<Void> send(JsonNode frame) {
        if (closed.get()) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(new IOException("transport closed"));
            return f;
        }
        return CompletableFuture.runAsync(() -> writeLine(frame.toString()));
    }

    private synchronized void writeLine(String s) {
        try {
            stdin.write(s);
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            closed.set(true);
            throw new RuntimeException("stdio write failed", e);
        }
    }

    private Thread startReader(InputStream in) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (closed.get()) return;
                    handleLine(line);
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    System.err.println(prefix + "stdout closed: " + e.getMessage());
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    System.err.println(prefix + "reader crashed: " + e.getMessage());
                }
            }
        }, "mcp-stdio-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void handleLine(String line) {
        try {
            JsonNode node = M.readTree(line);
            Consumer<JsonNode> cb = inbound;
            if (cb != null) cb.accept(node);
        } catch (Exception e) {
            System.err.println(prefix + "bad frame: " + e.getMessage());
        }
    }

    private Thread startStderrForward(InputStream err, Path log) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.err.println(prefix + line);
                    if (log != null) {
                        try {
                            java.nio.file.Files.writeString(log,
                                line + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                        } catch (IOException ignore) {}
                    }
                }
            } catch (IOException ignore) {}
        }, "mcp-stdio-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void close(Throwable cause) {
        if (!closed.compareAndSet(false, true)) return;
        try { stdin.close(); } catch (IOException ignore) {}
        process.destroyForcibly();
        try { readerThread.join(500); } catch (InterruptedException ignore) {}
        try { stderrThread.join(500); } catch (InterruptedException ignore) {}
    }

    @Override
    public void close() {
        close(null);
    }
}
