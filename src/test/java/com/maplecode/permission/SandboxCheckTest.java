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
        assertTrue(d.get().reason().contains("sandbox"), d.get().reason());
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
}
