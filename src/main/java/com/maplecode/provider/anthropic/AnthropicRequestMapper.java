package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.prompt.SystemBlock;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AnthropicRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 16384;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey, Duration readTimeout) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/messages"))
            .timeout(readTimeout)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("max_tokens", MAX_TOKENS);
            root.put("stream", true);

            if (!req.systemBlocks().isEmpty()) {
                ArrayNode sysArr = root.putArray("system");
                for (var sb : req.systemBlocks()) {
                    ObjectNode bn = sysArr.addObject();
                    bn.put("type", "text");
                    bn.put("text", sb.content());
                    if (sb.cacheBoundary()) {
                        ObjectNode cc = bn.putObject("cache_control");
                        cc.put("type", "ephemeral");
                    }
                }
            }

            ArrayNode msgs = root.putArray("messages");
            for (var m : req.messages()) {
                msgs.add(encodeMessage(m));
            }

            if (req.thinking() != null) {
                ThinkingConfig tc = req.thinking();
                ObjectNode thinking = root.putObject("thinking");
                switch (tc.type()) {
                    case ADAPTIVE -> {
                        thinking.put("type", "adaptive");
                        ObjectNode outputConfig = root.putObject("output_config");
                        outputConfig.put("effort", tc.effort().name().toLowerCase());
                    }
                    case ENABLED -> {
                        thinking.put("type", "enabled");
                        thinking.put("budget_tokens", tc.budgetTokens());
                    }
                }
            }

            // tools 数组 —— Task 17 完整实现；本任务先留接口位置
            if (req.tools() != null && !req.tools().isEmpty()) {
                ArrayNode toolsArr = root.putArray("tools");
                for (var tool : req.tools()) {
                    ObjectNode tn = toolsArr.addObject();
                    tn.put("name", tool.name());
                    tn.put("description", tool.description());
                    tn.set("input_schema", tool.inputSchema());
                }
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize Anthropic request", e);
        }
    }

    private ObjectNode encodeMessage(com.maplecode.provider.ChatMessage m) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", m.role().name().toLowerCase());

        ArrayNode content = msg.putArray("content");
        for (var block : m.blocks()) {
            if (block instanceof ContentBlock.TextBlock tb) {
                content.addObject().put("type", "text").put("text", tb.text());
            } else if (block instanceof ContentBlock.ToolUseBlock tu) {
                ObjectNode b = content.addObject();
                b.put("type", "tool_use");
                b.put("id", tu.id());
                b.put("name", tu.name());
                b.set("input", tu.input());
            } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                ObjectNode b = content.addObject();
                b.put("type", "tool_result");
                b.put("tool_use_id", tr.toolUseId());
                b.put("content", tr.content());
                b.put("is_error", tr.isError());
            }
        }
        return msg;
    }
}
