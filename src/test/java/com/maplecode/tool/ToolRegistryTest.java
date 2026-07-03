package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc-" + name; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.ok(""); }
            @Override public String toString() { return "tool:" + name; }
        };
    }

    @Test
    void empty_registry_all_is_empty() {
        var reg = new ToolRegistry(List.of());
        assertEquals(0, reg.all().size());
        assertEquals(Optional.empty(), reg.get("anything"));
    }

    @Test
    void all_returns_tools_in_construction_order() {
        var a = mk("a");
        var b = mk("b");
        var c = mk("c");
        var reg = new ToolRegistry(List.of(a, b, c));
        assertEquals(List.of(a, b, c), reg.all());
    }

    @Test
    void get_finds_by_name() {
        var a = mk("alpha");
        var b = mk("beta");
        var reg = new ToolRegistry(List.of(a, b));
        assertSame(a, reg.get("alpha").orElseThrow());
        assertSame(b, reg.get("beta").orElseThrow());
    }

    @Test
    void get_missing_returns_empty() {
        var reg = new ToolRegistry(List.of(mk("x")));
        assertEquals(Optional.empty(), reg.get("y"));
    }
}