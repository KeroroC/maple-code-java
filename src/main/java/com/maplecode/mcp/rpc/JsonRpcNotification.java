package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 notification：有 method 但无 id，不期待响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcNotification(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonProperty("method") String method,
    @JsonProperty("params") JsonNode params
) {
    public JsonRpcNotification(String method, JsonNode params) {
        this("2.0", method, params);
    }
}
