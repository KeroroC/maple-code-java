package com.maplecode.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

            // thinking 静默丢弃 —— v1 的 Chat Completions 没有这个字段
            // （后续可接到 o1 的 reasoning_effort）

            ArrayNode msgs = root.putArray("messages");
            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", "system")
                    .put("content", req.systemPrompt()));
            }
            for (var m : req.messages()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", m.role().name().toLowerCase())
                    .put("content", m.content()));
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize OpenAI request", e);
        }
    }
}