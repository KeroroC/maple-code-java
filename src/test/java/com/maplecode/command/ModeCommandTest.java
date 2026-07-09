package com.maplecode.command;

import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ModeCommandTest {

    @Test
    void execute_noArgs_printsCurrentMode() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);

        new ModeCommand().execute("", ctx);

        verify(ctx).sendMessage("当前权限模式: default");
    }

    @Test
    void execute_validArg_setsMode() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);

        new ModeCommand().execute("strict", ctx);

        verify(ctx).setPermissionMode(PermissionMode.STRICT);
        verify(ctx).updateStatusBar();
    }

    @Test
    void execute_invalidArg_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new ModeCommand().execute("relaxed", ctx);

        verify(ctx).sendError("未知模式: relaxed。可选: strict, default, permissive");
    }

    @Test
    void execute_caseInsensitive() {
        CommandContext ctx = mock(CommandContext.class);

        new ModeCommand().execute("PERMISSIVE", ctx);

        verify(ctx).setPermissionMode(PermissionMode.PERMISSIVE);
    }
}
