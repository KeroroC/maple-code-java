# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

MapleCode 是一个极简的 Java 21 命令行工具，通过 SSE 与 Anthropic Claude 或 OpenAI Chat Completions 对话，支持多轮上下文记忆。v1 明确不做 tool use、文件操作、命令执行与会话持久化。完整设计见 `docs/superpowers/specs/2026-07-01-maple-code-design.md`——做非琐碎改动前请先读 §3（结构）、§5（Provider 层）、§9（错误/UX）。

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

## 架构 —— 流式数据管线

整个程序是启动时一次性装配好的单向数据流：

```
App.main
  └─ ConfigLoader.load(path)           → AppConfig（校验 YAML + ${ENV} + ThinkingConfig）
  └─ ProviderRegistry.create(config)   → LlmProvider（anthropic | openai）
  └─ ReplLoop.run()                    → JLine 读-求-印 主循环
        └─ ChatSession                 → 内存 List<ChatMessage>
        └─ LlmProvider.stream(req, sink)
              ├─ <Provider>RequestMapper  → 构造 JSON body + HttpRequest
              ├─ HttpClient.send           → HttpResponse<Stream<String>>（BodyHandlers.ofLines）
              ├─ SseStreamReader           → SseEvent 流
              └─ <Provider>StreamParser    → Consumer<StreamChunk>
                                                  └─ StreamPrinter（ANSI 写到 stdout）
```

核心抽象：

- **`LlmProvider`** —— 唯一方法 `void stream(ChatRequest, Consumer<StreamChunk>)`。同步推送，没有回调/future。新增后端只需实现该接口并在 `ProviderRegistry.factories` 注册工厂。
- **`StreamChunk`** —— sealed 接口（`TextDelta | ThinkingDelta | MessageStart | MessageEnd | Error`）+ `StopReason` 枚举。sealed 层次结构保证新增 chunk 变体时所有 `switch` 必须更新。
- **`SseStreamReader`** —— 协议无关。把按行流入的字节流切成 `SseEvent(eventType, data)`：多行 `data:` 按规范用 `\n` 拼接，注释/心跳丢弃。
- **`ChatSession`** —— 一轮对话内只追加，并且只在成功时追加。用户消息在请求前追加；助手消息只在收到 `MessageEnd` 之后追加。如果流异常抛出，session 不动，用户可以重试。`/clear` 清空。

## 值得注意的约定

- **thinking 有两种互不兼容的格式**（设计文档 §5.5）。`type: adaptive` + `effort: low|medium|high` 是当前 API，Opus 4.7/4.6、Sonnet 4.6 都可用。`type: enabled` + `budget_tokens`（≥1024，`max_tokens` 在 `AnthropicRequestMapper` 硬编码为 16384）是 legacy：4.6 / Sonnet 4.6 上仍接受，Opus 4.7 上 HTTP 400。两种格式互斥，`ThinkingConfig` 紧凑构造器在配置加载时强制校验并抛 `ConfigException`。加载 `type=enabled` 时还会向 stderr 打印一行 deprecation 警告。
- **OpenAI 忽略 `thinking`**。`OpenAiRequestMapper` 静默丢弃——`ChatRequest` 上的 `thinking` 字段在两个 provider 间保持一致类型，但只有 Anthropic mapper 会用。
- **校验必须前置**。任何本应在发请求前发现的错误，都应该在 `ThinkingConfig` 紧凑构造器、`ConfigLoader` 或 `ProviderRegistry.create` 里抛出来。Provider 层不适合用来发现配置错误。
- **退出码**：配置错误（`ConfigException`）退出码 **78**（sysexits.h `EX_CONFIG`）。`/exit` 或 Ctrl+D 正常退出码 0。流式过程中的 provider 错误只打印、不退出 REPL——主循环继续。
- **超时**（`AppConfig.Timeouts`）：`connect_seconds`（默认 10）→ `HttpClient.connectTimeout`；`read_seconds`（默认 60）→ `HttpRequest.timeout()`（每次请求的读超时）。分别在 `*Provider` 构造器和 request mapper 里接线。
- **Session 不保存 thinking 内容**。Anthropic 的 `ThinkingDelta` 推给 UI 但不写回 session——`ChatSession` 每轮只保留最终文本。
- **Parser 状态每轮必须重置**。`AnthropicStreamParser.reset()` / `OpenAiStreamParser.reset()` 在每次 `stream()` 入口调用；不要把调用点移走，否则块级状态会在多轮之间泄漏。

## 测试

JUnit 5 + Mockito。测试位于 `src/test/java`，与主代码包结构一一对应。`ConfigLoaderTest` 里 `@TempDir Path` + `Files.writeString` 的写法是不落盘测试 config 加载的标准模板。SSE parser 测试（`SseStreamReaderTest`）则 mock 一个 `HttpResponse<Stream<String>>`，让 `body()` 返回 `Stream.of("data: ...", "")`。

仓库里没有 `*IT.java` 集成测试——v1 的端到端 smoke 是手工跑。Surefire 3.5.2 默认跑 `*Test.java`，无需额外配置。

## 明确不在 v1 范围

Tool use / function calling、文件操作、命令执行、会话持久化与 `/resume`、运行时切换 provider、多模态输入、插件系统。详见设计文档 §1 和 §12。如果任务看起来需要这些能力，请先确认范围再动手。
