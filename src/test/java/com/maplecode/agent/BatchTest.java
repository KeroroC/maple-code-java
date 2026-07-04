package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchTest {

    private static Tool tool(String name) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return ""; }
            public JsonNode inputSchema() { return null; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { return null; }
        };
    }

    private record Use(String id, String name, JsonNode input) implements NamedToolUse {}

    @Test
    void partitionsReadAndWrite() {
        var registry = new ToolRegistry(List.of(
            tool("read_file"), tool("write_file"), tool("glob"), tool("exec")));
        var uses = List.of(
            new Use("1", "read_file", null),
            new Use("2", "write_file", null),
            new Use("3", "glob", null),
            new Use("4", "exec", null)
        );
        var batch = Batch.partition(uses, registry);
        assertEquals(2, batch.safe().size());
        assertEquals(2, batch.unsafe().size());
    }

    @Test
    void allReadOnlyPutsEverythingInSafe() {
        var registry = new ToolRegistry(List.of(
            tool("read_file"), tool("glob"), tool("grep")));
        var uses = List.of(
            new Use("1", "read_file", null),
            new Use("2", "glob", null));
        var batch = Batch.partition(uses, registry);
        assertEquals(2, batch.safe().size());
        assertEquals(0, batch.unsafe().size());
    }

    @Test
    void allUnsafePutsEverythingInUnsafe() {
        var registry = new ToolRegistry(List.of(tool("write_file"), tool("exec")));
        var uses = List.of(new Use("1", "exec", null));
        var batch = Batch.partition(uses, registry);
        assertEquals(0, batch.safe().size());
        assertEquals(1, batch.unsafe().size());
    }

    @Test
    void emptyInputProducesEmptyBatches() {
        var registry = new ToolRegistry(List.of(tool("read_file")));
        var batch = Batch.partition(List.of(), registry);
        assertEquals(0, batch.safe().size());
        assertEquals(0, batch.unsafe().size());
    }

    @Test
    void unknownToolGoesToUnsafe() {
        var registry = new ToolRegistry(List.of(tool("read_file")));
        var uses = List.of(new Use("1", "unknown_tool", null));
        var batch = Batch.partition(uses, registry);
        assertEquals(1, batch.unsafe().size());
    }
}
