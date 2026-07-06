package com.maplecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP transport 抽象：负责把 JSON-RPC 帧送给对端，把对端送来的帧回调。
 * 不懂协议语义。失败由 close(cause) 把信息传回调用方。
 */
public interface McpTransport extends AutoCloseable {

    /** 把单帧送给对端。返回的 future 在帧写完后完成；失败时异常。 */
    CompletableFuture<Void> send(JsonNode frame);

    /** 注册进站回调；只允许调一次；后续调抛 IllegalStateException。 */
    void onInbound(Consumer<JsonNode> inbound);

    /** 关闭 transport（如 stdio 关子进程、http 取消订阅）。 */
    void close(Throwable cause);
}
