package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class AnthropicStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockType currentBlock = BlockType.NONE;

    private enum BlockType { NONE, THINKING, TEXT }

    public void reset() {
        currentBlock = BlockType.NONE;
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        String type = event.eventType();
        if (type.equals("message_start")) {
            currentBlock = BlockType.NONE;
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
        if (type.equals("message_stop")) {
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN));
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

    private JsonNode parse(String data) {
        try {
            return JSON.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse Anthropic SSE data: " + data, e);
        }
    }
}