package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void write_new_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("new.txt");
        var args = JSON.createObjectNode().put("path", f.toString()).put("content", "hello\n");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("hello\n", Files.readString(f));
        assertTrue(r.content().contains("wrote"));
    }

    @Test
    void overwrite_existing_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("old.txt");
        Files.writeString(f, "old");
        var args = JSON.createObjectNode().put("path", f.toString()).put("content", "new");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("new", Files.readString(f));
    }

    @Test
    void missing_parent_dir_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode()
            .put("path", tmp.resolve("nope/sub.txt").toString())
            .put("content", "x");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("parent directory"));
    }
}
