package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean ended = false;

    public void reset() {
        ended = false;
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        if (ended) return;
        String data = event.data();
        if (data == null) return;
        if (data.equals("[DONE]")) {
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.STOP));
            ended = true;
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            // 畸形 chunk —— 忽略（传输异常由 SseStreamReader 的 IOException 处理）
            return;
        }

        // 顶层 error 对象
        if (node.has("error")) {
            JsonNode err = node.path("error");
            sink.accept(new StreamChunk.Error(
                err.path("type").asText("unknown"),
                err.path("message").asText("")
            ));
            return;
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode choice0 = choices.get(0);
        JsonNode delta = choice0.path("delta");

        String content = delta.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
            sink.accept(new StreamChunk.TextDelta(content));
        }

        String finish = choice0.path("finish_reason").asText("");
        if (!finish.isEmpty() && !"null".equals(finish)) {
            StreamChunk.StopReason reason = switch (finish) {
                case "stop"   -> StreamChunk.StopReason.STOP;
                case "length" -> StreamChunk.StopReason.MAX_TOKENS;
                case "error"  -> StreamChunk.StopReason.ERROR;
                default       -> StreamChunk.StopReason.STOP;
            };
            sink.accept(new StreamChunk.MessageEnd(reason));
            ended = true;
        }
    }
}
