package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcTest {

    private JsonRpc rpc;
    private ObjectMapper m = new ObjectMapper();
    private java.util.concurrent.atomic.AtomicReference<JsonNode> sent;

    @BeforeEach
    void setUp() {
        sent = new java.util.concurrent.atomic.AtomicReference<>();
        rpc = new JsonRpc(json -> { sent.set(json); return CompletableFuture.completedFuture(null); },
                          Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() { rpc.close(); }

    @Test
    void sendReturnsResponseFuture() throws Exception {
        JsonNode reqParams = m.readTree("{}");
        var fut = rpc.send("tools/list", reqParams);
        JsonNode sentJson = sent.get();
        assertNotNull(sentJson);
        assertEquals("tools/list", sentJson.get("method").asText());
        // 模拟对端回包
        rpc.handle(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        JsonNode result = fut.get(1, TimeUnit.SECONDS);
        assertTrue(result.isObject());
    }

    @Test
    void handleErrorCompletesExceptionally() throws Exception {
        var fut = rpc.send("tools/list", null);
        rpc.handle(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"error\":{\"code\":-32601,\"message\":\"oops\"}}"));
        var ex = assertThrows(ExecutionException.class, () -> fut.get(1, TimeUnit.SECONDS));
        assertInstanceOf(McpProtocolException.class, ex.getCause());
    }

    @Test
    void timeoutCleansUpPending() throws Exception {
        var shortRpc = new JsonRpc(j -> CompletableFuture.completedFuture(null),
                                    Duration.ofMillis(50));
        try {
            var fut = shortRpc.send("slow", null);
            var ex = assertThrows(ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
            assertInstanceOf(McpTimeoutException.class, ex.getCause());
            // 再发同 id 不应回包到旧 future
            shortRpc.handle(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
            assertTrue(fut.isCompletedExceptionally());
        } finally {
            shortRpc.close();
        }
    }

    @Test
    void unknownIdIgnored() throws Exception {
        rpc.handle(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}"));
        Thread.sleep(50);
    }

    @Test
    void notificationWithoutIdIgnored() throws Exception {
        rpc.handle(m.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"foo\"}"));
        Thread.sleep(50);
    }

    @Test
    void concurrentSendsPairById() throws Exception {
        int N = 50;
        var futs = new java.util.ArrayList<CompletableFuture<JsonNode>>();
        for (int i = 0; i < N; i++) futs.add(rpc.send("m" + i, null));
        for (int i = N - 1; i >= 0; i--) {
            rpc.handle(m.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":" + (i + 1) + ",\"result\":{\"i\":" + i + "}}"));
        }
        for (int i = 0; i < N; i++) {
            JsonNode r = futs.get(i).get(1, TimeUnit.SECONDS);
            assertEquals(i, r.get("i").asInt());
        }
    }
}
