package com.maplecode.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.mcp.rpc.McpProtocolException;
import com.maplecode.mcp.transport.McpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private final ObjectMapper m = new ObjectMapper();

    static class FakeTransport implements McpTransport {
        final Queue<JsonNode> sent = new ConcurrentLinkedQueue<>();
        private Consumer<JsonNode> inbound;
        @Override public CompletableFuture<Void> send(JsonNode frame) {
            sent.add(frame); return CompletableFuture.completedFuture(null);
        }
        @Override public void onInbound(Consumer<JsonNode> in) { this.inbound = in; }
        @Override public void close(Throwable cause) {}
        @Override public void close() {}
        void deliver(JsonNode f) { inbound.accept(f); }
    }

    FakeTransport t;
    McpClient client;

    private JsonNode parse(String json) {
        try { return m.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void setUp() throws Exception {
        t = new FakeTransport();
        client = new McpClient(t, "[gh]", Duration.ofSeconds(1));
        // initialize() blocks on fut.get(); deliver response on background thread
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            t.deliver(parse("""
                {"jsonrpc":"2.0","id":1,"result":{
                  "protocolVersion":"2025-06-18",
                  "serverInfo":{"name":"gh","version":"1"},
                  "capabilities":{"tools":{"listChanged":true}}
                }}"""));
        });
        client.initialize();
        // cachedTools() blocks on fut.get(); deliver response on background thread
        // id=2（notifications/initialized 现在用 notify，不占 id）
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            t.deliver(parse("""
                {"jsonrpc":"2.0","id":2,"result":{
                  "tools":[
                    {"name":"create_issue","description":"d","inputSchema":{"type":"object"}},
                    {"name":"list_repos","description":"d","inputSchema":{"type":"object"}}
                  ]
                }}"""));
        });
        client.cachedTools();
        // drain all setup frames
        t.sent.clear();
    }

    @Test
    void initializeNegotiatesAndCachesTools() {
        var tools = client.cachedTools();
        assertEquals(2, tools.size());
        assertEquals("create_issue", tools.get(0).name());
    }

    @Test
    void unsupportedProtocolVersionThrows() throws Exception {
        FakeTransport t2 = new FakeTransport();
        var c = new McpClient(t2, "[x]", Duration.ofSeconds(1));
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            t2.deliver(parse("""
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"1999-01-01",
                  "capabilities":{"tools":{}}}}"""));
        });
        assertThrows(McpProtocolException.class, c::initialize);
    }

    @Test
    void callToolExtractsTextOnly() throws Exception {
        var fut = client.callToolFuture("create_issue", m.readTree("{\"repo\":\"foo\"}"));
        Thread.sleep(50);
        JsonNode outbound = t.sent.poll();
        assertEquals("tools/call", outbound.get("method").asText());
        long id = outbound.get("id").asLong();
        t.deliver(m.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":" + id + ","
            + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"));
        McpCallResult result = fut.get(1, TimeUnit.SECONDS);
        assertEquals("ok", result.text());
        assertFalse(result.isError());
    }

    @Test
    void nonTextContentBecomesPlaceholder() throws Exception {
        var fut = client.callToolFuture("create_issue", m.readTree("{}"));
        Thread.sleep(50);
        JsonNode outbound = t.sent.poll();
        long id = outbound.get("id").asLong();
        t.deliver(m.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
            + "\"content\":[{\"type\":\"image\",\"data\":\"abcdefghijkl\",\"mimeType\":\"image/png\"},"
            + "{\"type\":\"text\",\"text\":\"see image\"}]}}"));
        McpCallResult result = fut.get(1, TimeUnit.SECONDS);
        assertTrue(result.text().contains("see image"));
        assertTrue(result.text().contains("[mcp: image (12 chars, image/png)]"));
    }

    @Test
    void callToolErrorFromServer() throws Exception {
        var fut = client.callToolFuture("create_issue", m.readTree("{}"));
        Thread.sleep(50);
        JsonNode outbound = t.sent.poll();
        long id = outbound.get("id").asLong();
        t.deliver(m.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":" + id + ","
            + "\"error\":{\"code\":-32601,\"message\":\"no such tool\"}}"));
        var ex = assertThrows(Exception.class, () -> fut.get(1, TimeUnit.SECONDS));
        assertInstanceOf(McpProtocolException.class, ex.getCause());
    }
}
