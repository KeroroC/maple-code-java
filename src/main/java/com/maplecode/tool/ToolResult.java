package com.maplecode.tool;

/**
 * 工具执行的返回值。content 是字符串（v2 简化；后续可扩结构化）。
 * isError=true 时 content 是错误信息，会被回灌给模型让它重试。
 */
public record ToolResult(String content, boolean isError) {
    public static ToolResult ok(String content)    { return new ToolResult(content, false); }
    public static ToolResult error(String content) { return new ToolResult(content, true); }
}
