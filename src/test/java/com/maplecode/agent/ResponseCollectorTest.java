package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.provider.TokenUsage;
import com.maplecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseCollectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void collectsTextAndForwardsDelta() {
        var events = new ArrayList<AgentEvent>();
        var col = new ResponseCollector(events::add, emptyRegistry());

        col.accept(new StreamChunk.TextDelta("hello "));
        col.accept(new StreamChunk.TextDelta("world"));
        col.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, null));

        assertEquals("hello world", col.text().toString());
        assertEquals(StopReason.END_TURN, col.stopReason());

        long textDeltas = events.stream()
            .filter(e -> e instanceof AgentEvent.TextDelta).count();
        assertEquals(2, textDeltas);
    }

    @Test
    void collectsToolUsesAndForwardsEvents() throws Exception {
        var events = new ArrayList<AgentEvent>();
        var col = new ResponseCollector(events::add, emptyRegistry());

        col.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
        col.accept(new StreamChunk.ToolUseDelta("t1", "{\"path\":\"/tmp/x\"}"));
        col.accept(new StreamChunk.ToolUseEnd("t1", "read_file",
            JSON.readTree("{\"path\":\"/tmp/x\"}")));
        col.accept(new StreamChunk.MessageEnd(StopReason.TOOL_USE, null));

        assertEquals(1, col.toolUses().size());
        assertEquals("read_file", col.toolUses().get(0).name());
        assertEquals(StopReason.TOOL_USE, col.stopReason());

        long startEvents = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallStart).count();
        long endEvents = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallEnd).count();
        assertEquals(1, startEvents);
        assertEquals(1, endEvents);
    }

    @Test
    void errorChunkSetsErrored() {
        var events = new ArrayList<AgentEvent>();
        var col = new ResponseCollector(events::add, emptyRegistry());

        col.accept(new StreamChunk.Error("bad", "oops"));

        assertTrue(col.errored());
        assertEquals(StopReason.ERROR, col.stopReason());
    }

    @Test
    void capturesUsageFromMessageEnd() {
        var col = new ResponseCollector(e -> {}, emptyRegistry());
        col.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, TokenUsage.of(10, 20)));
        assertEquals(TokenUsage.of(10, 20), col.usage());
    }

    @Test
    void argSummaryExtractsPathFromCompleteJson() {
        var col = new ResponseCollector(e -> {}, registryWithRead());
        col.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
        col.accept(new StreamChunk.ToolUseDelta("t1", "{\"path\":\"/tmp/foo.java\"}"));
        // After ToolUseDelta, pendingJson should contain the path
        assertTrue(col.pendingJsonForTest().toString().contains("foo.java"));
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry(List.of());
    }

    private static ToolRegistry registryWithRead() {
        return new ToolRegistry(List.of(new com.maplecode.tool.ReadFileTool()));
    }
}
