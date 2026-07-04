package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean ended = false;
    private final Map<Integer, ToolAcc> toolAccs = new LinkedHashMap<>();

    private static class ToolAcc {
        String id;
        String name;
        boolean started;
        StringBuilder args = new StringBuilder();
    }

    public void reset() {
        ended = false;
        toolAccs.clear();
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        if (ended) return;
        String data = event.data();
        if (data == null) return;
        if (data.equals("[DONE]")) {
            flushTools(sink);
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.STOP));
            ended = true;
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            return;
        }

        if (node.has("error")) {
            JsonNode err = node.path("error");
            sink.accept(new StreamChunk.Error(
                err.path("type").asText("unknown"),
                err.path("message").asText("")));
            return;
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode choice0 = choices.get(0);
        JsonNode delta = choice0.path("delta");

        // 普通文本
        String content = delta.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
            sink.accept(new StreamChunk.TextDelta(content));
        }

        // tool_calls
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (var tc : toolCalls) {
                int idx = tc.path("index").asInt(0);
                ToolAcc acc = toolAccs.computeIfAbsent(idx, k -> new ToolAcc());

                String id = tc.path("id").asText(null);
                if (id != null && !id.isEmpty() && acc.id == null) {
                    acc.id = id;
                }
                String name = tc.path("function").path("name").asText(null);
                if (name != null && !name.isEmpty()) {
                    acc.name = name;
                }
                // Emit ToolUseStart as soon as we have id + name, so the REPL
                // can display the tool name during streaming (matches Anthropic behaviour).
                if (acc.id != null && acc.name != null && !acc.started) {
                    sink.accept(new StreamChunk.ToolUseStart(acc.id, acc.name));
                    acc.started = true;
                }
                String args = tc.path("function").path("arguments").asText(null);
                if (args != null && !args.isEmpty() && acc.id != null) {
                    acc.args.append(args);
                    sink.accept(new StreamChunk.ToolUseDelta(acc.id, args));
                }
            }
        }

        String finish = choice0.path("finish_reason").asText("");
        if (!finish.isEmpty() && !"null".equals(finish)) {
            // 在发 MessageEnd 之前先把所有累积的工具 flush
            if (finish.equals("tool_calls")) {
                flushTools(sink);
            }
            StreamChunk.StopReason reason = switch (finish) {
                case "stop"        -> StreamChunk.StopReason.STOP;
                case "length"      -> StreamChunk.StopReason.MAX_TOKENS;
                case "error"       -> StreamChunk.StopReason.ERROR;
                case "tool_calls"  -> StreamChunk.StopReason.TOOL_USE;
                default            -> StreamChunk.StopReason.STOP;
            };
            sink.accept(new StreamChunk.MessageEnd(reason));
            ended = true;
        }
    }

    private void flushTools(Consumer<StreamChunk> sink) {
        for (var entry : toolAccs.entrySet()) {
            ToolAcc acc = entry.getValue();
            if (acc.id == null) continue;  // 没 id 视为无效
            // 发 Start（如果流式阶段还没发过）
            if (!acc.started) {
                sink.accept(new StreamChunk.ToolUseStart(acc.id, acc.name == null ? "" : acc.name));
            }
            JsonNode input;
            try {
                input = acc.args.length() == 0
                    ? JSON.createObjectNode()
                    : JSON.readTree(acc.args.toString());
            } catch (Exception e) {
                sink.accept(new StreamChunk.Error("tool_input_invalid",
                    "tool input not valid JSON: " + e.getMessage()));
                continue;
            }
            sink.accept(new StreamChunk.ToolUseEnd(acc.id, acc.name == null ? "" : acc.name, input));
        }
        toolAccs.clear();
    }
}
