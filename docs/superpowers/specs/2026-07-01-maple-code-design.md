# MapleCode — 阶段一设计规格

**日期**：2026-07-01
**范围**：仅做命令行 AI 对话（TUI + 多 Provider 流式聊天）；不做 tool use、文件操作、代码编辑。

---

## 1. 目标与非目标

**目标**
- 在终端启动 `maplecode` 进入交互式 REPL
- 用户输入问题，MapleCode 调用大模型 API，把回复以 SSE 流式逐字打印
- 支持多轮对话，AI 能记住之前说过的话
- 支持 Anthropic Claude 与 OpenAI 两种后端，通过配置文件切换
- 支持 Anthropic 的 Extended Thinking
- Provider 层抽象成统一接口，便于新增后端

**非目标（明确不做）**
- Tool use / function calling
- 文件读写、代码编辑、命令执行
- 会话持久化与恢复（v1 仅内存）
- 多 Provider 热切换（修改配置后重启生效）
- TUI 多面板 / 鼠标交互（v1 单输入框 + 流式输出）

---

## 2. 技术栈

| 维度 | 选型 |
|---|---|
| Java | 21 (LTS) |
| 构建 | Maven（单模块） |
| TUI 输入 | JLine 3 |
| HTTP | JDK `java.net.http.HttpClient` |
| JSON | Jackson (`jackson-databind`) |
| YAML | SnakeYAML |
| 测试 | JUnit 5 |

**依赖列表（pom.xml）**
```xml
<dependency>org.jline:jline-reader:3.27.0</dependency>
<dependency>com.fasterxml.jackson.core:jackson-databind:2.17.2</dependency>
<dependency>org.yaml:snakeyaml:2.3</dependency>
<dependency>org.junit.jupiter:junit-jupiter:5.11.3 (test)</dependency>
```

---

## 3. 项目结构

```
maple-code-java/
├── pom.xml
├── maplecode.yaml.example
├── README.md
└── src/
    ├── main/java/com/maplecode/
    │   ├── App.java
    │   ├── config/
    │   │   ├── AppConfig.java
    │   │   └── ConfigLoader.java
    │   ├── provider/
    │   │   ├── LlmProvider.java
    │   │   ├── ChatRequest.java
    │   │   ├── ChatMessage.java
    │   │   ├── ThinkingConfig.java
    │   │   ├── StreamChunk.java
    │   │   ├── ProviderRegistry.java
    │   │   ├── anthropic/
    │   │   │   ├── AnthropicProvider.java
    │   │   │   ├── AnthropicRequestMapper.java
    │   │   │   └── AnthropicStreamParser.java
    │   │   └── openai/
    │   │       ├── OpenAiProvider.java
    │   │       ├── OpenAiRequestMapper.java
    │   │       └── OpenAiStreamParser.java
    │   ├── http/
    │   │   └── SseStreamReader.java
    │   ├── session/
    │   │   └── ChatSession.java
    │   ├── ui/
    │   │   ├── ReplLoop.java
    │   │   └── StreamPrinter.java
    │   └── error/
    │       ├── MapleCodeException.java
    │       ├── ConfigException.java
    │       └── ProviderException.java
    └── test/java/com/maplecode/...
```

---

## 4. 配置格式

**文件查找顺序**（首个存在即用）：
1. `--config <path>` 命令行参数
2. `./maplecode.yaml`
3. `~/.maplecode/config.yaml`

**示例（maplecode.yaml.example）：**
```yaml
protocol: anthropic            # "anthropic" | "openai"
model: claude-sonnet-4-6
base_url: https://api.anthropic.com
api_key: ${ANTHROPIC_API_KEY} # ${VAR} 占位符，从环境变量读取

system_prompt: |
  You are MapleCode, a helpful coding assistant.
  Be concise.

extended_thinking:             # 可选；不写则不发送 thinking 字段（仅 Anthropic 生效）
  type: adaptive               # "adaptive"（推荐，所有现行模型） | "enabled"（legacy / 在 Opus 4.7 上 400）
  effort: high                 # adaptive 时使用；low | medium | high
  # budget_tokens: 10000       # 仅 type=enabled；≥ 1024 且 < max_tokens；OpenAI 忽略

timeouts:
  connect_seconds: 10
  read_seconds: 60
```

**字段语义：**
- `protocol`（必填）：决定 `ProviderRegistry` 选哪个 Provider
- `model`（必填）：原样透传给 provider，不做白名单校验
- `base_url`（必填）：Anthropic 默认 `https://api.anthropic.com`，OpenAI 默认 `https://api.openai.com/v1`
- `api_key`（必填）：支持 `${VAR}` 占位符；`ConfigLoader` 解析时替换为 `System.getenv("VAR")`
- `system_prompt`（可选）：无则不发送 system 字段
- `extended_thinking.type`（可选）：`adaptive` 或 `enabled`，缺省不发送 thinking；OpenAI 忽略整个块
- `extended_thinking.effort`（仅 `adaptive`）：`low` / `medium` / `high`
- `extended_thinking.budget_tokens`（仅 `enabled`）：≥ 1024 且 < `max_tokens`
- `timeouts.connect_seconds`（可选，默认 10）
- `timeouts.read_seconds`（可选，默认 60）

**配置错误**
- 必填字段缺失 / 类型错误 → `ConfigException`，退出码 78
- `${VAR}` 引用未定义环境变量 → `ConfigException`，退出码 78
- `extended_thinking.type=enabled` 且 `budget_tokens < 1024` → `ConfigException`
- `extended_thinking.type=adaptive` 缺 `effort` 或 `effort` 取值非法 → `ConfigException`
- `extended_thinking.type=enabled` 时同时给了 `effort`，或 `type=adaptive` 时同时给了 `budget_tokens` → `ConfigException`（互斥）

**Deprecated 警告（启动时打印到 stderr，不中断）**
- 使用 `extended_thinking.type=enabled` 时打印 `warning: enabled + budget_tokens is deprecated for Opus 4.6 / Sonnet 4.6 and rejected on Opus 4.7. Prefer type=adaptive + effort.`

---

## 5. Provider 层

### 5.1 接口与 DTO

```java
public interface LlmProvider {
    void stream(ChatRequest request, Consumer<StreamChunk> sink);
}

public record ChatRequest(
    String model,
    String systemPrompt,          // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking       // nullable
) {}

public record ChatMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }
}

public record ThinkingConfig(
    Type type,
    Integer budgetTokens,        // 仅 type=ENABLED；>=1024 且 < max_tokens
    Effort effort                // 仅 type=ADAPTIVE
) {
    public enum Type { ADAPTIVE, ENABLED }
    public enum Effort { LOW, MEDIUM, HIGH }

    /** 启动期校验，失败抛 ConfigException */
    public ThinkingConfig {
        if (type == Type.ENABLED) {
            if (budgetTokens == null || budgetTokens < 1024) {
                throw new ConfigException(
                    "extended_thinking.type=enabled requires budget_tokens >= 1024");
            }
            if (effort != null) {
                throw new ConfigException(
                    "extended_thinking.type=enabled and effort are mutually exclusive");
            }
        }
        if (type == Type.ADAPTIVE) {
            if (effort == null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive requires effort (low|medium|high)");
            }
            if (budgetTokens != null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive and budget_tokens are mutually exclusive");
            }
        }
    }
}

public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR }
}
```

**设计要点：**
- `Consumer<StreamChunk>` 同步推；REPL 用 `switch` 穷举 chunk 类型
- sealed `StreamChunk` 让加新变体时所有 switch 编译失败提醒
- `ChatMessage` 不持久化 thinking 内容；Anthropic 流式 thinking 通过独立 chunk 推到 UI，session 仅保留最终 text（v1 简化）
- `ThinkingConfig` 紧凑构造器在配置加载期就完成校验；两种格式（adaptive + effort / enabled + budget_tokens）二选一，混用立刻抛 ConfigException

### 5.2 ProviderRegistry

```java
public final class ProviderRegistry {
    private final Map<String, Supplier<LlmProvider>> factories;

    public LlmProvider create(String protocol) { ... }
}
```

启动时按 `protocol` 字段 `create()` 出唯一 Provider。

### 5.3 Anthropic Provider

**请求：**
- 端点：POST `{base_url}/v1/messages`
- Headers：
  - `content-type: application/json`
  - `x-api-key: {api_key}`
  - `anthropic-version: 2023-06-01`
- Body（节选）：
  ```json
  {
    "model": "...",
    "system": "...",            // 仅当 systemPrompt 非空
    "messages": [{"role":"user","content":"..."}, ...],
    "max_tokens": 16384,        // v1 硬编码；adaptive 无预算要求，enabled 必须 > budget_tokens
    "stream": true,
    // 分支 A：adaptive（推荐，所有现行模型）
    "thinking": { "type": "adaptive" },
    "output_config": { "effort": "high" },   // low | medium | high
    // 分支 B：enabled（legacy，Opus 4.7 上 400；Opus 4.6 / Sonnet 4.6 接受但 deprecated）
    // "thinking": { "type": "enabled", "budget_tokens": 10000 }
  }
  ```

**Mapper 行为：**
- `ThinkingConfig.Type.ADAPTIVE` → 写入 `thinking: {type: "adaptive"}` + `output_config: {effort: <E>}`；不写 `budget_tokens`
- `ThinkingConfig.Type.ENABLED` → 写入 `thinking: {type: "enabled", budget_tokens: <N>}`；**不**写 `output_config`
- `null` → 不写 `thinking` 也不写 `output_config`
- OpenAI Provider 始终忽略 `thinking`，**不**为它模拟任何 reasoning 字段（v1 简化）

**SSE 事件关注：**
| event | data | 转 StreamChunk |
|---|---|---|
| `message_start` | `{"type":"message_start",...}` | `MessageStart` |
| `content_block_start` | `index=N, content_block.type=thinking` | 标记进入 thinking 段 |
| `content_block_start` | `index=N, content_block.type=text` | 标记进入 text 段 |
| `content_block_delta` | `delta.type=thinking_delta` | `ThinkingDelta` |
| `content_block_delta` | `delta.type=text_delta` | `TextDelta` |
| `content_block_stop` | — | 结束当前段 |
| `message_stop` | — | `MessageEnd(END_TURN)` |
| `error` | `{"type":"error","error":{...}}` | `Error(code, message)` |

### 5.4 OpenAI Provider

**请求：**
- 端点：POST `{base_url}/chat/completions`
- Headers：
  - `content-type: application/json`
  - `authorization: Bearer {api_key}`
- Body（节选）：
  ```json
  {
    "model": "...",
    "messages": [
      {"role":"system","content":"..."},  // 仅当 systemPrompt 非空
      {"role":"user","content":"..."},
      ...
    ],
    "stream": true
  }
  ```

**SSE 事件关注：**
- 每行 `data: {...}`（不含 `event:` 字段）
- `choices[0].delta.content` → `TextDelta`
- `choices[0].finish_reason` 非 null → `MessageEnd`
- `data: [DONE]` → 流结束（不再发 chunk）
- `data: {"error": {...}}`（若 OpenAI 返回 error 对象）→ `Error(code, message)`

**OpenAI 不发送 `thinking` 字段**——`ChatRequest.thinking()` 为 null 时直接忽略；非 null 时记录 warning 日志但不影响请求（v1 简化，不为 OpenAI 模拟 o1 风格 reasoning 字段）。

### 5.5 Extended Thinking API 兼容性矩阵

Anthropic 在 2026 年中期改动了 Extended Thinking 的请求格式。MapleCode v1 支持两种格式，但**只在新格式可用时使用新格式**。

| 模型 | `type=adaptive` + `output_config.effort` | `type=enabled` + `budget_tokens` |
|---|---|---|
| Claude Opus 4.7 | ✅ 唯一可用 | ❌ HTTP 400 |
| Claude Opus 4.6 | ✅ 推荐（官方推荐） | ⚠️ 仍接受，已 deprecated |
| Claude Sonnet 4.6 | ✅ 推荐（官方推荐） | ⚠️ 仍接受，已 deprecated |

**MapleCode v1 行为：**
- 配置 `type=adaptive` → 在所有上述模型上都正常工作；启动时不打印 warning
- 配置 `type=enabled` → 启动期打印一行 stderr warning（不中断）：
  ```
  warning: extended_thinking.type=enabled is deprecated for Opus 4.6 / Sonnet 4.6
           and returns HTTP 400 on Opus 4.7. Prefer:
               type: adaptive
               effort: high
  ```
- 不做运行时模型版本嗅探（model 字段是用户字符串，做前缀匹配不可靠；v1 简化）
- 用户自行决定是否切到 adaptive——如果他就是要在 Sonnet 4.6 上跑 legacy 格式，warning 提醒但不阻断

**官方文档参考：**
- Migration guide: `https://platform.claude.com/docs/en/about-claude/models/migration-guide`
- Effort parameter: `https://platform.claude.com/docs/en/build-with-claude/effort`
- Extended thinking: `https://platform.claude.com/docs/en/build-with-claude/extended-thinking`

**`budget_tokens` 强制下限：1024**（Anthropic API 校验），且必须 < `max_tokens`（v1 硬编码 16384）。`ThinkingConfig` 紧凑构造器在配置加载时校验，违规立刻抛 `ConfigException`，避免请求发出去再被 400 退回。

---

## 6. SSE 流式读取

### 6.1 SseStreamReader

```java
public final class SseStreamReader {
    public void read(
        HttpResponse<Stream<String>> response,
        Consumer<SseEvent> eventSink
    ) { ... }
}

public record SseEvent(String eventType, String data) {}
```

**行为：**
- 输入：`HttpResponse<Stream<String>>`（来自 `BodyHandlers.ofLines()`）
- 按行扫描，维护当前累积事件的 `event` 字段与 `data` 多行缓冲
- 注释行（`: ...`）和心跳（`: heartbeat`）丢弃
- 空行触发 flush：调用一次 `eventSink.accept(SseEvent)`
- 多行 `data:` 用 `\n` 拼接（按 SSE 规范）
- IO 异常 → 包成 `ProviderException` 抛出

### 6.2 调用姿势

```java
HttpRequest req = mapper.toHttpRequest(chatRequest, cfg);
HttpRequest httpReq = HttpRequest.newBuilder()
    .uri(URI.create(cfg.baseUrl() + "/v1/messages"))
    .timeout(Duration.ofSeconds(cfg.timeouts().connectSeconds()))
    .headers(headers)
    .POST(BodyPublishers.ofString(jsonBody))
    .build();

HttpResponse<Stream<String>> resp = httpClient.send(httpReq, BodyHandlers.ofLines());
sseReader.read(resp, ev -> parser.feed(ev, sink));
```

**超时策略：**
- connect 超时由 `HttpRequest.timeout()` 控制（默认 10s）
- read 超时由 `SseStreamReader` 内部在每次 `Stream<String>.iterator().next()` 上叠加（默认 60s 等待下一 chunk；流持续时自动重置）

---

## 7. 会话管理

```java
public final class ChatSession {
    private final List<ChatMessage> messages = new ArrayList<>();

    public void appendUser(String text)      { messages.add(new ChatMessage(USER, text)); }
    public void appendAssistant(String text) { messages.add(new ChatMessage(ASSISTANT, text)); }
    public void clear()                      { messages.clear(); }
    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking) {
        return new ChatRequest(model, systemPrompt, List.copyOf(messages), thinking);
    }
}
```

**生命周期规则：**
- 请求发起前 append user；收到第一个 chunk 前不修改 session
- 流成功结束（收到 `MessageEnd`）→ append assistant 的拼接文本
- 流异常中断 → **不** append assistant 文本，保留 user 消息以便重试
- `/clear` → 清空 messages

---

## 8. TUI / REPL 主循环

### 8.1 输入

- JLine 3 `TerminalBuilder.builder().system(true).build(true)` + `LineReaderBuilder`
- 单行 prompt：`> `
- 多行模式：以 `"""` 开头 → 进入多行，提示符变 `... `，直到单独一行 `"""` 结束
- EOF（Ctrl+D）→ 返回 `null`，REPL 退出
- `UserInterrupt`（Ctrl+C）→ 当前请求中断，session 保留，回到 prompt

### 8.2 REPL 流程

```
1. 打印 banner
2. loop:
   a. 读输入（支持多行）
   b. 若 null → 退出
   c. trim；空 → 继续
   d. 若 == "/exit" → 退出
   e. 若 == "/clear" → session.clear()，打印 "history cleared"
   f. session.appendUser(input)
   g. 构建 ChatRequest
   h. 打印 assistant 起始标记
   i. provider.stream(req, chunk -> switch 渲染)
   j. 流成功完成 → session.appendAssistant(accumulated text)
   k. 流异常 → 打印错误，不修改 session
   l. 打印换行
```

### 8.3 输出渲染

- 普通文本：默认色，stdout
- Thinking 文本：灰色 `\033[90m...\033[0m`，与正常文本视觉区分
- 错误：红色 `✗` 前缀
- Banner：粗体居中（启动时一次）

---

## 9. 错误处理

### 9.1 异常层级

```
RuntimeException
└── MapleCodeException
    ├── ConfigException              # YAML / ENV 错误
    └── ProviderException            # 网络 / HTTP / SSE 错误
        ├── HttpException(int status, String body)
        ├── SseParseException(int line, String raw)
        └── TimeoutException
```

### 9.2 处理策略

| 场景 | 行为 | 退出码 |
|---|---|---|
| 配置缺失 / YAML 解析失败 | 启动期抛 `ConfigException`，打印消息 | 78 |
| `${ENV}` 引用未定义变量 | 启动期抛 `ConfigException` | 78 |
| 未知 protocol | 启动期抛 `ConfigException` | 78 |
| `extended_thinking.type=enabled` 且 `budget_tokens<1024` | 启动期抛 `ConfigException` | 78 |
| `extended_thinking.type=adaptive` 缺 `effort` 或取值非法 | 启动期抛 `ConfigException` | 78 |
| `extended_thinking` 字段互斥（`enabled`+`effort` 或 `adaptive`+`budget_tokens`） | 启动期抛 `ConfigException` | 78 |
| `extended_thinking.type=enabled`（合法但 deprecated） | 启动期 stderr warning，继续 | 0 |
| 用户主动 /exit 或 Ctrl+D | 干净退出 | 0 |
| HTTP 4xx | 打印 provider 返回 body；保留 user；assistant 半截丢弃 | 不退出（继续 REPL） |
| HTTP 5xx / 网络错 | 同上 | 不退出 |
| SSE 解析错误 | 打印行号 + 原始内容，丢弃该 chunk 继续 | 不退出 |
| 流中断（连接断开） | 提示"连接中断，已收到部分内容"，由用户决定 | 不退出 |
| Ctrl+C | 中断当前请求，session 保留，回到 prompt | 不退出 |
| 不可恢复 fatal | 打印错误 | 1 |

### 9.3 关键决策

- 不在请求中途修改 session：失败后用户再发一条，session 里就是 `[user1, (未完成 assistant 已丢弃), user2]`
- `/clear` 是兜底清理手段

---

## 10. 测试策略

### 10.1 单元测试（CI 必跑）

| 测试类 | 覆盖点 |
|---|---|
| `ConfigLoaderTest` | 完整字段解析、缺字段抛 `ConfigException`、`${ENV}` 替换、未设环境变量抛错 |
| `ThinkingConfigTest` | `type=ENABLED` + `budget_tokens<1024` 抛 `ConfigException`；`type=ADAPTIVE` 缺 `effort` 抛；`ENABLED` 同时给 `effort` 抛；`ADAPTIVE` 同时给 `budget_tokens` 抛；正常构造无异常 |
| `SseStreamReaderTest` | 单行 `data:`、多行 `data:`、注释行、心跳、`[DONE]`、空行触发 flush |
| `AnthropicStreamParserTest` | 真实 SSE 样本 → 断言 `StreamChunk` 序列，含 adaptive thinking 场景 |
| `OpenAiStreamParserTest` | 真实 SSE 样本 → 断言序列，正确识别 `data: [DONE]` |
| `AnthropicRequestMapperTest` | 4 个 JSON 快照：(a) 无 thinking (b) `type=adaptive, effort=high` → `output_config.effort` (c) `type=enabled, budget_tokens=10000` (d) 互斥场景构造时已失败，不会到这里 |
| `OpenAiRequestMapperTest` | 同上 + `Authorization: Bearer ...` 头；传 `thinking` 时 mapper 内部忽略（不影响 JSON） |
| `ChatSessionTest` | append / clear / toRequest 顺序，`List.copyOf` 不可变副本 |
| `ProviderRegistryTest` | 按 protocol 选 Provider；未知 protocol 抛 `ConfigException` |
| `ConfigLoaderDeprecationWarningTest` | 加载 `type=enabled` 配置时 stderr 包含 deprecation 提示；`type=adaptive` 不打印 |

### 10.2 集成测试（手工 / 不上 CI）

- 用真 API key 跑一次端到端 smoke test，断言非空 + 流式事件触发
- 标记 `@EnabledIfEnvironmentVariable(named = "MAPLECODE_RUN_IT", matches = "1")`

### 10.3 REPL

- v1 不做 REPL 端到端测试（JLine 在 CI 终端环境坑多），靠手工验收
- 通过 `FakeProvider` 替换 `LlmProvider` 验证 REPL 渲染逻辑（手动测试，不入 Surefire）

### 10.4 Maven 配置

- Surefire 默认跑 `*Test.java`
- `*IT.java` 走 `failsafe` plugin，跑 `mvn verify` 时执行

---

## 11. 验收清单

- [ ] `mvn package` 产出可执行 jar
- [ ] `./maplecode` 或 `java -jar target/maple-code-java-0.1.0.jar` 启动 REPL
- [ ] Anthropic 配置下：单轮对话、多轮对话、extended thinking 三种模式输出符合预期
- [ ] OpenAI 配置下：单轮对话、多轮对话输出符合预期
- [ ] 流式逐字打印（不等到全部完成）
- [ ] `/exit`、`/clear`、`"""` 多行输入均按设计工作
- [ ] 缺字段 / 未设 env 变量时退出码 78 且错误消息友好
- [ ] `extended_thinking.type=adaptive` 在 Opus 4.7 / 4.6 / Sonnet 4.6 上均返回非 400
- [ ] `extended_thinking.type=enabled` 启动时打印 stderr deprecation 警告
- [ ] `budget_tokens < 1024` 启动期就拒绝，不让请求发出去
- [ ] HTTP 4xx / 5xx 不退出 REPL，能继续下一轮
- [ ] Ctrl+C 中断当前请求、session 不丢
- [ ] 切换 provider 只需改 YAML 重启

---

## 12. 后续阶段（不在本规格范围）

- Tool use / function calling
- 文件读写、代码编辑、命令执行
- 会话持久化与 `/resume`
- 多 Provider 热切换 / `/provider` 命令
- 多模态（图片输入）
- 插件系统