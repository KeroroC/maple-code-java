package com.maplecode.command;

import com.maplecode.session.ChatSession;
import com.maplecode.session.archive.SessionArchive;
import com.maplecode.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewCommandTest {

    @Test
    void execute_archivesThenClears() {
        SessionArchive archive = mock(SessionArchive.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new NewCommand(archive, null, skillRegistry).execute("", ctx);

        verify(archive).save(session);
        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
        verify(skillRegistry).deactivateAll();
    }

    @Test
    void execute_nullArchive_skipsArchiving() {
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        new NewCommand(null, null, skillRegistry).execute("", ctx);

        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
        verify(skillRegistry).deactivateAll();
    }
}
