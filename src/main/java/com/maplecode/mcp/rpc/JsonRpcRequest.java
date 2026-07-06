package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonProperty("id") long id,
    @JsonProperty("method") String method,
    @JsonProperty("params") JsonNode params
) {
    public JsonRpcRequest(long id, String method, JsonNode params) {
        this("2.0", id, method, params);
    }
}
