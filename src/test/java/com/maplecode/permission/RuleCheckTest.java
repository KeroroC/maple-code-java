package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuleCheckTest {

    private static PermissionRequest req(String tool, String field, String value) {
        var args = new ObjectMapper().createObjectNode();
        args.put(field, value);
        return new PermissionRequest(tool, args, Path.of("/tmp"));
    }

    private static PermissionContext ctx() {
        return new PermissionContext(PermissionMode.DEFAULT);
    }

    @Test
    void exec_shell_glob_matches_multi_word_command() {
        var rs = new RuleSet(List.of(new Rule("exec", "git *", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "git status"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void exec_exact_match() {
        var rs = new RuleSet(List.of(new Rule("exec", "ls -la", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "ls -la"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void exec_glob_does_not_match_unrelated_command() {
        var rs = new RuleSet(List.of(new Rule("exec", "git *", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "rm foo"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void path_glob_matches_against_read_file_path() {
        var rs = new RuleSet(List.of(new Rule("read_file", "src/**", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("read_file", "path", "src/main/Foo.java"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void first_match_wins_local_overrides_project() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls", Rule.Action.DENY),
            new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "ls"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void unmatched_returns_undecided() {
        var rs = new RuleSet(List.of(new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "rm"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void no_matching_tool_returns_undecided() {
        var rs = new RuleSet(List.of(new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("read_file", "path", "foo"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void shell_glob_helper_translates_star() {
        assertTrue(RuleCheck.shellGlobMatch("git *", "git status"));
        assertTrue(RuleCheck.shellGlobMatch("git *", "git"));
        assertFalse(RuleCheck.shellGlobMatch("git *", "rm"));
        assertTrue(RuleCheck.shellGlobMatch("ls ?", "ls -la"));
    }

    @Test
    void shell_glob_escapes_regex_metachars() {
        assertTrue(RuleCheck.shellGlobMatch("echo a.b", "echo a.b"));
        assertFalse(RuleCheck.shellGlobMatch("echo a.b", "echo aXb"));
    }
}
