package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Write content to a file (overwrite if exists). "
            + "Parent directory must already exist.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
            .put("description", "File path; relative paths resolve to cwd.");
        props.putObject("content").put("type", "string")
            .put("description", "Full file content to write.");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = ReadFileTool.requiredString(args, "path");
        String content = ReadFileTool.requiredString(args, "content");
        Path path = ReadFileTool.resolvePath(pathStr, ctx.cwd());

        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            return ToolResult.error("parent directory does not exist: " + parent);
        }
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return ToolResult.ok("wrote " + bytes.length + " bytes to " + pathStr);
        } catch (IOException e) {
            throw new ToolException("write failed: " + e.getMessage(), e);
        }
    }
}
