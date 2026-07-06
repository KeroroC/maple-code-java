package com.maplecode.mcp.client;

import com.maplecode.mcp.config.McpServerSpec;
import com.maplecode.mcp.transport.McpTransport;
import com.maplecode.mcp.transport.Stdio;
import com.maplecode.mcp.transport.StreamableHttp;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 并发启动多个 MCP server，每台独立超时降级——单台失败不影响其他。
 */
public final class McpClientBootstrap {

    private final Function<McpServerSpec, McpTransport> transportFactory;
    private final Duration perServerTimeout;

    public McpClientBootstrap(Duration perServerTimeout) {
        this(McpClientBootstrap::defaultTransport, perServerTimeout);
    }

    public McpClientBootstrap(Function<McpServerSpec, McpTransport> factory,
                              Duration perServerTimeout) {
        this.transportFactory = factory;
        this.perServerTimeout = perServerTimeout;
    }

    public Map<String, McpClient> start(List<McpServerSpec> specs) {
        if (specs.isEmpty()) return Map.of();

        var futures = new LinkedHashMap<String, CompletableFuture<McpClient>>();
        for (var spec : specs) {
            futures.put(spec.name(), CompletableFuture.supplyAsync(() -> tryStart(spec)));
        }

        var all = CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
        try {
            all.get(perServerTimeout.toMillis() + 200, TimeUnit.MILLISECONDS);
        } catch (Exception ignore) {
            // 超时或中断——后续逐个 getNow 收集已完成的
        }

        Map<String, McpClient> out = new LinkedHashMap<>();
        futures.forEach((name, fut) -> {
            try {
                McpClient c = fut.getNow(null);
                if (c != null) {
                    out.put(name, c);
                } else {
                    System.err.println("[mcp:bootstrap] WARN: server '" + name + "' returned null");
                }
            } catch (Exception e) {
                System.err.println("[mcp:bootstrap] WARN: server '" + name + "' failed: " + e.getMessage());
            }
        });
        return out;
    }

    private McpClient tryStart(McpServerSpec spec) {
        McpTransport t;
        try {
            t = transportFactory.apply(spec);
        } catch (Exception e) {
            return null;
        }
        McpClient client = new McpClient(t, "[" + spec.name() + "]", perServerTimeout);
        try {
            client.initialize();
            client.cachedTools();
            return client;
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignore) {}
            return null;
        }
    }

    private static McpTransport defaultTransport(McpServerSpec spec) {
        if (spec instanceof McpServerSpec.Stdio s) {
            try {
                return new Stdio(
                    join(s.command(), s.args()),
                    s.name(),
                    java.nio.file.Path.of("/tmp/mcp-" + s.name() + ".log"));
            } catch (IOException e) {
                throw new RuntimeException("stdio spawn failed for '" + s.name() + "'", e);
            }
        } else if (spec instanceof McpServerSpec.Http h) {
            return new StreamableHttp(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(),
                h.url(), h.headers());
        } else {
            throw new IllegalStateException("unknown spec type: " + spec.getClass());
        }
    }

    private static List<String> join(String cmd, List<String> args) {
        var out = new ArrayList<String>();
        out.add(cmd);
        out.addAll(args);
        return out;
    }
}
