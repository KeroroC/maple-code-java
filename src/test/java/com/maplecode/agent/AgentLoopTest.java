package com.maplecode.agent;

import com.maplecode.fake.FakeLlmProvider;
import com.maplecode.fake.RecordingTool;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void singleToolCallThenText() throws Exception {
        var mapper = new ObjectMapper();
        var chunks1 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "read_file"),
            new StreamChunk.ToolUseDelta("t1", "{\"path\":\"/tmp/x\"}"),
            new StreamChunk.ToolUseEnd("t1", "read_file", mapper.readTree("{\"path\":\"/tmp/x\"}")),
            new StreamChunk.MessageEnd(StopReason.TOOL_USE, null)
        );
        var chunks2 = List.<StreamChunk>of(
            new StreamChunk.TextDelta("got it"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(chunks1, chunks2));
        var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("file content"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

        var events = new ArrayList<AgentEvent>();
        agent.run("read x", events::add);

        // Should have 2 IterationStart (two iterations)
        long iterStarts = events.stream()
            .filter(e -> e instanceof AgentEvent.IterationStart).count();
        assertEquals(2, iterStarts);

        // Should have 1 ToolResult event
        long toolResults = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolResult).count();
        assertEquals(1, toolResults);

        // Last AgentStop should be END_TURN
        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).reduce((a, b) -> b).orElseThrow();
        assertEquals(StopReason.END_TURN, stop.reason());

        // session should have messages: user, assistant(tool_use), user(tool_result), assistant(text)
        assertEquals(4, session.size());
    }

    @Test
    void threeIterationsThenStop() throws Exception {
        var mapper = new ObjectMapper();
        var c1 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "read_file"),
            new StreamChunk.ToolUseEnd("t1", "read_file", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StopReason.TOOL_USE, null)
        );
        var c2 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t2", "read_file"),
            new StreamChunk.ToolUseEnd("t2", "read_file", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StopReason.TOOL_USE, null)
        );
        var c3 = List.<StreamChunk>of(
            new StreamChunk.TextDelta("done"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(c1, c2, c3));
        var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("x"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

        var events = new ArrayList<AgentEvent>();
        agent.run("go", events::add);

        long iterStarts = events.stream()
            .filter(e -> e instanceof AgentEvent.IterationStart).count();
        assertEquals(3, iterStarts);
    }

    @Test
    void twoReadFilesRunInBatch() throws Exception {
        var mapper = new ObjectMapper();
        var c1 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "read_file"),
            new StreamChunk.ToolUseEnd("t1", "read_file", mapper.readTree("{}")),
            new StreamChunk.ToolUseStart("t2", "read_file"),
            new StreamChunk.ToolUseEnd("t2", "read_file", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StopReason.TOOL_USE, null)
        );
        var c2 = List.<StreamChunk>of(
            new StreamChunk.TextDelta("ok"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(c1, c2));
        var sharedTool = new RecordingTool("read_file", ToolResult.ok("a"));
        var registry = new ToolRegistry(List.of(sharedTool));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

        var events = new ArrayList<AgentEvent>();
        agent.run("read both", events::add);

        assertEquals(2, sharedTool.calls().size());
        // Verify BatchStart/BatchEnd events exist
        long batchStarts = events.stream()
            .filter(e -> e instanceof AgentEvent.BatchStart).count();
        assertEquals(1, batchStarts);
    }

    @Test
    void twoExecRunSerially() throws Exception {
        var mapper = new ObjectMapper();
        var c1 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "exec"),
            new StreamChunk.ToolUseEnd("t1", "exec", mapper.readTree("{}")),
            new StreamChunk.ToolUseStart("t2", "exec"),
            new StreamChunk.ToolUseEnd("t2", "exec", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StopReason.TOOL_USE, null)
        );
        var c2 = List.<StreamChunk>of(
            new StreamChunk.TextDelta("done"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(c1, c2));
        var exec = new RecordingTool("exec", ToolResult.ok("ran"));
        var registry = new ToolRegistry(List.of(exec));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

        var events = new ArrayList<AgentEvent>();
        agent.run("run both", events::add);

        assertEquals(2, exec.calls().size());
    }
}
