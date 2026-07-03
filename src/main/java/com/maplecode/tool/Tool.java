package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具的统一接口。Provider 不知道工具语义；REPL/ToolExecutor
 * 通过这个接口发现工具并执行。
 *
 * sealed permits 列具体工具类——这样 ToolRegistry 在做异构 List 时
 * 类型已知，且新增工具时所有 switch / 注入点会编译失败被强制更新。
 */
public sealed interface Tool
    permits ReadFileTool,
            WriteFileTool,
            EditFileTool,
            ExecTool,
            GlobTool,
            GrepTool {

    /** 模型在 tool_use 块里写的工具名。 */
    String name();

    /** 模型看到的人类可读描述。 */
    String description();

    /** 工具的入参 JSON Schema。Provider mapper 直接透传给 wire 格式。 */
    JsonNode inputSchema();

    /**
     * 执行工具。args 是模型提供的 JSON 对象。
     * 实现约定：抛 ToolException 表示已知错误；抛其它 Exception 是 bug。
     * ToolExecutor 会兜底所有 Exception 包成 ToolResult.error。
     */
    ToolResult execute(JsonNode args, ToolContext ctx);
}
