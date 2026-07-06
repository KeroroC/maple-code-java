package com.maplecode.mcp.rpc;

public class McpConnectionException extends RuntimeException {
    public McpConnectionException(String msg) { super(msg); }
    public McpConnectionException(String msg, Throwable cause) { super(msg, cause); }
}
