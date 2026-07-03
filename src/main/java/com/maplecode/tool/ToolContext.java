package com.maplecode.tool;

import java.nio.file.Path;

/**
 * 工具执行上下文。所有工具的硬编码限都在这里，由 REPL 启动期构造。
 */
public record ToolContext(
    Path cwd,
    int readMaxBytes,             // 默认 1_048_576
    int execDefaultTimeoutSec,    // 默认 30
    int grepMaxResults,           // 默认 100
    int globMaxResults            // 默认 100
) {
    public static ToolContext defaults(Path cwd) {
        return new ToolContext(cwd, 1_048_576, 30, 100, 100);
    }
}
