# MapleCode 上下文管理设计（v6）

> 日期：2026-07-07
> 阶段：v6（Context Management · 两层压缩）
> 取代：无（首版）
> 前序：v1 流式 REPL · v2 工具系统 · v3 Agent Loop · v4 权限系统 · v5 系统提示词 + MCP 客户端

## 1. 目标与非目标

### 1.1 目标

让 MapleCode 能在有限的 Token 预算下长时间干活，靠两层压缩策略保证对话累积再多也不会因为上下文溢出而瘫掉：

1. **轻量预防（首层）**：工具结果占 Token 大头。每条工具结果超阈就把它写盘，对话里只留 preview + 绝对路径；一条消息里工具结果合计超阈，就挑大的依次写盘。
2. **重量兜底（次层）**：整体对话逼近窗口上限时，调 LLM 生成 5 段结构化摘要，把较早的消息摘掉、近期原文保留（按 token 往回数，约 1 万 token 或至少 5 条，**取多者为准**）。

用户原始消息尽量原文保留，不被摘要改写；摘要 Prompt 明确禁止模型调任何工具；摘要失败连续 3 次熔断（自动压缩停用，`/compress` 仍可重试）；手动 `/compress` 收窄余量；压缩后追加一条边界消息告诉模型"要文件细节请重新读取，别照摘要脑补代码"。

### 1.2 非目标（这一版显式不做）

- 不实现精确 tokenizer；用 `chars / 4` 近似估算，首轮无锚点、之后锚定上次 API 返回的 `usage.inputTokens`
- 不实现摘要策略的机器学习优化；摘要的章节顺序固定，由 Prompt 决定
- 不持久化会话或支持 `/resume`；`/clear` 不动 off-load 文件
- 不在摘要 LLM 调用中打开 prompt caching（每次摘要视为新会话）
- 不实现自动选择 Haiku/Opus；用户可在 `maplecode.yaml` 配 `summarizer_model`，未配回退到主对话 model
- 不重写 `AnthropicRequestMapper` / `OpenAiRequestMapper`；压缩只动 `ChatSession.messages` 的内容、不动序列化层
- 不在摘要 Prompt 里启用 thinking；避免 thinking 把 prompt token 数再加一倍
- 不实现并行压缩（一个会话只有一个 coordinator）；并发安全靠单线程触发

## 2. 架构

### 2.1 装配图

```
App.main
  ├─ (现有 LlmProvider / ToolRegistry / PermissionEngine / AgentLoop 装配不变)
  ├─ ConfigLoader                                  // 扩：解析 context_window / summarizer_model
  ├─ CompressionContext ctx = new CompressionContext(
  │       CompressionConfig.from(appConfig),
  │       new CompressionStorage(home, sessionUuid),
  │       new FailureCounter())
  ├─ CompressionCoordinator coord = new CompressionCoordinator(
  │       ctx, llmProvider, new TokenEstimator(),
  │       new Offloader(storage),
  │       new ConversationSummarizer(llmProvider, appConfig))
  ├─ AgentLoop(..., coord)                         // 扩：构造接 coord；run() 入口调用 beforeRequest
  └─ ReplLoop                                       // 扩：新增 /compress 命令

ChatSession
  └─ replaceAll(List<ChatMessage>)                 // 新增：CompressionCoordinator 提交压缩产物

CompressionCoordinator.beforeRequest(session, trigger, lastUsage)
  ├─ if counter.isTripped():              return SKIPPED_CIRCUIT_OPEN
  ├─ int tokens = estimator.estimate(session.messages(), lastUsage)
  ├─ int threshold = config.window - marginFor(trigger)  // auto: -13K, manual: -3K
  ├─ if tokens < threshold:               return NOOP
  ├─ List<ChatMessage> afterOffload;
  ├─ try:
  │     afterOffload = offloader.apply(session.messages(), config)
  ├─ catch CompressionException e:
  │     log.warn("offload failed, leaving session untouched", e)
  │     return FAILED_OFFLOAD                    // 不计 counter（off-load 不算摘要失败）
  ├─ int tokensAfter = estimator.estimate(afterOffload, lastUsage)
  ├─ if tokensAfter < threshold:
  │     session.replaceAll(afterOffload); return CHANGED_OFFLOAD_ONLY
  ├─ try:
  │     List<ChatMessage> afterSummary = summarizer.apply(afterOffload, config)
  │     counter.recordSuccess()
  │     session.replaceAll(afterSummary); return CHANGED_FULL
  ├─ catch CompressionException e:
  │     counter.recordFailure()
  │     log.warn("summary failed ({} consecutive)", counter.failures(), e)
  │     return FAILED_SUMMARY
```

### 2.2 抽象层级

| 组件 | 职责 | 不做什么 |
|---|---|---|
| `CompressionConfig` | 阈值常量集中（窗口、余量、单条 / 聚合 off-load 阈、recency、preview、熔断）；从 `AppConfig` 派生 | 不持 IO / 状态 |
| `CompressionContext` | 会话级不可变聚合：config + storage + counter | 不持 messages；不调 provider |
| `CompressionStorage` | `~/.maplecode/cache/<session-uuid>/` 下写文件（`Path write(String)`）、清理目录（`close()`）、构造 preview 文本（`String buildPreview(Path, String, int headLines, int tailLines)`） | 不懂 ContentBlock 语义；不持估算器 |
| `TokenEstimator` | `chars / 4` 估算；可锚定 `TokenUsage.inputTokens`（首轮为 null） | 不持 IO / 状态 |
| `FailureCounter` | `AtomicInteger` + `AtomicBoolean tripped`；提供 `recordSuccess / recordFailure / isTripped` | 不写日志（由 Coordinator 写） |
| `Offloader` | 输入 `List<ChatMessage>`，按单条 + 聚合阈值改写 `ToolResultBlock.content` 为 preview；返回新 list；写文件通过注入的 `CompressionStorage` | 不调 LLM；不动 `TextBlock` / `ToolUseBlock`；不动 assistant message |
| `ConversationSummarizer` | 输入 `List<ChatMessage>`，取尾段、调 LLM 出 5 段摘要、拼 [summary USER] + [recency tail] + [boundary USER]；返回新 list | 不动 storage；不重试；摘要失败抛 `CompressionException` |
| `CompressionCoordinator` | 唯一公开入口；编排 estimator → offloader → summarizer；统一错误处理；提交结果到 session；维护 `lastSeenUsage`（每次 MessageEnd 推过来） | 不写 stdout（所有诊断走 stderr） |
| `CompressionException` | 包内异常（流异常、5 段解析失败、refusal 等） | — |
| `CompressionTrigger` | enum：`AUTO` / `MANUAL`，决定 margin | — |
| `CompressionResult` | sealed：`NOOP` / `CHANGED_OFFLOAD_ONLY` / `CHANGED_FULL` / `FAILED_OFFLOAD` / `FAILED_SUMMARY` / `SKIPPED_CIRCUIT_OPEN` | — |

### 2.3 package 布局

```
com.maplecode.compression
├── CompressionConfig.java
├── CompressionContext.java
├── CompressionCoordinator.java
├── ConversationSummarizer.java
├── FailureCounter.java
├── Offloader.java
├── TokenEstimator.java
├── CompressionStorage.java
├── CompressionException.java
├── CompressionTrigger.java            // enum AUTO / MANUAL
└── CompressionResult.java             // sealed 6 变体
```

### 2.4 触发位置

| 调用点 | trigger | margin | 频度 |
|---|---|---|---|
| `AgentLoop.run()` 每个 iteration 开头（`iteration > 0`） | `AUTO` | 13 000 | 每轮模型请求前 |
| `ReplLoop` `/compress` 斜杠命令 | `MANUAL` | 3 000 | 用户显式触发 |
| `CompressionCoordinator.close()` | — | — | 进程退出 hook，删 cache 目录 |

## 3. 数据流

### 3.1 Token 估算

- **锚点**：`TokenEstimator.estimate(List<ChatMessage>, TokenUsage? anchor)`：
  - 若 `anchor != null`，计算 `anchorTokens = anchor.inputTokens + anchor.cacheReadTokens + anchor.cacheCreationTokens`（上次 API 返回的全量输入 token 精确值）
  - 对 `messages` 全量按 `chars / 4` 估（每个 block 的序列化文本长度）：
    - `TextBlock.text` → `text.length()`
    - `ToolUseBlock` → JSON 序列化（id + name + input）的字符数
    - `ToolResultBlock` → `content.length()`（content 已经是 String）
  - 返回 `max(anchorTokens, chars / 4)`（两者都是全量估算，不能相加否则历史消息重复计数）
- **首轮**（无锚点）：直接 `sum(chars) / 4`
- **OpenAI 锚点 caveat**：`OpenAiRequestMapper` 把 cache 字段写 0，所以锚点 `cacheReadTokens == 0` 是常态；不影响估算

### 3.2 首层 Offloader

`Offloader.apply(messages, config)`：

1. 遍历 `messages`，逐条看 `Role.USER + ToolResultBlock`：
   - 对每条 `ToolResultBlock` 单独估算 `tokens(content)`
   - 若 `tokens(content) > config.singleToolResultOffloadTokens`（默认 8 000）→ 标记为 off-load 候选
2. 把同一消息的候选 `tokens` 求和：
   - 若 `sum > config.messageToolResultAggregateTokens`（默认 30 000）→ 把候选按 `tokens` 降序排列，逐个 off-load 直到 `sum <= threshold` 或候选为空
3. 对每个被 off-load 的 `ToolResultBlock`：
   - `Storage.write(content)` → 拿到 `Path savedPath`
   - 用 `Storage.buildPreview(savedPath, content, headLines=8, tailLines=4)` 构造替换文本
   - 新 `ToolResultBlock(content=preview, toolUseId=原值, isError=原值)`
4. 不动 `Role.ASSISTANT` 消息；不动 `TextBlock`；不动 `ToolUseBlock`
5. 返回新 list（原 list 复制后修改）

**Preview 文本格式**：

```
[Offloaded to /Users/foo/.maplecode/cache/session-abc/file-123.txt — 124,503 bytes, 3,210 lines]
--- head ---
<前 8 行原文>
--- tail ---
<后 4 行原文>
[End of preview; re-read from path above for full content]
```

若原文行数 < `headLines + tailLines`，preview 只贴全部原文，不分 head/tail。

### 3.3 次层 ConversationSummarizer

`ConversationSummarizer.apply(messages, config)`：

1. **取 recency tail**（"以多者为准"）：
   - 从 list 末尾向前逐条累积 `tokens`（用 estimator 同样口径）
   - 当 `accum >= config.recencyTokens`（默认 10 000）时停下
   - 若停下时累计条数 `< config.recencyMinMessages`（默认 5），继续往前走到 5 条为止（**即使超 10K**）
   - 切片：`tail = messages.subList(startIdx, messages.size())`
2. **拼 LLM 请求**：
   - `model = appConfig.summarizerModel() != null ? summarizerModel() : appConfig.model()`
   - `systemBlocks = List.of(summarySystemBlock)`，单条 system block 内容见 §3.4
   - `messages = messages.subList(0, startIdx)`（**待摘要部分**，不含 recency tail）
   - 调 `provider.stream(new ChatRequest(model, systemBlocks, messages, thinking=null, tools=empty), sink)`
   - **`ConversationSummarizer` 构造期**接 `LlmProvider` + `AppConfig`，从 AppConfig 拿主 model + summarizer_model；不直接调 AppConfig 其他字段
3. **消费流**：
   - `sink` 收集所有 `TextDelta` 到 `StringBuilder`
   - `MessageEnd` 触发，拿到 `usage`（仅用于日志，不写回）
   - 任何 `Error` chunk → 抛 `CompressionException("llm error chunk")`
4. **校验 5 段结构**：
   - 必须包含 5 个 `^## ` 标题（Intent / Decisions / Open Questions / State / Next Step），大小写不敏感
   - 缺段或段名错 → 抛 `CompressionException("summary missing section: X")`
5. **拼最终 list**：
   - 把摘要文本包装成 `Role.USER + TextBlock("[Conversation summary]\n\n" + summaryText)`，前缀 `[Conversation summary]` 让模型区分这是摘要而非新用户输入
   - 跟 recency tail 拼接
   - 末尾追加 boundary 消息（见 §3.5）
6. 返回新 list；**失败抛 `CompressionException`，由 Coordinator 计数**

### 3.4 Summary system block

写入 `ChatRequest.systemBlocks[0]`，**单段长文**：

```
You are a conversation state compressor. Your job is to summarize a long
agent conversation into structured sections so a future model turn can
continue without re-reading the full transcript.

STRICT RULES:
- DO NOT call any tools. Your output must be pure text only.
- DO NOT invent facts not present in the messages.
- Preserve exact file paths, function names, error messages, and numeric
  values verbatim.
- The user messages and assistant intent are sacrosanct — never paraphrase
  the user's original ask.

PROCESS:
1. First, write a private scratch analysis in <scratchpad>...</scratchpad>
   tags. This section will be DISCARDED before sending the summary to the
   model. Use it to list: what tools were called, what each tool returned,
   what errors occurred, what the user originally asked.
2. After the scratchpad, write the formal summary with EXACTLY these 5
   sections, in this order, each starting with "## ":

   ## Intent
   The user's original goal, in one or two sentences. Quote the user's
   exact wording where possible.

   ## Decisions
   Key choices made during the conversation — files selected, approaches
   tried, trade-offs considered. Cite exact file paths.

   ## Open Questions
   Things the agent asked the user but did not get answered; ambiguities
   still unresolved; assumptions made without confirmation.

   ## State
   Current state of the work — what's done, what's in progress, what's
   broken. Include exact file paths of artifacts created/modified.

   ## Next Step
   The single most important concrete action the agent should take next.
   One sentence.

OUTPUT FORMAT:
- Output MUST start with <scratchpad>...</scratchpad>.
- Output MUST then contain exactly 5 "## " sections in the order above.
- Do not include any prose before the scratchpad or after the last section.
```

Coordinator 收到摘要文本后，**裁掉** `<scratchpad>...</scratchpad>` 段（Java 正则：`Pattern.compile("(?s)<scratchpad>.*?</scratchpad>").matcher(text).replaceAll("")`），再做 5 段校验；裁掉后只剩 5 段正文。

### 3.5 Boundary 消息

`ConversationSummarizer` 在每次摘要产物末尾追加一条 `Role.USER + TextBlock`：

```
[Compression boundary] Above messages are summarized to fit context window.
Tool outputs marked "[Offloaded to ...]" were written to disk; to see exact
code, file contents, or tool output, re-read from those absolute paths
(they are stable for this session). Do NOT guess code or output from the
summary — always re-read.
```

每次压缩都写一条；连续压缩时旧的 boundary 消息会进入"待摘要"区被摘要掉，但 recency tail 内的 boundary 消息会保留可见。

### 3.6 错误处理（按阶段）

| 阶段 | 出错情形 | 上抛形态 | Coordinator 行为 |
|---|---|---|---|
| 估算 | （纯函数，不会抛） | — | — |
| Offload | 磁盘写失败（`IOException`） | `CompressionException("offload write failed")` | 不计 counter（off-load 不算摘要失败）；stderr WARN；返回原 list（保持 session 不动） |
| Summary 流 | HTTP / 超时 / 解析错 | `CompressionException(cause)` | counter.recordFailure；stderr WARN；session 不动 |
| Summary 校验 | 5 段缺失 / 段名错 | `CompressionException("summary missing section: X")` | counter.recordFailure；session 不动 |
| Summary 内容 | model 在 5 段里输出 refusal / "I can't..." | `CompressionException("summary looks like refusal: ...")` | counter.recordFailure；session 不动 |

### 3.7 熔断

- `FailureCounter` 持 `AtomicInteger failures` + `AtomicBoolean tripped`
- `recordFailure()`：`failures++`；`if (failures >= threshold) tripped = true`
- `recordSuccess()`：`failures = 0`（成功后清零，重新累积）
- `isTripped()`：返回 `tripped`
- `reset()`：`failures = 0` + `tripped = false`；由 `/clear` 命令调用，重置会话级状态
- Coordinator 在 `beforeRequest` 开头查 `isTripped()`，若真则：
  - `trigger == AUTO` → 返回 `SKIPPED_CIRCUIT_OPEN`；stderr 一行 `[compression] circuit open (N failures), auto-compress disabled this session`；**session 不动**
  - `trigger == MANUAL` → 仍允许尝试；manual 失败也走同一个 counter
- `threshold = 3`（`CompressionConfig.failureThreshold`）
- `/clear` 调 `coord.resetCounter()` 清空状态（counter 跟 ChatSession 同生命周期，不跟进程生命周期）；`/exit` 进程退出 → counter 跟着 JVM 没

## 4. 配置格式

### 4.1 `maplecode.yaml` 新字段

放在顶层，**与其他字段并列**：

```yaml
# 上下文窗口总 token 数（输入预算，不含 max_tokens）
# 默认 200000，覆盖 Sonnet 4.6 / Opus 4.7 / Haiku 4.5
# GPT-4o 用户请调到 128000
context_window: 200000

# 摘要专用 model（可选）；未配则用主对话 model
# 推荐 claude-haiku-4-5：便宜、快、够用
summarizer_model: claude-haiku-4-5
```

### 4.2 `CompressionConfig` 默认值

```java
public record CompressionConfig(
    int window,                       // default 200_000
    int autoMargin,                   // default 13_000
    int manualMargin,                 // default 3_000
    int singleToolResultOffloadTokens,// default 8_000
    int messageToolResultAggregateTokens,// default 30_000
    int recencyTokens,                // default 10_000
    int recencyMinMessages,           // default 5
    int previewHeadLines,             // default 8
    int previewTailLines,             // default 4
    int failureThreshold              // default 3
) {
    public static CompressionConfig from(AppConfig appConfig) {
        int window = appConfig.contextWindow() > 0
                     ? appConfig.contextWindow() : 200_000;
        return new CompressionConfig(
            window,
            /* ... 其他用上述默认值 ... */
        );
    }
}
```

`AppConfig.contextWindow()` 若 YAML 未配则返回 0（让 CompressionConfig 兜底）。`summarizer_model` 同理。

### 4.3 Cache 目录生命周期

- 启动时生成 session UUID：`UUID.randomUUID()`，挂到 `CompressionContext`
- 路径：`~/.maplecode/cache/session-<uuid>/`
- 文件名：`<uuid>-<seq>.txt`，seq 是 `AtomicLong` 单调递增
- `CompressionCoordinator.close()`：删除整个 session 目录；调用于 `App.main` 注册的 shutdown hook（紧跟 MCP 客户端关闭之后）
- `/clear` / `/exit`：见 §5.4
- 不同进程启动 UUID 不同 → 文件互不冲突；旧 session 文件遗留等手动清理或被 OS 处理

## 5. App.main / AgentLoop / ReplLoop 接入

### 5.1 App.main 改动

```java
// 1. 解析新字段（ConfigLoader 改）
AppConfig appConfig = ConfigLoader.load(path);

// 2. 装配 compression 三件套
CompressionConfig compressionCfg = CompressionConfig.from(appConfig);
CompressionStorage storage = new CompressionStorage(
    Paths.get(System.getProperty("user.home"), ".maplecode", "cache",
              "session-" + UUID.randomUUID()));
CompressionContext ctx = new CompressionContext(compressionCfg, storage, new FailureCounter());

// 3. Coordinator（需要 provider，调 LLM 出摘要时复用）
CompressionCoordinator coord = new CompressionCoordinator(
    ctx, llmProvider, new TokenEstimator(),
    new Offloader(storage),
    new ConversationSummarizer(llmProvider, appConfig));

// 4. AgentLoop 接 coord
AgentLoop agentLoop = new AgentLoop(
    session, registry, executor, printer, agentConfig, coord);

// 5. ReplLoop 接 coord（用于 /compress）
ReplLoop repl = new ReplLoop(agentLoop, printer, coord);

// 6. shutdown hook（紧跟 MCP 关闭之后）
Runtime.getRuntime().addShutdownHook(new Thread(coord::close, "compression-shutdown"));
```

### 5.2 AgentLoop 改动

`run(userInput, sink)` 入口在 `iteration == 0` 后（即第一轮 user 输入已经 append 完），加：

```java
public void run(String userInput, Consumer<AgentEvent> sink) {
    session.appendUserText(userInput);

    int iteration = 0;
    while (iteration < config.maxIterations()) {
        if (iteration > 0) {  // 第一轮不压缩（还没东西可压）
            var result = coord.beforeRequest(session, CompressionTrigger.AUTO,
                                             coord.lastSeenUsage());
            if (result == CompressionResult.CHANGED_OFFLOAD_ONLY
                || result == CompressionResult.CHANGED_FULL) {
                sink.accept(new AgentEvent.CompressionApplied(result));
            }
            // SKIPPED_CIRCUIT_OPEN / FAILED_* / NOOP 都静默继续
        }
        // ... 既有 LLM 调用 + tool 执行逻辑 ...
    }
}
```

**Usage 注入路径**：AgentLoop 在每次收到 `StreamChunk.MessageEnd(usage)` 时调 `coord.recordUsage(usage)`（既有 `usageSink` 流加一路 Consumer；或直接改 `usageSink` 接受 list）。这样 Coordinator 维护的 `lastSeenUsage` 自动保持新鲜，不需要 AgentLoop 自己存字段。

### 5.3 ChatSession 改动

新增 `replaceAll(List<ChatMessage>)`：

```java
public void replaceAll(List<ChatMessage> messages) {
    this.messages = List.copyOf(messages);  // 不可变拷贝
}
```

`appendUser` / `appendAssistant` 不变；只是多了一个"批量替换"出口供 Coordinator 调用。

### 5.4 ReplLoop 改动

新增 `/compress` 命令（紧挨 `/clear`）：

```java
case "/compress":
    var r = coord.beforeRequest(session, MANUAL, coord.lastSeenUsage());
    printer.compressionResult(r);  // 打印 "压缩完成，offload N 处 / 摘要 M token" 等
    continue;

case "/clear":
    agent.session().clear();
    coord.resetCounter();            // counter 与 ChatSession 同生命周期；cache 文件不删
    continue;
```

- `/compress` 的 `lastSeenUsage` 从 `coord.lastSeenUsage()` 拿（Coordinator 维护，不从 ReplLoop 维护）
- `printer.compressionResult(r)` 是 `StreamPrinter` 新增方法（既有 printer 是 `Consumer<AgentEvent>`，新方法独立签名）
- `/clear` 不调 `coord.close()`；`/exit` / Ctrl+D 走正常退出，shutdown hook 负责清理 cache 目录

### 5.5 AgentEvent 新增

```java
sealed interface AgentEvent {
    // ... 既有 11 变体 ...
    record CompressionApplied(CompressionResult result) implements AgentEvent {}
}
```

`StreamPrinter` 处理 `CompressionApplied`：stderr 一行 `[compression] applied: <result>`，不写 stdout。

### 5.6 Provider 复用

摘要 LLM 调用完全走现有 `LlmProvider.stream(...)`：

```java
var req = new ChatRequest(
    model,
    List.of(summarySystemBlock),  // 单条 system
    messagesToSummarize,          // 待摘要
    null,                         // thinking 禁用
    List.of()                     // 无工具
);
StringBuilder acc = new StringBuilder();
provider.stream(req, chunk -> {
    if (chunk instanceof StreamChunk.TextDelta td) acc.append(td.text());
    else if (chunk instanceof StreamChunk.Error e) throw new CompressionException(e.message());
});
String summary = acc.toString();
```

Anthropic / OpenAI mapper 不动；自动按 `Role.USER` / `Role.ASSISTANT` 序列化（待摘要的 USER 含 ToolResultBlock 也照常编码）。

## 6. 测试

### 6.1 单元测试矩阵

| 测试类 | 必须覆盖 |
|---|---|
| `TokenEstimatorTest` | (a) 空 list → 0；(b) 单 TextBlock 1000 char → 250 tokens ± 0；(c) 锚点与 chars/4 取大：anchor=1000, chars=800 → 1000；(d) 含 cache 锚点：anchor=(500,100,200,300)=1000, chars=400 → 1000；(e) ToolResultBlock 100KB content → 25000 tokens（与 chars/4 一致） |
| `OffloaderTest` | (a) 单条 ToolResultBlock 9K char → 触发 off-load，文件存在，message list 中 content 被替换为 preview（含绝对路径 + head + tail）；(b) 一条消息内 4 个 ToolResultBlock 合计超阈，按 tokens 降序 off-load 至 sum ≤ 阈；(c) preview 短内容（< 12 行）不分 head/tail；(d) assistant message / TextBlock / ToolUseBlock 不动；(e) 磁盘写失败 → `CompressionException`、message list 不变 |
| `ConversationSummarizerTest` | (a) mock LLM 返 5 段 + scratchpad → 输出 list = [summary USER] + recency tail + [boundary USER]；(b) scratchpad 被裁；(c) recency 不足 5 条时强制 5 条；(d) recency 超 10K token 时按 token 截到不超过 10K 条数 ≥ 5；(e) 缺段 → 抛；(f) refusal → 抛；(g) Error chunk → 抛；(h) boundary 消息含 "re-read" / "offloaded" 字样 |
| `FailureCounterTest` | (a) 3 次失败后 isTripped；(b) recordSuccess 清零；(c) 并发线程跑 recordFailure 100 次只到 100，isTripped 时机正确 |
| `CompressionStorageTest` | (a) 写文件后 read 回一致；(b) 文件名含 UUID + seq；(c) buildPreview 短内容不切 head/tail，长内容分 head/tail + 元信息；(d) close() 删目录 |
| `CompressionCoordinatorTest` | (a) tokens < threshold → NOOP；(b) tokens 跨阈 + offload 足够 → CHANGED_OFFLOAD_ONLY；(c) offload 不够 → 调 summarizer → CHANGED_FULL；(d) summarizer 抛 → FAILED，counter+1；(e) counter tripped → SKIPPED_CIRCUIT_OPEN；(f) session.replaceAll 被调，参数是新 list |
| `CompressionConfigTest` | (a) AppConfig.contextWindow 未配 → 走默认 200K；(b) 显式配 128000 → 透传 |
| `ChatSessionReplaceAllTest` | (a) replaceAll 后 messages 引用替换；(b) 旧 append 仍有效；(c) 不可变拷贝防外部修改 |

### 6.2 手工 smoke（不写 IT 集成测试）

启动 REPL，按以下步骤验证：

1. **首层 off-load**：
   - 让 model 跑 5 次 `read_file` 一个 50KB 的文件
   - `/tools` 不变；REPL 无可见输出
   - 退出后 `ls ~/.maplecode/cache/session-<uuid>/` → 应有 1+ 个 `.txt` 文件
   - 下次启动 model 在 tool result 里看到 `[Offloaded to ...]` 提示

2. **次层摘要**：
   - 把 `maplecode.yaml` 的 `context_window` 临时调到 30000（模拟小窗口）
   - 让 model 跑多个工具攒 token
   - 观察 stderr 出现 `[compression] applied: CHANGED_FULL`
   - 紧接着一轮 model 仍能继续对话（验证 recency tail 保留上下文）

3. **熔断**：
   - 用 mock provider 注入 3 次失败（改坏 YAML 让 provider 报错）
   - 第四次请求前 stderr 出现 `[compression] circuit open`
   - 后续自动压缩停用；`/compress` 仍可重试

4. **/compress**：
   - 长对话后输 `/compress`
   - 立即出现 `applied: CHANGED_FULL`（manualMargin=3K 触发更激进）

5. **shutdown**：
   - 跑完 smoke 后 Ctrl+D 退出
   - `ls ~/.maplecode/cache/session-<uuid>/` → 目录已删（验证 close hook 跑通）

### 6.3 退出码

- 配置错误（`CompressionConfig.from` 校验失败）→ 78（沿用 sysexits.h `EX_CONFIG`）
- 压缩运行期错误 → 不退出，仅 stderr WARN，主循环继续
- 熔断 → 不退出，仅 stderr 一行

## 7. 接受标准

实现完成必须同时满足：

1. `mvn test` 全绿，新加测试 ≥ 30 个（覆盖上述 8 个测试类矩阵）
2. `mvn package` 生成可执行的 shaded jar；启动无新警告
3. 手工 smoke 5 步全跑通
4. `maplecode.yaml.example` 新增 `context_window` + `summarizer_model` 两字段，注释解释用途和默认值
5. `AnthropicRequestMapper` / `OpenAiRequestMapper` / `AgentLoop.runOneTurn` / `ToolExecutor.run` / `Tool` 接口 / `ContentBlock` sealed 形态 **均不变**（保证向后兼容 MCP、permission、tool 三个已实现的包）
6. `ChatSession.appendUser / appendAssistant / clear / toRequest` 既有方法签名不变；仅新增 `replaceAll(List<ChatMessage>)`
7. `AgentEvent` 新增 `CompressionApplied` 变体；既有 11 变体不变；其他 `switch (event)` 处编译器报错时由编译器强制补全
8. 所有 compression 包内日志走 stderr，前缀 `[compression]`；**绝不写 stdout**（与 MCP 客户端约定一致）
9. 压缩产物在 `/clear` / `/exit` 后行为正确：`/clear` 不删 cache 文件，`/exit` 通过 shutdown hook 删目录
10. Recency tail 边界严格遵守 §3.3 规则：5 条是刚性下限、10K token 是柔性上限