package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class GrepTool implements Tool {

    private static final int BINARY_PROBE_BYTES = 8192;

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "Search files for lines matching a regex. Returns path:lineno:content. "
            + "Use include_glob to restrict file types. Binary files are skipped.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Regular expression.");
        props.putObject("path").put("type", "string")
            .put("description", "Directory to search; default cwd.").put("default", ".");
        props.putObject("include_glob").put("type", "string")
            .put("description", "If set, only files whose name matches this glob are searched.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String patternStr = ReadFileTool.requiredString(args, "pattern");
        String pathStr = args.has("path") && !args.get("path").isNull()
            ? args.get("path").asText(".") : ".";
        String include = args.has("include_glob") && !args.get("include_glob").isNull()
            ? args.get("include_glob").asText() : null;

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("invalid regex: " + e.getMessage());
        }

        Path root = ReadFileTool.resolvePath(pathStr, ctx.cwd());
        if (!Files.exists(root)) return ToolResult.error("path not found: " + pathStr);

        PathMatcher fileMatcher = include != null
            ? FileSystems.getDefault().getPathMatcher("glob:" + include)
            : null;

        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            var iter = stream.filter(Files::isRegularFile).iterator();
            while (iter.hasNext()) {
                Path p = iter.next();
                if (fileMatcher != null && !fileMatcher.matches(p.getFileName())) continue;
                if (isBinary(p)) continue;
                List<String> fileLines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < fileLines.size(); i++) {
                    if (pattern.matcher(fileLines.get(i)).find()) {
                        String rel = root.relativize(p).toString();
                        lines.add(rel + ":" + (i + 1) + ":" + fileLines.get(i));
                    }
                }
            }
        } catch (IOException e) {
            throw new ToolException("search failed: " + e.getMessage(), e);
        }

        int limit = ctx.grepMaxResults();
        boolean truncated = lines.size() > limit;
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, lines.size());
        for (int i = 0; i < n; i++) sb.append(lines.get(i)).append('\n');
        if (truncated) sb.append("[truncated, total=").append(lines.size()).append("]\n");
        return ToolResult.ok(sb.toString());
    }

    private static boolean isBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }
}