package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.provider.TokenUsage;
import com.maplecode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单轮流式响应的累加器。
 * <p>
 * "双路"：一边把 chunk 同步转发到 sink（让 UI 实时看到），一边把
 * 完整状态（text / toolUses / stopReason / usage）累加到字段，
 * 供 AgentLoop 在 iteration 结束后决策。
 */
public final class ResponseCollector implements Consumer<StreamChunk> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringBuilder text = new StringBuilder();
    private final List<ToolUse> toolUses = new ArrayList<>();
    private final StringBuilder pendingJson = new StringBuilder();
    private final Consumer<AgentEvent> sink;
    private final ToolRegistry registry;
    private String pendingId;
    private String pendingName;
    private StopReason stopReason;
    private TokenUsage usage;
    private boolean errored;

    public ResponseCollector(Consumer<AgentEvent> sink, ToolRegistry registry) {
        this.sink = sink;
        this.registry = registry;
    }

    @Override
    public void accept(StreamChunk chunk) {
        switch (chunk) {
            case StreamChunk.TextDelta d -> {
                text.append(d.text());
                sink.accept(new AgentEvent.TextDelta(d.text()));
            }
            case StreamChunk.ThinkingDelta d -> {
                sink.accept(new AgentEvent.ThinkingDelta(d.text()));
            }
            case StreamChunk.ToolUseStart d -> {
                pendingId = d.id();
                pendingName = d.name();
                pendingJson.setLength(0);
                sink.accept(new AgentEvent.ToolCallStart(d.id(), d.name(), argSummary(d.name())));
            }
            case StreamChunk.ToolUseDelta d -> {
                pendingId = d.id();
                pendingJson.append(d.partialJson());
            }
            case StreamChunk.ToolUseEnd d -> {
                toolUses.add(new ToolUse(d.id(), d.name(), d.input()));
                sink.accept(new AgentEvent.ToolCallEnd(d.id(), d.name(), d.input()));
                pendingId = pendingName = null;
                pendingJson.setLength(0);
            }
            case StreamChunk.MessageStart s -> { /* no-op */ }
            case StreamChunk.MessageEnd e -> {
                stopReason = e.reason();
                usage = e.usage();
            }
            case StreamChunk.Error e -> {
                errored = true;
                stopReason = StopReason.ERROR;
            }
        }
    }

    /** 实时从 partialJson 抽 path/command/pattern，否则截前 40 字符。 */
    String argSummary(String toolName) {
        String partial = pendingJson.toString();
        if (partial.isEmpty()) return "";
        try {
            JsonNode node = JSON.readTree(partial);
            var path = node.path("path");
            if (!path.isMissingNode()) return path.asText();
            var cmd = node.path("command");
            if (!cmd.isMissingNode()) return cmd.asText();
            var pattern = node.path("pattern");
            if (!pattern.isMissingNode()) return pattern.asText();
        } catch (Exception ignored) {}
        return partial.length() > 40 ? partial.substring(0, 40) + "..." : partial;
    }

    public StringBuilder text() { return text; }
    public List<ToolUse> toolUses() { return toolUses; }
    public StopReason stopReason() { return stopReason; }
    public TokenUsage usage() { return usage; }
    public boolean errored() { return errored; }

    /** 测试用：暴露 pendingJson 给测试。 */
    StringBuilder pendingJsonForTest() { return pendingJson; }

    public record ToolUse(String id, String name, JsonNode input) {}
}
