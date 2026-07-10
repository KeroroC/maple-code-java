会话管理与归档是 MapleCode 的核心持久化机制，负责在用户退出时自动保存对话历史，并支持通过 `/resume` 命令恢复历史会话。该系统采用 JSONL 格式存储会话数据，与 Anthropic API 的 wire format 保持对齐，确保数据的可移植性和一致性。

## 架构概述

会话管理系统由四个核心组件构成：**ChatSession** 作为运行时会话容器，**SessionArchive** 作为存档操作的门面，**SessionWriter** 负责序列化写入，**SessionReader** 负责反序列化读取。整个系统遵循单一职责原则，通过清晰的接口边界实现松耦合。

```mermaid
graph TB
    subgraph "运行时"
        CS[ChatSession<br/>消息容器]
        RL[ReplLoop<br/>REPL主循环]
        AC[AgentLoop<br/>Agent循环]
    end
    
    subgraph "存档层"
        SA[SessionArchive<br/>门面API]
        SW[SessionWriter<br/>JSONL写入]
        SR[SessionReader<br/>JSONL读取]
        SM[SessionMeta<br/>元信息]
    end
    
    subgraph "存储"
        FS[~/.maplecode/sessions/*.jsonl]
    end
    
    CS -->|消息列表| SA
    SA -->|save()| SW
    SA -->|load()| SR
    SW -->|原子写入| FS
    SR -->|容错读取| FS
    SA -->|listRecent()| SM
    
    RL -->|/new /resume| SA
    RL -->|退出自动存档| SA
    AC -->|消息追加| CS
```

## 核心数据结构

### ChatSession：运行时会话容器

ChatSession 是会话管理的核心运行时类，采用 **append-only** 设计模式，维护当前会话的消息列表。它提供了类型安全的 API 来添加用户和助手消息，并支持压缩系统的批量替换操作。

**关键特性**：
- **不可变消息访问**：`messages()` 返回不可变副本，防止外部修改
- **防御性拷贝**：`appendUser()` 和 `appendAssistant()` 复制传入的 blocks 列表
- **批量替换**：`replaceAll()` 用于压缩系统和会话恢复
- **上下文窗口支持**：`recentMessages(n)` 获取最近 n 条消息

```java
// 消息添加示例
ChatSession session = new ChatSession();
session.appendUserText("你好");
session.appendAssistant(List.of(new ContentBlock.TextBlock("你好！有什么可以帮你的？")));

// 会话恢复示例（从存档加载）
List<ChatMessage> loaded = archive.load(sessionId);
session.replaceAll(loaded);
```

Sources: [ChatSession.java](src/main/java/com/maplecode/session/ChatSession.java#L1-L90)

### JSONL 存储格式

每个会话对应一个 JSONL 文件，每行一条 JSON 记录，对应一条 `ChatMessage`。这种格式与 Anthropic API 的 wire format 完全对齐，确保数据的可移植性。

**格式规范**：
- **文件扩展名**：`.jsonl`
- **编码**：UTF-8
- **行分隔符**：`\n`
- **每行结构**：`{"role":"user|assistant","content":[...]}`

**ContentBlock 序列化映射**：

| ContentBlock 类型 | JSON 结构 | 示例 |
|-------------------|-----------|------|
| `TextBlock` | `{"type":"text","text":"..."}` | `{"type":"text","text":"你好"}` |
| `ToolUseBlock` | `{"type":"tool_use","id":"...","name":"...","input":{...}}` | `{"type":"tool_use","id":"tu_1","name":"read_file","input":{"path":"a.java"}}` |
| `ToolResultBlock` | `{"type":"tool_result","toolUseId":"...","content":"...","isError":false}` | `{"type":"tool_result","toolUseId":"tu_1","content":"文件内容...","isError":false}` |

**时间信息处理**：不存储每条消息的时间戳，因为批量写入时无法还原真实时间间隔。`SessionMeta.lastActivity` 从文件 mtime（`Files.getLastModifiedTime()`）获取，用于 `/resume` 列表的相对时间展示。

Sources: [SessionWriter.java](src/main/java/com/maplecode/session/archive/SessionWriter.java#L1-L68), [SessionReader.java](src/main/java/com/maplecode/session/archive/SessionReader.java#L1-L114)

## 存档系统实现

### SessionArchive：门面 API

SessionArchive 是存档系统的唯一公开入口，封装了所有存档操作的复杂性。它采用门面模式，提供简洁的 API 给上层调用。

**核心方法**：

| 方法 | 功能 | 返回值 | 异常处理 |
|------|------|--------|----------|
| `save(ChatSession)` | 保存会话到 JSONL 文件 | session ID 或 null（空会话/失败） | 写入失败时 stderr 警告，返回 null |
| `load(String idOrPrefix)` | 加载指定会话（支持前缀匹配） | `List<ChatMessage>` | 找不到或无法加载时抛出 `SessionArchiveException` |
| `listRecent(int limit)` | 列出最近 N 个会话元信息 | `List<SessionMeta>` | 按文件 mtime 倒序排列 |
| `cleanExpired(Duration maxAge)` | 删除过期文件 | 删除数量 | 启动时调用一次即可 |

**Session ID 生成规则**：
```
<timestamp>-<shortUUID>.jsonl
```
- timestamp: `LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss"))`
- shortUUID: UUID.randomUUID() 前 6 位
- 示例：`2026-07-08T143022-a1b2c3.jsonl`

**前缀匹配机制**：`load()` 方法支持完整 ID 或前缀匹配。如果前缀匹配多个会话，抛出 `SessionArchiveException`；如果无匹配，返回 null。

Sources: [SessionArchive.java](src/main/java/com/maplecode/session/archive/SessionArchive.java#L1-L129)

### SessionWriter：序列化写入

SessionWriter 负责将 ChatSession 序列化为 JSONL 格式并写入文件。它采用原子写入策略，确保数据一致性。

**写入流程**：
1. 遍历 ChatSession 的每条 ChatMessage
2. 构造 JSON 对象：`role` → `role().name().toLowerCase()`，`content` → 递归序列化 blocks
3. `ObjectMapper.writeValueAsString()` → 单行 JSON
4. 使用 `IoUtil.atomicWrite()` 原子写入

**原子写入实现**：
```java
// 先写临时文件，再原子移动到目标路径
Path tmp = Files.createTempFile(dir, ".maplecode-", ".tmp");
Files.writeString(tmp, content, StandardCharsets.UTF_8);
Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```

这种策略确保进程崩溃时不会损坏目标文件（要么是旧内容，要么是完整新内容）。

Sources: [SessionWriter.java](src/main/java/com/maplecode/session/archive/SessionWriter.java#L1-L68), [IoUtil.java](src/main/java/com/maplecode/util/IoUtil.java#L1-L36)

### SessionReader：容错读取

SessionReader 负责从 JSONL 文件读取并反序列化为 ChatMessage 列表，具备强大的容错能力。

**容错机制**：

| 场景 | 处理方式 | 输出 |
|------|----------|------|
| 坏行（非法 JSON） | 跳过 + stderr 警告 | `[session] WARN: skipped malformed line N` |
| orphan tool_use | 截断该 block | `[session] WARN: truncated orphan tool_use <id>` |
| orphan tool_result | 截断该 block | `[session] WARN: truncated orphan tool_result <toolUseId>` |
| 空文件 | 返回空列表 | - |

**orphan 检测算法**：
1. 读取所有行到内存
2. 收集所有 `tool_use` ID 和 `tool_result` 的 `toolUseId`
3. 计算正向孤儿（tool_use 无对应 tool_result）和反向孤儿（tool_result 无对应 tool_use）
4. 遍历消息列表，移除孤儿 block

```java
// orphan 检测示例
Set<String> orphanToolUseIds = new HashSet<>(toolUseIds);
orphanToolUseIds.removeAll(toolResultIds);
Set<String> orphanToolResultIds = new HashSet<>(toolResultIds);
orphanToolResultIds.removeAll(toolUseIds);
```

Sources: [SessionReader.java](src/main/java/com/maplecode/session/archive/SessionReader.java#L1-L114)

### SessionMeta：会话元信息

SessionMeta 是一个 record 类型，封装会话的元数据信息，用于 `/resume` 命令的会话列表展示。

```java
public record SessionMeta(
    String id,           // session ID（不含 .jsonl）
    int messageCount,    // 消息总数
    Instant lastActivity // 文件 mtime，用于相对时间展示
) {}
```

**时间展示格式**：
- `< 60s` → `just now`
- `< 60m` → `Xm ago`
- `< 24h` → `Xh ago`
- `≥ 24h` → `Xd ago`

Sources: [SessionMeta.java](src/main/java/com/maplecode/session/archive/SessionMeta.java#L1-L10)

## REPL 命令集成

### /new 命令：归档并清空

`/new` 命令用于归档当前会话并开始新会话，是会话管理的主要操作入口。

**执行流程**：
1. 如果 session 非空，调用 `sessionArchive.save(session)`
2. `session.clear()` 清空当前会话
3. `coord.resetCounter()` 重置压缩计数器
4. 打印提示信息

**用户交互**：
```
> /new
Archived current session (8 messages).
New session started.
```

**错误处理**：如果存档失败（磁盘满/权限），stderr 警告但不中断操作。

Sources: [NewCommand.java](src/main/java/com/maplecode/command/NewCommand.java#L1-L33)

### /resume 命令：加载历史会话

`/resume` 命令支持两种模式：交互式选择和直接指定 ID。

**交互式模式（无参数）**：
```
> /resume
Recent sessions:
  [1] 2026-07-08T143022-a1b2c3 (3 messages, 2h ago)
  [2] 2026-07-08T210015-f9e8d7 (12 messages, 5m ago)
  [3] 2026-07-07T091530-c4d5e6 (5 messages, 1d ago)
Select [1-3]: 2
Cleared current session.
Restored 12 messages from archive.
```

**直接指定模式**：
```
> /resume 2026-07-08T21
Cleared current session.
Restored 12 messages from archive.
```

**执行流程**：
1. 调用 `sessionArchive.listRecent(10)` 获取最近 10 个会话
2. 格式化列表，显示相对时间
3. 读取用户选择（支持序号或完整 ID）
4. 调用 `sessionArchive.load()` 加载会话
5. `session.clear()` + `session.replaceAll(loaded)` 替换当前会话
6. 打印恢复提示

**错误处理**：
- 无历史会话：`(no archived sessions)`
- 无效选择：`invalid selection`
- 找不到会话：`no session found: <id>`
- 加载失败：`failed to load session: <message>`

Sources: [ResumeCommand.java](src/main/java/com/maplecode/command/ResumeCommand.java#L1-L71)

### 退出自动存档

在 `ReplLoop.run()` 的退出路径（`/exit` 或 Ctrl+D 导致的 `break`）之前，系统自动执行会话存档：

```java
// 退出前自动存档
String archived = sessionArchive.save(session);
if (archived != null) {
    printer.info("Session archived: " + archived);
}
```

**触发条件**：
- 用户输入 `/exit` 命令
- 用户按 Ctrl+D（EOF）
- 进程接收到中断信号

**降级策略**：如果存档失败，stderr 警告但不阻止退出。

Sources: [ReplLoop.java](src/main/java/com/maplecode/ui/ReplLoop.java#L231-L235)

## 系统装配与配置

### App.java 装配

会话存档系统在应用启动时完成装配，位于压缩系统装配之后：

```java
// 会话存档（v7.2）
Path sessionsDir = Paths.get(System.getProperty("user.home"), ".maplecode", "sessions");
SessionArchive sessionArchive = new SessionArchive(sessionsDir);
int cleaned = sessionArchive.cleanExpired(Duration.ofDays(30));
if (cleaned > 0) {
    System.err.println("[session] cleaned " + cleaned + " expired archives");
}
```

**存储位置**：`~/.maplecode/sessions/`

**过期策略**：30 天自动清理，启动时执行一次。

**ReplLoop 构造器集成**：SessionArchive 作为可选参数传入 ReplLoop，支持功能降级（null 表示禁用）。

Sources: [App.java](src/main/java/com/maplecode/App.java#L144-L150)

### 命令注册

SessionArchive 通过命令注册表注入到 REPL 命令系统：

```java
static CommandRegistry createCommandRegistry(
        ToolRegistry tools, SessionArchive archive,
        CompactCoordinator coord, MemoryManager memoryManager) {
    CommandRegistry commands = new CommandRegistry();
    // ... 其他命令
    commands.register(new NewCommand(archive, coord));
    commands.register(new ResumeCommand(archive));
    // ...
    return commands;
}
```

**依赖注入模式**：NewCommand 和 ResumeCommand 通过构造器接收 SessionArchive 实例，实现松耦合。

Sources: [App.java](src/main/java/com/maplecode/App.java#L223-L241)

## 错误处理与容错

会话管理系统采用防御性编程策略，确保在各种异常情况下都能优雅降级。

### 错误处理矩阵

| 场景 | 处理方式 | 影响范围 |
|------|----------|----------|
| sessionsDir 创建失败 | stderr 警告，存档功能降级 | save() 返回 null |
| 写入失败（磁盘满/权限） | stderr 警告，不中断退出 | 会话数据丢失 |
| 读取坏行 | stderr `[session] WARN: skipped malformed line N`，跳过 | 部分消息丢失 |
| orphan tool_use | 截断该 block，stderr 警告 | 工具调用链断裂 |
| /resume 找不到匹配 | `printer.error("no session found: " + id)` | 操作失败 |
| /resume 加载失败 | `printer.error("failed to load session: " + e.getMessage())` | 操作失败 |
| 前缀匹配多个会话 | 抛出 `SessionArchiveException` | 操作失败 |

### 异常类设计

```java
public class SessionArchiveException extends RuntimeException {
    public SessionArchiveException(String message) { super(message); }
    public SessionArchiveException(String message, Throwable cause) { super(message, cause); }
}
```

**设计选择**：继承 RuntimeException 而非 Exception，因为存档失败通常是运行时环境问题（磁盘满、权限），而非编程错误。

Sources: [SessionArchiveException.java](src/main/java/com/maplecode/session/archive/SessionArchiveException.java#L1-L7)

## 与压缩系统的交互

会话管理与压缩系统（CompactCoordinator）存在密切交互，主要体现在两个方面：

### 1. /new 命令重置压缩计数器

```java
public void execute(String args, CommandContext ctx) {
    if (archive != null) {
        archive.save(ctx.getSession());
    }
    ctx.getSession().clear();
    if (coord != null) {
        coord.recordUsage(null);  // 重置压缩计数器
    }
    ctx.updateStatusBar();
}
```

**设计意图**：新会话开始时，压缩系统的失败计数器应重置，允许压缩重试。

### 2. 会话恢复后压缩状态

当通过 `/resume` 恢复历史会话时，压缩系统的状态需要重新评估。当前实现中，压缩计数器不会自动重置，这可能导致恢复的会话立即触发压缩检查。

**建议**：在恢复会话后调用 `coord.resetCounter()` 以确保压缩系统状态一致。

Sources: [NewCommand.java](src/main/java/com/maplecode/command/NewCommand.java#L22-L31), [CompactCoordinator.java](src/main/java/com/maplecode/compact/CompactCoordinator.java#L48-L53)

## 测试策略

会话管理系统的测试覆盖了三个层次：单元测试、集成测试和边界条件测试。

### 测试矩阵

| 测试类 | 覆盖范围 | 关键断言 |
|--------|----------|----------|
| `SessionWriterTest` | 序列化写入 | JSONL 格式正确、ContentBlock 映射正确、空 session 不写入 |
| `SessionReaderTest` | 反序列化读取 | 正常 JSONL 解析、坏行跳过、orphan 截断、空文件处理 |
| `SessionArchiveTest` | 门面 API | save/load roundtrip、listRecent 排序、cleanExpired 删除、前缀匹配 |
| `ChatSessionReplaceAllTest` | 批量替换 | 防御性拷贝、clear 后恢复、append 后继续工作 |

### 测试数据示例

```java
// roundtrip 测试
ChatSession session = new ChatSession();
session.appendUserText("你好");
session.appendAssistant(List.of(new ContentBlock.TextBlock("你好！")));

String id = archive.save(session);
List<ChatMessage> loaded = archive.load(id);

assertEquals(2, loaded.size());
assertEquals("你好", ((ContentBlock.TextBlock) loaded.get(0).blocks().get(0)).text());
assertEquals("你好！", ((ContentBlock.TextBlock) loaded.get(1).blocks().get(0)).text());
```

Sources: [SessionArchiveTest.java](src/test/java/com/maplecode/session/archive/SessionArchiveTest.java#L1-L110), [SessionWriterTest.java](src/test/java/com/maplecode/session/archive/SessionWriterTest.java), [SessionReaderTest.java](src/test/java/com/maplecode/session/archive/SessionReaderTest.java)

## 性能与资源管理

### 写入性能

**优化策略**：
- **StringBuilder 缓冲**：所有 JSON 行先写入 StringBuilder，最后一次性写入文件
- **原子写入**：避免多次 I/O 操作，减少文件系统压力
- **无时间戳**：不存储每条消息的时间戳，减少存储开销

### 读取性能

**优化策略**：
- **流式读取**：BufferedReader 逐行读取，避免大文件一次性加载
- **延迟解析**：坏行跳过时不中断整个读取过程
- **内存优化**：orphan 检测在读取完成后一次性处理

### 存储优化

**策略**：
- **过期清理**：30 天自动清理，避免存储无限增长
- **前缀匹配**：支持短 ID 匹配，减少用户输入负担
- **相对时间**：从 mtime 计算，无需额外存储时间戳

Sources: [SessionWriter.java](src/main/java/com/maplecode/session/archive/SessionWriter.java#L20-L35), [SessionReader.java](src/main/java/com/maplecode/session/archive/SessionReader.java#L21-L41)

## 最佳实践

### 1. 会话命名规范

Session ID 自动生成，格式为 `<timestamp>-<shortUUID>`，建议：
- 不要手动修改文件名
- 使用前缀匹配时，确保前缀足够长以避免歧义
- 示例：`2026-07-08T14` 而非 `20`

### 2. 存档时机选择

**自动存档**：退出时自动执行，适合大多数场景
**手动存档**：`/new` 命令，适合需要明确标记会话边界的场景

**建议**：
- 长对话中途使用 `/new` 分割会话
- 重要工作完成后手动存档
- 不要依赖自动存档作为唯一备份

### 3. 恢复策略

**交互式恢复**：适合不确定会话 ID 的场景
**直接恢复**：适合已知会话 ID 的场景

**建议**：
- 使用 `/resume` 查看最近会话列表
- 使用完整 ID 避免歧义
- 恢复后检查会话状态是否一致

### 4. 存储管理

**监控**：定期检查 `~/.maplecode/sessions/` 目录大小
**清理**：依赖自动清理（30 天），或手动删除不需要的会话文件
**备份**：重要会话可手动备份 `.jsonl` 文件

## 未来演进方向

基于当前设计文档的"非目标"列表，以下是可能的未来演进方向：

### 1. 数据库持久化

**当前限制**：JSONL 文件不适合复杂查询
**可能方案**：SQLite 或 H2 嵌入式数据库
**优势**：支持全文搜索、标签分类、复杂查询

### 2. 增量追加

**当前限制**：全量写入，大文件性能问题
**可能方案**：追加模式 + 周期性压缩
**优势**：减少 I/O 开销，支持实时存档

### 3. 跨设备同步

**当前限制**：本地存储，无法跨设备访问
**可能方案**：云存储集成或端到端加密同步
**优势**：多设备协作，数据备份

### 4. 会话搜索

**当前限制**：只能按时间排序，无法内容搜索
**可能方案**：全文索引或向量搜索
**优势**：快速定位历史对话

### 5. 标签与分类

**当前限制**：纯时间线组织，缺乏语义分类
**可能方案**：自动标签或手动分类
**优势**：更好的会话组织和检索

## 相关文档

- [上下文管理与压缩](17-shang-xia-wen-guan-li-yu-ya-suo) - 了解压缩系统如何与会话管理交互
- [长期记忆系统](18-chang-qi-ji-yi-xi-tong) - 了解长期记忆与会话存档的区别
- [Agent Loop 实现](16-agent-loop-shi-xian) - 了解消息如何产生和追加到会话
- [命令框架与 REPL](20-ming-ling-kuang-jia-yu-repl) - 了解命令系统的整体架构