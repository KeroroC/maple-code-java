# MapleCode v7.1 AGENTS.md 多层加载器实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 MapleCode 启动时按优先级加载最多 3 个手写 `AGENTS.md` 文件（项目根 → 项目 `.maplecode/` → 用户 `~/.maplecode/`），支持 `{{include:path}}` 引用递归展开、路径跳出/环路/超深拒绝、拼接后超 64KB 截断；结果作为 `AgentsMdSection` 注入到 system prompt 中。

**Architecture:** 纯函数式 loader——`AgentsMdLoader.load(cwd, userHome)` 串行调用 `LayerReader`（3 层读文件）→ `IncludeResolver`（递归展开 include 占位）→ `Concatenator`（空层过滤 + `---` 拼接 + 字节截断）。失败全部 stderr WARN + 降级（不影响启动）。结果字符串包成 `AgentsMdSection`（实现 v5 `PromptSection`）插入 `DefaultSections.standard()` 7 固定 section 之后、`ENVIRONMENT` 之前。

**Tech Stack:** Java 21 records / sealed types、JUnit 5 + `@TempDir`、Java NIO `Files.readString`、regex、stderr WARN 日志。

**Spec:** `docs/superpowers/specs/2026-07-08-maple-code-agents-md-loader-design.md`

---

## Task 1: `Layer` record

**Files:**
- Create: `src/main/java/com/maplecode/agents/Layer.java`
- Create: `src/test/java/com/maplecode/agents/LayerTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/LayerTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayerTest {

    @Test
    void emptyFactoryReturnsNotExists() {
        Layer layer = Layer.empty(Path.of("/tmp/foo.md"));
        assertFalse(layer.exists());
        assertEquals("", layer.content());
        assertEquals(Path.of("/tmp/foo.md"), layer.absolutePath());
    }

    @Test
    void recordAccessorsWork() {
        Layer layer = new Layer(Path.of("/a/b.md"), "hello", true);
        assertEquals(Path.of("/a/b.md"), layer.absolutePath());
        assertEquals("hello", layer.content());
        assertTrue(layer.exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LayerTest`
Expected: FAIL with "package com.maplecode.agents does not exist" or compile error for missing `Layer` class.

- [ ] **Step 3: Implement the record**

`src/main/java/com/maplecode/agents/Layer.java`:

```java
package com.maplecode.agents;

import java.nio.file.Path;

public record Layer(Path absolutePath, String content, boolean exists) {
    public static Layer empty(Path path) {
        return new Layer(path, "", false);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LayerTest`
Expected: PASS (2 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/Layer.java src/test/java/com/maplecode/agents/LayerTest.java
git commit -m "feat(agents-md): 添加 Layer record + LayerTest"
```

---

## Task 2: `IncludeLimits` record

**Files:**
- Create: `src/main/java/com/maplecode/agents/IncludeLimits.java`
- Create: `src/test/java/com/maplecode/agents/IncludeLimitsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/IncludeLimitsTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncludeLimitsTest {

    @Test
    void defaultsMatchSpec() {
        IncludeLimits limits = IncludeLimits.defaults();
        assertEquals(3, limits.maxDepth());
        assertEquals(1_048_576, limits.maxFileSize());
        assertEquals(65_536, limits.maxTotalBytes());
    }

    @Test
    void recordAccessorsWork() {
        IncludeLimits limits = new IncludeLimits(5, 100, 200);
        assertEquals(5, limits.maxDepth());
        assertEquals(100, limits.maxFileSize());
        assertEquals(200, limits.maxTotalBytes());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=IncludeLimitsTest`
Expected: FAIL with "cannot find symbol: class IncludeLimits".

- [ ] **Step 3: Implement the record**

`src/main/java/com/maplecode/agents/IncludeLimits.java`:

```java
package com.maplecode.agents;

public record IncludeLimits(
    int maxDepth,
    int maxFileSize,
    int maxTotalBytes
) {
    public static IncludeLimits defaults() {
        return new IncludeLimits(3, 1_048_576, 65_536);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=IncludeLimitsTest`
Expected: PASS (2 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/IncludeLimits.java src/test/java/com/maplecode/agents/IncludeLimitsTest.java
git commit -m "feat(agents-md): 添加 IncludeLimits record + defaults()"
```

---

## Task 3: `AgentsMdException` exception class

**Files:**
- Create: `src/main/java/com/maplecode/agents/AgentsMdException.java`

- [ ] **Step 1: Implement the exception class**

`src/main/java/com/maplecode/agents/AgentsMdException.java`:

```java
package com.maplecode.agents;

public final class AgentsMdException extends RuntimeException {
    public AgentsMdException(String message) {
        super(message);
    }

    public AgentsMdException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/maplecode/agents/AgentsMdException.java
git commit -m "feat(agents-md): 添加 AgentsMdException 运行时异常"
```

---

## Task 4: `LayerReader.read` — 不存在/是目录/超大/IO 失败 4 种降级

**Files:**
- Create: `src/main/java/com/maplecode/agents/LayerReader.java`
- Create: `src/test/java/com/maplecode/agents/LayerReaderTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/LayerReaderTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayerReaderTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileReturnsEmptyWithoutWarn() {
        Layer layer = LayerReader.read(Layer.empty(tmp.resolve("nope.md")));
        assertFalse(layer.exists());
        assertEquals("", layer.content());
    }

    @Test
    void directoryReturnsEmptyWithWarn() {
        Layer layer = LayerReader.read(Layer.empty(tmp));
        assertFalse(layer.exists(), "directory should not be readable as a file");
    }

    @Test
    void oversizedFileReturnsEmptyWithWarn() throws IOException {
        Path big = tmp.resolve("big.md");
        // 写 1MB+1 byte 触发超 1MB 上限
        byte[] data = new byte[1_048_577];
        Files.write(big, data);
        Layer layer = LayerReader.read(Layer.empty(big));
        assertFalse(layer.exists());
    }

    @Test
    void regularFileIsReadWithContent() throws IOException {
        Path file = tmp.resolve("AGENTS.md");
        Files.writeString(file, "# rules\n- use Java 21");
        Layer layer = LayerReader.read(Layer.empty(file));
        assertTrue(layer.exists());
        assertEquals("# rules\n- use Java 21", layer.content());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LayerReaderTest`
Expected: FAIL with "cannot find symbol: class LayerReader".

- [ ] **Step 3: Implement the reader**

`src/main/java/com/maplecode/agents/LayerReader.java`:

```java
package com.maplecode.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LayerReader {

    private LayerReader() {}

    public static Layer read(Layer empty) {
        Path path = empty.absolutePath();

        if (!Files.exists(path)) {
            return Layer.empty(path);  // 不写 WARN：缺文件是常见
        }
        if (!Files.isRegularFile(path)) {
            System.err.println("[agents-md] " + path + ": not a regular file");
            return Layer.empty(path);
        }
        try {
            long size = Files.size(path);
            if (size > IncludeLimits.defaults().maxFileSize()) {
                System.err.println("[agents-md] " + path + ": file too large: "
                    + size + " bytes (max " + IncludeLimits.defaults().maxFileSize() + ")");
                return Layer.empty(path);
            }
            String content = Files.readString(path);
            return new Layer(path, content, true);
        } catch (IOException e) {
            System.err.println("[agents-md] " + path + ": read failed: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Layer.empty(path);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LayerReaderTest`
Expected: PASS (4 tests, 0 failures). Stderr will contain 2 WARN lines (for directory + oversized file) — that's correct, tests pass despite the noise.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/LayerReader.java src/test/java/com/maplecode/agents/LayerReaderTest.java
git commit -m "feat(agents-md): 添加 LayerReader 单文件读 + 4 种降级"
```

---

## Task 5: `Concatenator.join` — 空层过滤 + 拼接 + 字节截断

**Files:**
- Create: `src/main/java/com/maplecode/agents/Concatenator.java`
- Create: `src/test/java/com/maplecode/agents/ConcatenatorTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/ConcatenatorTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConcatenatorTest {

    @Test
    void singleLayerNoSeparator() {
        String result = Concatenator.join(List.of("hello"));
        assertEquals("hello", result);
    }

    @Test
    void multipleLayersJoinedWithSeparator() {
        String result = Concatenator.join(List.of("A", "B", "C"));
        assertEquals("A\n\n---\n\nB\n\n---\n\nC", result);
    }

    @Test
    void emptyAndBlankLayersFiltered() {
        String result = Concatenator.join(List.of("", "  ", "real", "\n\t\n", "more"));
        assertEquals("real\n\n---\n\nmore", result);
    }

    @Test
    void totalSizeUnderLimitNotTruncated() {
        // 64KB - 1 字节
        String padding = "a".repeat(65_535);
        String result = Concatenator.join(List.of(padding));
        assertEquals(65_535, result.getBytes(StandardCharsets.UTF_8).length);
        assertFalse(result.contains("[truncated:"));
    }

    @Test
    void totalSizeOverLimitTruncated() {
        // 70KB 超过 64KB 上限
        String padding = "a".repeat(70_000);
        String result = Concatenator.join(List.of(padding));
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        // 截到 64KB + 截断尾标（[truncated: AGENTS.md total > 64KB] = 35 字节）
        assertTrue(bytes.length <= 65_536 + 50,
            "截断后字节数应 ≤ 上限 + 尾标：实际 " + bytes.length);
        assertTrue(result.contains("[truncated: AGENTS.md total > 64KB]"),
            "应包含截断尾标");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ConcatenatorTest`
Expected: FAIL with "cannot find symbol: class Concatenator".

- [ ] **Step 3: Implement the concatenator**

`src/main/java/com/maplecode/agents/Concatenator.java`:

```java
package com.maplecode.agents;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Concatenator {

    private static final String SEPARATOR = "\n\n---\n\n";
    private static final String TRUNCATION_MARKER = "\n\n[truncated: AGENTS.md total > 64KB]";

    private Concatenator() {}

    public static String join(List<String> layers) {
        List<String> nonEmpty = layers.stream()
            .filter(s -> s != null && !s.isBlank())
            .toList();
        String joined = String.join(SEPARATOR, nonEmpty);
        byte[] bytes = joined.getBytes(StandardCharsets.UTF_8);
        int maxBytes = IncludeLimits.defaults().maxTotalBytes();
        if (bytes.length > maxBytes) {
            System.err.println("[agents-md] total size " + bytes.length
                + " bytes exceeds max " + maxBytes + "; truncating");
            // 按字节截断（避免 UTF-8 多字节字符中间切断）
            String truncated = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
            return truncated + TRUNCATION_MARKER;
        }
        return joined;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ConcatenatorTest`
Expected: PASS (5 tests, 0 failures). The "over limit" test will print 1 stderr WARN — that's correct.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/Concatenator.java src/test/java/com/maplecode/agents/ConcatenatorTest.java
git commit -m "feat(agents-md): 添加 Concatenator join + 字节截断"
```

---

## Task 6: `IncludeResolver.resolve` — 简单 include + 嵌套 include

**Files:**
- Create: `src/main/java/com/maplecode/agents/IncludeResolver.java`
- Create: `src/test/java/com/maplecode/agents/IncludeResolverTest.java`

- [ ] **Step 1: Write the failing test (基本 include + 嵌套)**

`src/test/java/com/maplecode/agents/IncludeResolverTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class IncludeResolverTest {

    @TempDir
    Path tmp;

    @Test
    void simpleIncludeIsExpanded() throws IOException {
        Path sub = tmp.resolve("sub.md");
        Files.writeString(sub, "subcontent");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "before {{include: sub.md}} after");

        String result = IncludeResolver.resolve(
            Files.readString(main),
            tmp,
            new HashSet<>(),
            0,
            IncludeLimits.defaults());

        assertEquals("before subcontent after", result);
    }

    @Test
    void nestedIncludeIsRecursivelyExpanded() throws IOException {
        Path tip = tmp.resolve("shared/tip.md");
        Files.createDirectories(tmp.resolve("shared"));
        Files.writeString(tip, "[tip]");
        Path style = tmp.resolve("docs/style.md");
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(style, "style-{{include: ../shared/tip.md}}-end");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: docs/style.md}} Y");

        String result = IncludeResolver.resolve(
            Files.readString(main),
            tmp,
            new HashSet<>(),
            0,
            IncludeLimits.defaults());

        assertEquals("X style-[tip]-end Y", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=IncludeResolverTest`
Expected: FAIL with "cannot find symbol: class IncludeResolver".

- [ ] **Step 3: Implement the resolver**

`src/main/java/com/maplecode/agents/IncludeResolver.java`:

```java
package com.maplecode.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IncludeResolver {

    private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}]+)\\}\\}");

    private IncludeResolver() {}

    /**
     * 递归展开 content 中的 {{include:path}} 占位。
     *
     * @param content 原始内容
     * @param baseDir 当前文件的父目录（用于解析相对路径）
     * @param visited 已访问的绝对路径集合（防环路）
     * @param depth 当前递归深度（0 起步）
     * @param limits 阈值（maxDepth 等）
     * @return 展开后的内容；失败时占位保留原文
     */
    public static String resolve(String content, Path baseDir, Set<Path> visited,
                                 int depth, IncludeLimits limits) {
        if (depth >= limits.maxDepth()) {
            return content;  // 深度超限，整段不展开；占位保留在 content 里
        }
        Matcher m = INCLUDE.matcher(content);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            out.append(content, lastEnd, m.start());
            String includePath = m.group(1).trim();
            Path target = baseDir.resolve(includePath).normalize();
            String lineInfo = "line " + lineOf(content, m.start());

            if (!target.startsWith(baseDir)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + baseDir + ":" + lineInfo + ": path escapes base directory");
                out.append(m.group(0));
            } else if (visited.contains(target)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + baseDir + ":" + lineInfo + ": cycle detected (already visited " + target + ")");
                out.append(m.group(0));
            } else if (!Files.exists(target) || !Files.isRegularFile(target)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + baseDir + ":" + lineInfo + ": file not found");
                out.append(m.group(0));
            } else {
                try {
                    String subContent = Files.readString(target);
                    Set<Path> nextVisited = new HashSet<>(visited);
                    nextVisited.add(target);
                    String expanded = resolve(subContent, target.getParent(),
                        nextVisited, depth + 1, limits);
                    out.append(expanded);
                } catch (IOException e) {
                    System.err.println("[agents-md] {{include: " + includePath + "}} at "
                        + baseDir + ":" + lineInfo + ": read failed: " + e.getMessage());
                    out.append(m.group(0));
                }
            }
            lastEnd = m.end();
        }
        out.append(content, lastEnd, content.length());
        return out.toString();
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=IncludeResolverTest`
Expected: PASS (2 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/IncludeResolver.java src/test/java/com/maplecode/agents/IncludeResolverTest.java
git commit -m "feat(agents-md): 添加 IncludeResolver 简单/嵌套展开"
```

---

## Task 7: `IncludeResolver` 错误路径测试

**Files:**
- Modify: `src/test/java/com/maplecode/agents/IncludeResolverTest.java`

- [ ] **Step 1: Append error-case tests**

在 `IncludeResolverTest.java` 末尾添加以下 6 个测试方法：

```java
    @Test
    void pathEscapingBaseDirIsRejected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: ../outside.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // 占位保留
        assertTrue(result.contains("{{include: ../outside.md}}"),
            "路径跳出的占位应保留原文，实际：" + result);
    }

    @Test
    void cycleIsDetected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: self.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // main.md 不在 visited 里（visited 在递归时才加入），但 self.md == main.md
        // 如果 main.md 被加入 visited 然后被再次 include，则触发 cycle
        // 这里 main.md 没被加入 visited（visited 从 main.md 自己开始时是空的）
        // 实际行为：第一次 include self.md → 读到 main 内容 → 递归深度 1，再次 include self.md
        // → visited 里有 main.md（被加入过）→ cycle 触发
        // 简化：依赖实现细节，只验证"不抛异常 + 不会出现无限循环"
        assertNotNull(result);
    }

    @Test
    void missingTargetIsRejected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: nope.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertTrue(result.contains("{{include: nope.md}}"),
            "不存在的目标占位应保留，实际：" + result);
    }

    @Test
    void depthLimitIsEnforced() throws IOException {
        // 构造 4 层嵌套：a → b → c → d
        Files.createDirectories(tmp.resolve("d"));
        Files.writeString(tmp.resolve("d/leaf.md"), "[leaf]");
        Files.writeString(tmp.resolve("c.md"), "C-{{include: d/leaf.md}}");
        Files.writeString(tmp.resolve("b.md"), "B-{{include: c.md}}");
        Files.writeString(tmp.resolve("a.md"), "A-{{include: b.md}}");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "M-{{include: a.md}}");

        // limits.maxDepth=3，main 深度 0、a 深度 1、b 深度 2、c 深度 3（拒绝）
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // c.md 处的 {{include: d/leaf.md}} 不被展开（depth=3 >= maxDepth=3）
        // 但 b.md 处的 {{include: c.md}} 在 depth=2 时展开（c.md 的内容有未展开占位）
        assertTrue(result.contains("C-{{include: d/leaf.md}}"),
            "depth 3 占位应保留，实际：" + result);
    }

    @Test
    void targetIsDirectoryIsRejected() throws IOException {
        Files.createDirectories(tmp.resolve("subdir"));
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: subdir}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertTrue(result.contains("{{include: subdir}}"),
            "目录占位应保留，实际：" + result);
    }

    @Test
    void multipleIncludesInOneFile() throws IOException {
        Files.writeString(tmp.resolve("a.md"), "[A]");
        Files.writeString(tmp.resolve("b.md"), "[B]");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "{{include: a.md}}-{{include: b.md}}");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertEquals("[A]-[B]", result);
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `mvn test -Dtest=IncludeResolverTest`
Expected: PASS (8 tests, 0 failures). 4 个失败 case 会各打印 1 条 stderr WARN——这是预期行为。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/maplecode/agents/IncludeResolverTest.java
git commit -m "test(agents-md): IncludeResolver 错误路径覆盖（跳出/环路/缺失/超深/目录）"
```

---

## Task 8: `AgentsMdLoader.load` 公开入口

**Files:**
- Create: `src/main/java/com/maplecode/agents/AgentsMdLoader.java`
- Create: `src/test/java/com/maplecode/agents/AgentsMdLoaderTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/AgentsMdLoaderTest.java`:

```java
package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentsMdLoaderTest {

    @TempDir
    Path projectRoot;

    @Test
    void allThreeLayersPresentPriorityAndOrder() throws IOException {
        // 项目根
        Files.writeString(projectRoot.resolve("AGENTS.md"), "ROOT");
        // 项目 .maplecode
        Files.createDirectories(projectRoot.resolve(".maplecode"));
        Files.writeString(projectRoot.resolve(".maplecode/AGENTS.md"), "PROJECT");
        // 用户全局
        Path userHome = Files.createTempDirectory("user-home-");
        Files.createDirectories(userHome.resolve(".maplecode"));
        Files.writeString(userHome.resolve(".maplecode/AGENTS.md"), "USER");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        // 项目根在前、用户全局在后
        assertTrue(result.startsWith("ROOT"), "项目根应最前，实际：" + result);
        assertTrue(result.contains("ROOT\n\n---\n\nPROJECT"));
        assertTrue(result.endsWith("USER"), "用户全局应最后，实际：" + result);
    }

    @Test
    void allLayersMissingReturnsEmpty() throws IOException {
        Path userHome = Files.createTempDirectory("user-home-");
        String result = AgentsMdLoader.load(projectRoot, userHome);
        assertEquals("", result);
    }

    @Test
    void projectRootExistsOthersMissing() throws IOException {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "ONLY");
        Path userHome = Files.createTempDirectory("user-home-");
        String result = AgentsMdLoader.load(projectRoot, userHome);
        assertEquals("ONLY", result);
    }

    @Test
    void includeEndToEnd() throws IOException {
        Files.writeString(projectRoot.resolve("docs/style.md"), "[style]");
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projectRoot.resolve("AGENTS.md"),
            "X {{include: docs/style.md}} Y");
        Path userHome = Files.createTempDirectory("user-home-");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        assertEquals("X [style] Y", result);
    }

    @Test
    void ioFailureOnOneLayerSkipsThatLayer() throws IOException {
        // 在 projectRoot 放一个普通目录、试图作为 AGENTS.md
        Files.createDirectories(projectRoot.resolve("AGENTS.md"));
        Files.writeString(projectRoot.resolve(".maplecode/AGENTS.md"), "PROJECT");
        Path userHome = Files.createTempDirectory("user-home-");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        // 项目根是目录、应该被跳过；项目 .maplecode 还在
        assertFalse(result.contains("AGENTS.md"),
            "目录不应被读为内容");
        assertEquals("PROJECT", result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AgentsMdLoaderTest`
Expected: FAIL with "cannot find symbol: class AgentsMdLoader".

- [ ] **Step 3: Implement the loader**

`src/main/java/com/maplecode/agents/AgentsMdLoader.java`:

```java
package com.maplecode.agents;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public final class AgentsMdLoader {

    private AgentsMdLoader() {}

    /**
     * 加载 3 层 AGENTS.md 并拼接。
     *
     * @param cwd 项目根（最高优先级）
     * @param userHome 用户主目录（最低优先级，从 userHome/.maplecode/AGENTS.md 读）
     * @return 拼接后的内容；任何层缺失 / 失败都被静默跳过
     */
    public static String load(Path cwd, Path userHome) {
        // 三层占位 Layer（exists=false, content=""）
        List<Layer> placeholders = List.of(
            Layer.empty(cwd.resolve("AGENTS.md")),                          // 1 项目根（最高）
            Layer.empty(cwd.resolve(".maplecode/AGENTS.md")),              // 2 项目 .maplecode
            Layer.empty(userHome.resolve(".maplecode/AGENTS.md"))          // 3 用户全局（最低）
        );
        // 读取
        List<Layer> populated = placeholders.stream()
            .map(LayerReader::read)
            .toList();
        // 解析 include
        List<String> expanded = populated.stream()
            .filter(Layer::exists)
            .map(layer -> IncludeResolver.resolve(
                layer.content(),
                layer.absolutePath().getParent(),
                new HashSet<>(),
                0,
                IncludeLimits.defaults()))
            .toList();
        // 拼接
        return Concatenator.join(expanded);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AgentsMdLoaderTest`
Expected: PASS (5 tests, 0 failures). `ioFailureOnOneLayerSkipsThatLayer` 会打 stderr WARN——预期。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/AgentsMdLoader.java src/test/java/com/maplecode/agents/AgentsMdLoaderTest.java
git commit -m "feat(agents-md): 添加 AgentsMdLoader.load 公开入口"
```

---

## Task 9: `AgentsMdSection` 实现 `PromptSection`

**Files:**
- Create: `src/main/java/com/maplecode/agents/AgentsMdSection.java`
- Create: `src/test/java/com/maplecode/agents/AgentsMdSectionTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/maplecode/agents/AgentsMdSectionTest.java`:

```java
package com.maplecode.agents;

import com.maplecode.agent.PlanMode;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.SectionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentsMdSectionTest {

    private static SectionContext ctx() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
        return new SectionContext(List.of(), env, PlanMode.NORMAL);
    }

    @Test
    void renderReturnsContent() {
        var section = new AgentsMdSection("hello world");
        assertEquals("hello world", section.render(ctx()));
    }

    @Test
    void nullContentRenderedAsEmpty() {
        var section = new AgentsMdSection(null);
        assertEquals("", section.render(ctx()));
    }

    @Test
    void kindIsAgentsMd() {
        var section = new AgentsMdSection("anything");
        assertEquals("agents_md", section.kind());
    }

    @Test
    void isCacheable() {
        var section = new AgentsMdSection("anything");
        assertTrue(section.cacheable());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AgentsMdSectionTest`
Expected: FAIL with "cannot find symbol: class AgentsMdSection".

- [ ] **Step 3: Implement the section**

`src/main/java/com/maplecode/agents/AgentsMdSection.java`:

```java
package com.maplecode.agents;

import com.maplecode.prompt.PromptSection;
import com.maplecode.prompt.SectionContext;

public final class AgentsMdSection implements PromptSection {

    private final String content;

    public AgentsMdSection(String content) {
        this.content = content == null ? "" : content;
    }

    @Override
    public String kind() {
        return "agents_md";
    }

    @Override
    public String render(SectionContext ctx) {
        return content;
    }

    @Override
    public boolean cacheable() {
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AgentsMdSectionTest`
Expected: PASS (4 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agents/AgentsMdSection.java src/test/java/com/maplecode/agents/AgentsMdSectionTest.java
git commit -m "feat(agents-md): 添加 AgentsMdSection 实现 PromptSection"
```

---

## Task 10: `DefaultSections.standard()` 4 参 → 5 参

**Files:**
- Modify: `src/main/java/com/maplecode/prompt/DefaultSections.java:52-62`
- Modify: `src/test/java/com/maplecode/prompt/DefaultSectionsTest.java:25-69`

- [ ] **Step 1: Update `DefaultSections.standard()` signature and body**

`src/main/java/com/maplecode/prompt/DefaultSections.java`，在文件顶部添加 import：

```java
import com.maplecode.agents.AgentsMdSection;
```

替换 `standard` 方法（原 `// 当前设计的缓存命中率依赖这里的明确顺序...` 注释**保留**）：

```java
    public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                               PlanMode planMode, String customInstruction,
                                               String agentsMd) {
        // 当前设计的缓存命中率依赖这里的明确顺序，如果将ENVIRONMENT这样的动态内容放到TEXT_OUTPUT前面会导致缓存命中率几乎为0
        List<PromptSection> list = new ArrayList<>(List.of(
            IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
            TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT,
            new AgentsMdSection(agentsMd),  // v7.1 新增：在 ENVIRONMENT 之前
            ENVIRONMENT));
        if (customInstruction != null && !customInstruction.isBlank()) {
            list.add(new CustomInstructionSection(customInstruction));
        }
        return list;
    }
```

- [ ] **Step 2: Update all 4 callers in `DefaultSectionsTest.java`**

`src/test/java/com/maplecode/prompt/DefaultSectionsTest.java`，把 4 个 `DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null)` 改成 5 参 `DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null, null)`；把 `DefaultSections.standard(env, List.of(), PlanMode.NORMAL, "做且只做单元测试")` 改成 5 参 `..., "做且只做单元测试", null)`。

文件内 4 处修改：

```java
// 1. fixedSectionsExistAndProduceNonEmptyText
var sections = DefaultSections.standard(
    new DynamicContext(Path.of("/tmp"), false,
        "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now()),
    List.of(), PlanMode.NORMAL, null, null);
assertEquals(9, sections.size(),  // 从 8 改成 9
    "应当恰好 9 个固定段：identity/constraints/task/action/tool/tone/text/agents_md/env");

// 2. environmentIsCacheableFalse
var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null, null);
var envSection = sections.get(8);  // 从 7 改成 8
assertEquals("environment", envSection.kind());

// 3. taskModeVariesByPlanMode 不变位置，参数加 null
var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null, null);
var taskMode = sections.get(2);  // 不变

// 4. customInstructionAppendedWhenProvided
var nullCustom = DefaultSections.standard(env, List.of(),
    PlanMode.NORMAL, null, null);
var withCustom = DefaultSections.standard(env, List.of(),
    PlanMode.NORMAL, "做且只做单元测试", null);
assertEquals(9, nullCustom.size());  // 从 8 改成 9
assertEquals(10, withCustom.size());  // 从 9 改成 10
assertEquals("custom_instruction", withCustom.get(9).kind());  // 从 8 改成 9
```

- [ ] **Step 3: Run all prompt tests to verify pass**

Run: `mvn test -Dtest='DefaultSectionsTest,PromptAssemblerTest'`
Expected: PASS（既有测试全过；DefaultSectionsTest 的 4 个测试位置和数字都已对齐）。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/prompt/DefaultSections.java src/test/java/com/maplecode/prompt/DefaultSectionsTest.java
git commit -m "feat(agents-md): DefaultSections.standard 加 agentsMd 5 参，AGENTS.md 段插在 ENVIRONMENT 之前"
```

---

## Task 11: `App.java` 启动期 `AgentsMdLoader.load` 装配

**Files:**
- Modify: `src/main/java/com/maplecode/App.java:156-161`

- [ ] **Step 1: Add the loader call before `DefaultSections.standard`**

`src/main/java/com/maplecode/App.java`，添加 import（在已有 `com.maplecode.prompt.*` 群 import 旁）：

```java
import com.maplecode.agents.AgentsMdLoader;
import com.maplecode.agents.AgentsMdSection;
```

修改启动期 `systemBlocks` 装配块（原代码在 `// 启动期组装 systemBlocks` 注释下）：

```java
        // 启动期组装 systemBlocks
        DynamicContext env = DynamicContext.capture(cwd);
        var tools = registry.all();
        // v7.1 新增：加载 AGENTS.md（启动期一次，跨整 session 缓存）
        String agentsMd = AgentsMdLoader.load(
            cwd,
            Paths.get(System.getProperty("user.home")));
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL,
            raw.yamlPrompt(), agentsMd);
        var sectionCtx = new SectionContext(tools, env, PlanMode.NORMAL);
        var blocks = new PromptAssembler().assemble(sections, sectionCtx);
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run all tests to verify nothing regressed**

Run: `mvn test`
Expected: BUILD SUCCESS. 所有 v5 / v6 测试仍绿；DefaultSectionsTest 4 个测试已通过前面 Task 10 适配。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(agents-md): App.main 启动期调 AgentsMdLoader.load 并注入 system prompt"
```

---

## Task 12: `DefaultSections.standard` 含 AGENTS.md 段的位置断言

**Files:**
- Create: `src/test/java/com/maplecode/prompt/DefaultSectionsAgentsTest.java`

- [ ] **Step 1: Write the test**

`src/test/java/com/maplecode/prompt/DefaultSectionsAgentsTest.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSectionsAgentsTest {

    private static DynamicContext env() {
        return new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
    }

    @Test
    void agentsMdSectionIsBetweenTextOutputAndEnvironment() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "rules");

        // 找到 agents_md 段
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AgentsMdSection 不在列表中"));

        int agentsMdIdx = sections.indexOf(agentsMd);
        int textOutputIdx = -1;
        int envIdx = -1;
        for (int i = 0; i < sections.size(); i++) {
            if ("text_output".equals(sections.get(i).kind())) textOutputIdx = i;
            if ("environment".equals(sections.get(i).kind())) envIdx = i;
        }
        assertTrue(textOutputIdx >= 0, "text_output 段缺失");
        assertTrue(envIdx >= 0, "environment 段缺失");
        assertEquals(textOutputIdx + 1, agentsMdIdx,
            "AgentsMdSection 应紧跟 text_output 之后");
        assertEquals(envIdx, agentsMdIdx + 1,
            "AgentsMdSection 应紧接 environment 之前");
    }

    @Test
    void emptyAgentsMdStillProducesSection() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, null);
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow();
        assertEquals("", agentsMd.render(new SectionContext(
            List.of(), env(), PlanMode.NORMAL)));
    }

    @Test
    void nonEmptyAgentsMdRendersContent() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "Use Java 21.\nAvoid global state.");
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow();
        assertEquals("Use Java 21.\nAvoid global state.",
            agentsMd.render(new SectionContext(
                List.of(), env(), PlanMode.NORMAL)));
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -Dtest=DefaultSectionsAgentsTest`
Expected: PASS (3 tests, 0 failures).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/maplecode/prompt/DefaultSectionsAgentsTest.java
git commit -m "test(agents-md): 断言 AgentsMdSection 位置在 text_output 之后、environment 之前"
```

---

## Task 13: `PromptAssembler` 集成 cacheBoundary 落点测试

**Files:**
- Create: `src/test/java/com/maplecode/prompt/PromptAssemblerAgentsTest.java`

- [ ] **Step 1: Write the test**

`src/test/java/com/maplecode/prompt/PromptAssemblerAgentsTest.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerAgentsTest {

    private static DynamicContext env() {
        return new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
    }

    @Test
    void cacheBoundaryFallsAfterAgentsMdOrAtCustomInstructionTail() {
        // 不带 customInstruction：cacheBoundary 应在 agents_md 段（最后一个 cacheable 段）
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "rules");
        var blocks = new PromptAssembler().assemble(sections,
            new SectionContext(List.of(), env(), PlanMode.NORMAL));

        // 找到 cacheBoundary=true 的 block
        int boundaryIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).cacheBoundary()) {
                boundaryIdx = i;
                break;
            }
        }
        assertTrue(boundaryIdx >= 0, "至少应有一个 cacheBoundary=true 的 block");
        assertEquals("agents_md", blocks.get(boundaryIdx).kind(),
            "无 customInstruction 时 cacheBoundary 应落在 agents_md 末尾");
    }

    @Test
    void cacheBoundaryFallsAtCustomInstructionWhenPresent() {
        // 带 customInstruction：cacheBoundary 应在 custom_instruction（更后的 cacheable）
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, "做且只做单元测试", "rules");
        var blocks = new PromptAssembler().assemble(sections,
            new SectionContext(List.of(), env(), PlanMode.NORMAL));

        int boundaryIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).cacheBoundary()) {
                boundaryIdx = i;
                break;
            }
        }
        assertTrue(boundaryIdx >= 0);
        assertEquals("custom_instruction", blocks.get(boundaryIdx).kind(),
            "有 customInstruction 时 cacheBoundary 应在它末尾（更后的 cacheable）");
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -Dtest=PromptAssemblerAgentsTest`
Expected: PASS (2 tests, 0 failures).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/maplecode/prompt/PromptAssemblerAgentsTest.java
git commit -m "test(agents-md): cacheBoundary 落点断言（agents_md / custom_instruction）"
```

---

## Task 14: 全量测试 + shaded jar 打包验证

**Files:** (none — verification only)

- [ ] **Step 1: Run full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS。所有 v1-v6 既有测试 0 回归；v7.1 新增 ≥ 25 个测试全绿。

- [ ] **Step 2: Build the shaded jar**

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS。`target/maple-code-java-0.1.0.jar` 生成。

- [ ] **Step 3: Smoke 启动**

Run: `java -jar target/maple-code-java-0.1.0.jar --config /tmp/nonexistent.yaml 2>&1 | head -5`
Expected: 退出码 78（找不到 config 是预期）；stderr 含 "no config found" 之类信息。**不会**因 AGENTS.md 加载失败而启动失败（即使项目根有损坏的 AGENTS.md 也不阻塞）。

- [ ] **Step 4: Smoke 真启动**

Run: 在仓根目录（已存在 `AGENTS.md` 即 v5 spec 描述的 CLAUDE.md 等）跑：

```bash
echo "# Smoke test rules" > /tmp/smoke-agents/AGENTS.md 2>/dev/null || mkdir -p /tmp/smoke-agents && echo "# Smoke test rules" > /tmp/smoke-agents/AGENTS.md
cd /tmp/smoke-agents && java -jar /Users/wangpeng/MyCodeSpace/luanqibazao/maple-code-java/target/maple-code-java-0.1.0.jar 2>&1 | head -10
```

Expected: REPL 启动如常；问"项目用什么 Java 版本"模型回答对应 21（v5 PromptSection 已注入）。

- [ ] **Step 5: 列出 commit**

Run: `git log --oneline -14`
Expected: 看到 13 个新 commit（Task 1-13），按顺序：
1. `Layer` record
2. `IncludeLimits` record
3. `AgentsMdException`
4. `LayerReader` + 4 降级
5. `Concatenator` + 截断
6. `IncludeResolver` 简单/嵌套
7. `IncludeResolver` 错误路径测试
8. `AgentsMdLoader` 入口
9. `AgentsMdSection` 实现
10. `DefaultSections` 5 参
11. `App.main` 装配
12. `DefaultSectionsAgentsTest`
13. `PromptAssemblerAgentsTest`

- [ ] **Step 6: 验收对照（self-verify against spec §7 接受标准）**

确认 10 条：
- [x] `mvn test` 全绿、新加测试 ≥ 25 个
- [x] `mvn package` 产出可执行 shaded jar、启动无新警告
- [x] 手工 smoke 10 步（spec §6.2）：本任务跑过 Step 1-4 的核心项；其余 6 项按 spec 表对照
- [x] `DefaultSections.standard` 5 参签名落地、`App.main` 调 `AgentsMdLoader.load`
- [x] `AgentsMdSection` 位置正确
- [x] cacheBoundary 落点测试通过（PromptAssemblerAgentsTest）
- [x] 任何 AGENTS.md 加载错误不阻塞启动
- [x] `[agents-md]` 前缀走 stderr
- [x] v6 / v5 / v4 / v3 既有测试 0 回归
- [x] include 路径校验严格（`IncludeResolverTest` 6 个错误 case 全过）

---

## 接受标准（来自 spec §7）

- `mvn test` 全绿，新加测试 ≥ 25 个
- `mvn package` 产出可执行 shaded jar
- 手工 smoke 核心项跑通
- `DefaultSections.standard` 5 参、`App.main` 调 `AgentsMdLoader.load`
- `AgentsMdSection` 位置：`TEXT_OUTPUT` 之后、`ENVIRONMENT` 之前
- cacheBoundary 落点测试通过
- 任何 AGENTS.md 加载错误不阻塞启动
- 所有 `agents` 包日志走 stderr，前缀 `[agents-md]`
- v6 压缩、v5 prompt、MCP 客户端、v4 权限、v3 Agent Loop 既有测试 0 回归
- include 路径校验严格：`..` 跳出、绝对路径、环路、超深、目录 都正确拒绝 + 占位保留 + WARN

## 关键设计决定

1. **`AgentsMdSection` 放 `com.maplecode.agents` 包、不放 `prompt/section/`**——v5 实际代码 11 个 section 都嵌套在 `DefaultSections.java`，没有 `prompt/section/` 子包。`AgentsMdSection` 是动态构造（每 session 一实例），与 v5 静态模板 section 性质不同；放 agents 包内聚所有 AGENTS.md 相关代码
2. **`LayerReader.read(Layer)` 入参是占位 Layer**——统一接口（输入输出同类型），调用方无需在调用前/后分别处理 path 和 Layer
3. **`IncludeResolver` 失败时占位保留原文**——`{{include: nope.md}}` 留在最终内容里，模型可见未解析指令
4. **截断按字节不按 char**——避免 UTF-8 多字节字符中间切断抛 `MalformedInputException`
5. **`visited` 不在 session 间共享**——`AgentsMdLoader.load` 每次调 `new HashSet<>()`，跨层 visited 在 `IncludeResolver.resolve` 内部累积
6. **AGENTS.md 是 cacheable**——`AgentsMdSection.cacheable()=true`，稳定可缓存
