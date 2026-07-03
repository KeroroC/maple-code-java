package com.maplecode.error;

/**
 * 工具执行失败（文件不存在、exec 退出非零、grep regex 非法等）。
 * ToolExecutor 捕获后包成 ToolResult(isError=true)，不抛回 REPL。
 */
public class ToolException extends MapleCodeException {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}