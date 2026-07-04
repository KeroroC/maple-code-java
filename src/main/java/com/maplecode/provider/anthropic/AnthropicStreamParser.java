package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;

import java.util.function.Consumer;

public final class AnthropicStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean errored = false;
    private BlockType currentBlock = BlockType.NONE;
    private String lastStopReason = null;
    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;
    private String currentToolUseId = null;
    private String currentToolName = null;
    private StringBuilder currentToolJson = new StringBuilder();

    private enum BlockType { NONE, THINKING, TEXT, TOOL_USE }

    public void reset() {
        errored = false;
        currentBlock = BlockType.NONE;
        lastStopReason = null;
        lastInputTokens = 0;
        lastOutputTokens = 0;
        currentToolUseId = null;
        currentToolName = null;
        currentToolJson.setLength(0);
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        if (errored) return;
        String type = event.eventType();
        if (type.equals("message_start")) {
            currentBlock = BlockType.NONE;
            lastStopReason = null;
            lastInputTokens = 0;
            lastOutputTokens = 0;
            currentToolUseId = null;
            currentToolName = null;
            currentToolJson.setLength(0);
            JsonNode node = parse(event.data());
            lastInputTokens = node.path("message").path("usage").path("input_tokens").asInt(0);
            sink.accept(new StreamChunk.MessageStart());
            return;
        }
        if (type.equals("content_block_start")) {
            JsonNode node = parse(event.data());
            String blockType = node.path("content_block").path("type").asText("");
            currentBlock = switch (blockType) {
                case "thinking" -> BlockType.THINKING;
                case "text"     -> BlockType.TEXT;
                case "tool_use" -> {
                    currentToolUseId = node.path("content_block").path("id").asText("");
                    currentToolName = node.path("content_block").path("name").asText("");
                    currentToolJson.setLength(0);
                    sink.accept(new StreamChunk.ToolUseStart(currentToolUseId, currentToolName));
                    yield BlockType.TOOL_USE;
                }
                default -> BlockType.NONE;
            };
            return;
        }
        if (type.equals("content_block_delta")) {
            JsonNode node = parse(event.data());
            JsonNode delta = node.path("delta");
            String deltaType = delta.path("type").asText("");
            if (deltaType.equals("thinking_delta")) {
                sink.accept(new StreamChunk.ThinkingDelta(delta.path("thinking").asText("")));
            } else if (deltaType.equals("text_delta")) {
                sink.accept(new StreamChunk.TextDelta(delta.path("text").asText("")));
            } else if (deltaType.equals("input_json_delta")) {
                String partial = delta.path("partial_json").asText("");
                if (currentToolUseId != null) {
                    currentToolJson.append(partial);
                    sink.accept(new StreamChunk.ToolUseDelta(currentToolUseId, partial));
                }
            }
            return;
        }
        if (type.equals("content_block_stop")) {
            if (currentBlock == BlockType.TOOL_USE && currentToolUseId != null) {
                JsonNode input;
                try {
                    input = JSON.readTree(currentToolJson.toString());
                } catch (Exception e) {
                    sink.accept(new StreamChunk.Error("tool_input_invalid",
                        "tool input not valid JSON: " + e.getMessage()));
                    input = JSON.createObjectNode();
                }
                sink.accept(new StreamChunk.ToolUseEnd(currentToolUseId, currentToolName, input));
                currentToolUseId = null;
                currentToolName = null;
                currentToolJson.setLength(0);
            }
            currentBlock = BlockType.NONE;
            return;
        }
        if (type.equals("message_delta")) {
            JsonNode node = parse(event.data());
            String stopReason = node.path("delta").path("stop_reason").asText(null);
            if (stopReason != null && !stopReason.isEmpty()) {
                lastStopReason = stopReason;
            }
            int outputTokens = node.path("usage").path("output_tokens").asInt(0);
            if (outputTokens > 0) {
                lastOutputTokens = outputTokens;
            }
            return;
        }
        if (type.equals("message_stop")) {
            TokenUsage usage = (lastInputTokens == 0 && lastOutputTokens == 0)
                ? null
                : new TokenUsage(lastInputTokens, lastOutputTokens);
            sink.accept(new StreamChunk.MessageEnd(mapStopReason(lastStopReason), usage));
            return;
        }
        if (type.equals("error")) {
            JsonNode node = parse(event.data());
            String code = node.path("error").path("type").asText("unknown");
            String msg = node.path("error").path("message").asText("");
            sink.accept(new StreamChunk.Error(code, msg));
            errored = true;
            return;
        }
        // ping / 未知 —— 忽略
    }

    private static StreamChunk.StopReason mapStopReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return StreamChunk.StopReason.END_TURN;
        }
        return switch (reason) {
            case "end_turn"      -> StreamChunk.StopReason.END_TURN;
            case "max_tokens"    -> StreamChunk.StopReason.MAX_TOKENS;
            case "stop_sequence" -> StreamChunk.StopReason.STOP;
            case "tool_use"      -> StreamChunk.StopReason.TOOL_USE;
            default              -> StreamChunk.StopReason.STOP;
        };
    }

    private JsonNode parse(String data) {
        try {
            return JSON.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse Anthropic SSE data: " + data, e);
        }
    }
}
