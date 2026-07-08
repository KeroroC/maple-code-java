# v7.2 会话存档与恢复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现会话 JSONL 存档与恢复，支持 /new、/resume 命令和退出自动存档。

**Architecture:** 新建 `com.maplecode.session.archive` 包，包含 SessionWriter（写入）、SessionReader（读取+容错）、SessionArchive（门面）。ReplLoop 在 /new、/resume、/exit 时调用 SessionArchive。

**Tech Stack:** Java 21, Jackson ObjectMapper, JUnit 5, @TempDir

---

## 文件清单

| 文件 | 职责 |
|------|------|
| `src/main/java/com/maplecode/session/archive/SessionArchiveException.java` | 运行时异常 |
| `src/main/java/com/maplecode/session/archive/SessionMeta.java` | record(id, messageCount, lastActivity) |
| `src/main/java/com/maplecode/session/archive/SessionWriter.java` | ChatSession → JSONL 文件 |
| `src/main/java/com/maplecode/session/archive/SessionReader.java` | JSONL 文件 → List\<ChatMessage\> |
| `src/main/java/com/maplecode/session/archive/SessionArchive.java` | 公开 API 门面 |
| `src/test/java/com/maplecode/session/archive/SessionWriterTest.java` | Writer 测试 |
| `src/test/java/com/maplecode/session/archive/SessionReaderTest.java` | Reader 测试 |
| `src/test/java/com/maplecode/session/archive/SessionArchiveTest.java` | Archive 集成测试 |
| `src/main/java/com/maplecode/ui/ReplLoop.java` | 修改：新增 /new、/resume、退出存档 |
| `src/main/java/com/maplecode/App.java` | 修改：装配 SessionArchive |

---

### Task 1: SessionArchiveException + SessionMeta

**Files:**
- Create: `src/main/java/com/maplecode/session/archive/SessionArchiveException.java`
- Create: `src/main/java/com/maplecode/session/archive/SessionMeta.java`

- [ ] **Step 1: 创建 SessionArchiveException**

```java
package com.maplecode.session.archive;

public class SessionArchiveException extends RuntimeException {
    public SessionArchiveException(String message) { super(message); }
    public SessionArchiveException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: 创建 SessionMeta**

```java
package com.maplecode.session.archive;

import java.time.Instant;

public record SessionMeta(
    String id,
    int messageCount,
    Instant lastActivity
) {}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/session/archive/SessionArchiveException.java \
        src/main/java/com/maplecode/session/archive/SessionMeta.java
git commit -m "feat(session-archive): 添加 SessionArchiveException + SessionMeta record"
```

---

### Task 2: SessionWriter — JSONL 写入

**Files:**
- Create: `src/main/java/com/maplecode/session/archive/SessionWriter.java`
- Create: `src/test/java/com/maplecode/session/archive/SessionWriterTest.java`

- [ ] **Step 1: 写测试 — 单条 user 消息 roundtrip**

```java
package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writeSingleUserMessage(@TempDir Path tmp) throws Exception {
        ChatSession session = new ChatSession();
        session.appendUserText("你好");
        Path target = tmp.resolve("test.jsonl");

        new SessionWriter().write(session, target);

        List<String> lines = Files.readAllLines(target);
        assertEquals(1, lines.size());
        JsonNode root = JSON.readTree(lines.get(0));
        assertEquals("user", root.get("role").asText());
        JsonNode content = root.get("content");
        assertTrue(content.isArray());
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("你好", content.get(0).get("text").asText());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionWriterTest#writeSingleUserMessage -q`
Expected: FAIL — SessionWriter 不存在

- [ ] **Step 3: 实现 SessionWriter**

```java
package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SessionWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    int write(ChatSession session, Path target) {
        try (BufferedWriter w = Files.newBufferedWriter(target)) {
            for (int i = 0; i < session.size(); i++) {
                ChatMessage msg = session.get(i);
                ObjectNode node = JSON.createObjectNode();
                node.put("role", msg.role().name().toLowerCase());
                node.set("content", serializeBlocks(msg.blocks()));
                w.write(JSON.writeValueAsString(node));
                w.newLine();
            }
            return session.size();
        } catch (IOException e) {
            throw new SessionArchiveException("write failed: " + target, e);
        }
    }

    private ArrayNode serializeBlocks(java.util.List<ContentBlock> blocks) {
        ArrayNode arr = JSON.createArrayNode();
        for (ContentBlock block : blocks) {
            arr.add(serializeBlock(block));
        }
        return arr;
    }

    private ObjectNode serializeBlock(ContentBlock block) {
        ObjectNode node = JSON.createObjectNode();
        switch (block) {
            case ContentBlock.TextBlock t -> {
                node.put("type", "text");
                node.put("text", t.text());
            }
            case ContentBlock.ToolUseBlock t -> {
                node.put("type", "tool_use");
                node.put("id", t.id());
                node.put("name", t.name());
                node.set("input", t.input());
            }
            case ContentBlock.ToolResultBlock t -> {
                node.put("type", "tool_result");
                node.put("toolUseId", t.toolUseId());
                node.put("content", t.content());
                node.put("isError", t.isError());
            }
        }
        return node;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionWriterTest#writeSingleUserMessage -q`
Expected: PASS

- [ ] **Step 5: 写测试 — 混合消息（text + tool_use + tool_result）**

在 SessionWriterTest 中追加：

```java
@Test
void writeMixedMessages(@TempDir Path tmp) throws Exception {
    ChatSession session = new ChatSession();
    session.appendUserText("读取文件");
    // assistant: text + tool_use
    session.appendAssistant(List.of(
        new ContentBlock.TextBlock("我来读取"),
        new ContentBlock.ToolUseBlock("tu_1", "read_file",
            JSON.createObjectNode().put("path", "a.java"))
    ));
    // user: tool_result
    session.appendUser(List.of(
        new ContentBlock.ToolResultBlock("tu_1", "文件内容", false)
    ));
    Path target = tmp.resolve("test.jsonl");

    new SessionWriter().write(session, target);

    List<String> lines = Files.readAllLines(target);
    assertEquals(3, lines.size());

    // 第 2 行：assistant + text + tool_use
    JsonNode line2 = JSON.readTree(lines.get(1));
    assertEquals("assistant", line2.get("role").asText());
    assertEquals(2, line2.get("content").size());
    assertEquals("text", line2.get("content").get(0).get("type").asText());
    assertEquals("tool_use", line2.get("content").get(1).get("type").asText());
    assertEquals("tu_1", line2.get("content").get(1).get("id").asText());

    // 第 3 行：user + tool_result
    JsonNode line3 = JSON.readTree(lines.get(2));
    assertEquals("user", line3.get("role").asText());
    assertEquals("tool_result", line3.get("content").get(0).get("type").asText());
    assertEquals("tu_1", line3.get("content").get(0).get("toolUseId").asText());
    assertFalse(line3.get("content").get(0).get("isError").asBoolean());
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=SessionWriterTest -q`
Expected: PASS（两个测试都通过）

- [ ] **Step 7: 写测试 — 空 session 不写入文件**

```java
@Test
void writeEmptySessionReturnsZero(@TempDir Path tmp) throws Exception {
    ChatSession session = new ChatSession();
    Path target = tmp.resolve("empty.jsonl");

    int count = new SessionWriter().write(session, target);

    assertEquals(0, count);
    // 文件存在但为空（0 行）
    assertTrue(Files.exists(target));
    assertEquals(0, Files.size(target));
}
```

- [ ] **Step 8: 运行全部 SessionWriterTest**

Run: `mvn test -Dtest=SessionWriterTest -q`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/maplecode/session/archive/SessionWriter.java \
        src/test/java/com/maplecode/session/archive/SessionWriterTest.java
git commit -m "feat(session-archive): 添加 SessionWriter JSONL 写入 + 测试"
```

---

### Task 3: SessionReader — JSONL 读取

**Files:**
- Create: `src/main/java/com/maplecode/session/archive/SessionReader.java`
- Create: `src/test/java/com/maplecode/session/archive/SessionReaderTest.java`

- [ ] **Step 1: 写测试 — 正常 JSONL 反序列化**

```java
package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionReaderTest {

    @Test
    void readNormalJsonl(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.jsonl");
        Files.write(file, List.of(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"你好！\"}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).role());
        assertEquals("你好", ((ContentBlock.TextBlock) messages.get(0).blocks().get(0)).text());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(1).role());
        assertEquals("你好！", ((ContentBlock.TextBlock) messages.get(1).blocks().get(0)).text());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionReaderTest#readNormalJsonl -q`
Expected: FAIL — SessionReader 不存在

- [ ] **Step 3: 实现 SessionReader**

```java
package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SessionReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    List<ChatMessage> read(Path file) {
        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;
                try {
                    JsonNode root = JSON.readTree(line);
                    messages.add(parseMessage(root));
                } catch (Exception e) {
                    System.err.println("[session] WARN: skipped malformed line " + lineNum);
                }
            }
        } catch (IOException e) {
            throw new SessionArchiveException("read failed: " + file, e);
        }
        truncateOrphanToolUse(messages);
        return messages;
    }

    private ChatMessage parseMessage(JsonNode root) {
        String roleStr = root.get("role").asText();
        ChatMessage.Role role = ChatMessage.Role.valueOf(roleStr.toUpperCase());
        List<ContentBlock> blocks = new ArrayList<>();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                blocks.add(parseBlock(item));
            }
        }
        return new ChatMessage(role, List.copyOf(blocks));
    }

    private ContentBlock parseBlock(JsonNode node) {
        String type = node.get("type").asText();
        return switch (type) {
            case "text" -> new ContentBlock.TextBlock(node.get("text").asText());
            case "tool_use" -> new ContentBlock.ToolUseBlock(
                node.get("id").asText(),
                node.get("name").asText(),
                node.get("input"));
            case "tool_result" -> new ContentBlock.ToolResultBlock(
                node.get("toolUseId").asText(),
                node.get("content").asText(),
                node.has("isError") && node.get("isError").asBoolean());
            default -> throw new SessionArchiveException("unknown block type: " + type);
        };
    }

    private void truncateOrphanToolUse(List<ChatMessage> messages) {
        // 收集所有 tool_use id 和 tool_result id
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (ChatMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                switch (block) {
                    case ContentBlock.ToolUseBlock t -> toolUseIds.add(t.id());
                    case ContentBlock.ToolResultBlock t -> toolResultIds.add(t.toolUseId());
                    default -> {}
                }
            }
        }
        // 差集 = orphan ids
        Set<String> orphanIds = new HashSet<>(toolUseIds);
        orphanIds.removeAll(toolResultIds);
        if (orphanIds.isEmpty()) return;

        // 从 ASSISTANT 消息中移除 orphan ToolUseBlock
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg.role() != ChatMessage.Role.ASSISTANT) continue;
            List<ContentBlock> filtered = new ArrayList<>();
            boolean changed = false;
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ContentBlock.ToolUseBlock t && orphanIds.contains(t.id())) {
                    changed = true;
                    System.err.println("[session] WARN: truncated orphan tool_use " + t.id());
                } else {
                    filtered.add(block);
                }
            }
            if (changed) {
                messages.set(i, new ChatMessage(msg.role(), List.copyOf(filtered)));
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionReaderTest#readNormalJsonl -q`
Expected: PASS

- [ ] **Step 5: 写测试 — 坏行跳过**

```java
@Test
void skipMalformedLines(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("bad.jsonl");
    Files.write(file, List.of(
        "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}",
        "this is not valid json",
        "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"fine\"}]}"
    ));

    List<ChatMessage> messages = new SessionReader().read(file);

    assertEquals(2, messages.size());
    assertEquals("ok", ((ContentBlock.TextBlock) messages.get(0).blocks().get(0)).text());
    assertEquals("fine", ((ContentBlock.TextBlock) messages.get(1).blocks().get(0)).text());
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=SessionReaderTest -q`
Expected: PASS

- [ ] **Step 7: 写测试 — orphan tool_use 截断**

```java
@Test
void truncateOrphanToolUse(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("orphan.jsonl");
    Files.write(file, List.of(
        "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"调用工具\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.java\"}}]}",
        "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"没有对应的 tool_result\"}]}"
    ));

    List<ChatMessage> messages = new SessionReader().read(file);

    assertEquals(2, messages.size());
    // 第 1 条消息：tool_use 被截断，只剩 text
    assertEquals(1, messages.get(0).blocks().size());
    assertInstanceOf(ContentBlock.TextBlock.class, messages.get(0).blocks().get(0));
}
```

- [ ] **Step 8: 写测试 — 空文件返回空列表**

```java
@Test
void readEmptyFileReturnsEmptyList(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("empty.jsonl");
    Files.createFile(file);

    List<ChatMessage> messages = new SessionReader().read(file);

    assertTrue(messages.isEmpty());
}
```

- [ ] **Step 9: 运行全部 SessionReaderTest**

Run: `mvn test -Dtest=SessionReaderTest -q`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/maplecode/session/archive/SessionReader.java \
        src/test/java/com/maplecode/session/archive/SessionReaderTest.java
git commit -m "feat(session-archive): 添加 SessionReader JSONL 读取 + 容错 + 测试"
```

---

### Task 4: SessionArchive 门面

**Files:**
- Create: `src/main/java/com/maplecode/session/archive/SessionArchive.java`
- Create: `src/test/java/com/maplecode/session/archive/SessionArchiveTest.java`

- [ ] **Step 1: 写测试 — save → load roundtrip**

```java
package com.maplecode.session.archive;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionArchiveTest {

    @Test
    void saveThenLoadRoundtrip(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession session = new ChatSession();
        session.appendUserText("你好");
        session.appendAssistantText("你好！");

        String id = archive.save(session);
        assertNotNull(id);

        List<ChatMessage> loaded = archive.load(id);
        assertEquals(2, loaded.size());
        assertEquals("你好", ((ContentBlock.TextBlock) loaded.get(0).blocks().get(0)).text());
        assertEquals("你好！", ((ContentBlock.TextBlock) loaded.get(1).blocks().get(0)).text());
    }
}
```

注意：`ChatSession` 目前没有 `appendAssistantText` 方法。需要在 ChatSession 中添加，或者测试用 `appendAssistant(List.of(new ContentBlock.TextBlock("你好！")))`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionArchiveTest#saveThenLoadRoundtrip -q`
Expected: FAIL — SessionArchive 不存在

- [ ] **Step 3: 实现 SessionArchive**

```java
package com.maplecode.session.archive;

import com.maplecode.session.ChatSession;
import com.maplecode.provider.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class SessionArchive {

    private final Path sessionsDir;
    private final SessionWriter writer = new SessionWriter();
    private final SessionReader reader = new SessionReader();

    public SessionArchive(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            System.err.println("[session] WARN: cannot create sessions dir: " + sessionsDir);
        }
    }

    public String save(ChatSession session) {
        if (session.size() == 0) return null;
        String id = generateId();
        Path target = sessionsDir.resolve(id + ".jsonl");
        try {
            writer.write(session, target);
            return id;
        } catch (Exception e) {
            System.err.println("[session] WARN: save failed: " + e.getMessage());
            return null;
        }
    }

    public List<ChatMessage> load(String idOrPrefix) {
        Path file = resolveFile(idOrPrefix);
        if (file == null) {
            throw new SessionArchiveException("no session found: " + idOrPrefix);
        }
        return reader.read(file);
    }

    public List<SessionMeta> listRecent(int limit) {
        try {
            return Files.list(sessionsDir)
                .filter(p -> p.toString().endsWith(".jsonl"))
                .sorted(Comparator.comparing(p -> {
                    try { return Files.getLastModifiedTime(p).toInstant(); }
                    catch (IOException e) { return Instant.EPOCH; }
                }).reversed())
                .limit(limit)
                .map(p -> {
                    String fileName = p.getFileName().toString();
                    String id = fileName.substring(0, fileName.length() - 6); // strip .jsonl
                    try {
                        long lineCount = Files.lines(p).filter(l -> !l.isBlank()).count();
                        Instant mtime = Files.getLastModifiedTime(p).toInstant();
                        return new SessionMeta(id, (int) lineCount, mtime);
                    } catch (IOException e) {
                        return new SessionMeta(id, 0, Instant.EPOCH);
                    }
                })
                .toList();
        } catch (IOException e) {
            System.err.println("[session] WARN: listRecent failed: " + e.getMessage());
            return List.of();
        }
    }

    public int cleanExpired(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int count = 0;
        try {
            var files = Files.list(sessionsDir)
                .filter(p -> p.toString().endsWith(".jsonl"))
                .toList();
            for (Path f : files) {
                try {
                    Instant mtime = Files.getLastModifiedTime(f).toInstant();
                    if (mtime.isBefore(cutoff)) {
                        Files.delete(f);
                        count++;
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            System.err.println("[session] WARN: cleanExpired failed: " + e.getMessage());
        }
        return count;
    }

    private Path resolveFile(String idOrPrefix) {
        // 精确匹配
        Path exact = sessionsDir.resolve(idOrPrefix + ".jsonl");
        if (Files.exists(exact)) return exact;
        // 前缀匹配
        try {
            return Files.list(sessionsDir)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jsonl") && name.startsWith(idOrPrefix);
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String generateId() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 6);
        return ts + "-" + uuid;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionArchiveTest#saveThenLoadRoundtrip -q`
Expected: PASS

- [ ] **Step 5: 写测试 — listRecent 排序**

```java
@Test
void listRecentSortedByMtime(@TempDir Path tmp) throws Exception {
    SessionArchive archive = new SessionArchive(tmp);
    // 创建两个 session，间隔 1 秒确保 mtime 不同
    ChatSession s1 = new ChatSession();
    s1.appendUserText("first");
    archive.save(s1);
    Thread.sleep(100);
    ChatSession s2 = new ChatSession();
    s2.appendUserText("second");
    archive.save(s2);

    List<SessionMeta> recent = archive.listRecent(10);
    assertEquals(2, recent.size());
    // 最新的在前
    assertTrue(recent.get(0).lastActivity().isAfter(recent.get(1).lastActivity()) ||
               recent.get(0).lastActivity().equals(recent.get(1).lastActivity()));
}
```

- [ ] **Step 6: 写测试 — cleanExpired**

```java
@Test
void cleanExpiredDeletesOldFiles(@TempDir Path tmp) throws Exception {
    SessionArchive archive = new SessionArchive(tmp);
    ChatSession session = new ChatSession();
    session.appendUserText("old");
    archive.save(session);

    // 设置文件 mtime 为 31 天前
    Path file = Files.list(tmp).findFirst().orElseThrow();
    Instant oldTime = Instant.now().minus(Duration.ofDays(31));
    file.toFile().setLastModified(oldTime.toEpochMilli());

    int cleaned = archive.cleanExpired(Duration.ofDays(30));
    assertEquals(1, cleaned);
    assertFalse(Files.exists(file));
}
```

- [ ] **Step 7: 写测试 — load 前缀匹配**

```java
@Test
void loadByPrefix(@TempDir Path tmp) {
    SessionArchive archive = new SessionArchive(tmp);
    ChatSession session = new ChatSession();
    session.appendUserText("hello");
    String id = archive.save(session);

    // 用前 10 个字符匹配
    String prefix = id.substring(0, 10);
    List<ChatMessage> loaded = archive.load(prefix);
    assertEquals(1, loaded.size());
}
```

- [ ] **Step 8: 写测试 — save 空 session 返回 null**

```java
@Test
void saveEmptySessionReturnsNull(@TempDir Path tmp) {
    SessionArchive archive = new SessionArchive(tmp);
    ChatSession session = new ChatSession();

    String id = archive.save(session);
    assertNull(id);
}
```

- [ ] **Step 9: 写测试 — load 不存在的 session 抛异常**

```java
@Test
void loadNonexistentThrows(@TempDir Path tmp) {
    SessionArchive archive = new SessionArchive(tmp);
    assertThrows(SessionArchiveException.class, () -> archive.load("nonexistent"));
}
```

- [ ] **Step 10: 运行全部 SessionArchiveTest**

Run: `mvn test -Dtest=SessionArchiveTest -q`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/maplecode/session/archive/SessionArchive.java \
        src/test/java/com/maplecode/session/archive/SessionArchiveTest.java
git commit -m "feat(session-archive): 添加 SessionArchive 门面 + 测试"
```

---

### Task 5: ChatSession 添加 appendAssistantText 便利方法

**Files:**
- Modify: `src/main/java/com/maplecode/session/ChatSession.java`

- [ ] **Step 1: 添加 appendAssistantText 方法**

在 `ChatSession.java` 的 `appendAssistant` 方法之后添加：

```java
/** 便利方法：助手回复纯文本。 */
public void appendAssistantText(String text) {
    appendAssistant(List.of(new ContentBlock.TextBlock(text)));
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/session/ChatSession.java
git commit -m "feat(session): ChatSession 添加 appendAssistantText 便利方法"
```

---

### Task 6: ReplLoop 集成 — /new、/resume、退出存档

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 添加 SessionArchive 字段和构造器参数**

在 ReplLoop 中：
1. 添加字段 `private final SessionArchive sessionArchive;`
2. 在主构造器中添加 `SessionArchive sessionArchive` 参数（放在 `CompactCoordinator coord` 之前）
3. 更新向后兼容构造器，传 `null` 给 sessionArchive

- [ ] **Step 2: 添加 /new 命令处理**

在 `/clear` 命令之后、`/compact` 命令之前添加：

```java
// /new
if (trimmed.equals("/new")) {
    if (sessionArchive != null) {
        String archived = sessionArchive.save(agent.session());
        if (archived != null) {
            printer.info("Archived current session (" + agent.session().size() + " messages).");
        }
    }
    agent.session().clear();
    if (coord != null) coord.resetCounter();
    printer.info("New session started.");
    continue;
}
```

注意：save 要在 clear 之前调用。

- [ ] **Step 3: 添加 /resume 命令处理**

在 `/new` 命令之后、`/compact` 命令之前添加：

```java
// /resume
if (trimmed.equals("/resume") || trimmed.startsWith("/resume ")) {
    if (sessionArchive == null) {
        printer.error("session archive not available");
        continue;
    }
    String arg = trimmed.length() > 8 ? trimmed.substring(8).trim() : "";
    if (arg.isEmpty()) {
        // 交互式列表
        var recent = sessionArchive.listRecent(10);
        if (recent.isEmpty()) {
            printer.info("(no archived sessions)");
            continue;
        }
        printer.info("Recent sessions:");
        for (int i = 0; i < recent.size(); i++) {
            var meta = recent.get(i);
            String relTime = formatRelativeTime(meta.lastActivity());
            printer.info("  [" + (i + 1) + "] " + meta.id()
                + " (" + meta.messageCount() + " messages, " + relTime + ")");
        }
        String selection;
        try {
            selection = reader.readLine("Select [1-" + recent.size() + "]: ");
        } catch (Exception e) {
            continue;
        }
        if (selection == null) continue;
        int idx;
        try {
            idx = Integer.parseInt(selection.trim());
        } catch (NumberFormatException e) {
            printer.error("invalid selection");
            continue;
        }
        if (idx < 1 || idx > recent.size()) {
            printer.error("invalid selection");
            continue;
        }
        var chosen = recent.get(idx - 1);
        try {
            var loaded = sessionArchive.load(chosen.id());
            agent.session().clear();
            agent.session().replaceAll(loaded);
            printer.info("Restored " + loaded.size() + " messages from archive.");
        } catch (Exception e) {
            printer.error("failed to load session: " + e.getMessage());
        }
    } else {
        // 直接指定 ID
        try {
            var loaded = sessionArchive.load(arg);
            agent.session().clear();
            agent.session().replaceAll(loaded);
            printer.info("Restored " + loaded.size() + " messages from archive.");
        } catch (Exception e) {
            printer.error("failed to load session: " + e.getMessage());
        }
    }
    continue;
}
```

- [ ] **Step 4: 添加 formatRelativeTime 辅助方法**

在 ReplLoop 的私有方法区域添加：

```java
private String formatRelativeTime(java.time.Instant instant) {
    java.time.Duration d = java.time.Duration.between(instant, java.time.Instant.now());
    long seconds = d.getSeconds();
    if (seconds < 60) return seconds + "s ago";
    long minutes = seconds / 60;
    if (minutes < 60) return minutes + "m ago";
    long hours = minutes / 60;
    if (hours < 24) return hours + "h ago";
    long days = hours / 24;
    return days + "d ago";
}
```

- [ ] **Step 5: 添加退出自动存档**

在 `ReplLoop.run()` 的 `while` 循环末尾（`break` 之前），在 `printer.newline()` 之后、循环结束之前，需要找到退出点。当前代码中 `/exit` 导致 `break`，Ctrl+D 导致 `break`（通过 `input == null`）。

在 `while (true)` 循环的末尾，`break` 语句之前没有统一的退出清理点。需要在两个 `break` 位置之前都加存档逻辑，或者在循环结束后添加。

最简方案：在循环结束后添加存档逻辑（在 `run()` 方法的 `while` 循环之后）：

```java
// 退出前自动存档
if (sessionArchive != null) {
    String archived = sessionArchive.save(session);
    if (archived != null) {
        printer.info("Session archived: " + archived);
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(session-archive): ReplLoop 集成 /new、/resume、退出存档"
```

---

### Task 7: App.java 装配

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: 添加 import**

```java
import com.maplecode.session.archive.SessionArchive;
import java.time.Duration;
```

- [ ] **Step 2: 在 compact 装配之后添加 SessionArchive 装配**

在 `CompactCoordinator coord = new CompactCoordinator(...)` 之后添加：

```java
// 会话存档（v7.2）
Path sessionsDir = Paths.get(System.getProperty("user.home"), ".maplecode", "sessions");
SessionArchive sessionArchive = new SessionArchive(sessionsDir);
int cleaned = sessionArchive.cleanExpired(Duration.ofDays(30));
if (cleaned > 0) {
    System.err.println("[session] cleaned " + cleaned + " expired archives");
}
```

- [ ] **Step 3: 更新 ReplLoop 构造器调用**

将 `new ReplLoop(raw, provider, new StreamPrinter(System.out), reader, registry, executor, engine, agentConfig, coord)` 改为 `new ReplLoop(raw, provider, new StreamPrinter(System.out), reader, registry, executor, engine, agentConfig, sessionArchive, coord)`。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: 运行全部测试**

Run: `mvn test -q`
Expected: BUILD SUCCESS（所有测试通过）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(session-archive): App.main 装配 SessionArchive"
```

---

### Task 8: 更新 banner 命令提示

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 更新 banner 文本**

在 `ReplLoop.run()` 的 `printer.banner(...)` 调用中，添加 `/new` 和 `/resume` 的提示：

```java
printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/new 新会话，/resume 恢复会话，/compact 压缩上下文，/tools 列出工具，/mode 权限模式，/plan 规划，/do 执行计划，/cancel 取消，\"\"\" 开始多行输入");
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行全部测试**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "docs(session-archive): banner 添加 /new、/resume 命令提示"
```

---

### Task 9: 端到端验证

- [ ] **Step 1: 运行全部测试**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 打包验证**

Run: `mvn package -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 手工 smoke test（可选）**

```bash
java -jar target/maple-code-java-0.1.0.jar --config maplecode.yaml.example
# 输入一些对话
# /new
# /resume
# /exit
# 检查 ~/.maplecode/sessions/ 下是否有 JSONL 文件
```

- [ ] **Step 4: 最终 Commit（如果有修复）**

```bash
git add -A
git commit -m "fix(session-archive): 修复端到端验证发现的问题"
```
