package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonProperty("id") long id,
    @JsonProperty("result") JsonNode result
) {
    public JsonRpcResponse(long id, JsonNode result) {
        this("2.0", id, result);
    }
}
