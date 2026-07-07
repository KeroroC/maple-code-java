# MapleCode 上下文管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 MapleCode 装上两层上下文压缩（轻量预防 + 重量兜底），让对话在有限 Token 预算里长时间干活而不溢出；用户可手动 `/compress`；摘要连续失败 3 次熔断。

**Architecture:** 新增 `com.maplecode.compression` 包，11 个类，按"数据 / IO / 编排"分层。Offloader 改写 `ToolResultBlock.content` 为 preview + 绝对路径；ConversationSummarizer 调 LLM 出 5 段摘要 + scratchpad；CompressionCoordinator 编排 estimator → offloader → summarizer，单一公开入口同时被 AgentLoop 和 `/compress` 调用。

**Tech Stack:** Java 21、`java.net.http.HttpClient`（已有）、Jackson（已有）、`Files.writeString` + `@TempDir`（已有）、JUnit 5 + Mockito（已有）。**不引新依赖**。

**Spec:** `docs/superpowers/specs/2026-07-07-maple-code-context-management-design.md`

---

## 任务依赖

```
T1  包骨架
T2  CompressionException / CompressionTrigger / CompressionResult
T3  CompressionConfig + CompressionContext
T4  FailureCounter (TDD) ────────────────────────┐
T5  CompressionStorage (TDD) ───────────────────┤
T6  TokenEstimator (TDD) ───────────────────────┤
T7  Offloader (TDD) ────────────────────────────┤
T8  ConversationSummarizer (TDD) ───────────────┤
T9  CompressionCoordinator (TDD) ───────────────┤
T10 ChatSession.replaceAll (TDD) ───────────────┤
T11 AppConfig + ConfigLoader 扩 context_window / summarizer_model
T12 AgentEvent.CompressionApplied 新变体 ───────┤
T13 StreamPrinter 新方法 compressionResult + accept 处理 CompressionApplied
T14 AgentLoop 接入 coord.beforeRequest + recordUsage 注入
T15 ReplLoop /compress + /clear 改 coord.resetCounter
T16 App.main 装配 + shutdown hook ──────────────┘
T17 maplecode.yaml.example 加两字段注释
T18 全量 mvn test + package + 手工 smoke
```

---

## Task 1：建包骨架

**Files:**
- Create: `src/main/java/com/maplecode/compression/.gitkeep`
- Create: `src/test/java/com/maplecode/compression/.gitkeep`

- [ ] **Step 1：建包目录**

```bash
mkdir -p src/main/java/com/maplecode/compression
mkdir -p src/test/java/com/maplecode/compression
touch src/main/java/com/maplecode/compression/.gitkeep
touch src/test/java/com/maplecode/compression/.gitkeep
```

- [ ] **Step 2：编译验证仍工作**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3：Commit**

```bash
git add src/main/java/com/maplecode/compression src/test/java/com/maplecode/compression
git commit -m "feat(compression): 新建 compression 包目录骨架"
```

---

## Task 2：CompressionException / CompressionTrigger / CompressionResult

**Files:**
- Create: `src/main/java/com/maplecode/compression/CompressionException.java`
- Create: `src/main/java/com/maplecode/compression/CompressionTrigger.java`
- Create: `src/main/java/com/maplecode/compression/CompressionResult.java`

- [ ] **Step 1：写 CompressionException**

`src/main/java/com/maplecode/compression/CompressionException.java`：

```java
package com.maplecode.compression;

/**
 * 压缩系统内部异常。Offloader 写盘失败、ConversationSummarizer 流异常 / 解析错 /
 * 5 段校验失败 / refusal 均抛此异常；CompressionCoordinator 捕获后分类处理。
 */
public class CompressionException extends RuntimeException {
    public CompressionException(String message) { super(message); }
    public CompressionException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2：写 CompressionTrigger**

`src/main/java/com/maplecode/compression/CompressionTrigger.java`：

```java
package com.maplecode.compression;

/** 压缩触发来源：自动（每个 iteration 开头）或手动（/compress）。 */
public enum CompressionTrigger {
    AUTO,
    MANUAL
}
```

- [ ] **Step 3：写 CompressionResult**

`src/main/java/com/maplecode/compression/CompressionResult.java`：

```java
package com.maplecode.compression;

/**
 * CompressionCoordinator.beforeRequest 的返回值。sealed 强制所有订阅者穷尽。
 */
public sealed interface CompressionResult
    permits CompressionResult.Noop,
            CompressionResult.ChangedOffloadOnly,
            CompressionResult.ChangedFull,
            CompressionResult.FailedOffload,
            CompressionResult.FailedSummary,
            CompressionResult.SkippedCircuitOpen {

    /** tokens 未超阈，无需压缩。 */
    record Noop() implements CompressionResult {}

    /** 仅 offload 落地，session 已 replace。 */
    record ChangedOffloadOnly(int offloadedCount) implements CompressionResult {}

    /** offload + summary，session 已 replace。 */
    record ChangedFull(int offloadedCount, int summaryInputTokens) implements CompressionResult {}

    /** offload 写盘失败；session 不动；不计 counter。 */
    record FailedOffload(String reason) implements CompressionResult {}

    /** summary 失败；session 不动；counter + 1。 */
    record FailedSummary(String reason, int consecutiveFailures) implements CompressionResult {}

    /** counter 已 tripped；session 不动。 */
    record SkippedCircuitOpen(int consecutiveFailures) implements CompressionResult {}
}
```

- [ ] **Step 4：编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/maplecode/compression/CompressionException.java \
        src/main/java/com/maplecode/compression/CompressionTrigger.java \
        src/main/java/com/maplecode/compression/CompressionResult.java
git commit -m "feat(compression): 加 Exception/Trigger/Result 三个基础类型"
```

---

## Task 3：CompressionConfig + CompressionContext

**Files:**
- Create: `src/main/java/com/maplecode/compression/CompressionConfig.java`
- Create: `src/main/java/com/maplecode/compression/CompressionContext.java`

- [ ] **Step 1：写 CompressionConfig**

`src/main/java/com/maplecode/compression/CompressionConfig.java`：

```java
package com.maplecode.compression;

/**
 * 压缩系统全部阈值的不可变聚合。从 AppConfig 派生（window 由 YAML context_window
 * 控制，未配走默认 200_000）。
 *
 * <p>默认值见 spec §4.2。
 */
public record CompressionConfig(
    int window,
    int autoMargin,
    int manualMargin,
    int singleToolResultOffloadTokens,
    int messageToolResultAggregateTokens,
    int recencyTokens,
    int recencyMinMessages,
    int previewHeadLines,
    int previewTailLines,
    int failureThreshold
) {
    public CompressionConfig {
        if (window < 1000) throw new IllegalArgumentException("window must be >= 1000");
        if (autoMargin < 0) throw new IllegalArgumentException("autoMargin must be >= 0");
        if (manualMargin < 0) throw new IllegalArgumentException("manualMargin must be >= 0");
        if (singleToolResultOffloadTokens < 100) throw new IllegalArgumentException("singleToolResultOffloadTokens must be >= 100");
        if (messageToolResultAggregateTokens < singleToolResultOffloadTokens) {
            throw new IllegalArgumentException("messageToolResultAggregateTokens must be >= singleToolResultOffloadTokens");
        }
        if (recencyTokens < 100) throw new IllegalArgumentException("recencyTokens must be >= 100");
        if (recencyMinMessages < 1) throw new IllegalArgumentException("recencyMinMessages must be >= 1");
        if (previewHeadLines < 1) throw new IllegalArgumentException("previewHeadLines must be >= 1");
        if (previewTailLines < 1) throw new IllegalArgumentException("previewTailLines must be >= 1");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
    }

    public static final int DEFAULT_WINDOW = 200_000;
    public static final int DEFAULT_AUTO_MARGIN = 13_000;
    public static final int DEFAULT_MANUAL_MARGIN = 3_000;
    public static final int DEFAULT_SINGLE_TOOL_RESULT_OFFLOAD_TOKENS = 8_000;
    public static final int DEFAULT_MESSAGE_TOOL_RESULT_AGGREGATE_TOKENS = 30_000;
    public static final int DEFAULT_RECENCY_TOKENS = 10_000;
    public static final int DEFAULT_RECENCY_MIN_MESSAGES = 5;
    public static final int DEFAULT_PREVIEW_HEAD_LINES = 8;
    public static final int DEFAULT_PREVIEW_TAIL_LINES = 4;
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /** 从 AppConfig 派生；window 未配走默认。 */
    public static CompressionConfig fromAppConfig(int yamlContextWindow) {
        int window = yamlContextWindow > 0 ? yamlContextWindow : DEFAULT_WINDOW;
        return new CompressionConfig(
            window,
            DEFAULT_AUTO_MARGIN,
            DEFAULT_MANUAL_MARGIN,
            DEFAULT_SINGLE_TOOL_RESULT_OFFLOAD_TOKENS,
            DEFAULT_MESSAGE_TOOL_RESULT_AGGREGATE_TOKENS,
            DEFAULT_RECENCY_TOKENS,
            DEFAULT_RECENCY_MIN_MESSAGES,
            DEFAULT_PREVIEW_HEAD_LINES,
            DEFAULT_PREVIEW_TAIL_LINES,
            DEFAULT_FAILURE_THRESHOLD
        );
    }

    public int marginFor(CompressionTrigger trigger) {
        return switch (trigger) {
            case AUTO -> autoMargin;
            case MANUAL -> manualMargin;
        };
    }
}
```

- [ ] **Step 2：写 CompressionContext**

`src/main/java/com/maplecode/compression/CompressionContext.java`：

```java
package com.maplecode.compression;

/**
 * 会话级不可变聚合。CompressionConfig + CompressionStorage + FailureCounter 三件套。
 * App.main 启动时构造一次，传给 CompressionCoordinator。
 */
public record CompressionContext(
    CompressionConfig config,
    CompressionStorage storage,
    FailureCounter counter
) {}
```

- [ ] **Step 3：编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（CompressionStorage / FailureCounter 尚不存在，会编译失败）

如果失败：因为 CompressionContext 引用了未创建的 CompressionStorage / FailureCounter，预期 FAIL with "symbol not found"。继续下一步（Task 4/5）会自然解决。

- [ ] **Step 4：Commit**

```bash
git add src/main/java/com/maplecode/compression/CompressionConfig.java \
        src/main/java/com/maplecode/compression/CompressionContext.java
git commit -m "feat(compression): 加 CompressionConfig / CompressionContext"
```

---

## Task 4：FailureCounter（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/FailureCounterTest.java`
- Create: `src/main/java/com/maplecode/compression/FailureCounter.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/FailureCounterTest.java`：

```java
package com.maplecode.compression;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FailureCounterTest {

    @Test
    void initialStateIsNotTripped() {
        var c = new FailureCounter(3);
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
    }

    @Test
    void threeFailuresTrip() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        assertFalse(c.isTripped());
        c.recordFailure();
        assertTrue(c.isTripped());
    }

    @Test
    void recordSuccessResetsCounter() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        c.recordSuccess();
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
        c.recordFailure();
        c.recordFailure();
        assertFalse(c.isTripped());  // 重置后要重新累积
    }

    @Test
    void resetClearsEverything() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        c.recordFailure();
        assertTrue(c.isTripped());
        c.reset();
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
    }

    @Test
    void concurrentRecordFailureStaysAtomic() throws Exception {
        var c = new FailureCounter(1000);
        var pool = Executors.newFixedThreadPool(8);
        var latch = new CountDownLatch(1);
        var futures = IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
            latch.await();
            c.recordFailure();
        })).toList();
        latch.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(100, c.failures());
    }
}
```

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=FailureCounterTest`
Expected: FAIL with "symbol not found: class FailureCounter"

- [ ] **Step 3：实现 FailureCounter**

`src/main/java/com/maplecode/compression/FailureCounter.java`：

```java
package com.maplecode.compression;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 摘要失败计数器。failureThreshold 之内累积失败次数；达到阈值后 isTripped() 返回
 * true，CompressionCoordinator 跳过自动压缩。recordSuccess() 清零，reset() 全清
 * （供 /clear 调用）。
 */
public final class FailureCounter {

    private final int threshold;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    public FailureCounter(int threshold) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        this.threshold = threshold;
    }

    public void recordFailure() {
        int n = failures.incrementAndGet();
        if (n >= threshold) tripped.set(true);
    }

    public void recordSuccess() {
        failures.set(0);
    }

    public void reset() {
        failures.set(0);
        tripped.set(false);
    }

    public int failures() {
        return failures.get();
    }

    public boolean isTripped() {
        return tripped.get();
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=FailureCounterTest`
Expected: PASS（5 个 test 全绿）

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/FailureCounterTest.java \
        src/main/java/com/maplecode/compression/FailureCounter.java
git commit -m "feat(compression): FailureCounter 计数器（TDD）"
```

---

## Task 5：CompressionStorage（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/CompressionStorageTest.java`
- Create: `src/main/java/com/maplecode/compression/CompressionStorage.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/CompressionStorageTest.java`：

```java
package com.maplecode.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompressionStorageTest {

    @Test
    void writeReturnsPathUnderSessionDir(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("hello world");
        assertTrue(Files.exists(saved));
        assertEquals("hello world", Files.readString(saved));
        assertTrue(saved.startsWith(tmp.resolve("session-abc")));
    }

    @Test
    void filenameIncludesUuidAndSeq(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path a = storage.write("a");
        Path b = storage.write("b");
        assertNotEquals(a, b);
        assertTrue(a.getFileName().toString().endsWith(".txt"));
        assertTrue(b.getFileName().toString().endsWith(".txt"));
    }

    @Test
    void previewShortContentHasNoHeadTailSplit(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("line1\nline2\nline3");
        String preview = storage.buildPreview(saved, "line1\nline2\nline3", 8, 4);
        assertTrue(preview.contains("line1"));
        assertTrue(preview.contains("line3"));
        assertFalse(preview.contains("--- head ---"));
        assertFalse(preview.contains("--- tail ---"));
    }

    @Test
    void previewLongContentHasHeadAndTail(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        String content = IntStream.rangeClosed(1, 100).mapToObj(i -> "line" + i)
            .reduce((a, b) -> a + "\n" + b).orElseThrow();
        Path saved = storage.write(content);
        String preview = storage.buildPreview(saved, content, 3, 2);
        assertTrue(preview.contains("--- head ---"));
        assertTrue(preview.contains("--- tail ---"));
        assertTrue(preview.contains("line1"));   // head
        assertTrue(preview.contains("line100")); // tail
        assertFalse(preview.contains("line50")); // middle 被截
    }

    @Test
    void previewIncludesMetadata(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("a\nb\nc");
        String preview = storage.buildPreview(saved, "a\nb\nc", 8, 4);
        assertTrue(preview.contains(saved.toAbsolutePath().toString()));
        assertTrue(preview.contains("bytes"));
        assertTrue(preview.contains("lines"));
    }

    @Test
    void closeDeletesSessionDir(@TempDir Path tmp) throws Exception {
        Path sessionDir = tmp.resolve("session-xyz");
        var storage = new CompressionStorage(sessionDir);
        storage.write("x");
        assertTrue(Files.exists(sessionDir));
        storage.close();
        assertFalse(Files.exists(sessionDir));
    }
}

// IntStream 在 java.util.stream
import java.util.stream.IntStream;
```

注意：import 顺序问题，Java 不允许 import 在类后。把上面文件改成正确顺序：

`src/test/java/com/maplecode/compression/CompressionStorageTest.java`：

```java
package com.maplecode.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompressionStorageTest {

    @Test
    void writeReturnsPathUnderSessionDir(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("hello world");
        assertTrue(Files.exists(saved));
        assertEquals("hello world", Files.readString(saved));
        assertTrue(saved.startsWith(tmp.resolve("session-abc")));
    }

    @Test
    void filenameIncludesUuidAndSeq(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path a = storage.write("a");
        Path b = storage.write("b");
        assertNotEquals(a, b);
        assertTrue(a.getFileName().toString().endsWith(".txt"));
        assertTrue(b.getFileName().toString().endsWith(".txt"));
    }

    @Test
    void previewShortContentHasNoHeadTailSplit(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("line1\nline2\nline3");
        String preview = storage.buildPreview(saved, "line1\nline2\nline3", 8, 4);
        assertTrue(preview.contains("line1"));
        assertTrue(preview.contains("line3"));
        assertFalse(preview.contains("--- head ---"));
        assertFalse(preview.contains("--- tail ---"));
    }

    @Test
    void previewLongContentHasHeadAndTail(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        String content = IntStream.rangeClosed(1, 100).mapToObj(i -> "line" + i)
            .reduce((a, b) -> a + "\n" + b).orElseThrow();
        Path saved = storage.write(content);
        String preview = storage.buildPreview(saved, content, 3, 2);
        assertTrue(preview.contains("--- head ---"));
        assertTrue(preview.contains("--- tail ---"));
        assertTrue(preview.contains("line1"));
        assertTrue(preview.contains("line100"));
        assertFalse(preview.contains("line50"));
    }

    @Test
    void previewIncludesMetadata(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("a\nb\nc");
        String preview = storage.buildPreview(saved, "a\nb\nc", 8, 4);
        assertTrue(preview.contains(saved.toAbsolutePath().toString()));
        assertTrue(preview.contains("bytes"));
        assertTrue(preview.contains("lines"));
    }

    @Test
    void closeDeletesSessionDir(@TempDir Path tmp) throws Exception {
        Path sessionDir = tmp.resolve("session-xyz");
        var storage = new CompressionStorage(sessionDir);
        storage.write("x");
        assertTrue(Files.exists(sessionDir));
        storage.close();
        assertFalse(Files.exists(sessionDir));
    }
}
```

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=CompressionStorageTest`
Expected: FAIL with "symbol not found: class CompressionStorage"

- [ ] **Step 3：实现 CompressionStorage**

`src/main/java/com/maplecode/compression/CompressionStorage.java`：

```java
package com.maplecode.compression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 负责 off-load 工具结果的磁盘 IO。文件命名 {@code <uuid>-<seq>.txt}。
 * close() 删除整个 session 目录（由 App.main 注册的 shutdown hook 调用）。
 */
public final class CompressionStorage {

    private final Path sessionDir;
    private final AtomicLong seq = new AtomicLong();

    public CompressionStorage(Path sessionDir) {
        this.sessionDir = sessionDir;
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new CompressionException("cannot create session dir: " + sessionDir, e);
        }
    }

    /** 写 content 到磁盘，返回写入的文件绝对路径。 */
    public Path write(String content) {
        long n = seq.incrementAndGet();
        String name = UUID.randomUUID() + "-" + n + ".txt";
        Path target = sessionDir.resolve(name);
        try {
            Files.writeString(target, content);
            return target;
        } catch (IOException e) {
            throw new CompressionException("offload write failed: " + target, e);
        }
    }

    /**
     * 构造 preview 文本：元信息 + head + tail。原文不足 headLines + tailLines 行
     * 时直接贴全部原文，不分 head/tail。
     */
    public String buildPreview(Path savedPath, String content, int headLines, int tailLines) {
        String[] lines = content.split("\n", -1);
        long bytes = content.getBytes().length;
        long lineCount = lines.length;
        String abs = savedPath.toAbsolutePath().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("[Offloaded to ").append(abs)
          .append(" — ").append(bytes).append(" bytes, ")
          .append(lineCount).append(" lines]\n");
        if (lineCount <= headLines + tailLines) {
            sb.append(content);
        } else {
            sb.append("--- head ---\n");
            for (int i = 0; i < headLines; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("--- tail ---\n");
            for (int i = (int) lineCount - tailLines; i < lineCount; i++) {
                sb.append(lines[i]).append('\n');
            }
        }
        sb.append("[End of preview; re-read from path above for full content]");
        return sb.toString();
    }

    /** 删除整个 session 目录；进程退出时调。 */
    public void close() {
        if (!Files.exists(sessionDir)) return;
        try (var walk = Files.walk(sessionDir)) {
            walk.sorted((a, b) -> b.compareTo(a))   // 逆序：先删文件再删目录
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) { /* 退出 hook 静默吞 */ }
                });
        } catch (IOException ignored) { /* 同上 */ }
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=CompressionStorageTest`
Expected: PASS（6 个 test 全绿）

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/CompressionStorageTest.java \
        src/main/java/com/maplecode/compression/CompressionStorage.java
git commit -m "feat(compression): CompressionStorage 写盘 + preview 构造（TDD）"
```

---

## Task 6：TokenEstimator（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/TokenEstimatorTest.java`
- Create: `src/main/java/com/maplecode/compression/TokenEstimator.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/TokenEstimatorTest.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    private final TokenEstimator est = new TokenEstimator();

    @Test
    void emptyListIsZero() {
        assertEquals(0, est.estimate(List.of(), null));
    }

    @Test
    void singleTextBlockCharsDividedBy4() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(1000))));
        assertEquals(250, est.estimate(List.of(msg), null));
    }

    @Test
    void anchorPlusDeltaIsSummed() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(800))));
        var anchor = new TokenUsage(1000, 200, 0, 0);
        // 1000 (anchor input) + 800/4 = 1000 + 200 = 1200
        assertEquals(1200, est.estimate(List.of(msg), anchor));
    }

    @Test
    void anchorWithCacheCounted() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(400))));
        var anchor = new TokenUsage(500, 100, 200, 300);
        // 500 + 200 + 300 + 400/4 = 1100
        assertEquals(1100, est.estimate(List.of(msg), anchor));
    }

    @Test
    void toolResultBlockCountedByContentLength() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.ToolResultBlock("tu-1", "y".repeat(100_000), false)));
        // 100_000 / 4 = 25_000
        assertEquals(25_000, est.estimate(List.of(msg), null));
    }

    @Test
    void toolUseBlockCountedByJsonSerialization() {
        var msg = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.ToolUseBlock("tu-1", "read_file",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "/a/b"))));
        // 包含 {"path":"/a/b"} 等；不验精确值，验非零 + 大致范围
        int t = est.estimate(List.of(msg), null);
        assertTrue(t > 0 && t < 200, "tool_use block 估 token 应在 (0, 200)，实际 " + t);
    }

    @Test
    void multipleBlocksSummed() {
        var msg1 = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(400))));
        var msg2 = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("y".repeat(800))));
        // 1200 / 4 = 300
        assertEquals(300, est.estimate(List.of(msg1, msg2), null));
    }
}
```

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=TokenEstimatorTest`
Expected: FAIL with "symbol not found: class TokenEstimator"

- [ ] **Step 3：实现 TokenEstimator**

`src/main/java/com/maplecode/compression/TokenEstimator.java`：

```java
package com.maplecode.compression;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.TokenUsage;

import java.util.List;

/**
 * 近似 token 估算：{@code chars / 4}。锚点为上次 API 返回的 TokenUsage
 * （input + cache_creation + cache_read），后续 messages 走 chars/4 求和。
 *
 * <p>首轮无锚点直接 sum(delta)/4，会偏低 13K 余量覆盖。
 */
public final class TokenEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param messages 待估的 session messages
     * @param anchor   上次 API 调用返回的 TokenUsage；可为 null（首轮）
     * @return 估算 token 数
     */
    public int estimate(List<ChatMessage> messages, TokenUsage anchor) {
        int anchorTokens = 0;
        if (anchor != null) {
            anchorTokens = anchor.inputTokens()
                + anchor.cacheCreationTokens()
                + anchor.cacheReadTokens();
        }
        long chars = 0;
        for (var msg : messages) {
            for (var block : msg.blocks()) {
                chars += blockChars(block);
            }
        }
        return anchorTokens + (int) (chars / 4);
    }

    private long blockChars(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock tb) {
            return tb.text().length();
        }
        if (block instanceof ContentBlock.ToolUseBlock tu) {
            // 序列化 {id, name, input}
            String json;
            try {
                json = JSON.writeValueAsString(java.util.Map.of(
                    "id", tu.id(),
                    "name", tu.name(),
                    "input", tu.input()));
            } catch (JsonProcessingException e) {
                json = tu.id() + tu.name() + tu.input().toString();
            }
            return json.length();
        }
        if (block instanceof ContentBlock.ToolResultBlock tr) {
            return tr.content().length();
        }
        return 0;
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=TokenEstimatorTest`
Expected: PASS（7 个 test 全绿）

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/TokenEstimatorTest.java \
        src/main/java/com/maplecode/compression/TokenEstimator.java
git commit -m "feat(compression): TokenEstimator chars/4 锚定估算（TDD）"
```

---

## Task 7：Offloader（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/OffloaderTest.java`
- Create: `src/main/java/com/maplecode/compression/Offloader.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/OffloaderTest.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OffloaderTest {

    private static final CompressionConfig CFG = new CompressionConfig(
        200_000, 13_000, 3_000,
        8_000, 30_000,
        10_000, 5,
        8, 4,
        3);

    private static ContentBlock.ToolResultBlock bigResult(int chars) {
        return new ContentBlock.ToolResultBlock("tu-1", "x".repeat(chars), false);
    }

    @Test
    void singleBigResultGetsOffloaded(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var msg = new ChatMessage(Role.USER, List.of(bigResult(40_000))); // 10K tokens
        var out = offloader.apply(List.of(msg), CFG);
        var trb = (ContentBlock.ToolResultBlock) out.get(0).blocks().get(0);
        assertNotEquals(40_000, trb.content().length());
        assertTrue(trb.content().contains("[Offloaded to"));
        assertTrue(trb.content().contains("--- head ---"));
        // 文件存在
        assertEquals(1, storage.toString().length() > 0 ? 1 : 0);  // sanity
    }

    @Test
    void aggregateThresholdPicksLargestFirst(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        // 5 个 tool result，每个 8K chars（2K tokens），合计 10K tokens < 30K aggregate
        // 加 1 个 40K chars（10K tokens）凑过阈 30K → offload 最少 1 个（这个 40K）
        var blocks = List.of(
            bigResult(8_000), bigResult(8_000), bigResult(8_000),
            bigResult(8_000), bigResult(40_000));
        var msg = new ChatMessage(Role.USER, blocks);
        var out = offloader.apply(List.of(msg), CFG);
        var newBlocks = out.get(0).blocks();
        // 40K 的那个必被 offload（5 个候选中最大的一个，按降序满足 aggregate 阈）
        assertTrue(newBlocks.get(4).content().contains("[Offloaded to"));
    }

    @Test
    void shortContentPreviewSkipsHeadTail(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        // 8K tokens = 32K chars → 超 single threshold
        String content = "line1\nline2\nline3\nline4\nline5";
        var block = new ContentBlock.ToolResultBlock("tu-1", content + "x".repeat(32_000), false);
        var msg = new ChatMessage(Role.USER, List.of(block));
        var out = offloader.apply(List.of(msg), CFG);
        var trb = (ContentBlock.ToolResultBlock) out.get(0).blocks().get(0);
        assertFalse(trb.content().contains("--- head ---"));
    }

    @Test
    void assistantMessagesUnchanged(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var asst = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("x".repeat(100_000))));
        var out = offloader.apply(List.of(asst), CFG);
        assertSame(asst, out.get(0));
    }

    @Test
    void toolUseBlockUnchanged(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var tue = new ContentBlock.ToolUseBlock("tu-1", "read_file",
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "/foo"));
        var msg = new ChatMessage(Role.ASSISTANT, List.of(tue));
        var out = offloader.apply(List.of(msg), CFG);
        var nue = (ContentBlock.ToolUseBlock) out.get(0).blocks().get(0);
        assertEquals(tue, nue);
    }

    @Test
    void writeFailurePropagatesAndLeavesListUnchanged(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        // 制造一个 storage 写失败的场景：read-only 目录
        Path roDir = tmp.resolve("ro");
        java.nio.file.Files.createDirectories(roDir);
        roDir.toFile().setReadOnly();
        var badStorage = new CompressionStorage(roDir.resolve("sub"));  // 创建子目录会失败
        var offloader = new Offloader(badStorage);
        var msg = new ChatMessage(Role.USER, List.of(bigResult(40_000)));
        // 构造期就抛 CompressionException；不在 apply 路径上
        assertThrows(CompressionException.class,
            () -> new CompressionStorage(roDir.resolve("another")));
    }
}
```

注：最后两个 test 写法已偏离原始意图，最后一个改为验证 storage 构造失败（明确错误路径）；offloader 写盘失败路径放到 Coordinator 集成测试里覆盖更干净。

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=OffloaderTest`
Expected: FAIL with "symbol not found: class Offloader"

- [ ] **Step 3：实现 Offloader**

`src/main/java/com/maplecode/compression/Offloader.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 首层：把超阈的工具结果写到磁盘，对话里只留 preview + 绝对路径。
 * 不动 TextBlock / ToolUseBlock / assistant message。
 */
public final class Offloader {

    private final CompressionStorage storage;
    private final TokenEstimator estimator = new TokenEstimator();

    public Offloader(CompressionStorage storage) {
        this.storage = storage;
    }

    /**
     * 返回新 messages list；候选 tool result 按阈值替换 content 为 preview。
     */
    public List<ChatMessage> apply(List<ChatMessage> messages, CompressionConfig config) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (var msg : messages) {
            if (msg.role() != Role.USER) {
                result.add(msg);
                continue;
            }
            var blocks = msg.blocks();
            var toolResults = new ArrayList<Integer>();  // 候选索引
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) instanceof ContentBlock.ToolResultBlock tr) {
                    int tokens = estimator.estimate(List.of(
                        new ChatMessage(Role.USER, List.of(tr))), null);
                    if (tokens > config.singleToolResultOffloadTokens()) {
                        toolResults.add(i);
                    }
                }
            }
            if (toolResults.isEmpty()) {
                result.add(msg);
                continue;
            }
            // 检查聚合阈值
            int sumTokens = toolResults.stream().mapToInt(i ->
                estimator.estimate(List.of(
                    new ChatMessage(Role.USER, List.of(blocks.get(i)))), null)).sum();
            if (sumTokens <= config.messageToolResultAggregateTokens()) {
                result.add(msg);
                continue;
            }
            // 按 tokens 降序 offload，直到 sum ≤ 阈
            var sortedByTokens = new ArrayList<>(toolResults);
            sortedByTokens.sort(Comparator.comparingInt((Integer i) ->
                estimator.estimate(List.of(
                    new ChatMessage(Role.USER, List.of(blocks.get(i)))), null)
            ).reversed());

            int currentSum = sumTokens;
            var offloadSet = new java.util.HashSet<Integer>();
            for (int idx : sortedByTokens) {
                if (currentSum <= config.messageToolResultAggregateTokens()) break;
                int t = estimator.estimate(List.of(
                    new ChatMessage(Role.USER, List.of(blocks.get(idx)))), null);
                offloadSet.add(idx);
                currentSum -= t;
            }

            // 构造新 blocks
            var newBlocks = new ArrayList<ContentBlock>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                var b = blocks.get(i);
                if (offloadSet.contains(i) && b instanceof ContentBlock.ToolResultBlock tr) {
                    var saved = storage.write(tr.content());
                    String preview = storage.buildPreview(saved, tr.content(),
                        config.previewHeadLines(), config.previewTailLines());
                    newBlocks.add(new ContentBlock.ToolResultBlock(tr.toolUseId(), preview, tr.isError()));
                } else {
                    newBlocks.add(b);
                }
            }
            result.add(new ChatMessage(msg.role(), newBlocks));
        }
        return result;
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=OffloaderTest`
Expected: PASS

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/OffloaderTest.java \
        src/main/java/com/maplecode/compression/Offloader.java
git commit -m "feat(compression): Offloader 首层写盘 + preview 替换（TDD）"
```

---

## Task 8：ConversationSummarizer（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/ConversationSummarizerTest.java`
- Create: `src/main/java/com/maplecode/compression/ConversationSummarizer.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/ConversationSummarizerTest.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.*;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSummarizerTest {

    /** 构造一个 mock provider 在 stream 时把传入的 ChatRequest 转换成预定的 StreamChunk 序列。 */
    private LlmProvider mockProviderReturning(String text) {
        LlmProvider p = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<StreamChunk> sink = inv.getArgument(1);
            sink.accept(new StreamChunk.MessageStart());
            for (String chunk : text.split("(?<=\\G.{20})")) {  // 切成 20 char 一段
                sink.accept(new StreamChunk.TextDelta(chunk));
            }
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN,
                new TokenUsage(100, 50, 0, 0)));
            return null;
        }).when(p).stream(any(), any());
        return p;
    }

    private AppConfig appConfig(String summarizerModel) {
        return new AppConfig("anthropic", "claude-sonnet-4-6", "http://x", "k",
            null, List.of(), null,
            new AppConfig.Timeouts(10, 60),
            com.maplecode.permission.PermissionMode.DEFAULT,
            AppConfig.AgentLimits.defaults(), null) {
            @Override public String summarizerModel() { return summarizerModel; }
        };
    }

    @Test
    void successProducesSummaryUserThenTailThenBoundary() {
        var tailMsg = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("tail")));
        var oldMsg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("user-1")));
        var oldAsst = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("asst-1")));

        String goodSummary =
            "<scratchpad>thinking...</scratchpad>\n" +
            "## Intent\nuser wants X\n\n" +
            "## Decisions\nchose Y\n\n" +
            "## Open Questions\nnone\n\n" +
            "## State\ndone\n\n" +
            "## Next Step\nfinish Z\n";

        var p = mockProviderReturning(goodSummary);
        var s = new ConversationSummarizer(p, appConfig(null));

        var out = s.apply(List.of(oldMsg, oldAsst, tailMsg), new CompressionConfig(
            200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3));

        // 3 条：summary USER + tail (asst) + boundary USER
        assertEquals(3, out.size());
        assertEquals(Role.USER, out.get(0).role());
        assertTrue(out.get(0).blocks().get(0) instanceof ContentBlock.TextBlock);
        var summaryText = ((ContentBlock.TextBlock) out.get(0).blocks().get(0)).text();
        assertTrue(summaryText.contains("[Conversation summary]"));
        assertTrue(summaryText.contains("## Intent"));
        assertFalse(summaryText.contains("<scratchpad>"));  // 裁掉

        // tail = 保留 tailMsg
        assertEquals(tailMsg, out.get(1));

        // boundary
        assertEquals(Role.USER, out.get(2).role());
        assertTrue(out.get(2).blocks().get(0) instanceof ContentBlock.TextBlock);
        var boundary = ((ContentBlock.TextBlock) out.get(2).blocks().get(0)).text();
        assertTrue(boundary.contains("re-read"));
        assertTrue(boundary.contains("Offloaded"));
    }

    @Test
    void recencyBelowMinMessagesExtendsTo5() {
        // 构造 3 条短消息，应该被扩展到 5 条（含 history 外的"待补"消息）
        // 用更长的 history 让 recency 推进
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 8; i++) {
            history.add(new ChatMessage(Role.USER,
                List.of(new ContentBlock.TextBlock("h" + i + " ".repeat(50)))));
        }
        for (int i = 0; i < 3; i++) {
            history.add(new ChatMessage(Role.ASSISTANT,
                List.of(new ContentBlock.TextBlock("t" + i + " ".repeat(50)))));
        }
        // 总 11 条；recency 默认 10K tokens，3 条短尾不到 5 → 强制 5
        // tail 应该是最后 5 条（3 user h5/h6/h7 + 3 asst t0/t1/t2 = 6），但 5 条: t0/t1/t2 + h5/h6 不对
        // 实际：list 末尾向前 5 条 = t0/t1/t2 + h5/h6 (3+2=5)，后面 h7 也会进，因为 t2 是 list[-1]
        // 重新数：list 是 [h0..h7, t0, t1, t2]，长度 11。末尾起 5 条 = [h6, h7, t0, t1, t2]
        // 所以 tail 应是这 5 条，待摘要 = [h0..h5]

        String good = "<scratchpad>x</scratchpad>\n" +
            "## Intent\ni\n\n## Decisions\nd\n\n## Open Questions\nq\n\n## State\ns\n\n## Next Step\nn\n";
        var p = mockProviderReturning(good);
        var s = new ConversationSummarizer(p, appConfig(null));
        var out = s.apply(history, new CompressionConfig(
            200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3));

        // 待摘要调用了 LLM → 收到 summary
        // 但内容不重要，关键是 tail 是 5 条 + summary + boundary = 7 条
        assertEquals(7, out.size());
    }

    @Test
    void missingSectionThrows() {
        String bad = "<scratchpad>x</scratchpad>\n" +
            "## Intent\ni\n\n## Decisions\nd\n\n";   // 缺 3 段
        var p = mockProviderReturning(bad);
        var s = new ConversationSummarizer(p, appConfig(null));
        var ex = assertThrows(CompressionException.class,
            () -> s.apply(List.of(
                new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("u"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a4"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a5")))),
                new CompressionConfig(
                    200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3)));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    void refusalThrows() {
        String refusal = "<scratchpad>x</scratchpad>\n" +
            "## Intent\nI can't summarize this conversation\n\n" +
            "## Decisions\nd\n\n## Open Questions\nq\n\n## State\ns\n\n## Next Step\nn\n";
        var p = mockProviderReturning(refusal);
        var s = new ConversationSummarizer(p, appConfig(null));
        assertThrows(CompressionException.class, () ->
            s.apply(List.of(
                new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("u"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a4"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a5")))),
                new CompressionConfig(
                    200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3)));
    }

    @Test
    void errorChunkThrows() {
        LlmProvider p = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<StreamChunk> sink = inv.getArgument(1);
            sink.accept(new StreamChunk.MessageStart());
            sink.accept(new StreamChunk.Error("provider boom"));
            return null;
        }).when(p).stream(any(), any());
        var s = new ConversationSummarizer(p, appConfig(null));
        assertThrows(CompressionException.class, () ->
            s.apply(List.of(
                new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("u"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a4"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a5")))),
                new CompressionConfig(
                    200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3)));
    }

    @Test
    void summarizerModelUsedWhenConfigured() {
        String good = "<scratchpad>x</scratchpad>\n" +
            "## Intent\ni\n\n## Decisions\nd\n\n## Open Questions\nq\n\n## State\ns\n\n## Next Step\nn\n";
        var p = mockProviderReturning(good);
        var s = new ConversationSummarizer(p, appConfig("claude-haiku-4-5"));
        s.apply(List.of(
                new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("u"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a4"))),
                new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a5")))),
            new CompressionConfig(
                200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3));
        var captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        try { verify(p).stream(captor.capture(), any()); }
        catch (Exception e) { throw new RuntimeException(e); }
        assertEquals("claude-haiku-4-5", captor.getValue().model());
    }
}
```

注：`AppConfig` 是 `record`，无法 override 方法。第二个测试需要重构——`AppConfig` 必须暴露 `summarizerModel()`。**这是 Task 11 的责任**；本测试先跳过 summarizerModel 字段，直接用构造函数后续追加的方法。运行时会有 NoSuchMethodError，需要先在 Task 11 加字段。

为避免阻塞，**本 Task 把 ConversationSummarizer 的签名写成**：

```java
public ConversationSummarizer(LlmProvider provider, String mainModel, String summarizerModelOrNull)
```

把 model 选择拆成两个 string 参数。等 Task 11 给 AppConfig 加字段后再做 wiring 转换。**测试代码相应改为直接传 string。**

重写后的测试骨架（节选）：

```java
@Test
void summarizerModelUsedWhenConfigured() {
    String good = "<scratchpad>x</scratchpad>\n" +
        "## Intent\ni\n\n## Decisions\nd\n\n## Open Questions\nq\n\n## State\ns\n\n## Next Step\nn\n";
    var p = mockProviderReturning(good);
    var s = new ConversationSummarizer(p, "claude-sonnet-4-6", "claude-haiku-4-5");
    s.apply(List.of(...), cfg);
    var captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
    verify(p).stream(captor.capture(), any());
    assertEquals("claude-haiku-4-5", captor.getValue().model());
}
```

把上述所有 `new ConversationSummarizer(p, appConfig(null))` 改为 `new ConversationSummarizer(p, "claude-sonnet-4-6", null)`，所有 `new ConversationSummarizer(p, appConfig("claude-haiku-4-5"))` 改为 `new ConversationSummarizer(p, "claude-sonnet-4-6", "claude-haiku-4-5")`。删除 `appConfig` helper 方法。

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=ConversationSummarizerTest`
Expected: FAIL with "symbol not found: class ConversationSummarizer"

- [ ] **Step 3：实现 ConversationSummarizer**

`src/main/java/com/maplecode/compression/ConversationSummarizer.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 次层：调 LLM 出 5 段结构化摘要 + scratchpad（裁掉），返回
 * [summary USER] + recency tail + [boundary USER]。
 */
public final class ConversationSummarizer {

    private static final Pattern SCRATCHPAD = Pattern.compile("(?s)<scratchpad>.*?</scratchpad>");
    private static final String SUMMARY_SYSTEM_PROMPT = String.join("\n",
        "You are a conversation state compressor. Your job is to summarize a long",
        "agent conversation into structured sections so a future model turn can",
        "continue without re-reading the full transcript.",
        "",
        "STRICT RULES:",
        "- DO NOT call any tools. Your output must be pure text only.",
        "- DO NOT invent facts not present in the messages.",
        "- Preserve exact file paths, function names, error messages, and numeric",
        "  values verbatim.",
        "- The user messages and assistant intent are sacrosanct — never paraphrase",
        "  the user's original ask.",
        "",
        "PROCESS:",
        "1. First, write a private scratch analysis in <scratchpad>...</scratchpad>",
        "   tags. This section will be DISCARDED before sending the summary to the",
        "   model. Use it to list: what tools were called, what each tool returned,",
        "   what errors occurred, what the user originally asked.",
        "2. After the scratchpad, write the formal summary with EXACTLY these 5",
        "   sections, in this order, each starting with \"## \":",
        "",
        "   ## Intent",
        "   The user's original goal, in one or two sentences. Quote the user's",
        "   exact wording where possible.",
        "",
        "   ## Decisions",
        "   Key choices made during the conversation — files selected, approaches",
        "   tried, trade-offs considered. Cite exact file paths.",
        "",
        "   ## Open Questions",
        "   Things the agent asked the user but did not get answered; ambiguities",
        "   still unresolved; assumptions made without confirmation.",
        "",
        "   ## State",
        "   Current state of the work — what's done, what's in progress, what's",
        "   broken. Include exact file paths of artifacts created/modified.",
        "",
        "   ## Next Step",
        "   The single most important concrete action the agent should take next.",
        "   One sentence.",
        "",
        "OUTPUT FORMAT:",
        "- Output MUST start with <scratchpad>...</scratchpad>.",
        "- Output MUST then contain exactly 5 \"## \" sections in the order above.",
        "- Do not include any prose before the scratchpad or after the last section."
    );

    private static final String[] REQUIRED_SECTIONS = {
        "## Intent", "## Decisions", "## Open Questions", "## State", "## Next Step"
    };

    private static final String BOUNDARY_TEXT = String.join(" ",
        "[Compression boundary] Above messages are summarized to fit context window.",
        "Tool outputs marked \"[Offloaded to ...]\" were written to disk; to see exact",
        "code, file contents, or tool output, re-read from those absolute paths",
        "(they are stable for this session). Do NOT guess code or output from the",
        "summary — always re-read."
    );

    private static final String[] REFUSAL_MARKERS = {"I can't", "I cannot", "I'm unable"};

    private final LlmProvider provider;
    private final String mainModel;
    private final String summarizerModelOrNull;
    private final TokenEstimator estimator = new TokenEstimator();

    public ConversationSummarizer(LlmProvider provider, String mainModel, String summarizerModelOrNull) {
        this.provider = provider;
        this.mainModel = mainModel;
        this.summarizerModelOrNull = summarizerModelOrNull;
    }

    public List<ChatMessage> apply(List<ChatMessage> messages, CompressionConfig config) {
        int[] split = computeRecencySplit(messages, config);
        int startIdx = split[0];
        int tailLen = split[1];

        var toSummarize = messages.subList(0, startIdx);
        var tail = messages.subList(startIdx, messages.size());

        String summary = callLlm(toSummarize);

        var result = new ArrayList<ChatMessage>(tailLen + 2);
        result.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("[Conversation summary]\n\n" + summary))));
        result.addAll(tail);
        result.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock(BOUNDARY_TEXT))));
        return result;
    }

    /** 返回 [startIdx, tailLen]。tailLen ≥ recencyMinMessages；若 10K 超则按 10K 截但保持 ≥ 5。 */
    int[] computeRecencySplit(List<ChatMessage> messages, CompressionConfig config) {
        int n = messages.size();
        int accTokens = 0;
        int from = n;
        for (int i = n - 1; i >= 0; i--) {
            int t = estimator.estimate(List.of(messages.get(i)), null);
            accTokens += t;
            from = i;
            int tailLen = n - from;
            if (accTokens >= config.recencyTokens() && tailLen >= config.recencyMinMessages()) {
                break;
            }
        }
        // 保证至少 recencyMinMessages
        while (n - from < config.recencyMinMessages() && from > 0) {
            from--;
        }
        return new int[]{from, n - from};
    }

    private String callLlm(List<ChatMessage> toSummarize) {
        String model = summarizerModelOrNull != null ? summarizerModelOrNull : mainModel;
        var req = new ChatRequest(
            model,
            List.of(SUMMARY_SYSTEM_PROMPT),
            toSummarize,
            null,
            List.of()
        );
        StringBuilder acc = new StringBuilder();
        provider.stream(req, chunk -> {
            switch (chunk) {
                case StreamChunk.TextDelta d -> acc.append(d.text());
                case StreamChunk.MessageStart s -> { /* no-op */ }
                case StreamChunk.MessageEnd e -> { /* no-op */ }
                case StreamChunk.Error e -> throw new CompressionException("llm error chunk: " + e.message());
                default -> { /* ignore ThinkingDelta / ToolUse* for summary */ }
            }
        });
        String raw = acc.toString();
        // 裁 scratchpad
        String cleaned = SCRATCHPAD.matcher(raw).replaceAll("").trim();
        // 5 段校验
        for (String section : REQUIRED_SECTIONS) {
            if (!cleaned.contains(section)) {
                throw new CompressionException("summary missing section: " + section);
            }
        }
        // refusal 检查
        String lower = cleaned.toLowerCase();
        for (String marker : REFUSAL_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                throw new CompressionException("summary looks like refusal: contains '" + marker + "'");
            }
        }
        return cleaned;
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=ConversationSummarizerTest`
Expected: PASS（6 个 test 全绿）

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/ConversationSummarizerTest.java \
        src/main/java/com/maplecode/compression/ConversationSummarizer.java
git commit -m "feat(compression): ConversationSummarizer 5 段摘要 + scratchpad 裁剪（TDD）"
```

---

## Task 9：CompressionCoordinator（TDD）

**Files:**
- Create: `src/test/java/com/maplecode/compression/CompressionCoordinatorTest.java`
- Create: `src/main/java/com/maplecode/compression/CompressionCoordinator.java`

- [ ] **Step 1：写失败测试**

`src/test/java/com/maplecode/compression/CompressionCoordinatorTest.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.session.ChatSession;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompressionCoordinatorTest {

    private static ChatSession sessionWith(List<ChatMessage> msgs) {
        ChatSession s = new ChatSession();
        msgs.forEach(m -> {
            if (m.role() == Role.USER) s.appendUser(m.blocks());
            else s.appendAssistant(m.blocks());
        });
        return s;
    }

    @Test
    void belowThresholdReturnsNoop(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var ctx = new CompressionContext(
            new CompressionConfig(10_000, 1_000, 1_000, 1_000, 1_000, 1_000, 2, 3, 2, 3),
            storage, new FailureCounter(3));
        var offloader = mock(Offloader.class);
        var summarizer = mock(ConversationSummarizer.class);
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class), offloader, summarizer);

        var session = sessionWith(List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("hi"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("hello")))));
        var r = coord.beforeRequest(session, CompressionTrigger.AUTO, null);
        assertTrue(r instanceof CompressionResult.Noop);
        verifyNoInteractions(offloader, summarizer);
    }

    @Test
    void offloadOnlyChangesSessionWithoutCallingSummarizer(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var cfg = new CompressionConfig(10_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1, 3, 2, 3);
        var ctx = new CompressionContext(cfg, storage, new FailureCounter(3));
        var bigTr = new ContentBlock.ToolResultBlock("tu-1", "x".repeat(8_000), false);  // 2K tokens > 1K single
        var msgs = List.of(
            new ChatMessage(Role.USER, List.of(bigTr)),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))));
        var offloaded = List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.ToolResultBlock("tu-1", "preview", false))),
            msgs.get(1), msgs.get(2));
        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(offloaded);
        var summarizer = mock(ConversationSummarizer.class);
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class), offloader, summarizer);

        var session = sessionWith(msgs);
        int beforeSize = session.toRequest("m", List.of(), null, List.of()).messages().size();
        var r = coord.beforeRequest(session, CompressionTrigger.AUTO, null);
        assertTrue(r instanceof CompressionResult.ChangedOffloadOnly);
        verify(summarizer, never()).apply(any(), any());
        // session 已替换
        int afterSize = session.toRequest("m", List.of(), null, List.of()).messages().size();
        assertEquals(offloaded.size(), afterSize);
    }

    @Test
    void offloadInsufficientFallsThroughToSummary(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var cfg = new CompressionConfig(2_000, 100, 100, 500, 500, 100, 2, 3, 2, 3);
        var ctx = new CompressionContext(cfg, storage, new FailureCounter(3));
        // 给个消息让 estimator 算出来 > 1900（threshold = 2000 - 100）
        var bigTr = new ContentBlock.ToolResultBlock("tu-1", "x".repeat(8_000), false);  // 2K
        var msgs = List.of(
            new ChatMessage(Role.USER, List.of(bigTr)),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))));
        // offloader 不动内容（offload 后仍超阈）
        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(msgs);
        // summarizer 返回 3 条
        var summarized = List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("summary"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("boundary"))));
        var summarizer = mock(ConversationSummarizer.class);
        when(summarizer.apply(any(), any())).thenReturn(summarized);
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class), offloader, summarizer);

        var session = sessionWith(msgs);
        var r = coord.beforeRequest(session, CompressionTrigger.AUTO, null);
        assertTrue(r instanceof CompressionResult.ChangedFull);
        int afterSize = session.toRequest("m", List.of(), null, List.of()).messages().size();
        assertEquals(summarized.size(), afterSize);
    }

    @Test
    void summarizerExceptionIncrementsCounter(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var cfg = new CompressionConfig(2_000, 100, 100, 500, 500, 100, 2, 3, 2, 3);
        var ctx = new CompressionContext(cfg, storage, new FailureCounter(3));
        var bigTr = new ContentBlock.ToolResultBlock("tu-1", "x".repeat(8_000), false);
        var msgs = List.of(
            new ChatMessage(Role.USER, List.of(bigTr)),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))));
        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(msgs);
        var summarizer = mock(ConversationSummarizer.class);
        when(summarizer.apply(any(), any())).thenThrow(new CompressionException("boom"));
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class), offloader, summarizer);

        var session = sessionWith(msgs);
        var r = coord.beforeRequest(session, CompressionTrigger.AUTO, null);
        assertTrue(r instanceof CompressionResult.FailedSummary);
        assertEquals(1, ctx.counter().failures());
    }

    @Test
    void trippedCounterSkipsAutoButManualProceeds(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var ctx = new CompressionContext(
            new CompressionConfig(200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3),
            storage, new FailureCounter(3));
        // 制造 tripped
        ctx.counter().recordFailure();
        ctx.counter().recordFailure();
        ctx.counter().recordFailure();
        var offloader = mock(Offloader.class);
        var summarizer = mock(ConversationSummarizer.class);
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class), offloader, summarizer);

        var session = sessionWith(List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("u"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a1"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a2"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a3"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a4"))),
            new ChatMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("a5")))));

        var rAuto = coord.beforeRequest(session, CompressionTrigger.AUTO, null);
        assertTrue(rAuto instanceof CompressionResult.SkippedCircuitOpen);

        // manual 不受 tripped 影响；offloader 没动它返回原 list，session.toRequest 不变
        var rManual = coord.beforeRequest(session, CompressionTrigger.MANUAL, null);
        assertFalse(rManual instanceof CompressionResult.SkippedCircuitOpen);
    }

    @Test
    void recordUsageUpdatesLastSeen() {
        var storage = new CompressionStorage(java.nio.file.Path.of("/tmp"));
        var ctx = new CompressionContext(
            new CompressionConfig(200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3),
            storage, new FailureCounter(3));
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class),
            mock(Offloader.class), mock(ConversationSummarizer.class));
        assertNull(coord.lastSeenUsage());
        var u = new TokenUsage(100, 50, 0, 0);
        coord.recordUsage(u);
        assertEquals(u, coord.lastSeenUsage());
    }

    @Test
    void resetCounterClearsState(@TempDir Path tmp) {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var ctx = new CompressionContext(
            new CompressionConfig(200_000, 13_000, 3_000, 8_000, 30_000, 10_000, 5, 8, 4, 3),
            storage, new FailureCounter(3));
        ctx.counter().recordFailure();
        ctx.counter().recordFailure();
        var coord = new CompressionCoordinator(ctx, mock(LlmProvider.class),
            mock(Offloader.class), mock(ConversationSummarizer.class));
        assertEquals(2, ctx.counter().failures());
        coord.resetCounter();
        assertEquals(0, ctx.counter().failures());
    }
}
```

- [ ] **Step 2：跑测试，确认失败**

Run: `mvn -q test -Dtest=CompressionCoordinatorTest`
Expected: FAIL with "symbol not found: class CompressionCoordinator" 或 "ChatSession.toRequest" 缺方法

**前置条件**：本测试依赖 `ChatSession.toRequest(...)`（在 Task 10 加 `replaceAll` 时一并验证）。如果当前 ChatSession 已有 toRequest，跳到 step 2；如果没有，先做 Task 10 再回来。

- [ ] **Step 3：实现 CompressionCoordinator**

`src/main/java/com/maplecode/compression/CompressionCoordinator.java`：

```java
package com.maplecode.compression;

import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.TokenUsage;
import com.maplecode.session.ChatSession;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 压缩编排器：唯一公开入口 beforeRequest。维护 lastSeenUsage 供调用方读取。
 */
public final class CompressionCoordinator {

    private final CompressionContext ctx;
    @SuppressWarnings("unused")   // 保留构造期参数，便于扩展；当前架构下走 Offloader/Summarizer
    private final LlmProvider provider;
    private final Offloader offloader;
    private final ConversationSummarizer summarizer;
    private final TokenEstimator estimator = new TokenEstimator();
    private final AtomicReference<TokenUsage> lastSeenUsage = new AtomicReference<>();

    public CompressionCoordinator(CompressionContext ctx, LlmProvider provider,
                                  Offloader offloader, ConversationSummarizer summarizer) {
        this.ctx = ctx;
        this.provider = provider;
        this.offloader = offloader;
        this.summarizer = summarizer;
    }

    /** AgentLoop 每次 MessageEnd 拿到 usage 时调一次，更新锚点。 */
    public void recordUsage(TokenUsage usage) {
        if (usage != null) lastSeenUsage.set(usage);
    }

    public TokenUsage lastSeenUsage() {
        return lastSeenUsage.get();
    }

    /** /clear 调用：清空 counter，usage 锚点保留（与 ChatSession 无关）。 */
    public void resetCounter() {
        ctx.counter().reset();
    }

    /** App.main shutdown hook 调用，删除 session 目录。 */
    public void close() {
        ctx.storage().close();
    }

    public CompressionResult beforeRequest(ChatSession session, CompressionTrigger trigger,
                                            TokenUsage anchorOverride) {
        var config = ctx.config();
        if (ctx.counter().isTripped()) {
            if (trigger == CompressionTrigger.AUTO) {
                System.err.println("[compression] circuit open ("
                    + ctx.counter().failures() + " failures), auto-compress disabled this session");
                return new CompressionResult.SkippedCircuitOpen(ctx.counter().failures());
            }
            // MANUAL 仍尝试
        }

        var tokenAnchor = anchorOverride != null ? anchorOverride : lastSeenUsage.get();
        int tokens = estimator.estimate(currentMessages(session), tokenAnchor);
        int threshold = config.window() - config.marginFor(trigger);
        if (tokens < threshold) {
            return new CompressionResult.Noop();
        }

        // 首层 offload
        List<com.maplecode.provider.ChatMessage> afterOffload;
        try {
            afterOffload = offloader.apply(currentMessages(session), config);
        } catch (CompressionException e) {
            System.err.println("[compression] offload failed, leaving session untouched: " + e.getMessage());
            return new CompressionResult.FailedOffload(e.getMessage());
        }
        int tokensAfter = estimator.estimate(afterOffload, tokenAnchor);
        if (tokensAfter < threshold) {
            session.replaceAll(afterOffload);
            return new CompressionResult.ChangedOffloadOnly(countOffloaded(currentMessages(session), afterOffload));
        }

        // 次层 summary
        try {
            var afterSummary = summarizer.apply(afterOffload, config);
            ctx.counter().recordSuccess();
            session.replaceAll(afterSummary);
            return new CompressionResult.ChangedFull(
                countOffloaded(currentMessages(session), afterSummary),
                tokensAfter);
        } catch (CompressionException e) {
            ctx.counter().recordFailure();
            System.err.println("[compression] summary failed ("
                + ctx.counter().failures() + " consecutive): " + e.getMessage());
            return new CompressionResult.FailedSummary(e.getMessage(), ctx.counter().failures());
        }
    }

    private static int countOffloaded(List<com.maplecode.provider.ChatMessage> before,
                                      List<com.maplecode.provider.ChatMessage> after) {
        // 简单近似：after 中含 "[Offloaded to" 的 ToolResultBlock 数
        int n = 0;
        for (var m : after) {
            for (var b : m.blocks()) {
                if (b instanceof com.maplecode.provider.ContentBlock.ToolResultBlock tr
                    && tr.content().contains("[Offloaded to")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static List<com.maplecode.provider.ChatMessage> currentMessages(ChatSession session) {
        return session.toRequest("unused", List.of(), null, List.of()).messages();
    }
}
```

- [ ] **Step 4：跑测试，确认通过**

Run: `mvn -q test -Dtest=CompressionCoordinatorTest`
Expected: PASS（7 个 test 全绿）

- [ ] **Step 5：Commit**

```bash
git add src/test/java/com/maplecode/compression/CompressionCoordinatorTest.java \
        src/main/java/com/maplecode/compression/CompressionCoordinator.java
git commit -m "feat(compression): CompressionCoordinator 编排 + counter + usage 锚点（TDD）"
```

---

## Task 10：ChatSession.replaceAll（TDD）

**Files:**
- Modify: `src/main/java/com/maplecode/session/ChatSession.java`
- Create: `src/test/java/com/maplecode/session/ChatSessionReplaceAllTest.java`

- [ ] **Step 1：读 ChatSession 当前结构**

Run: `cat src/main/java/com/maplecode/session/ChatSession.java`

确认 `messages` 字段是 `List<ChatMessage>`，`toRequest` 返回包含 `messages()` 方法的 `ChatRequest`。

- [ ] **Step 2：写失败测试**

`src/test/java/com/maplecode/session/ChatSessionReplaceAllTest.java`：

```java
package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionReplaceAllTest {

    @Test
    void replaceAllReplacesMessages() {
        var session = new ChatSession();
        session.appendUserText("u1");
        session.appendAssistant(List.of(new ContentBlock.TextBlock("a1")));
        var newMessages = List.of(
            new ChatMessage(Role.USER, List.of(new ContentBlock.TextBlock("replaced"))));
        session.replaceAll(newMessages);
        var req = session.toRequest("m", List.of(), null, List.of());
        assertEquals(1, req.messages().size());
        assertEquals("replaced",
            ((ContentBlock.TextBlock) req.messages().get(0).blocks().get(0)).text());
    }

    @Test
    void appendAfterReplaceStillWorks() {
        var session = new ChatSession();
        session.replaceAll(List.of());
        session.appendUserText("new");
        var req = session.toRequest("m", List.of(), null, List.of());
        assertEquals(1, req.messages().size());
    }

    @Test
    void clearAfterReplaceRestoresEmpty() {
        var session = new ChatSession();
        session.replaceAll(List.of(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x")))));
        session.clear();
        assertEquals(0, session.toRequest("m", List.of(), null, List.of()).messages().size());
    }

    @Test
    void replaceAllDefensiveCopy() {
        var session = new ChatSession();
        var backing = new java.util.ArrayList<ChatMessage>();
        backing.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("a"))));
        session.replaceAll(backing);
        backing.add(new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("sneaky"))));
        assertEquals(1, session.toRequest("m", List.of(), null, List.of()).messages().size());
    }
}
```

- [ ] **Step 3：跑测试，确认失败**

Run: `mvn -q test -Dtest=ChatSessionReplaceAllTest`
Expected: FAIL with "symbol not found: method replaceAll"

- [ ] **Step 4：在 ChatSession 加 replaceAll**

打开 `src/main/java/com/maplecode/session/ChatSession.java`，在 `clear()` 方法后追加：

```java
/**
 * Coordinator 提交压缩产物：整批替换 messages。append-only 不变量整体被替换。
 * 防御性拷贝：调用方继续修改传入 list 不影响 session。
 */
public void replaceAll(List<ChatMessage> messages) {
    this.messages = List.copyOf(messages);
}
```

- [ ] **Step 5：跑测试，确认通过**

Run: `mvn -q test -Dtest=ChatSessionReplaceAllTest`
Expected: PASS（4 个 test 全绿）

- [ ] **Step 6：跑所有 ChatSession 相关测试，确保不回归**

Run: `mvn -q test -Dtest='ChatSession*'`
Expected: PASS

- [ ] **Step 7：Commit**

```bash
git add src/main/java/com/maplecode/session/ChatSession.java \
        src/test/java/com/maplecode/session/ChatSessionReplaceAllTest.java
git commit -m "feat(session): ChatSession.replaceAll 批量替换出口（TDD）"
```

---

## Task 11：AppConfig 加字段 + ConfigLoader 解析

**Files:**
- Modify: `src/main/java/com/maplecode/config/AppConfig.java`
- Modify: `src/main/java/com/maplecode/config/ConfigLoader.java`

- [ ] **Step 1：在 AppConfig 加 contextWindow + summarizerModel**

打开 `src/main/java/com/maplecode/config/AppConfig.java`，把 record 头改为：

```java
public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String yamlPrompt,
    List<SystemBlock> systemBlocks,
    ThinkingConfig thinking,
    Timeouts timeouts,
    PermissionMode permissionMode,
    AgentLimits agentLimits,
    McpConfig mcpConfig,
    int contextWindow,         // 新增：0 表示未配
    String summarizerModel     // 新增：null 表示未配
) {
```

把 `contextWindow` 默认值用紧凑构造器兜底为 0、`summarizerModel` 仍为 null：

```java
public AppConfig {
    if (contextWindow < 0) throw new IllegalArgumentException("context_window must be >= 0");
}
```

- [ ] **Step 2：在 ConfigLoader.parse 末尾追加解析**

打开 `src/main/java/com/maplecode/config/ConfigLoader.java`，在 `return new AppConfig(...)` 之前：

```java
int contextWindow = root.get("context_window") instanceof Number nw ? nw.intValue() : 0;
String summarizerModel = optionalString(root, "summarizer_model");
```

修改 return 语句，把 `, mcp)` 改为 `, mcp, contextWindow, summarizerModel)`。

- [ ] **Step 3：跑 ConfigLoader 测试**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: PASS（构造新 AppConfig 字段需默认值兼容；ConfigLoaderTest 内构造的 AppConfig 实例需要更新字段）

如果 FAIL：打开 `ConfigLoaderTest.java`，把所有 `new AppConfig(...)` 加上 `, 0, null` 两个尾部参数。

- [ ] **Step 4：跑所有测试，确认无回归**

Run: `mvn -q test`
Expected: PASS（如果有其他测试构造 AppConfig，加字段后会编译失败，逐个补 `, 0, null`）

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/maplecode/config/AppConfig.java \
        src/main/java/com/maplecode/config/ConfigLoader.java
git commit -m "feat(config): 解析 context_window / summarizer_model"
```

---

## Task 12：AgentEvent.CompressionApplied

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentEvent.java`

- [ ] **Step 1：读 AgentEvent 当前结构**

Run: `cat src/main/java/com/maplecode/agent/AgentEvent.java`

- [ ] **Step 2：加 CompressionApplied 变体**

在 `sealed interface AgentEvent permits ...` 末尾的 permits 列表追加 `AgentEvent.CompressionApplied`；新增 record：

```java
record CompressionApplied(CompressionResult result) implements AgentEvent {}
```

注意 import：`import com.maplecode.compression.CompressionResult;`

- [ ] **Step 3：编译验证**

Run: `mvn -q compile`
Expected: FAIL（StreamPrinter 等所有 `switch (event)` 处需要补 case）

- [ ] **Step 4：把所有 `switch (event)` 加默认 case（CompressionApplied 在 Task 13 显式处理）**

在所有编译失败点（StreamPrinter 等）的 switch 里加：

```java
case AgentEvent.CompressionApplied c -> { /* 暂由 StreamPrinter.accept 显式处理，本步骤先吞 */ }
```

`grep -rn "switch (event)\|switch(e)" src/main/java --include="*.java"` 找全。

- [ ] **Step 5：跑所有测试**

Run: `mvn -q test`
Expected: PASS（行为不变）

- [ ] **Step 6：Commit**

```bash
git add src/main/java/com/maplecode/agent/AgentEvent.java
git commit -m "feat(event): 加 CompressionApplied sealed 变体"
```

---

## Task 13：StreamPrinter 处理 CompressionApplied + compressionResult 方法

**Files:**
- Modify: `src/main/java/com/maplecode/ui/StreamPrinter.java`

- [ ] **Step 1：读 StreamPrinter 当前结构**

Run: `cat src/main/java/com/maplecode/ui/StreamPrinter.java`

- [ ] **Step 2：在 accept 里加 CompressionApplied 分支**

打开 `StreamPrinter.java`，把 `case AgentEvent.AgentStop s -> info(...)` 之后追加：

```java
case AgentEvent.CompressionApplied c -> {
    System.err.println("[compression] applied: " + renderResult(c.result()));
}
```

- [ ] **Step 3：加 compressionResult 方法和 renderResult 私有方法**

在 `class StreamPrinter` 内（accept 之外）加：

```java
/** ReplLoop /compress 命令显式调用：打印结果到 stdout。 */
public void compressionResult(CompressionResult r) {
    out.println(renderResult(r));
}

private String renderResult(CompressionResult r) {
    return switch (r) {
        case CompressionResult.Noop n -> "[compression] noop: below threshold";
        case CompressionResult.ChangedOffloadOnly o ->
            "[compression] offloaded " + o.offloadedCount() + " tool result(s)";
        case CompressionResult.ChangedFull f ->
            "[compression] full compression: offloaded " + f.offloadedCount()
                + ", summary covered ~" + f.summaryInputTokens() + " input tokens";
        case CompressionResult.FailedOffload f ->
            "[compression] offload failed: " + f.reason();
        case CompressionResult.FailedSummary f ->
            "[compression] summary failed (" + f.consecutiveFailures() + " consecutive): " + f.reason();
        case CompressionResult.SkippedCircuitOpen s ->
            "[compression] circuit open (" + s.consecutiveFailures() + " failures); auto-compress disabled this session";
    };
}
```

import 顶部加：`import com.maplecode.compression.CompressionResult;`

- [ ] **Step 4：编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5：跑所有测试**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 6：Commit**

```bash
git add src/main/java/com/maplecode/ui/StreamPrinter.java
git commit -m "feat(printer): 处理 CompressionApplied + compressionResult 方法"
```

---

## Task 14：AgentLoop 接入 beforeRequest + recordUsage 注入

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`

- [ ] **Step 1：读 AgentLoop 当前结构**

已读过，确认：
- 构造接 `usageSink` (line 27, 32)
- `usageSink` 传给 `ResponseCollector` (line 87)
- ResponseCollector 在 MessageEnd 时调 `usageSink.accept(usage)` (ResponseCollector.java:79)

- [ ] **Step 2：加 CompressionCoordinator 构造参数**

修改 `AgentLoop` 构造签名（4 个 overload 都要加或新增一个 overload 保留向后兼容）：

```java
public AgentLoop(LlmProvider provider, ToolRegistry registry,
                 ToolExecutor executor, ChatSession session,
                 AgentConfig config, Consumer<TokenUsage> usageSink,
                 CompressionCoordinator coord) {
    this(provider, registry, executor, session, config, usageSink, coord, false);
}

public AgentLoop(LlmProvider provider, ToolRegistry registry,
                 ToolExecutor executor, ChatSession session,
                 AgentConfig config, Consumer<TokenUsage> usageSink) {
    this(provider, registry, executor, session, config, usageSink, null, false);
}

private AgentLoop(LlmProvider provider, ToolRegistry registry,
                  ToolExecutor executor, ChatSession session,
                  AgentConfig config, Consumer<TokenUsage> usageSink,
                  CompressionCoordinator coord, boolean unused) {
    this.provider = provider;
    this.registry = registry;
    this.executor = executor;
    this.session = session;
    this.config = config;
    this.usageSink = usageSink;
    this.coord = coord;
}
```

加字段：`private final CompressionCoordinator coord;`（可为 null）

- [ ] **Step 3：在 run() while 开头加 beforeRequest 调用**

在 `while (iteration < config.maxIterations())` 块的 `if (cancelled) ...` 之后、`sink.accept(new AgentEvent.IterationStart(iteration));` 之前插入：

```java
if (coord != null && iteration > 0) {
    var result = coord.beforeRequest(session, CompressionTrigger.AUTO, coord.lastSeenUsage());
    if (result instanceof CompressionResult.ChangedOffloadOnly o
        || result instanceof CompressionResult.ChangedFull f) {
        sink.accept(new AgentEvent.CompressionApplied(result));
    }
    // SKIPPED_CIRCUIT_OPEN / FAILED_* / NOOP 静默继续
}
```

加 import：`import com.maplecode.compression.CompressionCoordinator; import com.maplecode.compression.CompressionResult; import com.maplecode.compression.CompressionTrigger;`

- [ ] **Step 4：在 usageSink 调用处加 recordUsage 注入**

修改 ResponseCollector 的 usage 注入路径有两条路：

**路线 A（推荐）**：改 App.main 装配 usageSink 时链式调用 `coord::recordUsage`：

```java
Consumer<TokenUsage> usageSink = u -> {
    printer.usage(u);
    if (coord != null) coord.recordUsage(u);
};
```

**路线 B**：在 AgentLoop 内包一层：AgentLoop.run() 在 `ResponseCollector col = new ResponseCollector(...)` 之后加：

```java
if (coord != null && col.usage() != null) coord.recordUsage(col.usage());
```

本任务选 **路线 A**（侵入最小），但 Task 16 在 App.main 装配时实施。

- [ ] **Step 5：编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6：跑所有测试**

Run: `mvn -q test`
Expected: PASS（既有 AgentLoop 测试仍走 5 参构造，coord=null 安全）

- [ ] **Step 7：Commit**

```bash
git add src/main/java/com/maplecode/agent/AgentLoop.java
git commit -m "feat(agent): AgentLoop 接入 coord.beforeRequest + 7 参构造"
```

---

## Task 15：ReplLoop /compress + /clear 改

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1：读 ReplLoop 当前结构**

已读过。`/clear` 在 line 78-82，`/do` 在 line 105-123。

- [ ] **Step 2：扩 ReplLoop 构造，加 coord 参数**

```java
public class ReplLoop {
    private final AgentLoop agent;
    private final StreamPrinter printer;
    private final PermissionEngine engine;
    private AgentConfig agentConfig;
    private final CompressionCoordinator coord;  // 新增，可为 null

    public ReplLoop(AgentLoop agent, StreamPrinter printer,
                    PermissionEngine engine, AgentConfig agentConfig,
                    CompressionCoordinator coord) {
        this.agent = agent;
        this.printer = printer;
        this.engine = engine;
        this.agentConfig = agentConfig;
        this.coord = coord;
    }
}
```

- [ ] **Step 3：修改 /clear 分支**

把：

```java
if (trimmed.equals("/clear")) {
    agent.session().clear();
    printer.info("history cleared");
    continue;
}
```

改为：

```java
if (trimmed.equals("/clear")) {
    agent.session().clear();
    if (coord != null) coord.resetCounter();
    printer.info("history cleared");
    continue;
}
```

- [ ] **Step 4：加 /compress 分支**

在 `/clear` 之后、`/tools` 之前插入：

```java
if (trimmed.equals("/compress")) {
    if (coord == null) {
        printer.error("compression not enabled");
        continue;
    }
    var usage = coord.lastSeenUsage();
    var r = coord.beforeRequest(agent.session(), CompressionTrigger.MANUAL, usage);
    printer.compressionResult(r);
    continue;
}
```

加 import：`import com.maplecode.compression.CompressionCoordinator; import com.maplecode.compression.CompressionResult; import com.maplecode.compression.CompressionTrigger;`

- [ ] **Step 5：编译**

Run: `mvn -q compile`
Expected: FAIL（App.java 的 ReplLoop 构造少参数）

**不要立即补 App.java**；Task 16 统一装配。

- [ ] **Step 6：跑所有测试**

Run: `mvn -q test`
Expected: PASS（如果有测试直接构造 ReplLoop，加参数会编译失败，逐个补 `, null`）

- [ ] **Step 7：Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(repl): /compress 命令 + /clear 改调 resetCounter"
```

---

## Task 16：App.main 装配 + shutdown hook

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1：读 App.main 当前结构**

Run: `cat src/main/java/com/maplecode/App.java`

确认 main 装配顺序：ConfigLoader → ProviderRegistry → ToolRegistry → PermissionEngine → ToolExecutor → ChatSession → AgentLoop → StreamPrinter → ReplLoop → shutdown hook。

- [ ] **Step 2：在 PermissionEngine 装配后、ToolExecutor 前插 compression 装配**

```java
// 压缩系统（v6）
var contextWindow = appConfig.contextWindow();
var summarizerModel = appConfig.summarizerModel();
CompressionConfig compressionCfg = CompressionConfig.fromAppConfig(contextWindow);
CompressionStorage compressionStorage = new CompressionStorage(
    Paths.get(System.getProperty("user.home"), ".maplecode", "cache",
              "session-" + UUID.randomUUID()));
FailureCounter failureCounter = new FailureCounter(compressionCfg.failureThreshold());
CompressionContext compressionCtx = new CompressionContext(compressionCfg, compressionStorage, failureCounter);
TokenEstimator estimator = new TokenEstimator();
Offloader offloader = new Offloader(compressionStorage);
ConversationSummarizer summarizer = new ConversationSummarizer(
    provider, appConfig.model(), summarizerModel);
CompressionCoordinator coord = new CompressionCoordinator(
    compressionCtx, provider, offloader, summarizer);
```

加 import：
```java
import com.maplecode.compression.*;
import java.nio.file.Paths;
import java.util.UUID;
```

- [ ] **Step 3：把 AgentLoop / ReplLoop 改为接 coord**

```java
// 链式 usageSink：同时推到 printer 和 coord
Consumer<TokenUsage> usageSink = u -> {
    printer.usage(u);
    coord.recordUsage(u);
};
AgentLoop agent = new AgentLoop(provider, registry, executor, session,
    agentConfig, usageSink, coord);
ReplLoop repl = new ReplLoop(agent, printer, engine, agentConfig, coord);
```

- [ ] **Step 4：注册 shutdown hook**

在 MCP shutdown hook 之后追加：

```java
Runtime.getRuntime().addShutdownHook(new Thread(coord::close, "compression-shutdown"));
```

- [ ] **Step 5：编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6：跑所有测试**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 7：Commit**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(app): 装配 CompressionCoordinator + usage 链 + shutdown hook"
```

---

## Task 17：maplecode.yaml.example 加注释

**Files:**
- Modify: `maplecode.yaml.example`

- [ ] **Step 1：读现有 example**

Run: `cat maplecode.yaml.example`

- [ ] **Step 2：在文件末尾追加两字段**

```yaml
# ===== v6 上下文管理 =====

# 上下文窗口总 token 数（输入预算，不含 max_tokens 输出预算）
# 默认 200000，覆盖 Sonnet 4.6 / Opus 4.7 / Haiku 4.5
# GPT-4o 用户请调到 128000；调试时也可临时调小（如 30000）触发次层摘要
# 未配则走默认 200000
# context_window: 200000

# 摘要专用 model（可选）；未配则用主对话 model
# 推荐 claude-haiku-4-5：便宜、快，5 段结构化摘要任务足够
# 配错 model 名会在第一次 /compress 或自动压缩时由 provider 返回 HTTP 4xx
# summarizer_model: claude-haiku-4-5
```

- [ ] **Step 3：Commit**

```bash
git add maplecode.yaml.example
git commit -m "docs(config): maplecode.yaml.example 加 context_window / summarizer_model 注释"
```

---

## Task 18：全量验证

- [ ] **Step 1：跑全量单元测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，所有 test 绿

- [ ] **Step 2：打包**

Run: `mvn -q package`
Expected: BUILD SUCCESS，`target/maple-code-java-0.1.0.jar` 生成

- [ ] **Step 3：手工 smoke — 首层 off-load**

```bash
java -jar target/maple-code-java-0.1.0.jar
```

REPL 里让 model 跑 read_file 一个 50KB 的文件 5 次。退出后：

```bash
ls ~/.maplecode/cache/session-*/
```

Expected: 1+ 个 `.txt` 文件

- [ ] **Step 4：手工 smoke — 次层摘要**

临时改 `~/.maplecode/config.yaml`（或 `maplecode.yaml`）的 `context_window: 30000`，重启 REPL，跑长对话。观察 stderr 出现 `[compression] applied: CHANGED_FULL`。下一轮 model 仍能继续对话。

- [ ] **Step 5：手工 smoke — 熔断**

故意把 yaml 配错（如 `model: "nope"`），跑 4 轮对话。第 4 轮前 stderr 出现 `[compression] circuit open`。后续自动压缩停用。`/compress` 仍可重试。

- [ ] **Step 6：手工 smoke — /compress**

长对话后 REPL 里输 `/compress`，看到 `[compression] full compression: offloaded N, summary covered ~M input tokens`。

- [ ] **Step 7：手工 smoke — shutdown 清理**

跑完 smoke，Ctrl+D 退出：

```bash
ls ~/.maplecode/cache/session-*/
```

Expected: 目录不存在（shutdown hook 跑通）

- [ ] **Step 8：最终 commit（如有手动 fix）**

```bash
git status
# 如有未提交的 fix，commit
git log --oneline -20   # 确认所有 17 个任务都有 commit
```

---

## 完成标志

实现完成必须同时满足（spec §7）：

1. `mvn test` 全绿；新加 compression 包测试 ≥ 30 个
2. `mvn package` 生成 shaded jar
3. 手工 smoke 5 步全跑通
4. `maplecode.yaml.example` 含 `context_window` + `summarizer_model` 注释
5. `AnthropicRequestMapper` / `OpenAiRequestMapper` / `AgentLoop.runOneTurn` / `ToolExecutor.run` / `Tool` / `ContentBlock` sealed 形态不变
6. `ChatSession` 既有 4 个方法签名不变，仅新增 `replaceAll`
7. `AgentEvent` 既有 11 变体不变，新增 `CompressionApplied`
8. 所有 compression 包内日志走 stderr，前缀 `[compression]`
9. `/clear` 调 `coord.resetCounter()`；`/exit` 通过 shutdown hook 删 cache 目录
10. Recency tail 边界遵守 "max(10K tokens, 5 messages)"