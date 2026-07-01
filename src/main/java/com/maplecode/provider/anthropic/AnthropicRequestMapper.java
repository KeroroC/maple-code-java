package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;

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

            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                root.put("system", req.systemPrompt());
            }

            ArrayNode msgs = root.putArray("messages");
            for (var m : req.messages()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", m.role().name().toLowerCase())
                    .put("content", m.content()));
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

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize Anthropic request", e);
        }
    }
}