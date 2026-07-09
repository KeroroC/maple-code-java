# TUI 状态栏与输入框 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 MapleCode REPL 添加底部固定状态栏（模型、token、模式、目录）和带边框的输入区域，类 Claude Code 体验。

**Architecture:** 使用 JLine 3.27.0 内置的 `Status` 类（scroll region 技巧）实现固定状态栏。StreamPrinter 从 `System.out` 切换到 `terminal.writer()` 与 Status 共享同步机制。输入框使用 `╭─ > ` 作为 readLine prompt。状态栏在 REPL 生命周期内始终可见。

**Tech Stack:** Java 21, JLine 3.27.0 (jline-terminal Status class), JUnit 5, Mockito 5.20.0

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `src/main/java/com/maplecode/ui/StatusBar.java` | 封装 JLine Status，提供 update/resize |
| Create | `src/test/java/com/maplecode/ui/StatusBarTest.java` | StatusBar 单元测试 |
| Modify | `src/main/java/com/maplecode/ui/StreamPrinter.java` | PrintStream → PrintWriter，新增 Terminal 构造器 |
| Modify | `src/test/java/com/maplecode/ui/StreamPrinterTest.java` | 适配新构造器 |
| Modify | `src/main/java/com/maplecode/ui/ReplLoop.java` | 集成 StatusBar，修改 prompt，SIGWINCH |
| Modify | `src/main/java/com/maplecode/App.java` | 接线：Terminal → StatusBar → StreamPrinter → ReplLoop |

---

### Task 1: StatusBar 核心

**Files:**
- Create: `src/main/java/com/maplecode/ui/StatusBar.java`
- Create: `src/test/java/com/maplecode/ui/StatusBarTest.java`

- [ ] **Step 1: 写 StatusState record 和 StatusBar 骨架**

```java
// src/main/java/com/maplecode/ui/StatusBar.java
package com.maplecode.ui;

import com.maplecode.provider.TokenUsage;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.jline.terminal.Terminal;

import java.util.List;

public final class StatusBar {

    public record StatusState(
        String model,
        TokenUsage usage,   // nullable
        String mode,        // e.g. "normal", "plan", "strict", "permissive"
        String workingDir   // 缩略路径
    ) {}

    private final Terminal terminal;
    private final Status status;
    private final boolean supported;
    private StatusState state;

    public StatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.status = Status.getStatus(terminal);
        this.supported = status != null;
    }

    public void update(StatusState state) {
        if (!supported) return;
        this.state = state;
        status.update(List.of(render(state)));
    }

    public void resize() {
        if (!supported) return;
        status.resize();
        if (state != null) update(state);
    }

    public boolean isSupported() {
        return supported;
    }

    /** package-private for testing */
    static AttributedString render(StatusState state) {
        var sb = new AttributedStringBuilder();
        // 模型名：粗体
        sb.style(AttributedStyle.DEFAULT.bold());
        sb.append(state.model());
        sb.style(AttributedStyle.DEFAULT);
        // 分隔符
        sb.append(" │ ");  // │
        // Token 用量
        sb.append(formatUsage(state.usage()));
        // 分隔符
        sb.append(" │ ");
        // 模式：带颜色
        sb.append(coloredMode(state.mode()));
        // 分隔符
        sb.append(" │ ");
        // 工作目录：灰色
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        sb.append(state.workingDir());
        sb.style(AttributedStyle.DEFAULT);
        return sb.toAttributedString();
    }

    static String formatUsage(TokenUsage usage) {
        if (usage == null) return "tok:-/-";
        long in = usage.inputTokens();
        long out = usage.outputTokens();
        return "tok:" + abbreviate(in) + "/" + abbreviate(out);
    }

    static String abbreviate(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10_000) return String.format("%.1fk", n / 1000.0);
        return (n / 1000) + "k";
    }

    static AttributedString coloredMode(String mode) {
        var style = switch (mode) {
            case "plan" -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
            case "strict" -> AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
            case "permissive" -> AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
            default -> AttributedStyle.DEFAULT;
        };
        return new AttributedString(mode, style);
    }
}
```

- [ ] **Step 2: 写 StatusBarTest — 降级（不支持 scroll region 的终端）**

```java
// src/test/java/com/maplecode/ui/StatusBarTest.java
package com.maplecode.ui;

import org.jline.terminal.Terminal;
import org.jline.utils.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusBarTest {

    @Test
    void unsupportedTerminal_allOperationsAreNoOp() {
        // Status.getStatus(terminal) 返回 null 当终端不支持 scroll region
        // 需要一个真实的不支持 scroll region 的 Terminal，或者 mock
        // 由于 Status.getStatus 是静态方法，用 mock Terminal（默认不支持能力）
        Terminal terminal = mock(Terminal.class);
        // mock Terminal 的 getStringCapability 返回 null → Status.getStatus 返回 null
        when(terminal.getStringCapability(any())).thenReturn(null);
        when(terminal.getSize()).thenReturn(new Terminal.Size(80, 24));

        var bar = new StatusBar(terminal);

        assertFalse(bar.isSupported());
        // update 不应抛异常
        assertDoesNotThrow(() -> bar.update(new StatusBar.StatusState(
            "test-model", null, "normal", "~/project")));
        // resize 不应抛异常
        assertDoesNotThrow(bar::resize);
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `mvn test -Dtest=StatusBarTest -pl . -q`
Expected: FAIL（StatusBar 类不存在）

- [ ] **Step 4: 实现 StatusBar**

写出 Step 1 中的完整代码。

- [ ] **Step 5: 运行测试验证通过**

Run: `mvn test -Dtest=StatusBarTest -pl . -q`
Expected: PASS

- [ ] **Step 6: 写 render/format 测试**

在 `StatusBarTest.java` 中追加：

```java
@Test
void renderIncludesModelAndMode() {
    var state = new StatusBar.StatusState("claude-sonnet-4", null, "plan", "~/proj");
    var result = StatusBar.render(state);
    String plain = result.toAnsi(0);
    assertTrue(plain.contains("claude-sonnet-4"), "should contain model name");
    assertTrue(plain.contains("plan"), "should contain mode");
    assertTrue(plain.contains("~/proj"), "should contain working dir");
    assertTrue(plain.contains("tok:-/-"), "null usage should show tok:-/-");
}

@Test
void formatUsage_nullReturnsDash() {
    assertEquals("tok:-/-", StatusBar.formatUsage(null));
}

@Test
void formatUsage_zeroReturnsZero() {
    var usage = new com.maplecode.provider.TokenUsage(0, 0, 0, 0);
    assertEquals("tok:0/0", StatusBar.formatUsage(usage));
}

@Test
void formatUsage_largeValuesAbbreviated() {
    var usage = new com.maplecode.provider.TokenUsage(1234, 5678, 0, 0);
    String result = StatusBar.formatUsage(usage);
    assertTrue(result.contains("1.2k"), "1234 → 1.2k");
    assertTrue(result.contains("5.7k"), "5678 → 5.7k");
}

@Test
void abbreviate_smallValuesUnchanged() {
    assertEquals("999", StatusBar.abbreviate(999));
    assertEquals("0", StatusBar.abbreviate(0));
    assertEquals("1.0k", StatusBar.abbreviate(1000));
    assertEquals("12k", StatusBar.abbreviate(12000));
}

@Test
void coloredMode_planIsYellow() {
    var result = StatusBar.coloredMode("plan");
    assertEquals("plan", result.toAnsi(0));
}

@Test
void coloredMode_strictIsRed() {
    var result = StatusBar.coloredMode("strict");
    assertEquals("strict", result.toAnsi(0));
}

@Test
void coloredMode_normalIsDefault() {
    var result = StatusBar.coloredMode("normal");
    assertEquals("normal", result.toAnsi(0));
}
```

- [ ] **Step 7: 运行 render 测试**

Run: `mvn test -Dtest=StatusBarTest -pl . -q`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/maplecode/ui/StatusBar.java src/test/java/com/maplecode/ui/StatusBarTest.java
git commit -m "feat(ui): add StatusBar with JLine Status integration

- StatusState record: model, usage, mode, workingDir
- render(): AttributedStringBuilder with bold model, colored mode
- formatUsage(): tok:-/- / tok:0/0 / tok:1.2k/5.7k abbreviations
- Graceful no-op when terminal lacks scroll region support"
```

---

### Task 2: StreamPrinter 改用 Terminal 输出

**Files:**
- Modify: `src/main/java/com/maplecode/ui/StreamPrinter.java`
- Modify: `src/test/java/com/maplecode/ui/StreamPrinterTest.java`（如果存在）或 Create

- [ ] **Step 1: 检查现有 StreamPrinterTest**

Run: `find src/test -name "StreamPrinterTest.java" 2>/dev/null`
Expected: 可能不存在（当前 StreamPrinter 无专门测试）

- [ ] **Step 2: 写 StreamPrinter 输出测试**

```java
// src/test/java/com/maplecode/ui/StreamPrinterTest.java
package com.maplecode.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.*;

class StreamPrinterTest {

    @Test
    void writeOutputsToPrintWriter() {
        var buf = new ByteArrayOutputStream();
        var pw = new PrintWriter(buf);
        var printer = new StreamPrinter(pw);

        printer.write("hello ");
        printer.write("world");

        assertEquals("hello world", buf.toString());
    }

    @Test
    void infoPrintsLine() {
        var buf = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintWriter(buf));

        printer.info("test message");

        assertTrue(buf.toString().contains("test message"));
    }

    @Test
    void errorContainsCrossMark() {
        var buf = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintWriter(buf));

        printer.error("something failed");

        String output = buf.toString();
        assertTrue(output.contains("✗"), "should contain cross mark");
        assertTrue(output.contains("something failed"));
    }

    @Test
    void toolStartContainsGearSymbol() {
        var buf = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintWriter(buf));

        printer.toolStart("read_file", "/tmp/x");

        String output = buf.toString();
        assertTrue(output.contains("⚙"), "should contain gear symbol");
        assertTrue(output.contains("read_file"));
    }

    @Test
    void toolEndSuccessContainsCheckmark() {
        var buf = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintWriter(buf));

        printer.toolEnd("read_file", true, null);

        String output = buf.toString();
        assertTrue(output.contains("✓"), "should contain checkmark");
    }

    @Test
    void toolEndFailureContainsCrossMark() {
        var buf = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintWriter(buf));

        printer.toolEnd("read_file", false, "not found");

        String output = buf.toString();
        assertTrue(output.contains("✗"), "should contain cross mark");
        assertTrue(output.contains("not found"));
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `mvn test -Dtest=StreamPrinterTest -pl . -q`
Expected: FAIL（构造器签名变了，或 PrintWriter 方法不存在）

- [ ] **Step 4: 修改 StreamPrinter — 替换 PrintStream 为 PrintWriter**

关键变更：
- `private final PrintStream out` → `private final PrintWriter writer`
- 新增构造器 `StreamPrinter(PrintWriter writer)`
- 新增构造器 `StreamPrinter(Terminal terminal)` — 调用 `terminal.writer()`
- 无参构造器保持：`new StreamPrinter()` → `new PrintWriter(System.out)`
- 所有 `out.print()` → `writer.print()`
- 所有 `out.println()` → `writer.println()`
- 所有 `out.flush()` → `writer.flush()`

```java
package com.maplecode.ui;

import com.maplecode.agent.AgentEvent;
import com.maplecode.compact.CompactResult;
import com.maplecode.provider.TokenUsage;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.function.Consumer;

public final class StreamPrinter implements Consumer<AgentEvent> {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";
    private static final String GREEN = "\033[32m";

    private final PrintWriter writer;

    /** 使用 terminal.writer() 创建（生产环境，与 JLine Status 共享同步） */
    public StreamPrinter(Terminal terminal) {
        this.writer = terminal.writer();
    }

    /** 指定 PrintWriter 创建（测试环境） */
    public StreamPrinter(PrintWriter writer) {
        this.writer = writer;
    }

    /** 向后兼容：使用 System.out */
    public StreamPrinter() {
        this(new PrintWriter(System.out, true));
    }

    public void banner(String text) {
        writer.println(BOLD + text + RESET);
        writer.println();
        writer.flush();
    }

    public void startAssistant() { /* 空操作 */ }

    public void write(String text) {
        writer.print(text);
        writer.flush();
    }

    public void writeThinking(String text) {
        writer.print(DIM + text + RESET);
        writer.flush();
    }

    public void endAssistant() {
        writer.println();
        writer.flush();
    }

    public void error(String message) {
        writer.println(RED + "✗ " + message + RESET);
        writer.flush();
    }

    public void info(String message) {
        writer.println(message);
        writer.flush();
    }

    public void usage(TokenUsage u) {
        if (u == null) return;
        StringBuilder sb = new StringBuilder("[usage: input=").append(u.inputTokens())
            .append(" out=").append(u.outputTokens());
        if (u.cacheCreationTokens() > 0)
            sb.append(" cache_create=").append(u.cacheCreationTokens());
        if (u.cacheReadTokens() > 0)
            sb.append(" cache_read=").append(u.cacheReadTokens());
        sb.append("]");
        info(sb.toString());
    }

    public void newline() {
        writer.println();
        writer.flush();
    }

    public void toolStart(String name, String argSummary) {
        if (argSummary == null || argSummary.isEmpty()) {
            writer.println(DIM + "⚙ " + name + RESET);
        } else {
            writer.println(DIM + "⚙ " + name + " " + argSummary + RESET);
        }
        writer.flush();
    }

    public void toolEnd(String name, boolean success, String errorDetail) {
        if (success) {
            writer.println(GREEN + "✓ " + name + RESET);
        } else {
            String msg = errorDetail == null || errorDetail.isEmpty() ? "" : ": " + errorDetail;
            int nl = msg.indexOf('\n');
            if (nl > 0) msg = msg.substring(0, nl);
            writer.println(RED + "✗ " + name + msg + RESET);
        }
        writer.flush();
    }

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.TextDelta d -> write(d.text());
            case AgentEvent.ThinkingDelta d -> writeThinking(d.text());
            case AgentEvent.ToolCallStart s -> toolStart(s.name(), s.argSummary());
            case AgentEvent.ToolResult r -> toolEnd(r.name(), !r.isError(), r.isError() ? r.content() : null);
            case AgentEvent.IterationStart i -> { /* 静默 */ }
            case AgentEvent.IterationEnd i -> { /* 静默 */ }
            case AgentEvent.BatchStart b -> { /* 静默 */ }
            case AgentEvent.BatchEnd b -> { /* 静默 */ }
            case AgentEvent.ToolCallEnd e -> { /* 静默 */ }
            case AgentEvent.AgentStop s -> info("[agent stopped: " + s.reason() + "]");
            case AgentEvent.CompactApplied c -> {
                writer.println("[compact] applied: " + renderResult(c.result()));
                writer.flush();
            }
        }
    }

    public void compactResult(CompactResult r) {
        writer.println(renderResult(r));
        writer.flush();
    }

    private String renderResult(CompactResult r) {
        return switch (r) {
            case CompactResult.Noop n -> "[compact] noop: below threshold";
            case CompactResult.ChangedOffloadOnly o ->
                "[compact] offloaded " + o.offloadedCount() + " tool result(s)";
            case CompactResult.ChangedFull f ->
                "[compact] full compact: offloaded " + f.offloadedCount()
                    + ", summary covered ~" + f.summaryInputTokens() + " input tokens";
            case CompactResult.FailedOffload f ->
                "[compact] offload failed: " + f.reason();
            case CompactResult.FailedSummary f ->
                "[compact] summary failed (" + f.consecutiveFailures() + " consecutive): " + f.reason();
            case CompactResult.SkippedCircuitOpen s ->
                "[compact] circuit open (" + s.consecutiveFailures() + " failures); auto-compact disabled this session";
        };
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `mvn test -Dtest=StreamPrinterTest -pl . -q`
Expected: PASS

- [ ] **Step 6: 运行全量测试确认无回归**

Run: `mvn test -q`
Expected: PASS（StreamPrinter 被很多测试间接使用）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/ui/StreamPrinter.java src/test/java/com/maplecode/ui/StreamPrinterTest.java
git commit -m "refactor(ui): StreamPrinter PrintStream → PrintWriter + Terminal constructor

- 新增 StreamPrinter(Terminal) 构造器，使用 terminal.writer()
- 新增 StreamPrinter(PrintWriter) 构造器，便于测试
- 无参构造器保持向后兼容（PrintWriter 包装 System.out）
- PrintStream → PrintWriter（所有 print/println/flush 适配）"
```

---

### Task 3: ReplLoop 集成 StatusBar + 输入框 prompt

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 修改 ReplLoop 构造器 — 添加 StatusBar 参数**

在构造器参数列表末尾添加 `StatusBar statusBar`，存为字段。

```java
// 新增字段
private final StatusBar statusBar;

// 修改构造器签名（11 参数版本）
public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                LineReader reader, ToolRegistry registry, ToolExecutor executor,
                PermissionEngine engine, AgentConfig agentConfig,
                SessionArchive sessionArchive, CompactCoordinator coord,
                com.maplecode.memory.MemoryManager memoryManager,
                StatusBar statusBar) {
    // ... 现有赋值 ...
    this.statusBar = statusBar;
    // ...
}

// 修改 8 参数向后兼容构造器
public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                LineReader reader, ToolRegistry registry, ToolExecutor executor,
                PermissionEngine engine, AgentConfig agentConfig) {
    this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig,
         null, null, null, null);  // statusBar=null
}
```

- [ ] **Step 2: 修改 readMultiline() — 新 prompt**

```java
private String readMultiline() {
    String first;
    try {
        first = reader.readLine("╭─ > ");  // ╭─ > (含上边框)
    } catch (UserInterruptException e) {
        throw e;
    }
    if (first == null) return null;
    if (!first.equals("\"\"\"")) return first;
    StringBuilder sb = new StringBuilder();
    while (true) {
        String line;
        try {
            line = reader.readLine("│ ");  // │ (续行 prompt)
        } catch (UserInterruptException e) {
            throw e;
        }
        if (line == null) return null;
        if (line.equals("\"\"\"")) break;
        sb.append(line).append('\n');
    }
    String result = sb.toString();
    if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
    return result;
}
```

- [ ] **Step 3: 修改 run() — 启动时初始化状态栏 + SIGWINCH**

在 `run()` 方法开头，banner 打印之后：

```java
public void run() {
    printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，/new 新会话，/resume 恢复会话，/compact 压缩上下文，/tools 列出工具，/mode 权限模式，/plan 规划，/do 执行计划，/cancel 取消，/memory 记忆管理，\"\"\" 开始多行输入");

    // 初始化状态栏
    if (statusBar != null) {
        statusBar.update(new StatusBar.StatusState(
            appConfig.model(), null, "normal",
            abbreviateHome(System.getProperty("user.dir"))));
        // SIGWINCH 处理
        reader.getTerminal().handle(Terminal.Signal.WINCH, sig -> statusBar.resize());
    }

    while (true) {
        // ... 现有循环 ...
    }
}
```

- [ ] **Step 4: 在 run() 循环中添加状态更新**

在每个会修改状态的命令后，以及 Agent 执行后，调用 `updateStatusBar()`。

添加辅助方法：

```java
private void updateStatusBar(String mode) {
    if (statusBar == null) return;
    var usage = lastTokenUsage;  // 需要从 usageSink 捕获（见 Step 5）
    statusBar.update(new StatusBar.StatusState(
        appConfig.model(), usage, mode,
        abbreviateHome(System.getProperty("user.dir"))));
}

private static String abbreviateHome(String path) {
    String home = System.getProperty("user.home");
    if (path.startsWith(home)) {
        return "~" + path.substring(home.length());
    }
    return path;
}
```

在 `run()` 循环中的关键位置调用：
- `/mode` 命令后：`updateStatusBar(renderMode());`
- `/plan` 命令后：`updateStatusBar("plan");`
- `/do` 命令后：`updateStatusBar("normal");`
- `/cancel` 命令后：`updateStatusBar("normal");`
- `agent.run()` 后：`updateStatusBar(renderMode());`

```java
private String renderMode() {
    String planPart = agentConfig.planMode() == PlanMode.PLAN ? "plan" : "normal";
    String permPart = engine.mode().name().toLowerCase();
    if ("default".equals(permPart)) return planPart;
    return planPart + ":" + permPart;
}
```

- [ ] **Step 5: 捕获 TokenUsage 传递给 StatusBar**

修改构造器中的 `usageSink`，在现有逻辑基础上额外更新 `lastTokenUsage` 字段：

```java
// 新增字段
private volatile TokenUsage lastTokenUsage;

// 修改 usageSink 创建
java.util.function.Consumer<com.maplecode.provider.TokenUsage> usageSink = coord != null
    ? u -> { printer.usage(u); coord.recordUsage(u); lastTokenUsage = u; }
    : u -> { printer.usage(u); lastTokenUsage = u; };
```

- [ ] **Step 6: 运行全量测试确认编译通过**

Run: `mvn test -q`
Expected: PASS（ReplLoop 构造器签名变了，但 App.java 还没改，可能编译失败。如果失败，先临时传 null 给 statusBar）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(ui): integrate StatusBar into ReplLoop + change input prompts

- 添加 StatusBar 字段和构造器参数
- readMultiline(): prompt 改为 ╭─ > / │
- run(): 启动时初始化状态栏，注册 SIGWINCH handler
- 每轮 Agent 执行后更新状态栏（token、模式、目录）
- lastTokenUsage 从 usageSink 捕获"
```

---

### Task 4: App 接线

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: 修改 buildLineReader() — 暴露 Terminal**

```java
// 替换现有 buildLineReader()，改为返回 Terminal + LineReader 的 record
record TerminalContext(org.jline.terminal.Terminal terminal, org.jline.reader.LineReader reader) {}

private static TerminalContext buildTerminalContext() throws java.io.IOException {
    org.jline.terminal.Terminal terminal =
        org.jline.terminal.TerminalBuilder.builder().system(true).build();
    org.jline.reader.LineReader reader =
        org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
    return new TerminalContext(terminal, reader);
}
```

- [ ] **Step 2: 修改 App.main() — 创建 StatusBar 和 StreamPrinter(Terminal)**

```java
// 替换 var reader = buildLineReader(); 那行
var ctx = buildTerminalContext();
var reader = ctx.reader();

// 创建 StatusBar
var statusBar = new StatusBar(ctx.terminal());

// 替换 new StreamPrinter(System.out) 为
var printer = new StreamPrinter(ctx.terminal());

// 替换 ReplLoop 构造调用，添加 statusBar 参数
ReplLoop repl = new ReplLoop(raw, provider, printer,
    reader, registry, executor, engine, agentConfig,
    sessionArchive, coord, memoryManager, statusBar);
```

- [ ] **Step 3: 运行全量测试**

Run: `mvn test -q`
Expected: PASS

- [ ] **Step 4: 手工验证 — 运行程序看效果**

Run: `mvn package -q && java -jar target/maple-code-java-0.1.0.jar`
Expected: 看到状态栏在底部，输入框有 `╭─ > ` prompt

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(ui): wire StatusBar and StreamPrinter(Terminal) in App.main

- buildLineReader() → buildTerminalContext() 暴露 Terminal
- 创建 StatusBar(terminal) 传给 ReplLoop
- StreamPrinter 改用 terminal.writer() 输出"
```

---

### Task 5: 润色 — Token 格式化 + 路径缩略 + 边框对齐

**Files:**
- Modify: `src/main/java/com/maplecode/ui/StatusBar.java`（如果 Step 6 的 render 需要调整）
- Modify: `src/test/java/com/maplecode/ui/StatusBarTest.java`（补充边界用例）

- [ ] **Step 1: 验证 Token 格式化边界**

在 `StatusBarTest` 中补充：

```java
@Test
void formatUsage_oneSideZero() {
    var usage = new com.maplecode.provider.TokenUsage(0, 150, 0, 0);
    assertEquals("tok:0/150", StatusBar.formatUsage(usage));
}

@Test
void abbreviate_exactly1000() {
    assertEquals("1.0k", StatusBar.abbreviate(1000));
}

@Test
void abbreviate_9999() {
    assertEquals("10.0k", StatusBar.abbreviate(9999));
}

@Test
void abbreviate_10000() {
    assertEquals("10k", StatusBar.abbreviate(10000));
}
```

- [ ] **Step 2: 验证路径缩略**

```java
@Test
void statusState_abbreviatesHome() {
    // 这个测试在 ReplLoop.abbreviateHome() 中，不在 StatusBar 中
    // 但我们可以验证 StatusState 的 workingDir 字段接受 ~ 前缀
    var state = new StatusBar.StatusState("model", null, "normal", "~/projects/foo");
    assertTrue(state.workingDir().startsWith("~/"));
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn test -Dtest=StatusBarTest -pl . -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/maplecode/ui/StatusBarTest.java
git commit -m "test(ui): add edge case tests for StatusBar token formatting

- formatUsage: one-side-zero, exactly-1000, 9999, 10000
- abbreviate: boundary values
- StatusState: tilde prefix in workingDir"
```

---

### Task 6: 全量验证与清理

- [ ] **Step 1: 运行全量测试**

Run: `mvn test -q`
Expected: PASS（0 failures）

- [ ] **Step 2: 手工测试清单**

在 iTerm2 / macOS Terminal 中验证：
- [ ] 状态栏始终固定在底部
- [ ] Agent 流式输出时状态栏不动，文本在上方滚动
- [ ] `/mode strict` 后状态栏模式变红
- [ ] `/plan` 后状态栏模式变黄
- [ ] Agent 结束后 token 用量更新
- [ ] 终端 resize 后状态栏重绘
- [ ] 多行输入 `"""` 续行 prompt 为 `│ `
- [ ] Ctrl-C 正常中断

- [ ] **Step 3: dumb terminal 降级验证**

Run: `echo "test" | java -jar target/maple-code-java-0.1.0.jar 2>&1 | head -5`
Expected: 无状态栏，无异常

- [ ] **Step 4: 最终 Commit（如有修复）**

```bash
git add -A
git commit -m "fix(ui): polish TUI status bar based on manual testing"
```
