package com.maplecode.agent;

import com.maplecode.fake.FakeLlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private static Tool noopTool(String name, ToolResult result) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return ""; }
            public JsonNode inputSchema() { return null; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { return result; }
        };
    }

    @Test
    void emptyResponseEmitsAgentStop() {
        var chunks = List.<StreamChunk>of(
            new StreamChunk.MessageStart(),
            new StreamChunk.TextDelta("hello"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(chunks));
        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

        var events = new ArrayList<AgentEvent>();
        agent.run("hi", events::add);

        long stopEvents = events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).count();
        assertEquals(1, stopEvents);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StopReason.END_TURN, stop.reason());
    }

    @Test
    void cancelBeforeRunEmitsUserCancelled() {
        var provider = new FakeLlmProvider(List.of(
            List.<StreamChunk>of(new StreamChunk.MessageStart(),
                new StreamChunk.MessageEnd(StopReason.END_TURN, null))));
        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());
        agent.cancel();

        var events = new ArrayList<AgentEvent>();
        agent.run("hi", events::add);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StopReason.USER_CANCELLED, stop.reason());
    }
}
