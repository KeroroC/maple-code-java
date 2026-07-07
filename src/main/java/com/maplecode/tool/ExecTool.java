package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ExecTool implements Tool {

    private static final int OUTPUT_MAX_BYTES = 50 * 1024;

    @Override
    public String name() { return "exec"; }

    @Override
    public String description() {
        return "Run a shell command and return its combined stdout+stderr. "
            + "Use timeout_seconds (default 30) to bound long-running commands.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("command").put("type", "string")
            .put("description", "Shell command (executed via /bin/sh -c).");
        props.putObject("timeout_seconds").put("type", "integer")
            .put("description", "Timeout in seconds; default 30.").put("default", 30);
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String command = ReadFileTool.requiredString(args, "command");
        if (command.isBlank()) {
            return ToolResult.error("empty command");
        }
        int timeout = args.has("timeout_seconds")
            ? args.get("timeout_seconds").asInt(ctx.execDefaultTimeoutSec())
            : ctx.execDefaultTimeoutSec();

        Process process;
        try {
            process = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(ctx.cwd().toFile())
                .redirectErrorStream(true)
                .start();
        } catch (Exception e) {
            return ToolResult.error("failed to start process: " + e.getMessage());
        }

        // 异步读 stdout，避免进程 pipe buffer 满而死锁
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) {
                    synchronized (out) {
                        out.append(buf, 0, n);
                    }
                }
            } catch (Exception ignored) {}
        }, "exec-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResult.error("interrupted");
        }

        if (!finished) {
            process.destroyForcibly();
            try { reader.join(1000); } catch (InterruptedException ignored) {}
            return ToolResult.error("timeout after " + timeout + "s");
        }

        try { reader.join(2000); } catch (InterruptedException ignored) {}
        int exit = process.exitValue();
        String output;
        synchronized (out) {
            output = out.toString();
        }
        if (output.getBytes(StandardCharsets.UTF_8).length > OUTPUT_MAX_BYTES) {
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            String truncated = new String(bytes, 0, OUTPUT_MAX_BYTES, StandardCharsets.UTF_8);
            // 如果截断位置落在多字节字符中间，向前回退
            while (!truncated.isEmpty()
                    && truncated.getBytes(StandardCharsets.UTF_8).length > OUTPUT_MAX_BYTES) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            output = truncated + "\n[truncated]";
        }
        if (exit == 0) return ToolResult.ok(output);
        return ToolResult.error("exit=" + exit + (output.isEmpty() ? "" : "\n" + output));
    }
}
