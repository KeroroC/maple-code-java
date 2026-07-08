# v7.3 自动长期记忆设计

> 日期：2026-07-08  
> 状态：设计中  
> 前置：v7.1 AGENTS.md 加载器、v7.2 Session Archive

## 1. 目标

在每轮 Agent Loop 自然结束后，异步调用 LLM 分析对话，自动新增/修改/删除长期记忆。记忆在下次启动时注入系统提示词，实现跨会话知识积累。

### 1.1 非目标

- 记忆的语义搜索/向量化
- 记忆的自动过期/衰减
- 运行时动态刷新记忆（只在启动时加载）
- 多用户/团队共享记忆
- 记忆的加密/脱敏

## 2. 记忆分类与存储

### 2.1 四类记忆

| 类别 | 枚举值 | 归属 | 内容 |
|---|---|---|---|
| 用户偏好 | `USER` | 用户级 | 编码风格、语言偏好、输出格式偏好 |
| 纠正反馈 | `FEEDBACK` | 用户级 | 用户对助手行为的纠正、不满、表扬 |
| 项目知识 | `PROJECT` | 项目级 | 技术栈、架构约定、构建命令、目录结构 |
| 参考信息 | `REFERENCE` | 项目级 | 外部文档 URL、配置文件位置、API 端点 |

### 2.2 两个存储位置

| 位置 | 路径 | 存储类别 |
|---|---|---|
| 用户级 | `~/.maplecode/memory/` | `USER`, `FEEDBACK` |
| 项目级 | `<cwd>/.maplecode/memory/` | `PROJECT`, `REFERENCE` |

### 2.3 目录结构

```
~/.maplecode/memory/
  user/
    001-prefer-java21.md
    002-prefers-verbose-output.md
  feedback/
    001-no-explanation-needed.md
  MEMORY.md                        ← 索引文件

<cwd>/.maplecode/memory/
  project/
    001-uses-spring-boot-3.md
  reference/
    001-api-spec-location.md
  MEMORY.md
```

## 3. 文件格式

### 3.1 单条记忆文件

每个记忆一个 `.md` 文件，使用 YAML frontmatter + 正文格式：

```markdown
---
name: prefer-java21
category: user
created: 2026-07-08T10:30:00
updated: 2026-07-08T10:30:00
---

用户偏好使用 Java 21，包括 virtual threads、sealed classes、record patterns 等新特性。
```

字段说明：
- `name`：短横线命名的标识符，由 LLM 生成，简短且有意义
- `category`：`user` / `feedback` / `project` / `reference`
- `created`：首次创建时间（ISO 8601）
- `updated`：最后更新时间（ISO 8601）
- 正文：记忆内容，1-3 句话

### 3.2 MEMORY.md 索引

每个存储位置一个 MEMORY.md 索引文件，列出该位置所有记忆的摘要：

```markdown
# Memory Index

## User Preferences
- [prefer-java21](user/001-prefer-java21.md) — 用户偏好 Java 21 新特性
- [verbose-output](user/002-prefers-verbose-output.md) — 偏好详细输出

## Feedback
- [no-explanation-needed](feedback/001-no-explanation-needed.md) — 不需要逐步解释

## Project Knowledge
- [spring-boot-3](project/001-uses-spring-boot-3.md) — 项目使用 Spring Boot 3

## References
- [api-spec](reference/001-api-spec-location.md) — API 文档在 docs/api/
```

约束：
- 总行数 ≤ 200 行
- 总大小 ≤ 25KB（字节级）
- 超限时按 LRU（最后更新时间最旧的）删除

## 4. 组件设计

### 4.1 包结构

```
com.maplecode.memory/
  MemoryConfig          — 不可变配置记录
  MemoryCategory        — 枚举：USER / FEEDBACK / PROJECT / REFERENCE
  MemoryScope           — 枚举：USER / PROJECT（存储位置）
  MemoryEntry           — 索引条目记录（name, category, summary, relativePath, updated）
  MemoryOp              — sealed 接口：Create / Update / Delete
  MemoryOpsResult       — 解析后的操作列表
  MemoryExtractor       — 调 LLM → 解析 JSON ops
  MemoryStore           — 文件 I/O：读写单条记忆 + MEMORY.md 索引
  MemoryFailureCounter  — 连续失败计数 + 熔断
  MemoryManager         — 门面：协调 extractor + store + 失败计数
```

### 4.2 MemoryConfig

不可变记录，从 `AppConfig` 派生：

```java
record MemoryConfig(
    boolean enabled,              // 默认 false
    String memoryModel,           // 提取用模型，null 则复用主模型
    int maxContextMessages        // 提取时看最近几条消息，默认 10
) {
    static MemoryConfig fromAppConfig(AppConfig config) { ... }
}
```

### 4.3 MemoryCategory

```java
enum MemoryCategory {
    USER("user", MemoryScope.USER),
    FEEDBACK("feedback", MemoryScope.USER),
    PROJECT("project", MemoryScope.PROJECT),
    REFERENCE("reference", MemoryScope.PROJECT);

    final String dirName;
    final MemoryScope scope;
}
```

### 4.4 MemoryScope

```java
enum MemoryScope {
    USER(Path.of(System.getProperty("user.home"), ".maplecode", "memory")),
    PROJECT(Path.of(System.getProperty("user.dir"), ".maplecode", "memory"));

    final Path basePath;
}
```

### 4.5 MemoryEntry

索引条目，从 MEMORY.md 解析而来：

```java
record MemoryEntry(
    String name,               // 短横线标识符
    MemoryCategory category,   // 所属类别
    String summary,            // 一行摘要
    String relativePath,       // 相对于 scope basePath 的路径，如 "user/001-prefer-java21.md"
    String updated             // 最后更新时间（ISO 8601）
) {}
```

### 4.6 MemoryOp

```java
sealed interface MemoryOp {
    record Create(MemoryCategory category, String name, String content) implements MemoryOp {}
    record Update(String name, String content) implements MemoryOp {}
    record Delete(String name) implements MemoryOp {}
}
```

### 4.7 MemoryExtractor

负责调用 LLM 并解析响应：

```java
class MemoryExtractor {
    MemoryExtractor(LlmProvider provider, MemoryConfig config, List<MemoryEntry> existingMemories)

    // 调 LLM，返回解析后的操作列表
    // 同步方法，在 CompletableFuture.supplyAsync 中调用
    MemoryOpsResult extract(List<ChatMessage> recentMessages)
}
```

内部流程：
1. 构造 system prompt（含已有记忆列表 + 操作格式说明）
2. 构造 user prompt（最近 N 条消息的文本摘要）
3. 调 `provider.stream(req, collector)` 收集完整响应
4. 从响应文本中提取 JSON 块
5. **容错预处理**：剥离 markdown 代码块标记（```json ... ``` 或 ``` ... ```），提取纯 JSON
6. 解析为 `List<MemoryOp>`
7. 返回 `MemoryOpsResult(ops)`

**JSON 解析容错**：LLM 即使被要求输出纯 JSON，仍高概率带上 markdown 代码块标记。`extract()` 必须先做以下预处理再解析：
- 正则 `^```(?:json)?\s*\n?` 和 `\n?```\s*$` 剥离首尾代码块标记
- `trim()` 后再交给 JSON 解析器
- 如果仍解析失败，尝试从响应文本中用 `\{[\s\S]*\}` 贪婪匹配 JSON 对象

### 4.8 MemoryStore

文件 I/O 层：

```java
class MemoryStore {
    // 读取指定 scope 的 MEMORY.md，返回记忆条目列表
    List<MemoryEntry> loadIndex(MemoryScope scope)

    // 读取单条记忆文件的正文
    String readContent(MemoryScope scope, MemoryCategory category, String name)

    // 执行单个操作
    void executeOp(MemoryOp op)  // create → 写文件, update → 覆盖, delete → 删文件

    // 重建 MEMORY.md 索引
    void rebuildIndex(MemoryScope scope)

    // 清空指定 scope 的所有记忆
    void clearAll(MemoryScope scope)
}
```

文件命名：`NNN-short-name.md`，NNN 是三位数字序号（从已有文件中取 max+1）。

### 4.9 MemoryFailureCounter

```java
class MemoryFailureCounter {
    // 连续失败计数，达到阈值（3）后 isOpen() 返回 false
    void recordFailure()
    // 成功时重置计数
    void recordSuccess()
    // 是否允许继续尝试
    boolean isOpen()
}
```

### 4.10 MemoryManager

门面类，协调所有组件：

```java
class MemoryManager implements Closeable {
    MemoryManager(MemoryConfig config, LlmProvider provider)

    // 异步触发记忆提取（ReplLoop 调用）
    // 内部用专用单线程 ExecutorService 排队执行，保证串行
    void extractAsync(List<ChatMessage> recentMessages)

    // 同步触发（/memory extract 命令）
    void extractSync(List<ChatMessage> recentMessages)

    // 列出所有记忆
    String listMemories()

    // 清空所有记忆
    void clearAll()

    // 关闭：shutdown executor
    void close()
}
```

**并发安全**：`MemoryManager` 内部使用 `Executors.newSingleThreadExecutor()` 而非 `ForkJoinPool.commonPool()`。所有 `extractAsync` 调用提交到这个单线程队列，保证文件 I/O 串行执行，避免并发读写 MEMORY.md 和序号文件导致索引损坏或文件覆盖。`close()` 负责 `executor.shutdownNow()`。

失败处理：
- 连续 3 次失败后跳过后续调用（在 `com.maplecode.memory` 包内新建 `MemoryFailureCounter`，逻辑与 `compact.FailureCounter` 相同但独立实现，避免跨包依赖）
- 下次成功重置计数器
- 所有诊断走 stderr `[memory]` 前缀，绝不写 stdout

## 5. LLM 提取

### 5.1 提取 Prompt

System prompt：

```
你是一个记忆管理助手。根据对话内容，决定是否需要新增、修改或删除长期记忆。

记忆分为四类：
- user: 用户偏好、习惯、风格（如编码风格、语言偏好、输出格式偏好）
- feedback: 用户对助手行为的纠正、反馈（如"不要解释这么多"、"用中文回复"）
- project: 当前项目的技术栈、架构约定、构建命令、目录结构
- reference: 外部资源链接、文档位置、API 端点、配置文件路径

当前已有记忆：
{existing_memory_list}

输出要求：
- 输出纯 JSON，不要包含 markdown 代码块标记
- 如果无需任何操作，输出 {"ops": []}
- name 使用英文短横线命名，简短且有意义
- content 用 1-3 句话概括
- 对已有记忆，如果对话中有新信息，使用 update 更新
- 如果用户明确否定/纠正了之前的记忆，使用 delete 删除
```

User prompt：

```
以下是最近的对话：

{formatted_recent_messages}
```

### 5.2 JSON 响应格式

```json
{
  "ops": [
    {
      "action": "create",
      "category": "user",
      "name": "prefer-java21",
      "content": "用户偏好使用 Java 21，包括 virtual threads、sealed classes 等新特性。"
    },
    {
      "action": "update",
      "name": "verbose-output",
      "content": "用户偏好详细输出，包含代码示例和解释。"
    },
    {
      "action": "delete",
      "name": "old-memory"
    }
  ]
}
```

`action` 取值：`create`、`update`、`delete`。
- `create`：必须有 `category`、`name`、`content`
- `update`：必须有 `name`、`content`（根据 name 查找已有记忆）
- `delete`：只需 `name`

### 5.3 对话格式化

最近 N 条消息格式化为纯文本：

```
[用户] 请帮我重构这个类，使用 sealed interface
[助手] 好的，我来将 BaseHandler 改为 sealed interface...
[用户] 不要加注释，代码自解释就行
[助手] 明白，我移除了所有注释...
```

只取 TextBlock 内容，跳过 ToolUseBlock 和 ToolResultBlock。

## 6. 集成点

### 6.1 ReplLoop 集成

在 `ReplLoop` 中，`agent.run()` 返回后触发异步记忆提取：

```java
// ReplLoop.java — run() 方法内
agent.run(userInput, sink::accept);

// 记忆提取：异步，不阻塞用户交互
if (memoryManager != null) {
    memoryManager.extractAsync(session.recentMessages(config.memoryConfig().maxContextMessages()));
}
```

需要在 `ChatSession` 新增 `recentMessages(int n)` 方法：返回最近 n 条消息的不可变副本（防御性拷贝）。实现：`messages.subList(Math.max(0, messages.size() - n), messages.size())` 包装为 `List.copyOf`。

### 6.2 系统提示词注入

启动时读取两个 MEMORY.md（用户级 + 项目级），合并后作为参数传入 `DefaultSections.standard()`：

```java
// App.main — 系统提示词装配阶段
String userMemory = memoryStore.loadIndexText(MemoryScope.USER);
String projectMemory = memoryStore.loadIndexText(MemoryScope.PROJECT);
String combined = combineMemorySections(userMemory, projectMemory);

// standard() 新增 memoryContent 参数
List<PromptSection> sections = DefaultSections.standard(env, tools, planMode, customInstruction, agentsMd, combined);
```

`DefaultSections.standard()` 内部在 `AGENTS_MD` 之后、`ENVIRONMENT` 之前插入 memory section（content 非空时才插入）。同时删除 `LONG_TERM_MEMORY` 静态占位字段。

### 6.3 `/memory` 命令

| 命令 | 行为 |
|---|---|
| `/memory list` | 读取两个 MEMORY.md，格式化输出所有记忆 |
| `/memory clear` | 确认提示后删除两个 memory/ 目录下的所有 .md 文件和 MEMORY.md |
| `/memory extract` | 同步触发一次记忆提取，等待完成后显示结果 |

### 6.4 AppConfig 扩展

`AppConfig` 新增字段：

```java
record AppConfig(
    // ... existing fields ...
    MemoryConfig memoryConfig   // nullable，null 表示未配置（使用默认值）
) {}
```

`ConfigLoader` 解析 `memory` 配置块：

```yaml
memory:
  enabled: true
  memory_model: "claude-haiku-4-5"
  max_context_messages: 10
```

## 7. 错误处理

| 场景 | 行为 |
|---|---|
| memory_model 未配置 | 复用主模型 |
| LLM 调用失败 | stderr WARN，静默跳过 |
| JSON 解析失败 | stderr WARN，静默跳过 |
| 连续 3 次失败 | 熔断，跳过后续调用；下次成功重置 |
| MEMORY.md 不存在 | 视为空记忆列表 |
| 单条记忆文件损坏 | 跳过该条，stderr WARN |
| 文件写入权限错误 | stderr WARN，跳过该操作 |
| memory.enabled = false | 完全禁用，不创建 MemoryManager |

所有诊断日志使用 stderr，前缀 `[memory]`，绝不写 stdout（避免污染 REPL 输出）。

## 8. 配置示例

```yaml
# maplecode.yaml
model: "claude-sonnet-4-6"
api_key: "${ANTHROPIC_API_KEY}"

memory:
  enabled: true
  memory_model: "claude-haiku-4-5"    # 用便宜模型做记忆提取
  max_context_messages: 10             # 看最近 10 条消息
```

## 9. 测试策略

| 测试类 | 覆盖范围 |
|---|---|
| `MemoryConfigTest` | 配置解析、默认值、fromAppConfig |
| `MemoryCategoryTest` | 枚举映射、scope 归属 |
| `MemoryExtractorTest` | JSON 解析、边界情况（空 ops、无效 action、缺少字段）、markdown 代码块剥离容错 |
| `MemoryStoreTest` | 文件读写、索引重建、clearAll、文件命名、边界（损坏文件） |
| `MemoryManagerTest` | 异步调用、失败熔断、listMemories 格式化 |
| `MemoryFailureCounterTest` | 计数、熔断、重置 |
| `MemoryOpTest` | sealed 接口的 pattern matching |

使用 `@TempDir Path` 避免污染真实目录（与 `ConfigLoaderTest`、`CompactStorageTest` 同模式）。

## 10. 实现计划

### T1: MemoryConfig + MemoryCategory + MemoryScope + AppConfig 集成
- 新建 `com.maplecode.memory` 包
- 实现 MemoryConfig 记录、MemoryCategory 和 MemoryScope 枚举
- AppConfig 新增 memoryConfig 字段，ConfigLoader 解析 memory 配置块
- 更新 maplecode.yaml.example
- 测试：MemoryConfigTest、MemoryCategoryTest

### T2: MemoryEntry + MemoryOp + MemoryOpsResult
- 实现 MemoryEntry 记录、MemoryOp sealed 接口和 MemoryOpsResult
- 测试：MemoryOpTest

### T3: MemoryStore
- 实现文件 I/O 层
- 单条记忆读写、MEMORY.md 索引管理
- 测试：MemoryStoreTest

### T4: MemoryExtractor
- 实现 LLM 调用和 JSON 解析
- 提取 prompt 构造
- 测试：MemoryExtractorTest

### T5: MemoryFailureCounter + MemoryManager
- 实现 MemoryFailureCounter（计数 + 熔断逻辑）
- 实现 MemoryManager 门面类（异步调用、协调 extractor + store）
- 测试：MemoryFailureCounterTest、MemoryManagerTest

### T6: 系统提示词集成
- 删除 DefaultSections.LONG_TERM_MEMORY 静态占位
- DefaultSections.standard() 新增 memoryContent 参数，非空时在 AGENTS_MD 之后插入 memory section
- App.main 装配阶段读取 MEMORY.md 并传入
- 测试：验证 prompt 包含记忆内容

### T7: ChatSession.recentMessages + ReplLoop 集成
- ChatSession 新增 recentMessages(int n) 方法
- ReplLoop: agent.run() 后触发 extractAsync
- /memory list、/clear、/extract 命令
- 测试：ChatSessionTest（recentMessages）、手动验证

## 11. 未来扩展

- **记忆自动过期**：基于最后更新时间，超过 N 天未引用的记忆标记为过期
- **记忆合并**：相似记忆自动合并（需要语义理解）
- **运行时刷新**：新增记忆立即生效（需要重建 system prompt）
- **记忆导出/导入**：支持备份和迁移
