# MapleCode 权限系统（阶段四）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 MapleCode 装上五层防御的权限系统——黑名单 + 路径沙箱 + 三层 YAML 规则 + 三档模式 + HITL 4 选 1；权限拒绝包成 `ToolResult.error` 回灌，Agent Loop 不中断。

**Architecture:** 新建 `com.maplecode.permission` 包。核心是 `PermissionEngine` 持五层 `PermissionCheck` 串成短路管道：`BlacklistCheck → SandboxCheck → RuleCheck → ModeCheck → HitlCheck`；每层返回 `Optional<Decision>`，未决则交给下一层。`PermissionContext` per-call 新建（master state 在 engine 内用 `ConcurrentHashMap.newKeySet()`），保证 `Batch.partition()` 的 parallelStream 并发安全。`ToolExecutor` 在调 `tool.execute` 前调 `engine.check(req)`；DENY 包成 `ToolResult.error("permission denied: <reason>")`。三层 YAML 规则（user/project/local）合并顺序 first-match-wins，local 优先；HITL 选 3 时 line append 到 `permissions.local.yaml`。

**Tech Stack:** Java 21、Maven、Jackson 2.17.2（已有）、SnakeYAML 2.3（已有）、JUnit 5.11.3 + Mockito 5.20（已有）。**无新依赖**。

**Spec:** `docs/superpowers/specs/2026-07-06-maple-code-permission-system-design.md`

---

## 任务依赖与顺序

```
T1  Decision + Verdict enum (record + factories)
T2  PermissionMode enum
T3  ToolCall record
T4  PermissionRequest record
T5  PermissionCheck interface
T6  PermissionContext (per-call 视图 + session sets)
T7  PermissionEngine skeleton (构造 + setMode/mode + check 串联)
T8  PermissionEngine.permitForSession + persistProjectAllow
T9  BlacklistCheck (12 条硬编码 regex, 仅 exec)
T10 SandboxCheck (cwd.toRealPath 沙箱 + glob/grep normalize)
T11 Rule + RuleSet + PermissionFile records
T12 PermissionFileLoader (三层 YAML 合并 + 校验)
T13 RuleCheck + shell glob helper (自实现)
T14 ModeCheck (3 档)
T15 InputSource / OutputSink interfaces
T16 HitlCheck (4 选 1 + setEngine 注入 + 降级)
T17 JLineInputSource + PrintStreamOutputSink (生产实现)
T18 AppConfig + ConfigLoader.permission_mode
T19 ToolExecutor (engine 注入 + check 调用 + 6 参构造器)
T20 ReplLoop (/mode 命令 + engine 参数)
T21 App.java (装配 PermissionEngine 注入 executor + ReplLoop)
T22 跑全测 + 集成 smoke
```

每任务独立可跑、单独 commit。每个 commit 后 `mvn -q test` 应绿。

---

## Task 1: Decision + Verdict enum

**Files:**
- Create: `src/main/java/com/maplecode/permission/Decision.java`
- Create: `src/test/java/com/maplecode/permission/DecisionTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/DecisionTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionTest {

    @Test
    void allow_factory_sets_verdict_and_reason() {
        var d = Decision.allow("ok");
        assertEquals(Decision.Verdict.ALLOW, d.verdict());
        assertEquals("ok", d.reason());
    }

    @Test
    void deny_factory_sets_verdict_and_reason() {
        var d = Decision.deny("blocked");
        assertEquals(Decision.Verdict.DENY, d.verdict());
        assertEquals("blocked", d.reason());
    }

    @Test
    void record_equality() {
        assertEquals(Decision.allow("x"), Decision.allow("x"));
        assertNotEquals(Decision.allow("x"), Decision.deny("x"));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=DecisionTest`
Expected: FAIL —— `Decision` 类不存在

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/Decision.java`:

```java
package com.maplecode.permission;

/**
 * 一层 PermissionCheck 输出的决策结果。ALLOW/DENY 是终态；
 * Optional.empty() 表示未决，交给下一层。
 */
public record Decision(Verdict verdict, String reason) {

    public enum Verdict { ALLOW, DENY }

    public static Decision allow(String why) { return new Decision(Verdict.ALLOW, why); }
    public static Decision deny(String why)  { return new Decision(Verdict.DENY, why); }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=DecisionTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/Decision.java \
        src/test/java/com/maplecode/permission/DecisionTest.java
git commit -m "feat(permission): Decision record + Verdict enum"
```

---

## Task 2: PermissionMode enum

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionMode.java`
- Create: `src/test/java/com/maplecode/permission/PermissionModeTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionModeTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionModeTest {

    @Test
    void has_three_values() {
        assertEquals(3, PermissionMode.values().length);
        assertNotNull(PermissionMode.valueOf("STRICT"));
        assertNotNull(PermissionMode.valueOf("DEFAULT"));
        assertNotNull(PermissionMode.valueOf("PERMISSIVE"));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionModeTest`
Expected: FAIL —— 类不存在

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionMode.java`:

```java
package com.maplecode.permission;

/**
 * 顶层权限模式档位。决定规则未命中时怎么办。
 * STRICT → deny；PERMISSIVE → allow；DEFAULT → 交 HITL。
 */
public enum PermissionMode { STRICT, DEFAULT, PERMISSIVE }
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionModeTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionMode.java \
        src/test/java/com/maplecode/permission/PermissionModeTest.java
git commit -m "feat(permission): PermissionMode 三档枚举"
```

---

## Task 3: ToolCall record

**Files:**
- Create: `src/main/java/com/maplecode/permission/ToolCall.java`
- Create: `src/test/java/com/maplecode/permission/ToolCallTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/ToolCallTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {

    @Test
    void stores_tool_and_pattern() {
        var tc = new ToolCall("exec", "git *");
        assertEquals("exec", tc.toolName());
        assertEquals("git *", tc.pattern());
    }

    @Test
    void equality_is_value_based() {
        assertEquals(new ToolCall("exec", "git"), new ToolCall("exec", "git"));
        assertNotEquals(new ToolCall("exec", "git"), new ToolCall("exec", "ls"));
    }

    @Test
    void works_as_set_key() {
        var s = new java.util.HashSet<ToolCall>();
        s.add(new ToolCall("exec", "git"));
        assertTrue(s.contains(new ToolCall("exec", "git")));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ToolCallTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/ToolCall.java`:

```java
package com.maplecode.permission;

/**
 * 会话级 allow/deny 集合的存储单位——(toolName, pattern) 二元组。
 * pattern 是工具调用的关键字段（exec → command；其他工具 → path/pattern）。
 */
public record ToolCall(String toolName, String pattern) {}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=ToolCallTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/ToolCall.java \
        src/test/java/com/maplecode/permission/ToolCallTest.java
git commit -m "feat(permission): ToolCall record（会话集合存储单位）"
```

---

## Task 4: PermissionRequest record

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionRequest.java`
- Create: `src/test/java/com/maplecode/permission/PermissionRequestTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionRequestTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRequestTest {

    @Test
    void stores_all_three_fields() {
        JsonNode args = new ObjectMapper().createObjectNode().put("command", "ls");
        var req = new PermissionRequest("exec", args, Path.of("/tmp"));
        assertEquals("exec", req.toolName());
        assertSame(args, req.args());
        assertEquals(Path.of("/tmp"), req.cwd());
    }

    @Test
    void equality_is_value_based() {
        JsonNode a = new ObjectMapper().createObjectNode();
        JsonNode b = new ObjectMapper().createObjectNode();
        assertEquals(
            new PermissionRequest("read_file", a, Path.of("/x")),
            new PermissionRequest("read_file", b, Path.of("/x")));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionRequestTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionRequest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/**
 * 一次工具调用的请求快照，由 ToolExecutor 在调 tool.execute 前构造。
 */
public record PermissionRequest(String toolName, JsonNode args, Path cwd) {}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionRequestTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionRequest.java \
        src/test/java/com/maplecode/permission/PermissionRequestTest.java
git commit -m "feat(permission): PermissionRequest record"
```

---

## Task 5: PermissionCheck interface

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionCheck.java`
- Create: `src/test/java/com/maplecode/permission/PermissionCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckTest {

    @Test
    void anonymous_impl_can_return_decision() {
        PermissionCheck allowAll = (req, ctx) -> Optional.of(Decision.allow("ok"));
        var req = new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"),
            Path.of("/tmp"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.ALLOW,
            allowAll.check(req, ctx).orElseThrow().verdict());
    }

    @Test
    void anonymous_impl_can_return_empty_for_undecided() {
        PermissionCheck undecided = (req, ctx) -> Optional.empty();
        var req = new PermissionRequest("exec",
            new ObjectMapper().createObjectNode(),
            Path.of("/tmp"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT);
        assertTrue(undecided.check(req, ctx).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionCheckTest`
Expected: FAIL —— 接口不存在（编译失败）

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionCheck.java`:

```java
package com.maplecode.permission;

import java.util.Optional;

/**
 * 一层权限检查。check() 返回 Optional.empty() 表示未决交给下一层；
 * 返回 Optional.of(Decision) 表示终态，PermissionEngine 据此短路。
 * <p>
 * 非 sealed：同 Tool 的理由——单测要匿名 mock。
 */
public interface PermissionCheck {
    Optional<Decision> check(PermissionRequest req, PermissionContext ctx);
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionCheck.java \
        src/test/java/com/maplecode/permission/PermissionCheckTest.java
git commit -m "feat(permission): PermissionCheck 接口（非 sealed）"
```

---

## Task 6: PermissionContext（per-call 视图）

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionContext.java`
- Create: `src/test/java/com/maplecode/permission/PermissionContextTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionContextTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionContextTest {

    @Test
    void exposes_mode() {
        var ctx = new PermissionContext(PermissionMode.STRICT);
        assertEquals(PermissionMode.STRICT, ctx.mode());
    }

    @Test
    void exposes_session_sets() {
        Set<ToolCall> allow = ConcurrentHashMap.newKeySet();
        Set<ToolCall> deny = ConcurrentHashMap.newKeySet();
        allow.add(new ToolCall("exec", "ls"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT, allow, deny);
        assertTrue(ctx.sessionAllows().contains(new ToolCall("exec", "ls")));
        assertTrue(ctx.sessionDenies().isEmpty());
    }

    @Test
    void session_sets_are_thread_safe_under_concurrent_add() throws Exception {
        Set<ToolCall> allow = ConcurrentHashMap.newKeySet();
        Set<ToolCall> deny = ConcurrentHashMap.newKeySet();
        var ctx = new PermissionContext(PermissionMode.DEFAULT, allow, deny);

        int threads = 100, perThread = 100;
        var pool = Executors.newFixedThreadPool(16);
        var latch = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < perThread; i++) {
                    ctx.sessionAllows().add(new ToolCall("exec", "cmd-" + tid + "-" + i));
                }
            });
        }
        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, ctx.sessionAllows().size());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionContextTest`
Expected: FAIL —— 类不存在（编译失败）

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionContext.java`:

```java
package com.maplecode.permission;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-call 视图。每次 PermissionEngine.check() 都新建一份，
 * 避免 Batch.partition() 的 parallelStream 撞共享可变状态。
 * 内部持有的 set 引用是 engine 的 master set（thread-safe），
 * 因此 ctx 看似独立但写操作实际作用在 engine 的 master state。
 */
public final class PermissionContext {

    private final PermissionMode mode;
    private final Set<ToolCall> sessionAllow;
    private final Set<ToolCall> sessionDeny;

    public PermissionContext(PermissionMode mode) {
        this(mode, ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
    }

    public PermissionContext(PermissionMode mode,
                              Set<ToolCall> sessionAllow,
                              Set<ToolCall> sessionDeny) {
        this.mode = mode;
        this.sessionAllow = sessionAllow;
        this.sessionDeny = sessionDeny;
    }

    public PermissionMode mode() { return mode; }
    public Set<ToolCall> sessionAllows() { return sessionAllow; }
    public Set<ToolCall> sessionDenies() { return sessionDeny; }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionContextTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionContext.java \
        src/test/java/com/maplecode/permission/PermissionContextTest.java
git commit -m "feat(permission): PermissionContext per-call 视图"
```

---

## Task 7: PermissionEngine 骨架（构造 + setMode + 串联 check）

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionEngine.java`
- Create: `src/test/java/com/maplecode/permission/PermissionEngineTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionEngineTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    private static PermissionRequest req() {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"),
            Path.of("/tmp"));
    }

    @Test
    void first_deciding_check_short_circuits() {
        var denyFirst = (PermissionCheck) (r, c) -> Optional.of(Decision.deny("first"));
        var allowSecond = (PermissionCheck) (r, c) -> Optional.of(Decision.allow("second"));
        var engine = new PermissionEngine(List.of(denyFirst, allowSecond), PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.DENY, engine.check(req()).verdict());
    }

    @Test
    void undecided_passes_through_to_next_layer() {
        var undecided = (PermissionCheck) (r, c) -> Optional.empty();
        var allowLast = (PermissionCheck) (r, c) -> Optional.of(Decision.allow("last"));
        var engine = new PermissionEngine(List.of(undecided, undecided, allowLast), PermissionMode.DEFAULT);
        assertEquals(Decision.Verdict.ALLOW, engine.check(req()).verdict());
    }

    @Test
    void all_undecided_returns_deny_with_explicit_reason() {
        var undecided = (PermissionCheck) (r, c) -> Optional.empty();
        var engine = new PermissionEngine(List.of(undecided), PermissionMode.DEFAULT);
        var d = engine.check(req());
        assertEquals(Decision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("no decision reached"), d.reason());
    }

    @Test
    void setMode_changes_current_mode() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        assertEquals(PermissionMode.DEFAULT, engine.mode());
        engine.setMode(PermissionMode.PERMISSIVE);
        assertEquals(PermissionMode.PERMISSIVE, engine.mode());
    }

    @Test
    void mode_is_visible_in_context() {
        var capturingCheck = new PermissionCheck() {
            PermissionMode seen;
            @Override public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
                this.seen = ctx.mode();
                return Optional.empty();
            }
        };
        var engine = new PermissionEngine(List.of(capturingCheck), PermissionMode.STRICT);
        engine.check(req());
        assertEquals(PermissionMode.STRICT, capturingCheck.seen);

        engine.setMode(PermissionMode.PERMISSIVE);
        engine.check(req());
        assertEquals(PermissionMode.PERMISSIVE, capturingCheck.seen);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionEngineTest`
Expected: FAIL —— 类不存在

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionEngine.java`:

```java
package com.maplecode.permission;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 权限引擎。持五层 PermissionCheck 串成短路管道 + master session 状态。
 * 每次 check() 都新建 PermissionContext（per-call），ctx 内部引用 engine 的
 * thread-safe master set；这样 Batch.partition() 的 parallelStream 并发安全。
 */
public final class PermissionEngine {

    private final List<PermissionCheck> checks;
    private final Set<ToolCall> sessionAllow = ConcurrentHashMap.newKeySet();
    private final Set<ToolCall> sessionDeny = ConcurrentHashMap.newKeySet();
    private final AtomicReference<PermissionMode> mode =
        new AtomicReference<>();

    public PermissionEngine(List<PermissionCheck> checks, PermissionMode defaultMode) {
        this.checks = List.copyOf(checks);
        this.mode.set(defaultMode);
    }

    /** 串联跑 checks；首个有 Decision 的短路；全未决 → deny "no decision reached"。 */
    public Decision check(PermissionRequest req) {
        var ctx = new PermissionContext(mode.get(), sessionAllow, sessionDeny);
        for (PermissionCheck c : checks) {
            Optional<Decision> d = c.check(req, ctx);
            if (d.isPresent()) return d.get();
        }
        return Decision.deny("no decision reached");
    }

    public PermissionMode mode() { return mode.get(); }
    public void setMode(PermissionMode m) { mode.set(m); }
}
```

> 注意：本任务先不实现 `permitForSession` / `persistProjectAllow`，留给 Task 8。

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionEngineTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionEngine.java \
        src/test/java/com/maplecode/permission/PermissionEngineTest.java
git commit -m "feat(permission): PermissionEngine 骨架（串联 check + setMode）"
```

---

## Task 8: PermissionEngine.permitForSession + persistProjectAllow

**Files:**
- Modify: `src/main/java/com/maplecode/permission/PermissionEngine.java`
- Modify: `src/test/java/com/maplecode/permission/PermissionEngineTest.java`

- [ ] **Step 1: 追加测试**

在 `PermissionEngineTest.java` 末尾追加：

```java
    @Test
    void permit_for_session_adds_to_session_allow_set() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.permitForSession(new ToolCall("exec", "ls"));
        assertTrue(engine.sessionAllowForTest().contains(new ToolCall("exec", "ls")));
    }

    @Test
    void deny_for_session_adds_to_session_deny_set() {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.denyForSession(new ToolCall("exec", "rm"));
        assertTrue(engine.sessionDenyForTest().contains(new ToolCall("exec", "rm")));
    }

    @Test
    void persist_project_allow_writes_yaml_entry_to_local_file(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.persistProjectAllow(tmp, "exec", "git status");
        var file = tmp.resolve(".maplecode/permissions.local.yaml");
        assertTrue(java.nio.file.Files.exists(file));
        String content = java.nio.file.Files.readString(file);
        assertTrue(content.contains("rules:"), content);
        assertTrue(content.contains("tool: exec"), content);
        assertTrue(content.contains("git status"), content);
        assertTrue(content.contains("action: allow"), content);
    }

    @Test
    void persist_project_allow_appends_to_existing_file(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.persistProjectAllow(tmp, "exec", "git status");
        engine.persistProjectAllow(tmp, "exec", "ls");
        String content = java.nio.file.Files.readString(tmp.resolve(".maplecode/permissions.local.yaml"));
        // 头只出现一次
        long rulesHeaders = content.lines().filter(l -> l.equals("rules:")).count();
        assertEquals(1, rulesHeaders);
        assertTrue(content.contains("git status"));
        assertTrue(content.contains("\"ls\""));
    }

    @Test
    void escape_yaml_string_quotes_special_chars() {
        assertEquals("\"hello \\\"world\\\"\"",
            PermissionEngine.escapeYamlStringForTest("hello \"world\""));
        assertEquals("\"a\\\\b\"", PermissionEngine.escapeYamlStringForTest("a\\b"));
        assertEquals("\"line1\\nline2\"", PermissionEngine.escapeYamlStringForTest("line1\nline2"));
    }
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionEngineTest`
Expected: FAIL —— 编译失败（方法不存在）

- [ ] **Step 3: 扩展 PermissionEngine**

替换 `src/main/java/com/maplecode/permission/PermissionEngine.java`：

```java
package com.maplecode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 权限引擎。持五层 PermissionCheck 串成短路管道 + master session 状态。
 * 每次 check() 都新建 PermissionContext（per-call），ctx 内部引用 engine 的
 * thread-safe master set；这样 Batch.partition() 的 parallelStream 并发安全。
 */
public final class PermissionEngine {

    private final List<PermissionCheck> checks;
    private final Set<ToolCall> sessionAllow = ConcurrentHashMap.newKeySet();
    private final Set<ToolCall> sessionDeny = ConcurrentHashMap.newKeySet();
    private final AtomicReference<PermissionMode> mode = new AtomicReference<>();

    public PermissionEngine(List<PermissionCheck> checks, PermissionMode defaultMode) {
        this.checks = List.copyOf(checks);
        this.mode.set(defaultMode);
    }

    /** 串联跑 checks；首个有 Decision 的短路；全未决 → deny "no decision reached"。 */
    public Decision check(PermissionRequest req) {
        var ctx = new PermissionContext(mode.get(), sessionAllow, sessionDeny);
        for (PermissionCheck c : checks) {
            Optional<Decision> d = c.check(req, ctx);
            if (d.isPresent()) return d.get();
        }
        return Decision.deny("no decision reached");
    }

    public PermissionMode mode() { return mode.get(); }
    public void setMode(PermissionMode m) { mode.set(m); }

    public void permitForSession(ToolCall tc) { sessionAllow.add(tc); }
    public void denyForSession(ToolCall tc)    { sessionDeny.add(tc); }

    /** 追加 allow rule 到 <projectRoot>/.maplecode/permissions.local.yaml。 */
    public void persistProjectAllow(Path projectRoot, String tool, String pattern) throws IOException {
        Path file = projectRoot.resolve(".maplecode/permissions.local.yaml");
        Files.createDirectories(file.getParent());
        String entry = String.format(
            "  - tool: %s%n    pattern: %s%n    action: allow%n",
            tool, escapeYamlString(pattern));
        if (!Files.exists(file)) {
            Files.writeString(file, "rules:\n" + entry);
        } else {
            Files.writeString(file, entry, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** 把含特殊字符的字符串转义成 YAML 双引号字符串。 */
    public static String escapeYamlString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    // --- test-only accessors ---
    Set<ToolCall> sessionAllowForTest() { return sessionAllow; }
    Set<ToolCall> sessionDenyForTest()  { return sessionDeny; }
    public static String escapeYamlStringForTest(String s) { return escapeYamlString(s); }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionEngineTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionEngine.java \
        src/test/java/com/maplecode/permission/PermissionEngineTest.java
git commit -m "feat(permission): engine.permitForSession / persistProjectAllow"
```

---

## Task 9: BlacklistCheck（12 条硬编码 regex）

**Files:**
- Create: `src/main/java/com/maplecode/permission/BlacklistCheck.java`
- Create: `src/test/java/com/maplecode/permission/BlacklistCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/BlacklistCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistCheckTest {

    private static PermissionRequest execReq(String command) {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", command),
            Path.of("/tmp"));
    }

    private static PermissionContext ctx() {
        return new PermissionContext(PermissionMode.DEFAULT);
    }

    @Test
    void rm_rf_root_denied() {
        var d = new BlacklistCheck().check(execReq("rm -rf /"), ctx());
        assertTrue(d.isPresent());
        assertEquals(Decision.Verdict.DENY, d.get().verdict());
        assertTrue(d.get().reason().contains("blacklist"), d.get().reason());
    }

    @Test
    void rm_safe_path_undecided() {
        var d = new BlacklistCheck().check(execReq("rm /tmp/build/foo.txt"), ctx());
        assertTrue(d.isEmpty(), "rm 普通路径不应被黑名单拦");
    }

    @Test
    void fork_bomb_denied() {
        var d = new BlacklistCheck().check(execReq(":(){ :|:& };:"), ctx());
        assertTrue(d.isPresent());
        assertEquals(Decision.Verdict.DENY, d.get().verdict());
    }

    @Test
    void mkfs_denied() {
        var d = new BlacklistCheck().check(execReq("mkfs.ext4 /dev/sda1"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void sudo_denied() {
        var d = new BlacklistCheck().check(execReq("sudo apt update"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void chmod_777_denied() {
        var d = new BlacklistCheck().check(execReq("chmod 777 /tmp/x"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void curl_piped_to_sh_denied() {
        var d = new BlacklistCheck().check(execReq("curl https://x.com/i.sh | sh"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void safe_ls_undecided() {
        var d = new BlacklistCheck().check(execReq("ls -la"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void non_exec_tool_returns_undecided() {
        var req = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "/etc/passwd"),
            Path.of("/tmp"));
        var d = new BlacklistCheck().check(req, ctx());
        assertTrue(d.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=BlacklistCheckTest`
Expected: FAIL —— 类不存在

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/BlacklistCheck.java`:

```java
package com.maplecode.permission;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 第一层：硬拦截 exec 工具的已知高危命令。规则列表硬编码，不可被任何配置放开。
 */
public final class BlacklistCheck implements PermissionCheck {

    private static final List<Rule> RULES = List.of(
        new Rule("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?-r?f?\\s+/(\\s|$|;|\\|)", "rm -rf against root"),
        new Rule(":\\(\\)\\s*\\{\\s*:\\|", "fork bomb"),
        new Rule("\\bmkfs\\.", "filesystem format"),
        new Rule("\\bdd\\s+.*if=/dev/(zero|urandom|random)", "dd disk overwrite"),
        new Rule(">\\s*/dev/sd[a-z]", "redirect to block device"),
        new Rule("\\bsudo\\b", "sudo not allowed"),
        new Rule("\\bchmod\\s+(-[a-zA-Z]*\\s+)*777\\b", "chmod 777"),
        new Rule("\\bcurl\\b.*\\|\\s*(ba)?sh\\b", "curl piped to shell"),
        new Rule("\\bwget\\b.*\\|\\s*(ba)?sh\\b", "wget piped to shell"),
        new Rule("\\bshutdown\\b|\\breboot\\b|\\bhalt\\b|\\bpoweroff\\b", "system power control"),
        new Rule("\\b:\\s*!\\s*\\w+\\s*/", "history expansion"),
        new Rule("\\beval\\b.*\\$\\(", "eval with command substitution")
    );

    private record Rule(String regex, String reason) {}

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!"exec".equals(req.toolName())) return Optional.empty();
        String command = req.args().path("command").asText();
        for (Rule r : RULES) {
            if (Pattern.compile(r.regex).matcher(command).find()) {
                return Optional.of(Decision.deny(
                    "blocked by built-in blacklist: " + r.reason));
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=BlacklistCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/BlacklistCheck.java \
        src/test/java/com/maplecode/permission/BlacklistCheckTest.java
git commit -m "feat(permission): BlacklistCheck 12 条硬编码 regex"
```

---

## Task 10: SandboxCheck（路径沙箱）

**Files:**
- Create: `src/main/java/com/maplecode/permission/SandboxCheck.java`
- Create: `src/test/java/com/maplecode/permission/SandboxCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/SandboxCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCheckTest {

    private static PermissionContext ctx(Path cwd) {
        return new PermissionContext(PermissionMode.DEFAULT);
    }

    private static PermissionRequest req(String tool, String pathField, String value) {
        var args = new ObjectMapper().createObjectNode();
        if (value != null) args.put(pathField, value);
        return new PermissionRequest(tool, args, Path.of("/tmp"));
    }

    @Test
    void relative_path_inside_sandbox_allowed(@TempDir Path tmp) throws Exception {
        var inner = tmp.resolve("foo.txt");
        Files.writeString(inner, "hi");
        var sandbox = new SandboxCheck(tmp);
        var r = req("read_file", "path", "foo.txt");
        // tmp 是 ctx.cwd()——构造时 sandbox 解析 cwd.toRealPath()，
        // 但 req.cwd 是写死的 /tmp，所以要走绝对路径匹配 sandbox root
        var realReq = new PermissionRequest("read_file", r.args(), tmp);
        assertTrue(sandbox.check(realReq, ctx(tmp)).isEmpty());
    }

    @Test
    void absolute_path_outside_sandbox_denied(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "/etc/passwd"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("sandbox"), d.get().reason());
    }

    @Test
    void dotdot_traversal_denied(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("../escape.txt"), "x");
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "../escape.txt"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void symlink_escape_denied(@TempDir Path tmp) throws Exception {
        // 在 tmp 外放一个真文件，tmp 内建 symlink 指向它
        var outsideDir = tmp.getParent().resolve("outside-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        var real = outsideDir.resolve("secret.txt");
        Files.writeString(real, "secret");
        var link = tmp.resolve("link.txt");
        Files.createSymbolicLink(link, real);

        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "link.txt"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict(),
            "symlink 解析到 sandbox 外必须 deny");
    }

    @Test
    void nonexistent_path_returns_undecided(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("read_file",
            new ObjectMapper().createObjectNode().put("path", "does-not-exist.txt"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty(),
            "路径不存在不归沙箱管——让工具层自己报 file not found");
    }

    @Test
    void exec_tool_skips_sandbox(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls /etc"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty());
    }

    @Test
    void glob_pattern_outside_sandbox_denied(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("glob",
            new ObjectMapper().createObjectNode().put("pattern", "../*"),
            tmp);
        var d = sandbox.check(r, ctx(tmp));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void grep_default_path_inside_sandbox_undecided(@TempDir Path tmp) {
        var sandbox = new SandboxCheck(tmp);
        var r = new PermissionRequest("grep",
            new ObjectMapper().createObjectNode().put("pattern", "foo"),
            tmp);
        assertTrue(sandbox.check(r, ctx(tmp)).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=SandboxCheckTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/SandboxCheck.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 第二层：路径沙箱。所有 PATH_TOOLS 工具的 path/pattern 参数必须落在
 * 「启动时 cwd 的真实路径」之内。先 toRealPath() 解析符号链接再 startsWith，
 * 防 symlink 逃逸。glob/grep pattern 用 normalize 而非 toRealPath——
 * pattern 不是具体文件。
 */
public final class SandboxCheck implements PermissionCheck {

    private static final Set<String> PATH_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "glob", "grep");

    private final Path sandboxRoot;

    public SandboxCheck(Path cwd) {
        try {
            this.sandboxRoot = cwd.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve sandbox root: " + cwd, e);
        }
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!PATH_TOOLS.contains(req.toolName())) return Optional.empty();

        JsonNode pathNode = switch (req.toolName()) {
            case "glob" -> req.args().get("pattern");
            case "grep" -> req.args().has("path") ? req.args().get("path") : null;
            default     -> req.args().get("path");
        };
        if (pathNode == null || pathNode.isNull()) return Optional.empty();

        String raw = pathNode.asText();
        Path requested = raw.startsWith("/") ? Path.of(raw) : ctx.cwd().resolve(raw);

        // glob/grep pattern 用 normalize
        if (req.toolName().equals("glob") || req.toolName().equals("grep")) {
            Path normalized = requested.normalize();
            if (!normalized.startsWith(ctx.cwd())) {
                return Optional.of(Decision.deny(
                    "path escapes sandbox: " + normalized + " is outside " + ctx.cwd()));
            }
            return Optional.empty();
        }

        // 其他三个工具：解析符号链接后前缀判断
        Path real;
        try {
            real = requested.toRealPath();
        } catch (NoSuchFileException e) {
            return Optional.empty();  // 路径不存在不归沙箱管
        } catch (IOException e) {
            return Optional.of(Decision.deny("cannot resolve path: " + e.getMessage()));
        }
        if (!real.startsWith(sandboxRoot)) {
            return Optional.of(Decision.deny(
                "path escapes sandbox: " + real + " is outside " + sandboxRoot));
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=SandboxCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/SandboxCheck.java \
        src/test/java/com/maplecode/permission/SandboxCheckTest.java
git commit -m "feat(permission): SandboxCheck 路径沙箱"
```

---

## Task 11: Rule + RuleSet + PermissionFile records

**Files:**
- Create: `src/main/java/com/maplecode/permission/Rule.java`
- Create: `src/main/java/com/maplecode/permission/RuleSet.java`
- Create: `src/main/java/com/maplecode/permission/PermissionFile.java`
- Create: `src/test/java/com/maplecode/permission/RuleTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/RuleTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleTest {

    @Test
    void stores_tool_pattern_action() {
        var r = new Rule("exec", "git *", Rule.Action.ALLOW);
        assertEquals("exec", r.toolName());
        assertEquals("git *", r.pattern());
        assertEquals(Rule.Action.ALLOW, r.action());
    }

    @Test
    void action_enum_has_allow_and_deny() {
        assertEquals(2, Rule.Action.values().length);
    }

    @Test
    void record_equality() {
        assertEquals(new Rule("exec", "ls", Rule.Action.ALLOW),
                     new Rule("exec", "ls", Rule.Action.ALLOW));
        assertNotEquals(new Rule("exec", "ls", Rule.Action.ALLOW),
                        new Rule("exec", "ls", Rule.Action.DENY));
    }

    @Test
    void rule_set_stores_ordered_list() {
        var rs = new RuleSet(java.util.List.of(
            new Rule("exec", "ls", Rule.Action.ALLOW),
            new Rule("exec", "rm", Rule.Action.DENY)));
        assertEquals(2, rs.rules().size());
        assertEquals("ls", rs.rules().get(0).pattern());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=RuleTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/Rule.java`:

```java
package com.maplecode.permission;

public record Rule(String toolName, String pattern, Action action) {
    public enum Action { ALLOW, DENY }
}
```

`src/main/java/com/maplecode/permission/RuleSet.java`:

```java
package com.maplecode.permission;

import java.util.List;

public record RuleSet(List<Rule> rules) {
    public RuleSet { rules = List.copyOf(rules); }
}
```

`src/main/java/com/maplecode/permission/PermissionFile.java`:

```java
package com.maplecode.permission;

import java.util.List;

/** 一份 YAML 文件的解析结果。 */
public record PermissionFile(List<RuleEntry> rules) {
    public record RuleEntry(String tool, String pattern, String action) {}

    public PermissionFile { rules = List.copyOf(rules); }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=RuleTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/Rule.java \
        src/main/java/com/maplecode/permission/RuleSet.java \
        src/main/java/com/maplecode/permission/PermissionFile.java \
        src/test/java/com/maplecode/permission/RuleTest.java
git commit -m "feat(permission): Rule / RuleSet / PermissionFile records"
```

---

## Task 12: PermissionFileLoader（三层 YAML 合并）

**Files:**
- Create: `src/main/java/com/maplecode/permission/PermissionFileLoader.java`
- Create: `src/test/java/com/maplecode/permission/PermissionFileLoaderTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/PermissionFileLoaderTest.java`:

```java
package com.maplecode.permission;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionFileLoaderTest {

    @Test
    void missing_user_file_skipped(@TempDir Path tmp) throws Exception {
        // user file 不存在也 OK——loadAll 通过 fakeUserHome 隔离
        var rs = PermissionFileLoader.loadAll(tmp, tmp.resolve("nope.yaml"));
        assertTrue(rs.rules().isEmpty());
    }

    @Test
    void loads_project_file(@TempDir Path tmp) throws Exception {
        var dot = tmp.resolve(".maplecode");
        Files.createDirectories(dot);
        Files.writeString(dot.resolve("permissions.yaml"), """
            rules:
              - tool: exec
                pattern: "git *"
                action: allow
            """);
        var rs = PermissionFileLoader.loadAll(tmp, tmp.resolve("nope-user.yaml"));
        assertEquals(1, rs.rules().size());
        assertEquals("exec", rs.rules().get(0).toolName());
    }

    @Test
    void merges_user_project_local_with_local_highest_priority(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: exec
                pattern: "git *"
                action: deny
            """);
        var dot = tmp.resolve(".maplecode");
        Files.createDirectories(dot);
        Files.writeString(dot.resolve("permissions.yaml"), """
            rules:
              - tool: exec
                pattern: "ls *"
                action: deny
            """);
        Files.writeString(dot.resolve("permissions.local.yaml"), """
            rules:
              - tool: exec
                pattern: "git *"
                action: allow
            """);
        var rs = PermissionFileLoader.loadAll(tmp, user);
        // 顺序：user git deny → project ls deny → local git allow
        assertEquals(3, rs.rules().size());
        assertEquals("git *", rs.rules().get(0).pattern());
        assertEquals(Rule.Action.DENY, rs.rules().get(0).action());
        assertEquals("git *", rs.rules().get(2).pattern());
        assertEquals(Rule.Action.ALLOW, rs.rules().get(2).action());
    }

    @Test
    void unknown_tool_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: Bash
                pattern: "git *"
                action: allow
            """);
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }

    @Test
    void invalid_action_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: exec
                pattern: "ls"
                action: maybe
            """);
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }

    @Test
    void malformed_yaml_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, "rules: [unclosed");
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=PermissionFileLoaderTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/PermissionFileLoader.java`:

```java
package com.maplecode.permission;

import com.maplecode.error.ConfigException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三层 YAML 规则合并：user global → project → project local。
 * first-match-wins：合并顺序就是优先级——local 在最后，最先命中。
 * 不存在的文件跳过。
 */
public final class PermissionFileLoader {

    private static final Set<String> KNOWN_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "exec", "glob", "grep");

    private PermissionFileLoader() {}

    /** 加载三层规则并合并。userFile 由调用方传入（单测可注入）。 */
    public static RuleSet loadAll(Path projectRoot, Path userFile) {
        Path projectFile  = projectRoot.resolve(".maplecode/permissions.yaml");
        Path projectLocal = projectRoot.resolve(".maplecode/permissions.local.yaml");

        List<Rule> merged = new ArrayList<>();
        merged.addAll(parseFile(userFile).rules().stream().map(PermissionFileLoader::toRule).toList());
        merged.addAll(parseFile(projectFile).rules().stream().map(PermissionFileLoader::toRule).toList());
        merged.addAll(parseFile(projectLocal).rules().stream().map(PermissionFileLoader::toRule).toList());

        for (Rule r : merged) {
            if (!KNOWN_TOOLS.contains(r.toolName())) {
                throw new ConfigException("permission rule references unknown tool: " + r.toolName());
            }
        }
        return new RuleSet(merged);
    }

    static Rule toRule(PermissionFile.RuleEntry e) {
        Rule.Action act;
        try {
            act = Rule.Action.valueOf(e.action().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ConfigException("invalid rule action: " + e.action());
        }
        return new Rule(e.tool(), e.pattern(), act);
    }

    static PermissionFile parseFile(Path path) {
        if (!Files.exists(path)) return new PermissionFile(List.of());
        try (Reader r = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(r);
            if (raw == null) return new PermissionFile(List.of());
            if (!(raw instanceof Map<?, ?> root)) {
                throw new ConfigException("permission file root must be a mapping: " + path);
            }
            Object rulesObj = root.get("rules");
            if (rulesObj == null) return new PermissionFile(List.of());
            if (!(rulesObj instanceof List<?> rulesList)) {
                throw new ConfigException("'rules' must be a list: " + path);
            }
            List<PermissionFile.RuleEntry> entries = new ArrayList<>();
            for (Object item : rulesList) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new ConfigException("rule entry must be a mapping: " + path);
                }
                Object tool = m.get("tool");
                Object pattern = m.get("pattern");
                Object action = m.get("action");
                if (tool == null || pattern == null || action == null) {
                    throw new ConfigException("rule entry missing tool/pattern/action: " + path);
                }
                entries.add(new PermissionFile.RuleEntry(
                    tool.toString(), pattern.toString(), action.toString()));
            }
            return new PermissionFile(entries);
        } catch (IOException e) {
            throw new ConfigException("failed to read permission file: " + path, e);
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=PermissionFileLoaderTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/PermissionFileLoader.java \
        src/test/java/com/maplecode/permission/PermissionFileLoaderTest.java
git commit -m "feat(permission): PermissionFileLoader 三层 YAML 合并"
```

---

## Task 13: RuleCheck + shell glob helper

**Files:**
- Create: `src/main/java/com/maplecode/permission/RuleCheck.java`
- Create: `src/test/java/com/maplecode/permission/RuleCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/RuleCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuleCheckTest {

    private static PermissionRequest req(String tool, String field, String value) {
        var args = new ObjectMapper().createObjectNode();
        args.put(field, value);
        return new PermissionRequest(tool, args, Path.of("/tmp"));
    }

    private static PermissionContext ctx() {
        return new PermissionContext(PermissionMode.DEFAULT);
    }

    @Test
    void exec_shell_glob_matches_multi_word_command() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "git *", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "git status"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void exec_exact_match() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls -la", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "ls -la"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void exec_glob_does_not_match_unrelated_command() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "git *", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "rm foo"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void path_glob_matches_against_read_file_path() {
        var rs = new RuleSet(List.of(
            new Rule("read_file", "src/**", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("read_file", "path", "src/main/Foo.java"), ctx());
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
    }

    @Test
    void first_match_wins_local_overrides_project() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls", Rule.Action.DENY),
            new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "ls"), ctx());
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
    }

    @Test
    void unmatched_returns_undecided() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("exec", "command", "rm"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void no_matching_tool_returns_undecided() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls", Rule.Action.ALLOW)));
        var d = new RuleCheck(rs).check(req("read_file", "path", "foo"), ctx());
        assertTrue(d.isEmpty());
    }

    @Test
    void shell_glob_helper_translates_star() {
        assertTrue(RuleCheck.shellGlobMatch("git *", "git status"));
        assertTrue(RuleCheck.shellGlobMatch("git *", "git"));
        assertFalse(RuleCheck.shellGlobMatch("git *", "rm"));
        assertTrue(RuleCheck.shellGlobMatch("ls ?", "ls -la"));  // ? = single char
    }

    @Test
    void shell_glob_escapes_regex_metachars() {
        assertTrue(RuleCheck.shellGlobMatch("echo a.b", "echo a.b"));
        assertFalse(RuleCheck.shellGlobMatch("echo a.b", "echo aXb"));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=RuleCheckTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/RuleCheck.java`:

```java
package com.maplecode.permission;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 第三层：可配置规则。first-match-wins。
 * exec 用自实现 shell glob（* → 任意非空格；? → 单字符）；
 * 其他工具用 java.nio.file.PathMatcher。
 */
public final class RuleCheck implements PermissionCheck {

    private final RuleSet ruleSet;

    public RuleCheck(RuleSet ruleSet) { this.ruleSet = ruleSet; }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        String pattern = extractPattern(req);
        if (pattern == null) return Optional.empty();

        for (Rule r : ruleSet.rules()) {
            if (!r.toolName().equals(req.toolName())) continue;
            if (matches(req.toolName(), r.pattern(), pattern)) {
                return Optional.of(switch (r.action()) {
                    case ALLOW -> Decision.allow("rule match: " + r);
                    case DENY  -> Decision.deny("rule match: " + r);
                });
            }
        }
        return Optional.empty();
    }

    static String extractPattern(PermissionRequest req) {
        return switch (req.toolName()) {
            case "exec" -> req.args().path("command").asText();
            case "glob" -> req.args().path("pattern").asText();
            case "grep" -> req.args().has("path") ? req.args().get("path").asText() : ".";
            case "read_file", "write_file", "edit_file" -> req.args().path("path").asText();
            default -> null;
        };
    }

    static boolean matches(String tool, String rulePattern, String actual) {
        if (rulePattern.equals(actual)) return true;
        if (tool.equals("exec")) {
            return shellGlobMatch(rulePattern, actual);
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + rulePattern)
            .matches(Path.of(actual));
    }

    /** shell glob → 正则：* = [^ ]*；? = [^ ]；其他字面匹配；全串 matches。 */
    public static boolean shellGlobMatch(String pattern, String input) {
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> rx.append("[^ ]*");
                case '?' -> rx.append("[^ ]");
                case '.', '\\', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']'
                    -> rx.append('\\').append(c);
                default -> rx.append(c);
            }
        }
        rx.append('$');
        return Pattern.compile(rx.toString()).matcher(input).matches();
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=RuleCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/RuleCheck.java \
        src/test/java/com/maplecode/permission/RuleCheckTest.java
git commit -m "feat(permission): RuleCheck + shell glob helper"
```

---

## Task 14: ModeCheck

**Files:**
- Create: `src/main/java/com/maplecode/permission/ModeCheck.java`
- Create: `src/test/java/com/maplecode/permission/ModeCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/ModeCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModeCheckTest {

    private static PermissionRequest req() {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", "ls"),
            Path.of("/tmp"));
    }

    @Test
    void strict_denies_when_no_rule_matched() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.STRICT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("strict"));
    }

    @Test
    void permissive_allows_when_no_rule_matched() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.PERMISSIVE));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("permissive"));
    }

    @Test
    void default_returns_undecided_for_hitl() {
        var d = new ModeCheck().check(req(), new PermissionContext(PermissionMode.DEFAULT));
        assertTrue(d.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ModeCheckTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/ModeCheck.java`:

```java
package com.maplecode.permission;

import java.util.Optional;

/**
 * 第四层：模式顶层决策。
 * STRICT → deny（无规则也拦）；PERMISSIVE → allow；DEFAULT → 交给下一层 HITL。
 */
public final class ModeCheck implements PermissionCheck {

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        return switch (ctx.mode()) {
            case STRICT     -> Optional.of(Decision.deny(
                                 "no matching rule and mode is strict"));
            case PERMISSIVE -> Optional.of(Decision.allow(
                                 "no matching rule and mode is permissive"));
            case DEFAULT    -> Optional.empty();
        };
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=ModeCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/ModeCheck.java \
        src/test/java/com/maplecode/permission/ModeCheckTest.java
git commit -m "feat(permission): ModeCheck 三档决策"
```

---

## Task 15: InputSource / OutputSink interfaces

**Files:**
- Create: `src/main/java/com/maplecode/permission/InputSource.java`
- Create: `src/main/java/com/maplecode/permission/OutputSink.java`
- Create: `src/test/java/com/maplecode/permission/IoInterfaceSmokeTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/IoInterfaceSmokeTest.java`:

```java
package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 用匿名 InputSource/OutputSink 验证接口契约能被实现。 */
class IoInterfaceSmokeTest {

    static class TestInput implements InputSource {
        final java.util.Queue<String> q;
        TestInput(java.util.Queue<String> q) { this.q = q; }
        @Override public String readLine(String prompt) {
            String s = q.poll();
            if (s == null) throw new java.util.NoSuchElementException("no more input");
            return s;
        }
    }

    static class TestOutput implements OutputSink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(String line) { lines.add(line); }
    }

    @Test
    void interfaces_are_implementable() {
        TestOutput out = new TestOutput();
        out.println("hello");
        assertEquals(List.of("hello"), out.lines);

        TestInput in = new TestInput(new java.util.ArrayDeque<>(List.of("answer")));
        assertEquals("answer", in.readLine("> "));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=IoInterfaceSmokeTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/InputSource.java`:

```java
package com.maplecode.permission;

/** 抽象掉 JLine，让 HITL 单测可以塞 StringReader。 */
public interface InputSource {
    String readLine(String prompt);
}
```

`src/main/java/com/maplecode/permission/OutputSink.java`:

```java
package com.maplecode.permission;

/** 抽象掉 PrintStream，让 HITL 单测可以塞 StringWriter。 */
public interface OutputSink {
    void println(String line);
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=IoInterfaceSmokeTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/InputSource.java \
        src/main/java/com/maplecode/permission/OutputSink.java \
        src/test/java/com/maplecode/permission/IoInterfaceSmokeTest.java
git commit -m "feat(permission): InputSource / OutputSink 接口"
```

---

## Task 16: HitlCheck（4 选 1 + setEngine + 降级）

**Files:**
- Create: `src/main/java/com/maplecode/permission/HitlCheck.java`
- Create: `src/test/java/com/maplecode/permission/HitlCheckTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/permission/HitlCheckTest.java`:

```java
package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class HitlCheckTest {

    private static class StubInput implements InputSource {
        final Queue<String> q;
        StubInput(String... lines) { this.q = new ArrayDeque<>(List.of(lines)); }
        @Override public String readLine(String prompt) {
            String s = q.poll();
            if (s == null) throw new IllegalStateException("no more input");
            return s;
        }
    }

    private static class CapturingOutput implements OutputSink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(String line) { lines.add(line); }
    }

    private static PermissionRequest req(String command) {
        return new PermissionRequest("exec",
            new ObjectMapper().createObjectNode().put("command", command),
            Path.of("/tmp"));
    }

    @Test
    void choice_1_allows_once_no_engine_call() {
        var out = new CapturingOutput();
        var in = new StubInput("1");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("ls"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(engine.sessionAllowForTest().isEmpty());
    }

    @Test
    void choice_2_adds_to_session_allow() {
        var out = new CapturingOutput();
        var in = new StubInput("2");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("ls"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        assertTrue(engine.sessionAllowForTest().contains(new ToolCall("exec", "ls")));
    }

    @Test
    void choice_3_writes_to_local_yaml(@TempDir Path tmp) throws Exception {
        var out = new CapturingOutput();
        var in = new StubInput("3");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        // 注入一个会用 tmp 当 projectRoot 的 engine 桩
        engine.persistProjectAllow(tmp, "exec", "ls");
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        hitl.check(req("ls"), new PermissionContext(PermissionMode.DEFAULT));
        assertTrue(Files.exists(tmp.resolve(".maplecode/permissions.local.yaml")));
    }

    @Test
    void choice_4_denies() {
        var out = new CapturingOutput();
        var in = new StubInput("4");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("rm"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("user denied"));
    }

    @Test
    void invalid_choice_denies() {
        var out = new CapturingOutput();
        var in = new StubInput("9");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var d = hitl.check(req("rm"), new PermissionContext(PermissionMode.DEFAULT));
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("invalid choice"));
    }

    @Test
    void session_allow_short_circuits_prompt() {
        var out = new CapturingOutput();
        var in = new StubInput(/* 没输入——若被问到就抛 */);
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.permitForSession(new ToolCall("exec", "ls"));
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var ctx = new PermissionContext(PermissionMode.DEFAULT,
            engine.sessionAllowForTest(), engine.sessionDenyForTest());
        var d = hitl.check(req("ls"), ctx);
        assertEquals(Decision.Verdict.ALLOW, d.orElseThrow().verdict());
        // prompt 没被触发 → out 是空的
        assertTrue(out.lines.isEmpty());
    }

    @Test
    void session_deny_short_circuits_prompt() {
        var out = new CapturingOutput();
        var in = new StubInput();
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        engine.denyForSession(new ToolCall("exec", "rm"));
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        var ctx = new PermissionContext(PermissionMode.DEFAULT,
            engine.sessionAllowForTest(), engine.sessionDenyForTest());
        var d = hitl.check(req("rm"), ctx);
        assertEquals(Decision.Verdict.DENY, d.orElseThrow().verdict());
        assertTrue(d.get().reason().contains("session deny"));
    }

    @Test
    void prompt_is_printed_with_tool_args_and_mode() {
        var out = new CapturingOutput();
        var in = new StubInput("4");
        var engine = new PermissionEngine(List.of(), PermissionMode.DEFAULT);
        var hitl = new HitlCheck(in, out);
        hitl.setEngine(engine);
        hitl.check(req("rm -rf /tmp/foo"), new PermissionContext(PermissionMode.DEFAULT));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("permission required")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("exec")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("rm -rf /tmp/foo")));
        assertTrue(out.lines.stream().anyMatch(l -> l.contains("DEFAULT")));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=HitlCheckTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

`src/main/java/com/maplecode/permission/HitlCheck.java`:

```java
package com.maplecode.permission;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 第五层：人在回路。default 模式 + 规则未命中时弹 prompt 4 选 1。
 * session allow/deny 集合优先：若 (tool, pattern) 已在 session 里，直接复用。
 * 选 3 写项目 local.yaml 失败 → 降级到 session allow + 警告。
 */
public final class HitlCheck implements PermissionCheck {

    private final InputSource input;
    private final OutputSink output;
    private PermissionEngine engine;

    public HitlCheck(InputSource input, OutputSink output) {
        this.input = input;
        this.output = output;
    }

    /** 后置注入打破构造期循环——App.main 构造完 engine 后调一次。 */
    public void setEngine(PermissionEngine engine) {
        this.engine = engine;
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        String pattern = extractPattern(req);
        if (pattern != null) {
            ToolCall tc = new ToolCall(req.toolName(), pattern);
            if (ctx.sessionAllows().contains(tc))
                return Optional.of(Decision.allow("session allow"));
            if (ctx.sessionDenies().contains(tc))
                return Optional.of(Decision.deny("session deny"));
        }

        output.println("");
        output.println("─── permission required ───────────────────────────");
        output.println("  tool:    " + req.toolName());
        output.println("  args:    " + summarizeArgs(req));
        output.println("  mode:    " + ctx.mode());
        output.println("");
        output.println("  [1] allow this time");
        output.println("  [2] allow for this session");
        output.println("  [3] allow for this project (writes permissions.local.yaml)");
        output.println("  [4] deny");
        output.println("");
        String choice;
        try {
            choice = input.readLine("  choice [1-4]: ").trim();
        } catch (RuntimeException e) {
            return Optional.of(Decision.deny("user interrupted at permission prompt"));
        }

        return switch (choice) {
            case "1" -> Optional.of(Decision.allow("user allowed once"));
            case "2" -> {
                if (pattern != null && engine != null) {
                    engine.permitForSession(new ToolCall(req.toolName(), pattern));
                }
                yield Optional.of(Decision.allow("user allowed for session"));
            }
            case "3" -> {
                if (pattern != null && engine != null) {
                    try {
                        Path cwd = Paths.get(System.getProperty("user.dir"));
                        engine.persistProjectAllow(cwd, req.toolName(), pattern);
                    } catch (IOException e) {
                        output.println("  warning: failed to persist project allow: "
                            + e.getMessage() + "; treating as session allow");
                        engine.permitForSession(new ToolCall(req.toolName(), pattern));
                    }
                }
                yield Optional.of(Decision.allow("user allowed for project"));
            }
            case "4" -> Optional.of(Decision.deny("user denied"));
            default  -> Optional.of(Decision.deny("invalid choice '" + choice + "', denying"));
        };
    }

    static String extractPattern(PermissionRequest req) {
        return RuleCheck.extractPattern(req);
    }

    private static String summarizeArgs(PermissionRequest req) {
        return switch (req.toolName()) {
            case "exec" -> req.args().path("command").asText();
            case "read_file", "write_file", "edit_file" -> req.args().path("path").asText();
            case "glob" -> req.args().path("pattern").asText();
            case "grep" -> "pattern=" + req.args().path("pattern").asText()
                + " path=" + (req.args().has("path") ? req.args().get("path").asText() : ".");
            default -> req.args().toString();
        };
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q test -Dtest=HitlCheckTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/permission/HitlCheck.java \
        src/test/java/com/maplecode/permission/HitlCheckTest.java
git commit -m "feat(permission): HitlCheck 4 选 1 + setEngine 注入"
```

---

## Task 17: JLineInputSource + PrintStreamOutputSink（生产实现）

**Files:**
- Create: `src/main/java/com/maplecode/permission/JLineInputSource.java`
- Create: `src/main/java/com/maplecode/permission/PrintStreamOutputSink.java`

- [ ] **Step 1: 写实现**

`src/main/java/com/maplecode/permission/JLineInputSource.java`:

```java
package com.maplecode.permission;

import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/** 把 JLine LineReader 包成 InputSource。Ctrl-C 抛 RuntimeException 让 HitlCheck 当 deny 处理。 */
public final class JLineInputSource implements InputSource {

    private final LineReader reader;

    public JLineInputSource(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new RuntimeException(e);
        }
    }
}
```

`src/main/java/com/maplecode/permission/PrintStreamOutputSink.java`:

```java
package com.maplecode.permission;

import java.io.PrintStream;

/** 把 PrintStream 包成 OutputSink。 */
public final class PrintStreamOutputSink implements OutputSink {

    private final PrintStream out;

    public PrintStreamOutputSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void println(String line) {
        out.println(line);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/maplecode/permission/JLineInputSource.java \
        src/main/java/com/maplecode/permission/PrintStreamOutputSink.java
git commit -m "feat(permission): JLine + PrintStream 生产实现"
```

---

## Task 18: AppConfig + ConfigLoader.permission_mode

**Files:**
- Modify: `src/main/java/com/maplecode/config/AppConfig.java`
- Modify: `src/main/java/com/maplecode/config/ConfigLoader.java`
- Create: `src/test/java/com/maplecode/config/ConfigLoaderPermissionModeTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/config/ConfigLoaderPermissionModeTest.java`:

```java
package com.maplecode.config;

import com.maplecode.error.ConfigException;
import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderPermissionModeTest {

    @Test
    void default_when_missing(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            """);
        assertEquals(PermissionMode.DEFAULT, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void explicit_strict(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: strict
            """);
        assertEquals(PermissionMode.STRICT, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void explicit_permissive(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: permissive
            """);
        assertEquals(PermissionMode.PERMISSIVE, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void invalid_value_throws(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: chaos
            """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(f));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ConfigLoaderPermissionModeTest`
Expected: FAIL —— `permissionMode()` 方法不存在

- [ ] **Step 3: 改 AppConfig**

`src/main/java/com/maplecode/config/AppConfig.java` 末尾追加字段：

```java
package com.maplecode.config;

import com.maplecode.permission.PermissionMode;
import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ThinkingConfig;

import java.time.Duration;
import java.util.List;

public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String yamlPrompt,
    List<SystemBlock> systemBlocks,
    ThinkingConfig thinking,
    Timeouts timeouts,
    PermissionMode permissionMode
) {
    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }
}
```

- [ ] **Step 4: 改 ConfigLoader**

在 `src/main/java/com/maplecode/config/ConfigLoader.java` 顶部 import 加：

```java
import com.maplecode.permission.PermissionMode;
```

修改 `parse(Map<?, ?> root)` 方法——在最后 `return new AppConfig(...)` 前加：

```java
        String modeStr = optionalString(root, "permission_mode");
        PermissionMode mode = switch (modeStr == null ? "default" : modeStr) {
            case "strict"     -> PermissionMode.STRICT;
            case "default"    -> PermissionMode.DEFAULT;
            case "permissive" -> PermissionMode.PERMISSIVE;
            default -> throw new ConfigException(
                "permission_mode must be strict|default|permissive, got: " + modeStr);
        };
```

并把 `return new AppConfig(...)` 改成：

```java
        return new AppConfig(protocol, model, baseUrl, apiKey, yamlPrompt,
            List.of(), thinking, new AppConfig.Timeouts(connect, read), mode);
```

- [ ] **Step 5: 跑测试**

Run: `mvn -q test -Dtest=ConfigLoaderPermissionModeTest,ConfigLoaderTest`
Expected: PASS

> 注意：现有三处测试用 `new AppConfig(...)` 9 参构造器，加字段后必编译失败——需补 `PermissionMode.DEFAULT` 作第 10 参：
>
> - `src/test/java/com/maplecode/provider/ProviderRegistryTest.java:18-19`
> - `src/test/java/com/maplecode/agent/AgentConfigTest.java:49-50`
> - `src/test/java/com/maplecode/agent/AgentConfigTest.java:63-64`

- [ ] **Step 6: 跑全测，确保零回归**

Run: `mvn -q test`
Expected: PASS（除了显式标"已知挂"的）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/maplecode/config/AppConfig.java \
        src/main/java/com/maplecode/config/ConfigLoader.java \
        src/test/java/com/maplecode/config/ConfigLoaderPermissionModeTest.java
git commit -m "feat(permission): AppConfig.permissionMode + ConfigLoader 解析"
```

---

## Task 19: ToolExecutor（engine 注入 + check 调用）

**Files:**
- Modify: `src/main/java/com/maplecode/tool/ToolExecutor.java`
- Create: `src/test/java/com/maplecode/tool/ToolExecutorPermissionTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/tool/ToolExecutorPermissionTest.java`:

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.permission.Decision;
import com.maplecode.permission.PermissionCheck;
import com.maplecode.permission.PermissionContext;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.permission.PermissionRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorPermissionTest {

    private static Tool mk(String name,
                            java.util.function.BiFunction<JsonNode, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    @Test
    void engine_null_skips_permission_check() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), null);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ran", r.content());
    }

    @Test
    void deny_returns_permission_denied_error_without_calling_tool() {
        AtomicInteger calls = new AtomicInteger();
        var t = mk("foo", (a, c) -> { calls.incrementAndGet(); return ToolResult.ok("ran"); });
        PermissionCheck denyAll = (req, ctx) -> Optional.of(Decision.deny("blocked"));
        var engine = new PermissionEngine(List.of(denyAll), PermissionMode.DEFAULT);
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), engine);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertTrue(r.isError());
        assertTrue(r.content().startsWith("permission denied:"), r.content());
        assertTrue(r.content().contains("blocked"), r.content());
        assertEquals(0, calls.get(), "tool 不应被调用");
    }

    @Test
    void allow_passes_through_to_tool() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        PermissionCheck allowAll = (req, ctx) -> Optional.of(Decision.allow("ok"));
        var engine = new PermissionEngine(List.of(allowAll), PermissionMode.DEFAULT);
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)), engine);
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
        assertEquals("ran", r.content());
    }

    @Test
    void single_arg_constructor_still_works_for_backward_compat() {
        var t = mk("foo", (a, c) -> ToolResult.ok("ran"));
        // 不传 engine——旧测试路径必须仍 OK
        var exec = new ToolExecutor(new ToolRegistry(List.of(t)));
        var r = exec.run("foo", new ObjectMapper().createObjectNode());
        assertFalse(r.isError());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q test -Dtest=ToolExecutorPermissionTest`
Expected: FAIL —— 6 参构造器不存在

- [ ] **Step 3: 改 ToolExecutor**

替换 `src/main/java/com/maplecode/tool/ToolExecutor.java`：

```java
package com.maplecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.error.ToolException;
import com.maplecode.permission.Decision;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionRequest;

import java.nio.file.Path;
import java.util.stream.Collectors;

public final class ToolExecutor {

    private final ToolRegistry registry;
    private final PermissionEngine engine;  // nullable：旧测试不强制依赖

    public ToolExecutor(ToolRegistry registry) {
        this(registry, null);
    }

    public ToolExecutor(ToolRegistry registry, PermissionEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /**
     * 调工具。绝不抛异常——所有失败都包成 ToolResult(isError=true)。
     * 权限检查在 tool.execute 之前；DENY 包成 ToolResult.error 回灌。
     */
    public ToolResult run(String name, JsonNode args) {
        var toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) {
            String available = registry.all().stream()
                .map(Tool::name)
                .collect(Collectors.joining(", "));
            return ToolResult.error("Unknown tool: " + name + ". Available: " + available);
        }

        if (engine != null) {
            Path cwd = Path.of(System.getProperty("user.dir"));
            Decision decision = engine.check(new PermissionRequest(name, args, cwd));
            if (decision.verdict() == Decision.Verdict.DENY) {
                return ToolResult.error("permission denied: " + decision.reason());
            }
        }

        try {
            ToolContext ctx = ToolContext.defaults(Path.of(System.getProperty("user.dir")));
            return toolOpt.get().execute(args, ctx);
        } catch (ToolException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ToolExecutorPermissionTest,ToolExecutorTest`
Expected: PASS（既有 ToolExecutorTest 单参构造器路径必须仍通过）

- [ ] **Step 5: 跑全测**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/maplecode/tool/ToolExecutor.java \
        src/test/java/com/maplecode/tool/ToolExecutorPermissionTest.java
git commit -m "feat(permission): ToolExecutor 注入 engine + check 调用"
```

---

## Task 20: ReplLoop（/mode 命令 + engine 参数）

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 改 ReplLoop**

在 `src/main/java/com/maplecode/ui/ReplLoop.java` 顶部 import 加：

```java
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
```

修改构造器：

```java
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this.appConfig = appConfig;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
        this.registry = registry;
        this.executor = executor;
        this.engine = engine;
        this.session = new ChatSession();
        this.agentConfig = agentConfig;
        this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
                printer::usage);
    }
```

在字段区追加：

```java
    private final PermissionEngine engine;
```

在 `run()` 里的 `/cancel` 分支**之前**插入 `/mode` 分支：

```java
            // /mode
            if (trimmed.equals("/mode") || trimmed.startsWith("/mode ")) {
                String arg = trimmed.length() > 5 ? trimmed.substring(6).trim() : "";
                switch (arg) {
                    case "strict", "default", "permissive" -> {
                        engine.setMode(PermissionMode.valueOf(arg.toUpperCase()));
                        printer.info("mode → " + arg);
                    }
                    case "" -> printer.info("current mode: " + engine.mode());
                    default  -> printer.error("/mode <strict|default|permissive>");
                }
                continue;
            }
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: 编译通过（App.java 还没改，会报 ReplLoop 构造器签名不匹配——暂时可以放过，下个任务改 App）

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(permission): ReplLoop /mode 命令 + engine 参数"
```

---

## Task 21: App.java 装配 PermissionEngine

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: 改 App.java**

替换 `src/main/java/com/maplecode/App.java`：

```java
package com.maplecode;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.permission.BlacklistCheck;
import com.maplecode.permission.HitlCheck;
import com.maplecode.permission.InputSource;
import com.maplecode.permission.JLineInputSource;
import com.maplecode.permission.ModeCheck;
import com.maplecode.permission.OutputSink;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionFileLoader;
import com.maplecode.permission.PrintStreamOutputSink;
import com.maplecode.permission.RuleCheck;
import com.maplecode.permission.RuleSet;
import com.maplecode.permission.SandboxCheck;
import com.maplecode.prompt.DefaultSections;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.PromptAssembler;
import com.maplecode.prompt.SectionContext;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.tool.EditFileTool;
import com.maplecode.tool.ExecTool;
import com.maplecode.tool.GlobTool;
import com.maplecode.tool.GrepTool;
import com.maplecode.tool.ReadFileTool;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.WriteFileTool;
import com.maplecode.ui.ReplLoop;
import com.maplecode.ui.StreamPrinter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class App {

    public static void main(String[] args) throws Exception {
        Path configPath = locateConfig(args);
        if (configPath == null) {
            System.err.println("no config found. Looked in:");
            System.err.println("  --config <path> argument");
            System.err.println("  ./maplecode.yaml");
            System.err.println("  ~/.maplecode/config.yaml");
            System.err.println("Run with `maplecode --config path/to/config.yaml`");
            System.exit(78);
        }
        AppConfig raw = ConfigLoader.load(configPath);
        LlmProvider provider = new ProviderRegistry().create(raw);
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ExecTool(),
            new GlobTool(),
            new GrepTool()
        ));

        // 启动期组装 systemBlocks
        Path cwd = Paths.get(System.getProperty("user.dir"));
        DynamicContext env = DynamicContext.capture(cwd);
        var tools = registry.all();
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL, raw.yamlPrompt());
        var sectionCtx = new SectionContext(tools, env, PlanMode.NORMAL);
        var blocks = new PromptAssembler().assemble(sections, sectionCtx);

        AgentConfig agentConfig = new AgentConfig(
            raw.model(), blocks, raw.thinking(),
            25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());

        // PermissionEngine 装配（后置注入打破构造期循环）
        Path userPermFile = Paths.get(System.getProperty("user.home"),
            ".maplecode", "permissions.yaml");
        RuleSet ruleSet = PermissionFileLoader.loadAll(cwd, userPermFile);
        InputSource input = new JLineInputSource(buildLineReader());
        OutputSink output = new PrintStreamOutputSink(System.out);
        HitlCheck hitlCheck = new HitlCheck(input, output);
        PermissionEngine engine = new PermissionEngine(List.of(
            new BlacklistCheck(),
            new SandboxCheck(cwd),
            new RuleCheck(ruleSet),
            new ModeCheck(),
            hitlCheck
        ), raw.permissionMode());
        hitlCheck.setEngine(engine);

        ToolExecutor executor = new ToolExecutor(registry, engine);
        ReplLoop repl = new ReplLoop(raw, provider, new StreamPrinter(System.out),
            buildLineReader(), registry, executor, engine, agentConfig);
        repl.run();
    }

    private static org.jline.reader.LineReader buildLineReader() throws java.io.IOException {
        org.jline.terminal.Terminal terminal =
            org.jline.terminal.TerminalBuilder.builder().system(true).build();
        return org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
    }

    private static Path locateConfig(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) return Paths.get(args[i + 1]);
        }
        Path local = Paths.get("maplecode.yaml");
        if (Files.exists(local)) return local;
        Path home = Paths.get(System.getProperty("user.home"), ".maplecode", "config.yaml");
        if (Files.exists(home)) return home;
        return null;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: 编译通过

- [ ] **Step 3: 跑全测**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(permission): App 装配 PermissionEngine"
```

---

## Task 22: 全测 + 集成 smoke

**Files:** (无)

- [ ] **Step 1: 跑全测**

Run: `mvn -q test`
Expected: 全绿，包含：

- 现有 14 个工具/执行器测试零改动通过
- 10 个新 permission 测试（BlacklistCheck / SandboxCheck / RuleTest / PermissionFileLoader / RuleCheck / ModeCheck / IoInterfaceSmoke / HitlCheck / PermissionContext / PermissionEngine / Decision / ToolCall / PermissionRequest / PermissionCheck / PermissionMode）
- ToolExecutorPermissionTest / ConfigLoaderPermissionModeTest

> 若有失败：检查是哪个测试失败，按失败信息回溯到对应任务修复。

- [ ] **Step 2: 跑 package**

Run: `mvn -q package`
Expected: 产出 `target/maple-code-java-0.1.0.jar`

- [ ] **Step 3: 集成 smoke（手工）**

启动 REPL（需要有效 API key）：

```bash
java -jar target/maple-code-java-0.1.0.jar
```

按场景验证（不需要实际 API key 也能验证权限层——只要让模型发起调用）：

1. **黑名单**——提示模型 `exec "rm -rf /tmp/foo"`，观察 ToolResult.error 含 "blocked by built-in blacklist"
2. **路径沙箱**——`read_file /etc/passwd` 应被沙箱 deny
3. **HITL**——删 `~/.maplecode/permissions.yaml` 后 `exec ls`，应弹 4 选 1
4. **HITL 选 3**——检查 `<cwd>/.maplecode/permissions.local.yaml` 追加了 rule
5. **重启复用**——重启后再 `exec ls`，应直接放行（不再弹 prompt）
6. **/mode**——`/mode permissive` 后任何调用放行；`/mode` 显示当前 mode

- [ ] **Step 4: 最终提交（如果上面有遗漏改动）**

```bash
git status  # 看是否有 uncommitted 改动
# 如有：
git add <改动文件>
git commit -m "feat(permission): 集成 smoke 修正"
```

---

## 验收清单总览

- [ ] T1–T22 全部完成，每步 commit 落地
- [ ] `mvn -q test` 全绿
- [ ] `mvn -q package` 产出 jar
- [ ] 集成 smoke 六场景全部通过
- [ ] 设计文档 `docs/superpowers/specs/2026-07-06-maple-code-permission-system-design.md` 与实现一致（无偏离）
- [ ] pom.xml 无新依赖

## 已知边界（不在本阶段范围）

- 网络请求限制（不在 spec 内）
- 资源配额（不在 spec 内）
- 审计日志（不在 spec 内）
- 规则 UI 编辑器（不在 spec 内）
- session 规则序列化到磁盘（spec 明确不做）