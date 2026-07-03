package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    @Override
    public String name() { return "glob"; }

    @Override
    public String description() {
        return "Find files matching a glob pattern (e.g. '**/*.java'). "
            + "Results are paths relative to cwd, one per line.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Glob pattern, e.g. '**/*.java' or '*.txt'.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pattern = ReadFileTool.requiredString(args, "pattern");
        Path cwd = ctx.cwd();

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(cwd)) {
            stream
                .filter(Files::isRegularFile)
                .filter(matcher::matches)
                .sorted(Comparator.comparing(Path::toString))
                .forEach(matches::add);
        } catch (IOException e) {
            throw new ToolException("walk failed: " + e.getMessage(), e);
        }

        int limit = ctx.globMaxResults();
        boolean truncated = matches.size() > limit;
        List<Path> shown = truncated ? matches.subList(0, limit) : matches;

        StringBuilder sb = new StringBuilder();
        for (var p : shown) {
            String rel = cwd.relativize(p).toString();
            sb.append(rel).append('\n');
        }
        if (truncated) {
            sb.append("[truncated, total=").append(matches.size()).append("]\n");
        }
        return ToolResult.ok(sb.toString());
    }
}
