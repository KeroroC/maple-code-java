package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobToolTest {

    private final GlobTool tool = new GlobTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void non_recursive_glob(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("a"));
        Files.createDirectory(tmp.resolve("b"));
        Files.writeString(tmp.resolve("a/x.txt"), "");
        Files.writeString(tmp.resolve("a/y.java"), "");
        Files.writeString(tmp.resolve("b/z.java"), "");
        var args = JSON.createObjectNode().put("pattern", "**/*.java");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        // 相对路径，含 2 个 .java
        String[] lines = r.content().split("\n");
        long javaCount = 0;
        for (var l : lines) if (l.endsWith(".java")) javaCount++;
        assertEquals(2, javaCount, "expected 2 .java files, got: " + r.content());
    }

    @Test
    void zero_matches_returns_empty_not_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("pattern", "**/*.nope");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("", r.content());
    }

    @Test
    void truncates_above_max_results(@TempDir Path tmp) throws Exception {
        // 制造 110 个文件
        for (int i = 0; i < 110; i++) {
            Files.writeString(tmp.resolve("f" + i + ".txt"), "");
        }
        // 用 5 个 max
        var ctx = new ToolContext(tmp, 1_048_576, 30, 100, 5);
        var args = JSON.createObjectNode().put("pattern", "*.txt");
        var r = tool.execute(args, ctx);
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("[truncated"), r.content());
    }
}
