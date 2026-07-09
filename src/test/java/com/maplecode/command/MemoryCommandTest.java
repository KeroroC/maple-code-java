package com.maplecode.command;

import com.maplecode.memory.MemoryManager;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class MemoryCommandTest {

    @Test
    void execute_list_callsListMemories() {
        MemoryManager mgr = mock(MemoryManager.class);
        when(mgr.listMemories()).thenReturn("memory1\nmemory2");
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("list", ctx);

        verify(mgr).listMemories();
        verify(ctx).sendMessage("memory1\nmemory2");
    }

    @Test
    void execute_clear_callsClearAll() {
        MemoryManager mgr = mock(MemoryManager.class);
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("clear", ctx);

        verify(mgr).clearAll();
    }

    @Test
    void execute_extract_callsExtractSync() {
        MemoryManager mgr = mock(MemoryManager.class);
        ChatSession session = new ChatSession();
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new MemoryCommand(mgr).execute("extract", ctx);

        verify(mgr).extractSync(anyList());
    }

    @Test
    void execute_unknownSubcommand_sendsError() {
        MemoryManager mgr = mock(MemoryManager.class);
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("foo", ctx);

        verify(ctx).sendError(anyString());
    }

    @Test
    void execute_nullManager_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(null).execute("list", ctx);

        verify(ctx).sendError(anyString());
    }
}
