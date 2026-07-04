# MapleCode — Agent Loop 设计规格（阶段三）

**日期**：2026-07-04
**范围**：在 v2 单轮工具调用基础上增加 Agent Loop，让模型自主循环调工具、看结果、调整，直到任务完成。同时引入异步事件总线、双路流式收集、安全分批并发、Plan Mode（`/plan` + `/do`）。
**不在范围**：权限审批、上下文压缩、运行时切换 provider、多模态、会话持久化。

---

## 1. 目标与非目标

**目标**
- ReAct 循环：模型发 tool_use → 执行 → 回灌 → 模型再回话，直到模型不再要工具
- 五种停止条件：模型说完 / 迭代上限 / 用户取消 / 连续未知工具 / Provider 错误
- 异步事件总线（sealed `AgentEvent`）让 Agent 与 UI 解耦
- 流式收集器走双路：实时把文本推给 UI，同时攒出完整响应供后续判断
- 一次返回多个 tool_use 时按安全性分批：只读并发、有副作用串行
- Plan Mode 两段式：`/plan` 只放读类工具出计划，`/do` 二段提交切换到全工具执行

**非目标（明确不做）**
- 工具调用审批（写/执行前的 y/N 确认）—— 后续阶段
- 上下文压缩 / 摘要 —— 后续阶段
- 用户交互式确认（中途问用户问题）—— 后续阶段
- 会话持久化与 `/resume` —— 后续阶段
- 运行时切换 provider、多模态输入、插件系统 —— 详见 v1 §1 和 v2 §1

---

## 2. 新增 / 修改的抽象

### 2.1 `sealed interface AgentEvent`（新增，`com.maplecode.agent`）

```java
public sealed interface AgentEvent
    permits AgentEvent.TextDelta,
            AgentEvent.ThinkingDelta,
            AgentEvent.ToolCallStart,
            AgentEvent.ToolCallEnd,
            AgentEvent.ToolResult,
            AgentEvent.IterationStart,
            AgentEvent.IterationEnd,
            AgentEvent.TokenUsage,
            AgentEvent.BatchStart,
            AgentEvent.BatchEnd,
            AgentEvent.AgentStop {

    record TextDelta(String text) implements AgentEvent {}
    record ThinkingDelta(String text) implements AgentEvent {}
    record ToolCallStart(String id, String name, String argSummary) implements AgentEvent {}
    record ToolCallEnd(String id, String name, JsonNode input) implements AgentEvent {}
    record ToolResult(String toolUseId, String name, boolean isError, String content) implements AgentEvent {}
    record IterationStart(int iteration) implements AgentEvent {}
    record IterationEnd(int iteration, StopReason stopReason,
                        List<String> toolUseIds, TokenUsage usage) implements AgentEvent {}
    record TokenUsage(int inputTokens, int outputTokens) implements AgentEvent {}
    record BatchStart(int safeCount, int unsafeCount) implements AgentEvent {}
    record BatchEnd(int totalTools, int failedTools) implements AgentEvent {}
    record AgentStop(StopReason reason, String detail) implements AgentEvent {}
}
```

> **sealed 设计**：每个新事件类型添加时，AgentLoop 内 switch、StreamPrinter 渲染 switch、测试断言都必须更新；编译期强制。

### 2.2 `TokenUsage` 与 `StopReason` 扩展

```java
public record TokenUsage(int inputTokens, int outputTokens) {}
```

`StopReason` 增加两个枚举值：

```java
public enum StopReason {
    END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE,         // v2 已有
    MAX_ITERATIONS,                                       // v3 新增
    CONSECUTIVE_UNKNOWN,                                  // v3 新增
    PROVIDER_ERROR,                                       // v3 新增
    USER_CANCELLED                                        // v3 新增
}
```

### 2.3 `StreamChunk.MessageEnd` 加 `usage` 字段

```java
record MessageEnd(StopReason reason, TokenUsage usage) implements StreamChunk {}
```

- `usage` 可空（流里没拿到则 null）
- v2 所有 `MessageEnd` 构造点（两个 StreamParser 的 `message_stop` / `finish_reason` 分支）必须更新

### 2.4 `Tool` 接口 / `ToolContext` 不变

v2 已定义。不引入 `isReadOnly()` 方法 —— 工具安全性是注册表属性，由 `ToolRegistry` 集中查询，避免侵入 6 个具体工具类。

### 2.5 `ToolRegistry` 加 `isReadOnly`

```java
public final class ToolRegistry {
    // v2 已有
    public List<Tool> all() { ... }
    public Optional<Tool> get(String name) { ... }

    /** 工具是否只读。可并发执行（read_file / glob / grep）。 */
    public boolean isReadOnly(String name) { ... }

    /** 过滤出只读工具子集 —— 用于 Plan Mode。 */
    public List<Tool> readOnly() { ... }
}
```

**内置工具安全分类**：

| 工具 | 分类 |
|---|---|
| `read_file` | 只读 |
| `glob` | 只读 |
| `grep` | 只读 |
| `write_file` | 有副作用 |
| `edit_file` | 有副作用 |
| `exec` | 有副作用 |

`isReadOnly` 与 `readOnly()` 在 `ToolRegistry` 构造时初始化为常量 `Set<String>{"read_file","glob","grep"}`，无需遍历工具列表。

### 2.6 `AgentConfig`（新增）

```java
public record AgentConfig(
    int maxIterations,                 // 默认 25；上限；>= 1
    int maxConsecutiveUnknown,         // 默认 3；>= 1
    PlanMode planMode                  // 默认 NORMAL
) {
    public AgentConfig {
        if (maxIterations < 1)
            throw new ConfigException("maxIterations must be >= 1");
        if (maxConsecutiveUnknown < 1)
            throw new ConfigException("maxConsecutiveUnknown must be >= 1");
    }

    public static AgentConfig defaults() {
        return new AgentConfig(25, 3, PlanMode.NORMAL);
    }
}

public enum PlanMode { NORMAL, PLAN }
```

### 2.7 `Batch`（内部 helper）

```java
record Batch(List<ToolUse> safe, List<ToolUse> unsafe) {
    static Batch partition(List<ToolUse> uses, ToolRegistry registry) {
        var safe = uses.stream()
            .filter(u -> registry.isReadOnly(u.name()))
            .toList();
        var unsafe = uses.stream()
            .filter(u -> !registry.isReadOnly(u.name()))
            .toList();
        return new Batch(safe, unsafe);
    }
}
```

### 2.8 `ResponseCollector`（内部 helper）

```java
final class ResponseCollector implements Consumer<StreamChunk> {
    private final StringBuilder text = new StringBuilder();
    private final List<ToolUse> toolUses = new ArrayList<>();
    private StopReason stopReason;
    private TokenUsage usage;
    private boolean errored;

    private final Consumer<AgentEvent> sink;   // 双路：除了累加，还实时转发
    private final ToolRegistry registry;       // 用于 ToolCallStart 实时 argSummary
    private final StringBuilder pendingJson = new StringBuilder();
    private String pendingId, pendingName;

    @Override
    public void accept(StreamChunk chunk) {
        switch (chunk) {
            case TextDelta d -> {
                text.append(d.text());
                sink.accept(new AgentEvent.TextDelta(d.text()));
            }
            case ThinkingDelta d -> sink.accept(new AgentEvent.ThinkingDelta(d.text()));
            case ToolUseStart d -> {
                pendingId = d.id();
                pendingName = d.name();
                pendingJson.setLength(0);
                sink.accept(new AgentEvent.ToolCallStart(d.id(), d.name(), argSummary(d.name())));
            }
            case ToolUseDelta d -> {
                pendingId = d.id();
                pendingJson.append(d.partialJson());
            }
            case ToolUseEnd d -> {
                toolUses.add(new ToolUse(d.id(), d.name(), d.input()));
                sink.accept(new AgentEvent.ToolCallEnd(d.id(), d.name(), d.input()));
                pendingId = pendingName = null;
                pendingJson.setLength(0);
            }
            case MessageStart s -> { /* no-op */ }
            case MessageEnd e -> {
                stopReason = e.reason();
                usage = e.usage();
            }
            case Error e -> {
                errored = true;
                stopReason = StopReason.ERROR;
            }
        }
    }

    /** 实时从 pendingJson 抽 path/command/pattern，否则截前 40 字符。 */
    String argSummary(String toolName) { ... }
}
```

### 2.9 `AgentLoop`（新增）

```java
public final class AgentLoop {
    private final LlmProvider provider;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ChatSession session;
    private final AgentConfig config;
    private volatile boolean cancelled;

    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config) { ... }

    public void run(String userInput, Consumer<AgentEvent> sink) {
        session.appendUserText(userInput);
        int iteration = 0;
        int consecutiveUnknown = 0;
        StopReason finalStop = StopReason.END_TURN;
        String finalDetail = "assistant finished";

        while (iteration < config.maxIterations()) {
            if (cancelled) {
                finalStop = StopReason.USER_CANCELLED;
                finalDetail = "user cancelled";
                break;
            }

            sink.accept(new AgentEvent.IterationStart(iteration));
            ResponseCollector col = new ResponseCollector(sink, registry);
            ChatRequest req = session.toRequest(
                config.model(), config.systemPrompt(), config.thinking(),
                config.planMode() == PlanMode.PLAN ? registry.readOnly() : registry.all()
            );

            try {
                provider.stream(req, col);
            } catch (ProviderException e) {
                sink.accept(new AgentStop(StopReason.PROVIDER_ERROR, e.getMessage()));
                return;
            }

            List<ToolUse> uses = col.toolUses();
            sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
                uses.stream().map(ToolUse::id).toList(), col.usage()));

            if (col.stopReason() != StopReason.TOOL_USE) {
                finalStop = col.stopReason();
                finalDetail = "assistant finished";
                break;
            }
            if (uses.isEmpty()) {
                // 模型说 TOOL_USE 但 0 个 tool_use 是不一致状态，按 ERROR 处理
                finalStop = StopReason.ERROR;
                finalDetail = "stop_reason=tool_use but no tool_use emitted";
                break;
            }

            // 写 assistant 消息：text + 所有 tool_use
            List<ContentBlock> assistantBlocks = new ArrayList<>();
            if (!col.text().isEmpty()) {
                assistantBlocks.add(new ContentBlock.TextBlock(col.text().toString()));
            }
            for (ToolUse u : uses) {
                assistantBlocks.add(new ContentBlock.ToolUseBlock(u.id(), u.name(), u.input()));
            }
            session.appendAssistant(assistantBlocks);

            // 分批并发执行
            Batch batch = Batch.partition(uses, registry);
            sink.accept(new AgentEvent.BatchStart(batch.safe().size(), batch.unsafe().size()));

            List<ToolResultPayload> results = new ArrayList<>();
            // safe 批：并行
            batch.safe().parallelStream().forEach(u ->
                results.add(executeOne(u, sink)));
            // unsafe 批：串行
            for (ToolUse u : batch.unsafe()) {
                results.add(executeOne(u, sink));
            }

            int failed = (int) results.stream().filter(r -> r.result.isError()).count();
            sink.accept(new AgentEvent.BatchEnd(results.size(), failed));

            // 写 tool_result 消息
            for (ToolResultPayload r : results) {
                session.appendUser(List.of(new ContentBlock.ToolResultBlock(
                    r.toolUseId, r.result.content(), r.result.isError())));
            }

            // 累计连续未知工具
            for (ToolResultPayload r : results) {
                if (isUnknownToolError(r.result.content())) {
                    consecutiveUnknown++;
                } else {
                    consecutiveUnknown = 0;
                    break;  // 只要有一个已知工具就重置
                }
            }
            if (consecutiveUnknown >= config.maxConsecutiveUnknown()) {
                finalStop = StopReason.CONSECUTIVE_UNKNOWN;
                finalDetail = "unknown tool called "
                    + config.maxConsecutiveUnknown() + " times in a row";
                break;
            }

            iteration++;
        }

        if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
            // 循环是因为 cap 退出而不是自然结束
            // 注意：上面 while 条件是 < cap；最后一次 iteration == cap-1 进入；若 cap 触顶，
            // 此时 finalStop 还是 END_TURN 初值，需要改写
            finalStop = StopReason.MAX_ITERATIONS;
            finalDetail = "reached iteration cap: " + config.maxIterations();
        }

        sink.accept(new AgentEvent.AgentStop(finalStop, finalDetail));
    }

    public void cancel() {
        cancelled = true;
    }

    private ToolResultPayload executeOne(ToolUse u, Consumer<AgentEvent> sink) { ... }

    private static boolean isUnknownToolError(String content) {
        return content != null && content.startsWith("Unknown tool:");
    }

    private record ToolResultPayload(String toolUseId, ToolResult result) {}
    private record ToolUse(String id, String name, JsonNode input) {}
}
```

**关键决策点**：
1. **Assistant 消息先 append，再执行工具**——保持 session 完整对话轨迹。失败也保留（便于 `/resume` 未来使用）
2. **consecutiveUnknown 在 batch 内累加**：只看本批所有结果；如果有任何「已知工具成功」，重置
3. **`MAX_ITERATIONS` 检测**用初始 finalStop 值哨兵
4. **`ProviderException` 单独 emit**而非 AgentStop 兜底，因为是中途失败不是循环结尾

### 2.10 `AgentCancelledException`（新增）

`error/AgentCancelledException.java`：

```java
public final class AgentCancelledException extends MapleCodeException {
    public AgentCancelledException() {
        super("agent cancelled by user");
    }
}
```

> 注意：本设计采用协作式取消（`cancelled` volatile flag），不抛异常。只保留异常类作为未来扩展占位，本阶段不抛。

---

## 3. 工具分批与并发执行

### 3.1 分批规则

```java
Batch batch = Batch.partition(toolUses, registry);
// batch.safe()    = 只读工具（read_file / glob / grep）
// batch.unsafe()  = 有副作用工具（write_file / edit_file / exec）
```

### 3.2 执行顺序

1. **safe 批先并行跑**：`batch.safe().parallelStream().forEach(...)`
2. **unsafe 批后串行跑**：`for (ToolUse u : batch.unsafe()) ...`
3. 顺序不可调换 —— 先调研后动手的语义；若用户希望 unsafe 在 safe 前（紧急止血场景），后续阶段再做

### 3.3 并发隔离

- `parallelStream` 用默认 common ForkJoinPool（CPU 核数 - 1 线程）
- `ToolExecutor.run()` 已有完整 try/catch 兜底（v2 §6 错误处理保留）
- 单个工具抛异常不会污染其他并行工具

### 3.4 单工具 execute 流程

```java
private ToolResultPayload executeOne(ToolUse u, Consumer<AgentEvent> sink) {
    // ToolCallStart 已在 ResponseCollector 收到 ToolUseStart 时 emit
    ToolResult r;
    try {
        r = executor.run(u.name(), u.input());
    } catch (Exception e) {
        r = ToolResult.error("internal error: " + e.getClass().getSimpleName());
    }
    sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), r.isError(), r.content()));
    return new ToolResultPayload(u.id(), r);
}
```

**注意**：v2 的 `printer.toolStart / toolEnd` 改由 StreamPrinter 监听 `AgentEvent.ToolCallStart / ToolResult` 渲染，不再由 ReplLoop 直接调用。

---

## 4. Plan Mode

### 4.1 状态机

```
NORMAL ──/plan <query>──▶ PLAN ──/do──▶ (清空 session) ──▶ NORMAL (新计划作为用户输入)
                          │
                          └──/cancel──▶ NORMAL
```

### 4.2 `/plan <query>` 命令

ReplLoop 处理：
1. 校验 `query` 非空
2. `agentConfig` 切到 `PlanMode.PLAN`
3. **不**清空 session（保留之前对话上下文作为参考）
4. 调 `agent.run(query, sink)` —— 模型只能用 3 个读类工具
5. 模型最后一条纯文本消息视为「计划」

### 4.3 `/do` 命令

ReplLoop 处理：
1. 校验 `agentConfig.planMode() == PLAN`（否则报错「not in plan mode」）
2. 从 session 找到最后一条 assistant 消息，提取 TextBlock（若没有 TextBlock，报错「no plan to execute」）
3. **`session.clear()`** 清空所有历史
4. `agentConfig` 切回 `PlanMode.NORMAL`
5. 调 `agent.run(planText, sink)` —— 开放全工具

### 4.4 工具集切换

`AgentLoop.run` 内：

```java
List<Tool> tools = (config.planMode() == PlanMode.PLAN)
    ? registry.readOnly()
    : registry.all();
ChatRequest req = session.toRequest(..., tools);
```

### 4.5 PLAN 模式下调写工具

模型若在 PLAN 状态下调 `write_file`：
- `ToolExecutor.run` → `registry.get("write_file")` 仍然存在
- 工具正常执行 —— **设计决策**：PLAN 模式只限制**模型看到的工具清单**，不限制 Registry 本身
- 理由：保持 ToolExecutor 简单；模型违反计划语义时会写文件，但用户体验是「它真的写了」——这正是 PLAN 的目的：观察，不是执行

**等等，反思**：上面这个决策有问题。如果 PLAN 模式不真正禁掉写工具，模型可能误写文件。修正：

**修订决策**：PLAN 模式把写/执行工具**也**从 ToolRegistry 子集构造时排除 —— 让 ToolExecutor 查不到这些工具，返回 `Unknown tool: write_file (not available in plan mode)`。这样模型自己意识到不能写。

实现：
```java
// ToolRegistry 不变；AgentLoop 构造临时工具集
List<Tool> planTools = registry.all().stream()
    .filter(t -> registry.isReadOnly(t.name()))
    .toList();
ChatRequest req = session.toRequest(..., planTools);
```

模型 wire 层面就看不到 `write_file`；若它硬编码调（不该发生），`ToolExecutor.run` 找不到 → error。

### 4.6 `/cancel` 命令

ReplLoop 处理：
1. 若 AgentLoop 正在运行（异步线程）→ 调 `agent.cancel()`
2. 若不在运行 → 打印 "no agent running"
3. `agentConfig` 强制切回 `NORMAL`

> 当前阶段 AgentLoop.run 是同步阻塞调用（ReplLoop 单线程）。`/cancel` 实际在下一轮输入读取时生效（即：当前 turn 跑完才看到 cancelled）。JLine `UserInterruptException`（Ctrl-C）抛到 ReplLoop 时设 cancelled flag；当前 turn 跑完才退出。

---

## 5. Provider 改动 —— Token 用量

### 5.1 Anthropic

**StreamParser 改造**：
- 维护 `int lastInputTokens, lastOutputTokens`
- `message_start`：`data.message.usage` → `{input_tokens, output_tokens}` → 缓存 `lastInputTokens = input, lastOutputTokens = output`
- `message_delta`：`data.usage` → `{output_tokens}` → 更新 `lastOutputTokens`
- `message_stop`：emit `MessageEnd(stopReason, new TokenUsage(lastInputTokens, lastOutputTokens))`

注：Anthropic streaming 的 `usage` 是累加式（output_tokens 是到当前为止的总输出），所以正确做法是「取最新值」而非累加。

### 5.2 OpenAI

**StreamParser 改造**：
- 维护 `TokenUsage lastUsage`
- `choices=[], usage != null` 的 chunk → `lastUsage = new TokenUsage(usage.prompt_tokens, usage.completion_tokens)`
- `finish_reason` chunk 后：**先检查**当前 chunk 是否带 usage（OpenAI 把 usage 放在倒数第二个 chunk 的 choices=[] 里），若有则更新 lastUsage；emit `MessageEnd(reason, lastUsage)`
- 若流结束都没有 usage → emit `MessageEnd(reason, null)`（usage 字段可空）

**RequestMapper 改造**：
- `stream_options.include_usage=true` 必须发（v2 mapper 已有，需要核对）

### 5.3 测试

- `AnthropicStreamParserUsageTest`：mock SSE 序列 message_start 带 usage → message_delta 带 usage → message_stop → 断言 MessageEnd 带正确 TokenUsage
- `OpenAiStreamParserUsageTest`：mock 含 usage 字段的 chunk → 断言 MessageEnd 带 usage

---

## 6. REPL 改造

### 6.1 ReplLoop 新主循环

```java
public void run() {
    printer.banner("MapleCode — ...");
    AgentConfig agentConfig = AgentConfig.defaults();
    while (true) {
        String input;
        try { input = readMultiline(); }
        catch (UserInterruptException e) {
            agent.cancel();
            printer.info("(interrupted)");
            continue;
        }
        catch (RuntimeException e) { break; }
        if (input == null) break;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) continue;
        if (trimmed.equals("/exit")) break;
        if (trimmed.equals("/clear")) { session.clear(); printer.info("history cleared"); continue; }
        if (trimmed.equals("/tools")) { printTools(); continue; }
        if (trimmed.startsWith("/plan ")) {
            agentConfig = new AgentConfig(agentConfig.maxIterations(),
                agentConfig.maxConsecutiveUnknown(), PlanMode.PLAN);
            agent.run(trimmed.substring(6).trim(), printer);
            printer.newline();
            continue;
        }
        if (trimmed.equals("/do")) {
            if (agentConfig.planMode() != PlanMode.PLAN) {
                printer.error("not in plan mode");
                continue;
            }
            String planText = lastAssistantText(session);
            if (planText == null) { printer.error("no plan to execute"); continue; }
            session.clear();
            agentConfig = new AgentConfig(agentConfig.maxIterations(),
                agentConfig.maxConsecutiveUnknown(), PlanMode.NORMAL);
            agent.run(planText, printer);
            printer.newline();
            continue;
        }
        if (trimmed.equals("/cancel")) {
            agent.cancel();
            agentConfig = new AgentConfig(agentConfig.maxIterations(),
                agentConfig.maxConsecutiveUnknown(), PlanMode.NORMAL);
            printer.info("cancelled");
            continue;
        }

        agent.run(trimmed, printer);
        printer.newline();
    }
}
```

**关键改动**：
- ReplLoop 不再持有 `runOneTurn / handleChunk / TurnAccumulator / PendingToolUse` —— 全部下沉到 AgentLoop
- ReplLoop 直接把 `StreamPrinter` 当 `Consumer<AgentEvent>` 传进 AgentLoop.run
- `Session` 由 ReplLoop 持有（创建一次），传给 AgentLoop

### 6.2 StreamPrinter 改造

`StreamPrinter` 实现 `Consumer<AgentEvent>`：

```java
public final class StreamPrinter implements Consumer<AgentEvent> {
    private final PrintStream out;

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.TextDelta d -> write(d.text());
            case AgentEvent.ThinkingDelta d -> writeThinking(d.text());
            case AgentEvent.ToolCallStart s -> toolStart(s.name(), s.argSummary());
            case AgentEvent.ToolResult r -> toolEnd(r.name(), !r.isError(), r.isError() ? r.content() : null);
            case AgentEvent.IterationStart i -> {} // 静默
            case AgentEvent.IterationEnd i -> {}   // 静默
            case AgentEvent.TokenUsage u -> {}     // 静默（v3 不显示，留给未来）
            case AgentEvent.BatchStart b -> {}     // 静默
            case AgentEvent.BatchEnd b -> {}       // 静默
            case AgentEvent.ToolCallEnd e -> {}    // 静默
            case AgentEvent.AgentStop s -> info("[agent stopped: " + s.reason() + "]");
        }
    }

    // write / writeThinking / toolStart / toolEnd / error / info / newline / banner 保留
}
```

**注**：sealed 枚举增加时，StreamPrinter 必须更新；编译期强制。

### 6.3 lastAssistantText 辅助

```java
private String lastAssistantText(ChatSession session) {
    for (int i = session.size() - 1; i >= 0; i--) {
        var msg = session.get(i);
        if (msg.role() != Role.ASSISTANT) continue;
        for (var block : msg.blocks()) {
            if (block instanceof ContentBlock.TextBlock t) return t.text();
        }
    }
    return null;
}
```

需要给 ChatSession 加 `size()` 和 `get(int)` 方法（v2 没有）。

---

## 7. ChatSession 加访问器

```java
public final class ChatSession {
    // v2 已有不变
    public void appendUserText(String text) { ... }
    public void appendUser(List<ContentBlock> blocks) { ... }
    public void appendAssistant(List<ContentBlock> blocks) { ... }
    public void clear() { ... }
    public ChatRequest toRequest(...) { ... }

    // v3 新增
    public int size() { return messages.size(); }
    public ChatMessage get(int i) { return messages.get(i); }
}
```

测试 `ChatSessionTest` 补充 size/get 用例。

---

## 8. 错误处理

| 场景 | 处理 | 影响 |
|---|---|---|
| `ProviderException` 抛出 | AgentLoop 捕获 → emit `AgentStop(PROVIDER_ERROR, msg)` → return | REPL 不死，可继续输入 |
| Parser emit `StreamChunk.Error` | ResponseCollector 标记 errored → `stopReason = ERROR` | 本轮当作 ERROR 退出循环 |
| 工具抛 Exception | `executeOne` try/catch → `ToolResult.error` | 单工具失败不影响其他 |
| 未知工具（registry miss） | `ToolExecutor` 返回 `ToolResult.error("Unknown tool: X")` | 计入 consecutiveUnknown |
| 模型说 TOOL_USE 但 0 个 tool_use | AgentLoop 视为 ERROR | emit AgentStop(ERROR, "inconsistent state") |
| 达到 maxIterations | AgentLoop 循环退出 | emit AgentStop(MAX_ITERATIONS, "reached cap: N") |
| 连续 N 次未知工具 | AgentLoop 循环退出 | emit AgentStop(CONSECUTIVE_UNKNOWN, ...) |
| 用户 Ctrl-C | JLine UserInterruptException → `agent.cancel()` → 当前 turn 跑完后退出 | session 保留 |
| 配置错（maxIterations < 1 等） | `AgentConfig` 紧凑构造器抛 `ConfigException` | 退出码 78（v1 已定） |

---

## 9. 测试策略

### 9.1 单元测试（CI 必跑）

| 测试 | 覆盖 |
|---|---|
| `AgentLoopTest` | mock LlmProvider / ToolRegistry / ToolExecutor / ChatSession：<br>① 1 次 tool_use → 执行 → 结果 → 文本 → END_TURN<br>② 2 次连续 tool_use（读类）→ 并发执行<br>③ 达到 maxIterations → MAX_ITERATIONS<br>④ 连续 3 次 unknown tool → CONSECUTIVE_UNKNOWN<br>⑤ ProviderException → PROVIDER_ERROR<br>⑥ PLAN 模式只传 readOnly 工具<br>⑦ cancel() 在 iteration 顶部生效 |
| `AgentConfigTest` | 紧凑构造器校验；默认值 |
| `BatchTest` | partition：全 safe / 全 unsafe / 混合 / 空 |
| `ToolRegistryReadOnlyTest` | 6 个工具 isReadOnly 正确 |
| `ResponseCollectorTest` | chunk 序列 → text/toolUses/stopReason/usage 双路；errored 标志 |
| `AnthropicStreamParserUsageTest` | message_start/Delta usage → MessageEnd 带 TokenUsage |
| `OpenAiStreamParserUsageTest` | 末尾 usage chunk → MessageEnd 带 usage；缺失 usage → null |
| `StreamPrinterAgentEventTest` | sealed 事件渲染（空实现，断言不抛 + 输出捕获） |

### 9.2 测试辅助

- `FakeLlmProvider`：实现 `LlmProvider`，按脚本返回 `StreamChunk` 序列
- `RecordingTool`：实现 `Tool`，记录 execute 调用，便于验证并发

### 9.3 集成 smoke（手工）

- `@EnabledIfEnvironmentVariable(named = "MAPLECODE_RUN_IT", matches = "1")`
- Anthropic：`/plan 用 read_file 看下 src/main/java/com/maplecode/agent/AgentLoop.java` → 模型输出文本计划 → `/do` → 模型开始改文件
- OpenAI：同上

### 9.4 修改测试

- `ChatSessionTest`：加 `size / get(int)` 测试
- `AnthropicStreamParserTest` / `OpenAiStreamParserTest`：MessageEnd 签名变了
- `AnthropicStreamParserToolTest` / `OpenAiStreamParserToolTest`：MessageEnd 构造点更新
- `StreamPrinterTest`：新增 Consumer<AgentEvent> 路径

---

## 10. 文件清单

### 10.1 新增

```
src/main/java/com/maplecode/agent/
├── AgentLoop.java
├── AgentConfig.java
├── AgentEvent.java           (sealed)
├── TokenUsage.java           (record)
├── PlanMode.java             (enum)
├── ResponseCollector.java    (内部 helper)
└── Batch.java                (内部 helper)

src/main/java/com/maplecode/error/
└── AgentCancelledException.java

src/test/java/com/maplecode/agent/
├── AgentLoopTest.java
├── AgentConfigTest.java
├── BatchTest.java
├── ResponseCollectorTest.java
└── StreamPrinterAgentEventTest.java

src/test/java/com/maplecode/fake/
├── FakeLlmProvider.java
└── RecordingTool.java

src/test/java/com/maplecode/tool/
└── ToolRegistryReadOnlyTest.java

src/test/java/com/maplecode/provider/
├── anthropic/AnthropicStreamParserUsageTest.java
└── openai/OpenAiStreamParserUsageTest.java
```

### 10.2 修改

```
src/main/java/com/maplecode/provider/
├── StreamChunk.java          ← MessageEnd + TokenUsage usage 字段
├── StopReason.java           ← + 4 个枚举（MAX_ITERATIONS/CONSECUTIVE_UNKNOWN/PROVIDER_ERROR/USER_CANCELLED）
├── anthropic/AnthropicStreamParser.java    ← usage 缓存 + MessageEnd 携带 usage
└── openai/OpenAiStreamParser.java          ← 末尾 usage 解析

src/main/java/com/maplecode/tool/
└── ToolRegistry.java         ← + isReadOnly(name) / readOnly()

src/main/java/com/maplecode/session/
└── ChatSession.java          ← + size() / get(int)

src/main/java/com/maplecode/ui/
├── ReplLoop.java             ← 重写主循环；/plan /do /cancel 命令；移除 runOneTurn/handleChunk/TurnAccumulator
└── StreamPrinter.java        ← implements Consumer<AgentEvent>

src/main/java/com/maplecode/App.java        ← 构造 AgentConfig + AgentLoop；注入 ReplLoop
```

### 10.3 测试修改

```
src/test/java/com/maplecode/session/ChatSessionTest.java
src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserTest.java
src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserToolTest.java
src/test/java/com/maplecode/provider/openai/OpenAiStreamParserTest.java
src/test/java/com/maplecode/provider/openai/OpenAiStreamParserToolTest.java
src/test/java/com/maplecode/ui/StreamPrinterTest.java (若存在)
```

---

## 11. 验收清单

- [ ] `mvn package` 产出可执行 jar
- [ ] `mvn test` 全绿（新增 ~25 个 AgentLoop 相关测试 + 6 个修改测试）
- [ ] 模型发 2 个 `read_file` → 两个并发跑，UI 几乎同时看到 `⚙ read_file ...`
- [ ] 模型发 1 个 `write_file` + 1 个 `exec` → 严格串行（先 ⚙ write → ✓ → ⚙ exec → ✓）
- [ ] 达到 25 步迭代上限 → 终端打印 `[agent stopped: MAX_ITERATIONS]`，REPL 不卡死
- [ ] 连续 3 次调未知工具 → `[agent stopped: CONSECUTIVE_UNKNOWN]`
- [ ] `/plan 帮我重构 AgentLoop.java` → 模型只看到 read_file/glob/grep → 输出纯文本计划
- [ ] `/do` → 清空 session → 计划文本作为新输入 → 开放全工具 → 模型开始改文件
- [ ] `/cancel` → 当前 turn 跑完后退出，session 保留
- [ ] Anthropic / OpenAI 各跑一次 IT smoke（`MAPLECODE_RUN_IT=1`），断言看到 token 用量传递到 IterationEnd
- [ ] 关网络（mock ProviderException）→ `[agent stopped: PROVIDER_ERROR]`，REPL 可继续输入
- [ ] PLAN 模式下模型调 write_file → `ToolResult.error("Unknown tool: write_file")` 回灌
- [ ] v2 现有所有非修改的测试仍全绿
- [ ] `*Test.java` 走 Surefire，`*IT.java` 走 failsafe
- [ ] pom 依赖不变

---

## 12. 设计决策记录

1. **协作式取消**（volatile flag）而非异常：本阶段 AgentLoop 是同步阻塞调用，flag 足够；未来若改成异步线程再切异常
2. **Tool 接口不引入 `isReadOnly()`**：安全分类由 ToolRegistry 集中查询，避免侵入 6 个具体工具类
3. **PLAN 模式从 ToolRegistry 子集过滤**：让 ToolExecutor 查不到写工具，比「放出来再拦」更彻底
4. **assistant 消息先 append 再执行工具**：保持 session 完整对话轨迹；为未来 `/resume` 留接口
5. **consecutiveUnknown 在 batch 内重置**：模型只要混进一个已知工具调用就清零；避免误伤「先调三个 unknown 再调 read_file」
6. **MAX_ITERATIONS 检测用 finalStop 哨兵**：初始值 `END_TURN` 表示「自然结束」；循环退出后若还是哨兵值 → 改为 `MAX_ITERATIONS`
7. **`/do` 清空 session 而不是追加**：二段提交语义；plan 是「输入」而非「对话历史」
8. **Token 用量在 MessageEnd 携带而非单独事件**：减少事件类型；UI 暂不显示（v3 静默），为未来统计留接口
9. **StopReason 扩展而非新枚举** `AgentStopReason`：保持一致性；ChatSession 等已有代码不破坏
10. **`/cancel` 在 turn 间生效**：当前实现 AgentLoop.run 是同步阻塞；JLine Ctrl-C 抛到 ReplLoop 设 flag，当前 turn 跑完才生效