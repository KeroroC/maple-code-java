package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExitCommandTest {

    @Test
    void execute_throwsExitReplException() {
        ExitCommand cmd = new ExitCommand();
        assertThrows(ExitReplException.class, () -> cmd.execute("", null));
    }

    @Test
    void name_is_exit() {
        assertEquals("exit", new ExitCommand().name());
    }

    @Test
    void type_is_local() {
        assertEquals(CommandType.LOCAL, new ExitCommand().type());
    }

    @Test
    void notHidden() {
        assertFalse(new ExitCommand().hidden());
    }
}
