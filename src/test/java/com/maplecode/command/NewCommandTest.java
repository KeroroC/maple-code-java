package com.maplecode.command;

import com.maplecode.session.ChatSession;
import com.maplecode.session.archive.SessionArchive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewCommandTest {

    @Test
    void execute_archivesThenClears() {
        SessionArchive archive = mock(SessionArchive.class);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new NewCommand(archive, null).execute("", ctx);

        verify(archive).save(session);
        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }

    @Test
    void execute_nullArchive_skipsArchiving() {
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new NewCommand(null, null).execute("", ctx);

        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }
}
