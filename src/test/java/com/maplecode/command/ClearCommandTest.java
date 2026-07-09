package com.maplecode.command;

import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClearCommandTest {

    @Test
    void execute_clearsSession_updatesStatusBar() {
        ChatSession session = new ChatSession();
        session.appendUserText("hello");
        assertEquals(1, session.size());

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new ClearCommand(null).execute("", ctx);

        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }
}
