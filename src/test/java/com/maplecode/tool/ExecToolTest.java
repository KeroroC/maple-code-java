package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecToolTest {

    private final ExecTool tool = new ExecTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void simple_echo_succeeds(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "echo hello");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("hello"), r.content());
    }

    @Test
    void non_zero_exit_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "exit 7");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("exit=7"), r.content());
    }

    @Test
    void empty_command_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "   ");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("empty"));
    }

    @Test
    void timeout_kills_long_command(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "sleep 5").put("timeout_seconds", 1);
        long start = System.currentTimeMillis();
        var r = tool.execute(args, ToolContext.defaults(tmp));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("timeout"), r.content());
        assertTrue(elapsed < 4000, "should not wait full 5s; took " + elapsed + "ms");
    }
}
