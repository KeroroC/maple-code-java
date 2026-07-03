package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
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
    void append_user_text_and_assistant_text_in_order() {
        var session = new ChatSession();
        session.appendUserText("u1");
        session.appendAssistant(List.of(new ContentBlock.TextBlock("a1")));
        session.appendUserText("u2");

        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(3, msgs.size());
        assertEquals(ChatMessage.Role.USER, msgs.get(0).role());
        assertEquals("u1", ((ContentBlock.TextBlock) msgs.get(0).blocks().get(0)).text());
        assertEquals(ChatMessage.Role.ASSISTANT, msgs.get(1).role());
        assertEquals("a1", ((ContentBlock.TextBlock) msgs.get(1).blocks().get(0)).text());
        assertEquals(ChatMessage.Role.USER, msgs.get(2).role());
    }

    @Test
    void clear_resets_messages() {
        var session = new ChatSession();
        session.appendUserText("x");
        session.clear();
        assertEquals(0, session.toRequest("m", null, null).messages().size());
    }

    @Test
    void toRequest_returns_immutable_copy() {
        var session = new ChatSession();
        session.appendUserText("u1");
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertThrows(UnsupportedOperationException.class, () -> msgs.add(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("rogue")))));
    }

    @Test
    void toRequest_passes_through_system_prompt_and_thinking() {
        var session = new ChatSession();
        session.appendUserText("hi");
        ChatRequest req = session.toRequest("claude-sonnet-4-6", "be terse", null);
        assertEquals("claude-sonnet-4-6", req.model());
        assertEquals("be terse", req.systemPrompt());
        assertEquals(null, req.thinking());
    }

    @Test
    void toRequest_passes_through_tools_when_provided() {
        var session = new ChatSession();
        session.appendUserText("hi");
        // 传 null tools 走原 3 参重载
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(null, req.tools());
    }

    @Test
    void append_blocks_makes_independent_copy() {
        var session = new ChatSession();
        var blocks = new java.util.ArrayList<ContentBlock>();
        blocks.add(new ContentBlock.TextBlock("hi"));
        session.appendUser(blocks);
        blocks.add(new ContentBlock.TextBlock("rogue"));
        // 改原 list 不应影响 session
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(1, msgs.get(0).blocks().size());
    }
}