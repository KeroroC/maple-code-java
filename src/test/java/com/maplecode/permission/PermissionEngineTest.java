package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    private static PermissionRequest req() {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"),
            Path.of("/tmp"));
    }

    @Test
    void first_deciding_check_short_circuits() {
        var denyFirst = (PermissionCheck) (r, c) -> Optional.of(Decision.deny("first"));
        var allowSecond = (PermissionCheck) (r, c) -> Optional.of(Decision.allow("second"));
        var engine = new PermissionEngine(List.of(denyFirst, allowSecond), PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.DENY, engine.check(req()).verdict());
    }

    @Test
    void undecided_passes_through_to_next_layer() {
        var undecided = (PermissionCheck) (r, c) -> Optional.empty();
        var allowLast = (PermissionCheck) (r, c) -> Optional.of(Decision.allow("last"));
        var engine = new PermissionEngine(List.of(undecided, undecided, allowLast), PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.ALLOW, engine.check(req()).verdict());
    }

    @Test
    void all_undecided_returns_deny_with_explicit_reason() {
        var undecided = (PermissionCheck) (r, c) -> Optional.empty();
        var engine = new PermissionEngine(List.of(undecided), PermissionMode.DEFAULT);
        var d = engine.check(req());
        assertEquals(Decision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("no decision reached"), d.reason());
    }

    @Test
    void setMode_changes_current_mode() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        assertEquals(PermissionMode.DEFAULT, engine.mode());
        engine.setMode(PermissionMode.PERMISSIVE);
        assertEquals(PermissionMode.PERMISSIVE, engine.mode());
    }

    @Test
    void mode_is_visible_in_context() {
        var capturingCheck = new PermissionCheck() {
            PermissionMode seen;
            @Override public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
                this.seen = ctx.mode();
                return Optional.empty();
            }
        };
        var engine = new PermissionEngine(List.of(capturingCheck), PermissionMode.STRICT);
        engine.check(req());
        assertEquals(PermissionMode.STRICT, capturingCheck.seen);

        engine.setMode(PermissionMode.PERMISSIVE);
        engine.check(req());
        assertEquals(PermissionMode.PERMISSIVE, capturingCheck.seen);
    }

    // --- Task 8: session allow/deny + persist ---

    @Test
    void permit_for_session_adds_to_session_allow_set() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.permitForSession(new ToolCall("exec", "ls"));
        assertTrue(engine.sessionAllowForTest().contains(new ToolCall("exec", "ls")));
    }

    @Test
    void deny_for_session_adds_to_session_deny_set() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.denyForSession(new ToolCall("exec", "rm"));
        assertTrue(engine.sessionDenyForTest().contains(new ToolCall("exec", "rm")));
    }

    @Test
    void persist_project_allow_writes_yaml_entry_to_local_file(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.persistProjectAllow(tmp, "exec", "git status");
        var file = tmp.resolve(".maplecode/permissions.local.yaml");
        assertTrue(java.nio.file.Files.exists(file));
        String content = java.nio.file.Files.readString(file);
        assertTrue(content.contains("rules:"), content);
        assertTrue(content.contains("tool: exec"), content);
        assertTrue(content.contains("git status"), content);
        assertTrue(content.contains("action: allow"), content);
    }

    @Test
    void persist_project_allow_appends_to_existing_file(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.persistProjectAllow(tmp, "exec", "git status");
        engine.persistProjectAllow(tmp, "exec", "ls");
        String content = java.nio.file.Files.readString(tmp.resolve(".maplecode/permissions.local.yaml"));
        long rulesHeaders = content.lines().filter(l -> l.equals("rules:")).count();
        assertEquals(1, rulesHeaders);
        assertTrue(content.contains("git status"));
        assertTrue(content.contains("\"ls\""));
    }

    @Test
    void escape_yaml_string_quotes_special_chars() {
        assertEquals("\"hello \\\"world\\\"\"",
            PermissionEngine.escapeYamlStringForTest("hello \"world\""));
        assertEquals("\"a\\\\b\"", PermissionEngine.escapeYamlStringForTest("a\\b"));
        assertEquals("\"line1\\nline2\"", PermissionEngine.escapeYamlStringForTest("line1\nline2"));
    }
}
