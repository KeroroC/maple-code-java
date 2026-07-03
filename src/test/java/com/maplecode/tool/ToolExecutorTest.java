package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.error.ToolException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private static Tool mk(String name, java.util.function.BiFunction<JsonNode, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    private final ToolContext ctx = ToolContext.defaults(java.nio.file.Path.of("/tmp"));

    @Test
    void run_executes_and_returns_ok() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ok-result"));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ok-result", r.content());
    }

    @Test
    void run_unknown_tool_returns_error_with_available_list() {
        var t = mk("alpha", (a, c) -> ToolResult.ok(""));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("missing", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().contains("Unknown tool: missing"), r.content());
        assertTrue(r.content().contains("alpha"), r.content());
    }

    @Test
    void run_catches_tool_exception_returns_error() {
        var t = mk("bar", (a, c) -> { throw new ToolException("boom"); });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("bar", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertEquals("boom", r.content());
    }

    @Test
    void run_catches_other_exception_returns_generic_error() {
        var t = mk("bar", (a, c) -> { throw new IllegalStateException("internal"); });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("bar", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().contains("internal error"), r.content());
        assertTrue(r.content().contains("IllegalStateException"), r.content());
    }

    @Test
    void run_passes_args_and_ctx_to_tool() {
        var t = mk("check", (a, c) -> {
            assertEquals("hi", a.path("x").asText());
            assertEquals("/tmp", c.cwd().toString());
            return ToolResult.ok("");
        });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var args = new ObjectMapper().createObjectNode().put("x", "hi");
        exec.run("check", args);
    }
}
