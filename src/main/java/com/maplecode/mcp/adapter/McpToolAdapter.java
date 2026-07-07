package com.maplecode.mcp.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.mcp.client.McpCallResult;
import com.maplecode.mcp.client.McpClient;
import com.maplecode.mcp.client.McpToolDesc;
import com.maplecode.mcp.rpc.McpConnectionException;
import com.maplecode.mcp.rpc.McpProtocolException;
import com.maplecode.mcp.rpc.McpTimeoutException;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class McpToolAdapter {

    private McpToolAdapter() {}

    public static Tool of(McpClient client, McpToolDesc desc) {
        String synthetic = synthName(client.name(), desc.name());
        String description = desc.description();
        JsonNode schema = desc.inputSchema();
        return new Tool() {
            @Override public String name() { return synthetic; }
            @Override public String description() { return description; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
                try {
                    McpCallResult r = client.callToolFuture(desc.name(), args)
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                    return r.isError()
                        ? ToolResult.error(r.text())
                        : ToolResult.ok(r.text());
                } catch (TimeoutException e) {
                    return ToolResult.error("mcp[" + client.name() + ":" + desc.name()
                        + "] call timed out");
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    if (c instanceof McpConnectionException)
                        return ToolResult.error("mcp[" + client.name() + "] connection lost");
                    if (c instanceof McpProtocolException mpe)
                        return ToolResult.error("mcp[" + client.name() + ":" + desc.name()
                            + "] server error: " + mpe.getMessage()
                            + " (code " + mpe.code() + ")");
                    if (c instanceof McpTimeoutException)
                        return ToolResult.error("mcp[" + client.name() + ":" + desc.name()
                            + "] call timed out");
                    return ToolResult.error("mcp[" + client.name() + ":" + desc.name() + "] "
                        + c.getClass().getSimpleName() + ": " + c.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("mcp[" + client.name() + ":" + desc.name()
                        + "] interrupted");
                }
            }
        };
    }

    private static String synthName(String clientName, String toolName) {
        String server = clientName;
        if (server.startsWith("[") && server.endsWith("]"))
            server = server.substring(1, server.length() - 1);
        return "mcp__" + server + "__" + toolName;
    }

}
