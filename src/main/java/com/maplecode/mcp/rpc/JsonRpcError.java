package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JsonRpcError(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonProperty("id") Long id,
    @JsonProperty("error") ErrorBody error
) {
    public JsonRpcError(long id, int code, String message) {
        this("2.0", id, new ErrorBody(code, message, null));
    }

    public record ErrorBody(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data
    ) {}
}
