package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class HelpCommandTest {

    @Test
    void execute_noArgs_printsGroupedHelp() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new ModeCommand());

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("", ctx);

        verify(ctx, atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void execute_withArg_printsUsage() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("exit", ctx);

        verify(ctx).sendMessage(argThat(s -> s.contains("/exit") && s.contains("退出程序")));
    }

    @Test
    void execute_unknownCommand_sendsError() {
        CommandRegistry reg = new CommandRegistry();

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("nonexistent", ctx);

        verify(ctx).sendError(argThat(s -> s.contains("nonexistent")));
    }
}
