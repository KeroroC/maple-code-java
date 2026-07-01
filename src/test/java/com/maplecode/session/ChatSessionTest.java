package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatSessionTest {

    @Test
    void empty_session_toRequest_has_empty_messages() {
        var session = new ChatSession();
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(0, req.messages().size());
    }

    @Test
    void append_user_then_assistant_in_order() {
        var session = new ChatSession();
        session.appendUser("u1");
        session.appendAssistant("a1");
        session.appendUser("u2");

        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(3, msgs.size());
        assertEquals(ChatMessage.Role.USER, msgs.get(0).role());
        assertEquals("u1", msgs.get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, msgs.get(1).role());
        assertEquals("a1", msgs.get(1).content());
        assertEquals(ChatMessage.Role.USER, msgs.get(2).role());
        assertEquals("u2", msgs.get(2).content());
    }

    @Test
    void clear_resets_messages() {
        var session = new ChatSession();
        session.appendUser("x");
        session.clear();
        assertEquals(0, session.toRequest("m", null, null).messages().size());
    }

    @Test
    void toRequest_returns_immutable_copy() {
        var session = new ChatSession();
        session.appendUser("u1");
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertThrows(UnsupportedOperationException.class, () -> msgs.add(
            new ChatMessage(ChatMessage.Role.USER, "rogue")));
    }

    @Test
    void toRequest_passes_through_system_prompt_and_thinking() {
        var session = new ChatSession();
        session.appendUser("hi");
        ChatRequest req = session.toRequest("claude-sonnet-4-6", "be terse", null);
        assertEquals("claude-sonnet-4-6", req.model());
        assertEquals("be terse", req.systemPrompt());
        assertEquals(null, req.thinking());
    }
}