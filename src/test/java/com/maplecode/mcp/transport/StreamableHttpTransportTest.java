package com.maplecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamableHttpTransportTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void jsonResponseParsedAsSingleFrame() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        HttpClient client = mock(HttpClient.class);

        String respBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
        var httpResp = mock(HttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(httpResp.headers()).thenReturn(HttpHeaders.of(Map.of(
            "content-type", List.of("application/json")), (a, b) -> true));
        when(httpResp.body()).thenReturn(java.util.stream.Stream.of(respBody));
        when(client.send(any(HttpRequest.class), any())).thenReturn(httpResp);

        var transport = new StreamableHttp(client, "https://x/mcp", Map.of());
        transport.onInbound(received::offer);
        transport.send(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .get(2, java.util.concurrent.TimeUnit.SECONDS);
        JsonNode frame = received.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(frame);
        assertEquals(1, frame.get("id").asLong());
        transport.close(null);
    }

    @Test
    void sseResponseSplitsAndConcat() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        HttpClient client = mock(HttpClient.class);

        var httpResp = mock(HttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(httpResp.headers()).thenReturn(HttpHeaders.of(Map.of(
            "content-type", List.of("text/event-stream")), (a, b) -> true));
        when(httpResp.body()).thenReturn(java.util.stream.Stream.of(
            "event: message",
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":",
            "data: {\"ok\":true}}",
            "", // 终止空行
            ""
        ));
        when(client.send(any(HttpRequest.class), any())).thenReturn(httpResp);

        var transport = new StreamableHttp(client, "https://x/mcp", Map.of());
        transport.onInbound(received::offer);
        transport.send(m.readTree("{}")).get(2, java.util.concurrent.TimeUnit.SECONDS);
        JsonNode frame = received.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(frame);
        assertTrue(frame.get("result").get("ok").asBoolean());
        transport.close(null);
    }

    @Test
    void http401Fails() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var httpResp = mock(HttpResponse.class);
        when(httpResp.statusCode()).thenReturn(401);
        when(httpResp.body()).thenReturn(java.util.stream.Stream.of("unauthorized"));
        when(client.send(any(HttpRequest.class), any())).thenReturn(httpResp);

        var transport = new StreamableHttp(client, "https://x/mcp", Map.of());
        var ex = assertThrows(java.util.concurrent.ExecutionException.class, () ->
            transport.send(m.readTree("{}")).get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertInstanceOf(IOException.class, ex.getCause().getCause());
        transport.close(null);
    }
}
