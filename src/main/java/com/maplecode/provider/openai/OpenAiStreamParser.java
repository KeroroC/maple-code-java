package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean ended = false;
    private TokenUsage lastUsage;
    private StreamChunk.StopReason pendingReason;
    private final Map<Integer, ToolAcc> toolAccs = new LinkedHashMap<>();

    private static class ToolAcc {
        String id;
        String name;
        boolean started;
        StringBuilder args = new StringBuilder();
    }

    public void reset() {
        ended = false;
        lastUsage = null;
        pendingReason = null;
        toolAccs.clear();
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        String data = event.data();
        if (data == null) return;

        // [DONE] 结束信号：flush 工具 + 发 MessageEnd
        if (data.equals("[DONE]")) {
            if (ended) return;
            flushTools(sink);
            sink.accept(new StreamChunk.MessageEnd(
                pendingReason != null ? pendingReason : StreamChunk.StopReason.STOP,
                lastUsage));
            ended = true;
            return;
        }

        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            return;
        }

        // 提取 usage（OpenAI 在最后一个 chunk 里带 choices:[] + usage:{...}）
        // 必须在 ended 检查之前，因为 usage chunk 可能在 finish_reason 之后到达
        JsonNode usage = node.path("usage");
        if (!usage.isMissingNode() && usage.has("prompt_tokens")) {
            lastUsage = TokenUsage.of(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
        }

        if (ended) return;

        if (node.has("error")) {
            JsonNode err = node.path("error");
            sink.accept(new StreamChunk.Error(
                err.path("type").asText("unknown"),
                err.path("message").asText("")));
            return;
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // choices 为空的 usage chunk：如果已有 pendingReason 则发出 MessageEnd
            if (pendingReason != null) {
                flushTools(sink);
                sink.accept(new StreamChunk.MessageEnd(pendingReason, lastUsage));
                ended = true;
            }
            return;
        }
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
                // 一旦获得 id + name 就发出 ToolUseStart，使 REPL
                // 能在流式过程中显示工具名称（与 Anthropic 行为一致）。
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
            pendingReason = switch (finish) {
                case "stop"        -> StreamChunk.StopReason.STOP;
                case "length"      -> StreamChunk.StopReason.MAX_TOKENS;
                case "error"       -> StreamChunk.StopReason.ERROR;
                case "tool_calls"  -> StreamChunk.StopReason.TOOL_USE;
                default            -> StreamChunk.StopReason.STOP;
            };
            // 不立即发 MessageEnd —— 等 usage chunk 或 [DONE] 携带 TokenUsage
        }
    }

    /**
     * Stream 结束后调用。如果 pendingReason 存在但还没 emit MessageEnd
     * （比如流被截断、没收到 [DONE]），这里兜底 emit。
     */
    public void finish(Consumer<StreamChunk> sink) {
        if (!ended && pendingReason != null) {
            flushTools(sink);
            sink.accept(new StreamChunk.MessageEnd(pendingReason, lastUsage));
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
                    "工具输入不是有效的 JSON: " + e.getMessage()));
                continue;
            }
            sink.accept(new StreamChunk.ToolUseEnd(acc.id, acc.name == null ? "" : acc.name, input));
        }
        toolAccs.clear();
    }
}
