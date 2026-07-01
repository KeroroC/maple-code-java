package com.maplecode.provider.openai;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
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

class OpenAiRequestMapperTest {

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();

    @Test
    void minimal_request_emits_bearer_header() {
        var req = new ChatRequest("gpt-4o", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.openai.com/v1", "sk-test", Duration.ofSeconds(45));
        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("Bearer sk-test", http.headers().firstValue("authorization").orElseThrow());
        assertEquals(Duration.ofSeconds(45), http.timeout().orElseThrow(),
            "read timeout should come from the supplied Duration, not a hardcoded 60s");

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"gpt-4o\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertFalse(body.contains("\"thinking\""));
        assertFalse(body.contains("\"reasoning\""));
    }

    @Test
    void system_prompt_appears_first_in_messages_array() {
        var req = new ChatRequest("gpt-4o", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        String body = mapper.toJsonBody(req);
        int systemIdx = body.indexOf("\"role\":\"system\"");
        int userIdx = body.indexOf("\"role\":\"user\"");
        assertTrue(systemIdx > 0 && userIdx > systemIdx,
            "system 消息必须排在 user 前面");
        assertTrue(body.contains("\"content\":\"be terse\""));
    }

    @Test
    void thinking_is_silently_dropped_for_openai() {
        var req = new ChatRequest("gpt-4o", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH));

        String body = mapper.toJsonBody(req);
        assertFalse(body.contains("thinking"));
        assertFalse(body.contains("reasoning"));
        assertFalse(body.contains("output_config"));
    }
}