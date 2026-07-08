# v7.2 会话存档与恢复

## 1. 概述

为 MapleCode 增加会话存档与恢复功能。用户退出时自动将当前对话保存为 JSONL 文件，支持通过 `/resume` 命令恢复历史会话。存档位置 `~/.maplecode/sessions/`，30 天自动过期清理。

### 1.1 非目标

- 会话持久化到数据库（SQLite / H2）
- 跨设备同步
- 会话搜索 / 全文检索
- 会话标签 / 分类
- 增量追加（当前方案为全量写入）

## 2. JSONL 数据格式

每个 session 对应一个 JSONL 文件。每行一条 JSON 记录，对应一条 `ChatMessage`：

```json
{"role":"user","content":[{"type":"text","text":"你好"}]}
{"role":"assistant","content":[{"type":"text","text":"你好！有什么可以帮你的？"}]}
{"role":"assistant","content":[{"type":"text","text":"我来读取文件"},{"type":"tool_use","id":"tu_1","name":"read_file","input":{"path":"a.java"}}]}
{"role":"user","content":[{"type":"tool_result","toolUseId":"tu_1","content":"文件内容...","isError":false}]}
```

### 2.1 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | string | `"user"` 或 `"assistant"` |
| `content` | array | ContentBlock JSON 数组 |

不存储每条消息的时间戳——批量写入时无法还原真实时间间隔。`SessionMeta.lastActivity` 从文件 mtime 获取。

### 2.2 ContentBlock 序列化

与 Anthropic API wire format 对齐：

- **TextBlock**: `{"type":"text","text":"..."}`
- **ToolUseBlock**: `{"type":"tool_use","id":"...","name":"...","input":{...}}`
- **ToolResultBlock**: `{"type":"tool_result","toolUseId":"...","content":"...","isError":false}`

### 2.3 时间信息

不存储每条消息的时间戳。`SessionMeta.lastActivity` 从文件 mtime（`Files.getLastModifiedTime()`）获取，用于 `/resume` 列表的相对时间展示（如 "2h ago"）。

## 3. 包结构

```
com.maplecode.session.archive/
  SessionArchive          — 公开 API 门面
  SessionWriter           — JSONL 写入
  SessionReader           — JSONL 读取 + 容错
  SessionMeta             — record(id, messageCount, lastActivity)
  SessionArchiveException — 运行时异常
```

### 3.1 SessionArchive

```java
public final class SessionArchive {
    private final Path sessionsDir;  // ~/.maplecode/sessions/

    public SessionArchive(Path sessionsDir);

    /**
     * 保存当前 session 到 JSONL 文件。
     * 空 session（size==0）跳过，返回 null。
     * 写入失败时 stderr 警告，返回 null（不抛异常）。
     * @return session ID（文件名不含 .jsonl 后缀）
     */
    public String save(ChatSession session);

    /**
     * 加载指定 session。支持完整 ID 或前缀匹配。
     * 坏行跳过 + stderr 警告；orphan tool_use 截断。
     * @throws SessionArchiveException 如果找不到或完全无法加载
     */
    public List<ChatMessage> load(String idOrPrefix);

    /**
     * 列出最近 N 个 session 元信息，按文件 mtime 倒序。
     */
    public List<SessionMeta> listRecent(int limit);

    /**
     * 删除 mtime 超过 maxAge 的文件，返回删除数量。
     * 启动时调用一次即可。
     */
    public int cleanExpired(Duration maxAge);
}
```

### 3.2 SessionWriter

```java
final class SessionWriter {
    private final ObjectMapper mapper;

    /**
     * 将 ChatSession 写入 target 文件。
     * 每条 ChatMessage 序列化为一行 JSON。
     * @return 写入的消息数
     */
    int write(ChatSession session, Path target);
}
```

序列化逻辑：
1. 遍历 `ChatSession` 的每条 `ChatMessage`
2. 构造 JSON 对象：`role` → `role().name().toLowerCase()`，`content` → 递归序列化 blocks
3. `ObjectMapper.writeValueAsString()` → 单行 JSON
4. `BufferedWriter` 写入，每行 `\n` 结尾

ContentBlock 序列化：
- `TextBlock` → `{"type":"text","text":"..."}`
- `ToolUseBlock` → `{"type":"tool_use","id":"...","name":"...","input":{...}}`
- `ToolResultBlock` → `{"type":"tool_result","toolUseId":"...","content":"...","isError":...}`

### 3.3 SessionReader

```java
final class SessionReader {
    private final ObjectMapper mapper;

    /**
     * 逐行读取 JSONL 文件，反序列化为 ChatMessage 列表。
     * 坏行跳过 + stderr 警告。
     * orphan tool_use 截断。
     */
    List<ChatMessage> read(Path file);
}
```

反序列化逻辑：
1. `BufferedReader` 逐行读取
2. 每行独立 `ObjectMapper.readValue()` → catch `JsonProcessingException` → stderr 警告 + 跳过
3. 解析 `role` 字段 → `ChatMessage.Role.valueOf(role.toUpperCase())`
4. 解析 `content` 数组 → 逐元素构造 `ContentBlock`
5. orphan 检测：如果一条 ASSISTANT 消息末尾是 `ToolUseBlock`，检查后续消息中是否存在对应的 `ToolResultBlock`（在 USER 消息的 blocks 中，按 `toolUseId` 匹配）。如果没有找到匹配的 result，截断该 `ToolUseBlock`。实现方式：先读取所有行到 `List<RawRecord>`，收集所有 tool_use id 集合和所有 tool_result id 集合，差集即为 orphan；遍历时移除 orphan block。

### 3.4 SessionMeta

```java
public record SessionMeta(
    String id,           // session ID（不含 .jsonl）
    int messageCount,    // 消息总数
    Instant lastActivity // 文件 mtime，用于 /resume 列表展示
) {}
```

### 3.5 SessionArchiveException

```java
public class SessionArchiveException extends RuntimeException {
    public SessionArchiveException(String message) { super(message); }
    public SessionArchiveException(String message, Throwable cause) { super(message, cause); }
}
```

## 4. Session ID 生成

文件名格式：`<timestamp>-<shortUUID>.jsonl`

- timestamp: `LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss"))`
- shortUUID: UUID.randomUUID() 前 6 位
- 示例：`2026-07-08T143022-a1b2c3.jsonl`

## 5. REPL 命令集成

### 5.1 /new

```
> /new
Archived current session (8 messages).
New session started.
```

逻辑：
1. 如果 session 非空，调 `sessionArchive.save(session)`
2. `session.clear()`
3. `coord.resetCounter()`（重置 compact 计数器）
4. 打印提示

### 5.2 /resume（无参数）

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

逻辑：
1. 调 `sessionArchive.listRecent(10)`
2. 如果为空，打印 `printer.info("(no archived sessions)")`
3. 格式化列表，用相对时间（基于文件 mtime：2h ago / 5m ago / 1d ago）
4. `reader.readLine("Select [1-N]: ")` 读取用户选择
5. 解析序号：非数字或超出范围 → `printer.error("invalid selection")` 并返回
6. 获取对应 SessionMeta
7. 调 `sessionArchive.load(meta.id())`
8. `session.clear()` + `session.replaceAll(loaded)`
9. 打印恢复提示

### 5.3 /resume <id>

```
> /resume 2026-07-08T21
Cleared current session.
Restored 12 messages from archive.
```

逻辑：
1. 调 `sessionArchive.load(idOrPrefix)`
2. `session.clear()` + `session.replaceAll(loaded)`
3. 打印恢复提示

### 5.4 退出自动存档

在 `ReplLoop.run()` 的退出路径（`/exit` 或 Ctrl+D 导致的 `break`）之前：

```java
// 退出前自动存档
String archived = sessionArchive.save(session);
if (archived != null) {
    printer.info("Session archived: " + archived);
}
```

## 6. App.java 装配

在 `App.main` 中，compact 装配之后新增：

```java
// 会话存档（v7.2）
Path sessionsDir = Paths.get(System.getProperty("user.home"), ".maplecode", "sessions");
SessionArchive sessionArchive = new SessionArchive(sessionsDir);
int cleaned = sessionArchive.cleanExpired(Duration.ofDays(30));
if (cleaned > 0) {
    System.err.println("[session] cleaned " + cleaned + " expired archives");
}
```

`ReplLoop` 构造器新增 `SessionArchive` 参数。

## 7. 错误处理

| 场景 | 处理 |
|------|------|
| sessionsDir 创建失败 | stderr 警告，存档功能降级（save 返回 null） |
| 写入失败（磁盘满/权限） | stderr 警告，不中断退出 |
| 读取坏行 | stderr `[session] WARN: skipped malformed line N`，跳过 |
| orphan tool_use | 截断该 block，stderr `[session] WARN: truncated orphan tool_use at line N` |
| /resume 找不到匹配 | `printer.error("no session found: " + id)` |
| /resume 加载失败 | `printer.error("failed to load session: " + e.getMessage())` |

## 8. 测试计划

### 8.1 SessionWriterTest

- 写入 1 条 user 消息 → 读回验证 JSONL 行数和内容
- 写入混合消息（text + tool_use + tool_result）→ 验证序列化正确
- 空 session → 验证不写入文件

### 8.2 SessionReaderTest

- 正常 JSONL → 反序列化正确
- 坏行（非法 JSON）→ 跳过 + 返回其他正常行
- orphan tool_use（末尾 ToolUseBlock 无后续 ToolResultBlock）→ 截断
- 空文件 → 返回空列表

### 8.3 SessionArchiveTest

- save → load roundtrip（@TempDir）
- listRecent 排序正确（按文件 mtime 倒序）
- cleanExpired 删除过期文件
- load 前缀匹配
- save 空 session 返回 null

## 9. 文件清单

新增文件：
- `src/main/java/com/maplecode/session/archive/SessionArchive.java`
- `src/main/java/com/maplecode/session/archive/SessionWriter.java`
- `src/main/java/com/maplecode/session/archive/SessionReader.java`
- `src/main/java/com/maplecode/session/archive/SessionMeta.java`
- `src/main/java/com/maplecode/session/archive/SessionArchiveException.java`
- `src/test/java/com/maplecode/session/archive/SessionWriterTest.java`
- `src/test/java/com/maplecode/session/archive/SessionReaderTest.java`
- `src/test/java/com/maplecode/session/archive/SessionArchiveTest.java`

修改文件：
- `src/main/java/com/maplecode/ui/ReplLoop.java` — 新增 /new、/resume 命令 + 退出存档
- `src/main/java/com/maplecode/App.java` — 装配 SessionArchive
