package com.maplecode.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 异步配对器：把 "send" 与 "handle" 桥接起来，按 id 配对回 future。
 * 不做 IO；IO 由 transport 完成，transport 把每条 JSON 帧反序列化后回调 handle。
 */
public final class JsonRpc implements AutoCloseable {

    private final Function<JsonNode, CompletableFuture<Void>> sink;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending =
        new ConcurrentHashMap<>();
    private final Duration defaultTimeout;
    private final ScheduledExecutorService timer;
    private volatile boolean closed = false;

    public JsonRpc(Function<JsonNode, CompletableFuture<Void>> sink,
                   Duration defaultTimeout) {
        this.sink = sink;
        this.defaultTimeout = defaultTimeout;
        this.timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "mcp-jsonrpc-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public Duration defaultTimeout() { return defaultTimeout; }

    public CompletableFuture<JsonNode> send(String method, JsonNode params) {
        if (closed) throw new IllegalStateException("JsonRpc closed");
        long id = nextId.getAndIncrement();
        var req = new JsonRpcRequest(id, method, params);
        JsonNode wire;
        try { wire = mapper.valueToTree(req); }
        catch (Exception e) { throw new RuntimeException(e); }
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        timer.schedule(() -> {
            var f = pending.remove(id);
            if (f != null && !f.isDone())
                f.completeExceptionally(new McpTimeoutException(
                    "mcp call '" + method + "' (id=" + id + ") timed out after " + defaultTimeout.toMillis() + "ms"));
        }, defaultTimeout.toMillis(), TimeUnit.MILLISECONDS);
        sink.apply(wire);
        return fut;
    }

    /**
     * 发送 JSON-RPC notification（无 id，不期待响应，不注册 pending）。
     * 按 JSON-RPC 规范，notification 不会被服务端回包。
     */
    public void notify(String method, JsonNode params) {
        if (closed) throw new IllegalStateException("JsonRpc closed");
        JsonNode wire;
        try {
            wire = mapper.valueToTree(new JsonRpcNotification(method, params));
        } catch (Exception e) { throw new RuntimeException(e); }
        sink.apply(wire);
    }

    public void handle(JsonNode frame) {
        if (frame == null || !frame.isObject()) return;
        JsonNode idNode = frame.get("id");
        JsonNode methodNode = frame.get("method");
        if (idNode == null && methodNode != null) {
            // notification from server → ignore (V1)
            return;
        }
        if (idNode == null) return;
        long id = idNode.asLong();
        var fut = pending.remove(id);
        if (fut == null) return;   // 超时 / 启动期外 / 异常 id
        if (frame.has("error")) {
            JsonNode err = frame.get("error");
            int code = err.has("code") ? err.get("code").asInt() : 0;
            String msg = err.has("message") ? err.get("message").asText() : "(no message)";
            fut.completeExceptionally(new McpProtocolException(code, msg));
        } else {
            fut.complete(frame.get("result"));
        }
    }

    public void failAllPending(Throwable cause) {
        pending.forEach((id, fut) -> {
            if (pending.remove(id, fut)) fut.completeExceptionally(cause);
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        timer.shutdownNow();
        failAllPending(new McpConnectionException("JsonRpc closed"));
    }
}
