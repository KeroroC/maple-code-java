package com.maplecode.mcp.rpc;

public class McpProtocolException extends RuntimeException {
    private final int code;

    public McpProtocolException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public McpProtocolException(int code, String msg, Throwable cause) {
        super(msg, cause);
        this.code = code;
    }

    public int code() { return code; }
}
