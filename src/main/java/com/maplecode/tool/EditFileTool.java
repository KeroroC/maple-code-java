package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EditFileTool implements Tool {

    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() {
        return "Replace a unique string in a file. old_string must match exactly once. "
            + "Provide more context to disambiguate if it would match multiple locations.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string")
            .put("description", "Text to find; must match exactly once in the file.");
        props.putObject("new_string").put("type", "string")
            .put("description", "Replacement text.");
        schema.putArray("required").add("path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = ReadFileTool.requiredString(args, "path");
        String oldStr = ReadFileTool.requiredString(args, "old_string");
        String newStr = ReadFileTool.requiredString(args, "new_string");
        Path path = ReadFileTool.resolvePath(pathStr, ctx.cwd());

        if (!Files.exists(path)) {
            return ToolResult.error("file not found: " + pathStr);
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        if (oldStr.equals(newStr)) {
            return ToolResult.error("no-op: old_string == new_string");
        }

        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return ToolResult.error("old_string not found in " + pathStr);
        }
        if (count > 1) {
            return ToolResult.error("old_string matches " + count + " locations in "
                + pathStr + "; provide more context to make it unique");
        }

        // 唯一匹配
        String updated = content.replace(oldStr, newStr);
        try {
            Files.writeString(path, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("write failed: " + e.getMessage(), e);
        }
        return ToolResult.ok("replaced 1 occurrence in " + pathStr);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}