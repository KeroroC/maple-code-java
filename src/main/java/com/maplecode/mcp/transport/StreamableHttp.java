package com.maplecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class StreamableHttp implements McpTransport {

    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient http;
    private final String url;
    private final Map<String, String> extraHeaders;
    private final ExecutorService sendExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Consumer<JsonNode> inbound;
    private volatile String sessionId;

    public StreamableHttp(HttpClient http, String url, Map<String, String> extraHeaders) {
        this.http = http;
        this.url = url;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-http-send");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onInbound(Consumer<JsonNode> inbound) {
        if (this.inbound != null) throw new IllegalStateException("onInbound already set");
        this.inbound = inbound;
    }

    @Override
    public CompletableFuture<Void> send(JsonNode frame) {
        if (closed.get()) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(new IOException("transport closed"));
            return f;
        }
        return CompletableFuture.runAsync(() -> doSend(frame), sendExecutor);
    }

    private void doSend(JsonNode frame) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(frame.toString()));
            extraHeaders.forEach(b::header);
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
            HttpRequest req = b.build();

            HttpResponse<Stream<String>> resp = http.send(req,
                HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " from " + url);
            }
            captureSessionId(resp.headers());
            String ct = resp.headers().firstValue("content-type").orElse("");
            if (ct.startsWith("text/event-stream")) {
                handleSse(resp);
            } else {
                handleSingleJson(resp.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void captureSessionId(HttpHeaders headers) {
        headers.firstValue("mcp-session-id").ifPresent(v -> sessionId = v);
    }

    private void handleSingleJson(Stream<String> lines) {
        String body = String.join("", (Iterable<String>) lines::iterator);
        try {
            JsonNode parsed = M.readTree(body);
            Consumer<JsonNode> cb = inbound;
            if (cb != null) cb.accept(parsed);
        } catch (Exception e) {
            throw new RuntimeException("bad JSON response", e);
        }
    }

    private void handleSse(HttpResponse<Stream<String>> resp) {
        var reader = new SseStreamReader();
        reader.read(resp, ev -> {
            try {
                JsonNode parsed = M.readTree(ev.data());
                Consumer<JsonNode> cb = inbound;
                if (cb != null) cb.accept(parsed);
            } catch (Exception e) {
                throw new RuntimeException("bad SSE frame: " + ev.data(), e);
            }
        });
    }

    @Override
    public void close(Throwable cause) {
        closed.set(true);
        sendExecutor.shutdownNow();
    }

    @Override
    public void close() {
        close(null);
    }
}
