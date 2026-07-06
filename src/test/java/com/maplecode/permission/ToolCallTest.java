package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {
    @Test
    void stores_tool_and_pattern() {
        var tc = new ToolCall("exec", "git *");
        assertEquals("exec", tc.toolName());
        assertEquals("git *", tc.pattern());
    }

    @Test
    void equality_is_value_based() {
        assertEquals(new ToolCall("exec", "git"), new ToolCall("exec", "git"));
        assertNotEquals(new ToolCall("exec", "git"), new ToolCall("exec", "ls"));
    }

    @Test
    void works_as_set_key() {
        var s = new java.util.HashSet<ToolCall>();
        s.add(new ToolCall("exec", "git"));
        assertTrue(s.contains(new ToolCall("exec", "git")));
    }
}
