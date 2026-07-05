# MapleCode — System Prompt 结构化与缓存（阶段四）

**日期**：2026-07-05
**范围**：把 `ConfigLoader` 里那段三行默认 `systemPrompt` 升级为按职责拆分的模块化结构。让稳定内容走 Anthropic prompt cache 通道省 token，动态内容（环境、运行中 reminder）走消息通道。引入 `<system-reminder>` 包装的用户消息作为运行时补充指令通道，按 N=5 节流注入 Plan Mode 上下文。
**不在范围**：项目指令文件加载（CLAUDE.md / AGENTS.md）、自动长期记忆、真实 MCP 集成、自动化 LLM 评估 —— 都是后续阶段。本步只做结构化 + 缓存 + reminder。

---

## 1. 目标与非目标

**目标**
- 系统提示按 7 个固定模块 + 可选模块按优先级拼装（身份 / 系统约束 / 任务模式 / 动作执行 / 工具使用 / 语气风格 / 文本输出 / 环境信息；末尾是自定义指令 / 已激活 Skill / 长期记忆 占位）。
- Anthropic 走 prompt cache：稳定模块合并后用一个 `cache_control: ephemeral` 标记。OpenAI 路径同样结构化但不强写 cache 控制（依靠服务端自动缓存）。
- `AnthropicStreamParser` 解析 `cache_creation_input_tokens` 与 `cache_read_input_tokens`，`StreamPrinter` 默认展示缓存命中情况。
- 运行中补充指令（如 Plan Mode reminder）通过 `<system-reminder>...</system-reminder>` 包裹的临时 user 消息注入，**不进 session 历史**，**不污染缓存**，模型识别 tag 而不当成用户输入来回复。
- Plan Mode reminder 按 N=5 节流：第 1 轮完整、之后每 5 轮重复完整、其余轮次插一句精简版。
- ChatRequest 接口统一为结构化（`List<SystemBlock>`），新旧 mapper 共存一个版本周期后清理。
- 验证策略覆盖 cache 命中字段自动解析 + 6 个典型场景的定性人工对比。

**非目标（明确不做）**
- 项目指令文件加载（CLAUDE.md / AGENTS.md / README） —— v5
- 自动长期 / 短期记忆 —— v5
- 真实 MCP 接入 —— v5
- Skill 真实注入（接口留 placeholder） —— v5
- 自动化 LLM 评估脚本 —— v5
- 多模态输入、会话持久化与 `/resume` —— 已在前几期明确剔除
- Anthropic prompt cache 多断点（系统 + tools 都显式写）—— 留给 v5 做性能优化

---

## 2. 新增 / 修改的抽象

### 2.1 `SystemBlock`（新增，`com.maplecode.prompt`）

```java
public record SystemBlock(String content, boolean cacheBoundary, String kind) {}
```

- `content`：渲染后的纯文本
- `cacheBoundary`：标记该 block 是否带 cache_control；`PromptAssembler` 走"最后一个 cacheable section 上写 true"的策略，因此一个 assembled list 最多有一个 `cacheBoundary=true`
- `kind`：section 名字（"identity" / "constraints" / ...），便于调试

`Map<String, SystemBlock>` 在两个 mapper 里等价于 `List<SystemBlock>`，但为了 mapper 输出顺序明确，使用 list。

### 2.2 `SectionContext`（新增）

```java
public record SectionContext(
    List<Tool> tools,
    DynamicContext env,
    PlanMode planMode
) {}
```

传给 `PromptSection.render(SectionContext)`，让 sections 渲染时能引用工具列表、当前 env、规划模式。

### 2.3 `DynamicContext`（新增）

```java
public record DynamicContext(
    Path cwd,
    boolean isGitRepo,
    String platform,        // 例 "darwin (arm64)"
    String javaVersion,     // 例 "21.0.5"
    String mavenVersion,    // 例 "3.9.6"
    LocalDate date,
    LocalTime time
) {
    public static DynamicContext capture(Path cwd) {
        boolean git = Files.exists(cwd.resolve(".git"));
        String os = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
        String java = System.getProperty("java.version");
        String maven = detectMavenVersion();      // 见下文
        return new DynamicContext(cwd, git, os, java, maven,
            LocalDate.now(), LocalTime.now().withNano(0));
    }

    /** 启动期跑 `mvn -v` 抓版本号；不抛错，失败时返 "unknown"。 */
    static String detectMavenVersion() { /* ... */ }
}
```

`DynamicContext.capture` 在 `App.main` 启动时跑一次（开销最多 100ms）。本字段全部 cacheable=false，因为每次 REPL 启动重新构建。

### 2.4 `PromptSection`（新增接口）

```java
public interface PromptSection {
    String kind();
    String render(SectionContext ctx);
    default boolean cacheable() { return true; }
    default boolean enabled(SectionContext ctx) { return true; }
}
```

- `enabled` 给可选模块（CustomInstructionSection）和计划模式模块留口
- `cacheable=false` 仅 `EnvironmentSection` 使用

### 2.5 `DefaultSections`（新增常量 registry）

```java
public final class DefaultSections {
    private DefaultSections() {}

    // 固定 7 段，按优先级排序
    public static final PromptSection IDENTITY           = new IdentitySection();
    public static final PromptSection SYSTEM_CONSTRAINTS = new SystemConstraintsSection();
    public static final PromptSection TASK_MODE          = new TaskModeSection();
    public static final PromptSection ACTION_EXECUTION   = new ActionExecutionSection();
    public static final PromptSection TOOL_USAGE         = new ToolUsageSection();
    public static final PromptSection TONE_STYLE         = new ToneStyleSection();
    public static final PromptSection TEXT_OUTPUT        = new TextOutputSection();
    public static final PromptSection ENVIRONMENT        = new EnvironmentSection();

    /** 装配顺序：固定段 → 可选段（用户覆盖 / Skill / 长期记忆）。 */
    public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                               PlanMode planMode, String customInstruction) {
        List<PromptSection> list = new ArrayList<>(List.of(
            IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
            TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT, ENVIRONMENT));
        if (customInstruction != null && !customInstruction.isBlank()) {
            list.add(new CustomInstructionSection(customInstruction));
        }
        // ActivatedSkillsSection 暂返空内容；接口保留
        list.add(new ActivatedSkillsSection());
        // LongTermMemorySection 暂返空内容；接口保留
        list.add(new LongTermMemorySection());
        return list;
    }
}
```

7 个固定 section 各为 `final class ...Section implements PromptSection`，render 直接 `return "..."`。`EnvironmentSection.cacheable()` 默认 `false`（override）。

### 2.6 `PromptAssembler`（新增）

```java
public final class PromptAssembler {

    /** sections 按入参顺序拼接；标定最后一个 cacheable section 的 cacheBoundary=true。 */
    public List<SystemBlock> assemble(List<PromptSection> sections, SectionContext ctx) {
        List<SystemBlock> blocks = new ArrayList<>();
        int lastCacheableIdx = -1;
        for (PromptSection s : sections) {
            if (!s.enabled(ctx)) continue;
            String text = s.render(ctx);
            if (text == null || text.isBlank()) continue;
            blocks.add(new SystemBlock(text, false, s.kind()));
            if (s.cacheable()) lastCacheableIdx = blocks.size() - 1;
        }
        if (lastCacheableIdx >= 0) {
            SystemBlock tail = blocks.get(lastCacheableIdx);
            blocks.set(lastCacheableIdx,
                new SystemBlock(tail.content(), true, tail.kind()));
        }
        return blocks;
    }

    /**
     * 把本轮的 system-reminder（若有）追加到 messages 末尾。
     * 返回一个新的 ChatRequest，原消息列表不动；新 reminder 不存回 ChatSession。
     */
    public ChatRequest attachReminder(ChatRequest req, String reminderBody) {
        if (reminderBody == null || reminderBody.isBlank()) return req;
        String wrapped = ReminderMessage.wrap(reminderBody);
        List<ChatMessage> newMsgs = new ArrayList<>(req.messages());
        newMsgs.add(new ChatMessage(ChatMessage.Role.USER,
            List.of(new ContentBlock.TextBlock(wrapped))));
        return new ChatRequest(req.model(), req.systemBlocks(), newMsgs,
            req.thinking(), req.tools(), req.transientReminder());  // transientReminder 不变
    }
}
```

### 2.7 `ReminderMessage`（新增）

```java
public final class ReminderMessage {
    public static final String TAG_OPEN = "<system-reminder>";
    public static final String TAG_CLOSE = "</system-reminder>";

    public static String wrap(String body) {
        return TAG_OPEN + "\n" + body + "\n" + TAG_CLOSE;
    }
}
```

### 2.8 `PlanModeReminder`（新增）

```java
public final class PlanModeReminder {
    public static final int REPEAT_INTERVAL = 5;

    public enum Form { FULL, BRIEF, NONE }

    public record State(int fullInserts, int lastFullIteration) {
        public static State initial() { return new State(0, 0); }
        public State afterFull(int iter) { return new State(fullInserts + 1, iter); }
    }

    /** 决定本轮 reminder 形态。NORMAL 模式永远 NONE。 */
    public static Form decide(PlanMode mode, State state, int iteration) {
        if (mode != PlanMode.PLAN) return Form.NONE;
        if (state.fullInserts() == 0) return Form.FULL;
        if (iteration - state.lastFullIteration >= REPEAT_INTERVAL) return Form.FULL;
        return Form.BRIEF;
    }

    public static String renderFull() {
        return "规划模式已开启。禁止调用 write_file / edit_file / exec。\n"
             + "仅可使用 read_file / glob / grep。输出一份可执行计划，列出每个步骤及对应工具调用，完成后停止。";
    }

    public static String renderBrief() {
        return "规划模式仍处于激活状态，仅可调用只读工具。";
    }
}
```

### 2.9 `ChatRequest` 改造（`com.maplecode.provider`）

```java
public record ChatRequest(
    String model,
    List<SystemBlock> systemBlocks,        // 替代 systemPrompt: String
    List<ChatMessage> messages,
    ThinkingConfig thinking,
    List<Tool> tools,
    List<ContentBlock> transientReminder   // 新增；同上 reserved，与 messages 并列
) {
    /** 向后兼容重载：transientReminder 默认为空。 */
    public ChatRequest(String model, List<SystemBlock> systemBlocks,
                       List<ChatMessage> messages, ThinkingConfig thinking,
                       List<Tool> tools) {
        this(model, systemBlocks, messages, thinking, tools, List.of());
    }
}
```

### 2.10 `AgentConfig` 改造（`com.maplecode.agent`）

```java
public record AgentConfig(
    String model,
    List<SystemBlock> systemBlocks,        // 替代 systemPrompt: String
    ThinkingConfig thinking,
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode,
    PlanModeReminder.State reminderState   // 新增；初始值 State.initial()
) {
    public AgentConfig {
        if (maxIterations < 1) throw new ConfigException("maxIterations must be >= 1");
        if (maxConsecutiveUnknown < 1) throw new ConfigException("maxConsecutiveUnknown must be >= 1");
    }

    public static AgentConfig defaults() {
        return new AgentConfig("test-model", List.of(), null, 25, 3,
            PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    public static AgentConfig fromAppConfig(AppConfig app) {
        return new AgentConfig(app.model(), app.systemBlocks(), app.thinking(),
            25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    /** record 显式附带方法：返回 reminderState 被替换的副本，其余字段不变。 */
    public AgentConfig withReminderState(PlanModeReminder.State state) {
        return new AgentConfig(model, systemBlocks, thinking,
            maxIterations, maxConsecutiveUnknown, planMode, state);
    }
}
```

> **关键改动**：`/plan`、`/do`、`/cancel` 三处构造 `new AgentConfig(...)` 的代码必须保留 `reminderState` 字段（`ReplLoop` 修改点）。

### 2.11 `AppConfig` 改造（`com.maplecode.config`）

```java
public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String yamlPrompt,               // 来自 YAML `system_prompt`；nullable。ConfigLoader 阶段填充
    List<SystemBlock> systemBlocks,  // 默认 List.of()；App.main 启动时组装后写入
    ThinkingConfig thinking,
    AppConfig.Timeouts timeouts
) {}
```

**装配流程**：
1. `ConfigLoader.load()` 只读 `system_prompt` 为 String 填进 `yamlPrompt`；`systemBlocks` 留空 list
2. `App.main` 启动后：`DynamicContext.capture(cwd)` + 已注册的 6 个工具 + `yamlPrompt` → 调 `DefaultSections.standard(...)` + `PromptAssembler.assemble(...)` → 写入新建的 `AppConfig`（或直接构 `AgentConfig` 时覆盖）

**为何这样切分**：ConfigLoader 不依赖 `prompt` 包、工具注册表或文件 IO（cwd）；App.main 启动后才有 cwd、才注册工具。这样 `ConfigLoader` 测试可以不 mock 这两块。

> ConfigLoader 里的 `DEFAULT_SYSTEM_PROMPT` 三行常量移除 —— "默认"行为全部交给 `DefaultSections` 的 `IdentitySection` 等。

### 2.12 `ConfigLoader` 改造（`com.maplecode.config`）

```java
public final class ConfigLoader {
    // DEFAULT_SYSTEM_PROMPT 常量移除（不再用）；保留 yamlPrompt 字段
    // load() 流程不变，仅 systemPrompt 字段含义改为「用户覆盖文本，可能为 null」
}
```

`App.main` 启动后构造：

```java
// App.java
DynamicContext env = DynamicContext.capture(Paths.get(System.getProperty("user.dir")));
List<Tool> tools = List.of(new ReadFileTool(), ...);  // 现有 6 个
String yamlPrompt = config.yamlPrompt();              // nullable
List<PromptSection> sections = DefaultSections.standard(
    env, tools, PlanMode.NORMAL, yamlPrompt);
SectionContext ctx = new SectionContext(tools, env, PlanMode.NORMAL);
List<SystemBlock> blocks = new PromptAssembler().assemble(sections, ctx);

AgentConfig agentConfig = new AgentConfig(
    config.model(), blocks, config.thinking(),
    25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());
```

`AppConfig.systemBlocks` 字段保留以兼容其他模块的依赖（`AgentConfig.fromAppConfig` 直接读）；运行时 `App.main` 会重新构造 `AgentConfig`。

---

## 3. Provider 改动 —— 两个 mapper 与 cache 字段

### 3.1 `AnthropicRequestMapper`（`com.maplecode.provider.anthropic`）

```java
public final class AnthropicRequestMapper {

    public String toJsonBody(ChatRequest req) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", req.model());
        root.put("max_tokens", MAX_TOKENS);
        root.put("stream", true);

        // system 改为 multi-block array
        if (req.systemBlocks() != null && !req.systemBlocks().isEmpty()) {
            ArrayNode sysArr = root.putArray("system");
            for (SystemBlock b : req.systemBlocks()) {
                ObjectNode bn = sysArr.addObject();
                bn.put("type", "text");
                bn.put("text", b.content());
                if (b.cacheBoundary()) {
                    ObjectNode cc = bn.putObject("cache_control");
                    cc.put("type", "ephemeral");
                }
            }
        }

        // messages
        ArrayNode msgs = root.putArray("messages");
        for (var m : req.messages()) msgs.add(encodeMessage(m));

        // thinking（不变）
        if (req.thinking() != null) { /* ... */ }

        // tools（不变）
        if (req.tools() != null && !req.tools().isEmpty()) { /* ... */ }

        return JSON.writeValueAsString(root);
    }
}
```

> tools 数组**不**额外写 cache_control（v4 单点策略；v5 性能优化再加）

### 3.2 `OpenAiRequestMapper`（`com.maplecode.provider.openai`）

```java
public String toJsonBody(ChatRequest req) {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", req.model());
    root.put("stream", true);
    root.putObject("stream_options").put("include_usage", true);

    // systemBlocks 拼接为单条 system message；cacheBoundary 忽略
    ArrayNode msgs = root.putArray("messages");
    if (req.systemBlocks() != null && !req.systemBlocks().isEmpty()) {
        String joined = req.systemBlocks().stream()
            .map(SystemBlock::content)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining("\n\n"));
        if (!joined.isBlank()) {
            msgs.add(JSON.createObjectNode()
                .put("role", "system")
                .put("content", joined));
        }
    }
    for (var m : req.messages()) {
        ObjectNode om = encodeMessage(m);
        if (om != null) msgs.add(om);
    }
    // tools / thinking 同前
    return JSON.writeValueAsString(root);
}
```

### 3.3 `TokenUsage` 扩展（`com.maplecode.provider`）

```java
public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int cacheCreationTokens,    // 默认 0；Anthropic 才有
    int cacheReadTokens         // 默认 0；Anthropic 才有
) {
    /** 向后兼容：旧两参构造。 */
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, 0, 0);
    }
}
```

OpenAI mapper 不读 cache 字段，传 0/0 即可。

### 3.4 `AnthropicStreamParser`（`com.maplecode.provider.anthropic`）

```java
private int lastInputTokens = 0;
private int lastOutputTokens = 0;
private int cacheCreation = 0;
private int cacheRead = 0;

public void reset() {
    // ... 已有 6 个字段清空，新增：
    cacheCreation = 0;
    cacheRead = 0;
}

if (type.equals("message_start")) {
    JsonNode usage = node.path("message").path("usage");
    lastInputTokens = usage.path("input_tokens").asInt(0);
    cacheCreation   = usage.path("cache_creation_input_tokens").asInt(0);
    cacheRead       = usage.path("cache_read_input_tokens").asInt(0);
    sink.accept(new StreamChunk.MessageStart());
    return;
}

if (type.equals("message_delta")) {
    JsonNode usage = node.path("usage");
    int outputTokens = usage.path("output_tokens").asInt(0);
    if (outputTokens > 0) lastOutputTokens = outputTokens;
    // cache_creation / cache_read 不在 message_delta 刷新（Anthropic streaming 行为）
    return;
}

if (type.equals("message_stop")) {
    TokenUsage usage = (lastInputTokens == 0 && lastOutputTokens == 0
        && cacheCreation == 0 && cacheRead == 0)
        ? null
        : new TokenUsage(lastInputTokens, lastOutputTokens, cacheCreation, cacheRead);
    sink.accept(new StreamChunk.MessageEnd(mapStopReason(lastStopReason), usage));
    return;
}
```

### 3.5 `OpenAiStreamParser` 改造

OpenAI 不发 cache 字段。当前 `MessageEnd` 构造点改为 `TokenUsage.of(prompt, completion)`。

### 3.6 `StreamPrinter` 增强 + 触发点

`StreamPrinter` 加 `usage(TokenUsage)`：

```java
public void usage(TokenUsage u) {
    if (u == null) return;
    StringBuilder sb = new StringBuilder("[usage: input=").append(u.inputTokens())
        .append(" out=").append(u.outputTokens());
    if (u.cacheCreationTokens() > 0)
        sb.append(" cache_create=").append(u.cacheCreationTokens());
    if (u.cacheReadTokens() > 0)
        sb.append(" cache_read=").append(u.cacheReadTokens());
    sb.append("]");
    info(sb.toString());
}
```

**触发点**：`ResponseCollector` 在收 `MessageEnd` 时，如果 `usage != null`，推给一个外部传入的 `Consumer<TokenUsage> usageSink`。`ResponseCollector` 构造增加一个 `usageSink` 参数；`null` 表示不打印（保留旧测试路径）。`AgentLoop` 在循环顶部把 `printer::usage` 当 `usageSink` 传进去。

```java
// ResponseCollector.java —— 改造后签名
final class ResponseCollector implements Consumer<StreamChunk> {
    private final Consumer<AgentEvent> sink;
    private final ToolRegistry registry;
    private final Consumer<TokenUsage> usageSink;   // nullable

    ResponseCollector(Consumer<AgentEvent> sink, ToolRegistry registry,
                      Consumer<TokenUsage> usageSink) {
        this.sink = sink;
        this.registry = registry;
        this.usageSink = usageSink;
    }

    @Override public void accept(StreamChunk chunk) { /* 不变 */ }

    /** 与原 accept 一致，但 MessageEnd 分支加一句。 */
    case MessageEnd e -> {
        stopReason = e.reason();
        usage = e.usage();
        if (usageSink != null && usage != null) usageSink.accept(usage);
    }
}
```

不新增 `AgentEvent.Usage` 类型，避免扩大 sealed 接口。`IterationEnd.usage` 字段（已存在）继续作为程序内数据流；UI 展示走 `printer.usage()`。

---

## 4. ChatSession 与 AgentLoop 改动

### 4.1 `ChatSession.toRequest` 改

```java
public ChatRequest toRequest(String model, List<SystemBlock> systemBlocks,
                             ThinkingConfig thinking, List<Tool> tools) {
    return new ChatRequest(model, systemBlocks,
        Collections.unmodifiableList(new ArrayList<>(messages)),
        thinking, tools);
}
```

### 4.2 `AgentLoop.run` 改造

```java
public void run(String userInput, Consumer<AgentEvent> sink) {
    session.appendUserText(userInput);
    int iteration = 0;
    // ... existing state

    while (iteration < config.maxIterations()) {
        if (cancelled) { /* ... */ break; }

        sink.accept(new AgentEvent.IterationStart(iteration));
        ResponseCollector col = new ResponseCollector(sink, registry,
            usageSink != null ? usageSink : null);   // 见 §3.6

        // PLAN 模式: 只读工具
        List<Tool> tools = (config.planMode() == PlanMode.PLAN)
            ? registry.readOnly()
            : registry.all();

        ChatRequest req = session.toRequest(
            config.model(), config.systemBlocks(), config.thinking(), tools);

        // system-reminder 节流注入（不持久）
        PlanModeReminder.Form form = PlanModeReminder.decide(
            config.planMode(), config.reminderState(), iteration);
        if (form != PlanModeReminder.Form.NONE) {
            String body = (form == PlanModeReminder.Form.FULL)
                ? PlanModeReminder.renderFull()
                : PlanModeReminder.renderBrief();
            req = new PromptAssembler().attachReminder(req, body);
            if (form == PlanModeReminder.Form.FULL) {
                config = config.withReminderState(
                    config.reminderState().afterFull(iteration));
            }
        }

        try { provider.stream(req, col); }
        catch (ProviderException e) { /* ... */ return; }

        sink.accept(new AgentEvent.IterationEnd(iteration, col.stopReason(),
            col.toolUses().stream().map(t -> t.id()).toList(),
            col.usage()));
        // usage 推送由 ResponseCollector 在收到 MessageEnd 时完成；不在这里重复

        // ... 其余不变（TOOL_USE 分支、appendAssistant、回灌等）
    }
}
```

**关键改动**：
- `AgentConfig.reminderState()` 在每轮 FULL reminder 后通过 `withReminderState` 副本更新；不变更原 config 引用（保证线程可见性）
- `AgentConfig.withReminderState(State)` 是 record 显式附加方法（Java record 不自动生成 `with*`，需手动声明；见 §2.10）
- `AgentLoop` 构造接收新参数 `Consumer<TokenUsage> usageSink`；`ReplLoop` 传 `printer::usage`，测试传 `null`

### 4.3 `ReplLoop` `/plan`、`/do`、`/cancel` 改造

```java
if (trimmed.startsWith("/plan ")) {
    String query = trimmed.substring(6).trim();
    if (query.isEmpty()) { printer.error("/plan requires a query"); continue; }
    agentConfig = new AgentConfig(
        agentConfig.model(), agentConfig.systemBlocks(), agentConfig.thinking(),
        agentConfig.maxIterations(), agentConfig.maxConsecutiveUnknown(),
        PlanMode.PLAN,
        PlanModeReminder.State.initial());   // 重置 reminder 状态
    agent.updateConfig(agentConfig);
    agent.run(query, printer);
    printer.newline();
    continue;
}
```

`/do`、`/cancel` 同样保留 `reminderState`。`/cancel` 把 state 重置回 `initial()`。

---

## 5. 7 个固定 Section 的内容草稿

> 草稿文字仅供实现期参考；最终措辞落在 commit 时微调。

**IdentitySection**：
```
你是 MapleCode，一个运行在终端的 AI 编程助手。当前工作目录：<cwd>。
```

**SystemConstraintsSection**：
```
请遵循：
- 不确定时，先读相关文件再行动，不要凭空猜测 API、路径或代码内容。
- 仅使用已注册的工具。不要伪造工具结果。
- 引用文件路径时优先使用工作目录相对的相对路径。
- 回答与代码无关的问题时保持简洁（一两句话），不要长篇展开。
```

**TaskModeSection**：根据 `ctx.planMode()` 切换两套：
- NORMAL：`你处于执行模式，可以读写文件、执行命令，完成用户的任务即可。`
- PLAN：`你处于规划模式，仅可使用 read_file / glob / grep。输出一份可执行计划，列出每个步骤及对应工具调用，完成后停止。`

**ActionExecutionSection**：
```
执行原则：
- 多步任务先列出计划，再按顺序执行；不要把所有步骤一口气说出。
- 调用工具前先说明目的（一两句话），调用后说明观察到的关键结果。
- 工具返回错误时，先分析根因再决定是否重试，避免盲目重复相同调用。
- 完整任务完成后再下总结；不要中途打断。
```

**ToolUsageSection**：
```
工具使用约定：
- 优先使用专用工具：读文件用 read_file，搜索用 glob / grep，写文件用 write_file，精确修改用 edit_file。
- edit_file 之前必须先 read_file 目标文件，确认实际内容。
- exec 运行长命令前评估超时；尽量在命令后加 `|| true` 或 `timeout N` 避免僵死。
- 不要用 exec 模拟 ls / find / grep；这些有专用工具，效果更好。
- 工具返回错误时，结构化错误信息优先看，比如 `Unknown tool:` / `path is a directory:`，按错误指引调整。
```

**ToneStyleSection**：
```
风格：
- 中文短句优先；标点用中文全角。
- 技术名词保留英文（如 cache, token, schema）。
- 段落用空行分隔，避免长段落。
```

**TextOutputSection**：
```
输出格式：
- 代码块包裹路径和命令。
- 列表用 `- ` 不用数字编号，除非是步骤。
- 不要把工具调用的 JSON 完整回显；只说结论。
```

**EnvironmentSection**（cacheable=false）：
```
## Environment
- Working directory: <cwd>
- Git repo: <yes/no>
- Platform: <os name> (<arch>)
- Runtime: Java <version>, Maven <version>
- Date: <YYYY-MM-DD> (<day of week>)
- Time: <HH:MM>
```

---

## 6. 错误处理

| 场景 | 处理 | 影响 |
|---|---|---|
| `ConfigLoader.load` 失败 | 已包 `ConfigException`，退出码 78 | 不变 |
| `AgentConfig` 紧凑构造器失败（maxIterations < 1） | 抛 `ConfigException` | 同上 |
| `PromptAssembler.assemble` 全部 section disabled | 返回 `List.of()`；mapper 检测空列表不发 system 字段 | 不退化 |
| `DynamicContext.detectMavenVersion` 失败 | 返 "unknown"；不阻塞启动 | 仅缺少版本号展示 |
| `AnthropicRequestMapper` 输出含 cache_control 但 wire 序列化失败 | `JsonProcessingException` 包 `IllegalStateException` | REPL 退出 |
| `ResponseCollector` 收到 MessageEnd 时调 `printer.usage(null)` | `usage` 方法 null-check 静默返回 | 验证时显示 |
| Plan Mode model 调 write_file | 双层防御：executor 层 unknown tool 兜底 | 回灌 error |
| Reminder tag 字符被模型回显 | 已知风险，Anthropic 训练已抑制；本期不重点验证 | v5 |

---

## 7. 测试策略

### 7.1 单元测试

| 测试类 | 覆盖 |
|--------|------|
| `DefaultSectionsTest` | 7 个固定 section 渲染快照；含关键字 / 工具名 |
| `PromptAssemblerTest` | 多种 sections 组合 → 验证 list 长度 / 仅最后一 cacheable=true / 0 cacheable 时全 false |
| `SectionContextTest` | 字段透传 |
| `DynamicContextTest` | `capture` 在 `@TempDir` 下验证 git 探测；`detectMavenVersion` mock |
| `PlanModeReminderTest` | `decide` 表格驱动：NORMAL 全 NONE；PLAN 0→FULL；PLAN 1→4→BRIEF；PLAN 5→FULL；PLAN 10→FULL |
| `ReminderMessageTest` | wrap 拼出带 tag 的字符串 |
| `TokenUsageTest` | (int, int) 重载；4 字段全构造 |
| `ChatRequestCompatTest` | 旧 5 参构造自动给 `transientReminder=List.of()` |

### 7.2 mapper / parser 测试

| 测试类 | 覆盖 |
|--------|------|
| `AnthropicRequestMapperTest` | systemBlocks 多 block → JSON 含 array；cacheBoundary=true → JSON 含 `cache_control: {type: ephemeral}`；0 个 block → 不含 system 字段 |
| `OpenAiRequestMapperTest` | systemBlocks 多 block → 拼成第一条 system message；不含 cache_control 字段 |
| `AnthropicStreamParserCacheTest` | mock SSE：message_start 带 cache_creation / cache_read → message_delta → message_stop → 终态 TokenUsage 4 字段正确；空白 usage → null |
| `AnthropicStreamParserEnd2EndTest` | 完整 round-trip：构造 request + 解析响应 |

### 7.3 reminder 流集成测试

| 测试类 | 覆盖 |
|--------|------|
| `ReminderInjectorTest` | 验证 wrap + 末尾追加；空 reminder 不动 |
| `AgentLoopReminderTest` | mock provider；PLAN 模式跑 11 轮 → reminder 出现 1 + 5 + 10（即 3 次 FULL）+ 7 个 BRIEF；session 中无 reminder 痕迹 |

### 7.4 兼容性

- 旧 `TokenUsage(int, int)` 测试构造点改 `.of(in, out)`
- 旧 `ChatRequest(String, String, ...)` 测试构造点改 `(model, List.of(new SystemBlock(prompt, false, "legacy")), ...)`，或加 @Deprecated 临时保留
- 所有现存 `mvn test` 仍绿

### 7.5 验证场景（手工 smoke，必跑 A-F 六项）

| 场景 | 期望 |
|---|---|
| A. 多轮 NL→tool→NL | tool 命中率稳定；UI 显示 `cache_create=K` 仅在改 system 时出现 |
| B. /clear 后首轮 | 该轮 `cache_creation>0, cache_read=0`；下一轮反过来 |
| C. /plan "设计 XXX" | 模型只看到 3 个 read 工具；plan reminder 第 1/6/11 轮完整 |
| D. /do 接力 | NORMAL 后无 reminder；本轮 system 切回执行模式 |
| E. YAML `system_prompt:` 自定义 | 该段出现在 ENVIRONMENT 之后；规则生效 |
| F. /cancel 中断 | reminder 状态重置；下一轮再 /plan 仍首发 FULL |

---

## 8. 文件清单

### 8.1 新增

```
src/main/java/com/maplecode/prompt/
├── SystemBlock.java                 (record)
├── PromptSection.java               (interface)
├── SectionContext.java              (record)
├── DynamicContext.java              (record + capture())
├── DefaultSections.java             (常量 registry + 7 个 nested final class)
├── PromptAssembler.java             (assemble / attachReminder)
├── ReminderMessage.java             (wrap)
└── PlanModeReminder.java            (decide / renderFull / renderBrief + State record)

src/main/java/com/maplecode/prompt/section/
├── IdentitySection.java
├── SystemConstraintsSection.java
├── TaskModeSection.java
├── ActionExecutionSection.java
├── ToolUsageSection.java
├── ToneStyleSection.java
├── TextOutputSection.java
├── EnvironmentSection.java
├── CustomInstructionSection.java
├── ActivatedSkillsSection.java      (placeholder 返空)
└── LongTermMemorySection.java       (placeholder 返空)

src/test/java/com/maplecode/prompt/
├── DefaultSectionsTest.java
├── PromptAssemblerTest.java
├── SectionContextTest.java
├── DynamicContextTest.java
├── PlanModeReminderTest.java
├── ReminderMessageTest.java
├── ReminderInjectorTest.java
└── AgentLoopReminderTest.java

src/test/java/com/maplecode/provider/
├── ChatRequestCompatTest.java
├── TokenUsageTest.java
├── anthropic/AnthropicRequestMapperTest.java
├── anthropic/AnthropicStreamParserCacheTest.java
└── openai/OpenAiRequestMapperTest.java
```

### 8.2 修改

```
src/main/java/com/maplecode/
├── provider/
│   ├── ChatRequest.java             ← systemPrompt: String → systemBlocks: List<SystemBlock> + transientReminder
│   └── TokenUsage.java              ← + cacheCreationTokens / cacheReadTokens + .of(int, int) 工厂
├── provider/anthropic/
│   └── AnthropicRequestMapper.java  ← system multi-block array + cache_control 输出
├── provider/anthropic/
│   └── AnthropicStreamParser.java   ← 解析 cache_creation / cache_read
├── provider/openai/
│   └── OpenAiRequestMapper.java     ← systemBlocks 拼接为第一条 message
├── provider/openai/
│   └── OpenAiStreamParser.java      ← TokenUsage.of(...) 构造点更新
├── session/ChatSession.java         ← toRequest 签名同步 systemBlocks
├── agent/AgentConfig.java           ← systemBlocks + reminderState + withReminderState
├── agent/AgentLoop.java             ← attachReminder 调用 + reminder state 更新 + usage 推送
├── config/AppConfig.java            ← systemBlocks 字段（保留 systemPrompt 字段作 fromString 兼容入口）
├── config/ConfigLoader.java         ← DEFAULT_SYSTEM_PROMPT 改为 placeholder；保留 yamlPrompt 字段
├── ui/ReplLoop.java                 ← /plan /do /cancel 携带 reminderState
├── ui/StreamPrinter.java            ← usage(u) 新方法；ResponseCollector 收 MessageEnd 时调用
└── App.java                         ← 启动期 DynamicContext.capture + PromptAssembler 生成 systemBlocks
```

---

## 9. 验收清单

- [ ] `mvn package` 产出可执行 jar；`mvn test` 全绿
- [ ] 默认配置下，启动后 systemBlocks 含 7 段（identity / constraints / task_mode / action / tool_usage / tone / text_output / environment）；YAML `system_prompt` 非空时多一段 `custom_instruction`
- [ ] `AnthropicRequestMapper` 输出 wire JSON 含 system array；末段（cacheable 末位）含 `cache_control: ephemeral`
- [ ] `OpenAiRequestMapper` 输出第一条 message role=system，content 为 blocks 用 `\n\n` 拼接
- [ ] `TokenUsage(0,0,0,0)` / null 时 `printer.usage` 不打印
- [ ] 终端默认显示 `[usage: input=N out=M cache_create=K cache_read=J]`
- [ ] `cache_create > 0` 的轮次：第一次访问该段（重启 / /clear）；后续轮次应 `cache_read > 0`
- [ ] /plan 进入后第一个 assistant 之前 prompt 含 reminder 完整版；之后连续 4 轮精简；第 6 轮重发完整
- [ ] reminder 在 session 历史中**不**出现（用 `printer.info` 配合 `ChatSessionTest.size()` 二次确认）
- [ ] YAML 写 `system_prompt: "做且只做单元测试"` 后，模型在该 session 中拒绝非测试任务
- [ ] `mvn test` 无 deprecation warning 残留（ChatRequest 旧构造器 deprecation 在本期保留两期后清理）
- [ ] pom 依赖不变

---

## 10. 设计决策记录

1. **`ChatRequest.systemBlocks: List<SystemBlock>` 替代 String** —— Anthropic 的 prompt cache 必须 multi-block array；OpenAI 也走同一接口保证 mapper 行为一致；接口破坏已与用户确认
2. **OpenAI 不显式写 cache_control** —— OpenAI Chat Completions 自动缓存连续 1024 token 以上的 system content；不强行加字段避免破坏服务端协议
3. **cacheBoundary 单点策略** —— Anthropic prompt cache 4 断点上限；本设计只在 system 末尾写 1 处，把 tools 留给自动 cache。v5 性能优化阶段再双断点
4. **EnvironmentSection `cacheable=false`** —— cwd / date / time 每次启动变；走消息通道（首块不命中）。若用户希望 date 跨日缓存，v5 加 token 预算监控后再开
5. **7 个 Section 是常量 final class，不是 record** —— record 无 default method override 不便；final class 直接 override `cacheable()`（EnvironmentSection）
6. **ConfigLoader 只保留 yamlPrompt 字符串，不在加载期组装 blocks** —— 避免 ConfigLoader 依赖 PromptAssembler / DynamicContext；这些只能在 App 启动后拿到 env 与 tools
7. **`ReminderInjector` 注入到 messages 末尾而非新增 system 块** —— Anthropic 没"中途 system"通道；`<system-reminder>` 包裹 user message 是 Anthropic 训练的合规隐喻
8. **reminder transient、不写 session** —— 占 token 与污染历史两个理由都成立；交换是失去"可复查"
9. **`PlanModeReminder.State` 放在 `AgentConfig` 上** —— 跨轮迭代需要可更新；recreate AgentConfig 时显式复制 state，避免 ReplLoop 漏带
10. **`/do` 与 `/cancel` 重置 reminderState** —— `/do` 是切换到 NORMAL 不需要 reminder 状态；`/cancel` 之后用户再 /plan 应重新首发 FULL
11. **TokenUsage 加 2 字段保留 `of(int, int)` 重载** —— 兼容 OpenAI 与现有测试调用点；不引入新文件
12. **`printer.usage()` 在 ResponseCollector 收 MessageEnd 时调** —— 不新加 sealed event；print 复用现有 Consumer<StreamChunk> 链路（ResponseCollector 已经能看到 MessageEnd.usage）

---

## 11. 后续阶段预告（不在本期）

- v5：CLAUDE.md / AGENTS.md 等项目指令文件加载
- v5：长期记忆 / 会话内记忆
- v5：真实 MCP 接入
- v5：Skill 真实注入
- v5：自动化 LLM 评估
- v5：Anthropic cache 双断点（system + tools）
