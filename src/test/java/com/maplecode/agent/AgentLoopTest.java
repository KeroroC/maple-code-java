package com.maplecode.agent;

import com.maplecode.fake.FakeLlmProvider;
import com.maplecode.fake.RecordingTool;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.provider.LlmProvider;
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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);

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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);
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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);

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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);

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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);

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
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null);

        var events = new ArrayList<AgentEvent>();
        agent.run("run both", events::add);

        assertEquals(2, exec.calls().size());
    }

    @Test
    void maxIterationsTriggersStop() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cfg = new AgentConfig("m", List.of(), null, 3, 3,
            com.maplecode.agent.PlanMode.NORMAL, PlanModeReminder.State.initial());

        var loopScript = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "read_file"),
            new StreamChunk.ToolUseEnd("t1", "read_file", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
        );
        var scripts = List.of(loopScript, loopScript, loopScript, loopScript, loopScript);
        var provider = new FakeLlmProvider(scripts);
        var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("x"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, cfg, null);

        var events = new ArrayList<AgentEvent>();
        agent.run("loop forever", events::add);

        long iterStarts = events.stream()
            .filter(e -> e instanceof AgentEvent.IterationStart).count();
        assertEquals(3, iterStarts);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.MAX_ITERATIONS, stop.reason());
    }

    @Test
    void threeConsecutiveUnknownToolsTriggersStop() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var unknownChunk = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "unknown_tool"),
            new StreamChunk.ToolUseEnd("t1", "unknown_tool", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
        );
        var scripts = List.of(unknownChunk, unknownChunk, unknownChunk, unknownChunk);
        var provider = new FakeLlmProvider(scripts);
        var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("x"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session,
            new AgentConfig("m", List.of(), null, 25, 3,
                com.maplecode.agent.PlanMode.NORMAL, PlanModeReminder.State.initial()), null);

        var events = new ArrayList<AgentEvent>();
        agent.run("try unknown", events::add);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.CONSECUTIVE_UNKNOWN, stop.reason());
    }

    @Test
    void providerExceptionEmitsProviderErrorStop() {
        LlmProvider failing = new LlmProvider() {
            public void stream(com.maplecode.provider.ChatRequest req,
                               java.util.function.Consumer<StreamChunk> sink) {
                throw new com.maplecode.error.ProviderException("network down");
            }
        };
        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(failing, registry, executor, session, AgentConfig.defaults(), null);

        var events = new ArrayList<AgentEvent>();
        agent.run("hello", events::add);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.PROVIDER_ERROR, stop.reason());
        assertTrue(stop.detail().contains("network down"));
    }

    @Test
    void planModePassesOnlyReadOnlyTools() {
        var chunks = List.<StreamChunk>of(
            new StreamChunk.TextDelta("plan"),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
        );

        // Spy provider to capture the ChatRequest
        var capturedReq = new java.util.concurrent.atomic.AtomicReference<com.maplecode.provider.ChatRequest>();
        var spyProvider = new LlmProvider() {
            public void stream(com.maplecode.provider.ChatRequest req,
                               java.util.function.Consumer<StreamChunk> sink) {
                capturedReq.set(req);
                for (var c : chunks) sink.accept(c);
            }
        };

        var registry = new ToolRegistry(List.of(
            noopTool("read_file", ToolResult.ok("x")),
            noopTool("write_file", ToolResult.ok("x"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var cfg = new AgentConfig("m", List.of(), null, 25, 3,
            PlanMode.PLAN, PlanModeReminder.State.initial());
        var agent = new AgentLoop(spyProvider, registry, executor, session, cfg, null);

        var events = new ArrayList<AgentEvent>();
        agent.run("plan this", events::add);

        var tools = capturedReq.get().tools();
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("read_file", tools.get(0).name());
    }

    @Test
    void planModeRejectsUnsafeToolAtExecutorLevel() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var chunks1 = List.<StreamChunk>of(
            new StreamChunk.ToolUseStart("t1", "write_file"),
            new StreamChunk.ToolUseEnd("t1", "write_file", mapper.readTree("{}")),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
        );
        var chunks2 = List.<StreamChunk>of(
            new StreamChunk.TextDelta("ok"),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
        );
        var provider = new FakeLlmProvider(List.of(chunks1, chunks2));
        var registry = new ToolRegistry(List.of(
            noopTool("read_file", ToolResult.ok("x")),
            noopTool("write_file", ToolResult.ok("wrote"))));
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var cfg = new AgentConfig("m", List.of(), null, 25, 3,
            PlanMode.PLAN, PlanModeReminder.State.initial());
        var agent = new AgentLoop(provider, registry, executor, session, cfg, null);

        var events = new ArrayList<AgentEvent>();
        agent.run("plan+do", events::add);

        var tr = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolResult)
            .map(e -> (AgentEvent.ToolResult) e)
            .findFirst()
            .orElseThrow();
        assertTrue(tr.isError(), "tool should be rejected in PLAN mode");
        assertTrue(tr.content().contains("write_file"));
    }
}
