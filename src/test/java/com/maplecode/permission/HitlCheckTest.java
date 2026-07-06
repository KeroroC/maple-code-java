package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class HitlCheckTest {

    private static class StubInput implements InputSource {
        final Queue<String> q;
        StubInput(String... lines) { this.q = new ArrayDeque<>(List.of(lines)); }
        @Override public String readLine(String prompt) {
            String s = q.poll();
            if (s == null) throw new IllegalStateException("no more input");
            return s;
        }
    }

    private static class CapturingOutput implements OutputSink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(String line) { lines.add(line); }
    }

    private static PermissionRequest req(String command) {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", command), Path.of("/tmp"));
    }

    @Test
    void choice_1_allows_once_no_engine_call() {
        var out = new CapturingOutput();
        var in = new StubInput("1");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("ls"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(engine.sessionAllowForTest().isEmpty());
    }

    @Test
    void choice_2_adds_to_session_allow() {
        var out = new CapturingOutput();
        var in = new StubInput("2");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("ls"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(engine.sessionAllowForTest().contains(new ToolCall("exec", "ls")));
    }

    @Test
    void choice_4_denies() {
        var out = new CapturingOutput();
        var in = new StubInput("4");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("rm"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("user denied"));
    }

    @Test
    void invalid_choice_denies() {
        var out = new CapturingOutput();
        var in = new StubInput("9");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("rm"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("invalid choice"));
    }

    @Test
    void session_allow_short_circuits_prompt() {
        var out = new CapturingOutput();
        var in = new StubInput();
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.permitForSession(new ToolCall("exec", "ls"));
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var ctx = new PermissionContext(PermissionMode.DEFAULT,
            engine.sessionAllowForTest(), engine.sessionDenyForTest());
        var d = hitl.check(req("ls"), ctx);
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(out.lines.isEmpty());
    }

    @Test
    void session_deny_short_circuits_prompt() {
        var out = new CapturingOutput();
        var in = new StubInput();
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.denyForSession(new ToolCall("exec", "rm"));
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var ctx = new PermissionContext(PermissionMode.DEFAULT,
            engine.sessionAllowForTest(), engine.sessionDenyForTest());
        var d = hitl.check(req("rm"), ctx);
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("session deny"));
    }

    @Test
    void prompt_is_printed_with_tool_args_and_mode() {
        var out = new CapturingOutput();
        var in = new StubInput("4");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        hitl.check(req("rm -rf /tmp/foo"), new PermissionContext(PermissionMode.DEFAULT));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("permission required")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("exec")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("rm -rf /tmp/foo")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("DEFAULT")));
    }
}
