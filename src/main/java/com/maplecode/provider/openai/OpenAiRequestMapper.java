package com.maplecode.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.prompt.SystemBlock;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class OpenAiRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey, Duration readTimeout) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .timeout(readTimeout)
            .header("content-type", "application/json")
            .header("authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("stream", true);

            // 请求 OpenAI 在流式末尾返回 usage 统计
            ObjectNode streamOptions = root.putObject("stream_options");
            streamOptions.put("include_usage", true);

            // thinking 静默丢弃 —— v1 的 Chat Completions 没有这个字段
            // （后续可接到 o1 的 reasoning_effort）

            ArrayNode msgs = root.putArray("messages");
            if (!req.systemBlocks().isEmpty()) {
                String joined = req.systemBlocks().stream()
                    .map(SystemBlock::content)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining("\n\n"));
                if (!joined.isBlank()) {
                    msgs.add(JSON.createObjectNode()
                        .put("role", "system")
                        .put("content", joined));
                }
            }
            for (var m : req.messages()) {
                for (ObjectNode om : encodeMessages(m)) {
                    msgs.add(om);
                }
            }

            // tools 数组 —— Task 18 完整实现
            if (req.tools() != null && !req.tools().isEmpty()) {
                ArrayNode toolsArr = root.putArray("tools");
                for (var tool : req.tools()) {
                    ObjectNode t = toolsArr.addObject();
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tool.name());
                    fn.put("description", tool.description());
                    fn.set("parameters", tool.inputSchema());
                }
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 OpenAI 请求失败", e);
        }
    }

    /**
     * OpenAI 编码方式（一条内部 ChatMessage 可能展开为多条 OpenAI 消息）：
     * - USER + TextBlock  → role=user, content=string
     * - USER + ToolResultBlock(s) → 每个 ToolResultBlock 独立编码为 role=tool 消息
     * - ASSISTANT + TextBlock → role=assistant, content=string
     * - ASSISTANT + ToolUseBlock → role=assistant, content=null, tool_calls=[{id,type:function,function:{name,arguments}}]
     *
     * 返回空列表表示该消息应当跳过（不该发生的边界情况）。
     */
    private List<ObjectNode> encodeMessages(ChatMessage m) {
        var blocks = m.blocks();

        if (m.role() == ChatMessage.Role.USER) {
            // 检查是不是 tool_result（可能有多个）
            if (!blocks.isEmpty() && blocks.stream().allMatch(b -> b instanceof ContentBlock.ToolResultBlock)) {
                var result = new ArrayList<ObjectNode>();
                for (var b : blocks) {
                    ContentBlock.ToolResultBlock tr = (ContentBlock.ToolResultBlock) b;
                    ObjectNode msg = JSON.createObjectNode();
                    msg.put("role", "tool");
                    msg.put("content", tr.content());
                    msg.put("tool_call_id", tr.toolUseId());
                    result.add(msg);
                }
                return result;
            }
            // 普通 user 消息：拼接 TextBlock 为 string
            ObjectNode msg = JSON.createObjectNode();
            msg.put("role", "user");
            StringBuilder sb = new StringBuilder();
            for (var b : blocks) {
                if (b instanceof ContentBlock.TextBlock tb) sb.append(tb.text());
            }
            msg.put("content", sb.toString());
            return List.of(msg);
        }

        // ASSISTANT
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "assistant");
        StringBuilder textBuf = new StringBuilder();
        ArrayNode toolCalls = null;
        for (var b : blocks) {
            if (b instanceof ContentBlock.TextBlock tb) {
                textBuf.append(tb.text());
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                if (toolCalls == null) toolCalls = msg.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", tu.id());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", tu.name());
                fn.put("arguments", tu.input() == null ? "{}" : tu.input().toString());
            }
        }
        if (textBuf.length() > 0) msg.put("content", textBuf.toString());
        else msg.putNull("content");
        return List.of(msg);
    }
}
