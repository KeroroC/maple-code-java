package com.maplecode.provider.openai;

import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
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
    void minimal_request_with_user_text() {
        var req = new ChatRequest("gpt-5", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null, null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.openai.com/v1", "sk-test", Duration.ofSeconds(30));
        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("Bearer sk-test", http.headers().firstValue("authorization").orElseThrow());
        assertEquals(Duration.ofSeconds(30), http.timeout().orElseThrow());

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"gpt-5\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertFalse(body.contains("\"thinking\""));
        assertFalse(body.contains("\"tools\""), "no tools means no tools array");
    }

    @Test
    void system_prompt_emits_system_role_message() {
        var req = new ChatRequest("gpt-5",
            List.of(new SystemBlock("be terse", false, "user")),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"system\",\"content\":\"be terse\""));
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("gpt-5", List.of(), List.of(
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
