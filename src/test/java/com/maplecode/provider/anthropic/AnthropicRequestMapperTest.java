package com.maplecode.provider.anthropic;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.provider.ThinkingConfig.Effort;
import com.maplecode.provider.ThinkingConfig.Type;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    void minimal_request_no_thinking_no_system() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.anthropic.com", "sk-test");
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("sk-test", http.headers().firstValue("x-api-key").orElseThrow());
        assertEquals("2023-06-01", http.headers().firstValue("anthropic-version").orElseThrow());

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4-6\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"max_tokens\":16384"));
        assertFalse(body.contains("\"system\""), "system null must be absent");
        assertFalse(body.contains("\"thinking\""), "thinking null must be absent");
        assertFalse(body.contains("\"output_config\""), "no thinking means no output_config");
    }

    @Test
    void adaptive_thinking_emits_thinking_and_output_config() {
        var req = new ChatRequest("claude-opus-4-7", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"));
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"));
        assertFalse(body.contains("\"budget_tokens\""), "adaptive must not include budget_tokens");
        assertTrue(body.contains("\"system\":\"be terse\""));
    }

    @Test
    void enabled_thinking_emits_thinking_with_budget_tokens_only() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ENABLED, 10000, null));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}"));
        assertFalse(body.contains("\"output_config\""),
            "enabled must not write output_config");
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("claude-sonnet-4-6", null, List.of(
            new ChatMessage(ChatMessage.Role.USER, "u1"),
            new ChatMessage(ChatMessage.Role.ASSISTANT, "a1"),
            new ChatMessage(ChatMessage.Role.USER, "u2")
        ), null);
        String body = mapper.toJsonBody(req);
        int u1 = body.indexOf("\"u1\"");
        int a1 = body.indexOf("\"a1\"");
        int u2 = body.indexOf("\"u2\"");
        assertTrue(u1 > 0 && a1 > u1 && u2 > a1, "messages must preserve input order");
    }
}