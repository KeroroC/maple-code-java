package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckTest {
    @Test
    void anonymous_impl_can_return_decision() {
        PermissionCheck allowAll = (req, ctx) -> Optional.of(Decision.allow("ok"));
        var req = new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"), Path.of("/tmp"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.ALLOW, allowAll.check(req, ctx).orElseThrow().verdict());
    }

    @Test
    void anonymous_impl_can_return_empty_for_undecided() {
        PermissionCheck undecided = (req, ctx) -> Optional.empty();
        var req = new PermissionRequest("exec", new ObjectMapper().createObjectNode(), Path.of("/tmp"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT);
        assertTrue(undecided.check(req, ctx).isEmpty());
    }
}
