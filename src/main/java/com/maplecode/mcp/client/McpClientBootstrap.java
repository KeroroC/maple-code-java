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
 *
 * @param perCallTimeout 单次 JsonRpc 调用超时（initialize / tools/list）
 * @param globalStartDeadline 全部 server 启动的 wall-clock 上限
 */
public final class McpClientBootstrap {

    private final Function<McpServerSpec, McpTransport> transportFactory;
    private final Duration perCallTimeout;
    private final Duration globalStartDeadline;

    public McpClientBootstrap(Duration perCallTimeout) {
        this(McpClientBootstrap::defaultTransport, perCallTimeout,
             perCallTimeout.multipliedBy(2));
    }

    public McpClientBootstrap(Function<McpServerSpec, McpTransport> factory,
                              Duration perCallTimeout) {
        this(factory, perCallTimeout, perCallTimeout.multipliedBy(2));
    }

    public McpClientBootstrap(Function<McpServerSpec, McpTransport> factory,
                              Duration perCallTimeout,
                              Duration globalStartDeadline) {
        this.transportFactory = factory;
        this.perCallTimeout = perCallTimeout;
        this.globalStartDeadline = globalStartDeadline;
    }

    public Map<String, McpClient> start(List<McpServerSpec> specs) {
        if (specs.isEmpty()) return Map.of();

        var futures = new LinkedHashMap<String, CompletableFuture<McpClient>>();
        for (var spec : specs) {
            futures.put(spec.name(), CompletableFuture.supplyAsync(() -> tryStart(spec)));
        }

        var all = CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
        try {
            all.get(globalStartDeadline.toMillis(), TimeUnit.MILLISECONDS);
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
            System.err.println("[mcp:bootstrap] WARN: server '" + spec.name()
                + "' transport creation failed: " + e.getMessage());
            return null;
        }
        McpClient client = new McpClient(t, "[" + spec.name() + "]", perCallTimeout);
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
        return switch (spec) {
            case McpServerSpec.Stdio s -> {
                try {
                    yield new Stdio(
                        join(s.command(), s.args()),
                        s.name(),
                        java.nio.file.Path.of("/tmp/mcp-" + s.name() + ".log"));
                } catch (IOException e) {
                    throw new RuntimeException("stdio spawn failed for '" + s.name() + "'", e);
                }
            }
            case McpServerSpec.Http h -> new StreamableHttp(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(),
                h.url(), h.headers());
        };
    }

    private static List<String> join(String cmd, List<String> args) {
        var out = new ArrayList<String>();
        out.add(cmd);
        out.addAll(args);
        return out;
    }
}
