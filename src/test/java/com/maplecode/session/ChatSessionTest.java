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
    void toRequest_3arg_passes_null_tools() {
        var session = new ChatSession();
        session.appendUserText("hi");
        // 3 参重载固定传 null
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(null, req.tools());
    }

    @Test
    void toRequest_4arg_passes_tools_through() {
        var session = new ChatSession();
        session.appendUserText("hi");
        // 4 参重载把 tools 透传；null 也接受
        ChatRequest req = session.toRequest("m", null, null, null);
        assertEquals(null, req.tools());
        // 4 参重载把空列表也透传
        ChatRequest req2 = session.toRequest("m", null, null, List.of());
        assertEquals(List.of(), req2.tools());
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