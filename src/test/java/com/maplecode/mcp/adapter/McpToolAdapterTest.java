package com.maplecode.mcp.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.mcp.client.McpCallResult;
import com.maplecode.mcp.client.McpClient;
import com.maplecode.mcp.client.McpToolDesc;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpToolAdapterTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void syntheticNameFormat() throws Exception {
        var client = mock(McpClient.class);
        when(client.name()).thenReturn("[gh]");
        var desc = new McpToolDesc("create_issue", "desc",
            m.readTree("{\"type\":\"object\"}"));
        var tool = McpToolAdapter.of(client, desc);
        assertEquals("mcp__gh__create_issue", tool.name());
        assertEquals("desc", tool.description());
    }

    @Test
    void executeReturnsMcpResult() throws Exception {
        var client = mock(McpClient.class);
        when(client.name()).thenReturn("[gh]");
        when(client.callToolFuture(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new McpCallResult("ok", false)));
        var desc = new McpToolDesc("create_issue", "d", m.readTree("{}"));
        var tool = McpToolAdapter.of(client, desc);
        JsonNode args = m.readTree("{\"repo\":\"foo\"}");
        ToolResult r = tool.execute(args, ToolContext.defaults(Path.of(".")));
        assertFalse(r.isError());
        assertEquals("ok", r.content());
    }

    @Test
    void isErrorPropagates() throws Exception {
        var client = mock(McpClient.class);
        when(client.name()).thenReturn("[gh]");
        when(client.callToolFuture(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new McpCallResult("oops", true)));
        var desc = new McpToolDesc("x", "d", m.readTree("{}"));
        var t = McpToolAdapter.of(client, desc);
        var r = t.execute(m.readTree("{}"), ToolContext.defaults(Path.of(".")));
        assertTrue(r.isError());
        assertEquals("oops", r.content());
    }
}
