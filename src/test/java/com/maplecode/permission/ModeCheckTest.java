package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModeCheckTest {

    private static PermissionRequest req() {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"), Path.of("/tmp"));
    }

    @Test
    void strict_denies_when_no_rule_matched() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.STRICT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("strict"));
    }

    @Test
    void permissive_allows_when_no_rule_matched() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.PERMISSIVE));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("permissive"));
    }

    @Test
    void default_returns_undecided_for_hitl() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.DEFAULT));
        assertTrue(d.isEmpty());
    }
}
