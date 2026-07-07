package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionReplaceAllTest {

    @Test
    void replaceAllReplacesMessages() {
        var session = new ChatSession();
        session.appendUserText("u1");
        session.appendAssistant(List.of(new ContentBlock.TextBlock("a1")));
        var newMessages = List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("replaced"))));
        session.replaceAll(newMessages);
        var req = session.toRequest("m", List.of(), null, List.of());
        assertEquals(1, req.messages().size());
        assertEquals("replaced",
            ((ContentBlock.TextBlock) req.messages().get(0).blocks().get(0)).text());
    }

    @Test
    void appendAfterReplaceStillWorks() {
        var session = new ChatSession();
        session.replaceAll(List.of());
        session.appendUserText("new");
        var req = session.toRequest("m", List.of(), null, List.of());
        assertEquals(1, req.messages().size());
    }

    @Test
    void clearAfterReplaceRestoresEmpty() {
        var session = new ChatSession();
        session.replaceAll(List.of(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x")))));
        session.clear();
        assertEquals(0, session.toRequest("m", List.of(), null, List.of()).messages().size());
    }

    @Test
    void replaceAllDefensiveCopy() {
        var session = new ChatSession();
        var backing = new ArrayList<ChatMessage>();
        backing.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("a"))));
        session.replaceAll(backing);
        backing.add(new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("sneaky"))));
        assertEquals(1, session.toRequest("m", List.of(), null, List.of()).messages().size());
    }
}
