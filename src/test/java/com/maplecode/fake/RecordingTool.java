package com.maplecode.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用 Tool：每次 execute 记录调用，返回预置的 ToolResult。
 * <p>
 * 用于验证 AgentLoop 是否真的调了工具、调了几次、并发时序是否对。
 */
public final class RecordingTool implements Tool {

    private final String name;
    private final ToolResult preset;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public RecordingTool(String name, ToolResult preset) {
        this.name = name;
        this.preset = preset;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return "recording " + name; }

    @Override
    public JsonNode inputSchema() { return null; }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        calls.add(new Call(args, ctx, Thread.currentThread().getName()));
        return preset;
    }

    public List<Call> calls() { return calls; }

    public record Call(JsonNode args, ToolContext ctx, String threadName) {}
}
