package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具的统一接口。Provider 不知道工具语义；REPL/ToolExecutor
 * 通过这个接口发现工具并执行。
 * <p>
 * 历史上此接口声明为 sealed permits ReadFileTool/.../GrepTool，但
 * Java sealed 接口不允许匿名类实现——这会阻碍测试用具名 mock。
 * 由于本项目对 Tool 没有 `switch (tool)` 站，sealed 的穷尽检查
 * 没有实际收益，所以改为非 sealed。Tool 的 6 个具体类仍在
 * App 里集中注册（新增工具时 App.java 编译失败提醒）。
 */
public interface Tool {

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
