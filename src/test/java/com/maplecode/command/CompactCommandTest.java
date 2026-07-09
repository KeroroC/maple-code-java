package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;
import com.maplecode.provider.ChatMessage;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompactCommandTest {

    @Test
    void execute_changed_replacesSession() {
        CompactCoordinator coord = mock(CompactCoordinator.class);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");
        session.appendAssistantText("world");
        assertEquals(2, session.size());

        List<ChatMessage> newMessages = List.of();
        CompactCoordinator.CompactOutcome outcome =
            new CompactCoordinator.CompactOutcome(
                new CompactResult.ChangedOffloadOnly(3), newMessages);
        when(coord.beforeRequest(any(), any(CompactTrigger.class), any()))
            .thenReturn(outcome);
        when(coord.lastSeenUsage()).thenReturn(null);

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new CompactCommand(coord).execute("", ctx);

        // replaceAll with empty list should clear the session
        assertEquals(0, session.size());
    }

    @Test
    void execute_noop_doesNotReplaceSession() {
        CompactCoordinator coord = mock(CompactCoordinator.class);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");
        assertEquals(1, session.size());

        CompactCoordinator.CompactOutcome outcome =
            new CompactCoordinator.CompactOutcome(
                new CompactResult.Noop(), null);
        when(coord.beforeRequest(any(), any(CompactTrigger.class), any()))
            .thenReturn(outcome);
        when(coord.lastSeenUsage()).thenReturn(null);

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new CompactCommand(coord).execute("", ctx);

        // Noop should not replace session
        assertEquals(1, session.size());
    }
}
