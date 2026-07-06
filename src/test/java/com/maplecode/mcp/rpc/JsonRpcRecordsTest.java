package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcRecordsTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void requestSerializesToWireShape() throws Exception {
        var req = new JsonRpcRequest(7, "tools/list", null);
        JsonNode node = m.valueToTree(req);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(7, node.get("id").asLong());
        assertEquals("tools/list", node.get("method").asText());
        assertFalse(node.has("params"));
    }

    @Test
    void requestWithParamsSerializesParams() throws Exception {
        var req = new JsonRpcRequest(7, "tools/call",
            m.readTree("{\"name\":\"x\"}"));
        JsonNode node = m.valueToTree(req);
        assertEquals("x", node.get("params").get("name").asText());
    }

    @Test
    void responseWithResult() throws Exception {
        var resp = new JsonRpcResponse(7,
            m.readTree("{\"content\":[]}"));
        JsonNode node = m.valueToTree(resp);
        assertFalse(node.has("error"));
        assertTrue(node.get("result").isObject());
    }

    @Test
    void errorSerializes() throws Exception {
        var err = new JsonRpcError(7, -32601, "Method not found");
        JsonNode node = m.valueToTree(err);
        assertEquals(7, node.get("id").asLong());
        assertEquals(-32601, node.get("error").get("code").asInt());
        assertEquals("Method not found", node.get("error").get("message").asText());
    }
}
