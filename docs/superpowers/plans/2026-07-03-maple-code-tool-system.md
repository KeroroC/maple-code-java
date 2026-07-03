# MapleCode 工具系统（阶段二）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 v1 流式 REPL 基础上加 6 个本地工具（read_file / write_file / edit_file / exec / glob / grep），让 Anthropic 和 OpenAI 模型都能调用工具。本步骤不实现 Agent Loop（连环工具调用）——下章再做。

**Architecture:** 协议层驱动。`sealed StreamChunk` 加 `ToolUseStart/Delta/End` 三种变体；`sealed ContentBlock` 三种（`TextBlock / ToolUseBlock / ToolResultBlock`）替换 `ChatMessage` 的字符串 content。Provider mappers 负责 wire 格式，parsers 负责流式 JSON 碎片拼接，REPL + ToolRegistry/ToolExecutor 负责发现与执行。单轮：模型发 1 个 tool_use → 执行 → 回灌 tool_result → 模型发最终文本 → 结束。

**Tech Stack:** Java 21、Maven、Jackson 2.17.2（已有）、JUnit 5、JDK `ProcessBuilder` / `Files.walkFileTree`、无新依赖。

**Spec:** `docs/superpowers/specs/2026-07-03-maple-code-tool-system-design.md`

---

## 任务依赖与顺序

```
T1  ContentBlock
T2  StreamChunk + StopReason
T3  ToolException
T4  Tool/ToolResult/ToolContext
T5  ChatRequest.tools
T6  ChatMessage refactor + ChatSession + ChatSessionTest
T7  AnthropicRequestMapper content blocks + test
T8  OpenAiRequestMapper content blocks + test
T9  ToolRegistry + test
T10 ToolExecutor + test
T11 ReadFileTool + test
T12 WriteFileTool + test
T13 EditFileTool + test
T14 ExecTool + test
T15 GlobTool + test
T16 GrepTool + test
T17 AnthropicRequestMapper tools array + test
T18 OpenAiRequestMapper tools array + test
T19 AnthropicStreamParser tool_use + test
T20 OpenAiStreamParser tool_calls + test
T21 StreamPrinter toolStart/toolEnd
T22 ReplLoop tool flow + /tools
T23 App constructs ToolRegistry/Executor
T24 跑全测
```

每任务独立可跑、单独 commit。每个 commit 后 `mvn -q test` 应绿（除了显式标"已知挂"的步骤）。

---

## Task 1: ContentBlock sealed interface

**Files:**
- Create: `src/main/java/com/maplecode/provider/ContentBlock.java`

- [ ] **Step 1: 写文件**

```java
package com.maplecode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 消息内容的原子单元。Anthropic 的消息是 content block 数组，
 * OpenAI 的 assistant 消息是 content + tool_calls，
 * 都用这个 sealed 层次统一。
 *
 * sealed 保证 switch 穷尽。
 */
public sealed interface ContentBlock
    permits ContentBlock.TextBlock,
            ContentBlock.ToolUseBlock,
            ContentBlock.ToolResultBlock {

    /** 文本段。Anthropic 走 {type:"text",text:..}，OpenAI 走 string content。 */
    record TextBlock(String text) implements ContentBlock {}

    /** 助手发起的工具调用。id 对应 Anthropic tool_use_id / OpenAI tool_call_id。 */
    record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {}

    /** 工具结果回灌。content 是字符串（v2 简化；后续可扩结构化）。 */
    record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContentBlock {}
}
```

- [ ] **Step 2: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/provider/ContentBlock.java
git commit -m "feat(provider): add sealed ContentBlock (TextBlock/ToolUseBlock/ToolResultBlock)"
```

---

## Task 2: StreamChunk + StopReason 扩展

**Files:**
- Modify: `src/main/java/com/maplecode/provider/StreamChunk.java`
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java:62-70`（switch 暂时加 default 占位）

- [ ] **Step 1: 改 StreamChunk.java**

把整个文件替换成：

```java
package com.maplecode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 流式响应中所有可能的事件类型。
 *
 * sealed interface 是 Java 17 引入的特性，通过 permits 关键字
 * 明确列出所有允许实现该接口的子类，编译器据此保证：
 * 1. 类型层次是封闭的——不允许其他类意外实现该接口；
 * 2. switch 表达式可以做到穷尽检查——新增子类时未处理的 switch 会编译报错。
 */
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

    /**
     * 流式 tool_use 的三段拼装：
     *   ToolUseStart  → 工具被声明（id + name）
     *   ToolUseDelta  → 参数 JSON 碎片（partialJson）
     *   ToolUseEnd    → 工具参数完整，input 是解析后的 JsonNode
     *
     * Anthropic 的 content_block_start/delta/stop 拆成这三段。
     * OpenAI 的 delta.tool_calls 同构映射（按 tool_calls 数组 index 跟踪）。
     */
    record ToolUseStart(String id, String name) implements StreamChunk {}
    record ToolUseDelta(String id, String partialJson) implements StreamChunk {}
    record ToolUseEnd(String id, String name, JsonNode input) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE }
}
```

- [ ] **Step 2: ReplLoop.java 临时加 default 让 switch 继续编译**

打开 `src/main/java/com/maplecode/ui/ReplLoop.java`，把第 62-70 行的 switch 块：

```java
                provider.stream(req, chunk -> {
                    switch (chunk) {
                        case StreamChunk.TextDelta d       -> { printer.write(d.text()); textBuf.append(d.text()); }
                        case StreamChunk.ThinkingDelta d   -> printer.writeThinking(d.text());
                        case StreamChunk.MessageStart s    -> { /* 空操作 */ }
                        case StreamChunk.MessageEnd e      -> printer.endAssistant();
                        case StreamChunk.Error e           -> printer.error(e.code() + ": " + e.message());
                    }
                });
```

改成（加 3 个新 case 暂时打日志，后续 Task 22 替换为真实逻辑）：

```java
                provider.stream(req, chunk -> {
                    switch (chunk) {
                        case StreamChunk.TextDelta d       -> { printer.write(d.text()); textBuf.append(d.text()); }
                        case StreamChunk.ThinkingDelta d   -> printer.writeThinking(d.text());
                        case StreamChunk.MessageStart s    -> { /* 空操作 */ }
                        case StreamChunk.MessageEnd e      -> printer.endAssistant();
                        case StreamChunk.Error e           -> printer.error(e.code() + ": " + e.message());
                        case StreamChunk.ToolUseStart d    -> { /* TODO Task 22 */ }
                        case StreamChunk.ToolUseDelta d    -> { /* TODO Task 22 */ }
                        case StreamChunk.ToolUseEnd d      -> { /* TODO Task 22 */ }
                    }
                });
```

- [ ] **Step 3: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/provider/StreamChunk.java src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(provider): extend StreamChunk with tool_use variants; StopReason+TOOL_USE"
```

---

## Task 3: ToolException

**Files:**
- Create: `src/main/java/com/maplecode/error/ToolException.java`

- [ ] **Step 1: 写文件**

```java
package com.maplecode.error;

/**
 * 工具执行失败（文件不存在、exec 退出非零、grep regex 非法等）。
 * ToolExecutor 捕获后包成 ToolResult(isError=true)，不抛回 REPL。
 */
public class ToolException extends MapleCodeException {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/error/ToolException.java
git commit -m "feat(error): add ToolException"
```

---

## Task 4: Tool / ToolResult / ToolContext

**Files:**
- Create: `src/main/java/com/maplecode/tool/Tool.java`
- Create: `src/main/java/com/maplecode/tool/ToolResult.java`
- Create: `src/main/java/com/maplecode/tool/ToolContext.java`

- [ ] **Step 1: 写 ToolResult.java**

```java
package com.maplecode.tool;

/**
 * 工具执行的返回值。content 是字符串（v2 简化；后续可扩结构化）。
 * isError=true 时 content 是错误信息，会被回灌给模型让它重试。
 */
public record ToolResult(String content, boolean isError) {
    public static ToolResult ok(String content)    { return new ToolResult(content, false); }
    public static ToolResult error(String content) { return new ToolResult(content, true); }
}
```

- [ ] **Step 2: 写 ToolContext.java**

```java
package com.maplecode.tool;

import java.nio.file.Path;

/**
 * 工具执行上下文。所有工具的硬编码限都在这里，由 REPL 启动期构造。
 */
public record ToolContext(
    Path cwd,
    int readMaxBytes,             // 默认 1_048_576
    int execDefaultTimeoutSec,    // 默认 30
    int grepMaxResults,           // 默认 100
    int globMaxResults            // 默认 100
) {
    public static ToolContext defaults(Path cwd) {
        return new ToolContext(cwd, 1_048_576, 30, 100, 100);
    }
}
```

- [ ] **Step 3: 写 Tool.java**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具的统一接口。Provider 不知道工具语义；REPL/ToolExecutor
 * 通过这个接口发现工具并执行。
 *
 * sealed permits 列具体工具类——这样 ToolRegistry 在做异构 List 时
 * 类型已知，且新增工具时所有 switch / 注入点会编译失败被强制更新。
 */
public sealed interface Tool
    permits ReadFileTool,
            WriteFileTool,
            EditFileTool,
            ExecTool,
            GlobTool,
            GrepTool {

    /** 模型在 tool_use 块里写的工具名。 */
    String name();

    /** 模型看到的人类可读描述。 */
    String description();

    /** 工具的入参 JSON Schema。Provider mapper 直接透传给 wire 格式。 */
    JsonNode inputSchema();

    /**
     * 执行工具。args 是模型提供的 JSON 对象。
     * 实现约定：抛 ToolException 表示已知错误；抛其它 Exception 是 bug。
     * ToolExecutor 会兜底所有 Exception 包成 ToolResult.error。
     */
    ToolResult execute(JsonNode args, ToolContext ctx);
}
```

- [ ] **Step 4: 编译**

注意此时 sealed permits 引用的 6 个工具类还不存在——会编译失败。

```bash
mvn -q -DskipTests compile
```

Expected: FAIL with "permits clause declares subclass ReadFileTool but it is not defined" 等 6 个错。

这是预期——继续下一步。

- [ ] **Step 5: Commit（Tool.java 文件本身保留）**

```bash
git add src/main/java/com/maplecode/tool/
git commit -m "feat(tool): add Tool/ToolResult/ToolContext (permits 待实现类)"
```

- [ ] **Step 6: 留待后续任务填实类**

Task 11-16 创建具体工具类后这条编译才会过。继续。

---

## Task 5: ChatRequest.tools 字段

**Files:**
- Modify: `src/main/java/com/maplecode/provider/ChatRequest.java`

- [ ] **Step 1: 改 ChatRequest.java**

```java
package com.maplecode.provider;

import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking,  // nullable
    List<Tool> tools          // nullable；v1 旧测试传 null 也能跑
) {}
```

注意：现在 ChatRequest 引用 `com.maplecode.tool.Tool`，Task 4 还没建具体类。但是只引接口、接口已经存在，编译应通过。

- [ ] **Step 2: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS（Tool.java 是 sealed permits 但接口本身已存在）。

- [ ] **Step 3: 跑现有测试**

```bash
mvn -q test
```

Expected: 所有 v1 测试绿。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/provider/ChatRequest.java
git commit -m "feat(provider): ChatRequest.tools field"
```

---

## Task 6: ChatMessage + ChatSession + ChatSessionTest 重构

**Files:**
- Modify: `src/main/java/com/maplecode/provider/ChatMessage.java`
- Modify: `src/main/java/com/maplecode/session/ChatSession.java`
- Modify: `src/test/java/com/maplecode/session/ChatSessionTest.java`

**注意**：本任务三处文件必须同步修改，否则编译挂。

- [ ] **Step 1: 改 ChatMessage.java**

```java
package com.maplecode.provider;

import java.util.List;

/**
 * ChatMessage 持有 ContentBlock 列表。
 * Anthropic 一条消息可以含 text + tool_use 等多 block；
 * OpenAI 一条 assistant 消息可能同时有 content + tool_calls。
 * v1 的 String content 不够用，改为 List<ContentBlock>。
 */
public record ChatMessage(Role role, List<ContentBlock> blocks) {
    public enum Role { USER, ASSISTANT }
}
```

- [ ] **Step 2: 改 ChatSession.java**

```java
package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSession {

    private final List<ChatMessage> messages = new ArrayList<>();

    /** 便利方法：用户输入纯文本。 */
    public void appendUserText(String text) {
        appendUser(List.of(new ContentBlock.TextBlock(text)));
    }

    /** 添 user 消息。 */
    public void appendUser(List<ContentBlock> blocks) {
        messages.add(new ChatMessage(Role.USER, List.copyOf(blocks)));
    }

    /** 添 assistant 消息。 */
    public void appendAssistant(List<ContentBlock> blocks) {
        messages.add(new ChatMessage(Role.ASSISTANT, List.copyOf(blocks)));
    }

    public void clear() {
        messages.clear();
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking, null);
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking, List<Tool> tools) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking, tools);
    }
}
```

- [ ] **Step 3: 改 ChatSessionTest.java**

```java
package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatSessionTest {

    @Test
    void empty_session_toRequest_has_empty_messages() {
        var session = new ChatSession();
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(0, req.messages().size());
    }

    @Test
    void append_user_text_and_assistant_text_in_order() {
        var session = new ChatSession();
        session.appendUserText("u1");
        session.appendAssistant(List.of(new ContentBlock.TextBlock("a1")));
        session.appendUserText("u2");

        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(3, msgs.size());
        assertEquals(ChatMessage.Role.USER, msgs.get(0).role());
        assertEquals("u1", msgs.get(0).blocks().get(0).toString());  // TextBlock.toString
        assertEquals(ChatMessage.Role.ASSISTANT, msgs.get(1).role());
        assertEquals("a1", msgs.get(1).blocks().get(0).toString());
        assertEquals(ChatMessage.Role.USER, msgs.get(2).role());
    }

    @Test
    void clear_resets_messages() {
        var session = new ChatSession();
        session.appendUserText("x");
        session.clear();
        assertEquals(0, session.toRequest("m", null, null).messages().size());
    }

    @Test
    void toRequest_returns_immutable_copy() {
        var session = new ChatSession();
        session.appendUserText("u1");
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertThrows(UnsupportedOperationException.class, () -> msgs.add(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("rogue")))));
    }

    @Test
    void toRequest_passes_through_system_prompt_and_thinking() {
        var session = new ChatSession();
        session.appendUserText("hi");
        ChatRequest req = session.toRequest("claude-sonnet-4-6", "be terse", null);
        assertEquals("claude-sonnet-4-6", req.model());
        assertEquals("be terse", req.systemPrompt());
        assertEquals(null, req.thinking());
    }

    @Test
    void toRequest_passes_through_tools_when_provided() {
        var session = new ChatSession();
        session.appendUserText("hi");
        // 传 null tools 走原 3 参重载
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(null, req.tools());
    }

    @Test
    void append_blocks_makes_independent_copy() {
        var session = new ChatSession();
        var blocks = new java.util.ArrayList<ContentBlock>();
        blocks.add(new ContentBlock.TextBlock("hi"));
        session.appendUser(blocks);
        blocks.add(new ContentBlock.TextBlock("rogue"));
        // 改原 list 不应影响 session
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(1, msgs.get(0).blocks().size());
    }
}
```

**注意：** `TextBlock.toString()` 在 record 默认实现下是 `TextBlock[text=u1]`。改用 `((ContentBlock.TextBlock) msgs.get(0).blocks().get(0)).text()` 强转更稳。改成：

把上面三处 `assertEquals("u1", msgs.get(0).blocks().get(0).toString());` 改为：

```java
        assertEquals("u1", ((ContentBlock.TextBlock) msgs.get(0).blocks().get(0)).text());
        assertEquals(ChatMessage.Role.ASSISTANT, msgs.get(1).role());
        assertEquals("a1", ((ContentBlock.TextBlock) msgs.get(1).blocks().get(0)).text());
```

- [ ] **Step 4: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 跑测试**

```bash
mvn -q test -Dtest=ChatSessionTest
```

Expected: 6 个 test 全绿。

- [ ] **Step 6: 跑全测（应发现 mapper 测试挂）**

```bash
mvn -q test
```

Expected: FAIL——`AnthropicRequestMapperTest` 和 `OpenAiRequestMapperTest` 还在用旧 `new ChatMessage(USER, "x")` 签名，所以编译挂。预期。Task 7-8 修。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/provider/ChatMessage.java \
        src/main/java/com/maplecode/session/ChatSession.java \
        src/test/java/com/maplecode/session/ChatSessionTest.java
git commit -m "refactor(provider): ChatMessage uses List<ContentBlock> blocks"
```

---

## Task 7: AnthropicRequestMapper + Test 改为 content blocks

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java`
- Modify: `src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java`

- [ ] **Step 1: 改 AnthropicRequestMapper.java**

把整个文件替换成：

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AnthropicRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 16384;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey, Duration readTimeout) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/messages"))
            .timeout(readTimeout)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("max_tokens", MAX_TOKENS);
            root.put("stream", true);

            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                root.put("system", req.systemPrompt());
            }

            ArrayNode msgs = root.putArray("messages");
            for (var m : req.messages()) {
                msgs.add(encodeMessage(m));
            }

            if (req.thinking() != null) {
                ThinkingConfig tc = req.thinking();
                ObjectNode thinking = root.putObject("thinking");
                switch (tc.type()) {
                    case ADAPTIVE -> {
                        thinking.put("type", "adaptive");
                        ObjectNode outputConfig = root.putObject("output_config");
                        outputConfig.put("effort", tc.effort().name().toLowerCase());
                    }
                    case ENABLED -> {
                        thinking.put("type", "enabled");
                        thinking.put("budget_tokens", tc.budgetTokens());
                    }
                }
            }

            // tools 数组 —— Task 17 完整实现；本任务先留接口位置
            if (req.tools() != null && !req.tools().isEmpty()) {
                ArrayNode toolsArr = root.putArray("tools");
                for (var tool : req.tools()) {
                    ObjectNode tn = toolsArr.addObject();
                    tn.put("name", tool.name());
                    tn.put("description", tool.description());
                    tn.set("input_schema", tool.inputSchema());
                }
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize Anthropic request", e);
        }
    }

    private ObjectNode encodeMessage(com.maplecode.provider.ChatMessage m) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", m.role().name().toLowerCase());

        ArrayNode content = msg.putArray("content");
        for (var block : m.blocks()) {
            if (block instanceof ContentBlock.TextBlock tb) {
                content.addObject().put("type", "text").put("text", tb.text());
            } else if (block instanceof ContentBlock.ToolUseBlock tu) {
                ObjectNode b = content.addObject();
                b.put("type", "tool_use");
                b.put("id", tu.id());
                b.put("name", tu.name());
                b.set("input", tu.input());
            } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                ObjectNode b = content.addObject();
                b.put("type", "tool_result");
                b.put("tool_use_id", tr.toolUseId());
                b.put("content", tr.content());
                b.put("is_error", tr.isError());
            }
        }
        return msg;
    }
}
```

- [ ] **Step 2: 改 AnthropicRequestMapperTest.java**

把整个文件替换成：

```java
package com.maplecode.provider.anthropic;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.provider.ThinkingConfig.Effort;
import com.maplecode.provider.ThinkingConfig.Type;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    void minimal_request_no_thinking_no_system() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.anthropic.com", "sk-test", Duration.ofSeconds(30));
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("sk-test", http.headers().firstValue("x-api-key").orElseThrow());
        assertEquals("2023-06-01", http.headers().firstValue("anthropic-version").orElseThrow());
        assertEquals(Duration.ofSeconds(30), http.timeout().orElseThrow(),
            "read timeout should come from the supplied Duration, not a hardcoded 60s");

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4-6\""));
        // content 现在是 [{type:"text",text:"hi"}]
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"),
            "content must be a text block array, body was: " + body);
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"max_tokens\":16384"));
        assertFalse(body.contains("\"system\""), "system null must be absent");
        assertFalse(body.contains("\"thinking\""), "thinking null must be absent");
        assertFalse(body.contains("\"output_config\""), "no thinking means no output_config");
        assertFalse(body.contains("\"tools\""), "no tools means no tools array");
    }

    @Test
    void adaptive_thinking_emits_thinking_and_output_config() {
        var req = new ChatRequest("claude-opus-4-7", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"));
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"));
        assertFalse(body.contains("\"budget_tokens\""), "adaptive must not include budget_tokens");
        assertTrue(body.contains("\"system\":\"be terse\""));
    }

    @Test
    void enabled_thinking_emits_thinking_with_budget_tokens_only() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            new ThinkingConfig(Type.ENABLED, 10000, null));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}"));
        assertFalse(body.contains("\"output_config\""),
            "enabled must not write output_config");
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("claude-sonnet-4-6", null, List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u1"))),
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u2")))
        ), null);
        String body = mapper.toJsonBody(req);
        int u1 = body.indexOf("\"u1\"");
        int a1 = body.indexOf("\"a1\"");
        int u2 = body.indexOf("\"u2\"");
        assertTrue(u1 > 0 && a1 > u1 && u2 > a1, "messages must preserve input order");
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
mvn -q test -Dtest=AnthropicRequestMapperTest
```

Expected: 4 个 test 全绿。

- [ ] **Step 4: 跑全测**

```bash
mvn -q test
```

Expected: OpenAiRequestMapperTest 仍挂（Task 8 修）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java \
        src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java
git commit -m "refactor(anthropic): emit content block arrays; stub tools wire format"
```

---

## Task 8: OpenAiRequestMapper + Test 改为 content blocks

**Files:**
- Modify: `src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java`
- Modify: `src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java`

- [ ] **Step 1: 改 OpenAiRequestMapper.java**

把整个文件替换成：

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenAiRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey, Duration readTimeout) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .timeout(readTimeout)
            .header("content-type", "application/json")
            .header("authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("stream", true);

            // thinking 静默丢弃 —— v1 的 Chat Completions 没有这个字段
            // （后续可接到 o1 的 reasoning_effort）

            ArrayNode msgs = root.putArray("messages");
            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", "system")
                    .put("content", req.systemPrompt()));
            }
            for (var m : req.messages()) {
                ObjectNode om = encodeMessage(m);
                if (om != null) msgs.add(om);
            }

            // tools 数组 —— Task 18 完整实现
            if (req.tools() != null && !req.tools().isEmpty()) {
                ArrayNode toolsArr = root.putArray("tools");
                for (var tool : req.tools()) {
                    ObjectNode t = toolsArr.addObject();
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tool.name());
                    fn.put("description", tool.description());
                    fn.set("parameters", tool.inputSchema());
                }
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize OpenAI request", e);
        }
    }

    /**
     * OpenAI 编码方式：
     * - USER + TextBlock  → role=user, content=string
     * - USER + ToolResultBlock → role=tool, content=string, tool_call_id=...
     * - ASSISTANT + TextBlock → role=assistant, content=string
     * - ASSISTANT + ToolUseBlock → role=assistant, content=null, tool_calls=[{id,type:function,function:{name,arguments}}]
     *
     * 返回 null 表示该消息应当跳过（不该发生的边界情况）。
     */
    private ObjectNode encodeMessage(ChatMessage m) {
        ObjectNode msg = JSON.createObjectNode();
        var blocks = m.blocks();

        if (m.role() == ChatMessage.Role.USER) {
            // 检查是不是 tool_result
            if (blocks.size() == 1 && blocks.get(0) instanceof ContentBlock.ToolResultBlock tr) {
                msg.put("role", "tool");
                msg.put("content", tr.content());
                msg.put("tool_call_id", tr.toolUseId());
                return msg;
            }
            // 普通 user 消息：拼接 TextBlock 为 string
            msg.put("role", "user");
            StringBuilder sb = new StringBuilder();
            for (var b : blocks) {
                if (b instanceof ContentBlock.TextBlock tb) sb.append(tb.text());
            }
            msg.put("content", sb.toString());
            return msg;
        }

        // ASSISTANT
        msg.put("role", "assistant");
        StringBuilder textBuf = new StringBuilder();
        ArrayNode toolCalls = null;
        for (var b : blocks) {
            if (b instanceof ContentBlock.TextBlock tb) {
                textBuf.append(tb.text());
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                if (toolCalls == null) toolCalls = msg.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", tu.id());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", tu.name());
                fn.put("arguments", tu.input() == null ? "{}" : tu.input().toString());
            }
        }
        if (textBuf.length() > 0) msg.put("content", textBuf.toString());
        else msg.putNull("content");
        return msg;
    }
}
```

- [ ] **Step 2: 改 OpenAiRequestMapperTest.java**

把整个文件替换成：

```java
package com.maplecode.provider.openai;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRequestMapperTest {

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();

    @Test
    void minimal_request_with_user_text() {
        var req = new ChatRequest("gpt-5", null,
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.openai.com/v1", "sk-test", Duration.ofSeconds(30));
        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("Bearer sk-test", http.headers().firstValue("authorization").orElseThrow());
        assertEquals(Duration.ofSeconds(30), http.timeout().orElseThrow());

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"gpt-5\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertFalse(body.contains("\"thinking\""));
        assertFalse(body.contains("\"tools\""), "no tools means no tools array");
    }

    @Test
    void system_prompt_emits_system_role_message() {
        var req = new ChatRequest("gpt-5", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))), null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"system\",\"content\":\"be terse\""));
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("gpt-5", null, List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u1"))),
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("u2")))
        ), null);
        String body = mapper.toJsonBody(req);
        int u1 = body.indexOf("\"u1\"");
        int a1 = body.indexOf("\"a1\"");
        int u2 = body.indexOf("\"u2\"");
        assertTrue(u1 > 0 && a1 > u1 && u2 > a1, "messages must preserve input order");
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
mvn -q test -Dtest=OpenAiRequestMapperTest
```

Expected: 3 个 test 全绿。

- [ ] **Step 4: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java \
        src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java
git commit -m "refactor(openai): emit content blocks; tool_result → role:tool; stub tools wire format"
```

---

## Task 9: ToolRegistry

**Files:**
- Create: `src/main/java/com/maplecode/tool/ToolRegistry.java`
- Create: `src/test/java/com/maplecode/tool/ToolRegistryTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc-" + name; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.ok(""); }
            @Override public String toString() { return "tool:" + name; }
        };
    }

    @Test
    void empty_registry_all_is_empty() {
        var reg = new ToolRegistry(List.of());
        assertEquals(0, reg.all().size());
        assertEquals(Optional.empty(), reg.get("anything"));
    }

    @Test
    void all_returns_tools_in_construction_order() {
        var a = mk("a");
        var b = mk("b");
        var c = mk("c");
        var reg = new ToolRegistry(List.of(a, b, c));
        assertEquals(List.of(a, b, c), reg.all());
    }

    @Test
    void get_finds_by_name() {
        var a = mk("alpha");
        var b = mk("beta");
        var reg = new ToolRegistry(List.of(a, b));
        assertSame(a, reg.get("alpha").orElseThrow());
        assertSame(b, reg.get("beta").orElseThrow());
    }

    @Test
    void get_missing_returns_empty() {
        var reg = new ToolRegistry(List.of(mk("x")));
        assertEquals(Optional.empty(), reg.get("y"));
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=ToolRegistryTest
```

Expected: FAIL——`ToolRegistry` 类不存在。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        this.byName = new HashMap<>();
        for (var t : this.tools) {
            byName.put(t.name(), t);
        }
    }

    public List<Tool> all() {
        return tools;
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=ToolRegistryTest
```

Expected: 4 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/ToolRegistry.java \
        src/test/java/com/maplecode/tool/ToolRegistryTest.java
git commit -m "feat(tool): ToolRegistry with all() and get(name)"
```

---

## Task 10: ToolExecutor

**Files:**
- Create: `src/main/java/com/maplecode/tool/ToolExecutor.java`
- Create: `src/test/java/com/maplecode/tool/ToolExecutorTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.error.ToolException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private static Tool mk(String name, java.util.function.BiFunction<JsonNode, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    private final ToolContext ctx = ToolContext.defaults(java.nio.file.Path.of("/tmp"));

    @Test
    void run_executes_and_returns_ok() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ok-result"));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ok-result", r.content());
    }

    @Test
    void run_unknown_tool_returns_error_with_available_list() {
        var t = mk("alpha", (a, c) -> ToolResult.ok(""));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("missing", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().contains("Unknown tool: missing"), r.content());
        assertTrue(r.content().contains("alpha"), r.content());
    }

    @Test
    void run_catches_tool_exception_returns_error() {
        var t = mk("bar", (a, c) -> { throw new ToolException("boom"); });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("bar", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertEquals("boom", r.content());
    }

    @Test
    void run_catches_other_exception_returns_generic_error() {
        var t = mk("bar", (a, c) -> { throw new IllegalStateException("internal"); });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("bar", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().contains("internal error"), r.content());
        assertTrue(r.content().contains("IllegalStateException"), r.content());
    }

    @Test
    void run_passes_args_and_ctx_to_tool() {
        var t = mk("check", (a, c) -> {
            assertEquals("hi", a.path("x").asText());
            assertEquals("/tmp", c.cwd().toString());
            return ToolResult.ok("");
        });
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var args = new ObjectMapper().createObjectNode().put("x", "hi");
        exec.run("check", args);
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=ToolExecutorTest
```

Expected: FAIL——`ToolExecutor` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.util.stream.Collectors;

public final class ToolExecutor {

    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 调工具。绝不抛异常——所有失败都包成 ToolResult(isError=true)。
     */
    public ToolResult run(String name, JsonNode args) {
        var toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) {
            String available = registry.all().stream()
                .map(Tool::name)
                .collect(Collectors.joining(", "));
            return ToolResult.error("Unknown tool: " + name + ". Available: " + available);
        }
        try {
            // 缺 ctx 也要兜底：构造一个默认 ctx，cwd=.，保守限
            ToolContext ctx = ToolContext.defaults(java.nio.file.Path.of(System.getProperty("user.dir")));
            return toolOpt.get().execute(args, ctx);
        } catch (ToolException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

**注意：** REPL 在 Task 22 会构造 executor 时注入一个统一的 ctx。本任务让 ToolExecutor 自管一个 ctx 兜底（让 unit test 简单）。

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=ToolExecutorTest
```

Expected: 5 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/ToolExecutor.java \
        src/test/java/com/maplecode/tool/ToolExecutorTest.java
git commit -m "feat(tool): ToolExecutor with full fallback chain"
```

---

## Task 11: ReadFileTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/ReadFileTool.java`
- Create: `src/test/java/com/maplecode/tool/ReadFileToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.error.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void read_full_small_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("hello.txt");
        Files.writeString(f, "line1\nline2\nline3\n");
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals("   1\tline1\n   2\tline2\n   3\tline3\n", r.content());
        assertEquals(false, r.isError());
    }

    @Test
    void read_with_offset_and_limit(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("multi.txt");
        Files.writeString(f, "a\nb\nc\nd\ne\n");
        var args = JSON.createObjectNode().put("path", f.toString()).put("offset", 1).put("limit", 2);
        var r = tool.execute(args, ToolContext.defaults(tmp));
        // offset=1 是 0-indexed 即从第 2 行开始；limit=2 拿 b, c
        assertTrue(r.content().contains("b"));
        assertTrue(r.content().contains("c"));
        assertEquals(false, r.isError());
    }

    @Test
    void missing_file_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("path", tmp.resolve("nope.txt").toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("nope.txt"));
    }

    @Test
    void binary_file_rejected(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bin.dat");
        byte[] data = new byte[100];
        data[50] = 0;  // NUL in first 100 bytes
        Files.write(f, data);
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("binary"));
    }

    @Test
    void truncates_large_file_with_marker(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("big.txt");
        // 写入 2 MiB
        byte[] data = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(data, (byte) 'x');
        Files.write(f, data);
        var args = JSON.createObjectNode().put("path", f.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().endsWith("[truncated]"), "expected truncation marker, got tail: "
            + r.content().substring(r.content().length() - 50));
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=ReadFileToolTest
```

Expected: FAIL——`ReadFileTool` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class ReadFileTool implements Tool {

    private static final int BINARY_PROBE_BYTES = 8192;

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read a text file. Returns lines with line numbers. "
            + "Use offset (0-indexed) and limit to read parts of large files.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
            .put("description", "File path; relative paths resolve to cwd.");
        props.putObject("offset").put("type", "integer")
            .put("description", "0-indexed starting line number").put("default", 0);
        props.putObject("limit").put("type", "integer")
            .put("description", "Max lines to return").put("default", 2000);
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = requiredString(args, "path");
        int offset = args.has("offset") ? args.get("offset").asInt(0) : 0;
        int limit = args.has("limit") ? args.get("limit").asInt(2000) : 2000;
        if (offset < 0 || limit <= 0) {
            return ToolResult.error("offset must be >= 0 and limit must be > 0");
        }

        Path path = resolvePath(pathStr, ctx.cwd());
        if (!Files.exists(path)) return ToolResult.error("file not found: " + pathStr);
        if (Files.isDirectory(path)) return ToolResult.error("path is a directory: " + pathStr);

        // 二进制探测
        try (var probe = Files.newInputStream(path)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int n = probe.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return ToolResult.error("binary file not supported: " + pathStr);
            }
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        // 读
        List<String> allLines;
        try {
            allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        int start = Math.min(offset, allLines.size());
        int end = Math.min(start + limit, allLines.size());

        StringBuilder sb = new StringBuilder();
        int lineNoWidth = String.valueOf(end).length();
        // 简单右对齐——用 String.format；最小宽度 4，文件大时跟着 lineNoWidth 走
        int width = Math.max(4, lineNoWidth);
        for (int i = start; i < end; i++) {
            sb.append(String.format("%" + width + "d\t%s%n", i + 1, allLines.get(i)));
        }
        // 字节数截断：粗略估算（UTF-8 字节 ≈ 字符数对 ASCII；中文会超，这里只防全 ASCII 大文件）
        String out = sb.toString();
        if (out.getBytes(StandardCharsets.UTF_8).length > ctx.readMaxBytes()) {
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            String truncated = new String(bytes, 0, ctx.readMaxBytes(), StandardCharsets.UTF_8);
            // 防止切到多字节字符中间
            while (ctx.readMaxBytes > 0 && (truncated.getBytes(StandardCharsets.UTF_8).length > ctx.readMaxBytes()
                || (truncated.charAt(truncated.length() - 1) == '�'))) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            return ToolResult.ok(truncated + "\n[truncated]");
        }
        return ToolResult.ok(out);
    }

    static Path resolvePath(String p, Path cwd) {
        if (p.startsWith("/") || p.startsWith("~")) return Path.of(p);
        return Path.of(cwd.toString(), p);
    }

    static String requiredString(JsonNode args, String name) {
        JsonNode n = args.get(name);
        if (n == null || n.isNull()) {
            throw new ToolException("missing required argument: " + name);
        }
        return n.asText();
    }
}
```

**注意**：`%n` 在 macOS/Linux 上是换行；Windows 上不行。v1 已经在 Unix-only 路上，无影响。

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=ReadFileToolTest
```

Expected: 5 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/ReadFileTool.java \
        src/test/java/com/maplecode/tool/ReadFileToolTest.java
git commit -m "feat(tool): ReadFileTool with offset/limit/binary detect/truncation"
```

---

## Task 12: WriteFileTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/WriteFileTool.java`
- Create: `src/test/java/com/maplecode/tool/WriteFileToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void write_new_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("new.txt");
        var args = JSON.createObjectNode().put("path", f.toString()).put("content", "hello\n");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("hello\n", Files.readString(f));
        assertTrue(r.content().contains("wrote"));
    }

    @Test
    void overwrite_existing_file(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("old.txt");
        Files.writeString(f, "old");
        var args = JSON.createObjectNode().put("path", f.toString()).put("content", "new");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("new", Files.readString(f));
    }

    @Test
    void missing_parent_dir_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode()
            .put("path", tmp.resolve("nope/sub.txt").toString())
            .put("content", "x");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("parent directory"));
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=WriteFileToolTest
```

Expected: FAIL。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Write content to a file (overwrite if exists). "
            + "Parent directory must already exist.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
            .put("description", "File path; relative paths resolve to cwd.");
        props.putObject("content").put("type", "string")
            .put("description", "Full file content to write.");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = ReadFileTool.requiredString(args, "path");
        String content = ReadFileTool.requiredString(args, "content");
        Path path = ReadFileTool.resolvePath(pathStr, ctx.cwd());

        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            return ToolResult.error("parent directory does not exist: " + parent);
        }
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return ToolResult.ok("wrote " + bytes.length + " bytes to " + pathStr);
        } catch (IOException e) {
            throw new ToolException("write failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=WriteFileToolTest
```

Expected: 3 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/WriteFileTool.java \
        src/test/java/com/maplecode/tool/WriteFileToolTest.java
git commit -m "feat(tool): WriteFileTool (overwrite, no parent creation)"
```

---

## Task 13: EditFileTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/EditFileTool.java`
- Create: `src/test/java/com/maplecode/tool/EditFileToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unique_match_replaces(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "hello world\nbye world\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "hello world")
            .put("new_string", "hi world");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("hi world\nbye world\n", Files.readString(f));
    }

    @Test
    void zero_matches_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "hello world\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "missing")
            .put("new_string", "x");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("not found"));
    }

    @Test
    void multiple_matches_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "x\nx\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "x")
            .put("new_string", "y");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("matches 2"));
    }

    @Test
    void noop_returns_error(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("code.txt");
        Files.writeString(f, "abc\n");
        var args = JSON.createObjectNode()
            .put("path", f.toString())
            .put("old_string", "abc")
            .put("new_string", "abc");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("no-op"));
    }

    @Test
    void missing_file_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode()
            .put("path", tmp.resolve("nope.txt").toString())
            .put("old_string", "x")
            .put("new_string", "y");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("not found"));
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=EditFileToolTest
```

Expected: FAIL。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EditFileTool implements Tool {

    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() {
        return "Replace a unique string in a file. old_string must match exactly once. "
            + "Provide more context to disambiguate if it would match multiple locations.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string")
            .put("description", "Text to find; must match exactly once in the file.");
        props.putObject("new_string").put("type", "string")
            .put("description", "Replacement text.");
        schema.putArray("required").add("path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pathStr = ReadFileTool.requiredString(args, "path");
        String oldStr = ReadFileTool.requiredString(args, "old_string");
        String newStr = ReadFileTool.requiredString(args, "new_string");
        Path path = ReadFileTool.resolvePath(pathStr, ctx.cwd());

        if (!Files.exists(path)) {
            return ToolResult.error("file not found: " + pathStr);
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("read failed: " + e.getMessage(), e);
        }

        if (oldStr.equals(newStr)) {
            return ToolResult.error("no-op: old_string == new_string");
        }

        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return ToolResult.error("old_string not found in " + pathStr);
        }
        if (count > 1) {
            return ToolResult.error("old_string matches " + count + " locations in "
                + pathStr + "; provide more context to make it unique");
        }

        // 唯一匹配
        String updated = content.replace(oldStr, newStr);
        try {
            Files.writeString(path, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("write failed: " + e.getMessage(), e);
        }
        return ToolResult.ok("replaced 1 occurrence in " + pathStr);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=EditFileToolTest
```

Expected: 5 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/EditFileTool.java \
        src/test/java/com/maplecode/tool/EditFileToolTest.java
git commit -m "feat(tool): EditFileTool (strict unique-match replace)"
```

---

## Task 14: ExecTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/ExecTool.java`
- Create: `src/test/java/com/maplecode/tool/ExecToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecToolTest {

    private final ExecTool tool = new ExecTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void simple_echo_succeeds(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "echo hello");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("hello"), r.content());
    }

    @Test
    void non_zero_exit_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "exit 7");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("exit=7"), r.content());
    }

    @Test
    void empty_command_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "   ");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("empty"));
    }

    @Test
    void timeout_kills_long_command(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("command", "sleep 5").put("timeout_seconds", 1);
        long start = System.currentTimeMillis();
        var r = tool.execute(args, ToolContext.defaults(tmp));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("timeout"), r.content());
        assertTrue(elapsed < 4000, "should not wait full 5s; took " + elapsed + "ms");
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=ExecToolTest
```

Expected: FAIL。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ExecTool implements Tool {

    private static final int OUTPUT_MAX_BYTES = 50 * 1024;

    @Override
    public String name() { return "exec"; }

    @Override
    public String description() {
        return "Run a shell command and return its combined stdout+stderr. "
            + "Use timeout_seconds (default 30) to bound long-running commands.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("command").put("type", "string")
            .put("description", "Shell command (executed via /bin/sh -c).");
        props.putObject("timeout_seconds").put("type", "integer")
            .put("description", "Timeout in seconds; default 30.").put("default", 30);
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String command = ReadFileTool.requiredString(args, "command");
        if (command.isBlank()) {
            return ToolResult.error("empty command");
        }
        int timeout = args.has("timeout_seconds")
            ? args.get("timeout_seconds").asInt(ctx.execDefaultTimeoutSec())
            : ctx.execDefaultTimeoutSec();

        Process process;
        try {
            process = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(ctx.cwd().toFile())
                .redirectErrorStream(true)
                .start();
        } catch (Exception e) {
            return ToolResult.error("failed to start process: " + e.getMessage());
        }

        // 异步读 stdout，避免进程 pipe buffer 满而死锁
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) {
                    synchronized (out) {
                        out.append(buf, 0, n);
                    }
                }
            } catch (Exception ignored) {}
        }, "exec-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResult.error("interrupted");
        }

        if (!finished) {
            process.destroyForcibly();
            try { reader.join(1000); } catch (InterruptedException ignored) {}
            return ToolResult.error("timeout after " + timeout + "s");
        }

        try { reader.join(2000); } catch (InterruptedException ignored) {}
        int exit = process.exitValue();
        String output;
        synchronized (out) {
            output = out.toString();
        }
        if (output.getBytes(StandardCharsets.UTF_8).length > OUTPUT_MAX_BYTES) {
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            output = new String(bytes, 0, OUTPUT_MAX_BYTES, StandardCharsets.UTF_8) + "\n[truncated]";
        }
        if (exit == 0) return ToolResult.ok(output);
        return ToolResult.error("exit=" + exit + (output.isEmpty() ? "" : "\n" + output));
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=ExecToolTest
```

Expected: 4 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/ExecTool.java \
        src/test/java/com/maplecode/tool/ExecToolTest.java
git commit -m "feat(tool): ExecTool with timeout, output truncation, exit code in result"
```

---

## Task 15: GlobTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/GlobTool.java`
- Create: `src/test/java/com/maplecode/tool/GlobToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobToolTest {

    private final GlobTool tool = new GlobTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void non_recursive_glob(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("a"));
        Files.createDirectory(tmp.resolve("b"));
        Files.writeString(tmp.resolve("a/x.txt"), "");
        Files.writeString(tmp.resolve("a/y.java"), "");
        Files.writeString(tmp.resolve("b/z.java"), "");
        var args = JSON.createObjectNode().put("pattern", "**/*.java");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        // 相对路径，含 2 个 .java
        String[] lines = r.content().split("\n");
        long javaCount = 0;
        for (var l : lines) if (l.endsWith(".java")) javaCount++;
        assertEquals(2, javaCount, "expected 2 .java files, got: " + r.content());
    }

    @Test
    void zero_matches_returns_empty_not_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("pattern", "**/*.nope");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("", r.content());
    }

    @Test
    void truncates_above_max_results(@TempDir Path tmp) throws Exception {
        // 制造 110 个文件
        for (int i = 0; i < 110; i++) {
            Files.writeString(tmp.resolve("f" + i + ".txt"), "");
        }
        // 用 5 个 max
        var ctx = new ToolContext(tmp, 1_048_576, 30, 100, 5);
        var args = JSON.createObjectNode().put("pattern", "*.txt");
        var r = tool.execute(args, ctx);
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("[truncated"), r.content());
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=GlobToolTest
```

Expected: FAIL。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    @Override
    public String name() { return "glob"; }

    @Override
    public String description() {
        return "Find files matching a glob pattern (e.g. '**/*.java'). "
            + "Results are paths relative to cwd, one per line.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Glob pattern, e.g. '**/*.java' or '*.txt'.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pattern = ReadFileTool.requiredString(args, "pattern");
        Path cwd = ctx.cwd();

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(cwd)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(matches::add);
        } catch (IOException e) {
            throw new ToolException("walk failed: " + e.getMessage(), e);
        }

        int limit = ctx.globMaxResults();
        boolean truncated = matches.size() > limit;
        List<Path> shown = truncated ? matches.subList(0, limit) : matches;

        StringBuilder sb = new StringBuilder();
        for (var p : shown) {
            String rel = cwd.relativize(p).toString();
            sb.append(rel).append('\n');
        }
        if (truncated) {
            sb.append("[truncated, total=").append(matches.size()).append("]\n");
        }
        return ToolResult.ok(sb.toString());
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=GlobToolTest
```

Expected: 3 个 test 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/tool/GlobTool.java \
        src/test/java/com/maplecode/tool/GlobToolTest.java
git commit -m "feat(tool): GlobTool with walk + PathMatcher + truncation"
```

---

## Task 16: GrepTool

**Files:**
- Create: `src/main/java/com/maplecode/tool/GrepTool.java`
- Create: `src/test/java/com/maplecode/tool/GrepToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {

    private final GrepTool tool = new GrepTool();
    private final ObjectMapper JSON = new ObjectMapper();

    @Test
    void basic_regex_match(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "alpha\nbeta\ngamma\n");
        var args = JSON.createObjectNode().put("pattern", "be").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("beta"), r.content());
        assertTrue(r.content().contains("a.txt"), r.content());
        assertTrue(r.content().contains("2:"), r.content());
    }

    @Test
    void zero_matches_returns_empty(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "alpha\n");
        var args = JSON.createObjectNode().put("pattern", "zzz").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertEquals("", r.content());
    }

    @Test
    void invalid_regex_returns_error(@TempDir Path tmp) {
        var args = JSON.createObjectNode().put("pattern", "[unclosed").put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(true, r.isError());
        assertTrue(r.content().contains("invalid regex"));
    }

    @Test
    void include_glob_filters_files(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "needle\n");
        Files.writeString(tmp.resolve("a.md"), "needle\n");
        var args = JSON.createObjectNode()
            .put("pattern", "needle")
            .put("path", tmp.toString())
            .put("include_glob", "*.txt");
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("a.txt"), r.content());
        assertEquals(false, r.content().contains("a.md"), r.content());
    }

    @Test
    void binary_file_is_skipped(@TempDir Path tmp) throws Exception {
        byte[] data = new byte[200];
        data[50] = 0;
        Files.write(tmp.resolve("bin.dat"), data);
        Files.writeString(tmp.resolve("clean.txt"), "needle\n");
        var args = JSON.createObjectNode()
            .put("pattern", "needle")
            .put("path", tmp.toString());
        var r = tool.execute(args, ToolContext.defaults(tmp));
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("clean.txt"), r.content());
        assertEquals(false, r.content().contains("bin.dat"), r.content());
    }

    @Test
    void truncates_above_max_results(@TempDir Path tmp) throws Exception {
        // 110 个匹配行
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 110; i++) big.append("needle\n");
        Files.writeString(tmp.resolve("a.txt"), big.toString());
        var ctx = new ToolContext(tmp, 1_048_576, 30, 5, 100);
        var args = JSON.createObjectNode().put("pattern", "needle").put("path", tmp.toString());
        var r = tool.execute(args, ctx);
        assertEquals(false, r.isError());
        assertTrue(r.content().contains("[truncated"), r.content());
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=GrepToolTest
```

Expected: FAIL。

- [ ] **Step 3: 写实现**

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class GrepTool implements Tool {

    private static final int BINARY_PROBE_BYTES = 8192;

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "Search files for lines matching a regex. Returns path:lineno:content. "
            + "Use include_glob to restrict file types. Binary files are skipped.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Regular expression.");
        props.putObject("path").put("type", "string")
            .put("description", "Directory to search; default cwd.").put("default", ".");
        props.putObject("include_glob").put("type", "string")
            .put("description", "If set, only files whose name matches this glob are searched.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String patternStr = ReadFileTool.requiredString(args, "pattern");
        String pathStr = args.has("path") && !args.get("path").isNull()
            ? args.get("path").asText(".") : ".";
        String include = args.has("include_glob") && !args.get("include_glob").isNull()
            ? args.get("include_glob").asText() : null;

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("invalid regex: " + e.getMessage());
        }

        Path root = ReadFileTool.resolvePath(pathStr, ctx.cwd());
        if (!Files.exists(root)) return ToolResult.error("path not found: " + pathStr);

        PathMatcher fileMatcher = include != null
            ? FileSystems.getDefault().getPathMatcher("glob:" + include)
            : null;

        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            var iter = stream.filter(Files::isRegularFile).iterator();
            while (iter.hasNext()) {
                Path p = iter.next();
                if (fileMatcher != null && !fileMatcher.matches(p.getFileName())) continue;
                if (isBinary(p)) continue;
                List<String> fileLines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < fileLines.size(); i++) {
                    if (pattern.matcher(fileLines.get(i)).find()) {
                        String rel = root.relativize(p).toString();
                        lines.add(rel + ":" + (i + 1) + ":" + fileLines.get(i));
                        if (lines.size() >= ctx.grepMaxResults() * 2) {
                            // 多收集一些方便截断判断
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new ToolException("search failed: " + e.getMessage(), e);
        }

        int limit = ctx.grepMaxResults();
        boolean truncated = lines.size() > limit;
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, lines.size());
        for (int i = 0; i < n; i++) sb.append(lines.get(i)).append('\n');
        if (truncated) sb.append("[truncated, total=").append(lines.size()).append("]\n");
        return ToolResult.ok(sb.toString());
    }

    private static boolean isBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=GrepToolTest
```

Expected: 6 个 test 全绿。

- [ ] **Step 5: 跑全测（应绿——所有 tool sealed permits 现在都满足）**

```bash
mvn -q test
```

Expected: 全绿。Tool.java 的 permits 在 6 个具体类齐了之后编译过。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maplecode/tool/GrepTool.java \
        src/test/java/com/maplecode/tool/GrepToolTest.java
git commit -m "feat(tool): GrepTool with regex, include_glob, binary skip, truncation"
```

---

## Task 17: AnthropicRequestMapper tools wire format 完整 + 测试

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java`
- Create: `src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;  // unused
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperToolTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();
    private final ObjectMapper JSON = new ObjectMapper();

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc of " + name; }
            @Override public JsonNode inputSchema() {
                var s = JSON.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("path").put("type", "string");
                return s;
            }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.ok(""); }
        };
    }

    @Test
    void tools_field_emitted_when_provided() {
        var req = new ChatRequest("m", null,
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null,
            List.of(mk("read_file")));
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"tools\":["), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"description\":\"desc of read_file\""), body);
        assertTrue(body.contains("\"input_schema\":{"), body);
    }

    @Test
    void tool_use_message_wire_format() {
        var args = JSON.createObjectNode().put("path", "/tmp/x");
        var req = new ChatRequest("m", null,
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.ASSISTANT,
                List.of(
                    new ContentBlock.TextBlock("I'll read it"),
                    new ContentBlock.ToolUseBlock("tu_1", "read_file", args)
                ))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"assistant\""), body);
        assertTrue(body.contains("\"type\":\"text\""), body);
        assertTrue(body.contains("\"text\":\"I'll read it\""), body);
        assertTrue(body.contains("\"type\":\"tool_use\""), body);
        assertTrue(body.contains("\"id\":\"tu_1\""), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"input\":{\"path\":\"/tmp/x\"}"), body);
    }

    @Test
    void tool_result_message_wire_format() {
        var req = new ChatRequest("m", null,
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.USER,
                List.of(new ContentBlock.ToolResultBlock("tu_1", "file contents", false)))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertTrue(body.contains("\"type\":\"tool_result\""), body);
        assertTrue(body.contains("\"tool_use_id\":\"tu_1\""), body);
        assertTrue(body.contains("\"content\":\"file contents\""), body);
        assertTrue(body.contains("\"is_error\":false"), body);
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=AnthropicRequestMapperToolTest
```

Expected: FAIL——Task 7 已写基础（`if (req.tools() != null...)`），tool_result 也已写。**这 3 个 test 在 Task 7 应已部分通过；tool_result 已经在 Task 7 写好。所以这一步只需要补"tools 数组"的 wire format 已经存在**——可能已经全过。直接跑确认。

如果全过，跳到 Step 4（commit）。

- [ ] **Step 3: 如有失败，按需改 AnthropicRequestMapper.java**

如果 test 失败（比如 tools 数组格式不对），改 `AnthropicRequestMapper.java` 的 `if (req.tools() != null && !req.tools().isEmpty())` 块。当前已写好；不需改。

- [ ] **Step 4: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperToolTest.java
git commit -m "test(anthropic): tools array / tool_use / tool_result wire format"
```

---

## Task 18: OpenAiRequestMapper tools wire format 完整 + 测试

**Files:**
- Create: `src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRequestMapperToolTest {

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();
    private final ObjectMapper JSON = new ObjectMapper();

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc-" + name; }
            @Override public JsonNode inputSchema() {
                var s = JSON.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("path").put("type", "string");
                return s;
            }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.ok(""); }
        };
    }

    @Test
    void tools_field_wrapped_in_function_type() {
        var req = new ChatRequest("m", null,
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null, List.of(mk("read_file")));
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"type\":\"function\""), body);
        assertTrue(body.contains("\"function\":{"), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"description\":\"desc-read_file\""), body);
        assertTrue(body.contains("\"parameters\":{"), body);
    }

    @Test
    void assistant_message_with_tool_calls_emits_tool_calls_array() {
        var args = JSON.createObjectNode().put("path", "/tmp/x");
        var req = new ChatRequest("m", null,
            List.of(new ChatMessage(ChatMessage.Role.ASSISTANT,
                List.of(
                    new ContentBlock.TextBlock("I'll read it"),
                    new ContentBlock.ToolUseBlock("call_1", "read_file", args)
                ))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"assistant\""), body);
        assertTrue(body.contains("\"tool_calls\":["), body);
        assertTrue(body.contains("\"id\":\"call_1\""), body);
        assertTrue(body.contains("\"type\":\"function\""), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"arguments\":\"{\\\"path\\\":\\\"/tmp/x\\\"}\"")
            || body.contains("\"arguments\":\"{"), body);
    }

    @Test
    void tool_result_becomes_role_tool_message() {
        var req = new ChatRequest("m", null,
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.ToolResultBlock("call_1", "file contents", false)))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"tool\""), body);
        assertTrue(body.contains("\"content\":\"file contents\""), body);
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), body);
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
mvn -q test -Dtest=OpenAiRequestMapperToolTest
```

Expected: 全绿。Task 8 已写好 wire format 转换。

- [ ] **Step 3: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperToolTest.java
git commit -m "test(openai): tools wrapped in function / tool_calls / role:tool wire format"
```

---

## Task 19: AnthropicStreamParser tool_use 解析 + 测试

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`
- Create: `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.provider.anthropic;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicStreamParserToolTest {

    private final AnthropicStreamParser parser = new AnthropicStreamParser();

    private static SseEvent ev(String type, String data) {
        return new SseEvent(type, data);
    }

    @Test
    void tool_use_start_delta_stop_emits_three_chunks() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        Consumer_ c = out::add;

        parser.feed(ev("content_block_start",
            "{\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\"}}"), c);
        parser.feed(ev("content_block_delta",
            "{\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}"), c);
        parser.feed(ev("content_block_delta",
            "{\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"/tmp/x\\\"}\"}}"), c);
        parser.feed(ev("content_block_stop", "{\"index\":1}"), c);

        // 1 个 ToolUseStart, 2 个 ToolUseDelta, 1 个 ToolUseEnd
        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long deltas = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseDelta).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        assertEquals(1, starts);
        assertEquals(2, deltas);
        assertEquals(1, ends);

        StreamChunk.ToolUseEnd end = (StreamChunk.ToolUseEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).findFirst().orElseThrow();
        assertEquals("tu_1", end.id());
        assertEquals("read_file", end.name());
        assertEquals("/tmp/x", end.input().get("path").asText());
    }

    @Test
    void stop_reason_tool_use_maps_to_TOOL_USE() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        parser.feed(ev("message_delta",
            "{\"delta\":{\"stop_reason\":\"tool_use\"}}"), out::add);
        parser.feed(ev("message_stop", "{}"), out::add);
        StreamChunk.MessageEnd me = (StreamChunk.MessageEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.MessageEnd).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.TOOL_USE, me.reason());
    }

    @Test
    void invalid_partial_json_emits_error_chunk_not_throw() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        parser.feed(ev("content_block_start",
            "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"foo\"}}"), out::add);
        parser.feed(ev("content_block_delta",
            "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{not json\"}}"), out::add);
        parser.feed(ev("content_block_stop", "{\"index\":0}"), out::add);
        // 应有 Error chunk，不抛
        assertTrue(out.stream().anyMatch(c2 -> c2 instanceof StreamChunk.Error),
            "expected Error chunk, got: " + out);
    }

    @FunctionalInterface
    interface Consumer_ extends java.util.function.Consumer<StreamChunk> {}
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=AnthropicStreamParserToolTest
```

Expected: FAIL——parser 当前不处理 tool_use 事件。

- [ ] **Step 3: 改 AnthropicStreamParser.java**

把整个文件替换成：

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class AnthropicStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockType currentBlock = BlockType.NONE;
    private String lastStopReason = null;
    private String currentToolUseId = null;
    private String currentToolName = null;
    private StringBuilder currentToolJson = new StringBuilder();

    private enum BlockType { NONE, THINKING, TEXT, TOOL_USE }

    public void reset() {
        currentBlock = BlockType.NONE;
        lastStopReason = null;
        currentToolUseId = null;
        currentToolName = null;
        currentToolJson.setLength(0);
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        String type = event.eventType();
        if (type.equals("message_start")) {
            currentBlock = BlockType.NONE;
            lastStopReason = null;
            currentToolUseId = null;
            currentToolName = null;
            currentToolJson.setLength(0);
            sink.accept(new StreamChunk.MessageStart());
            return;
        }
        if (type.equals("content_block_start")) {
            JsonNode node = parse(event.data());
            String blockType = node.path("content_block").path("type").asText("");
            currentBlock = switch (blockType) {
                case "thinking" -> BlockType.THINKING;
                case "text"     -> BlockType.TEXT;
                case "tool_use" -> {
                    currentToolUseId = node.path("content_block").path("id").asText("");
                    currentToolName = node.path("content_block").path("name").asText("");
                    currentToolJson.setLength(0);
                    sink.accept(new StreamChunk.ToolUseStart(currentToolUseId, currentToolName));
                    yield BlockType.TOOL_USE;
                }
                default -> BlockType.NONE;
            };
            return;
        }
        if (type.equals("content_block_delta")) {
            JsonNode node = parse(event.data());
            JsonNode delta = node.path("delta");
            String deltaType = delta.path("type").asText("");
            if (deltaType.equals("thinking_delta")) {
                sink.accept(new StreamChunk.ThinkingDelta(delta.path("thinking").asText("")));
            } else if (deltaType.equals("text_delta")) {
                sink.accept(new StreamChunk.TextDelta(delta.path("text").asText("")));
            } else if (deltaType.equals("input_json_delta")) {
                String partial = delta.path("partial_json").asText("");
                if (currentToolUseId != null) {
                    currentToolJson.append(partial);
                    sink.accept(new StreamChunk.ToolUseDelta(currentToolUseId, partial));
                }
            }
            return;
        }
        if (type.equals("content_block_stop")) {
            if (currentBlock == BlockType.TOOL_USE && currentToolUseId != null) {
                JsonNode input;
                try {
                    input = JSON.readTree(currentToolJson.toString());
                } catch (Exception e) {
                    sink.accept(new StreamChunk.Error("tool_input_invalid",
                        "tool input not valid JSON: " + e.getMessage()));
                    input = JSON.createObjectNode();
                }
                sink.accept(new StreamChunk.ToolUseEnd(currentToolUseId, currentToolName, input));
                currentToolUseId = null;
                currentToolName = null;
                currentToolJson.setLength(0);
            }
            currentBlock = BlockType.NONE;
            return;
        }
        if (type.equals("message_delta")) {
            JsonNode node = parse(event.data());
            String stopReason = node.path("delta").path("stop_reason").asText(null);
            if (stopReason != null && !stopReason.isEmpty()) {
                lastStopReason = stopReason;
            }
            return;
        }
        if (type.equals("message_stop")) {
            sink.accept(new StreamChunk.MessageEnd(mapStopReason(lastStopReason)));
            return;
        }
        if (type.equals("error")) {
            JsonNode node = parse(event.data());
            String code = node.path("error").path("type").asText("unknown");
            String msg = node.path("error").path("message").asText("");
            sink.accept(new StreamChunk.Error(code, msg));
            return;
        }
        // ping / 未知 —— 忽略
    }

    private static StreamChunk.StopReason mapStopReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return StreamChunk.StopReason.END_TURN;
        }
        return switch (reason) {
            case "end_turn"      -> StreamChunk.StopReason.END_TURN;
            case "max_tokens"    -> StreamChunk.StopReason.MAX_TOKENS;
            case "stop_sequence" -> StreamChunk.StopReason.STOP;
            case "tool_use"      -> StreamChunk.StopReason.TOOL_USE;
            default              -> StreamChunk.StopReason.STOP;
        };
    }

    private JsonNode parse(String data) {
        try {
            return JSON.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse Anthropic SSE data: " + data, e);
        }
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=AnthropicStreamParserToolTest
```

Expected: 3 个 test 全绿。

- [ ] **Step 5: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java \
        src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserToolTest.java
git commit -m "feat(anthropic): stream parser emits ToolUseStart/Delta/End + TOOL_USE stop_reason"
```

---

## Task 20: OpenAiStreamParser tool_calls 解析 + 测试

**Files:**
- Modify: `src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`
- Create: `src/test/java/com/maplecode/provider/openai/OpenAiStreamParserToolTest.java`

- [ ] **Step 1: 写测试**

```java
package com.maplecode.provider.openai;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamParserToolTest {

    private final OpenAiStreamParser parser = new OpenAiStreamParser();

    private static SseEvent ev(String data) {
        return new SseEvent("", data);
    }

    @Test
    void tool_calls_split_across_deltas_emits_three_chunks() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        var c = out::add;

        // 第一个 delta：id + name 出现
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]},"
                + "\"finish_reason\":null}]}"), c);
        // 第二个 delta：arguments 片段
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{\\\"path\":\"}}]},\"finish_reason\":null}]}"), c);
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"\\\"/tmp/x\\\"}\"}}]},\"finish_reason\":null}]}"), c);
        // 结束
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"), c);

        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long deltas = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseDelta).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        long me = out.stream().filter(c2 -> c2 instanceof StreamChunk.MessageEnd).count();
        assertEquals(1, starts, "ToolUseStart count; got: " + out);
        assertEquals(2, deltas, "ToolUseDelta count; got: " + out);
        assertEquals(1, ends, "ToolUseEnd count; got: " + out);
        assertEquals(1, me, "MessageEnd count");

        StreamChunk.ToolUseEnd end = (StreamChunk.ToolUseEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).findFirst().orElseThrow();
        assertEquals("call_1", end.id());
        assertEquals("read_file", end.name());
        assertEquals("/tmp/x", end.input().get("path").asText());

        StreamChunk.MessageEnd me2 = (StreamChunk.MessageEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.MessageEnd).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.TOOL_USE, me2.reason());
    }

    @Test
    void multiple_parallel_tool_calls_all_flushed() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        var c = out::add;

        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"foo\",\"arguments\":\"\"}},"
                + "{\"index\":1,\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"bar\",\"arguments\":\"\"}}"
                + "]},\"finish_reason\":null}]}"), c);
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"function\":{\"arguments\":\"{}\"}}"
                + "]},\"finish_reason\":\"tool_calls\"}]}"), c);

        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        assertEquals(2, starts);
        assertEquals(2, ends);
    }
}
```

- [ ] **Step 2: 跑测试确认挂**

```bash
mvn -q test -Dtest=OpenAiStreamParserToolTest
```

Expected: FAIL。

- [ ] **Step 3: 改 OpenAiStreamParser.java**

把整个文件替换成：

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean ended = false;
    private final Map<Integer, ToolAcc> toolAccs = new LinkedHashMap<>();

    private static class ToolAcc {
        String id;
        String name;
        StringBuilder args = new StringBuilder();
    }

    public void reset() {
        ended = false;
        toolAccs.clear();
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        if (ended) return;
        String data = event.data();
        if (data == null) return;
        if (data.equals("[DONE]")) {
            flushTools(sink);
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.STOP));
            ended = true;
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            return;
        }

        if (node.has("error")) {
            JsonNode err = node.path("error");
            sink.accept(new StreamChunk.Error(
                err.path("type").asText("unknown"),
                err.path("message").asText("")));
            return;
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode choice0 = choices.get(0);
        JsonNode delta = choice0.path("delta");

        // 普通文本
        String content = delta.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
            sink.accept(new StreamChunk.TextDelta(content));
        }

        // tool_calls
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (var tc : toolCalls) {
                int idx = tc.path("index").asInt(0);
                ToolAcc acc = toolAccs.computeIfAbsent(idx, k -> new ToolAcc());

                String id = tc.path("id").asText(null);
                if (id != null && !id.isEmpty() && acc.id == null) {
                    acc.id = id;
                }
                String name = tc.path("function").path("name").asText(null);
                if (name != null && !name.isEmpty()) {
                    acc.name = name;
                }
                String args = tc.path("function").path("arguments").asText(null);
                if (args != null && !args.isEmpty() && acc.id != null) {
                    acc.args.append(args);
                    sink.accept(new StreamChunk.ToolUseDelta(acc.id, args));
                }
            }
        }

        String finish = choice0.path("finish_reason").asText("");
        if (!finish.isEmpty() && !"null".equals(finish)) {
            // 在发 MessageEnd 之前先把所有累积的工具 flush
            if (finish.equals("tool_calls")) {
                flushTools(sink);
            }
            StreamChunk.StopReason reason = switch (finish) {
                case "stop"        -> StreamChunk.StopReason.STOP;
                case "length"      -> StreamChunk.StopReason.MAX_TOKENS;
                case "error"       -> StreamChunk.StopReason.ERROR;
                case "tool_calls"  -> StreamChunk.StopReason.TOOL_USE;
                default            -> StreamChunk.StopReason.STOP;
            };
            sink.accept(new StreamChunk.MessageEnd(reason));
            ended = true;
        }
    }

    private void flushTools(Consumer<StreamChunk> sink) {
        for (var entry : toolAccs.entrySet()) {
            ToolAcc acc = entry.getValue();
            if (acc.id == null) continue;  // 没 id 视为无效
            // 先发 Start（如果还没发）
            sink.accept(new StreamChunk.ToolUseStart(acc.id, acc.name == null ? "" : acc.name));
            JsonNode input;
            try {
                input = acc.args.length() == 0
                    ? JSON.createObjectNode()
                    : JSON.readTree(acc.args.toString());
            } catch (Exception e) {
                sink.accept(new StreamChunk.Error("tool_input_invalid",
                    "tool input not valid JSON: " + e.getMessage()));
                continue;
            }
            sink.accept(new StreamChunk.ToolUseEnd(acc.id, acc.name == null ? "" : acc.name, input));
        }
        toolAccs.clear();
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest=OpenAiStreamParserToolTest
```

Expected: 2 个 test 全绿。

- [ ] **Step 5: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java \
        src/test/java/com/maplecode/provider/openai/OpenAiStreamParserToolTest.java
git commit -m "feat(openai): stream parser emits ToolUseStart/Delta/End; tracks tool_calls by index; flushes on finish_reason=tool_calls"
```

---

## Task 21: StreamPrinter toolStart/toolEnd

**Files:**
- Modify: `src/main/java/com/maplecode/ui/StreamPrinter.java`

- [ ] **Step 1: 改 StreamPrinter.java**

把整个文件替换成：

```java
package com.maplecode.ui;

import java.io.PrintStream;

public final class StreamPrinter {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";
    private static final String GREEN = "\033[32m";

    private final PrintStream out;

    public StreamPrinter() {
        this(System.out);
    }

    public StreamPrinter(PrintStream out) {
        this.out = out;
    }

    public void banner(String text) {
        out.println(BOLD + text + RESET);
        out.println();
    }

    public void startAssistant() {
        // 空操作 —— 由 REPL 自行追踪
    }

    public void write(String text) {
        out.print(text);
        out.flush();
    }

    public void writeThinking(String text) {
        out.print(DIM + text + RESET);
        out.flush();
    }

    public void endAssistant() {
        out.println();
    }

    public void error(String message) {
        out.println(RED + "✗ " + message + RESET);
    }

    public void info(String message) {
        out.println(message);
    }

    public void newline() {
        out.println();
    }

    /** 工具开始。灰字行：⚙ read_file /tmp/x */
    public void toolStart(String name, String argSummary) {
        if (argSummary == null || argSummary.isEmpty()) {
            out.println(DIM + "⚙ " + name + RESET);
        } else {
            out.println(DIM + "⚙ " + name + " " + argSummary + RESET);
        }
        out.flush();
    }

    /** 工具结束。绿字 ✓ 或红字 ✗ */
    public void toolEnd(String name, boolean success, String errorDetail) {
        if (success) {
            out.println(GREEN + "✓ " + name + RESET);
        } else {
            String msg = errorDetail == null || errorDetail.isEmpty() ? "" : ": " + errorDetail;
            // 多行错误只取第一行，避免刷屏
            int nl = msg.indexOf('\n');
            if (nl > 0) msg = msg.substring(0, nl);
            out.println(RED + "✗ " + name + msg + RESET);
        }
        out.flush();
    }
}
```

- [ ] **Step 2: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/ui/StreamPrinter.java
git commit -m "feat(ui): StreamPrinter.toolStart/toolEnd"
```

---

## Task 22: ReplLoop 工具流 + /tools

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`
- Modify: `src/main/java/com/maplecode/App.java`（ReplLoop 构造器签名变化）

- [ ] **Step 1: 改 ReplLoop.java**

把整个文件替换成：

```java
package com.maplecode.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReplLoop {

    private final AppConfig config;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ChatSession session = new ChatSession();
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ToolContext toolCtx;

    public ReplLoop(AppConfig config, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry) {
        this.config = config;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = new ToolExecutor(registry);
        this.toolCtx = ToolContext.defaults(Path.of(System.getProperty("user.dir")));
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                      ToolRegistry registry) throws java.io.IOException {
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
                continue;
            } catch (RuntimeException e) {
                break;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("/exit")) break;
            if (trimmed.equals("/clear")) {
                session.clear();
                printer.info("history cleared");
                continue;
            }
            if (trimmed.equals("/tools")) {
                printTools();
                continue;
            }

            session.appendUserText(trimmed);
            runOneTurn();
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

    /**
     * 单轮工具调用循环：模型发 tool_use → 执行 → 回灌 → 模型再回话 → 结束。
     * v2 严格 1 个 tool_use；多则报错不修改 session。
     */
    private void runOneTurn() {
        while (true) {
            TurnAccumulator acc = new TurnAccumulator();
            ChatRequest req = session.toRequest(
                config.model(), config.systemPrompt(), config.thinking(), registry.all());
            try {
                provider.stream(req, chunk -> handleChunk(chunk, acc));
            } catch (ProviderException e) {
                printer.error("request failed: " + e.getMessage());
                return;
            }

            if (acc.stopReason != StreamChunk.StopReason.TOOL_USE) {
                if (!acc.text.isEmpty()) {
                    session.appendAssistant(List.of(new ContentBlock.TextBlock(acc.text)));
                }
                return;
            }

            if (acc.toolUses.size() != 1) {
                printer.error("expected exactly 1 tool_use, got " + acc.toolUses.size());
                return;
            }

            var tu = acc.toolUses.get(0);
            // 把 assistant 这一轮的 text + tool_use 都写入 session
            var assistantBlocks = new ArrayList<ContentBlock>();
            if (!acc.text.isEmpty()) {
                assistantBlocks.add(new ContentBlock.TextBlock(acc.text));
            }
            assistantBlocks.add(new ContentBlock.ToolUseBlock(tu.id(), tu.name(), tu.input()));
            session.appendAssistant(assistantBlocks);

            // 跑工具
            var result = executor.run(tu.name(), tu.input());
            printer.toolEnd(tu.name(), !result.isError(), result.isError() ? result.content() : null);

            // 回灌 tool_result
            session.appendUser(List.of(new ContentBlock.ToolResultBlock(
                tu.id(), result.content(), result.isError())));
            // 继续 while 让模型看到结果再回话
        }
    }

    private void handleChunk(StreamChunk chunk, TurnAccumulator acc) {
        switch (chunk) {
            case StreamChunk.TextDelta d -> {
                printer.write(d.text());
                acc.text.append(d.text());
            }
            case StreamChunk.ThinkingDelta d -> printer.writeThinking(d.text());
            case StreamChunk.ToolUseStart d -> {
                printer.toolStart(d.name(), argSummary(d.id(), d.name(), acc));
                acc.pendingToolId = d.id();
                acc.pendingToolName = d.name();
            }
            case StreamChunk.ToolUseDelta d -> {
                acc.pendingToolId = d.id();
                acc.pendingToolJson.append(d.partialJson());
            }
            case StreamChunk.ToolUseEnd d -> {
                acc.toolUses.add(new PendingToolUse(d.id(), d.name(), d.input()));
                acc.pendingToolId = null;
                acc.pendingToolName = null;
                acc.pendingToolJson.setLength(0);
            }
            case StreamChunk.MessageStart s -> { /* 空 */ }
            case StreamChunk.MessageEnd e -> acc.stopReason = e.reason();
            case StreamChunk.Error e -> printer.error(e.code() + ": " + e.message());
        }
    }

    /** 从累积的 partialJson 里抽 path/command/pattern 等做状态行摘要。 */
    private String argSummary(String toolId, String toolName, TurnAccumulator acc) {
        // 优先用已累积的 partialJson 解析
        String partial = acc.pendingToolJson.toString();
        if (partial.isEmpty()) return "";
        try {
            JsonNode node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(partial);
            var path = node.path("path");
            if (!path.isMissingNode()) return path.asText();
            var cmd = node.path("command");
            if (!cmd.isMissingNode()) return cmd.asText();
            var pattern = node.path("pattern");
            if (!pattern.isMissingNode()) return pattern.asText();
        } catch (Exception ignored) {}
        // 截断 partial json
        return partial.length() > 40 ? partial.substring(0, 40) + "..." : partial;
    }

    private static class TurnAccumulator {
        StringBuilder text = new StringBuilder();
        List<PendingToolUse> toolUses = new ArrayList<>();
        StreamChunk.StopReason stopReason;
        // 跟踪 pending（start 已发但 end 还没到）
        String pendingToolId;
        String pendingToolName;
        StringBuilder pendingToolJson = new StringBuilder();
    }

    private record PendingToolUse(String id, String name, JsonNode input) {}

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

- [ ] **Step 2: 改 App.java**

把整个文件替换成：

```java
package com.maplecode;

import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.tool.EditFileTool;
import com.maplecode.tool.ExecTool;
import com.maplecode.tool.GlobTool;
import com.maplecode.tool.GrepTool;
import com.maplecode.tool.ReadFileTool;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.WriteFileTool;
import com.maplecode.ui.ReplLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class App {

    public static void main(String[] args) throws Exception {
        Path configPath = locateConfig(args);
        if (configPath == null) {
            System.err.println("no config found. Looked in:");
            System.err.println("  --config <path> argument");
            System.err.println("  ./maplecode.yaml");
            System.err.println("  ~/.maplecode/config.yaml");
            System.err.println("Run with `maplecode --config path/to/config.yaml`");
            System.exit(78);
        }
        AppConfig config = ConfigLoader.load(configPath);
        LlmProvider provider = new ProviderRegistry().create(config);
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ExecTool(),
            new GlobTool(),
            new GrepTool()
        ));
        ReplLoop repl = ReplLoop.fromConfig(config, provider, registry);
        repl.run();
    }

    private static Path locateConfig(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) return Paths.get(args[i + 1]);
        }
        Path local = Paths.get("maplecode.yaml");
        if (Files.exists(local)) return local;
        Path home = Paths.get(System.getProperty("user.home"), ".maplecode", "config.yaml");
        if (Files.exists(home)) return home;
        return null;
    }
}
```

- [ ] **Step 3: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 跑全测**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java \
        src/main/java/com/maplecode/App.java
git commit -m "feat(repl): tool flow (single-turn loop) + /tools slash command; App constructs ToolRegistry"
```

---

## Task 23: package 打 jar

- [ ] **Step 1: package**

```bash
mvn -q package
```

Expected: BUILD SUCCESS，产出 `target/maple-code-java-0.1.0.jar`。

- [ ] **Step 2: 验证 jar 可执行**

```bash
java -jar target/maple-code-java-0.1.0.jar 2>&1 | head -5
```

Expected: 输出 "no config found" 错误（因没 config 文件），退出码 78。说明 jar 启动成功。

- [ ] **Step 3: 跑全测确认无回归**

```bash
mvn -q test
```

Expected: 全绿。

- [ ] **Step 4: 手动 smoke（可选，需 config + API key）**

配置好 `maplecode.yaml` 后：

```bash
java -jar target/maple-code-java-0.1.0.jar
```

输入 `列出这个目录里的 java 文件`，等模型回 `glob "**/*.java"`，看到终端 `⚙ glob **/*.java` → `✓ glob`，模型最终文本含文件列表。Ctrl+D 退出。

如果不是带 API key 环境，跳过这个步骤，标记为"待人工验收"。

- [ ] **Step 5: Commit（target/ 已在 .gitignore；无文件需提交）**

无文件需 commit。如果改了 .gitignore，commit 那个。

- [ ] **Step 6: 总结**

输出到终端：
```
mvn test 全绿 ✓
- 14 个 v2 新测试（ToolRegistry/ToolExecutor + 6 个工具 + 4 个 Provider 工具相关）
- 3 个 v1 测试已迁移到新 API
- 6 个 tool 类 + Tool/ToolResult/ToolContext + ToolRegistry + ToolExecutor
- ReplLoop 支持单轮工具调用
- /tools 命令列出工具
- 退码 78 (config) / 0 (运行) 不变
jar 可执行 ✓
```

---

## 任务检查表

- [ ] T1 ContentBlock
- [ ] T2 StreamChunk/StopReason
- [ ] T3 ToolException
- [ ] T4 Tool/ToolResult/ToolContext
- [ ] T5 ChatRequest.tools
- [ ] T6 ChatMessage/ChatSession 重构
- [ ] T7 AnthropicRequestMapper + test
- [ ] T8 OpenAiRequestMapper + test
- [ ] T9 ToolRegistry + test
- [ ] T10 ToolExecutor + test
- [ ] T11 ReadFileTool + test
- [ ] T12 WriteFileTool + test
- [ ] T13 EditFileTool + test
- [ ] T14 ExecTool + test
- [ ] T15 GlobTool + test
- [ ] T16 GrepTool + test
- [ ] T17 AnthropicRequestMapper tools 测试
- [ ] T18 OpenAiRequestMapper tools 测试
- [ ] T19 AnthropicStreamParser tool_use + test
- [ ] T20 OpenAiStreamParser tool_calls + test
- [ ] T21 StreamPrinter toolStart/toolEnd
- [ ] T22 ReplLoop + App
- [ ] T23 打包验证

每任务结束 `mvn -q test` 全绿后 commit。
