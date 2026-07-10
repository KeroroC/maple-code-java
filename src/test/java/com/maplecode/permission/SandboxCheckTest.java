package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCheckTest {

    private static PermissionContext ctx(Path cwd) {
        return new PermissionContext(PermissionMode.DEFAULT);
    }

    private static PermissionRequest req(String tool, String pathField, String value) {
        var args = new ObjectMapper().createObjectNode();
        if (value != null) args.put(pathField, value);
        return new PermissionRequest(tool, args, Path.of("/tmp"));
    }

    @Test
    void relative_path_inside_sandbox_allowed(@TempDir Path tmp) throws Exception {
        var inner = tmp.resolve("foo.txt");
        Files.writeString(inner, "hi");
        var sandbox = new SandboxCheck(tmp);
        var r = req("read_file", "path", "foo.txt");
        var realReq = new PermissionRequest("read_file", r.args(), tmp);
        assertTrue(sandbox.check(realReq, ctx(tmp)).isEmpty());
    }

    @Test
    void absolute_path_outside_sandbox_denied(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "/etc/passwd"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("沙箱"), d.get().reason());
    }

    @Test
    void dotdot_traversal_denied(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("../escape.txt"), "x");
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "../escape.txt"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void symlink_escape_denied(@TempDir Path tmp) throws Exception {
        var outsideDir = tmp.getParent().resolve("outside-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var real = outsideDir.resolve("secret.txt");
        Files.writeString(real, "secret");
        var link = tmp.resolve("link.txt");
        Files.createSymbolicLink(link, real);

        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "link.txt"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "symlink resolves outside sandbox must deny");
    }

    @Test
    void nonexistent_path_returns_undecided(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "does-not-exist.txt"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty(),
            "nonexistent path not sandbox's concern -- let tool layer report file not found");
    }

    @Test
    void exec_tool_skips_sandbox(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls /etc"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty());
    }

    @Test
    void glob_pattern_outside_sandbox_denied(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("glob",
            new ObjectMapper().createObjectNode().put("pattern", "../*"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void grep_default_path_inside_sandbox_undecided(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("grep",
            new ObjectMapper().createObjectNode().put("pattern", "foo"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty());
    }

    @Test
    void grep_symlink_path_outside_sandbox_denied(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱外的 symlink 目录
        var outsideDir = tmp.getParent().resolve("outside-grep-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var link = tmp.resolve("grep-link");
        Files.createSymbolicLink(link, outsideDir);

        var sandbox = new SandboxCheck(tmp);
        var args = new ObjectMapper().createObjectNode()
            .put("pattern", "secret")
            .put("path", "grep-link");
        var r = new PermissionRequest("grep", args, tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "grep symlink path resolves outside sandbox must deny");
    }

    @Test
    void grep_symlink_path_inside_sandbox_allowed(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱内的 symlink 目录
        var innerDir = tmp.resolve("inner");
        Files.createDirectories(innerDir);
        var link = tmp.resolve("grep-link-inner");
        Files.createSymbolicLink(link, innerDir);

        var sandbox = new SandboxCheck(tmp);
        var args = new ObjectMapper().createObjectNode()
            .put("pattern", "test")
            .put("path", "grep-link-inner");
        var r = new PermissionRequest("grep", args, tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty(),
            "grep symlink path inside sandbox should be allowed");
    }

    @Test
    void write_file_parent_symlink_outside_sandbox_denied(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱外的 symlink 目录
        var outsideDir = tmp.getParent().resolve("outside-write-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var link = tmp.resolve("write-link");
        Files.createSymbolicLink(link, outsideDir);

        var sandbox = new SandboxCheck(tmp);
        // 目标文件不存在，但父目录是 symlink 指向沙箱外
        var r = new PermissionRequest("write_file",
            new ObjectMapper().createObjectNode()
                .put("path", "write-link/new-file.txt")
                .put("content", "test"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "write_file via symlink parent outside sandbox must deny");
    }

    @Test
    void write_file_parent_symlink_inside_sandbox_allowed(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱内的 symlink 目录
        var innerDir = tmp.resolve("inner-write");
        Files.createDirectories(innerDir);
        var link = tmp.resolve("write-link-inner");
        Files.createSymbolicLink(link, innerDir);

        var sandbox = new SandboxCheck(tmp);
        // 目标文件不存在，但父目录是 symlink 指向沙箱内
        var r = new PermissionRequest("write_file",
            new ObjectMapper().createObjectNode()
                .put("path", "write-link-inner/new-file.txt")
                .put("content", "test"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty(),
            "write_file via symlink parent inside sandbox should be allowed");
    }

    @Test
    void read_file_parent_symlink_outside_sandbox_denied(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱外的 symlink 目录
        var outsideDir = tmp.getParent().resolve("outside-read-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var link = tmp.resolve("read-link");
        Files.createSymbolicLink(link, outsideDir);

        var sandbox = new SandboxCheck(tmp);
        // 目标文件不存在，但父目录是 symlink 指向沙箱外
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "read-link/nonexistent.txt"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "read_file via symlink parent outside sandbox must deny");
    }

    @Test
    void edit_file_parent_symlink_outside_sandbox_denied(@TempDir Path tmp) throws Exception {
        // 创建一个指向沙箱外的 symlink 目录
        var outsideDir = tmp.getParent().resolve("outside-edit-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var link = tmp.resolve("edit-link");
        Files.createSymbolicLink(link, outsideDir);

        var sandbox = new SandboxCheck(tmp);
        // 目标文件不存在，但父目录是 symlink 指向沙箱外
        var r = new PermissionRequest("edit_file",
            new ObjectMapper().createObjectNode()
                .put("path", "edit-link/nonexistent.txt")
                .put("old_string", "old")
                .put("new_string", "new"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "edit_file via symlink parent outside sandbox must deny");
    }
}
