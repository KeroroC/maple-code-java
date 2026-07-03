package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.util.stream.Collectors;

public final class ToolExecutor {

    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 调工具。绝不抛异常——所有失败都包成 ToolResult(isError=true)。
     */
    public ToolResult run(String name, JsonNode args) {
        var toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) {
            String available = registry.all().stream()
                .map(Tool::name)
                .collect(Collectors.joining(", "));
            return ToolResult.error("Unknown tool: " + name + ". Available: " + available);
        }
        try {
            // 缺 ctx 也要兜底：构造一个默认 ctx，cwd=.，保守限
            ToolContext ctx = ToolContext.defaults(java.nio.file.Path.of(System.getProperty("user.dir")));
            return toolOpt.get().execute(args, ctx);
        } catch (ToolException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
