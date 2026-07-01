package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class AnthropicStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockType currentBlock = BlockType.NONE;
    private String lastStopReason = null;

    private enum BlockType { NONE, THINKING, TEXT }

    public void reset() {
        currentBlock = BlockType.NONE;
        lastStopReason = null;
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        String type = event.eventType();
        if (type.equals("message_start")) {
            currentBlock = BlockType.NONE;
            lastStopReason = null;
            sink.accept(new StreamChunk.MessageStart());
            return;
        }
        if (type.equals("content_block_start")) {
            JsonNode node = parse(event.data());
            String blockType = node.path("content_block").path("type").asText("");
            currentBlock = switch (blockType) {
                case "thinking" -> BlockType.THINKING;
                case "text"     -> BlockType.TEXT;
                default         -> BlockType.NONE;
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
            }
            return;
        }
        if (type.equals("content_block_stop")) {
            currentBlock = BlockType.NONE;
            return;
        }
        if (type.equals("message_delta")) {
            // Anthropic 在单独的 message_delta 事件里给出最终的 stop_reason
            // （可能在 content_block_stop 之后、message_stop 之前到达）。
            JsonNode node = parse(event.data());
            String stopReason = node.path("delta").path("stop_reason").asText(null);
            if (stopReason != null && !stopReason.isEmpty()) {
                lastStopReason = stopReason;
            }
            return;
        }
        if (type.equals("message_stop")) {
            sink.accept(new StreamChunk.MessageEnd(mapStopReason(lastStopReason)));
            return;
        }
        if (type.equals("error")) {
            JsonNode node = parse(event.data());
            String code = node.path("error").path("type").asText("unknown");
            String msg = node.path("error").path("message").asText("");
            sink.accept(new StreamChunk.Error(code, msg));
            return;
        }
        // ping / 未知 —— 忽略
    }

    private static StreamChunk.StopReason mapStopReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            // 没看到 message_delta —— 按 Anthropic 的"自然结束"处理
            return StreamChunk.StopReason.END_TURN;
        }
        return switch (reason) {
            case "end_turn"      -> StreamChunk.StopReason.END_TURN;
            case "max_tokens"    -> StreamChunk.StopReason.MAX_TOKENS;
            case "stop_sequence" -> StreamChunk.StopReason.STOP;
            case "tool_use"      -> StreamChunk.StopReason.ERROR;  // v1 不支持 tool use
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