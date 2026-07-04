package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadFileTool implements Tool {

    private static final int BINARY_PROBE_BYTES = 8192;

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read a text file. Returns lines with line numbers. "
            + "Use offset (0-indexed) and limit to read parts of large files.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
            .put("description", "File path; relative paths resolve to cwd.");
        props.putObject("offset").put("type", "integer")
            .put("description", "0-indexed starting line number").put("default", 0);
        props.putObject("limit").put("type", "integer")
            .put("description", "Max lines to return").put("default", 2000);
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = requiredString(args, "path");
        int offset = args.has("offset") ? args.get("offset").asInt(0) : 0;
        int limit = args.has("limit") ? args.get("limit").asInt(2000) : 2000;
        if (offset < 0 || limit <= 0) {
            return ToolResult.error("offset must be >= 0 and limit must be > 0");
        }

        Path path = resolvePath(pathStr, ctx.cwd());
        if (!Files.exists(path)) return ToolResult.error("file not found: " + pathStr);
        if (Files.isDirectory(path)) return ToolResult.error("path is a directory: " + pathStr);

        // 二进制探测
        try (var probe = Files.newInputStream(path)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int n = probe.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return ToolResult.error("binary file not supported: " + pathStr);
            }
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        // 读
        List<String> allLines;
        try {
            allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        int start = Math.min(offset, allLines.size());
        int end = Math.min(start + limit, allLines.size());

        StringBuilder sb = new StringBuilder();
        int lineNoWidth = String.valueOf(end).length();
        // 简单右对齐——用 String.format；最小宽度 4，文件大时跟着 lineNoWidth 走
        int width = Math.max(4, lineNoWidth);
        for (int i = start; i < end; i++) {
            sb.append(String.format("%" + width + "d\t%s%n", i + 1, allLines.get(i)));
        }
        // 字节数截断：粗略估算（UTF-8 字节 ≈ 字符数对 ASCII；中文会超，这里只防全 ASCII 大文件）
        String out = sb.toString();
        if (out.getBytes(StandardCharsets.UTF_8).length > ctx.readMaxBytes()) {
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            String truncated = new String(bytes, 0, ctx.readMaxBytes(), StandardCharsets.UTF_8);
            // 防止切到多字节字符中间
            while (ctx.readMaxBytes() > 0 && (truncated.getBytes(StandardCharsets.UTF_8).length > ctx.readMaxBytes()
                || (truncated.charAt(truncated.length() - 1) == '�'))) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            return ToolResult.ok(truncated + "\n[truncated]");
        }
        return ToolResult.ok(out);
    }

    static Path resolvePath(String p, Path cwd) {
        if (p.startsWith("/")) return Path.of(p);
        if (p.equals("~")) return Path.of(System.getProperty("user.home"));
        if (p.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), p.substring(2));
        }
        return Path.of(cwd.toString(), p);
    }

    static String requiredString(JsonNode args, String name) {
        JsonNode n = args.get(name);
        if (n == null || n.isNull()) {
            throw new ToolException("missing required argument: " + name);
        }
        return n.asText();
    }
}
