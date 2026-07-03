package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unique_match_replaces(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "hello world\nbye world\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "hello world")
            .put("new_string", "hi world");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("hi world\nbye world\n", Files.readString(f));
    }

    @Test
    void zero_matches_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "hello world\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "missing")
            .put("new_string", "x");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("not found"));
    }

    @Test
    void multiple_matches_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "x\nx\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "x")
            .put("new_string", "y");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("matches 2"));
    }

    @Test
    void noop_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "abc\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "abc")
            .put("new_string", "abc");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("no-op"));
    }

    @Test
    void missing_file_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode()
            .put("path", tmp.resolve("nope.txt").toString())
            .put("old_string", "x")
            .put("new_string", "y");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("not found"));
    }
}