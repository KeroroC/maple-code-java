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

    @Test
    void symlink_file_outside_sandbox_is_skipped(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱外的 symlink 文件
        var outsideDir = tmp.getParent().resolve("outside-grep-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var secretFile = outsideDir.resolve("secret.txt");
        Files.writeString(secretFile, "secret_password=12345");

        // 在沙箱内创建一个 symlink 指向沙箱外的文件
        var symlink = tmp.resolve("secret-link.txt");
        Files.createSymbolicLink(symlink, secretFile);

        // 在沙箱内创建一个正常文件
        var normalFile = tmp.resolve("normal.txt");
        Files.writeString(normalFile, "normal_content");

        // 执行 grep 搜索
        var args = JSON.createObjectNode()
            .put("pattern", "secret|normal")
            .put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));

        // 应该只找到 normal.txt，不包含 secret.txt 的内容
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("normal.txt"), "should find normal.txt");
        assertTrue(r.content().contains("normal_content"), "should find normal_content");
        // 不应该包含沙箱外文件的内容
        assertEquals(false, r.content().contains("secret_password"),
            "should not read symlink pointing outside sandbox");
    }

    @Test
    void symlink_file_inside_sandbox_is_included(@TempDir Path tmp) throws Exception {
        // 创建一个沙箱内的目录和文件
        var innerDir = tmp.resolve("inner");
        Files.createDirectories(innerDir);
        var innerFile = innerDir.resolve("data.txt");
        Files.writeString(innerFile, "inner_data");

        // 创建一个 symlink 指向沙箱内的文件
        var symlink = tmp.resolve("data-link.txt");
        Files.createSymbolicLink(symlink, innerFile);

        // 执行 grep 搜索
        var args = JSON.createObjectNode()
            .put("pattern", "inner_data")
            .put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));

        // 应该找到 symlink 指向的文件内容
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("inner_data"), "should find data via symlink inside sandbox");
    }
}