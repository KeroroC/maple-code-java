package com.maplecode.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.mcp.rpc.JsonRpc;
import com.maplecode.mcp.rpc.McpProtocolException;
import com.maplecode.mcp.transport.McpTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class McpClient {

    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
        Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private final McpTransport transport;
    private final String serverName;
    private final ObjectMapper m = new ObjectMapper();
    private final JsonRpc jsonRpc;

    private volatile List<McpToolDesc> cachedTools = List.of();

    public McpClient(McpTransport transport, String serverName,
                     Duration defaultTimeout) {
        this.transport = transport;
        this.serverName = serverName;
        this.jsonRpc = new JsonRpc(
            frame -> transport.send(frame),
            defaultTimeout);
        this.transport.onInbound(jsonRpc::handle);
    }

    public void initialize() {
        JsonNode initParams;
        try {
            initParams = m.readTree("""
                {"protocolVersion":"2025-06-18",
                 "capabilities":{},
                 "clientInfo":{"name":"maplecode","version":"0.1.0"}}""");
        } catch (Exception e) { throw new IllegalStateException(e); }
        var fut = jsonRpc.send("initialize", initParams);
        try {
            JsonNode result = fut.get(5, TimeUnit.SECONDS);
            String protocolVersion = textOr(result, "protocolVersion", null);
            if (!SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion))
                throw new McpProtocolException(-32000,
                    "unsupported protocol version: " + protocolVersion);
            JsonNode caps = result.get("capabilities");
            boolean hasTools = caps != null && caps.has("tools");
            if (!hasTools)
                throw new McpProtocolException(-32000, "server doesn't expose tools capability");
            jsonRpc.notify("notifications/initialized", m.createObjectNode());
        } catch (Exception e) {
            throw new McpProtocolException(-32000, "initialize failed: " + e.getMessage(), e);
        }
    }

    public List<McpToolDesc> cachedTools() {
        if (!cachedTools.isEmpty()) return cachedTools;
        var fut = jsonRpc.send("tools/list", null);
        try {
            JsonNode result = fut.get(5, TimeUnit.SECONDS);
            JsonNode arr = result.get("tools");
            List<McpToolDesc> descs = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (var n : arr) {
                    descs.add(new McpToolDesc(
                        n.get("name").asText(),
                        n.path("description").asText(""),
                        n.get("inputSchema")));
                }
            }
            this.cachedTools = List.copyOf(descs);
            return cachedTools;
        } catch (Exception e) {
            throw new McpProtocolException(-32000, "tools/list failed: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<McpCallResult> callToolFuture(String name, JsonNode arguments) {
        var params = m.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null ? arguments : m.createObjectNode());
        var fut = jsonRpc.send("tools/call", params);
        return fut.handle((result, err) -> {
            if (err != null) {
                if (err instanceof CompletionException ce && ce.getCause() != null)
                    throw new CompletionException(ce.getCause());
                throw new CompletionException(err);
            }
            return extract(result);
        });
    }

    private McpCallResult extract(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (var c : content) {
                String type = c.path("type").asText("");
                switch (type) {
                    case "text" -> sb.append(c.get("text").asText());
                    case "image", "audio" -> {
                        String data = c.path("data").asText("");
                        sb.append("[mcp: ").append(type)
                          .append(" (").append(data.length()).append(" chars");
                        String mime = c.path("mimeType").asText("");
                        if (!mime.isEmpty()) sb.append(", ").append(mime);
                        sb.append(")]");
                    }
                    case "resource" -> sb.append("[mcp: embedded resource]")
                          .append(c.path("uri").asText(""));
                    default -> sb.append("[mcp: unknown content type=").append(type).append("]");
                }
                if (sb.length() > 0 && !"text".equals(type))
                    sb.append("\n");
            }
        }
        return new McpCallResult(sb.toString(), isError);
    }

    public String name() { return serverName; }

    public void close() {
        try { transport.close(null); } catch (Exception e) {
            System.err.println("[mcp:" + serverName + "] WARN: transport close failed: " + e.getMessage());
        }
        jsonRpc.close();
    }

    private static String textOr(JsonNode n, String f, String def) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null ? def : v.asText();
    }
}
