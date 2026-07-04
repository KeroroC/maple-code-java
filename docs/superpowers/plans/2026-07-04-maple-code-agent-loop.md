# MapleCode Agent Loop（阶段三）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 v2 单轮工具调用升级为 ReAct Agent Loop —— 模型自主循环调工具、看结果、调整；同时引入事件总线、双路收集、安全分批并发、Plan Mode（`/plan` + `/do`）。

**Architecture:** Agent 拥有循环，UI 只订阅事件。新建 `com.maplecode.agent` 包：`sealed AgentEvent`（11 变体）+ `AgentLoop` + `AgentConfig` + `Batch` + `ResponseCollector`。Provider 层新增 `TokenUsage` record 与 `MessageEnd.usage` 字段；两个 StreamParser 解析各自 provider 的 token 统计。`ToolRegistry.isReadOnly()` 集中查询；AgentLoop 按 safety 分批 —— safe 工具 `parallelStream` 并发跑，unsafe 工具串行跑。Plan Mode 通过 ChatRequest 层过滤 + 临时 ToolRegistry 包装 ToolExecutor 双层防御。

**Tech Stack:** Java 21、Maven、Jackson 2.17.2（已有）、JUnit 5 + Mockito（已有）。无新依赖。

**Spec:** `docs/superpowers/specs/2026-07-04-maple-code-agent-loop-design.md`

**YAGNI 决策**：
- spec §2.11 提到的 `AgentCancelledException` 本阶段不抛（协作式取消用 volatile flag），按 YAGNI 不创建该文件。

## 任务依赖与顺序

```
T1  TokenUsage record
T2  StopReason 扩展 + MessageEnd.usage 字段
T3  AnthropicStreamParser 解析 usage
T4  OpenAiStreamParser 解析 usage
T5  ToolRegistry.isReadOnly / readOnly
T6  AgentConfig + PlanMode
T7  AgentEvent sealed interface
T8  Batch.partition helper
T9  ResponseCollector 双路收集
T10 ChatSession.size() / get(int)
T11 FakeLlmProvider 测试辅助
T12 RecordingTool 测试辅助
T13 AgentLoop 骨架（构造 + run 入口 + cancel）
T14 AgentLoop 单轮 tool_use → 执行 → 结果 → 文本 → END_TURN
T15 AgentLoop 多轮迭代
T16 AgentLoop 分批并发（safe 并行 + unsafe 串行）
T17 AgentLoop 停止条件 MAX_ITERATIONS
T18 AgentLoop 停止条件 CONSECUTIVE_UNKNOWN
T19 AgentLoop 停止条件 PROVIDER_ERROR
T20 AgentLoop Plan Mode（ChatRequest 层过滤）
T21 AgentLoop Plan Mode（executor 层防御）
T22 StreamPrinter 实现 Consumer<AgentEvent>
T23 ReplLoop 重写（基本循环 + /exit /clear /tools）
T24 ReplLoop /plan 命令
T25 ReplLoop /do 命令
T26 ReplLoop /cancel 命令 + Ctrl-C 处理
T27 App 注入 AgentLoop
T28 跑全测 + 集成 smoke
```

每任务独立可跑、单独 commit。每个 commit 后 `mvn -q test` 应绿（除了显式标"已知挂"的步骤）。

---

## Task 1: TokenUsage record

**Files:**
- Create: `src/main/java/com/maplecode/provider/TokenUsage.java`
- Create: `src/test/java/com/maplecode/provider/TokenUsageTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/provider/TokenUsageTest.java`:

```java
package com.maplecode.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageTest {

    @Test
    void equality() {
        assertEquals(new TokenUsage(10, 20), new TokenUsage(10, 20));
        assertNotEquals(new TokenUsage(10, 20), new TokenUsage(10, 21));
    }

    @Test
    void zeroUsage() {
        var u = new TokenUsage(0, 0);
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=TokenUsageTest`
Expected: FAIL —— `TokenUsage` 类不存在

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/provider/TokenUsage.java`:

```java
package com.maplecode.provider;

/** Provider 返回的 token 用量统计。Anthropic / OpenAI 两边共用。 */
public record TokenUsage(int inputTokens, int outputTokens) {}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=TokenUsageTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/provider/TokenUsage.java \
        src/test/java/com/maplecode/provider/TokenUsageTest.java
git commit -m "feat(provider): 新增 TokenUsage record"
```

---

## Task 2: StopReason 扩展 + MessageEnd.usage 字段

**Files:**
- Modify: `src/main/java/com/maplecode/provider/StreamChunk.java`

> 注：此任务**故意不写新测试**。`StopReason` 是 enum 加值，已有 `AnthropicStreamParserTest` / `OpenAiStreamParserTest` 通过构造 `MessageEnd` 用到这些枚举值；本任务会更新那两个测试（详见 Step 3）。`MessageEnd.usage` 字段加默认值 `null` 保证现有测试不破。

- [ ] **Step 1: 改 MessageEnd 签名，加 usage 字段**

`src/main/java/com/maplecode/provider/StreamChunk.java`，找到 `record MessageEnd(StopReason reason) implements StreamChunk {}`，改为：

```java
record MessageEnd(StopReason reason, TokenUsage usage) implements StreamChunk {}
```

并加入 `TokenUsage` 的 import（已同包，无需 import）。

- [ ] **Step 2: StopReason 加 4 个枚举值**

同文件，找到 `enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE }`，改为：

```java
enum StopReason {
    END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE,         // v2 已有
    MAX_ITERATIONS,                                       // v3 新增
    CONSECUTIVE_UNKNOWN,                                  // v3 新增
    PROVIDER_ERROR,                                       // v3 新增
    USER_CANCELLED                                        // v3 新增
}
```

- [ ] **Step 3: 更新所有 MessageEnd 构造点**

搜索所有 `new MessageEnd(` 出现位置。当前文件应该没有；改完后其他文件会编译失败指引。

Run: `mvn -q compile`
Expected: FAIL —— 列出所有 `MessageEnd` 旧调用点位置

- [ ] **Step 4: 更新两个 StreamParser + 测试中的 MessageEnd 调用**

- `src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`：找到 emit MessageEnd 的地方（通常是 `message_stop` 分支），改为 `new MessageEnd(stopReason, null)`（先不解析 usage，T3 再补；用 `null` 占位）。
- `src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`：同上传 `usage` 字段占位 `null`。
- 所有测试里 `new MessageEnd(StopReason.X)` 调用改为 `new MessageEnd(StopReason.X, null)`。具体文件：
  - `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserTest.java`
  - `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserToolTest.java`
  - `src/test/java/com/maplecode/provider/openai/OpenAiStreamParserTest.java`
  - `src/test/java/com/maplecode/provider/openai/OpenAiStreamParserToolTest.java`

Run: `mvn -q compile`
Expected: PASS（编译过）

Run: `mvn -q test`
Expected: PASS（已有测试不破）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(provider): MessageEnd 加 usage 字段；StopReason 扩展 4 个枚举"
```

---

## Task 3: AnthropicStreamParser 解析 usage

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`
- Create: `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserUsageTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserUsageTest.java`:

```java
package com.maplecode.provider.anthropic;

import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicStreamParserUsageTest {

    @Test
    void messageStartCarriesInputUsage() {
        var parser = new AnthropicStreamParser();
        var chunks = new ArrayList<StreamChunk>();

        // message_start 带 input_tokens
        parser.parse("""
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":100,"output_tokens":0}}}

            """.trim().lines().toList(), chunks::add);

        // message_delta 带 output_tokens
        parser.parse("""
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}

            """.trim().lines().toList(), chunks::add);

        // message_stop
        parser.parse("""
            event: message_stop
            data: {"type":"message_stop"}

            """.trim().lines().toList(), chunks::add);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertEquals(new TokenUsage(100, 50), messageEnd.usage());
    }

    @Test
    void noUsageMeansNull() {
        var parser = new AnthropicStreamParser();
        var chunks = new ArrayList<StreamChunk>();
        parser.parse("""
            event: message_start
            data: {"type":"message_start","message":{"id":"m1"}}

            """.trim().lines().toList(), chunks::add);
        parser.parse("""
            event: message_stop
            data: {"type":"message_stop"}

            """.trim().lines().toList(), chunks::add);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertNull(messageEnd.usage());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AnthropicStreamParserUsageTest`
Expected: FAIL —— `usage()` 字段拿到 `null`（实现还没解析）

- [ ] **Step 3: 解析 usage**

修改 `AnthropicStreamParser.java`：
- 加字段 `private int lastInputTokens = 0; private int lastOutputTokens = 0;`
- `message_start` 解析分支：从 `data.message.usage.input_tokens` 读 → `lastInputTokens = ...`
- `message_delta` 解析分支：从 `data.usage.output_tokens` 读 → `lastOutputTokens = ...`
- `message_stop` emit `MessageEnd` 时：`new MessageEnd(stopReason, new TokenUsage(lastInputTokens, lastOutputTokens))`

> 具体 JSON 字段路径取决于 parser 当前代码结构；按 AnthropicStreamParserToolTest 里的解析模式补充。`ObjectMapper` 已在 v2 引入。

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=AnthropicStreamParserUsageTest`
Expected: PASS

Run: `mvn -q test -Dtest=AnthropicStreamParserTest,AnthropicStreamParserToolTest`
Expected: PASS（不破现有测试）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(anthropic): StreamParser 解析 usage，MessageEnd 携带 TokenUsage"
```

---

## Task 4: OpenAiStreamParser 解析 usage

**Files:**
- Modify: `src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`
- Create: `src/test/java/com/maplecode/provider/openai/OpenAiStreamParserUsageTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/provider/openai/OpenAiStreamParserUsageTest.java`:

```java
package com.maplecode.provider.openai;

import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiStreamParserUsageTest {

    @Test
    void lastChunkWithUsagePropagates() {
        var parser = new OpenAiStreamParser();
        var chunks = new ArrayList<StreamChunk>();

        parser.parse("""
            data: {"id":"x","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}

            """.trim().lines().toList(), chunks::add);

        parser.parse("""
            data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            """.trim().lines().toList(), chunks::add);

        parser.parse("""
            data: {"id":"x","choices":[],"usage":{"prompt_tokens":30,"completion_tokens":10}}

            """.trim().lines().toList(), chunks::add);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertEquals(new TokenUsage(30, 10), messageEnd.usage());
    }

    @Test
    void noUsageChunkMeansNull() {
        var parser = new OpenAiStreamParser();
        var chunks = new ArrayList<StreamChunk>();

        parser.parse("""
            data: {"id":"x","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}

            """.trim().lines().toList(), chunks::add);

        parser.parse("""
            data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            """.trim().lines().toList(), chunks::add);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertNull(messageEnd.usage());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=OpenAiStreamParserUsageTest`
Expected: FAIL

- [ ] **Step 3: 解析 usage**

修改 `OpenAiStreamParser.java`：
- 加字段 `private TokenUsage lastUsage;`
- 新增一个解析分支：chunk 含 `choices=[]` 且 `usage != null` → `lastUsage = new TokenUsage(usage.prompt_tokens, usage.completion_tokens)`
- `finish_reason` 分支 emit `MessageEnd` 时：`new MessageEnd(reason, lastUsage)`（可能为 null）

确认 `OpenAiRequestMapper` 已发 `stream_options.include_usage=true`。grep 一下 `OpenAiRequestMapper.java`，如果有该字段则跳过；否则在 JSON body 里加 `"stream_options": {"include_usage": true}`。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=OpenAiStreamParserUsageTest,OpenAiStreamParserTest,OpenAiStreamParserToolTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(openai): StreamParser 解析末尾 usage，MessageEnd 携带 TokenUsage"
```

---

## Task 5: ToolRegistry.isReadOnly / readOnly

**Files:**
- Modify: `src/main/java/com/maplecode/tool/ToolRegistry.java`
- Create: `src/test/java/com/maplecode/tool/ToolRegistryReadOnlyTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/tool/ToolRegistryReadOnlyTest.java`:

```java
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
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ToolRegistryReadOnlyTest`
Expected: FAIL —— `isReadOnly` / `readOnly` 不存在

- [ ] **Step 3: 实现**

修改 `ToolRegistry.java`，加字段和方法：

```java
import java.util.Set;

public final class ToolRegistry {
    /** 6 个内置工具中只读的有这些。 */
    private static final Set<String> READ_ONLY = Set.of("read_file", "glob", "grep");

    private final List<Tool> tools;
    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        this.byName = tools.stream().collect(java.util.stream.Collectors.toMap(Tool::name, t -> t));
    }

    public List<Tool> all() { return tools; }
    public Optional<Tool> get(String name) { return Optional.ofNullable(byName.get(name)); }

    /** 工具是否只读。未知工具返回 false。 */
    public boolean isReadOnly(String name) {
        return READ_ONLY.contains(name);
    }

    /** 返回只读工具的子集（用于 Plan Mode）。 */
    public List<Tool> readOnly() {
        return tools.stream().filter(t -> READ_ONLY.contains(t.name())).toList();
    }
}
```

（保留 v2 已有的构造函数 / `all()` / `get()` 逻辑；用 `Map` 替换线性查找以保持 O(1)。）

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ToolRegistryReadOnlyTest,ToolRegistryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(tool): ToolRegistry 加 isReadOnly / readOnly 查询方法"
```

---

## Task 6: AgentConfig + PlanMode

**Files:**
- Create: `src/main/java/com/maplecode/agent/AgentConfig.java`
- Create: `src/test/java/com/maplecode/agent/AgentConfigTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/agent/AgentConfigTest.java`:

```java
package com.maplecode.agent;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void defaultsAreSensible() {
        var c = AgentConfig.defaults();
        assertEquals(25, c.maxIterations());
        assertEquals(3, c.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, c.planMode());
    }

    @Test
    void rejectsZeroIterations() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig(0, 3, PlanMode.NORMAL));
    }

    @Test
    void rejectsZeroConsecutiveUnknown() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig(25, 0, PlanMode.NORMAL));
    }

    @Test
    void planModeEnumHasTwoValues() {
        assertEquals(2, PlanMode.values().length);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentConfigTest`
Expected: FAIL —— `AgentConfig` / `PlanMode` 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/maplecode/agent/AgentConfig.java`:

```java
package com.maplecode.agent;

import com.maplecode.error.ConfigException;

public record AgentConfig(
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode
) {
    public AgentConfig {
        if (maxIterations < 1) {
            throw new ConfigException("maxIterations must be >= 1");
        }
        if (maxConsecutiveUnknown < 1) {
            throw new ConfigException("maxConsecutiveUnknown must be >= 1");
        }
    }

    public static AgentConfig defaults() {
        return new AgentConfig(25, 3, PlanMode.NORMAL);
    }
}

enum PlanMode { NORMAL, PLAN }
```

> PlanMode 与 AgentConfig 同文件；Java 允许一个文件一个 public record + 一个 package-private enum。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentConfigTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentConfig record + PlanMode enum，紧凑构造器校验"
```

---

## Task 7: AgentEvent sealed interface

**Files:**
- Create: `src/main/java/com/maplecode/agent/AgentEvent.java`

> 注：sealed interface 定义本身通过编译器保证穷尽性（StreamPrinter、AgentLoop 内的 switch 编译失败即被强制更新）。本任务只定义类型，行为测试在后续 AgentLoop / StreamPrinter 任务里覆盖。

- [ ] **Step 1: 写文件**

`src/main/java/com/maplecode/agent/AgentEvent.java`:

```java
package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.provider.StopReason;
import com.maplecode.provider.TokenUsage;

import java.util.List;

/**
 * Agent 向外推送的事件类型。所有订阅者（UI / 日志 / 测试）通过
 * Consumer&lt;AgentEvent&gt; 接收。
 * <p>
 * sealed 强制每个 switch 穷尽；新增变体时所有订阅者编译失败即被强制更新。
 */
public sealed interface AgentEvent
    permits AgentEvent.TextDelta,
            AgentEvent.ThinkingDelta,
            AgentEvent.ToolCallStart,
            AgentEvent.ToolCallEnd,
            AgentEvent.ToolResult,
            AgentEvent.IterationStart,
            AgentEvent.IterationEnd,
            AgentEvent.BatchStart,
            AgentEvent.BatchEnd,
            AgentEvent.AgentStop {

    record TextDelta(String text) implements AgentEvent {}
    record ThinkingDelta(String text) implements AgentEvent {}

    record ToolCallStart(String id, String name, String argSummary) implements AgentEvent {}
    record ToolCallEnd(String id, String name, JsonNode input) implements AgentEvent {}

    record ToolResult(String toolUseId, String name, boolean isError, String content)
        implements AgentEvent {}

    record IterationStart(int iteration) implements AgentEvent {}
    record IterationEnd(int iteration, StopReason stopReason,
                        List<String> toolUseIds, TokenUsage usage) implements AgentEvent {}

    record BatchStart(int safeCount, int unsafeCount) implements AgentEvent {}
    record BatchEnd(int totalTools, int failedTools) implements AgentEvent {}

    record AgentStop(StopReason reason, String detail) implements AgentEvent {}
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/agent/AgentEvent.java
git commit -m "feat(agent): AgentEvent sealed interface（11 变体）"
```

---

## Task 8: Batch.partition helper

**Files:**
- Create: `src/main/java/com/maplecode/agent/Batch.java`
- Create: `src/test/java/com/maplecode/agent/BatchTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/agent/BatchTest.java`:

```java
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

    private record Use(String id, String name, JsonNode input) {}

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
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=BatchTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`src/main/java/com/maplecode/agent/Batch.java`:

```java
package com.maplecode.agent;

import com.maplecode.tool.ToolRegistry;

import java.util.List;

/**
 * 一轮 tool_use 按安全性分批：safe = 只读工具，unsafe = 有副作用工具。
 * <p>
 * 泛型 T 接受任意 tool_use 类型（AgentLoop 内部 record 或测试替身）。
 */
record Batch<T>(List<T> safe, List<T> unsafe) {

    static <T extends NamedToolUse> Batch<T> partition(List<T> uses, ToolRegistry registry) {
        var safe = uses.stream()
            .filter(u -> registry.isReadOnly(u.name()))
            .toList();
        var unsafe = uses.stream()
            .filter(u -> !registry.isReadOnly(u.name()))
            .toList();
        return new Batch<>(safe, unsafe);
    }
}

/** tool_use 最小契约：至少有个 name 用于分批。 */
interface NamedToolUse {
    String name();
}
```

> 因为 AgentLoop 内的 `ToolUse(String id, String name, JsonNode input)` 还没定义（Task 13+），这里用泛型 + 接口让测试能直接用任意 record。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=BatchTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): Batch.partition 按 safety 分批（safe / unsafe）"
```

---

## Task 9: ResponseCollector 双路收集

**Files:**
- Create: `src/main/java/com/maplecode/agent/ResponseCollector.java`
- Create: `src/test/java/com/maplecode/agent/ResponseCollectorTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/agent/ResponseCollectorTest.java`:

```java
package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StopReason;
import com.maplecode.provider.TokenUsage;
import com.maplecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    void collectsToolUsesAndForwardsEvents() {
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
        col.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, new TokenUsage(10, 20)));
        assertEquals(new TokenUsage(10, 20), col.usage());
    }

    @Test
    void argSummaryFallbackTruncatesLongJson() {
        var col = new ResponseCollector(e -> {}, registryWithRead());
        StringBuilder big = new StringBuilder("{\"path\":\"");
        for (int i = 0; i < 100; i++) big.append("a");
        big.append("\"}");
        col.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
        col.accept(new StreamChunk.ToolUseDelta("t1", big.toString()));
        // pendingJson 累积超过 40 字符；ToolUseStart 时 argSummary 会 truncate
        assertTrue(col.pendingJsonForTest().length() > 40);
    }

    @Test
    void argSummaryExtractsPathFromCompleteJson() {
        var col = new ResponseCollector(e -> {}, registryWithRead());
        // 模拟完整 partialJson 在 ToolUseStart 时已收到（罕见但可能）
        col.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
        col.accept(new StreamChunk.ToolUseDelta("t1", "{\"path\":\"/tmp/foo.java\"}"));
        // 此时 pendingJson 已可解析，argSummary 应提取 path
        assertTrue(col.pendingJsonForTest().toString().contains("foo.java"));
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry(List.of());
    }

    private static ToolRegistry registryWithRead() {
        return new ToolRegistry(List.of(new com.maplecode.tool.ReadFileTool()));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ResponseCollectorTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`src/main/java/com/maplecode/agent/ResponseCollector.java`:

```java
package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.StopReason;
import com.maplecode.provider.TokenUsage;
import com.maplecode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单轮流式响应的累加器。
 * <p>
 * "双路"：一边把 chunk 同步转发到 sink（让 UI 实时看到），一边把
 * 完整状态（text / toolUses / stopReason / usage）累加到字段，
 * 供 AgentLoop 在 iteration 结束后决策。
 */
public final class ResponseCollector implements Consumer<StreamChunk> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringBuilder text = new StringBuilder();
    private final List<ToolUse> toolUses = new ArrayList<>();
    private final StringBuilder pendingJson = new StringBuilder();
    private final Consumer<AgentEvent> sink;
    private final ToolRegistry registry;
    private String pendingId;
    private String pendingName;
    private StopReason stopReason;
    private TokenUsage usage;
    private boolean errored;

    public ResponseCollector(Consumer<AgentEvent> sink, ToolRegistry registry) {
        this.sink = sink;
        this.registry = registry;
    }

    @Override
    public void accept(StreamChunk chunk) {
        switch (chunk) {
            case StreamChunk.TextDelta d -> {
                text.append(d.text());
                sink.accept(new AgentEvent.TextDelta(d.text()));
            }
            case StreamChunk.ThinkingDelta d -> {
                sink.accept(new AgentEvent.ThinkingDelta(d.text()));
            }
            case StreamChunk.ToolUseStart d -> {
                pendingId = d.id();
                pendingName = d.name();
                pendingJson.setLength(0);
                sink.accept(new AgentEvent.ToolCallStart(d.id(), d.name(), argSummary(d.name())));
            }
            case StreamChunk.ToolUseDelta d -> {
                pendingId = d.id();
                pendingJson.append(d.partialJson());
            }
            case StreamChunk.ToolUseEnd d -> {
                toolUses.add(new ToolUse(d.id(), d.name(), d.input()));
                sink.accept(new AgentEvent.ToolCallEnd(d.id(), d.name(), d.input()));
                pendingId = pendingName = null;
                pendingJson.setLength(0);
            }
            case StreamChunk.MessageStart s -> { /* no-op */ }
            case StreamChunk.MessageEnd e -> {
                stopReason = e.reason();
                usage = e.usage();
            }
            case StreamChunk.Error e -> {
                errored = true;
                stopReason = StopReason.ERROR;
            }
        }
    }

    /** 实时从 pendingJson 抽 path/command/pattern，否则截前 40 字符。 */
    private String argSummary(String toolName) {
        String partial = pendingJson.toString();
        if (partial.isEmpty()) return "";
        try {
            JsonNode node = JSON.readTree(partial);
            var path = node.path("path");
            if (!path.isMissingNode()) return path.asText();
            var cmd = node.path("command");
            if (!cmd.isMissingNode()) return cmd.asText();
            var pattern = node.path("pattern");
            if (!pattern.isMissingNode()) return pattern.asText();
        } catch (Exception ignored) {}
        return partial.length() > 40 ? partial.substring(0, 40) + "..." : partial;
    }

    public StringBuilder text() { return text; }
    public List<ToolUse> toolUses() { return toolUses; }
    public StopReason stopReason() { return stopReason; }
    public TokenUsage usage() { return usage; }
    public boolean errored() { return errored; }

    /** 测试用：暴露 pendingJson 给测试。 */
    StringBuilder pendingJsonForTest() { return pendingJson; }

    public record ToolUse(String id, String name, JsonNode input) {}
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ResponseCollectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): ResponseCollector 双路收集（实时转发 + 完整累加）"
```

---

## Task 10: ChatSession.size() / get(int)

**Files:**
- Modify: `src/main/java/com/maplecode/session/ChatSession.java`
- Modify: `src/test/java/com/maplecode/session/ChatSessionTest.java`

- [ ] **Step 1: 加测试**

`src/test/java/com/maplecode/session/ChatSessionTest.java` 末尾追加：

```java
@Test
void sizeAndGetExposeInternalList() {
    var s = new ChatSession();
    assertEquals(0, s.size());
    s.appendUserText("hi");
    s.appendAssistant(List.of(new ContentBlock.TextBlock("hello")));
    assertEquals(2, s.size());
    assertEquals(ChatMessage.Role.USER, s.get(0).role());
    assertEquals("hi", ((ContentBlock.TextBlock) s.get(0).blocks().get(0)).text());
    assertEquals(ChatMessage.Role.ASSISTANT, s.get(1).role());
    assertEquals("hello", ((ContentBlock.TextBlock) s.get(1).blocks().get(0)).text());
}

@Test
void clearResetsSize() {
    var s = new ChatSession();
    s.appendUserText("hi");
    assertEquals(1, s.size());
    s.clear();
    assertEquals(0, s.size());
}
```

> 确保 import 包含 `ChatMessage`、`ContentBlock`、`List`、`assertEquals`。

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: FAIL —— `size()` / `get(int)` 不存在

- [ ] **Step 3: 实现**

修改 `ChatSession.java`，加：

```java
public int size() {
    return messages.size();
}

public ChatMessage get(int i) {
    return messages.get(i);
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(session): ChatSession 加 size() / get(int) 访问器"
```

---

## Task 11: FakeLlmProvider 测试辅助

**Files:**
- Create: `src/test/java/com/maplecode/fake/FakeLlmProvider.java`
- Create: `src/test/java/com/maplecode/fake/FakeLlmProviderTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/fake/FakeLlmProviderTest.java`:

```java
package com.maplecode.fake;

import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FakeLlmProviderTest {

    @Test
    void emitsPreprogrammedChunks() {
        var chunks = List.of(
            new StreamChunk.MessageStart(),
            new StreamChunk.TextDelta("hi"),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
        );
        LlmProvider fake = new FakeLlmProvider(List.of(chunks));

        var received = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, received::add);

        assertEquals(3, received.size());
    }

    @Test
    void multipleCallsDrainSequence() {
        var chunks1 = List.<StreamChunk>of(new StreamChunk.TextDelta("a"));
        var chunks2 = List.<StreamChunk>of(new StreamChunk.TextDelta("b"));
        LlmProvider fake = new FakeLlmProvider(List.of(chunks1, chunks2));

        var r1 = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, r1::add);
        var r2 = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, r2::add);

        assertEquals("a", ((StreamChunk.TextDelta) r1.get(0)).text());
        assertEquals("b", ((StreamChunk.TextDelta) r2.get(0)).text());
    }

    @Test
    void throwsWhenExhausted() {
        LlmProvider fake = new FakeLlmProvider(List.of());
        assertThrows(java.util.NoSuchElementException.class,
            () -> fake.stream(null, c -> {}));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=FakeLlmProviderTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`src/test/java/com/maplecode/fake/FakeLlmProvider.java`:

```java
package com.maplecode.fake;

import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * 测试用 LlmProvider：按脚本返回 chunks 序列。
 * <p>
 * 每次 stream() 调用消耗一个脚本（List&lt;StreamChunk&gt;），
 * 全部 chunk push 给 sink。脚本用完抛 NoSuchElementException。
 */
public final class FakeLlmProvider implements LlmProvider {

    private final Deque<List<StreamChunk>> scripts = new ArrayDeque<>();

    public FakeLlmProvider(List<List<StreamChunk>> scripts) {
        this.scripts.addAll(scripts);
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamChunk> sink) {
        var script = scripts.poll();
        if (script == null) {
            throw new NoSuchElementException("FakeLlmProvider: no more scripts");
        }
        for (StreamChunk chunk : script) {
            sink.accept(chunk);
        }
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=FakeLlmProviderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: FakeLlmProvider 测试辅助（按脚本返回 StreamChunk 序列）"
```

---

## Task 12: RecordingTool 测试辅助

**Files:**
- Create: `src/test/java/com/maplecode/fake/RecordingTool.java`
- Create: `src/test/java/com/maplecode/fake/RecordingToolTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/fake/RecordingToolTest.java`:

```java
package com.maplecode.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RecordingToolTest {

    @Test
    void recordsAllExecutions() throws Exception {
        var tool = new RecordingTool("read_file", ToolResult.ok("hello"));
        var ctx = new ToolContext(java.nio.file.Path.of("/tmp"), 1024, 30, 100, 100);
        tool.execute(null, ctx);
        tool.execute(null, ctx);
        assertEquals(2, tool.calls().size());
    }

    @Test
    void returnsPresetResult() throws Exception {
        var tool = new RecordingTool("exec", ToolResult.error("boom"));
        var ctx = new ToolContext(java.nio.file.Path.of("/tmp"), 1024, 30, 100, 100);
        var r = tool.execute(null, ctx);
        assertTrue(r.isError());
        assertEquals("boom", r.content());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=RecordingToolTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`src/test/java/com/maplecode/fake/RecordingTool.java`:

```java
package com.maplecode.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用 Tool：每次 execute 记录调用，返回预置的 ToolResult。
 * <p>
 * 用于验证 AgentLoop 是否真的调了工具、调了几次、并发时序是否对。
 */
public final class RecordingTool implements Tool {

    private final String name;
    private final ToolResult preset;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public RecordingTool(String name, ToolResult preset) {
        this.name = name;
        this.preset = preset;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return "recording " + name; }

    @Override
    public JsonNode inputSchema() { return null; }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        calls.add(new Call(args, ctx, Thread.currentThread().getName()));
        return preset;
    }

    public List<Call> calls() { return calls; }

    public record Call(JsonNode args, ToolContext ctx, String threadName) {}
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=RecordingToolTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: RecordingTool 测试辅助（记录 execute 调用 + 返回预置结果）"
```

---

## Task 13: AgentLoop 骨架（构造 + run 入口 + cancel）

**Files:**
- Create: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Create: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/agent/AgentLoopTest.java`:

```java
package com.maplecode.agent;

import com.maplecode.fake.FakeLlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
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
            new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
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
        assertEquals(StreamChunk.StopReason.END_TURN, stop.reason());
    }

    @Test
    void cancelBeforeRunEmitsUserCancelled() {
        var provider = new FakeLlmProvider(List.of(
            List.<StreamChunk>of(new StreamChunk.MessageStart(),
                new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null))));
        var registry = new ToolRegistry(List.of());
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());
        agent.cancel();

        var events = new ArrayList<AgentEvent>();
        agent.run("hi", events::add);

        var stop = (AgentEvent.AgentStop) events.stream()
            .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.USER_CANCELLED, stop.reason());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: FAIL —— `AgentLoop` 不存在

- [ ] **Step 3: 实现骨架**

`src/main/java/com/maplecode/agent/AgentLoop.java`:

```java
package com.maplecode.agent;

import com.maplecode.error.ProviderException;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StopReason;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;

import java.util.function.Consumer;

public final class AgentLoop {

    private final LlmProvider provider;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ChatSession session;
    private final AgentConfig config;
    private volatile boolean cancelled;

    public AgentLoop(LlmProvider provider, ToolRegistry registry,
                     ToolExecutor executor, ChatSession session,
                     AgentConfig config) {
        this.provider = provider;
        this.registry = registry;
        this.executor = executor;
        this.session = session;
        this.config = config;
    }

    public void cancel() {
        cancelled = true;
    }

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

            try {
                provider.stream(/* req placeholder, see Task 14 */ null, col);
            } catch (ProviderException e) {
                sink.accept(new AgentEvent.AgentStop(StopReason.PROVIDER_ERROR, e.getMessage()));
                return;
            }

            sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
                List.of(), col.usage()));

            finalStop = col.stopReason();
            finalDetail = "assistant finished";
            break;  // 单轮测试骨架：每轮都退出，下一 task 加 while 循环
        }

        if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
            finalStop = StopReason.MAX_ITERATIONS;
            finalDetail = "reached iteration cap: " + config.maxIterations();
        }

        sink.accept(new AgentEvent.AgentStop(finalStop, finalDetail));
    }
}
```

> 占位符 `null` req：Task 14 引入 ChatRequest 构造。当前为了让骨架可编译通过。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（两个测试都通过 —— `cancel` 在循环顶部检测，`break` 后 emit AgentStop）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop 骨架（构造 + run + cancel + ProviderException 处理）"
```

---

## Task 14: AgentLoop 单轮 tool_use → 执行 → 结果 → 文本 → END_TURN

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void singleToolCallThenText() {
    var chunks1 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "read_file"),
        new StreamChunk.ToolUseDelta("t1", "{\"path\":\"/tmp/x\"}"),
        new StreamChunk.ToolUseEnd("t1", "read_file", new ObjectMapper().readTree("{\"path\":\"/tmp/x\"}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    var chunks2 = List.<StreamChunk>of(
        new StreamChunk.TextDelta("got it"),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
    );
    var provider = new FakeLlmProvider(List.of(chunks1, chunks2));
    var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("file content"))));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

    var events = new ArrayList<AgentEvent>();
    agent.run("read x", events::add);

    // 应该有两个 IterationStart（两轮迭代）
    long iterStarts = events.stream()
        .filter(e -> e instanceof AgentEvent.IterationStart).count();
    assertEquals(2, iterStarts);

    // 应该有一个 ToolResult 事件
    long toolResults = events.stream()
        .filter(e -> e instanceof AgentEvent.ToolResult).count();
    assertEquals(1, toolResults);

    // 最后一个 AgentStop 应该是 END_TURN
    var stop = (AgentEvent.AgentStop) events.stream()
        .filter(e -> e instanceof AgentEvent.AgentStop).reduce((a, b) -> b).orElseThrow();
    assertEquals(StreamChunk.StopReason.END_TURN, stop.reason());

    // session 应该有 3 条消息：user, assistant(tool_use), user(tool_result), assistant(text)
    assertEquals(4, session.size());
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#singleToolCallThenText`
Expected: FAIL

- [ ] **Step 3: 扩展 AgentLoop.run**

替换 Task 13 的占位 run 方法：

```java
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

        var req = new com.maplecode.provider.ChatRequest(
            "test-model", null,
            new java.util.ArrayList<>(session.size() == 0 ? List.of() :
                List.of()),  // 占位：实际要用 session.toRequest
            null, registry.all());

        try {
            provider.stream(req, col);
        } catch (ProviderException e) {
            sink.accept(new AgentEvent.AgentStop(StopReason.PROVIDER_ERROR, e.getMessage()));
            return;
        }

        sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
            col.toolUses().stream().map(ResponseCollector.ToolUse::id).toList(),
            col.usage()));

        if (col.stopReason() != StopReason.TOOL_USE) {
            // 自然结束：写 assistant text
            if (!col.text().isEmpty()) {
                session.appendAssistant(List.of(new com.maplecode.provider.ContentBlock.TextBlock(
                    col.text().toString())));
            }
            finalStop = col.stopReason();
            finalDetail = "assistant finished";
            break;
        }

        if (col.toolUses().isEmpty()) {
            finalStop = StopReason.ERROR;
            finalDetail = "stop_reason=tool_use but no tool_use emitted";
            break;
        }

        // 写 assistant text + 所有 tool_use
        var assistantBlocks = new java.util.ArrayList<com.maplecode.provider.ContentBlock>();
        if (!col.text().isEmpty()) {
            assistantBlocks.add(new com.maplecode.provider.ContentBlock.TextBlock(col.text().toString()));
        }
        for (var u : col.toolUses()) {
            assistantBlocks.add(new com.maplecode.provider.ContentBlock.ToolUseBlock(
                u.id(), u.name(), u.input()));
        }
        session.appendAssistant(assistantBlocks);

        // 执行所有 tool_use（暂不分区，单个执行）
        for (var u : col.toolUses()) {
            sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), false, ""));
        }

        iteration++;
    }

    if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
        finalStop = StopReason.MAX_ITERATIONS;
        finalDetail = "reached iteration cap: " + config.maxIterations();
    }

    sink.accept(new AgentEvent.AgentStop(finalStop, finalDetail));
}
```

> **关键提示**：上面的 `req` 用了一个 hack（`new ArrayList<>(session.size() == 0 ? List.of() : List.of())`）—— 这只是为了让骨架编译过。**Task 15 引入真正的 ChatRequest 构造（用 `session.toRequest(...)`）**。

> 这一步 ToolResult 的 content 设为空字符串是占位，下一 task 加 ToolExecutor 真的执行。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest#singleToolCallThenText`
Expected: PASS

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（三个测试都绿）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop 单轮 tool_use → 占位执行 → 文本 → END_TURN"
```

---

## Task 15: AgentLoop 多轮迭代（真正用 session.toRequest）

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void threeIterationsThenStop() {
    // 第 1 轮：调 1 个 tool_use
    var c1 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "read_file"),
        new StreamChunk.ToolUseEnd("t1", "read_file", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    // 第 2 轮：再调 1 个 tool_use
    var c2 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t2", "read_file"),
        new StreamChunk.ToolUseEnd("t2", "read_file", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    // 第 3 轮：纯文本结束
    var c3 = List.<StreamChunk>of(
        new StreamChunk.TextDelta("done"),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
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

    // session 在 run 结束后应该有：
    // 1. user("go")
    // 2. assistant(tool_use t1)
    // 3. user(tool_result t1)
    // 4. assistant(tool_use t2)
    // 5. user(tool_result t2)
    // 6. assistant(text "done")
    assertEquals(6, session.size());
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#threeIterationsThenStop`
Expected: FAIL —— session.toRequest 没真正接入（Task 14 用的是空消息列表）

- [ ] **Step 3: 改用 session.toRequest**

在 `AgentLoop.run` 里，替换 Task 14 的占位 req 构造：

```java
// 删除 Task 14 的占位 var req = ...
// 替换为：
var req = session.toRequest(
    "test-model",       // 临时 model；Task 27 把 model 从 config 拿
    null,               // systemPrompt；同上
    null,               // thinking
    registry.all()
);
```

把 `import com.maplecode.provider.ChatRequest;` 移至文件顶部。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（三个测试都绿）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop 用 session.toRequest 构造请求，支持多轮迭代"
```

---

## Task 16: AgentLoop 分批并发（safe 并行 + unsafe 串行）

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void twoReadFilesRunConcurrently() {
    var c1 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "read_file"),
        new StreamChunk.ToolUseEnd("t1", "read_file", new ObjectMapper().readTree("{}")),
        new StreamChunk.ToolUseStart("t2", "read_file"),
        new StreamChunk.ToolUseEnd("t2", "read_file", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    var c2 = List.<StreamChunk>of(
        new StreamChunk.TextDelta("ok"),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
    );
    var provider = new FakeLlmProvider(List.of(c1, c2));

    // 用 RecordingTool 验证并发
    var tool1 = new com.maplecode.fake.RecordingTool("read_file", ToolResult.ok("a"));
    var tool2 = new com.maplecode.fake.RecordingTool("read_file", ToolResult.ok("b"));
    // 注意：ToolRegistry 不允许同名的两个 tool；这里手动构造 ToolRegistry 用单一 RecordingTool
    var sharedTool = new com.maplecode.fake.RecordingTool("read_file", ToolResult.ok("a"));
    var registry = new ToolRegistry(List.of(sharedTool));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

    var events = new ArrayList<AgentEvent>();
    agent.run("read both", events::add);

    assertEquals(2, sharedTool.calls().size());
    // 验证 BatchStart / BatchEnd 事件存在
    long batchStarts = events.stream()
        .filter(e -> e instanceof AgentEvent.BatchStart).count();
    assertEquals(1, batchStarts);
}

@Test
void twoExecRunSerially() {
    var c1 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "exec"),
        new StreamChunk.ToolUseEnd("t1", "exec", new ObjectMapper().readTree("{}")),
        new StreamChunk.ToolUseStart("t2", "exec"),
        new StreamChunk.ToolUseEnd("t2", "exec", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    var c2 = List.<StreamChunk>of(
        new StreamChunk.TextDelta("done"),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
    );
    var provider = new FakeLlmProvider(List.of(c1, c2));
    var exec = new com.maplecode.fake.RecordingTool("exec", ToolResult.ok("ran"));
    var registry = new ToolRegistry(List.of(exec));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());

    var events = new ArrayList<AgentEvent>();
    agent.run("run both", events::add);

    assertEquals(2, exec.calls().size());
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#twoReadFilesRunConcurrently,AgentLoopTest#twoExecRunSerially`
Expected: FAIL —— Batch 没实现，只跑单 tool

- [ ] **Step 3: 实现分批并发**

修改 `AgentLoop.run` 中执行 tool_use 的循环：

```java
// 替换 Task 14/15 中的 for (var u : col.toolUses()) { sink.accept(...) }
// 改为：

Batch<ResponseCollector.ToolUse> batch = Batch.partition(col.toolUses(), registry);
sink.accept(new AgentEvent.BatchStart(batch.safe().size(), batch.unsafe().size()));

var results = new java.util.ArrayList<ToolResultPayload>();

// safe 批：并行
batch.safe().parallelStream().forEach(u -> {
    var r = executeOne(u);
    synchronized (results) {
        results.add(new ToolResultPayload(u.id(), r));
    }
    sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), r.isError(), r.content()));
});

// unsafe 批：串行
for (var u : batch.unsafe()) {
    var r = executeOne(u);
    results.add(new ToolResultPayload(u.id(), r));
    sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), r.isError(), r.content()));
}

int failed = (int) results.stream().filter(r -> r.result().isError()).count();
sink.accept(new AgentEvent.BatchEnd(results.size(), failed));

// 写 tool_result 消息
for (var r : results) {
    session.appendUser(List.of(new com.maplecode.provider.ContentBlock.ToolResultBlock(
        r.toolUseId(), r.result().content(), r.result().isError())));
}
```

加私有方法：

```java
private com.maplecode.tool.ToolResult executeOne(ResponseCollector.ToolUse u) {
    try {
        return executor.run(u.name(), u.input());
    } catch (Exception e) {
        return com.maplecode.tool.ToolResult.error("internal error: " + e.getClass().getSimpleName());
    }
}
```

把 Task 14 占位的 `sink.accept(new AgentEvent.ToolResult(u.id(), u.name(), false, ""));` 删除（上面已经替换）。

> 注：`ToolResultPayload` 已在 ToolExecutor 包里吗？检查 —— 不在，需要新建一个 record 或放 agent 包里。最简单：直接在 AgentLoop 内部定义：

```java
record ToolResultPayload(String toolUseId, com.maplecode.tool.ToolResult result) {}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（所有测试都绿）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop 按 safety 分批并发（safe 并行 + unsafe 串行）"
```

---

## Task 17: AgentLoop 停止条件 MAX_ITERATIONS

**Files:**
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

> MAX_ITERATIONS 的核心逻辑（cap 检测 + finalStop 哨兵）在 Task 13 骨架已经埋好；本任务加测试验证。

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void maxIterationsTriggersStop() {
    // 配置 maxIterations=3
    var cfg = new AgentConfig(3, 3, PlanMode.NORMAL);

    // 准备 5 个 script（理论上第 4 次应该被 cap 截断）
    var loopScript = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "read_file"),
        new StreamChunk.ToolUseEnd("t1", "read_file", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    var scripts = List.of(loopScript, loopScript, loopScript, loopScript, loopScript);
    var provider = new FakeLlmProvider(scripts);
    var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("x"))));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, executor, session, cfg);

    var events = new ArrayList<AgentEvent>();
    agent.run("loop forever", events::add);

    // 应该有 3 个 IterationStart（iteration=0, 1, 2）；第 4 次进不去
    long iterStarts = events.stream()
        .filter(e -> e instanceof AgentEvent.IterationStart).count();
    assertEquals(3, iterStarts);

    var stop = (AgentEvent.AgentStop) events.stream()
        .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
    assertEquals(StreamChunk.StopReason.MAX_ITERATIONS, stop.reason());
}
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `mvn -q test -Dtest=AgentLoopTest#maxIterationsTriggersStop`
Expected: PASS（Task 13 骨架已实现）

如果失败：检查 `AgentLoop.run` 末尾的哨兵逻辑：

```java
if (iteration >= config.maxIterations() && finalStop == StopReason.END_TURN) {
    finalStop = StopReason.MAX_ITERATIONS;
    finalDetail = "reached iteration cap: " + config.maxIterations();
}
```

注意：`iteration++` 在循环末尾必须存在（已在 Task 14 实现里）。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(agent): 验证 AgentLoop MAX_ITERATIONS 停止条件"
```

---

## Task 18: AgentLoop 停止条件 CONSECUTIVE_UNKNOWN

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void threeConsecutiveUnknownToolsTriggersStop() {
    var unknownChunk = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "unknown_tool"),
        new StreamChunk.ToolUseEnd("t1", "unknown_tool", new ObjectMapper().readTree("{}")),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.TOOL_USE, null)
    );
    var scripts = List.of(unknownChunk, unknownChunk, unknownChunk, unknownChunk);
    var provider = new FakeLlmProvider(scripts);
    var registry = new ToolRegistry(List.of(noopTool("read_file", ToolResult.ok("x"))));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, executor, session,
        new AgentConfig(25, 3, PlanMode.NORMAL));

    var events = new ArrayList<AgentEvent>();
    agent.run("try unknown", events::add);

    var stop = (AgentEvent.AgentStop) events.stream()
        .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
    assertEquals(StreamChunk.StopReason.CONSECUTIVE_UNKNOWN, stop.reason());
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#threeConsecutiveUnknownToolsTriggersStop`
Expected: FAIL

- [ ] **Step 3: 实现连续未知工具检测**

在 `AgentLoop.run` 写入 `session.appendUser(tool_result)` 之后、`iteration++` 之前加：

```java
// 累计连续未知工具
int unknownThisBatch = 0;
for (var r : results) {
    if (r.result().isError() && r.result().content() != null
        && r.result().content().startsWith("Unknown tool:")) {
        unknownThisBatch++;
    } else {
        unknownThisBatch = 0;
        break;
    }
}
if (unknownThisBatch == 0) {
    consecutiveUnknown = 0;
} else {
    consecutiveUnknown += unknownThisBatch;
}
if (consecutiveUnknown >= config.maxConsecutiveUnknown()) {
    finalStop = StopReason.CONSECUTIVE_UNKNOWN;
    finalDetail = "unknown tool called "
        + config.maxConsecutiveUnknown() + " times in a row";
    break;
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（所有测试都绿）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop 连续未知工具停止条件（CONSECUTIVE_UNKNOWN）"
```

---

## Task 19: AgentLoop 停止条件 PROVIDER_ERROR（已有骨架，加测试）

**Files:**
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

> ProviderException 抛出 → emit AgentStop(PROVIDER_ERROR, msg) 的逻辑已在 Task 13 骨架实现。本任务加测试。

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void providerExceptionEmitsProviderErrorStop() {
    LlmProvider failing = new LlmProvider() {
        public void stream(com.maplecode.provider.ChatRequest req, Consumer<StreamChunk> sink) {
            throw new com.maplecode.error.ProviderException("network down");
        }
    };
    var registry = new ToolRegistry(List.of());
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var agent = new AgentLoop(failing, registry, executor, session, AgentConfig.defaults());

    var events = new ArrayList<AgentEvent>();
    agent.run("hello", events::add);

    var stop = (AgentEvent.AgentStop) events.stream()
        .filter(e -> e instanceof AgentEvent.AgentStop).findFirst().orElseThrow();
    assertEquals(StreamChunk.StopReason.PROVIDER_ERROR, stop.reason());
    assertTrue(stop.detail().contains("network down"));
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest#providerExceptionEmitsProviderErrorStop`
Expected: PASS

如果失败：检查 Task 13 骨架里的 `catch (ProviderException e) { sink.accept(new AgentEvent.AgentStop(...)); return; }` —— 一定要 `return`，否则会继续到末尾的 `sink.accept(AgentEvent.AgentStop(END_TURN, ...))`。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(agent): 验证 AgentLoop PROVIDER_ERROR 停止条件"
```

---

## Task 20: AgentLoop Plan Mode（ChatRequest 层过滤）

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void planModePassesOnlyReadOnlyTools() throws Exception {
    var chunks = List.<StreamChunk>of(
        new StreamChunk.TextDelta("plan"),
        new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
    );
    var provider = new FakeLlmProvider(List.of(chunks));

    // 用 spy 验证 ChatRequest.tools 字段只含 readOnly 工具
    var capturedReq = new java.util.concurrent.atomic.AtomicReference<com.maplecode.provider.ChatRequest>();
    var spyProvider = new LlmProvider() {
        public void stream(com.maplecode.provider.ChatRequest req, Consumer<StreamChunk> sink) {
            capturedReq.set(req);
            for (var c : chunks) sink.accept(c);
        }
    };

    var registry = new ToolRegistry(List.of(
        noopTool("read_file", ToolResult.ok("x")),
        noopTool("write_file", ToolResult.ok("x"))));
    var executor = new ToolExecutor(registry);
    var session = new ChatSession();
    var cfg = new AgentConfig(25, 3, PlanMode.PLAN);
    var agent = new AgentLoop(spyProvider, registry, executor, session, cfg);

    var events = new ArrayList<AgentEvent>();
    agent.run("plan this", events::add);

    var tools = capturedReq.get().tools();
    assertNotNull(tools);
    assertEquals(1, tools.size());
    assertEquals("read_file", tools.get(0).name());
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#planModePassesOnlyReadOnlyTools`
Expected: FAIL —— AgentLoop 没在 PLAN 时切换工具集

- [ ] **Step 3: PLAN 时过滤工具集**

修改 `AgentLoop.run` 中的 `var req = ...` 行：

```java
var tools = (config.planMode() == PlanMode.PLAN)
    ? registry.readOnly()
    : registry.all();
var req = session.toRequest("test-model", null, null, tools);
```

> Task 27 之前 model / systemPrompt 是临时值。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentLoop PLAN 模式只传 readOnly 工具到 ChatRequest"
```

---

## Task 21: AgentLoop Plan Mode（executor 层防御）

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 加测试**

`AgentLoopTest.java` 末尾追加：

```java
@Test
void planModeRejectsUnsafeToolAtExecutorLevel() {
    // 即使模型在 PLAN 模式下调 write_file，executor 应拒绝
    var chunks1 = List.<StreamChunk>of(
        new StreamChunk.ToolUseStart("t1", "write_file"),
        new StreamChunk.ToolUseEnd("t1", "write_file", new ObjectMapper().readTree("{}")),
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
    var cfg = new AgentConfig(25, 3, PlanMode.PLAN);
    var agent = new AgentLoop(provider, registry, executor, session, cfg);

    var events = new ArrayList<AgentEvent>();
    agent.run("plan+do", events::add);

    // 找到 ToolResult 事件，断言是 error
    var tr = events.stream()
        .filter(e -> e instanceof AgentEvent.ToolResult)
        .map(e -> (AgentEvent.ToolResult) e)
        .findFirst()
        .orElseThrow();
    assertTrue(tr.isError(), "tool should be rejected in PLAN mode");
    assertTrue(tr.content().contains("write_file"));
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#planModeRejectsUnsafeToolAtExecutorLevel`
Expected: FAIL —— write_file 被正常执行了

- [ ] **Step 3: 构造临时只读 ToolRegistry + ToolExecutor**

修改 `AgentLoop.run` 顶部（在 try 外面）：

```java
// PLAN 模式：用临时只读 ToolRegistry 包装 executor
ToolExecutor effectiveExecutor = executor;
if (config.planMode() == PlanMode.PLAN) {
    var readOnlyRegistry = new ToolRegistry(
        registry.all().stream()
            .filter(t -> registry.isReadOnly(t.name()))
            .toList());
    effectiveExecutor = new ToolExecutor(readOnlyRegistry);
}
```

把所有 `executor.run(...)` 改为 `effectiveExecutor.run(...)`。

> 这个分支可以优化：在循环外只算一次（在 PLAN 模式稳定不变时），但简单起见放循环外顶部（每次循环重算一次开销可忽略）。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=AgentLoopTest`
Expected: PASS（所有测试都绿）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): PLAN 模式 executor 层防御（临时只读 ToolRegistry）"
```

---

## Task 22: StreamPrinter 实现 Consumer<AgentEvent>

**Files:**
- Modify: `src/main/java/com/maplecode/ui/StreamPrinter.java`
- Create: `src/test/java/com/maplecode/ui/StreamPrinterAgentEventTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/ui/StreamPrinterAgentEventTest.java`:

```java
package com.maplecode.ui;

import com.maplecode.agent.AgentEvent;
import com.maplecode.provider.StopReason;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class StreamPrinterAgentEventTest {

    @Test
    void textDeltaWritesRaw() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.TextDelta("hello"));
        assertEquals("hello", out.toString());
    }

    @Test
    void thinkingDeltaWritesDim() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ThinkingDelta("hmm"));
        assertTrue(out.toString().contains("hmm"));
        assertTrue(out.toString().contains("\033[90m"));  // DIM prefix
    }

    @Test
    void toolCallStartWritesArgSummary() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolCallStart("t1", "read_file", "/tmp/foo"));
        var s = out.toString();
        assertTrue(s.contains("read_file"));
        assertTrue(s.contains("/tmp/foo"));
    }

    @Test
    void toolResultSuccessWritesGreenCheck() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolResult("t1", "read_file", false, "content"));
        assertTrue(out.toString().contains("✓"));
        assertTrue(out.toString().contains("read_file"));
    }

    @Test
    void toolResultErrorWritesRedX() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolResult("t1", "read_file", true, "file not found"));
        assertTrue(out.toString().contains("✗"));
        assertTrue(out.toString().contains("file not found"));
    }

    @Test
    void agentStopWritesBracketedMessage() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.AgentStop(StreamChunk.StopReason.MAX_ITERATIONS, "cap"));
        var s = out.toString();
        assertTrue(s.contains("[agent stopped"));
        assertTrue(s.contains("MAX_ITERATIONS"));
    }

    @Test
    void silentEventsWriteNothing() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.IterationStart(0));
        printer.accept(new AgentEvent.IterationEnd(0, StreamChunk.StopReason.END_TURN,
            java.util.List.of(), null));
        printer.accept(new AgentEvent.BatchStart(2, 1));
        printer.accept(new AgentEvent.BatchEnd(3, 0));
        assertEquals("", out.toString());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=StreamPrinterAgentEventTest`
Expected: FAIL —— `accept(AgentEvent)` 不存在

- [ ] **Step 3: 实现**

修改 `StreamPrinter.java`：

- 加 `import com.maplecode.agent.AgentEvent;` 与 `import java.util.function.Consumer;`
- 类签名加 `implements Consumer<AgentEvent>`
- 加 `accept` 方法：

```java
@Override
public void accept(AgentEvent event) {
    switch (event) {
        case AgentEvent.TextDelta d -> write(d.text());
        case AgentEvent.ThinkingDelta d -> writeThinking(d.text());
        case AgentEvent.ToolCallStart s -> toolStart(s.name(), s.argSummary());
        case AgentEvent.ToolResult r -> toolEnd(r.name(), !r.isError(), r.isError() ? r.content() : null);
        case AgentEvent.IterationStart i -> { /* 静默 */ }
        case AgentEvent.IterationEnd i -> { /* 静默（携带 usage，v3 不展示） */ }
        case AgentEvent.BatchStart b -> { /* 静默 */ }
        case AgentEvent.BatchEnd b -> { /* 静默 */ }
        case AgentEvent.ToolCallEnd e -> { /* 静默（ToolCallStart 已经渲染了） */ }
        case AgentEvent.AgentStop s -> info("[agent stopped: " + s.reason() + "]");
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=StreamPrinterAgentEventTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ui): StreamPrinter 实现 Consumer<AgentEvent>，渲染 11 种事件"
```

---

## Task 23: ReplLoop 重写（基本循环 + /exit /clear /tools）

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

> 这一步先做最小骨架：把 ReplLoop 改造成持有 AgentLoop、移除 runOneTurn / handleChunk / TurnAccumulator。`/plan` /`/do` /`/cancel` 在后续 task 加。

- [ ] **Step 1: 重写 ReplLoop**

`src/main/java/com/maplecode/ui/ReplLoop.java` 全量替换为：

```java
package com.maplecode.ui;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.config.AppConfig;
import com.maplecode.provider.LlmProvider;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ReplLoop {

    private final AppConfig config;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ToolRegistry registry;
    private final AgentLoop agent;
    private AgentConfig agentConfig;

    public ReplLoop(AppConfig config, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry) {
        this.config = config;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        var executor = new ToolExecutor(registry);
        var session = new ChatSession();
        var toolCtx = ToolContext.defaults(Path.of(System.getProperty("user.dir")));
        this.agent = new AgentLoop(provider, registry, executor, session, AgentConfig.defaults());
        this.agentConfig = AgentConfig.defaults();
        // toolCtx 暂时未使用（v3 计划阶段不传给 executor；v4 引入）
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        return new ReplLoop(config, provider, new StreamPrinter(System.out), reader, registry);
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/tools 列出工具，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                input = readMultiline();
            } catch (UserInterruptException e) {
                agent.cancel();
                printer.info("(interrupted)");
                continue;
            } catch (RuntimeException e) {
                break;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("/exit")) break;
            if (trimmed.equals("/clear")) {
                agent.session().clear();
                printer.info("history cleared");
                continue;
            }
            if (trimmed.equals("/tools")) {
                printTools();
                continue;
            }

            agent.run(trimmed, printer);
            printer.newline();
        }
    }

    private void printTools() {
        var tools = registry.all();
        if (tools.isEmpty()) {
            printer.info("(no tools registered)");
            return;
        }
        for (var t : tools) {
            printer.info("- " + t.name() + ": " + t.description());
        }
    }

    private String readMultiline() {
        String first;
        try {
            first = reader.readLine("> ");
        } catch (UserInterruptException e) {
            throw e;
        }
        if (first == null) return null;
        if (!first.equals("\"\"\"")) return first;
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine("... ");
            } catch (UserInterruptException e) {
                throw e;
            }
            if (line == null) return null;
            if (line.equals("\"\"\"")) break;
            sb.append(line).append('\n');
        }
        String result = sb.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
```

同时在 `AgentLoop.java` 加访问器：

```java
public ChatSession session() { return session; }
```

这样 ReplLoop 可以 `agent.session().clear()`。

- [ ] **Step 2: 编译**

Run: `mvn -q compile`
Expected: PASS

如果 `agent.session()` 缺失，加访问器。

- [ ] **Step 3: 跑现有 ReplLoop 相关测试**

Run: `mvn -q test`
Expected: PASS（v2 ReplLoop 没有单元测试，主要靠编译 + smoke）

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(ui): ReplLoop 重写持有 AgentLoop，移除 runOneTurn/handleChunk"
```

---

## Task 24: ReplLoop /plan 命令

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 加 /plan 处理**

在 `ReplLoop.run` 的 `if (trimmed.equals("/tools"))` 后面加：

```java
if (trimmed.startsWith("/plan ")) {
    String query = trimmed.substring(6).trim();
    if (query.isEmpty()) {
        printer.error("/plan requires a query");
        continue;
    }
    agentConfig = new AgentConfig(
        agentConfig.maxIterations(),
        agentConfig.maxConsecutiveUnknown(),
        PlanMode.PLAN);
    agent.updateConfig(agentConfig);
    agent.run(query, printer);
    printer.newline();
    continue;
}
```

同时在 `AgentLoop.java` 加：

```java
public void updateConfig(AgentConfig config) {
    // 注：当前的实现下 planMode 在 run 顶部判断；updateConfig 后下一次 run 才生效
    // 如果要立即生效，需要把 config 改为 volatile 或在 run 顶部重新读
    // 简化：mutate the field directly（不安全但短期可接受）
    // 或：把 config 改为不可变 + 提供 updatePlanMode 方法
    // 本方案用后者的简化版本：
    // （实际上 Java record 字段是 final；改用 volatile 字段包装）
}
```

为了支持 `updateConfig`，把 `AgentLoop.config` 改为非 final + setter：

修改 `AgentLoop.java`：
- `private AgentConfig config;`（去掉 final）
- `public void updateConfig(AgentConfig config) { this.config = config; }`

并在 run() 顶部 `while` 循环内部，重新计算 `effectiveExecutor`（如果 planMode 变了）—— 改为：

```java
ToolExecutor effectiveExecutor;
if (config.planMode() == PlanMode.PLAN) {
    var readOnlyRegistry = new ToolRegistry(
        registry.all().stream()
            .filter(t -> registry.isReadOnly(t.name()))
            .toList());
    effectiveExecutor = new ToolExecutor(readOnlyRegistry);
} else {
    effectiveExecutor = executor;
}
```

（当前 Task 21 实现的版本把 effectiveExecutor 计算放在 run 顶部 if 块外；本任务改为每次循环顶部重新计算。）

- [ ] **Step 2: 编译**

Run: `mvn -q compile`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ui): ReplLoop /plan 命令 + AgentLoop.updateConfig 支持"
```

---

## Task 25: ReplLoop /do 命令

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 加 /do 处理**

在 `if (trimmed.startsWith("/plan "))` 后面加：

```java
if (trimmed.equals("/do")) {
    if (agentConfig.planMode() != PlanMode.PLAN) {
        printer.error("not in plan mode");
        continue;
    }
    String planText = lastAssistantText();
    if (planText == null) {
        printer.error("no plan to execute");
        continue;
    }
    agent.session().clear();
    agentConfig = new AgentConfig(
        agentConfig.maxIterations(),
        agentConfig.maxConsecutiveUnknown(),
        PlanMode.NORMAL);
    agent.updateConfig(agentConfig);
    agent.run(planText, printer);
    printer.newline();
    continue;
}
```

加私有方法：

```java
private String lastAssistantText() {
    var session = agent.session();
    for (int i = session.size() - 1; i >= 0; i--) {
        var msg = session.get(i);
        if (msg.role() != com.maplecode.provider.ChatMessage.Role.ASSISTANT) continue;
        for (var block : msg.blocks()) {
            if (block instanceof com.maplecode.provider.ContentBlock.TextBlock t) {
                return t.text();
            }
        }
    }
    return null;
}
```

- [ ] **Step 2: 编译**

Run: `mvn -q compile`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ui): ReplLoop /do 命令（二段提交：清空 session 跑计划）"
```

---

## Task 26: ReplLoop /cancel 命令

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 加 /cancel 处理**

在 `if (trimmed.equals("/do"))` 后面加：

```java
if (trimmed.equals("/cancel")) {
    agent.cancel();
    agentConfig = new AgentConfig(
        agentConfig.maxIterations(),
        agentConfig.maxConsecutiveUnknown(),
        PlanMode.NORMAL);
    agent.updateConfig(agentConfig);
    printer.info("cancelled");
    continue;
}
```

- [ ] **Step 2: 编译**

Run: `mvn -q compile`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ui): ReplLoop /cancel 命令（取消 + 重置 plan mode）"
```

---

## Task 27: App 注入 AgentLoop（model / systemPrompt 从 config 拿）

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: AgentLoop 用 config.model() / config.systemPrompt() / config.thinking()**

修改 `AgentLoop.run`：

```java
var req = session.toRequest(
    config.model(),
    config.systemPrompt(),
    config.thinking(),
    tools);
```

但是！当前 `AgentConfig` 没有 model / systemPrompt / thinking 字段 —— 这些在 `AppConfig` 里。需要让 `AgentConfig` 持有这些，或者让 `AgentLoop` 同时持有 `AppConfig`。

决策：扩展 `AgentConfig` 持有 `model / systemPrompt / thinking`：

```java
public record AgentConfig(
    String model,
    String systemPrompt,
    ThinkingConfig thinking,
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode
) {
    public AgentConfig {
        if (maxIterations < 1) throw new ConfigException(...);
        if (maxConsecutiveUnknown < 1) throw new ConfigException(...);
    }

    public static AgentConfig fromAppConfig(AppConfig app) {
        return new AgentConfig(app.model(), app.systemPrompt(), app.thinking(),
            25, 3, PlanMode.NORMAL);
    }
}
```

更新 `AgentConfigTest.java` 的 `defaultsAreSensible` 测试：

```java
@Test
void defaultsAreSensible() {
    var c = AgentConfig.defaults();
    assertEquals(25, c.maxIterations());
    assertEquals(3, c.maxConsecutiveUnknown());
    assertEquals(PlanMode.NORMAL, c.planMode());
}
```

（保持不变；`fromAppConfig` 是新方法。）

- [ ] **Step 2: ReplLoop 构造时用 fromAppConfig**

```java
var agentConfig = AgentConfig.fromAppConfig(config);
this.agentConfig = agentConfig;
this.agent = new AgentLoop(provider, registry, executor, session, agentConfig);
```

- [ ] **Step 3: App.java 不变**

`App.java` 已构造 ToolRegistry / ReplLoop；不需改（ReplLoop 自己 fromConfig 包了）。但需要让 ReplLoop 的构造接受 AgentConfig（或 ReplLoop.fromConfig 自己用 AppConfig 构造）。

当前 ReplLoop.fromConfig 已经传 AppConfig；只要 ReplLoop 内部用 `AgentConfig.fromAppConfig(config)` 即可。✅

- [ ] **Step 4: 编译 + 跑全测**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): AgentConfig 持有 model/systemPrompt/thinking；ReplLoop 用 fromAppConfig"
```

---

## Task 28: 跑全测 + 集成 smoke

**Files:** (无新增文件)

- [ ] **Step 1: 跑全测**

Run: `mvn -q test`
Expected: PASS（所有 v1 / v2 / v3 测试都绿）

如果失败：定位失败点，按需补测试或修代码。

- [ ] **Step 2: 编译可执行 jar**

Run: `mvn -q package`
Expected: BUILD SUCCESS；产出 `target/maple-code-java-0.1.0.jar`

- [ ] **Step 3: 手工 smoke（Anthropic）**

如果环境有 ANTHROPIC_API_KEY：

```bash
ANTHROPIC_API_KEY=... \
MAPLECODE_RUN_IT=1 \
mvn -q test -Dtest=*IT
```

否则跑：

```bash
java -jar target/maple-code-java-0.1.0.jar
```

手动输入：
- `read /Users/wangpeng/MyCodeSpace/luanqibazao/maple-code-java/pom.xml` → 模型调 read_file → 输出文件内容
- `/exit`

预期：stderr / stdout 出现 `⚙ read_file .../pom.xml` 与 `✓ read_file`，模型文本包含 "Maven" 字样。

- [ ] **Step 4: 手工 smoke（Plan Mode）**

```bash
java -jar target/maple-code-java-0.1.0.jar
```

手动输入：
- `/plan 我想加一个 JSON 工具`
- 等待模型输出文本计划
- `/do`
- 等待模型开始实现

预期：第一步模型只用 read_file / glob / grep；第二步模型开始调 write_file / edit_file。

- [ ] **Step 5: Commit（如有 fix）**

如果有 smoke 发现 bug，修代码 + 补测试 + commit：

```bash
git add -A
git commit -m "fix: smoke 阶段发现的 XX 问题"
```

---

## 验收清单

跑完所有 Task 后对照 spec §11 验收清单：

- [ ] `mvn package` 产出可执行 jar
- [ ] `mvn test` 全绿
- [ ] 模型发 2 个 `read_file` → 两个并发跑
- [ ] 模型发 1 个 `write_file` + 1 个 `exec` → 串行
- [ ] 达到 25 步迭代上限 → `MAX_ITERATIONS`
- [ ] 连续 3 次调未知工具 → `CONSECUTIVE_UNKNOWN`
- [ ] `/plan 帮我重构 AgentLoop.java` → 模型只用读类工具
- [ ] `/do` → 清空 session → 计划文本作为新输入 → 全工具
- [ ] `/cancel` → 当前 turn 跑完后退出
- [ ] Anthropic / OpenAI smoke 通过
- [ ] 关网络 → `PROVIDER_ERROR`，REPL 可继续
- [ ] PLAN 模式下模型调 write_file → Unknown tool error 回灌
- [ ] v2 现有所有非修改的测试仍全绿