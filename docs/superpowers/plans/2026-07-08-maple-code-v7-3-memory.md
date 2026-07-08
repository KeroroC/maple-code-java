# v7.3 自动长期记忆 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现自动长期记忆系统，每轮 Agent Loop 结束后异步调 LLM 分析对话，自动新增/修改/删除记忆，下次启动时注入系统提示词。

**Architecture:** 新建 `com.maplecode.memory` 包（10 个类），通过 `MemoryManager` 门面协调 `MemoryExtractor`（LLM 调用 + JSON 解析）和 `MemoryStore`（文件 I/O）。异步执行使用单线程 `ExecutorService` 保证串行。记忆在启动时通过 `DefaultSections.standard()` 的新参数注入系统提示词。

**Tech Stack:** Java 21, JUnit 5, SnakeYAML（已有）, JLine（已有）

---

## 文件清单

### 新建文件（11 个）
| 文件 | 职责 |
|---|---|
| `src/main/java/com/maplecode/memory/MemoryConfig.java` | 不可变配置记录 |
| `src/main/java/com/maplecode/memory/MemoryCategory.java` | 四类记忆枚举 |
| `src/main/java/com/maplecode/memory/MemoryScope.java` | 两存储位置枚举 |
| `src/main/java/com/maplecode/memory/MemoryEntry.java` | 索引条目记录 |
| `src/main/java/com/maplecode/memory/MemoryOp.java` | 操作 sealed 接口 |
| `src/main/java/com/maplecode/memory/MemoryOpsResult.java` | 操作结果记录 |
| `src/main/java/com/maplecode/memory/MemoryExtractor.java` | LLM 调用 + JSON 解析 |
| `src/main/java/com/maplecode/memory/MemoryStore.java` | 文件 I/O |
| `src/main/java/com/maplecode/memory/MemoryFailureCounter.java` | 熔断计数器 |
| `src/main/java/com/maplecode/memory/MemoryManager.java` | 门面 |
| `src/main/java/com/maplecode/prompt/MemorySection.java` | PromptSection 实现 |

### 修改文件（7 个）
| 文件 | 改动 |
|---|---|
| `src/main/java/com/maplecode/config/AppConfig.java` | 新增 `memoryConfig` 字段 |
| `src/main/java/com/maplecode/config/ConfigLoader.java` | 解析 `memory` YAML 块 |
| `src/main/java/com/maplecode/prompt/DefaultSections.java` | `standard()` 新增 `memoryContent` 参数，删除 `LONG_TERM_MEMORY` 占位 |
| `src/main/java/com/maplecode/session/ChatSession.java` | 新增 `recentMessages(int n)` |
| `src/main/java/com/maplecode/ui/ReplLoop.java` | `/memory` 命令 + extractAsync |
| `src/main/java/com/maplecode/App.java` | 装配 MemoryManager |
| `maplecode.yaml.example` | 新增 memory 配置示例 |

### 新建测试文件（8 个）
| 文件 | 覆盖 |
|---|---|
| `src/test/java/com/maplecode/memory/MemoryConfigTest.java` | 配置解析 |
| `src/test/java/com/maplecode/memory/MemoryCategoryTest.java` | 枚举映射 |
| `src/test/java/com/maplecode/memory/MemoryOpTest.java` | sealed 接口 |
| `src/test/java/com/maplecode/memory/MemoryExtractorTest.java` | JSON 解析容错 |
| `src/test/java/com/maplecode/memory/MemoryStoreTest.java` | 文件 I/O |
| `src/test/java/com/maplecode/memory/MemoryFailureCounterTest.java` | 熔断逻辑 |
| `src/test/java/com/maplecode/memory/MemoryManagerTest.java` | 门面集成 |
| `src/test/java/com/maplecode/session/ChatSessionTest.java` | 新增 recentMessages 测试 |

---

## Task 1: MemoryConfig + AppConfig 集成

**Files:**
- Create: `src/main/java/com/maplecode/memory/MemoryConfig.java`
- Modify: `src/main/java/com/maplecode/config/AppConfig.java:14-17`（新增字段）
- Modify: `src/main/java/com/maplecode/config/ConfigLoader.java:91-92`（解析 memory 块）
- Test: `src/test/java/com/maplecode/memory/MemoryConfigTest.java`

- [ ] **Step 1: Write MemoryConfigTest**

```java
package com.maplecode.memory;

import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {

    @Test
    void defaults_whenNotConfigured() {
        var mc = new MemoryConfig(false, null, 10);
        assertFalse(mc.enabled());
        assertNull(mc.memoryModel());
        assertEquals(10, mc.maxContextMessages());
    }

    @Test
    void fromAppConfig_returnsDisabled_whenMemoryConfigIsNull() {
        var app = minimalApp(null);
        var mc = MemoryConfig.fromAppConfig(app);
        assertFalse(mc.enabled());
    }

    @Test
    void fromAppConfig_delegates_to_MemoryConfig(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("c.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            memory:
              enabled: true
              memory_model: claude-haiku-4-5
              max_context_messages: 20
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("c.yaml"));
        assertNotNull(cfg.memoryConfig());
        assertTrue(cfg.memoryConfig().enabled());
        assertEquals("claude-haiku-4-5", cfg.memoryConfig().memoryModel());
        assertEquals(20, cfg.memoryConfig().maxContextMessages());
    }

    @Test
    void fromAppConfig_defaults_maxContextMessages(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("c.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            memory:
              enabled: true
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("c.yaml"));
        assertEquals(10, cfg.memoryConfig().maxContextMessages());
        assertNull(cfg.memoryConfig().memoryModel());
    }

    private static AppConfig minimalApp(MemoryConfig mc) {
        return new AppConfig("anthropic", "m", "https://api.anthropic.com", "k",
            null, java.util.List.of(), null,
            new AppConfig.Timeouts(10, 60), PermissionMode.DEFAULT,
            AppConfig.AgentLimits.defaults(), null, 0, null, mc);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MemoryConfigTest -pl . -q`
Expected: FAIL — `MemoryConfig` class does not exist

- [ ] **Step 3: Create MemoryConfig.java**

```java
package com.maplecode.memory;

import com.maplecode.config.AppConfig;

public record MemoryConfig(
    boolean enabled,
    String memoryModel,       // null = 复用主模型
    int maxContextMessages    // 默认 10
) {
    public static final int DEFAULT_MAX_CONTEXT_MESSAGES = 10;

    public MemoryConfig {
        if (maxContextMessages < 1) {
            throw new IllegalArgumentException("max_context_messages must be >= 1");
        }
    }

    /** 禁用时的默认实例。 */
    public static MemoryConfig disabled() {
        return new MemoryConfig(false, null, DEFAULT_MAX_CONTEXT_MESSAGES);
    }

    public static MemoryConfig fromAppConfig(AppConfig config) {
        MemoryConfig mc = config.memoryConfig();
        return mc != null ? mc : disabled();
    }
}
```

- [ ] **Step 4: Add memoryConfig field to AppConfig**

在 `AppConfig.java` record 参数列表末尾新增：

```java
    String summarizerModel,              // null = 未配置
    MemoryConfig memoryConfig            // nullable；null 表示未配置 memory 块
```

同时在 compact constructor 中删除旧的 `if (contextWindow < 0)` 验证（保留不变），不需要为 memoryConfig 添加验证（nullable 即可）。

- [ ] **Step 5: Parse memory block in ConfigLoader**

在 `ConfigLoader.parse()` 方法中，`String summarizerModel = optionalString(root, "summarizer_model");` 之后新增：

```java
        MemoryConfig memoryConfig = parseMemoryConfig(optionalMap(root, "memory"));
```

并添加新方法：

```java
    private static MemoryConfig parseMemoryConfig(Map<?, ?> m) {
        if (m == null) return null;
        boolean enabled = isEnabled(m.get("enabled"));
        String model = optionalString(m, "memory_model");
        int maxCtx = m.get("max_context_messages") instanceof Number n
            ? n.intValue() : MemoryConfig.DEFAULT_MAX_CONTEXT_MESSAGES;
        return new MemoryConfig(enabled, model, maxCtx);
    }
```

同时在 `ConfigLoader.java` 顶部 import 区新增：

```java
import com.maplecode.memory.MemoryConfig;
```

- [ ] **Step 6: Update AppConfig constructor call in ConfigLoader.parse()**

将 return 语句更新为包含 memoryConfig 参数：

```java
        return new AppConfig(protocol, model, baseUrl, apiKey, yamlPrompt,
            List.of(), thinking, new AppConfig.Timeouts(connect, read), mode,
            new AppConfig.AgentLimits(maxIter, maxUnknown), mcp,
            contextWindow, summarizerModel, memoryConfig);
```

- [ ] **Step 7: Run MemoryConfigTest**

Run: `mvn test -Dtest=MemoryConfigTest -pl . -q`
Expected: PASS

- [ ] **Step 8: Run full test suite to check AppConfig ripple**

Run: `mvn test -q`
Expected: 全部 PASS（现有代码的 AppConfig 构造调用需要更新——见 Step 9）

- [ ] **Step 9: Fix any compilation errors from AppConfig signature change**

所有现有 `new AppConfig(...)` 调用需要追加 `null` 作为最后一个参数（memoryConfig=null 表示未配置）。受影响的文件：
- `ConfigLoaderTest.java` 中如有直接构造 AppConfig 的地方
- 其他测试文件中直接构造 AppConfig 的地方

用 `grep -rn "new AppConfig(" src/` 找到所有调用点，逐一追加 `, null`。

- [ ] **Step 10: Run full test suite**

Run: `mvn test -q`
Expected: 全部 PASS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/maplecode/memory/MemoryConfig.java \
        src/main/java/com/maplecode/config/AppConfig.java \
        src/main/java/com/maplecode/config/ConfigLoader.java \
        src/test/java/com/maplecode/memory/MemoryConfigTest.java
git commit -m "feat(memory): T1 MemoryConfig + AppConfig memory 字段"
```

---

## Task 2: MemoryCategory + MemoryScope + MemoryEntry + MemoryOp + MemoryOpsResult

**Files:**
- Create: `src/main/java/com/maplecode/memory/MemoryCategory.java`
- Create: `src/main/java/com/maplecode/memory/MemoryScope.java`
- Create: `src/main/java/com/maplecode/memory/MemoryEntry.java`
- Create: `src/main/java/com/maplecode/memory/MemoryOp.java`
- Create: `src/main/java/com/maplecode/memory/MemoryOpsResult.java`
- Test: `src/test/java/com/maplecode/memory/MemoryCategoryTest.java`
- Test: `src/test/java/com/maplecode/memory/MemoryOpTest.java`

- [ ] **Step 1: Write MemoryCategoryTest**

```java
package com.maplecode.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCategoryTest {

    @Test
    void userAndFeedbackAreUserScope() {
        assertEquals(MemoryScope.USER, MemoryCategory.USER.scope());
        assertEquals(MemoryScope.USER, MemoryCategory.FEEDBACK.scope());
    }

    @Test
    void projectAndReferenceAreProjectScope() {
        assertEquals(MemoryScope.PROJECT, MemoryCategory.PROJECT.scope());
        assertEquals(MemoryScope.PROJECT, MemoryCategory.REFERENCE.scope());
    }

    @Test
    void dirNameMatchesLowercase() {
        assertEquals("user", MemoryCategory.USER.dirName());
        assertEquals("feedback", MemoryCategory.FEEDBACK.dirName());
        assertEquals("project", MemoryCategory.PROJECT.dirName());
        assertEquals("reference", MemoryCategory.REFERENCE.dirName());
    }

    @Test
    void fromDirName_roundTrips() {
        for (var cat : MemoryCategory.values()) {
            assertEquals(cat, MemoryCategory.fromDirName(cat.dirName()));
        }
    }

    @Test
    void fromDirName_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> MemoryCategory.fromDirName("bogus"));
    }
}
```

- [ ] **Step 2: Write MemoryOpTest**

```java
package com.maplecode.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryOpTest {

    @Test
    void createHasAllFields() {
        var op = new MemoryOp.Create(MemoryCategory.USER, "prefer-java21", "content");
        assertEquals(MemoryCategory.USER, op.category());
        assertEquals("prefer-java21", op.name());
        assertEquals("content", op.content());
    }

    @Test
    void updateHasNameAndContent() {
        var op = new MemoryOp.Update("prefer-java21", "updated content");
        assertEquals("prefer-java21", op.name());
        assertEquals("updated content", op.content());
    }

    @Test
    void deleteHasOnlyName() {
        var op = new MemoryOp.Delete("prefer-java21");
        assertEquals("prefer-java21", op.name());
    }

    @Test
    void sealed_hierarchy() {
        MemoryOp op = new MemoryOp.Create(MemoryCategory.USER, "x", "y");
        assertTrue(op instanceof MemoryOp.Create);
        assertFalse(op instanceof MemoryOp.Update);
        assertFalse(op instanceof MemoryOp.Delete);
    }
}
```

- [ ] **Step 3: Create MemoryCategory.java**

```java
package com.maplecode.memory;

import java.util.Arrays;

public enum MemoryCategory {
    USER("user", MemoryScope.USER),
    FEEDBACK("feedback", MemoryScope.USER),
    PROJECT("project", MemoryScope.PROJECT),
    REFERENCE("reference", MemoryScope.PROJECT);

    private final String dirName;
    private final MemoryScope scope;

    MemoryCategory(String dirName, MemoryScope scope) {
        this.dirName = dirName;
        this.scope = scope;
    }

    public String dirName() { return dirName; }
    public MemoryScope scope() { return scope; }

    public static MemoryCategory fromDirName(String dirName) {
        return Arrays.stream(values())
            .filter(c -> c.dirName.equals(dirName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown category dir: " + dirName));
    }
}
```

- [ ] **Step 4: Create MemoryScope.java**

```java
package com.maplecode.memory;

import java.nio.file.Path;
import java.nio.file.Paths;

public enum MemoryScope {
    USER(Paths.get(System.getProperty("user.home"), ".maplecode", "memory")),
    PROJECT(Paths.get(System.getProperty("user.dir"), ".maplecode", "memory"));

    private final Path basePath;

    MemoryScope(Path basePath) {
        this.basePath = basePath;
    }

    public Path basePath() { return basePath; }
}
```

- [ ] **Step 5: Create MemoryEntry.java**

```java
package com.maplecode.memory;

public record MemoryEntry(
    String name,
    MemoryCategory category,
    String summary,
    String relativePath,
    String updated
) {}
```

- [ ] **Step 6: Create MemoryOp.java**

```java
package com.maplecode.memory;

public sealed interface MemoryOp {
    record Create(MemoryCategory category, String name, String content) implements MemoryOp {}
    record Update(String name, String content) implements MemoryOp {}
    record Delete(String name) implements MemoryOp {}
}
```

- [ ] **Step 7: Create MemoryOpsResult.java**

```java
package com.maplecode.memory;

import java.util.List;

public record MemoryOpsResult(List<MemoryOp> ops) {
    public static MemoryOpsResult empty() {
        return new MemoryOpsResult(List.of());
    }
}
```

- [ ] **Step 8: Run tests**

Run: `mvn test -Dtest='MemoryCategoryTest,MemoryOpTest' -pl . -q`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/maplecode/memory/MemoryCategory.java \
        src/main/java/com/maplecode/memory/MemoryScope.java \
        src/main/java/com/maplecode/memory/MemoryEntry.java \
        src/main/java/com/maplecode/memory/MemoryOp.java \
        src/main/java/com/maplecode/memory/MemoryOpsResult.java \
        src/test/java/com/maplecode/memory/MemoryCategoryTest.java \
        src/test/java/com/maplecode/memory/MemoryOpTest.java
git commit -m "feat(memory): T2 MemoryCategory + MemoryScope + MemoryEntry + MemoryOp"
```

---

## Task 3: MemoryStore

**Files:**
- Create: `src/main/java/com/maplecode/memory/MemoryStore.java`
- Test: `src/test/java/com/maplecode/memory/MemoryStoreTest.java`

- [ ] **Step 1: Write MemoryStoreTest**

```java
package com.maplecode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path userDir;

    @TempDir
    Path projectDir;

    MemoryStore store;

    @BeforeEach
    void setUp() {
        // 注入自定义路径：通过构造 MemoryStore 时覆盖
        store = new MemoryStore(userDir, projectDir);
    }

    @Test
    void loadIndex_returnsEmpty_whenNoMemoryMd() {
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertTrue(entries.isEmpty());
    }

    @Test
    void createThenLoadIndex_roundTrips() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "prefer-java21", "用户偏好 Java 21"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(1, entries.size());
        assertEquals("prefer-java21", entries.get(0).name());
        assertEquals(MemoryCategory.USER, entries.get(0).category());
    }

    @Test
    void updateOverwritesContent() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "pref", "original"));
        store.executeOp(new MemoryOp.Update("pref", "updated"));
        String content = store.readContent(MemoryScope.USER, MemoryCategory.USER, "pref");
        assertTrue(content.contains("updated"));
    }

    @Test
    void deleteRemovesFile() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.PROJECT, "spring", "uses spring"));
        store.executeOp(new MemoryOp.Delete("spring"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.PROJECT);
        assertTrue(entries.isEmpty());
    }

    @Test
    void clearAll_removesEverything() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "a", "content a"));
        store.executeOp(new MemoryOp.Create(MemoryCategory.FEEDBACK, "b", "content b"));
        store.clearAll(MemoryScope.USER);
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
    }

    @Test
    void fileNaming_incrementsSequence() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "first", "c1"));
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "second", "c2"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(2, entries.size());
        // 文件名应为 001-first.md 和 002-second.md
        assertTrue(entries.get(0).relativePath().contains("001"));
        assertTrue(entries.get(1).relativePath().contains("002"));
    }

    @Test
    void readContent_returnsNull_forNonExistent() {
        assertNull(store.readContent(MemoryScope.USER, MemoryCategory.USER, "nope"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MemoryStoreTest -pl . -q`
Expected: FAIL — `MemoryStore` class does not exist

- [ ] **Step 3: Implement MemoryStore**

```java
package com.maplecode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MemoryStore {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Pattern FRONTMATTER = Pattern.compile("(?s)^---\\n(.*?)\\n---\\n(.*)$");
    private static final Pattern NAME_IN_PATH = Pattern.compile("\\d{3}-(.+)\\.md");
    private static final int MAX_INDEX_LINES = 200;
    private static final long MAX_INDEX_BYTES = 25 * 1024;

    private final Path userBase;
    private final Path projectBase;

    /** 默认构造：使用系统路径。测试时可注入自定义路径。 */
    public MemoryStore() {
        this(MemoryScope.USER.basePath(), MemoryScope.PROJECT.basePath());
    }

    public MemoryStore(Path userBase, Path projectBase) {
        this.userBase = userBase;
        this.projectBase = projectBase;
    }

    // ---- 查询 ----

    public List<MemoryEntry> loadIndex(MemoryScope scope) {
        Path index = basePath(scope).resolve("MEMORY.md");
        if (!Files.exists(index)) return List.of();
        try {
            String content = Files.readString(index, StandardCharsets.UTF_8);
            return parseIndex(content, scope);
        } catch (IOException e) {
            System.err.println("[memory] WARN: failed to read index: " + index);
            return List.of();
        }
    }

    public String readContent(MemoryScope scope, MemoryCategory category, String name) {
        Path dir = basePath(scope).resolve(category.dirName());
        // 找匹配的文件
        try (Stream<Path> walk = Files.list(dir)) {
            Optional<Path> file = walk
                .filter(p -> p.getFileName().toString().endsWith("-" + name + ".md")
                          || p.getFileName().toString().equals("000-" + name + ".md"))
                .findFirst();
            if (file.isEmpty()) return null;
            return parseMemoryFile(file.get()).content();
        } catch (IOException e) {
            return null;
        }
    }

    public String loadIndexText(MemoryScope scope) {
        Path index = basePath(scope).resolve("MEMORY.md");
        if (!Files.exists(index)) return "";
        try {
            return Files.readString(index, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[memory] WARN: failed to read index: " + index);
            return "";
        }
    }

    // ---- 操作 ----

    public void executeOp(MemoryOp op) {
        switch (op) {
            case MemoryOp.Create c -> doCreate(c);
            case MemoryOp.Update u -> doUpdate(u);
            case MemoryOp.Delete d -> doDelete(d);
        }
    }

    public void rebuildIndex(MemoryScope scope) {
        Path base = basePath(scope);
        List<MemoryEntry> all = new ArrayList<>();
        for (MemoryCategory cat : MemoryCategory.values()) {
            if (cat.scope() != scope) continue;
            Path dir = base.resolve(cat.dirName());
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.list(dir)) {
                walk.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        var parsed = parseMemoryFile(p);
                        if (parsed != null) {
                            String rel = base.relativize(p).toString();
                            all.add(new MemoryEntry(parsed.name, cat, parsed.content.lines()
                                .findFirst().orElse(""), rel, parsed.updated));
                        }
                    });
            } catch (IOException e) {
                System.err.println("[memory] WARN: failed to list " + dir);
            }
        }
        writeIndex(scope, all);
    }

    public void clearAll(MemoryScope scope) {
        Path base = basePath(scope);
        for (MemoryCategory cat : MemoryCategory.values()) {
            if (cat.scope() != scope) continue;
            Path dir = base.resolve(cat.dirName());
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.list(dir)) {
                walk.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
        Path index = base.resolve("MEMORY.md");
        try { Files.deleteIfExists(index); } catch (IOException ignored) {}
    }

    // ---- 内部 ----

    private Path basePath(MemoryScope scope) {
        return scope == MemoryScope.USER ? userBase : projectBase;
    }

    private void doCreate(MemoryOp.Create c) {
        Path dir = basePath(c.category().scope()).resolve(c.category().dirName());
        try { Files.createDirectories(dir); } catch (IOException e) {
            System.err.println("[memory] WARN: cannot create dir: " + dir);
            return;
        }
        int seq = nextSeq(dir);
        String fileName = String.format("%03d-%s.md", seq, c.name());
        Path target = dir.resolve(fileName);
        String now = LocalDateTime.now().format(ISO_FMT);
        String front = "---\nname: " + c.name() + "\ncategory: " + c.category().dirName()
            + "\ncreated: " + now + "\nupdated: " + now + "\n---\n\n";
        try {
            Files.writeString(target, front + c.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[memory] WARN: write failed: " + target);
            return;
        }
        rebuildIndex(c.category().scope());
    }

    private void doUpdate(MemoryOp.Update u) {
        // 在所有 scope 中查找 name
        for (MemoryScope scope : MemoryScope.values()) {
            Path base = basePath(scope);
            for (MemoryCategory cat : MemoryCategory.values()) {
                if (cat.scope() != scope) continue;
                Path dir = base.resolve(cat.dirName());
                if (!Files.exists(dir)) continue;
                try (Stream<Path> walk = Files.list(dir)) {
                    Optional<Path> file = walk
                        .filter(p -> p.getFileName().toString().contains("-" + u.name() + ".md"))
                        .findFirst();
                    if (file.isPresent()) {
                        var parsed = parseMemoryFile(file.get());
                        String now = LocalDateTime.now().format(ISO_FMT);
                        String front = "---\nname: " + u.name() + "\ncategory: " + cat.dirName()
                            + "\ncreated: " + parsed.created + "\nupdated: " + now + "\n---\n\n";
                        try {
                            Files.writeString(file.get(), front + u.content(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            System.err.println("[memory] WARN: update write failed: " + file.get());
                        }
                        rebuildIndex(scope);
                        return;
                    }
                } catch (IOException ignored) {}
            }
        }
        System.err.println("[memory] WARN: update target not found: " + u.name());
    }

    private void doDelete(MemoryOp.Delete d) {
        for (MemoryScope scope : MemoryScope.values()) {
            Path base = basePath(scope);
            for (MemoryCategory cat : MemoryCategory.values()) {
                if (cat.scope() != scope) continue;
                Path dir = base.resolve(cat.dirName());
                if (!Files.exists(dir)) continue;
                try (Stream<Path> walk = Files.list(dir)) {
                    Optional<Path> file = walk
                        .filter(p -> p.getFileName().toString().contains("-" + d.name() + ".md"))
                        .findFirst();
                    if (file.isPresent()) {
                        try { Files.delete(file.get()); } catch (IOException e) {
                            System.err.println("[memory] WARN: delete failed: " + file.get());
                        }
                        rebuildIndex(scope);
                        return;
                    }
                } catch (IOException ignored) {}
            }
        }
        System.err.println("[memory] WARN: delete target not found: " + d.name());
    }

    private int nextSeq(Path dir) {
        try (Stream<Path> walk = Files.list(dir)) {
            return walk
                .map(p -> p.getFileName().toString())
                .filter(n -> n.matches("\\d{3}-.+"))
                .mapToInt(n -> Integer.parseInt(n.substring(0, 3)))
                .max().orElse(0) + 1;
        } catch (IOException e) {
            return 1;
        }
    }

    private record ParsedMemory(String name, String category, String created, String updated, String content) {}

    private ParsedMemory parseMemoryFile(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Matcher m = FRONTMATTER.matcher(raw);
            if (!m.matches()) return null;
            String fm = m.group(1);
            String body = m.group(2).strip();
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : fm.split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) fields.put(line.substring(0, idx).strip(), line.substring(idx + 1).strip());
            }
            return new ParsedMemory(
                fields.getOrDefault("name", "unknown"),
                fields.getOrDefault("category", "user"),
                fields.getOrDefault("created", ""),
                fields.getOrDefault("updated", ""),
                body);
        } catch (IOException e) {
            System.err.println("[memory] WARN: cannot read " + path);
            return null;
        }
    }

    private List<MemoryEntry> parseIndex(String content, MemoryScope scope) {
        List<MemoryEntry> entries = new ArrayList<>();
        MemoryCategory currentCat = null;
        for (String line : content.split("\n")) {
            // 检测 section header
            for (MemoryCategory cat : MemoryCategory.values()) {
                if (line.contains(cat.dirName()) || line.contains(capitalize(cat.dirName()))) {
                    currentCat = cat;
                }
            }
            // 解析 "- [name](path) — summary"
            Matcher m = Pattern.compile("- \\[([^]]+)]\\(([^)]+)\\) — (.+)").matcher(line);
            if (m.matches() && currentCat != null) {
                entries.add(new MemoryEntry(m.group(1), currentCat, m.group(3), m.group(2), ""));
            }
        }
        return entries;
    }

    private void writeIndex(MemoryScope scope, List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder("# Memory Index\n");
        for (MemoryCategory cat : MemoryCategory.values()) {
            if (cat.scope() != scope) continue;
            List<MemoryEntry> catEntries = entries.stream()
                .filter(e -> e.category() == cat).toList();
            if (catEntries.isEmpty()) continue;
            sb.append("\n## ").append(capitalize(cat.dirName())).append("\n");
            for (MemoryEntry e : catEntries) {
                sb.append("- [").append(e.name()).append("](").append(e.relativePath())
                  .append(") — ").append(e.summary()).append("\n");
            }
        }
        Path index = basePath(scope).resolve("MEMORY.md");
        try {
            Files.createDirectories(basePath(scope));
            Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[memory] WARN: cannot write index: " + index);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
```

- [ ] **Step 4: Run MemoryStoreTest**

Run: `mvn test -Dtest=MemoryStoreTest -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/memory/MemoryStore.java \
        src/test/java/com/maplecode/memory/MemoryStoreTest.java
git commit -m "feat(memory): T3 MemoryStore 文件 I/O"
```

---

## Task 4: MemoryExtractor

**Files:**
- Create: `src/main/java/com/maplecode/memory/MemoryExtractor.java`
- Test: `src/test/java/com/maplecode/memory/MemoryExtractorTest.java`

- [ ] **Step 1: Write MemoryExtractorTest**

```java
package com.maplecode.memory;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractorTest {

    @Test
    void parseJsonOps_plainJson() {
        String json = """
            {"ops": [{"action": "create", "category": "user", "name": "prefer-java21", "content": "用户偏好 Java 21"}]}
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Create);
        var c = (MemoryOp.Create) result.ops().get(0);
        assertEquals(MemoryCategory.USER, c.category());
        assertEquals("prefer-java21", c.name());
    }

    @Test
    void parseJsonOps_stripsMarkdownCodeBlock() {
        String json = """
            ```json
            {"ops": [{"action": "update", "name": "test", "content": "updated"}]}
            ```
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Update);
    }

    @Test
    void parseJsonOps_stripsPlainCodeBlock() {
        String json = """
            ```
            {"ops": [{"action": "delete", "name": "old"}]}
            ```
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Delete);
    }

    @Test
    void parseJsonOps_emptyOps() {
        var result = MemoryExtractor.parseResponse("{\"ops\": []}");
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void parseJsonOps_greedyFallback() {
        // JSON 嵌在无关文本中
        String text = "Here is the result:\n{\"ops\": []}\nDone.";
        var result = MemoryExtractor.parseResponse(text);
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void parseJsonOps_invalidJson_returnsEmpty() {
        var result = MemoryExtractor.parseResponse("not json at all");
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void formatMessages_extractsTextBlocksOnly() {
        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hello"))),
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new ContentBlock.TextBlock("hi"),
                new ContentBlock.ToolUseBlock("t1", "read_file", "{}")
            ))
        );
        String formatted = MemoryExtractor.formatMessages(msgs);
        assertTrue(formatted.contains("[用户] hello"));
        assertTrue(formatted.contains("[助手] hi"));
        assertFalse(formatted.contains("read_file"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MemoryExtractorTest -pl . -q`
Expected: FAIL — `MemoryExtractor` class does not exist

- [ ] **Step 3: Implement MemoryExtractor**

```java
package com.maplecode.memory;

import com.maplecode.provider.*;
import com.maplecode.prompt.SystemBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryExtractor {

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)^```(?:json)?\\s*\\n?(.*)\\n?```\\s*$");
    private static final Pattern JSON_OBJECT = Pattern.compile("(?s)(\\{.*})");

    private final LlmProvider provider;
    private final String model;
    private final List<MemoryEntry> existingMemories;

    public MemoryExtractor(LlmProvider provider, String model, List<MemoryEntry> existingMemories) {
        this.provider = provider;
        this.model = model;
        this.existingMemories = existingMemories;
    }

    /** 同步调用 LLM 提取记忆操作。 */
    public MemoryOpsResult extract(List<ChatMessage> recentMessages) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = "以下是最近的对话：\n\n" + formatMessages(recentMessages);

        ChatRequest request = new ChatRequest(
            model,
            List.of(new SystemBlock(systemPrompt, false, "memory_extractor")),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock(userPrompt)))),
            null, null
        );

        StringBuilder sb = new StringBuilder();
        provider.stream(request, chunk -> {
            if (chunk instanceof StreamChunk.TextDelta td) {
                sb.append(td.text());
            } else if (chunk instanceof StreamChunk.Error e) {
                System.err.println("[memory] WARN: LLM error: " + e.code() + " - " + e.message());
            }
        });

        return parseResponse(sb.toString());
    }

    // ---- JSON 解析（package-private 供测试） ----

    static MemoryOpsResult parseResponse(String raw) {
        if (raw == null || raw.isBlank()) return MemoryOpsResult.empty();

        // 1. 剥离 markdown 代码块
        String stripped = raw.strip();
        Matcher codeMatcher = CODE_BLOCK.matcher(stripped);
        if (codeMatcher.matches()) {
            stripped = codeMatcher.group(1).strip();
        }

        // 2. 尝试直接解析
        MemoryOpsResult result = tryParse(stripped);
        if (result != null) return result;

        // 3. 贪婪匹配 JSON 对象
        Matcher jsonMatcher = JSON_OBJECT.matcher(stripped);
        if (jsonMatcher.find()) {
            result = tryParse(jsonMatcher.group(1));
            if (result != null) return result;
        }

        System.err.println("[memory] WARN: failed to parse LLM response as JSON");
        return MemoryOpsResult.empty();
    }

    private static MemoryOpsResult tryParse(String json) {
        try {
            // 简单 JSON 解析：用字符串操作而非引入 JSON 库
            if (!json.contains("\"ops\"")) return null;
            List<MemoryOp> ops = new ArrayList<>();
            // 找到 ops 数组内容
            int opsStart = json.indexOf("[");
            int opsEnd = json.lastIndexOf("]");
            if (opsStart < 0 || opsEnd <= opsStart) {
                // 空 ops
                return MemoryOpsResult.empty();
            }
            String opsContent = json.substring(opsStart + 1, opsEnd).strip();
            if (opsContent.isEmpty()) return MemoryOpsResult.empty();

            // 按 },{ 分割各个操作
            List<String> opStrings = splitJsonObjects(opsContent);
            for (String opStr : opStrings) {
                MemoryOp op = parseSingleOp(opStr);
                if (op != null) ops.add(op);
            }
            return new MemoryOpsResult(ops);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitJsonObjects(String content) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    result.add(content.substring(start, i + 1));
                    // 跳过逗号和空白
                    int j = i + 1;
                    while (j < content.length() && (content.charAt(j) == ',' || Character.isWhitespace(content.charAt(j)))) j++;
                    start = j;
                    i = j - 1;
                }
            }
        }
        return result;
    }

    private static MemoryOp parseSingleOp(String json) {
        String action = extractString(json, "action");
        String name = extractString(json, "name");
        if (action == null || name == null) return null;

        return switch (action) {
            case "create" -> {
                String category = extractString(json, "category");
                String content = extractString(json, "content");
                if (category == null || content == null) yield null;
                MemoryCategory cat;
                try { cat = MemoryCategory.fromDirName(category); }
                catch (IllegalArgumentException e) { yield null; }
                yield new MemoryOp.Create(cat, name, content);
            }
            case "update" -> {
                String content = extractString(json, "content");
                if (content == null) yield null;
                yield new MemoryOp.Update(name, content);
            }
            case "delete" -> new MemoryOp.Delete(name);
            default -> null;
        };
    }

    /** 从 JSON 字符串中提取 "key": "value" 的 value。 */
    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '"' && json.charAt(quoteEnd - 1) != '\\') break;
            quoteEnd++;
        }
        return json.substring(quoteStart + 1, quoteEnd).replace("\\\"", "\"");
    }

    // ---- Prompt 构造 ----

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是一个记忆管理助手。根据对话内容，决定是否需要新增、修改或删除长期记忆。

            记忆分为四类：
            - user: 用户偏好、习惯、风格（如编码风格、语言偏好、输出格式偏好）
            - feedback: 用户对助手行为的纠正、反馈（如"不要解释这么多"、"用中文回复"）
            - project: 当前项目的技术栈、架构约定、构建命令、目录结构
            - reference: 外部资源链接、文档位置、API 端点、配置文件路径

            """);
        if (!existingMemories.isEmpty()) {
            sb.append("当前已有记忆：\n");
            for (MemoryEntry e : existingMemories) {
                sb.append("- [").append(e.name()).append("] ").append(e.summary()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("""
            输出要求：
            - 输出纯 JSON，不要包含 markdown 代码块标记
            - 如果无需任何操作，输出 {"ops": []}
            - name 使用英文短横线命名，简短且有意义
            - content 用 1-3 句话概括
            - 对已有记忆，如果对话中有新信息，使用 update 更新
            - 如果用户明确否定/纠正了之前的记忆，使用 delete 删除
            """);
        return sb.toString();
    }

    /** 将消息格式化为可读文本（仅 TextBlock）。 */
    static String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String prefix = msg.role() == ChatMessage.Role.USER ? "[用户]" : "[助手]";
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ContentBlock.TextBlock t) {
                    sb.append(prefix).append(" ").append(t.text()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run MemoryExtractorTest**

Run: `mvn test -Dtest=MemoryExtractorTest -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/memory/MemoryExtractor.java \
        src/test/java/com/maplecode/memory/MemoryExtractorTest.java
git commit -m "feat(memory): T4 MemoryExtractor LLM 调用 + JSON 解析容错"
```

---

## Task 5: MemoryFailureCounter + MemoryManager

**Files:**
- Create: `src/main/java/com/maplecode/memory/MemoryFailureCounter.java`
- Create: `src/main/java/com/maplecode/memory/MemoryManager.java`
- Test: `src/test/java/com/maplecode/memory/MemoryFailureCounterTest.java`
- Test: `src/test/java/com/maplecode/memory/MemoryManagerTest.java`

- [ ] **Step 1: Write MemoryFailureCounterTest**

```java
package com.maplecode.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFailureCounterTest {

    @Test
    void startsOpen() {
        var c = new MemoryFailureCounter();
        assertTrue(c.isOpen());
        assertEquals(0, c.failures());
    }

    @Test
    void threeFailuresTrips() {
        var c = new MemoryFailureCounter();
        c.recordFailure();
        c.recordFailure();
        assertTrue(c.isOpen());
        c.recordFailure();
        assertFalse(c.isOpen());
    }

    @Test
    void successResetsCounter() {
        var c = new MemoryFailureCounter();
        c.recordFailure();
        c.recordFailure();
        c.recordSuccess();
        assertTrue(c.isOpen());
        assertEquals(0, c.failures());
    }
}
```

- [ ] **Step 2: Write MemoryManagerTest**

```java
package com.maplecode.memory;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @TempDir
    Path userDir;

    @TempDir
    Path projectDir;

    @Test
    void extractSync_callsProviderAndWritesFiles() {
        AtomicInteger callCount = new AtomicInteger();
        LlmProvider mockProvider = (req, sink) -> {
            callCount.incrementAndGet();
            sink.accept(new StreamChunk.TextDelta(
                "{\"ops\": [{\"action\": \"create\", \"category\": \"user\", \"name\": \"test\", \"content\": \"test content\"}]}"));
        };
        var config = new MemoryConfig(true, "test-model", 10);
        var store = new MemoryStore(userDir, projectDir);
        var manager = new MemoryManager(config, mockProvider, store, "main-model");

        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hi")))
        );
        manager.extractSync(msgs);

        assertEquals(1, callCount.get());
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(1, entries.size());
        assertEquals("test", entries.get(0).name());
    }

    @Test
    void extractSync_skipped_whenCircuitOpen() {
        LlmProvider failingProvider = (req, sink) -> {
            throw new RuntimeException("provider error");
        };
        var config = new MemoryConfig(true, "test-model", 10);
        var store = new MemoryStore(userDir, projectDir);
        var manager = new MemoryManager(config, failingProvider, store, "main-model");

        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hi")))
        );

        // 3 次失败触发熔断
        manager.extractSync(msgs);
        manager.extractSync(msgs);
        manager.extractSync(msgs);

        // 第 4 次应被跳过（不抛异常）
        manager.extractSync(msgs);
        // 验证：记忆仍为空（没写入任何东西）
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
    }

    @Test
    void listMemories_returnsFormattedString() {
        var config = new MemoryConfig(true, null, 10);
        var store = new MemoryStore(userDir, projectDir);
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "test", "test content"));
        var manager = new MemoryManager(config, null, store, "main-model");

        String list = manager.listMemories();
        assertTrue(list.contains("test"));
    }

    @Test
    void clearAll_removesAllMemories() {
        var config = new MemoryConfig(true, null, 10);
        var store = new MemoryStore(userDir, projectDir);
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "a", "content"));
        var manager = new MemoryManager(config, null, store, "main-model");

        manager.clearAll();
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
        assertTrue(store.loadIndex(MemoryScope.PROJECT).isEmpty());
    }
}
```

- [ ] **Step 3: Create MemoryFailureCounter.java**

```java
package com.maplecode.memory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MemoryFailureCounter {

    private static final int THRESHOLD = 3;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    public void recordFailure() {
        int n = failures.incrementAndGet();
        if (n >= THRESHOLD) tripped.set(true);
    }

    public void recordSuccess() {
        failures.set(0);
    }

    public boolean isOpen() {
        return !tripped.get();
    }

    public int failures() {
        return failures.get();
    }
}
```

- [ ] **Step 4: Run MemoryFailureCounterTest**

Run: `mvn test -Dtest=MemoryFailureCounterTest -pl . -q`
Expected: PASS

- [ ] **Step 5: Create MemoryManager.java**

```java
package com.maplecode.memory;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.LlmProvider;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MemoryManager implements Closeable {

    private final MemoryConfig config;
    private final LlmProvider provider;
    private final MemoryStore store;
    private final String mainModel;
    private final MemoryFailureCounter counter = new MemoryFailureCounter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-extractor");
        t.setDaemon(true);
        return t;
    });

    public MemoryManager(MemoryConfig config, LlmProvider provider, MemoryStore store, String mainModel) {
        this.config = config;
        this.provider = provider;
        this.store = store;
        this.mainModel = mainModel;
    }

    /** 异步触发记忆提取（ReplLoop 调用）。 */
    public void extractAsync(List<ChatMessage> recentMessages) {
        if (!config.enabled()) return;
        if (!counter.isOpen()) return;
        executor.submit(() -> doExtract(recentMessages));
    }

    /** 同步触发（/memory extract 命令）。 */
    public void extractSync(List<ChatMessage> recentMessages) {
        if (!config.enabled()) return;
        if (!counter.isOpen()) {
            System.err.println("[memory] WARN: circuit breaker open, skipping extraction");
            return;
        }
        doExtract(recentMessages);
    }

    private void doExtract(List<ChatMessage> recentMessages) {
        try {
            // 加载已有记忆
            List<MemoryEntry> userEntries = store.loadIndex(MemoryScope.USER);
            List<MemoryEntry> projEntries = store.loadIndex(MemoryScope.PROJECT);
            var allEntries = new java.util.ArrayList<>(userEntries);
            allEntries.addAll(projEntries);

            String model = config.memoryModel() != null ? config.memoryModel() : mainModel;
            var extractor = new MemoryExtractor(provider, model, allEntries);
            MemoryOpsResult result = extractor.extract(recentMessages);

            for (MemoryOp op : result.ops()) {
                try {
                    store.executeOp(op);
                } catch (Exception e) {
                    System.err.println("[memory] WARN: op failed: " + e.getMessage());
                }
            }
            counter.recordSuccess();
        } catch (Exception e) {
            counter.recordFailure();
            System.err.println("[memory] WARN: extraction failed (" + counter.failures() + "): " + e.getMessage());
        }
    }

    /** 列出所有记忆。 */
    public String listMemories() {
        StringBuilder sb = new StringBuilder();
        for (MemoryScope scope : MemoryScope.values()) {
            String text = store.loadIndexText(scope);
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(text);
            }
        }
        return sb.isEmpty() ? "(no memories)" : sb.toString();
    }

    /** 清空所有记忆。 */
    public void clearAll() {
        store.clearAll(MemoryScope.USER);
        store.clearAll(MemoryScope.PROJECT);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 6: Run MemoryManagerTest**

Run: `mvn test -Dtest=MemoryManagerTest -pl . -q`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/memory/MemoryFailureCounter.java \
        src/main/java/com/maplecode/memory/MemoryManager.java \
        src/test/java/com/maplecode/memory/MemoryFailureCounterTest.java \
        src/test/java/com/maplecode/memory/MemoryManagerTest.java
git commit -m "feat(memory): T5 MemoryFailureCounter + MemoryManager 门面"
```

---

## Task 6: 系统提示词集成

**Files:**
- Create: `src/main/java/com/maplecode/prompt/MemorySection.java`
- Modify: `src/main/java/com/maplecode/prompt/DefaultSections.java:51,53-65`
- Modify: `src/main/java/com/maplecode/App.java:174`

- [ ] **Step 1: Create MemorySection.java**

```java
package com.maplecode.prompt;

/**
 * 长期记忆 prompt section。content 为空时 enabled()=false，不注入。
 */
public final class MemorySection implements PromptSection {

    private final String content;

    public MemorySection(String content) {
        this.content = content == null ? "" : content;
    }

    @Override
    public String kind() {
        return "long_term_memory";
    }

    @Override
    public String render(SectionContext ctx) {
        return content;
    }

    @Override
    public boolean cacheable() {
        return true;
    }

    @Override
    public boolean enabled(SectionContext ctx) {
        return !content.isBlank();
    }
}
```

- [ ] **Step 2: Modify DefaultSections.java**

删除 `LONG_TERM_MEMORY` 静态字段（第 51 行）：

```java
    /** v5 占位：长期记忆段。本期 enabled()=false，不挂进 standard()。 */
    public static final PromptSection LONG_TERM_MEMORY = new Section("long_term_memory", "", true, false);
```

修改 `standard()` 方法签名，新增 `memoryContent` 参数：

```java
    public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                               PlanMode planMode, String customInstruction,
                                               String agentsMd, String memoryContent) {
```

在 `new AgentsMdSection(agentsMd)` 之后插入 memory section：

```java
        List<PromptSection> list = new ArrayList<>(List.of(
            IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
            TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT,
            new AgentsMdSection(agentsMd),
            new MemorySection(memoryContent),   // v7.3 新增
            ENVIRONMENT));
```

- [ ] **Step 3: Update App.java — assemble memory content and pass to standard()**

在 `App.java` 中，`String agentsMd = AgentsMdLoader.load(...)` 之后新增：

```java
        // v7.3 长期记忆
        MemoryStore memoryStore = new MemoryStore();
        String userMemory = memoryStore.loadIndexText(MemoryScope.USER);
        String projectMemory = memoryStore.loadIndexText(MemoryScope.PROJECT);
        String memoryContent = combineMemorySections(userMemory, projectMemory);
```

将 `DefaultSections.standard(...)` 调用更新为传入 `memoryContent`：

```java
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL,
            raw.yamlPrompt(), agentsMd, memoryContent);
```

在 `App.java` 底部的 helper 方法区新增：

```java
    private static String combineMemorySections(String user, String project) {
        boolean hasUser = user != null && !user.isBlank();
        boolean hasProject = project != null && !project.isBlank();
        if (!hasUser && !hasProject) return "";
        StringBuilder sb = new StringBuilder();
        if (hasUser) sb.append(user);
        if (hasUser && hasProject) sb.append("\n\n");
        if (hasProject) sb.append(project);
        return sb.toString();
    }
```

同时在 `App.java` import 区新增：

```java
import com.maplecode.memory.MemoryScope;
import com.maplecode.memory.MemoryStore;
```

- [ ] **Step 4: Run full test suite**

Run: `mvn test -q`
Expected: 全部 PASS（修复因 `standard()` 签名变化导致的编译错误——所有调用点需要追加 `, ""` 参数）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/prompt/MemorySection.java \
        src/main/java/com/maplecode/prompt/DefaultSections.java \
        src/main/java/com/maplecode/App.java
git commit -m "feat(memory): T6 系统提示词注入 MemorySection"
```

---

## Task 7: ChatSession.recentMessages + ReplLoop 集成

**Files:**
- Modify: `src/main/java/com/maplecode/session/ChatSession.java:43-45`
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java:25-38,71-72,258-260`
- Modify: `src/main/java/com/maplecode/App.java:182-184`
- Test: `src/test/java/com/maplecode/session/ChatSessionTest.java`（追加）

- [ ] **Step 1: Add recentMessages to ChatSession**

在 `ChatSession.java` 的 `get(int i)` 方法之后新增：

```java
    /**
     * 返回最近 n 条消息的不可变副本。
     */
    public List<ChatMessage> recentMessages(int n) {
        int from = Math.max(0, messages.size() - n);
        return List.copyOf(messages.subList(from, messages.size()));
    }
```

- [ ] **Step 2: Add recentMessages tests to ChatSessionTest**

在 `ChatSessionTest.java` 末尾追加：

```java
    @Test
    void recentMessages_returnsLastN() {
        var s = new ChatSession();
        s.appendUserText("a");
        s.appendAssistantText("b");
        s.appendUserText("c");
        s.appendAssistantText("d");
        var recent = s.recentMessages(2);
        assertEquals(2, recent.size());
        assertEquals("c", ((ContentBlock.TextBlock) recent.get(0).blocks().get(0)).text());
        assertEquals("d", ((ContentBlock.TextBlock) recent.get(1).blocks().get(0)).text());
    }

    @Test
    void recentMessages_returnsAll_whenNExceedsSize() {
        var s = new ChatSession();
        s.appendUserText("a");
        var recent = s.recentMessages(10);
        assertEquals(1, recent.size());
    }

    @Test
    void recentMessages_returnsEmpty_whenSessionEmpty() {
        var s = new ChatSession();
        assertTrue(s.recentMessages(5).isEmpty());
    }

    @Test
    void recentMessages_returnsDefensiveCopy() {
        var s = new ChatSession();
        s.appendUserText("a");
        s.appendAssistantText("b");
        var recent = s.recentMessages(2);
        // 修改 session 不影响已返回的 list
        s.appendUserText("c");
        assertEquals(2, recent.size());
    }
```

同时在 ChatSessionTest 的 import 区确认已有 `import com.maplecode.provider.ContentBlock;`。

- [ ] **Step 3: Run ChatSessionTest**

Run: `mvn test -Dtest=ChatSessionTest -pl . -q`
Expected: PASS

- [ ] **Step 4: Modify ReplLoop — add MemoryManager field and /memory commands**

在 `ReplLoop.java` 字段区新增：

```java
    private final com.maplecode.memory.MemoryManager memoryManager;  // nullable
```

修改构造器，追加 `MemoryManager` 参数（在 `CompactCoordinator coord` 之后）：

```java
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    SessionArchive sessionArchive, CompactCoordinator coord,
                    com.maplecode.memory.MemoryManager memoryManager) {
        // ... existing assignments ...
        this.memoryManager = memoryManager;
        // ... rest ...
    }
```

更新 8 参数向后兼容构造器：

```java
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig, null, null, null);
    }
```

在 `run()` 方法中，`agent.run(trimmed, printer)` 之后、`printer.newline()` 之前新增记忆提取触发：

```java
            // 普通对话：委托给 AgentLoop
            agent.run(trimmed, printer);
            // 记忆提取：异步，不阻塞用户交互
            if (memoryManager != null && appConfig.memoryConfig() != null && appConfig.memoryConfig().enabled()) {
                int maxCtx = appConfig.memoryConfig().maxContextMessages();
                memoryManager.extractAsync(agent.session().recentMessages(maxCtx));
            }
            printer.newline();
```

在 `/cancel` 命令之后、`agent.run(trimmed, printer)` 之前新增 `/memory` 命令：

```java
            // /memory
            if (trimmed.equals("/memory list")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else {
                    printer.info(memoryManager.listMemories());
                }
                continue;
            }
            if (trimmed.equals("/memory clear")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else {
                    memoryManager.clearAll();
                    printer.info("all memories cleared");
                }
                continue;
            }
            if (trimmed.equals("/memory extract")) {
                if (memoryManager == null) {
                    printer.error("memory not enabled");
                } else if (appConfig.memoryConfig() == null || !appConfig.memoryConfig().enabled()) {
                    printer.error("memory not enabled in config");
                } else {
                    int maxCtx = appConfig.memoryConfig().maxContextMessages();
                    memoryManager.extractSync(agent.session().recentMessages(maxCtx));
                    printer.info("memory extraction completed");
                }
                continue;
            }
```

- [ ] **Step 5: Update App.main — create MemoryManager and pass to ReplLoop**

在 `App.java` 中，`CompactCoordinator coord = ...` 之后新增：

```java
        // 长期记忆（v7.3）
        MemoryConfig memoryCfg = MemoryConfig.fromAppConfig(raw);
        com.maplecode.memory.MemoryManager memoryManager = null;
        if (memoryCfg.enabled()) {
            memoryManager = new com.maplecode.memory.MemoryManager(memoryCfg, provider, memoryStore, raw.model());
            Runtime.getRuntime().addShutdownHook(new Thread(memoryManager, "memory-shutdown"));
        }
```

更新 `ReplLoop` 构造调用，追加 `memoryManager`：

```java
        ReplLoop repl = new ReplLoop(raw, provider, new StreamPrinter(System.out),
            reader, registry, executor, engine, agentConfig, sessionArchive, coord, memoryManager);
```

在 import 区新增：

```java
import com.maplecode.memory.MemoryConfig;
import com.maplecode.memory.MemoryManager;
```

- [ ] **Step 6: Update banner to include /memory**

在 `ReplLoop.run()` 的 `printer.banner(...)` 中追加 `/memory 记忆管理`：

```java
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/new 新会话，/resume 恢复会话，/compact 压缩上下文，/tools 列出工具，/mode 权限模式，/plan 规划，/do 执行计划，/cancel 取消，/memory 记忆管理，\"\"\" 开始多行输入");
```

- [ ] **Step 7: Run full test suite**

Run: `mvn test -q`
Expected: 全部 PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/maplecode/session/ChatSession.java \
        src/test/java/com/maplecode/session/ChatSessionTest.java \
        src/main/java/com/maplecode/ui/ReplLoop.java \
        src/main/java/com/maplecode/App.java
git commit -m "feat(memory): T7 ChatSession.recentMessages + ReplLoop /memory 命令"
```

---

## Task 8: 更新配置示例 + 手动验证

**Files:**
- Modify: `maplecode.yaml.example`（末尾追加 memory 配置）

- [ ] **Step 1: Append memory section to maplecode.yaml.example**

在文件末尾追加：

```yaml

# ===== v7.3 自动长期记忆 =====

# 每轮 Agent Loop 结束后异步调 LLM 分析对话，自动新增/修改/删除长期记忆。
# 记忆在下次启动时注入系统提示词，实现跨会话知识积累。
# memory:
#   enabled: true
#   memory_model: claude-haiku-4-5    # 记忆提取用模型（可选，默认复用主模型）
#   max_context_messages: 10          # 提取时看最近几条消息（默认 10）
```

- [ ] **Step 2: Run full test suite**

Run: `mvn test -q`
Expected: 全部 PASS

- [ ] **Step 3: Manual smoke test**

```bash
# 构建
mvn package -q

# 运行（需要在有 API key 的环境中测试）
java -jar target/maple-code-java-0.1.0.jar

# 在 REPL 中测试：
# 1. 随便聊几句
# 2. /memory list — 应显示 "(no memories)"
# 3. /memory extract — 应触发一次记忆提取
# 4. /memory list — 应显示提取出的记忆
# 5. /memory clear — 应清空
# 6. /memory list — 应显示 "(no memories)"
```

- [ ] **Step 4: Commit**

```bash
git add maplecode.yaml.example
git commit -m "feat(memory): T8 更新 maplecode.yaml.example memory 配置示例"
```

---

## Task 9: 清理 + 最终验证

- [ ] **Step 1: Verify all tests pass**

Run: `mvn test -q`
Expected: 全部 PASS

- [ ] **Step 2: Verify compilation**

Run: `mvn package -q -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Review all memory-related files exist**

```bash
ls -la src/main/java/com/maplecode/memory/
# 应有 10 个文件：MemoryConfig, MemoryCategory, MemoryScope, MemoryEntry,
#                  MemoryOp, MemoryOpsResult, MemoryExtractor, MemoryStore,
#                  MemoryFailureCounter, MemoryManager

ls -la src/test/java/com/maplecode/memory/
# 应有 7 个文件：MemoryConfigTest, MemoryCategoryTest, MemoryOpTest,
#                 MemoryExtractorTest, MemoryStoreTest, MemoryFailureCounterTest,
#                 MemoryManagerTest
```

- [ ] **Step 4: Final commit if any fixes needed**

```bash
git commit -m "feat(memory): v7.3 自动长期记忆功能完成"
```
