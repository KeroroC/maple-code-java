# MapleCode — 工具系统设计规格（阶段二）

**日期**：2026-07-03
**范围**：在 v1 流式 REPL 基础上增加工具系统（6 个核心工具 + Anthropic / OpenAI 双 Provider 工具协议 + 单轮工具调用）。不做 Agent Loop、不做工具并发、不做工具审批、不做会话持久化。

---

## 1. 目标与非目标

**目标**
- 6 个本地工具：`read_file` / `write_file` / `edit_file` / `exec` / `glob` / `grep`
- 统一的 `Tool` 接口 + `ToolRegistry` + `ToolExecutor`（name → 工具 → `ToolResult`）
- Anthropic / OpenAI 双 Provider 都支持工具（各自的 wire 格式）
- 流式 tool_use JSON 碎片拼接（`ToolUseStart/Delta/End`）
- 单轮：模型发 1 个 tool_use → 执行 → 回灌 tool_result → 模型发最终文本 → 结束
- `/tools` 斜杠命令列出可用工具

**非目标（明确不做）**
- Agent Loop（连环工具调用）——下一章
- 工具调用审批（写/执行前的 y/N 确认）
- 工具并发执行
- 会话持久化
- 多模态输入、插件系统、运行时切换 provider

---

## 2. 新增 / 修改的抽象

### 2.1 `sealed interface ContentBlock`（新增，`com.maplecode.provider`）

```java
public sealed interface ContentBlock
    permits ContentBlock.TextBlock,
            ContentBlock.ToolUseBlock,
            ContentBlock.ToolResultBlock {

    record TextBlock(String text) implements ContentBlock {}
    record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {}
    record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContentBlock {}
}
```

### 2.2 `ChatMessage`（修改）

```java
public record ChatMessage(Role role, List<ContentBlock> blocks) {
    public enum Role { USER, ASSISTANT }
}
```

**便利方法**（`ChatSession` 上）：
- `appendUserText(String)` —— 内部走 `appendUser(List.of(new TextBlock(text)))`
- `appendUser(List<ContentBlock>)` —— 主入口
- `appendAssistant(List<ContentBlock>)` —— 主入口

### 2.3 `sealed interface StreamChunk`（扩展 3 个变体）

```java
public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error,
            StreamChunk.ToolUseStart,
            StreamChunk.ToolUseDelta,
            StreamChunk.ToolUseEnd {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}
    record ToolUseStart(String id, String name) implements StreamChunk {}
    record ToolUseDelta(String id, String partialJson) implements StreamChunk {}
    record ToolUseEnd(String id, String name, JsonNode input) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE }
}
```

**sealed 扩展的代价**：`StreamParser` 内部 switch、REPL switch、所有订阅者 switch 编译失败即被强制更新。

### 2.4 `Tool` 接口与 `ToolResult`（新包 `com.maplecode.tool`）

```java
public sealed interface Tool
    permits ReadFileTool, WriteFileTool, EditFileTool,
            ExecTool, GlobTool, GrepTool {

    String name();
    String description();
    JsonNode inputSchema();       // JSON Schema
    ToolResult execute(JsonNode args, ToolContext ctx);
}

public record ToolResult(String content, boolean isError) {
    public static ToolResult ok(String content)           { return new ToolResult(content, false); }
    public static ToolResult error(String content)        { return new ToolResult(content, true); }
}

public record ToolContext(
    Path cwd,
    int readMaxBytes,             // 默认 1_048_576 (1 MiB)
    int execDefaultTimeoutSec,    // 默认 30
    int grepMaxResults,           // 默认 100
    int globMaxResults            // 默认 100
) {}
```

### 2.5 `ToolRegistry` / `ToolExecutor`（新包）

```java
public final class ToolRegistry {
    public ToolRegistry(List<Tool> tools) { ... }
    public List<Tool> all() { ... }                       // 喂给 ChatRequest.tools
    public Optional<Tool> get(String name) { ... }
}

public final class ToolExecutor {
    public ToolExecutor(ToolRegistry registry) { ... }
    public ToolResult run(String name, JsonNode args) {
        // registry.get(name) 缺失 → ToolResult.error("Unknown tool: X. Available: ...")
        // 工具抛 ToolException → 捕，返回 error
        // 工具抛其它 Exception → 兜底返回 error（绝不让坏工具杀死 REPL）
    }
}
```

### 2.6 `ChatRequest` 加一个字段

```java
public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking,  // nullable
    List<Tool> tools          // nullable；v1 旧测试传 null 也能跑
) {}
```

---

## 3. 工具契约

### 3.1 `read_file`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `path` | string | ✅ | — | 文件路径；相对路径解析为 `cwd/path` |
| `offset` | integer | ❌ | 0 | 起始行号（0-indexed） |
| `limit` | integer | ❌ | 2000 | 最多返回行数 |

**行为**：
1. 解析路径；存在性检查（不存在 → `error`）
2. 读前 8KB 检测 NUL 字节；二进制 → `error("binary file not supported")`
3. 按行读；`offset + limit` 越界 clamp 到文件末尾
4. 总字节数 > `ctx.readMaxBytes` → 截断到 maxBytes，content 末尾追加 `\n[truncated]`

**返回格式**：
```
   1	package com.foo;
   2	public class Bar { ... }
```

行号右对齐到 6 字符宽 + TAB + 内容。

### 3.2 `write_file`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | ✅ | 写入路径 |
| `content` | string | ✅ | 完整文件内容（覆盖写） |

**行为**：
1. 父目录不存在 → `error("parent directory does not exist: ...")`
2. 已存在 → 覆盖；不存在 → 创建
3. 写完返回 `ok("wrote N bytes to <path>")`

### 3.3 `edit_file`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | ✅ | 文件路径 |
| `old_string` | string | ✅ | 要替换的文本 |
| `new_string` | string | ✅ | 替换为 |

**行为**：
1. 读全文
2. 计算 `old_string` 出现次数
3. 0 次 → `error("old_string not found in <path>")`
4. >1 次 → `error("old_string matches N locations in <path>; provide more context to make it unique")`
5. `old_string.equals(new_string)` → `error("no-op: old_string == new_string")`
6. 唯一匹配 → 替换、写回

**不**支持 `replace_all` —— 严格唯一匹配。

### 3.4 `exec`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `command` | string | ✅ | — | shell 命令 |
| `timeout_seconds` | integer | ❌ | 30 | 超时秒数 |

**行为**：
1. `command.isBlank()` → `error("empty command")`
2. `ProcessBuilder("/bin/sh", "-c", command)`；`directory(cwd)`
3. 合并 stdout + stderr（按到达顺序交错）
4. `process.waitFor(timeout, SECONDS)`
5. 超时：`destroyForcibly()` + `error("timeout after Ns")`
6. 正常退出：合并输出 > 50KB 截断；非零退出 → `error("exit=N\n<output>")`，零退出 → `ok("<output>")`（空输出也返回空字符串，不省略）

### 3.5 `glob`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `pattern` | string | ✅ | glob 模式（`**/*.java`） |

**行为**：
1. `Files.walkFileTree(cwd)` + `PathMatcher("glob:" + pattern)`
2. 收集到 List；size > `ctx.globMaxResults` 截断并追加 `\n[truncated, total=N]`
3. 0 匹配 → `ok("")`（不报错）
4. 返回 `path1\npath2\n...` 相对 cwd

### 3.6 `grep`

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `pattern` | string | ✅ | — | regex |
| `path` | string | ❌ | `"."` | 搜索根 |
| `include_glob` | string | ❌ | — | 文件名过滤 glob |

**行为**：
1. 递归 `path`；文件名匹配 `include_glob`（若给）
2. 跳过二进制文件（前 8KB 含 NUL → 跳过）
3. 正则编译失败 → `error("invalid regex: <msg>")`
4. 输出 `path:lineno:content`，按文件 → 行号顺序
5. 行数 > `ctx.grepMaxResults` 截断并追加 `\n[truncated, total=N]`
6. 0 匹配 → `ok("")`

### 3.7 共用基础设施

- **路径解析**：`Path.of(ctx.cwd(), path)` 若 `!path.startsWith("/")`
- **二进制检测**：读前 8KB，扫到 `\\u0000` 即视为二进制
- **安全网**：所有工具 `execute()` 内部 `try { ... } catch (IOException e) { throw new ToolException(...) }`

---

## 4. Provider 接线

### 4.1 Anthropic

**RequestMapper 改造**：
- `tools` 字段非空时，在 JSON body 添加 `"tools": [{"name":..,"description":..,"input_schema":..}, ...]`
- `messages[*].content` 从 string 改为 `[{type:"text",text:..}]` 或 `[{type:"tool_use",id:..,name:..,input:..}]`（依 ContentBlock 类型）
- 工具结果回灌：`{role:"user", content:[{type:"tool_result",tool_use_id:..,content:..,is_error:..}]}`

**StreamParser 改造**：
- `content_block_start` 中 `content_block.type=tool_use` → `sink.accept(new ToolUseStart(id, name))`；记录 `currentBlock = TOOL_USE` 携带 `id`
- `content_block_delta` 中 `delta.type=input_json_delta` → `sink.accept(new ToolUseDelta(id, delta.partial_json))`
- `content_block_stop` 时若 currentBlock == TOOL_USE → flush accumulated partialJson 解析为 JsonNode，发 `ToolUseEnd(id, name, input)`
- `message_delta` 中 `delta.stop_reason=tool_use` → `lastStopReason = "tool_use"`
- `message_stop` 时 `mapStopReason("tool_use")` → `StopReason.TOOL_USE`（v1 映射成 ERROR 的那条删除）

### 4.2 OpenAI

**RequestMapper 改造**：
- `tools` 非空时，添加 `"tools": [{"type":"function","function":{"name":..,"description":..,"parameters":<input_schema>}}]`
- 助手消息 wire 格式：`{role:"assistant", content:..或null, tool_calls:[{id,type:"function",function:{name,arguments}}]}`
- 工具结果回灌：OpenAI 用 `role:"tool"` 多条消息，每条带 `tool_call_id`；REPL 在 `session.appendUser` 时把 ToolResultBlock 拆成多个 `ChatMessage(USER, [ToolResultBlock(...)])`

**StreamParser 改造**：
- `delta.tool_calls[i].id` 首次出现 → `ToolUseStart(id, delta.tool_calls[i].function.name)`
- `delta.tool_calls[i].function.arguments` 增量 → `ToolUseDelta(id, partial)`
- `finish_reason=tool_calls` 时 flush 所有累积 JSON → 多个 `ToolUseEnd`；`MessageEnd(TOOL_USE)`

---

## 5. REPL 流程

### 5.1 一次用户输入的完整生命周期

```java
void onUserInput(String input) {
    session.appendUserText(input);
    runOneTurn();
}

void runOneTurn() {
    while (true) {
        var accumulated = new TurnAccumulator();
        var req = session.toRequest(model, systemPrompt, thinking, registry.all());
        try {
            provider.stream(req, chunk -> handle(chunk, accumulated));
        } catch (ProviderException e) {
            printer.error("request failed: " + e.getMessage());
            return;
        }
        if (accumulated.stopReason != StopReason.TOOL_USE) {
            if (accumulated.text.length() > 0) {
                session.appendAssistant(List.of(new TextBlock(accumulated.text)));
            }
            return;
        }
        if (accumulated.toolUses.size() != 1) {
            printer.error("expected exactly 1 tool_use, got " + accumulated.toolUses.size());
            return;
        }
        var tu = accumulated.toolUses.get(0);
        session.appendAssistant(List.of(
            new TextBlock(accumulated.text),
            new ToolUseBlock(tu.id(), tu.name(), tu.input())
        ));
        var result = executor.run(tu.name(), tu.input());
        printer.toolEnd(tu.name(), !result.isError());
        session.appendUser(List.of(new ToolResultBlock(tu.id(), result.content(), result.isError())));
    }
}
```

**严格 1 个 tool_use**：v2 收到 N>1 → 报错、不改 session、返回 prompt。下一章改 `!= 1` → `>= 1` 即可。

### 5.2 斜杠命令

| 命令 | 行为 |
|---|---|
| `/exit` | 退出（v1 已有） |
| `/clear` | 清空 session（v1 已有） |
| `/tools` | 列出 `registry.all()` 的 name + 描述（v2 新增） |

### 5.3 StreamPrinter 新增

```java
public void toolStart(String name, String argSummary)  // 灰字: ⚙ read_file src/Foo.java
public void toolEnd(String name, boolean success)     // ✓ read_file  /  ✗ read_file: <error>
```

`toolStart` 在 `ToolUseStart` chunk 到达时调用，`toolEnd` 在 executor 返回后调用。args 摘要只取常见字段（path / command / pattern），不打印完整 JSON。

---

## 6. 错误处理

| 场景 | 行为 | 退出码 |
|---|---|---|
| 工具找不到（registry miss） | `ToolResult(error, "Unknown tool: X. Available: ...")` 回灌 | 0（继续） |
| 工具抛 `ToolException` | 捕获 → `ToolResult(error, message)` 回灌 | 0 |
| 工具抛其它 `Exception` | 兜底 → `ToolResult(error, "internal error: <class>")` | 0 |
| 工具入参 JSON 不合法 | `ToolResult(error, "tool input not valid JSON: ...")` | 0 |
| N != 1 tool_use 收到 | 打印错误，不改 session | 0 |
| Provider 错（HTTP / SSE） | v1 行为不变：不退出 REPL | 0 |
| 配置错 | v1 行为不变：78 | 78 |

**绝不让工具执行杀死 REPL**。所有路径都有兜底。

---

## 7. 测试策略

### 7.1 单元测试（CI 必跑）

| 测试 | 覆盖 |
|---|---|
| `ToolRegistryTest` | 构造、按名查、all()、空注册 |
| `ToolExecutorTest` | 正常执行、未知工具 → error、ToolException → error、其它 Exception → error |
| `ReadFileToolTest` | 正常读、offset/limit、>1MB 截断、二进制拒绝、文件不存在 |
| `WriteFileToolTest` | 写新文件、覆盖、目录不存在 |
| `EditFileToolTest` | 唯一匹配、0 匹配 → error、>1 匹配 → error、no-op → error |
| `ExecToolTest` | `echo` 命令、非零退出、超时、空命令 |
| `GlobToolTest` | `*.java`、`**/*.java` 递归、0 匹配、>100 截断 |
| `GrepToolTest` | 基本 regex、行号格式、include_glob、>100 截断、二进制跳过、非法 regex |
| `AnthropicStreamParserToolTest` | `tool_use` start→delta→stop 序列、stop_reason=tool_use → TOOL_USE |
| `OpenAiStreamParserToolTest` | `delta.tool_calls` 序列、finish_reason=tool_calls → TOOL_USE |
| `AnthropicRequestMapperToolTest` | `tools` 数组 wire 格式、tool_use 消息 wire 格式、tool_result 消息 wire 格式 |
| `OpenAiRequestMapperToolTest` | `tools` 数组 + function 包装、tool_calls 消息、role=tool 回灌消息 |
| `ChatSessionTest`（修改） | `appendUserText` 便利方法、blocks 顺序、`List.copyOf` 不可变副本 |
| `AnthropicRequestMapperTest`（修改） | content 从 string 变 `[{type:"text",text:..}]` |
| `OpenAiRequestMapperTest`（修改） | 同上 |

### 7.2 集成 smoke（手工）

- 启动 REPL，模型调 `read_file` 读真实文件，断言 stderr 出现 `✓ read_file` 且模型最终文本里引用到文件内容
- `@EnabledIfEnvironmentVariable(named = "MAPLECODE_RUN_IT", matches = "1")`

### 7.3 REPL 测试

v2 仍不做 REPL 端到端测试（与 v1 一致 —— JLine 在 CI 环境坑多）。通过手工 smoke 验收。

---

## 8. 文件清单

### 8.1 新增

```
src/main/java/com/maplecode/tool/
├── Tool.java                 (sealed)
├── ToolResult.java           (record)
├── ToolContext.java          (record)
├── ToolRegistry.java
├── ToolExecutor.java
├── ReadFileTool.java
├── WriteFileTool.java
├── EditFileTool.java
├── ExecTool.java
├── GlobTool.java
└── GrepTool.java
```

### 8.2 修改

```
src/main/java/com/maplecode/provider/
├── ChatMessage.java          ← String content → List<ContentBlock> blocks
├── ChatRequest.java          ← + tools 字段
├── StreamChunk.java          ← + ToolUseStart/Delta/End 变体；StopReason + TOOL_USE
├── ContentBlock.java         (新增, 同包)
├── anthropic/AnthropicRequestMapper.java   ← 添 tools 字段、content blocks、tool_result
├── anthropic/AnthropicStreamParser.java    ← 添 tool_use 解析；stop_reason tool_use → TOOL_USE
├── openai/OpenAiRequestMapper.java         ← 添 tools、tool_calls、role=tool
└── openai/OpenAiStreamParser.java          ← 添 tool_calls 解析；finish_reason tool_calls → TOOL_USE

src/main/java/com/maplecode/error/
└── ToolException.java        (新增, extends MapleCodeException)

src/main/java/com/maplecode/session/
└── ChatSession.java          ← 改 content 字段；添 appendUserText / appendUser(blocks) / appendAssistant(blocks)

src/main/java/com/maplecode/ui/
├── ReplLoop.java             ← 改：runOneTurn 内部循环；添 /tools；构造时收 registry + executor
└── StreamPrinter.java        ← 添 toolStart / toolEnd

src/main/java/com/maplecode/App.java        ← 构造 ToolRegistry + ToolExecutor，注入 ReplLoop
```

### 8.3 测试新增

```
src/test/java/com/maplecode/tool/
├── ToolRegistryTest.java
├── ToolExecutorTest.java
├── ReadFileToolTest.java
├── WriteFileToolTest.java
├── EditFileToolTest.java
├── ExecToolTest.java
├── GlobToolTest.java
└── GrepToolTest.java

src/test/java/com/maplecode/provider/
├── anthropic/AnthropicStreamParserToolTest.java
├── openai/OpenAiStreamParserToolTest.java
├── anthropic/AnthropicRequestMapperToolTest.java
└── openai/OpenAiRequestMapperToolTest.java
```

### 8.4 测试修改

```
src/test/java/com/maplecode/session/ChatSessionTest.java
src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java
src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java
```

---

## 9. 验收清单

- [ ] `mvn package` 产出可执行 jar
- [ ] `mvn test` 全绿（含 14 个 v2 新测试 + 3 个修改后的 v1 测试）
- [ ] `/tools` 列出 6 个工具
- [ ] Anthropic 模型发 `read_file` → 终端显示 `⚙ read_file` + `✓ read_file`，模型最终文本含文件内容
- [ ] OpenAI 模型发 `read_file` → 行为同上
- [ ] 模型发 2 个 tool_use → 终端报错，不改 session
- [ ] `read_file` 读 1.5MB 文件 → 截断带 `[truncated]`
- [ ] `edit_file` 0 匹配 → 终端 `✗ edit_file: old_string not found`，模型收到 error 结果
- [ ] `edit_file` 2 匹配 → 终端 `✗ edit_file: matches 2 locations`，模型收到 error 结果
- [ ] `exec "sleep 5"` + `timeout_seconds=1` → 1s 内返回 `timeout after 1s`
- [ ] `exec "false"` → `exit=1` + `is_error=true`
- [ ] 工具错不杀死 REPL —— 出错后用户能继续输入
- [ ] 缺工具名 → `Unknown tool: foo. Available: read_file, write_file, ...`
- [ ] v1 现有所有非修改的测试仍然全绿
- [ ] `*Test.java` 走 Surefire，`*IT.java` 走 failsafe，配置不变
- [ ] 集成 smoke（MAPLECODE_RUN_IT=1）能跑通 read_file 端到端
- [ ] pom 依赖不变（Jackson 2.17.2 已含 JsonNode，无需新库）
