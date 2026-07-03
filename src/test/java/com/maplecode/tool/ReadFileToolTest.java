package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void read_full_small_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("hello.txt");
        Files.writeString(f, "line1\nline2\nline3\n");
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals("   1\tline1\n   2\tline2\n   3\tline3\n", r.content());
        assertEquals(false, r.isError());
    }

    @Test
    void read_with_offset_and_limit(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("multi.txt");
        Files.writeString(f, "a\nb\nc\nd\ne\n");
        var args = JSON.createObjectNode().put("path", f.toString()).put("offset", 1).put("limit", 2);
        var r = tool.execute(args, ToolContext.defaults(tmp));
        // offset=1 是 0-indexed 即从第 2 行开始；limit=2 拿 b, c
        assertTrue(r.content().contains("b"));
        assertTrue(r.content().contains("c"));
        assertEquals(false, r.isError());
    }

    @Test
    void missing_file_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("path", tmp.resolve("nope.txt").toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("nope.txt"));
    }

    @Test
    void binary_file_rejected(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bin.dat");
        byte[] data = new byte[100];
        data[50] = 0;  // NUL in first 100 bytes
        Files.write(f, data);
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("binary"));
    }

    @Test
    void truncates_large_file_with_marker(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("big.txt");
        // 写入 2 MiB
        byte[] data = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(data, (byte) 'x');
        Files.write(f, data);
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().endsWith("[truncated]"), "expected truncation marker, got tail: "
            + r.content().substring(r.content().length() - 50));
    }
}
