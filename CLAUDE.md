# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

MapleCode 是一个极简的 Java 21 命令行工具，通过 SSE 与 Anthropic Claude 或 OpenAI Chat Completions 对话，支持多轮上下文记忆、工具调用和 Agent Loop。设计文档：
- v1 流式 REPL：`docs/superpowers/specs/2026-07-01-maple-code-design.md`
- v2 工具系统：`docs/superpowers/specs/2026-07-03-maple-code-tool-system-design.md`
- v3 Agent Loop：`docs/superpowers/specs/2026-07-04-maple-code-agent-loop-design.md`
- v4 权限系统：`docs/superpowers/specs/2026-07-06-maple-code-permission-system-design.md`
- v5 系统提示词：`docs/superpowers/specs/2026-07-05-maple-code-system-prompt-design.md`
- v5 MCP 客户端：`docs/superpowers/specs/2026-07-06-maple-code-mcp-client-design.md`
- v6 上下文管理：`docs/superpowers/specs/2026-07-07-maple-code-context-management-design.md`
- v7.1 AGENTS.md 加载器：`docs/superpowers/specs/2026-07-08-maple-code-agents-md-loader-design.md`
- v7.2 记忆系统：`docs/superpowers/specs/2026-07-08-maple-code-memory-design.md`
- v7.3 会话归档：`docs/superpowers/specs/2026-07-08-maple-code-session-archive-design.md`
- v7.4 命令框架：`docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md`
- v7.5 TUI 状态栏：`docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md`
- v7.6 Escape 控制：`docs/superpowers/specs/2026-07-10-maple-code-escape-controls-design.md`

## 构建 / 运行 / 测试

需要 Java 21 和 Maven 3.9+。

```bash
mvn package              # 产出 target/maple-code-java-0.1.0.jar（已 shade，可直接执行）
mvn test                 # 跑全部单元测试
mvn test -Dtest=ClassName                    # 跑单个测试类
mvn test -Dtest=ClassName#methodName         # 跑单个方法
mvn test -Dtest='*StreamParser*'             # 通配符

# 运行
java -jar target/maple-code-java-0.1.0.jar
java -jar target/maple-code-java-0.1.0.jar --config /path/to/config.yaml
```

配置文件查找顺序（命中即用）：`--config <path>` → `./maplecode.yaml` → `~/.maplecode/config.yaml`。仓库根目录的 `maplecode.yaml.example` 是模板。

## 架构

整个程序是启动时一次性装配好的单向数据流：

```
App.main
  └─ ConfigLoader.load(path)           → AppConfig（校验 YAML + ${ENV} + ThinkingConfig + PermissionMode）
  └─ ProviderRegistry.create(config)   → LlmProvider（anthropic | openai）
  └─ ToolRegistry(tools)               → ToolRegistry（6 个内置工具）
  └─ PermissionFileLoader.loadAll()    → RuleSet（三层 YAML 规则合并）
  └─ PermissionEngine(5层check, mode)  → 五层防御管道
  └─ ToolExecutor(registry, engine)    → 工具执行 + 权限检查入口
  └─ ReplLoop.run()                    → JLine 读-求-印 主循环
        └─ AgentLoop                   → ReAct 循环（模型自主调工具、看结果、调整）
              └─ ChatSession           → 内存 List<ChatMessage>（支持 ContentBlock）
              └─ LlmProvider.stream(req, sink)
                    ├─ <Provider>RequestMapper  → 构造 JSON body + HttpRequest
                    ├─ HttpClient.send           → HttpResponse<Stream<String>>（BodyHandlers.ofLines）
                    ├─ SseStreamReader           → SseEvent 流
                    └─ <Provider>StreamParser    → Consumer<StreamChunk>（8 种变体）
                                                        ├─ StreamPrinter（ANSI 写到 stdout）
                                                        └─ ToolExecutor（权限检查 + 工具执行 + 结果回灌）
```

核心抽象：

- **包结构**（`com.maplecode.*`）：`config` 加载 + 校验、`provider`（anthropic / openai 子包）+ 通用 http、`agent` ReAct + PlanMode、`agents` 跨会话记忆加载器（v7.1）、`tool` 7 个内置（含 load_skill）+ `ToolExecutor`、`permission` 6 层 check（含 SkillWhitelistCheck）+ engine + HITL、`session` ChatSession + ContentBlock + `archive` 归档（v7.3）、`ui` REPL + StreamPrinter + StatusBar + EscapeController、`prompt` system prompt 装配（v5）、`mcp` 客户端 5 子包（transport/rpc/client/adapter/config，v5）、`compact` 压缩（v6）、`memory` 记忆系统（v7.2）、`command` 命令框架（v7.4，Command + CommandRegistry + CommandCompleter）、`skill` Skill 系统（v8，SkillRegistry + SkillLoader + ExecutionMode）、`util` 工具类、`error` 异常类型。
- **`LlmProvider`** —— 唯一方法 `void stream(ChatRequest, Consumer<StreamChunk>)`。同步推送，没有回调/future。新增后端只需实现该接口并在 `ProviderRegistry.factories` 注册工厂。
- **`StreamChunk`** —— sealed 接口（`TextDelta | ThinkingDelta | MessageStart | MessageEnd | Error | ToolUseStart | ToolUseDelta | ToolUseEnd`）+ `StopReason` 枚举（含 `TOOL_USE`）。sealed 层次结构保证新增 chunk 变体时所有 `switch` 必须更新。
- **`ContentBlock`** —— sealed 接口（`TextBlock | ToolUseBlock | ToolResultBlock`），用于表示消息内容。ChatMessage 的 content 从 String 改为 `List<ContentBlock>`。
- **`Tool`** —— 工具接口（非 sealed），定义 `name()`、`description()`、`inputSchema()`、`execute()` 方法。6 个内置工具：read_file、write_file、edit_file、exec、glob、grep。
- **`ToolRegistry`** —— 工具注册中心，启动时合并 7 个内置工具（含 load_skill）+ 所有 MCP 工具；命名空间 `mcp__<server>__<tool>` 防冲突；构造期同名重复即抛错。`all()` 返回所有工具，`get(name)` 按名查找。
- **`ToolExecutor`** —— 工具执行器。先查 registry 找工具；再调 `engine.check(req)` 走权限管道（DENY → `ToolResult.error("权限拒绝: ...")`）；最后调 `tool.execute`。带完整错误兜底链：未知工具 → ToolException → 其他异常。单参构造器 `ToolExecutor(registry)` 跳过权限检查（PLAN mode 路径）。
- **`SseStreamReader`** —— 协议无关。把按行流入的字节流切成 `SseEvent(eventType, data)`：多行 `data:` 按规范用 `\n` 拼接，注释/心跳丢弃。
- **`ChatSession`** —— 一轮对话内只追加，并且只在成功时追加。用户消息在请求前追加；助手消息只在收到 `MessageEnd` 之后追加。如果流异常抛出，session 不动，用户可以重试。`/clear` 清空。

### Agent Loop（v3）

- **`AgentLoop`** —— ReAct 循环。`run(userInput, sink)` 内 `while(iteration < maxIterations)`：发请求 → 收到 `TOOL_USE` → 按安全性分批执行 → 结果回灌 → 继续；收到 `END_TURN` / `MAX_ITERATIONS` / `CONSECUTIVE_UNKNOWN` / `PROVIDER_ERROR` → 停止。
- **`Batch.partition`** —— 按 `ToolRegistry.isReadOnly()` 分成 safe（只读，并行 `parallelStream`）和 unsafe（有副作用，串行）。
- **`ResponseCollector`** —— 双路收集：`StringBuilder text` + `List<ToolUse> toolUses`。`ToolUse` 内嵌在 `ResponseCollector` 里。
- **`PlanMode`** —— `NORMAL` / `PLAN`。PLAN 模式下 ChatRequest 层只暴露只读工具，executor 层包装 readOnlyReg——双层防御。
- **`AgentEvent`** —— sealed 接口（11 变体），`StreamPrinter` 实现 `Consumer<AgentEvent>`。
- **`AgentConfig`** —— immutable 配置：model、systemBlocks、thinking、maxIterations(25)、maxConsecutiveUnknown(3)、planMode、reminderState。

### 权限系统（v4）

五层 `PermissionCheck` 串成短路管道，每层返回 `Optional<Decision>`，未决则交给下一层：

```
ToolExecutor.run()
  └─ BlacklistCheck     → 12 条硬编码 regex，仅 exec，不可配置
  └─ SandboxCheck       → 路径沙箱，toRealPath() 防 symlink 逃逸；exec 跳过
  └─ RuleCheck          → 三层 YAML 规则 first-match-wins；exec 用 shell glob，其他用 PathMatcher
  └─ SkillWhitelistCheck → INDEPENDENT 模式下仅放行白名单工具；非 skill 上下文 pass-through
  └─ ModeCheck          → strict→deny / permissive→allow / default→未决
  └─ HitlCheck          → 弹 prompt 4 选 1：本次/本会话/本项目/拒绝
```

- **`PermissionEngine`** —— 持有序 `List<PermissionCheck>` + `AtomicReference<PermissionMode>` + `ConcurrentHashMap.newKeySet()` 的 session 集合。`check()` 每次新建 `PermissionContext`（per-call 视图），parallelStream 并发安全。
- **`HitlCheck.setEngine(engine)`** —— 后置注入打破构造期循环。构造时 engine=null，`App.main` 构造完 engine 后调一次 `setEngine`。
- **规则文件**：`~/.maplecode/permissions.yaml`（用户全局）→ `<cwd>/.maplecode/permissions.yaml`（项目）→ `<cwd>/.maplecode/permissions.local.yaml`（项目本地），优先级 local > project > user。
- **`/mode` 命令**：热切三档（strict / default / permissive），不持久化，重启回到 YAML 配置。

### MCP 客户端（v5）

- **`mcp_servers`** —— YAML 配置块（`enabled` 默认 true，`startup_timeout_ms` 默认 5000）。`maplecode.yaml.example` 末尾有完整示例。
- **三层配置文件**（优先级从低到高，子 map deep-merge）：
  - `~/.maplecode/mcp_servers.yaml` —— 用户全局
  - `<cwd>/.maplecode/mcp_servers.yaml` —— 项目级（入 git）
  - `<cwd>/.maplecode/mcp_servers.local.yaml` —— 项目本地（入 `.gitignore`）
- **两种 transport**：`stdio`（子进程 + 行分隔 JSON）和 `http`（StreamableHttp POST，`${ENV}` 展开 headers）。Stdio 进程默认超时 30s。
- **装配点**：`App.main` 调 `McpServerConfigLoader.loadAll` → `McpClientBootstrap.start`（并发启动 + 整体超时 + 单 server 失败 WARN 一行降级）→ `McpToolAdapter` 包装每个远端 tool 注入 `ToolRegistry`。MCP tool 走完整 `PermissionEngine` 管道，和内置工具一视同仁。
- **诊断**：所有 MCP 内部日志走 stderr，前缀 `[mcp:<server>:stderr]`，**绝不写 stdout**（会污染 MCP wire）。
- **非目标**：`resources` / `prompts` / `sampling` / `roots` / 通知 / 断点续传 / OAuth 均未实现。

### REPL 斜杠命令

| 命令 | 行为 |
|---|---|
| `/help [command]` | 显示命令帮助 |
| `/clear` | 清空 session 历史 |
| `/compact` | 手动压缩上下文 |
| `/skill [list\|load\|unload]` | 管理 Skill（列出、加载、卸载） |
| `/tools` | 列出所有可用工具（内置 + MCP，含多行 description） |
| `/plan <query>` | 规划模式：只读工具，模型只分析不执行 |
| `/do` | 执行上一条规划 |
| `/review` | 代码审查 |
| `/memory <list\|clear\|extract>` | 记忆系统管理 |
| `/new` | 新建会话 |
| `/resume` | 恢复归档会话 |
| `/status` | 显示当前状态 |
| `/mode [s\|d\|p]` | 查看或切换 strict/default/permissive |
| `/exit` 或 Ctrl+D | 正常退出（退出码 0） |

**StatusBar**：终端底部状态栏（JLine `Status`），显示模型名、token 用量、模式、工作目录。依赖终端 scroll region 支持。

**EscapeController**：管理 Esc 键行为，支持 agent 流取消和输入清空：

- Agent 流式输出期间单击 Esc：立即取消当前流式响应
- 用户输入期间 500ms 内双击 Esc：清空输入
- 多行输入期间双击 Esc：丢弃整段内容并返回主提示符

### 系统提示词（v5）

- **`PromptAssembler`** —— 装配 system prompt，由 `DefaultSections` 提供静态块、`DynamicContext` 提供运行时上下文（cwd、平台、当前时间等）、`PlanModeReminder` 在 PLAN 模式下追加只读约束。
- 配置项 `system_prompt`（顶层字符串）会作为额外 system block 注入；与内嵌块叠加。

### 命令框架（v7.4）

- **`Command`** —— 斜杠命令接口，定义 `name()`、`description()`、`usage()`、`type()`、`hidden()`、`aliases()`、`execute()` 方法。
- **`CommandRegistry`** —— 启动时注册所有内置命令，名称冲突检测，支持 Tab 补全（`CommandCompleter`）。
- **`CommandContext`** —— 命令执行上下文，提供 UI 控制接口。
- **14 个内置命令**：clear、compact、do、exit、help、memory、mode、new、plan、resume、review、skill、status、tools。
- **`ExitReplException`** —— `/exit` 抛出此异常终止 REPL 主循环。

### Skill 系统（v8）

- **`SkillRegistry`** —— 管理所有 Skill 定义和激活状态。`loadAll()` 启动时调 `SkillLoader` 加载。
- **`SkillLoader`** —— 两阶段加载：classpath 扫描内置 skill YAML + AGENTS.md 中 `skills:` 块声明。
- **`ExecutionMode`** —— `SHARED`（skill 工具注入主 ToolRegistry，与内置工具共存）/ `INDEPENDENT`（`IndependentSkillRunner` 独立 Agent Loop，仅暴露白名单工具）。
- **`SkillWhitelistCheck`** —— 第 6 层 `PermissionCheck`，INDEPENDENT 模式下仅放行白名单工具。
- **`LoadSkillTool`** —— 第 7 个内置工具，模型可通过 `load_skill` 动态激活 skill。
- **`ActivatedSkillsSection`** —— `PromptSection` 实现，将已激活 skill 注入系统提示词。

## 值得注意的约定

- **Tool 接口是非 sealed 的**。原计划 sealed permits 6 个具体工具类，但 Java 不允许 sealed 接口被匿名类实现，测试需要 mock 工具实例。改为非 sealed，6 个具体类仍在 App.java 集中注册。
- **ChatMessage 使用 ContentBlock 列表**。v1 的 String content 改为 `List<ContentBlock>`，支持文本、工具调用、工具结果三种内容块。
- **工具执行静默自动跑**。read_file、write_file、edit_file、exec、glob、grep 都不需用户确认（v4 权限系统可能拒绝——但拒绝本身也是静默的，返回 `ToolResult.error`）。工具失败时返回结构化错误信息给模型，不中断 Agent Loop。
- **工具调用循环**。`runOneTurn()` 内有 `while(true)` 循环：模型发 tool_use → 执行 → 回灌 tool_result → 模型再回话 → 若仍是 tool_use 则继续循环，直到模型生成文本回复为止。每次只处理 1 个 tool_use，多则报错。
- **GlobTool 匹配相对路径**。PathMatcher 必须匹配 `cwd.relativize(p)` 而非绝对路径，否则 `*.txt` 无法匹配 `/tmp/.../f0.txt`。
- **ToolExecutor 创建自己的 ToolContext**。`run()` 方法内部用 `System.getProperty("user.dir")` 创建默认上下文，不接受外部传入的 ctx。同时 cwd 也用于构造 `PermissionRequest`。
- **StringBuilder 转 String**。ReplLoop 中 acc.text 是 StringBuilder，传给 TextBlock 时需要 `.toString()`。
- **Parser 错误保护**。AnthropicStreamParser 有 `errored` 标志，error 事件后停止处理后续事件（防止 error + message_stop 时错误响应被追加到会话）。
- **OpenAI tool_calls 按 index 跟踪**。parser 用 `Map<Integer, ToolAcc>` 跟踪多个并行工具调用，`finish_reason=tool_calls` 时 flush 所有累积器。
- **thinking 有两种互不兼容的格式**（设计文档 §5.5）。`type: adaptive` + `effort: low|medium|high` 是当前 API，Opus 4.7/4.6、Sonnet 4.6 都可用。`type: enabled` + `budget_tokens`（≥1024，`max_tokens` 在 `AnthropicRequestMapper` 硬编码为 16384）是 legacy：4.6 / Sonnet 4.6 上仍接受，Opus 4.7 上 HTTP 400。两种格式互斥，`ThinkingConfig` 紧凑构造器在配置加载时强制校验并抛 `ConfigException`。加载 `type=enabled` 时还会向 stderr 打印一行 deprecation 警告。
- **OpenAI 忽略 `thinking`**。`OpenAiRequestMapper` 静默丢弃——`ChatRequest` 上的 `thinking` 字段在两个 provider 间保持一致类型，但只有 Anthropic mapper 会用。
- **校验必须前置**。任何本应在发请求前发现的错误，都应该在 `ThinkingConfig` 紧凑构造器、`ConfigLoader` 或 `ProviderRegistry.create` 里抛出来。Provider 层不适合用来发现配置错误。
- **退出码**：配置错误（`ConfigException`）退出码 **78**（sysexits.h `EX_CONFIG`）。`/exit` 或 Ctrl+D 正常退出码 0。流式过程中的 provider 错误只打印、不退出 REPL——主循环继续。
- **超时**（`AppConfig.Timeouts`）：`connect_seconds`（默认 10）→ `HttpClient.connectTimeout`；`read_seconds`（默认 60）→ `HttpRequest.timeout()`（每次请求的读超时）。分别在 `*Provider` 构造器和 request mapper 里接线。
- **Session 不保存 thinking 内容**。Anthropic 的 `ThinkingDelta` 推给 UI 但不写回 session——`ChatSession` 每轮只保留最终文本。
- **Parser 状态每轮必须重置**。`AnthropicStreamParser.reset()` / `OpenAiStreamParser.reset()` 在每次 `stream()` 入口调用；不要把调用点移走，否则块级状态会在多轮之间泄漏。

## 测试

JUnit 5 + Mockito。测试位于 `src/test/java`，与主代码包结构一一对应。`ConfigLoaderTest` 里 `@TempDir Path` + `Files.writeString` 的写法是不落盘测试 config 加载的标准模板。SSE parser 测试（`SseStreamReaderTest`）则 mock 一个 `HttpResponse<Stream<String>>`，让 `body()` 返回 `Stream.of("data: ...", "")`。

工具测试（`ReadFileToolTest`、`WriteFileToolTest` 等）使用 `@TempDir Path` 创建临时目录，避免污染项目目录。`ToolExecutorTest` 使用匿名 `new Tool() { ... }` 实现 mock 工具（因为 Tool 是非 sealed 接口）。

仓库里没有 `*IT.java` 集成测试——端到端 smoke 是手工跑。Surefire 3.5.2 默认跑 `*Test.java`，无需额外配置。

## 明确不在当前范围

网络请求限制、资源配额（CPU/内存/磁盘）、审计日志、规则 UI 编辑器、运行时切换 provider、多模态输入、插件系统。详见各阶段设计文档 §1。
