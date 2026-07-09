package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {

    // -- isCommand --

    @Test
    void isCommand_empty_returnsFalse() {
        assertFalse(CommandParser.isCommand(""));
    }

    @Test
    void isCommand_noSlash_returnsFalse() {
        assertFalse(CommandParser.isCommand("hello"));
    }

    @Test
    void isCommand_slashOnly_returnsFalse() {
        assertFalse(CommandParser.isCommand("/"));
    }

    @Test
    void isCommand_slashWithSpace_returnsFalse() {
        assertFalse(CommandParser.isCommand("/ help"));
    }

    @Test
    void isCommand_slashName_returnsTrue() {
        assertTrue(CommandParser.isCommand("/help"));
    }

    @Test
    void isCommand_slashNameArgs_returnsTrue() {
        assertTrue(CommandParser.isCommand("/review 重点关注"));
    }

    // -- parseName --

    @Test
    void parseName_noArgs() {
        assertEquals("help", CommandParser.parseName("/help"));
    }

    @Test
    void parseName_withArgs() {
        assertEquals("review", CommandParser.parseName("/review 重点关注"));
    }

    @Test
    void parseName_uppercase_lowercased() {
        assertEquals("help", CommandParser.parseName("/HELP"));
    }

    @Test
    void parseName_mixedCase_lowercased() {
        assertEquals("memory", CommandParser.parseName("/Memory List"));
    }

    @Test
    void parseName_slashOnly_returnsEmpty() {
        assertEquals("", CommandParser.parseName("/"));
    }

    // -- parseArgs --

    @Test
    void parseArgs_noArgs_returnsEmpty() {
        assertEquals("", CommandParser.parseArgs("/help"));
    }

    @Test
    void parseArgs_withArgs() {
        assertEquals("重点关注并发", CommandParser.parseArgs("/review 重点关注并发"));
    }

    @Test
    void parseArgs_multipleSpaces_trimmed() {
        assertEquals("分析代码", CommandParser.parseArgs("/plan  分析代码"));
    }

    @Test
    void parseArgs_subcommand() {
        assertEquals("list", CommandParser.parseArgs("/memory list"));
    }
}
