package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {

    private final GrepTool tool = new GrepTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void basic_regex_match(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "alpha\nbeta\ngamma\n");
        var args = JSON.createObjectNode().put("pattern", "be").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("beta"), r.content());
        assertTrue(r.content().contains("a.txt"), r.content());
        assertTrue(r.content().contains("2:"), r.content());
    }

    @Test
    void zero_matches_returns_empty(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "alpha\n");
        var args = JSON.createObjectNode().put("pattern", "zzz").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("", r.content());
    }

    @Test
    void invalid_regex_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("pattern", "[unclosed").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("invalid regex"));
    }

    @Test
    void include_glob_filters_files(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "needle\n");
        Files.writeString(tmp.resolve("a.md"), "needle\n");
        var args = JSON.createObjectNode()
            .put("pattern", "needle")
            .put("path", tmp.toString())
            .put("include_glob", "*.txt");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("a.txt"), r.content());
        assertEquals(false, r.content().contains("a.md"), r.content());
    }

    @Test
    void binary_file_is_skipped(@TempDir Path tmp) throws Exception {
        byte[] data = new byte[200];
        data[50] = 0;
        Files.write(tmp.resolve("bin.dat"), data);
        Files.writeString(tmp.resolve("clean.txt"), "needle\n");
        var args = JSON.createObjectNode()
            .put("pattern", "needle")
            .put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("clean.txt"), r.content());
        assertEquals(false, r.content().contains("bin.dat"), r.content());
    }

    @Test
    void truncates_above_max_results(@TempDir Path tmp) throws Exception {
        // 110 个匹配行
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 110; i++) big.append("needle\n");
        Files.writeString(tmp.resolve("a.txt"), big.toString());
        var ctx = new ToolContext(tmp, 1_048_576, 30, 5, 100);
        var args = JSON.createObjectNode().put("pattern", "needle").put("path", tmp.toString());
        var r = tool.execute(args, ctx);
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("[truncated"), r.content());
    }
}