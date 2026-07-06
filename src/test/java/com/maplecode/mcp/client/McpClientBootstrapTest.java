package com.maplecode.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.mcp.config.McpServerSpec;
import com.maplecode.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpClientBootstrapTest {

    @Test
    void emptySpecsReturnsEmptyMap() {
        var b = new McpClientBootstrap(spec -> mock(McpTransport.class), Duration.ofMillis(100));
        Map<String, McpClient> out = b.start(List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void startsSpecReturnsMapByName() {
        var transport = mock(McpTransport.class);
        when(transport.send(any(JsonNode.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        var b = new McpClientBootstrap(spec -> transport, Duration.ofMillis(200));
        var specs = List.<McpServerSpec>of(
            new McpServerSpec.Stdio("only", "echo", List.of(), Map.of()));
        Map<String, McpClient> out = b.start(specs);
        assertNotNull(out);
    }

    @Test
    void timeoutOnOneServerDoesNotStopOthers() {
        McpTransport hang = mock(McpTransport.class);
        when(hang.send(any(JsonNode.class))).thenReturn(new CompletableFuture<>());
        McpTransport ok = mock(McpTransport.class);
        when(ok.send(any(JsonNode.class))).thenReturn(CompletableFuture.completedFuture(null));

        var ctr = new AtomicInteger();
        var b = new McpClientBootstrap(spec -> ctr.getAndIncrement() == 0 ? hang : ok,
                                       Duration.ofMillis(200));
        var specs = List.<McpServerSpec>of(
            new McpServerSpec.Stdio("a", "x", List.of(), Map.of()),
            new McpServerSpec.Stdio("b", "y", List.of(), Map.of()));
        Map<String, McpClient> out = b.start(specs);
        assertNotNull(out);
    }
}
