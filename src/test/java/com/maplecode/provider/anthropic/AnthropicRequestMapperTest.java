package com.maplecode.provider.anthropic;

import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.provider.ThinkingConfig.Effort;
import com.maplecode.provider.ThinkingConfig.Type;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    void minimal_request_no_thinking_no_system() {
        var req = new ChatRequest("claude-sonnet-4-6", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null, null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.anthropic.com", "sk-test", Duration.ofSeconds(30));
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("sk-test", http.headers().firstValue("x-api-key").orElseThrow());
        assertEquals("2023-06-01", http.headers().firstValue("anthropic-version").orElseThrow());
        assertEquals(Duration.ofSeconds(30), http.timeout().orElseThrow(),
            "read timeout should come from the supplied Duration, not a hardcoded 60s");

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4-6\""));
        // content 现在是 [{type:"text",text:"hi"}]
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"),
            "content must be a text block array, body was: " + body);
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"max_tokens\":16384"));
        assertFalse(body.contains("\"system\""), "system null must be absent");
        assertFalse(body.contains("\"thinking\""), "thinking null must be absent");
        assertFalse(body.contains("\"output_config\""), "no thinking means no output_config");
        assertFalse(body.contains("\"tools\""), "no tools means no tools array");
    }

    @Test
    void adaptive_thinking_emits_thinking_and_output_config() {
        var req = new ChatRequest("claude-opus-4-7",
            List.of(new SystemBlock("be terse", false, "user")),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH), null);

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"));
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"));
        assertFalse(body.contains("\"budget_tokens\""), "adaptive must not include budget_tokens");
        assertTrue(body.contains("\"be terse\""));
    }

    @Test
    void enabled_thinking_emits_thinking_with_budget_tokens_only() {
        var req = new ChatRequest("claude-sonnet-4-6", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            new ThinkingConfig(Type.ENABLED, 10000, null), null);

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}"));
        assertFalse(body.contains("\"output_config\""),
            "enabled must not write output_config");
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("claude-sonnet-4-6", List.of(), List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u1"))),
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u2")))
        ), null, null);
        String body = mapper.toJsonBody(req);
        int u1 = body.indexOf("\"u1\"");
        int a1 = body.indexOf("\"a1\"");
        int u2 = body.indexOf("\"u2\"");
        assertTrue(u1 > 0 && a1 > u1 && u2 > a1, "messages must preserve input order");
    }
}
