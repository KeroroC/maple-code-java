package com.maplecode.tool;

import com.maplecode.mcp.adapter.McpToolAdapter;
import com.maplecode.mcp.client.McpClient;
import com.maplecode.mcp.client.McpToolDesc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolRegistryCollisionTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void duplicateMcpToolNamesCollideAtRegistry() throws Exception {
        McpClient client = mock(McpClient.class);
        when(client.name()).thenReturn("[gh]");
        var desc = new McpToolDesc("create_issue", "first", m.readTree("{}"));
        var desc2 = new McpToolDesc("create_issue", "second", m.readTree("{}"));
        var tools = new ArrayList<Tool>();
        tools.add(McpToolAdapter.of(client, desc));
        tools.add(McpToolAdapter.of(client, desc2));
        var ex = assertThrows(IllegalArgumentException.class,
            () -> new ToolRegistry(tools));
        assertTrue(ex.getMessage().contains("duplicate tool name"));
    }
}
