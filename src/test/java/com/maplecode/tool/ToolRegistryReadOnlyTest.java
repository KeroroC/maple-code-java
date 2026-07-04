package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryReadOnlyTest {

    private static Tool tool(String name) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return ""; }
            public JsonNode inputSchema() { return null; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { return null; }
        };
    }

    @Test
    void readFileIsReadOnly() {
        var r = new ToolRegistry(List.of(tool("read_file"), tool("write_file"), tool("exec")));
        assertTrue(r.isReadOnly("read_file"));
        assertFalse(r.isReadOnly("write_file"));
        assertFalse(r.isReadOnly("exec"));
    }

    @Test
    void globAndGrepAreReadOnly() {
        var r = new ToolRegistry(List.of(tool("glob"), tool("grep")));
        assertTrue(r.isReadOnly("glob"));
        assertTrue(r.isReadOnly("grep"));
    }

    @Test
    void editFileIsNotReadOnly() {
        var r = new ToolRegistry(List.of(tool("edit_file")));
        assertFalse(r.isReadOnly("edit_file"));
    }

    @Test
    void readOnlyReturnsFilteredList() {
        var r = new ToolRegistry(List.of(
            tool("read_file"), tool("write_file"), tool("glob"),
            tool("edit_file"), tool("grep"), tool("exec")));
        var readOnly = r.readOnly();
        assertEquals(3, readOnly.size());
        var names = readOnly.stream().map(Tool::name).sorted().toList();
        assertEquals(List.of("glob", "grep", "read_file"), names);
    }

    @Test
    void unknownToolIsNotReadOnly() {
        var r = new ToolRegistry(List.of(tool("read_file")));
        assertFalse(r.isReadOnly("unknown_tool"));
    }
}
