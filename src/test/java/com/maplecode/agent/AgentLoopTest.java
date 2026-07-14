package com.maplecode.agent;

import com.maplecode.fake.FakeLlmProvider;
import com.maplecode.fake.RecordingTool;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.PromptSection;
import com.maplecode.prompt.SectionContext;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.session.ChatSession;
import com.maplecode.skill.ActivatedSkillsSection;
import com.maplecode.skill.ExecutionMode;
import com.maplecode.skill.SkillDef;
import com.maplecode.skill.SkillRegistry;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    void cancelDuringStreamStopsLaterTextAndResetsForNextRun() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var agentRef = new java.util.concurrent.atomic.AtomicReference<AgentLoop>();
        LlmProvider provider = (req, sink) -> {
            if (calls.incrementAndGet() == 1) {
                sink.accept(new StreamChunk.TextDelta("before"));
                agentRef.get().cancel();
                sink.accept(new StreamChunk.TextDelta("after"));
                sink.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, null));
            } else {
                sink.accept(new StreamChunk.TextDelta("next"));
                sink.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, null));
            }
        };
        var registry = new ToolRegistry(List.of());
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, new ToolExecutor(registry),
            session, AgentConfig.defaults(), null);
        agentRef.set(agent);

        var first = new ArrayList<AgentEvent>();
        agent.run("first", first::add);
        assertEquals(StopReason.USER_CANCELLED, first.stream()
            .filter(AgentEvent.AgentStop.class::isInstance)
            .map(AgentEvent.AgentStop.class::cast)
            .findFirst().orElseThrow().reason());
        assertTrue(first.stream()
            .filter(AgentEvent.TextDelta.class::isInstance)
            .map(AgentEvent.TextDelta.class::cast)
            .noneMatch(e -> e.text().equals("after")));
        assertEquals(1, session.size());

        var second = new ArrayList<AgentEvent>();
        agent.run("second", second::add);
        assertEquals(2, calls.get());
        assertTrue(second.stream()
            .filter(AgentEvent.TextDelta.class::isInstance)
            .map(AgentEvent.TextDelta.class::cast)
            .anyMatch(e -> e.text().equals("next")));
    }

    @Test
    void cancelAfterToolResponsePreventsToolExecution() {
        var agentRef = new java.util.concurrent.atomic.AtomicReference<AgentLoop>();
        LlmProvider provider = (req, sink) -> {
            sink.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
            sink.accept(new StreamChunk.ToolUseEnd("t1", "read_file",
                new ObjectMapper().createObjectNode()));
            sink.accept(new StreamChunk.MessageEnd(StopReason.TOOL_USE, null));
            agentRef.get().cancel();
        };
        var tool = new RecordingTool("read_file", ToolResult.ok("content"));
        var registry = new ToolRegistry(List.of(tool));
        var agent = new AgentLoop(provider, registry, new ToolExecutor(registry),
            new ChatSession(), AgentConfig.defaults(), null);
        agentRef.set(agent);

        var events = new ArrayList<AgentEvent>();
        agent.run("read", events::add);

        assertEquals(0, tool.calls().size());
        assertEquals(StopReason.USER_CANCELLED, events.stream()
            .filter(AgentEvent.AgentStop.class::isInstance)
            .map(AgentEvent.AgentStop.class::cast)
            .findFirst().orElseThrow().reason());
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

    @Test
    void usageSinkReceivesTokenUsageFromMessageEnd() {
        var usage = new com.maplecode.provider.TokenUsage(1500, 300, 100, 50);
        var chunks = List.<StreamChunk>of(
            new StreamChunk.MessageStart(),
            new StreamChunk.TextDelta("hello"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, usage)
        );
        var provider = new FakeLlmProvider(List.of(chunks));
        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var captured = new java.util.concurrent.atomic.AtomicReference<com.maplecode.provider.TokenUsage>();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), captured::set);

        agent.run("hi", e -> {});

        assertNotNull(captured.get(), "usageSink should have received TokenUsage");
        assertEquals(1500, captured.get().inputTokens());
        assertEquals(300, captured.get().outputTokens());
        assertEquals(100, captured.get().cacheCreationTokens());
        assertEquals(50, captured.get().cacheReadTokens());
    }

    @Test
    void activatedSkillInjectedIntoSystemBlocksOnNextRun() {
        // 准备：一个未激活的 skill
        var skillDef = new SkillDef("test-skill", "A test skill", List.of(),
            ExecutionMode.SHARED, 0, null, "Do the thing {{input}}", Path.of("test-skill.md"));
        var skillRegistry = new SkillRegistry(Map.of("test-skill", skillDef));

        // 构建 sections：包含 ActivatedSkillsSection + 一个静态段
        var skillsSection = new ActivatedSkillsSection(skillRegistry);
        PromptSection identitySection = new PromptSection() {
            public String kind() { return "identity"; }
            public String render(SectionContext ctx) { return "You are a test agent."; }
            public boolean cacheable() { return true; }
        };
        List<PromptSection> sections = List.of(identitySection, skillsSection);
        var env = DynamicContext.capture(Path.of("."));

        // spy provider：捕获 ChatRequest
        var capturedReqs = new java.util.concurrent.atomic.AtomicReference<com.maplecode.provider.ChatRequest>();
        var endTurnChunks = List.<StreamChunk>of(
            new StreamChunk.TextDelta("ok"),
            new StreamChunk.MessageEnd(StopReason.END_TURN, null)
        );
        LlmProvider spyProvider = (req, sink) -> {
            capturedReqs.set(req);
            for (var c : endTurnChunks) sink.accept(c);
        };

        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(spyProvider, registry, executor, session,
            AgentConfig.defaults(), null, null, sections, env);

        // 第一次 run：skill 未激活
        agent.run("hello", e -> {});
        var req1 = capturedReqs.get();
        assertNotNull(req1);
        boolean hasSkill1 = req1.systemBlocks().stream()
            .anyMatch(b -> b.content().contains("Do the thing"));
        assertFalse(hasSkill1, "system blocks should NOT contain skill content before activation");

        // 激活 skill
        skillRegistry.activate(skillDef, "Do the thing with magic");

        // 第二次 run：skill 已激活
        agent.run("again", e -> {});
        var req2 = capturedReqs.get();
        assertNotNull(req2);
        boolean hasSkill2 = req2.systemBlocks().stream()
            .anyMatch(b -> b.content().contains("Do the thing with magic"));
        assertTrue(hasSkill2, "system blocks SHOULD contain skill content after activation");
    }
}
