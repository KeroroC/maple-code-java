package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.permission.Decision;
import com.maplecode.permission.PermissionCheck;
import com.maplecode.permission.PermissionContext;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.permission.PermissionRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorPermissionTest {

    private static Tool mk(String name,
                            java.util.function.BiFunction<JsonNode, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    @Test
    void engine_null_skips_permission_check() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), null);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ran", r.content());
    }

    @Test
    void deny_returns_permission_denied_error_without_calling_tool() {
        AtomicInteger calls = new AtomicInteger();
        var t = mk("foo", (a, c) -> { calls.incrementAndGet(); return ToolResult.ok("ran"); });
        PermissionCheck denyAll = (req, ctx) -> Optional.of(Decision.deny("blocked"));
        var engine = new PermissionEngine(List.of(denyAll), PermissionMode.DEFAULT);
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), engine);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().startsWith("权限拒绝:"), r.content());
        assertTrue(r.content().contains("blocked"), r.content());
        assertEquals(0, calls.get(), "tool should not be called");
    }

    @Test
    void allow_passes_through_to_tool() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        PermissionCheck allowAll = (req, ctx) -> Optional.of(Decision.allow("ok"));
        var engine = new PermissionEngine(List.of(allowAll), PermissionMode.DEFAULT);
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), engine);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ran", r.content());
    }

    @Test
    void single_arg_constructor_still_works_for_backward_compat() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
    }
}
