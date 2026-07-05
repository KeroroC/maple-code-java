# MapleCode System Prompt 结构化与缓存（阶段四）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把三行默认 `systemPrompt` 升级为按职责拆分的模块化结构：稳定部分走 Anthropic prompt cache 通道，动态部分（环境信息、运行时 Plan Mode 提醒）走消息通道；引入 `<system-reminder>` 包裹的 transient user 消息作为运行中补充指令，按 N=5 节流注入到 Plan Mode 上下文。

**Architecture:** 新建 `com.maplecode.prompt` 包：`SystemBlock` record、`PromptSection` 接口、`SectionContext` / `DynamicContext` record、`DefaultSections` 常量 registry（7 个固定 section + 自定义 + Skill/记忆占位）、`PromptAssembler`（assemble 拼出 List<SystemBlock> 并在最后一个 cacheable section 上标 cacheBoundary；attachReminder 把 `<system-reminder>` 包裹的 user 消息 append 到 messages 末尾 transient）、`PlanModeReminder`（State 状态机 + renderFull/Brief）、`ReminderMessage` 工具类。`ChatRequest` 接口从 `systemPrompt: String` 切到 `systemBlocks: List<SystemBlock>` + 新增 `transientReminder: List<ContentBlock>`；`AgentConfig` 加 `reminderState` 字段并显式声明 `withReminderState` 方法。`TokenUsage` 加 `cacheCreationTokens` / `cacheReadTokens`，保留 `of(int, int)` 工厂。两个 RequestMapper 各按 spec 输出；AnthropicStreamParser 在 `message_start` 里读 `cache_creation_input_tokens` 与 `cache_read_input_tokens`。`StreamPrinter` 加 `usage(TokenUsage)`。`ResponseCollector` 多吃一个 `Consumer<TokenUsage> usageSink`；`AgentLoop` 构造多吃 `usageSink`、run 内做 reminder 注入。`ReplLoop` 的 `/plan` / `/do` / `/cancel` 复制 `reminderState`；`App.main` 启动期 `DynamicContext.capture` + `DefaultSections.standard(...)` + `PromptAssembler.assemble(...)` 生成最终 `systemBlocks`。

**Tech Stack:** Java 21、Maven、Jackson 2.17.2（已有）、JUnit 5 + Mockito（已有）。无新依赖。

**Spec:** `docs/superpowers/specs/2026-07-05-maple-code-system-prompt-design.md`

**YAGNI 决策**：
- `AgentCancelledException` 之前已声明占位，本阶段不在 AgentLoop 抛，保持协作式 cancel。
- `DefaultSections.standard(...)` 内 ActivatedSkillsSection / LongTermMemorySection 是 v5 占位，本期 `enabled()` 返回 false（不挂进 sections 列表）。
- Section 是常量（`DefaultSections.IDENTITY` 等），不是为每个 section 单独建一个 final class 文件 —— 见 Task 4。

**任务依赖与顺序**

```
T1  TokenUsage 扩展（4 字段 + .of 重载）
T2  foundation：SystemBlock / PromptSection / SectionContext / ReminderMessage
T3  DynamicContext（capture + detectMavenVersion）
T4  DefaultSections（7 固定 + Custom + placeholders）
T5  PlanModeReminder（State + decide + render*）
T6  ChatRequest 改 systemBlocks + transientReminder；ChatSession.toRequest 同步
T7  PromptAssembler（assemble + attachReminder）—— 依赖 T6
T8  AgentConfig 加 reminderState + withReminderState —— 依赖 T5/T6
T9  AppConfig.yamlPrompt 字段 + ConfigLoader 简化
T10 AnthropicStreamParser 解析 cache_*.tokens；OpenAiStreamParser 用 .of；StreamPrinter.usage
T11 AnthropicRequestMapper 改 multi-block + cache_control；OpenAiRequestMapper 拼接第一条 system —— 依赖 T6
T12 ResponseCollector 加 usageSink；AgentLoop 构造 / run 加 reminder 注入与 usage 推送 —— 依赖 T7/T8/T10
T13 ReplLoop /plan /do /cancel 携带 reminderState + 传 printer::usage 给 AgentLoop —— 依赖 T12
T14 App.main 启动期 env+blocks 组装 + ReplLoop 接受显式 AgentConfig —— 依赖 T13
T15 跑 mvn test 全绿 + 6 场景手动 smoke
```

依赖流向清晰：T1→所有；T2→T4/T7；T3→T4；T4→T14；T5→T8；T6→T7/T11/T12；T7→T12；T8→T12；T10→T12；T11→T12；T12→T13；T13→T14；T14→T15。每个任务独立可跑、单独 commit；每 commit 后 `mvn -q test` 应绿（T1-T5 可独立绿；T6 之后预期在 T12 全部绿；中间状态局部红是预期的「interface 破坏期」）。

---

## Task 1: TokenUsage 扩展

**Files:**
- Modify: `src/main/java/com/maplecode/provider/TokenUsage.java`
- Create: `src/test/java/com/maplecode/provider/TokenUsageTest.java`（覆盖四个字段）

- [ ] **Step 1: 写新测试覆盖四字段**

`src/test/java/com/maplecode/provider/TokenUsageTest.java`:

```java
package com.maplecode.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageTest {

    @Test
    void equalityFourFields() {
        assertEquals(
            new TokenUsage(10, 20, 0, 0),
            new TokenUsage(10, 20, 0, 0));
        assertNotEquals(
            new TokenUsage(10, 20, 0, 0),
            new TokenUsage(10, 20, 100, 0));
    }

    @Test
    void ofFactory() {
        var u = TokenUsage.of(50, 60);
        assertEquals(50, u.inputTokens());
        assertEquals(60, u.outputTokens());
        assertEquals(0, u.cacheCreationTokens());
        assertEquals(0, u.cacheReadTokens());
    }

    @Test
    void zeroUsage() {
        var u = new TokenUsage(0, 0, 0, 0);
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
    }
}
```

- [ ] **Step 2: 运行测试，验证编译失败**

Run: `mvn -q test -Dtest=TokenUsageTest`
Expected: 编译失败 —— `TokenUsage` 仍是 2 字段 record

- [ ] **Step 3: 升级 TokenUsage**

`src/main/java/com/maplecode/provider/TokenUsage.java`:

```java
package com.maplecode.provider;

/**
 * Provider 返回的 token 用量统计。Anthropic / OpenAI 两边共用。
 * <p>
 * cacheCreationTokens / cacheReadTokens 仅 Anthropic prompt cache 返回非零；
 * OpenAI 路径用 {@link #of(int, int)}，传 0/0。
 */
public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int cacheCreationTokens,
    int cacheReadTokens
) {
    /** 向后兼容工厂：仅设置 input/output，cache 字段置 0。 */
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, 0, 0);
    }
}
```

- [ ] **Step 4: 跑测试，验证通过**

Run: `mvn -q test -Dtest=TokenUsageTest`
Expected: 3 tests passed

> 这是**接口破坏性变更**：所有 2 参 `new TokenUsage(a, b)` 调用点必须改为 `TokenUsage.of(a, b)`。下游任务 T10 负责改 parser，T13/T14 负责改其它调用点（本任务范围内只需改 TokenUsage 本身）。

- [ ] **Step 5: 修其它调用点（同步但本任务范围内）**

文件: `src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`（在 T10 中详细改，本步先 grep 一遍所有 `new TokenUsage`：

Run: `grep -rn "new TokenUsage(" src/main/java`

预期命中：
- 旧 AnthropicStreamParser message_stop 处的 `new TokenUsage(...)` （T10 改）
- 旧 OpenAiStreamParser 处的 `new TokenUsage(...)` （T10 改）
- 旧 ChatRequest / AgentEvent.IterationEnd 相关不要替换（它们只是 carrier）

本任务**只**让 `TokenUsageTest` 绿，不动 parser。`grep` 命令应返回旧调用点至少 2 个，T10 会处理。

- [ ] **Step 6: 提交**

Run:
```bash
git add src/main/java/com/maplecode/provider/TokenUsage.java \
        src/test/java/com/maplecode/provider/TokenUsageTest.java
git commit -m "feat(prompt): TokenUsage 增加 cacheCreationTokens/cacheReadTokens 字段"
```

---

## Task 2: 基础类型（SystemBlock / PromptSection / SectionContext / ReminderMessage）

**Files:**
- Create: `src/main/java/com/maplecode/prompt/SystemBlock.java`
- Create: `src/main/java/com/maplecode/prompt/PromptSection.java`
- Create: `src/main/java/com/maplecode/prompt/SectionContext.java`
- Create: `src/main/java/com/maplecode/prompt/ReminderMessage.java`
- Create: `src/test/java/com/maplecode/prompt/ReminderMessageTest.java`

- [ ] **Step 1: 写 ReminderMessage 测试**

`src/test/java/com/maplecode/prompt/ReminderMessageTest.java`:

```java
package com.maplecode.prompt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReminderMessageTest {

    @Test
    void wrapContainsBodyAndTags() {
        String wrapped = ReminderMessage.wrap("规划模式仍激活");
        assertTrue(wrapped.startsWith("<system-reminder>\n"));
        assertTrue(wrapped.contains("规划模式仍激活"));
        assertTrue(wrapped.endsWith("\n</system-reminder>"));
    }

    @Test
    void wrapEmptyBody() {
        String wrapped = ReminderMessage.wrap("");
        assertEquals("<system-reminder>\n\n</system-reminder>", wrapped);
    }
}
```

- [ ] **Step 2: 创建 SystemBlock / PromptSection / SectionContext / ReminderMessage**

`src/main/java/com/maplecode/prompt/SystemBlock.java`:

```java
package com.maplecode.prompt;

/**
 * 单段系统提示块。Anthropic mapper 会把 List<SystemBlock> 序列化成 multi-block
 * system array；cacheBoundary=true 时附加 cache_control: ephemeral 标记。
 */
public record SystemBlock(String content, boolean cacheBoundary, String kind) {}
```

`src/main/java/com/maplecode/prompt/PromptSection.java`:

```java
package com.maplecode.prompt;

/** 单段 prompt 模块契约。Section 自己声明 cacheable；assembler 决定 cache 边界。 */
public interface PromptSection {
    String kind();
    String render(SectionContext ctx);
    default boolean cacheable() { return true; }
    default boolean enabled(SectionContext ctx) { return true; }
}
```

`src/main/java/com/maplecode/prompt/SectionContext.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import com.maplecode.tool.Tool;
import java.util.List;

/** 渲染时给 sections 用的环境：工具清单、动态 env、规划模式。 */
public record SectionContext(
    List<Tool> tools,
    DynamicContext env,
    PlanMode planMode
) {}
```

`src/main/java/com/maplecode/prompt/ReminderMessage.java`:

```java
package com.maplecode.prompt;

/** 运行时注入的 transient user 消息包装。Anthropic 训练时识别该 tag 不当用户输入。 */
public final class ReminderMessage {
    public static final String TAG_OPEN = "<system-reminder>";
    public static final String TAG_CLOSE = "</system-reminder>";

    private ReminderMessage() {}

    public static String wrap(String body) {
        return TAG_OPEN + "\n" + body + "\n" + TAG_CLOSE;
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=ReminderMessageTest`
Expected: 2 tests passed

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/prompt/ src/test/java/com/maplecode/prompt/
git commit -m "feat(prompt): SystemBlock/PromptSection/SectionContext/ReminderMessage"
```

注意：`SectionContext` 引用了 `com.maplecode.agent.PlanMode`（已存在）和 `com.maplecode.tool.Tool`（已存在），不需要新建。

---

## Task 3: DynamicContext 与 capture()

**Files:**
- Create: `src/main/java/com/maplecode/prompt/DynamicContext.java`
- Create: `src/test/java/com/maplecode/prompt/DynamicContextTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/prompt/DynamicContextTest.java`:

```java
package com.maplecode.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DynamicContextTest {

    @Test
    void captureDetectsNonGitDir(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("not-a-repo");
        Files.createDirectory(cwd);
        var ctx = DynamicContext.capture(cwd);
        assertEquals(cwd, ctx.cwd());
        assertFalse(ctx.isGitRepo());
        assertNotNull(ctx.platform());
        assertNotNull(ctx.javaVersion());
        assertNotNull(ctx.mavenVersion());
        assertNotNull(ctx.date());
        assertNotNull(ctx.time());
    }

    @Test
    void captureDetectsGitRepo(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("repo");
        Files.createDirectory(cwd);
        Files.createDirectory(cwd.resolve(".git"));
        var ctx = DynamicContext.capture(cwd);
        assertTrue(ctx.isGitRepo());
    }
}
```

- [ ] **Step 2: 实现 DynamicContext**

`src/main/java/com/maplecode/prompt/DynamicContext.java`:

```java
package com.maplecode.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 动态环境信息 —— EnvironmentSection 渲染时填充，由 App.main 启动期构建一次。
 * 全部 cacheable=false（每次启动都变）。detectMavenVersion 失败时返 "unknown"。
 */
public record DynamicContext(
    Path cwd,
    boolean isGitRepo,
    String platform,
    String javaVersion,
    String mavenVersion,
    LocalDate date,
    LocalTime time
) {
    public static DynamicContext capture(Path cwd) {
        boolean git = Files.exists(cwd.resolve(".git"));
        String os = System.getProperty("os.name")
            + " (" + System.getProperty("os.arch") + ")";
        String java = System.getProperty("java.version");
        String maven = detectMavenVersion();
        LocalDate date = LocalDate.now();
        LocalTime time = LocalTime.now().withNano(0);
        return new DynamicContext(cwd, git, os, java, maven, date, time);
    }

    /**
     * 启动期跑一次 `mvn -v` 抓版本。失败（命令不在 PATH、异常）回 "unknown"，
     * 不阻塞 App 启动。最多 ~1s 超时。
     */
    static String detectMavenVersion() {
        try {
            Process p = new ProcessBuilder("mvn", "-v")
                .redirectErrorStream(true)
                .start();
            boolean done = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "unknown"; }
            if (p.exitValue() != 0) return "unknown";
            String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            for (String line : out.split("\n")) {
                if (line.startsWith("Apache Maven")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) return parts[2];
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=DynamicContextTest`
Expected: 2 tests passed（注意 `mvn -v` 在沙盒里若无 mvn 会回 "unknown"；平台/jvm 一定有）

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/prompt/DynamicContext.java \
        src/test/java/com/maplecode/prompt/DynamicContextTest.java
git commit -m "feat(prompt): DynamicContext 与 capture 启动期探测"
```

---

## Task 4: DefaultSections（7 固定 + Custom + placeholders）

**Files:**
- Create: `src/main/java/com/maplecode/prompt/DefaultSections.java`
- Create: `src/test/java/com/maplecode/prompt/DefaultSectionsTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/prompt/DefaultSectionsTest.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSectionsTest {

    private static SectionContext ctx(PlanMode mode) {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "darwin (arm64)", "21.0.5", "3.9.6",
            LocalDate.of(2026, 7, 5), LocalTime.of(10, 0));
        return new SectionContext(List.of(), env, mode);
    }

    @Test
    void fixedSectionsExistAndProduceNonEmptyText() {
        var sections = DefaultSections.standard(
            new DynamicContext(Path.of("/tmp"), false,
                "x", "x", "x", LocalDate.now(), LocalTime.now()),
            List.of(), PlanMode.NORMAL, null);
        assertEquals(8, sections.size(),
            "应当恰好 8 个固定段：identity/constraints/task/action/tool/tone/text/env");
        for (var s : sections) {
            assertFalse(s.render(ctx(PlanMode.NORMAL)).isBlank(),
                "section " + s.kind() + " 输出空");
        }
    }

    @Test
    void environmentIsCacheableFalse() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null);
        var envSection = sections.get(7);
        assertEquals("environment", envSection.kind());
        assertFalse(envSection.cacheable(),
            "EnvironmentSection 必须是 cacheable=false");
    }

    @Test
    void taskModeVariesByPlanMode() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null);
        var taskMode = sections.get(2);
        assertEquals("task_mode", taskMode.kind());
        assertNotEquals(taskMode.render(ctx(PlanMode.NORMAL)),
                        taskMode.render(ctx(PlanMode.PLAN)));
    }

    @Test
    void customInstructionAppendedWhenProvided() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalTime.now());
        var nullCustom = DefaultSections.standard(env, List.of(),
            PlanMode.NORMAL, null);
        var withCustom = DefaultSections.standard(env, List.of(),
            PlanMode.NORMAL, "做且只做单元测试");
        assertEquals(8, nullCustom.size());
        assertEquals(9, withCustom.size());
        assertEquals("custom_instruction", withCustom.get(8).kind());
    }
}
```

- [ ] **Step 2: 实现 DefaultSections**

`src/main/java/com/maplecode/prompt/DefaultSections.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import com.maplecode.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置段 + 占位段的常量 registry。本期不含 ActivatedSkills / LongTermMemory 真实实现，
 * 故 enabled() 始终 false，standard() 不会把它们计入 sections 列表。
 */
public final class DefaultSections {

    private DefaultSections() {}

    // -------- 7 个固定 section（常量） --------

    public static final PromptSection IDENTITY = new Section("identity", """
            你是 MapleCode，一个运行在终端的 AI 编程助手。""", true, true);

    public static final PromptSection SYSTEM_CONSTRAINTS = new Section("constraints", """
            请遵循：
            - 不确定时，先读相关文件再行动，不要凭空猜测 API、路径或代码内容。
            - 仅使用已注册的工具；不要伪造工具结果。
            - 引用文件路径时优先使用工作目录相对的相对路径。""", true, true);

    public static final PromptSection TASK_MODE = new PlanModeAwareSection("task_mode");

    public static final PromptSection ACTION_EXECUTION = new Section("action_execution", """
            执行原则：
            - 多步任务先列出计划再按顺序执行；不要把所有步骤一口气说出。
            - 调用工具前先说明目的，调用后说明观察到的关键结果。
            - 工具返回错误时，先分析根因再决定是否重试。""", true, true);

    public static final PromptSection TOOL_USAGE = new ToolAwareSection("tool_usage");

    public static final PromptSection TONE_STYLE = new Section("tone_style", """
            风格：
            - 中文短句优先；标点用中文全角。
            - 技术名词保留英文（如 cache、token、schema）。
            - 段落用空行分隔。""", true, true);

    public static final PromptSection TEXT_OUTPUT = new Section("text_output", """
            输出格式：
            - 代码块包裹路径和命令。
            - 列表用 `- `，不要数字编号（除非按步骤）。
            - 不要把工具调用 JSON 完整回显；只说结论。""", true, true);

    public static final PromptSection ENVIRONMENT = new EnvSection();

    // -------- 标准装配顺序 --------

    public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                               PlanMode planMode, String customInstruction) {
        List<PromptSection> list = new ArrayList<>(List.of(
            IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
            TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT, ENVIRONMENT));
        if (customInstruction != null && !customInstruction.isBlank()) {
            list.add(new CustomInstructionSection(customInstruction));
        }
        // ActivatedSkillsSection / LongTermMemorySection 暂留作 v5 接口，本期不挂进列表。
        return list;
    }

    // -------- 具体类（package-private） --------

    /** 静态文本 section。enabled/cacheable 可定制（默认 true/true）。 */
    static final class Section implements PromptSection {
        private final String kind;
        private final String text;
        private final boolean cacheable;
        private final boolean enabled;
        Section(String kind, String text, boolean cacheable, boolean enabled) {
            this.kind = kind; this.text = text;
            this.cacheable = cacheable; this.enabled = enabled;
        }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) { return text; }
        @Override public boolean cacheable() { return cacheable; }
        @Override public boolean enabled(SectionContext ctx) { return enabled; }
    }

    /** TaskMode：根据 ctx.planMode() 切换文案。 */
    static final class PlanModeAwareSection implements PromptSection {
        private final String kind;
        PlanModeAwareSection(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) {
            return ctx.planMode() == PlanMode.PLAN
                ? "你处于规划模式，仅可使用 read_file / glob / grep。输出一份可执行计划。"
                : "你处于执行模式，可以读写文件、执行命令，完成用户的任务即可。";
        }
    }

    /** ToolUsage：渲染时把工具名清单注入正文。 */
    static final class ToolAwareSection implements PromptSection {
        private final String kind;
        ToolAwareSection(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) {
            StringBuilder toolNames = new StringBuilder();
            for (var t : ctx.tools()) {
                if (toolNames.length() > 0) toolNames.append(", ");
                toolNames.append(t.name());
            }
            return "工具使用约定：\n"
                 + "- 优先使用专用工具：读文件用 read_file，搜索用 glob/grep，"
                 + "写文件用 write_file，精确修改用 edit_file。\n"
                 + "- edit_file 之前必须先 read_file 目标文件，确认实际内容。\n"
                 + "- exec 跑长命令加 timeout，不要用 exec 模拟 ls/find/grep。\n"
                 + "- 可用工具：" + toolNames + "。\n"
                 + "- 工具返回错误时按错误信息调整调用。";
        }
    }

    /** EnvironmentSection：cacheable=false。 */
    static final class EnvSection implements PromptSection {
        @Override public String kind() { return "environment"; }
        @Override public String render(SectionContext ctx) {
            var e = ctx.env();
            return "## Environment\n"
                 + "- Working directory: " + e.cwd() + "\n"
                 + "- Git repo: " + (e.isGitRepo() ? "yes" : "no") + "\n"
                 + "- Platform: " + e.platform() + "\n"
                 + "- Runtime: Java " + e.javaVersion()
                 + ", Maven " + e.mavenVersion() + "\n"
                 + "- Date: " + e.date() + "\n"
                 + "- Time: " + e.time();
        }
        @Override public boolean cacheable() { return false; }
    }

    /** CustomInstructionSection：enabled 由 customInstruction 非空决定。 */
    static final class CustomInstructionSection implements PromptSection {
        private final String text;
        CustomInstructionSection(String text) { this.text = text; }
        @Override public String kind() { return "custom_instruction"; }
        @Override public String render(SectionContext ctx) { return text; }
        @Override public boolean cacheable() { return true; }
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=DefaultSectionsTest`
Expected: 4 tests passed

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/prompt/DefaultSections.java \
        src/test/java/com/maplecode/prompt/DefaultSectionsTest.java
git commit -m "feat(prompt): DefaultSections 含 7 固定段 + Custom 段"
```

---

## Task 5: PlanModeReminder（State + decide + render*）

**Files:**
- Create: `src/main/java/com/maplecode/prompt/PlanModeReminder.java`
- Create: `src/test/java/com/maplecode/prompt/PlanModeReminderTest.java`

- [ ] **Step 1: 写表格驱动测试**

`src/test/java/com/maplecode/prompt/PlanModeReminderTest.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanModeReminderTest {

    @Test
    void normalModeAlwaysNone() {
        for (int i = 0; i < 20; i++) {
            assertEquals(PlanModeReminder.Form.NONE,
                PlanModeReminder.decide(PlanMode.NORMAL,
                    PlanModeReminder.State.initial(), i));
        }
    }

    @Test
    void planModeFirstIterationFull() {
        assertEquals(PlanModeReminder.Form.FULL,
            PlanModeReminder.decide(PlanMode.PLAN,
                PlanModeReminder.State.initial(), 0));
    }

    @Test
    void planModeSubsequentBrief() {
        var s1 = PlanModeReminder.State.initial().afterFull(0);
        for (int i = 1; i <= 4; i++) {
            assertEquals(PlanModeReminder.Form.BRIEF,
                PlanModeReminder.decide(PlanMode.PLAN, s1, i),
                "iter=" + i);
        }
    }

    @Test
    void planModeEveryFiveFullRepeat() {
        var s = PlanModeReminder.State.initial().afterFull(0);   // 0 完整
        for (int i = 1; i < 5; i++) {
            PlanModeReminder.decide(PlanMode.PLAN, s, i);  // 1..4 brief
        }
        // 第 5 轮仍是 brief（到第 5 轮时距 lastFull=0 只有 5 步）
        assertEquals(PlanModeReminder.Form.BRIEF,
            PlanModeReminder.decide(PlanMode.PLAN, s, 5));
        // 第 6 轮：距 lastFull=0 是 6 步 ≥ 5 → FULL
        assertEquals(PlanModeReminder.Form.FULL,
            PlanModeReminder.decide(PlanMode.PLAN, s, 6));
    }

    @Test
    void renderFullNonBlankAndContainsKeywords() {
        String r = PlanModeReminder.renderFull();
        assertFalse(r.isBlank());
        assertTrue(r.contains("规划"));
        assertTrue(r.contains("write_file"));
        assertTrue(r.contains("read_file"));
    }

    @Test
    void renderBriefNonBlank() {
        assertFalse(PlanModeReminder.renderBrief().isBlank());
    }
}
```

- [ ] **Step 2: 实现 PlanModeReminder**

`src/main/java/com/maplecode/prompt/PlanModeReminder.java`:

```java
package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;

/**
 * Plan Mode 节奏控制器。State 跨轮迭代更新（每次 FULL 后调用 afterFull）。
 * REPEAT_INTERVAL = 5：第 1 轮完整，之后每 5 轮再完整，其余精简。
 */
public final class PlanModeReminder {

    public static final int REPEAT_INTERVAL = 5;

    public enum Form { FULL, BRIEF, NONE }

    public record State(int fullInserts, int lastFullIteration) {
        public static State initial() { return new State(0, 0); }
        public State afterFull(int iter) {
            return new State(fullInserts + 1, iter);
        }
    }

    private PlanModeReminder() {}

    public static Form decide(PlanMode mode, State state, int iteration) {
        if (mode != PlanMode.PLAN) return Form.NONE;
        if (state.fullInserts() == 0) return Form.FULL;
        if (iteration - state.lastFullIteration >= REPEAT_INTERVAL) return Form.FULL;
        return Form.BRIEF;
    }

    public static String renderFull() {
        return "规划模式已开启。禁止调用 write_file / edit_file / exec。\n"
             + "仅可使用 read_file / glob / grep。输出一份可执行计划，"
             + "列出每个步骤及对应工具调用，完成后停止。";
    }

    public static String renderBrief() {
        return "规划模式仍处于激活状态，仅可调用只读工具。";
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=PlanModeReminderTest`
Expected: 6 tests passed

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/prompt/PlanModeReminder.java \
        src/test/java/com/maplecode/prompt/PlanModeReminderTest.java
git commit -m "feat(prompt): PlanModeReminder 节流状态机"
```

---

## Task 6: ChatRequest 改造 + ChatSession.toRequest 同步

**Files:**
- Modify: `src/main/java/com/maplecode/provider/ChatRequest.java`
- Modify: `src/main/java/com/maplecode/session/ChatSession.java`

- [ ] **Step 1: 改 ChatRequest**

`src/main/java/com/maplecode/provider/ChatRequest.java`:

```java
package com.maplecode.provider;

import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    List<SystemBlock> systemBlocks,        // 替代旧 systemPrompt: String
    List<ChatMessage> messages,
    ThinkingConfig thinking,
    List<Tool> tools,
    List<com.maplecode.provider.ContentBlock> transientReminder   // 见 PromptAssembler
) {
    /** 向后兼容 5 参重载：transientReminder 默认为空 list。 */
    public ChatRequest(String model, List<SystemBlock> systemBlocks,
                       List<ChatMessage> messages, ThinkingConfig thinking,
                       List<Tool> tools) {
        this(model, systemBlocks, messages, thinking, tools, List.of());
    }
}
```

注意：`SystemBlock` 位于 `com.maplecode.prompt`，不在 `com.maplecode.provider` —— 这构成**新跨包依赖**：`provider → prompt`。`prompt` 包不依赖任何其他业务包（除 `agent.PlanMode` 和 `tool.Tool`，这两个原本 provider 也依赖）。安全性 OK。

- [ ] **Step 2: 改 ChatSession.toRequest 签名**

`src/main/java/com/maplecode/session/ChatSession.java` —— 在 `import` 区添加 `import com.maplecode.prompt.SystemBlock;`，并把 `toRequest` 两个重载的签名替换：

```java
public ChatRequest toRequest(String model, List<SystemBlock> systemBlocks,
                             ThinkingConfig thinking, List<Tool> tools) {
    return new ChatRequest(model, systemBlocks,
        Collections.unmodifiableList(new ArrayList<>(messages)),
        thinking, tools);
}

public ChatRequest toRequest(String model, List<SystemBlock> systemBlocks,
                             ThinkingConfig thinking) {
    return toRequest(model, systemBlocks, thinking, null);
}
```

去掉旧的 (String, String, ...) 形式。

- [ ] **Step 3: 跑全测看哪些测试挂**

Run: `mvn -q test`
Expected: 多处旧调用点 `session.toRequest("m", systemPrompt: String, ...)` 会编译失败。详细列表：

- `src/main/java/com/maplecode/agent/AgentLoop.java:81`（详见 T13 改）
- `src/test/java/com/maplecode/session/ChatSessionTest.java`（对应改）
- 其它可能

**不要试图在本任务范围内修所有调用点** —— AgentLoop 由 T12 修、SessionTest 由本步 Step 4 修。预期编译会挂若干 `.java`。

- [ ] **Step 4: 修 ChatSessionTest**

`src/test/java/com/maplecode/session/ChatSessionTest.java` —— 找到所有 `toRequest("m", "stringPrompt", ...)` 调用，改为：

```java
toRequest("m", List.of(new SystemBlock("old default", false, "legacy")), null)
```

（带 cacheBoundary=false 即可，不影响 toRequest 行为）

- [ ] **Step 5: 跑测试，至少 ChatSessionTest 应绿（其它仍挂）**

Run: `mvn -q test -Dtest=ChatSessionTest`
Expected: ChatSessionTest 全绿。其它测试可能因 AgentLoop 未改而编译失败 —— 这是预期的，T13 才修。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/maplecode/provider/ChatRequest.java \
        src/main/java/com/maplecode/session/ChatSession.java \
        src/test/java/com/maplecode/session/ChatSessionTest.java
git commit -m "refactor(prompt): ChatRequest 改用 List<SystemBlock> + transientReminder 字段"
```

---

## Task 7: PromptAssembler（assemble + attachReminder）

**前置**：依赖 T6（ChatRequest 6 参构造含 transientReminder）。先做 T6 再做本任务。

**Files:**
- Create: `src/main/java/com/maplecode/prompt/PromptAssembler.java`
- Create: `src/test/java/com/maplecode/prompt/PromptAssemblerTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/maplecode/prompt/PromptAssemblerTest.java`:

```java
package com.maplecode.prompt;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerTest {

    private static DynamicContext env() {
        return new DynamicContext(java.nio.file.Path.of("/tmp"), false,
            "x", "x", "x", java.time.LocalDate.now(), java.time.LocalTime.now());
    }

    private static PromptSection fixed(String kind, boolean cacheable) {
        return new PromptSection() {
            @Override public String kind() { return kind; }
            @Override public String render(SectionContext ctx) { return "T:" + kind; }
            @Override public boolean cacheable() { return cacheable; }
        };
    }

    @Test
    void lastCacheableSectionGetsBoundaryTrue() {
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", true), fixed("b", false), fixed("c", true)),
            ctx);
        assertEquals(3, blocks.size());
        assertFalse(blocks.get(0).cacheBoundary());  // a 之前还有 cacheable，所以不写
        assertFalse(blocks.get(1).cacheBoundary());  // b 不可缓存
        assertTrue(blocks.get(2).cacheBoundary());   // c 是最后一个 cacheable
    }

    @Test
    void noCacheableSectionsMeansNoBoundary() {
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", false), fixed("b", false)), ctx);
        assertTrue(blocks.stream().noneMatch(SystemBlock::cacheBoundary));
    }

    @Test
    void disabledSectionIsSkipped() {
        var disabled = new PromptSection() {
            @Override public String kind() { return "d"; }
            @Override public String render(SectionContext ctx) { return "X"; }
            @Override public boolean enabled(SectionContext ctx) { return false; }
        };
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(
            List.of(fixed("a", true), disabled, fixed("c", true)), ctx);
        assertEquals(2, blocks.size());
    }

    @Test
    void blankRenderIsSkipped() {
        var blank = new PromptSection() {
            @Override public String kind() { return "b"; }
            @Override public String render(SectionContext ctx) { return ""; }
        };
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", true), blank, fixed("c", true)), ctx);
        assertEquals(2, blocks.size());
        assertEquals("a", blocks.get(0).kind());
        assertEquals("c", blocks.get(1).kind());
    }

    @Test
    void attachReminderAppendsUserMessageAndDoesNotMutateOriginal() {
        var a = new PromptAssembler();
        var req = new ChatRequest("m", List.of(), new ArrayList<>(), null, List.of());
        ChatRequest out = a.attachReminder(req, "plan mode active");
        // 原 request 不变
        assertTrue(req.messages().isEmpty());
        // 新 request 多了 1 条 user message
        assertEquals(1, out.messages().size());
        ChatMessage m = out.messages().get(0);
        assertEquals(Role.USER, m.role());
        assertInstanceOf(ContentBlock.TextBlock.class, m.blocks().get(0));
        String text = ((ContentBlock.TextBlock) m.blocks().get(0)).text();
        assertTrue(text.contains("<system-reminder>"));
        assertTrue(text.contains("plan mode active"));
        assertTrue(text.contains("</system-reminder>"));
    }

    @Test
    void attachReminderNoOpWhenBodyBlank() {
        var a = new PromptAssembler();
        var req = new ChatRequest("m", List.of(new SystemBlock("x", false, "k")),
            new ArrayList<>(), null, List.of());
        ChatRequest out = a.attachReminder(req, "");
        assertSame(req, out);
    }
}
```

- [ ] **Step 2: 实现 PromptAssembler**

`src/main/java/com/maplecode/prompt/PromptAssembler.java`:

```java
package com.maplecode.prompt;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 sections 拼成 List<SystemBlock>（含 cache 边界标定），并把 reminder 注入 messages。
 * <p>
 * 不变式：assemble() 输出的 List 里最多一个 cacheBoundary=true；
 * attachReminder() 不修改入参 ChatRequest 的 messages（构造新 list 返回新对象）。
 */
public final class PromptAssembler {

    public List<SystemBlock> assemble(List<PromptSection> sections, SectionContext ctx) {
        List<SystemBlock> blocks = new ArrayList<>();
        int lastCacheableIdx = -1;
        for (PromptSection s : sections) {
            if (!s.enabled(ctx)) continue;
            String text = s.render(ctx);
            if (text == null || text.isBlank()) continue;
            blocks.add(new SystemBlock(text, false, s.kind()));
            if (s.cacheable()) lastCacheableIdx = blocks.size() - 1;
        }
        if (lastCacheableIdx >= 0) {
            SystemBlock tail = blocks.get(lastCacheableIdx);
            blocks.set(lastCacheableIdx,
                new SystemBlock(tail.content(), true, tail.kind()));
        }
        return blocks;
    }

    public ChatRequest attachReminder(ChatRequest req, String reminderBody) {
        if (reminderBody == null || reminderBody.isBlank()) return req;
        String wrapped = ReminderMessage.wrap(reminderBody);
        List<ChatMessage> newMsgs = new ArrayList<>(req.messages());
        newMsgs.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock(wrapped))));
        return new ChatRequest(req.model(), req.systemBlocks(), newMsgs,
            req.thinking(), req.tools(), req.transientReminder());
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=PromptAssemblerTest`
Expected: 6 tests passed

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/maplecode/prompt/PromptAssembler.java \
        src/test/java/com/maplecode/prompt/PromptAssemblerTest.java
git commit -m "feat(prompt): PromptAssembler（assemble + attachReminder）"
```

---

## Task 8: AgentConfig 加 reminderState + withReminderState

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentConfig.java`
- Modify: `src/test/java/com/maplecode/agent/AgentConfigTest.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`（仅改 6 参构造点）

- [ ] **Step 1: 改 AgentConfig**

`src/main/java/com/maplecode/agent/AgentConfig.java`:

```java
package com.maplecode.agent;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ThinkingConfig;

import java.util.List;

public record AgentConfig(
    String model,
    List<SystemBlock> systemBlocks,           // 替代旧 systemPrompt: String
    ThinkingConfig thinking,
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode,
    PlanModeReminder.State reminderState      // 新增
) {
    public AgentConfig {
        if (maxIterations < 1) throw new ConfigException("maxIterations must be >= 1");
        if (maxConsecutiveUnknown < 1) {
            throw new ConfigException("maxConsecutiveUnknown must be >= 1");
        }
    }

    public static AgentConfig defaults() {
        return new AgentConfig("test-model", List.of(), null, 25, 3,
            PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    public static AgentConfig fromAppConfig(AppConfig app) {
        return new AgentConfig(app.model(), app.systemBlocks(), app.thinking(),
            25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    /** 显式 `with` 风格 helper：替换 reminder 字段，其它不变。 */
    public AgentConfig withReminderState(PlanModeReminder.State state) {
        return new AgentConfig(model, systemBlocks, thinking,
            maxIterations, maxConsecutiveUnknown, planMode, state);
    }
}
```

- [ ] **Step 2: 改 AgentConfigTest**

`src/test/java/com/maplecode/agent/AgentConfigTest.java` —— 所有 `new AgentConfig("m", null, null, ...)` 改为 7 参：

```java
new AgentConfig("m", null, null, 25, 3, PlanMode.NORMAL,
    PlanModeReminder.State.initial())
```

- [ ] **Step 3: 改 AgentLoopTest**

`src/test/java/com/maplecode/agent/AgentLoopTest.java` —— 找到 `AgentConfig.defaults()` 调用保持不变；找到手写的：

```java
new AgentConfig("m", null, null, 3, 3, com.maplecode.agent.PlanMode.NORMAL)
```

改为：

```java
new AgentConfig("m", List.of(), null, 3, 3,
    com.maplecode.agent.PlanMode.NORMAL,
    com.maplecode.prompt.PlanModeReminder.State.initial())
```

- [ ] **Step 4: 跑测试验证**

Run: `mvn -q test -Dtest='AgentConfigTest,AgentLoopTest'`
Expected: 这两个测试类全绿。其它测试**仍可能挂**，因为 AgentLoop.run() 和 ChatSession.toRequest 还没对接。T13 之后统一绿。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/agent/AgentConfig.java \
        src/test/java/com/maplecode/agent/AgentConfigTest.java \
        src/test/java/com/maplecode/agent/AgentLoopTest.java
git commit -m "refactor(prompt): AgentConfig 加 reminderState + systemBlocks 切结构化"
```

---


---

## Task 9: AppConfig.yamlPrompt 字段 + ConfigLoader 简化

**Files:**
- Modify: `src/main/java/com/maplecode/config/AppConfig.java`
- Modify: `src/main/java/com/maplecode/config/ConfigLoader.java`
- Modify: `src/test/java/com/maplecode/config/ConfigLoaderTest.java`

- [ ] **Step 1: 改 AppConfig**

`src/main/java/com/maplecode/config/AppConfig.java`:

```java
package com.maplecode.config;

import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ThinkingConfig;

import java.time.Duration;
import java.util.List;

public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String yamlPrompt,                  // 来自 YAML `system_prompt`；nullable
    List<SystemBlock> systemBlocks,     // 默认 List.of()；App.main 启动时组装
    ThinkingConfig thinking,
    AppConfig.Timeouts timeouts
) {
    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }
}
```

- [ ] **Step 2: 改 ConfigLoader**

`src/main/java/com/maplecode/config/ConfigLoader.java` —— 删除 `DEFAULT_SYSTEM_PROMPT` 三行常量；`parse()` 中：

```java
String yamlPrompt = optionalString(root, "system_prompt");   // nullable，无 fallback 常量
// ...
return new AppConfig(protocol, model, baseUrl, apiKey, yamlPrompt,
    List.of(),   // systemBlocks 占位；App.main 启动期写入
    thinking, new AppConfig.Timeouts(connect, read));
```

- [ ] **Step 3: 改 ConfigLoaderTest**

`src/test/java/com/maplecode/config/ConfigLoaderTest.java` —— 凡断言 `app.systemPrompt()` / `app.systemPrompt() != null` 的地方改为：

```java
assertEquals(expectedPrompt, app.yamlPrompt());
```

如果有断言 `app.systemPrompt().equals(DEFAULT_3_LINES)` 的，改为：

```java
assertNull(app.yamlPrompt());   // 没有 system_prompt 字段时为 null
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest='ConfigLoaderTest,ConfigLoaderDeprecationWarningTest'`
Expected: 这两个绿。其它涉及 `app.systemPrompt()` 的旧调用点（如 AgentConfig.fromAppConfig）已由 T8 处理。其它 `app.systemPrompt()` 调用可能存在于 main / 其它类 —— 留待 T14 一起修。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/config/AppConfig.java \
        src/main/java/com/maplecode/config/ConfigLoader.java \
        src/test/java/com/maplecode/config/ConfigLoaderTest.java
git commit -m "refactor(prompt): AppConfig.yamlPrompt 字段；默认三行 prompt 常量下放至 sections"
```

---

## Task 10: TokenUsage 解析与 StreamPrinter.usage

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`
- Modify: `src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`
- Modify: `src/main/java/com/maplecode/ui/StreamPrinter.java`
- Modify: `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserUsageTest.java`（若已存在）
- Create: `src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserCacheTest.java`

- [ ] **Step 1: 写 cache 解析测试**

`src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserCacheTest.java`:

```java
package com.maplecode.provider.anthropic;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicStreamParserCacheTest {

    @Test
    void messageStartCarriesCacheTokens() {
        var parser = new AnthropicStreamParser();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;

        parser.feed(new SseEvent("message_start",
            "{\"type\":\"message_start\","
          + "\"message\":{\"usage\":{\"input_tokens\":2000,"
          + "\"cache_creation_input_tokens\":1800,"
          + "\"cache_read_input_tokens\":0}}}"),
            sink);
        parser.feed(new SseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":300}}"),
            sink);
        parser.feed(new SseEvent("message_stop",
            "{\"type\":\"message_stop\"}"), sink);

        var msgEnd = (StreamChunk.MessageEnd) out.get(out.size() - 1);
        TokenUsage u = msgEnd.usage();
        assertNotNull(u);
        assertEquals(2000, u.inputTokens());
        assertEquals(300, u.outputTokens());
        assertEquals(1800, u.cacheCreationTokens());
        assertEquals(0, u.cacheReadTokens());
    }

    @Test
    void messageStartCacheReadOnSecondCall() {
        var parser = new AnthropicStreamParser();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;

        parser.feed(new SseEvent("message_start",
            "{\"type\":\"message_start\","
          + "\"message\":{\"usage\":{\"input_tokens\":2000,"
          + "\"cache_creation_input_tokens\":0,"
          + "\"cache_read_input_tokens\":1800}}}"),
            sink);
        parser.feed(new SseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":150}}"),
            sink);
        parser.feed(new SseEvent("message_stop",
            "{\"type\":\"message_stop\"}"), sink);

        var msgEnd = (StreamChunk.MessageEnd) out.get(out.size() - 1);
        TokenUsage u = msgEnd.usage();
        assertEquals(0, u.cacheCreationTokens());
        assertEquals(1800, u.cacheReadTokens());
    }
}
```

- [ ] **Step 2: 跑测试验证挂**

Run: `mvn -q test -Dtest=AnthropicStreamParserCacheTest`
Expected: 失败 —— 字段不存在

- [ ] **Step 3: 改 AnthropicStreamParser**

`src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java` 在类顶部加：

```java
private int cacheCreation = 0;
private int cacheRead = 0;
```

`reset()` 内加：

```java
cacheCreation = 0;
cacheRead = 0;
```

`message_start` 分支改为：

```java
if (type.equals("message_start")) {
    currentBlock = BlockType.NONE;
    lastStopReason = null;
    lastInputTokens = 0;
    lastOutputTokens = 0;
    cacheCreation = 0;
    cacheRead = 0;
    currentToolUseId = null;
    currentToolName = null;
    currentToolJson.setLength(0);
    JsonNode node = parse(event.data());
    JsonNode usage = node.path("message").path("usage");
    lastInputTokens  = usage.path("input_tokens").asInt(0);
    cacheCreation    = usage.path("cache_creation_input_tokens").asInt(0);
    cacheRead        = usage.path("cache_read_input_tokens").asInt(0);
    sink.accept(new StreamChunk.MessageStart());
    return;
}
```

`message_stop` 分支改为：

```java
if (type.equals("message_stop")) {
    TokenUsage usage = (lastInputTokens == 0 && lastOutputTokens == 0
        && cacheCreation == 0 && cacheRead == 0)
        ? null
        : new TokenUsage(lastInputTokens, lastOutputTokens, cacheCreation, cacheRead);
    sink.accept(new StreamChunk.MessageEnd(mapStopReason(lastStopReason), usage));
    return;
}
```

注意：`message_delta` **不**刷新 cache_*（Anthropic streaming 行为，只发 output_tokens）。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest='AnthropicStreamParserCacheTest,AnthropicStreamParserUsageTest'`
Expected: 都绿

- [ ] **Step 5: 改 OpenAiStreamParser**

`src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java` —— 把所有 `new TokenUsage(a, b)` 改为 `TokenUsage.of(a, b)`。

- [ ] **Step 6: 改 StreamPrinter**

`src/main/java/com/maplecode/ui/StreamPrinter.java` 在 import 区加 `import com.maplecode.provider.TokenUsage;`，类内添加：

```java
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
```

`info(...)` 已有。`StreamPrinterAgentEventTest` 也要相应更新（如果有断言 `printer` 没有 `usage` 方法的话）—— 检查测试类，没断言就不动。

- [ ] **Step 7: 跑全测**

Run: `mvn -q test`
Expected: 应该**仍红**，因为 AgentLoop / 两个 RequestMapper 没改。**预期已知挂**：

```
AgentLoopTest, AnthropicRequestMapperTest, OpenAiRequestMapperTest, OpenAiStreamParserTest,
OpenAiStreamParserToolTest, AnthropicStreamParserTest, AnthropicStreamParserToolTest,
StreamPrinterAgentEventTest
```

继续 T11、T12 后会逐步转绿。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/maplecode/provider/anthropic/ \
        src/main/java/com/maplecode/provider/openai/ \
        src/main/java/com/maplecode/ui/ \
        src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserCacheTest.java
git commit -m "feat(prompt): 解析 cache_*.tokens；StreamPrinter.usage"
```

---

## Task 11: 两个 RequestMapper 切 multi-block / 拼第一条 message

**Files:**
- Modify: `src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java`
- Modify: `src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java`
- Create: `src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java`
- Create: `src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java`

- [ ] **Step 1: 写 AnthropicRequestMapper 测试**

`src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java`:

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.prompt.SystemBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicRequestMapperTest {

    private final ObjectMapper JSON = new ObjectMapper();
    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    void systemBlocksSerialisedAsArray() throws Exception {
        var req = new ChatRequest("m",
            List.of(new SystemBlock("A", false, "a"),
                    new SystemBlock("B", true, "b")),
            List.of(), null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        var sys = root.path("system");
        assertTrue(sys.isArray());
        assertEquals(2, sys.size());
        assertEquals("A", sys.get(0).path("text").asText());
        assertEquals("B", sys.get(1).path("text").asText());
    }

    @Test
    void cacheBoundaryMapsToEphemeral() throws Exception {
        var req = new ChatRequest("m",
            List.of(new SystemBlock("A", false, "a"),
                    new SystemBlock("B", true, "b")),
            List.of(), null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        // 第一个 block 不应有 cache_control
        assertTrue(root.path("system").get(0).path("cache_control").isMissingNode());
        // 第二个 block 应当有 ephemeral
        assertEquals("ephemeral",
            root.path("system").get(1).path("cache_control").path("type").asText());
    }

    @Test
    void emptySystemBlocksOmitsSystem() throws Exception {
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        assertTrue(root.path("system").isMissingNode());
    }

    @Test
    void userMessagePreserved() throws Exception {
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        assertEquals("hi",
            root.path("messages").get(0).path("content").get(0).path("text").asText());
    }
}
```

- [ ] **Step 2: 跑测试验证挂**

Run: `mvn -q test -Dtest=AnthropicRequestMapperTest`
Expected: 失败 —— 旧 mapper 把 systemPrompt: String 写为字符串

- [ ] **Step 3: 改 AnthropicRequestMapper**

`src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java` 在 import 区加：

```java
import com.maplecode.prompt.SystemBlock;
```

把原 `if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) { root.put("system", req.systemPrompt()); }` 替换为：

```java
if (req.systemBlocks() != null && !req.systemBlocks().isEmpty()) {
    ArrayNode sysArr = root.putArray("system");
    for (SystemBlock b : req.systemBlocks()) {
        ObjectNode bn = sysArr.addObject();
        bn.put("type", "text");
        bn.put("text", b.content());
        if (b.cacheBoundary()) {
            ObjectNode cc = bn.putObject("cache_control");
            cc.put("type", "ephemeral");
        }
    }
}
```

- [ ] **Step 4: 跑 Anthropic 测试**

Run: `mvn -q test -Dtest=AnthropicRequestMapperTest`
Expected: 4 tests passed

- [ ] **Step 5: 写 OpenAI 测试**

`src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java`:

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.prompt.SystemBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiRequestMapperTest {

    private final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();

    @Test
    void systemBlocksJoinAsFirstSystemMessage() throws Exception {
        var req = new ChatRequest("m",
            List.of(new SystemBlock("A", false, "a"),
                    new SystemBlock("B", true, "b")),
            List.of(new ChatMessage(Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        var msgs = root.path("messages");
        assertEquals("system", msgs.get(0).path("role").asText());
        assertEquals("A\n\nB", msgs.get(0).path("content").asText());
        assertEquals("user", msgs.get(1).path("role").asText());
    }

    @Test
    void cacheBoundaryIgnoredOnOpenAI() throws Exception {
        var req = new ChatRequest("m",
            List.of(new SystemBlock("A", true, "a")),
            List.of(), null, List.of());
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        // OpenAI 不写 cache_control 字段
        var sys0 = root.path("messages").get(0);
        assertTrue(sys0.path("cache_control").isMissingNode());
    }
}
```

- [ ] **Step 6: 改 OpenAiRequestMapper**

`src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java` 在 import 区加：

```java
import com.maplecode.prompt.SystemBlock;
import java.util.stream.Collectors;
```

把 `msgs.add(system...)` 之前整段改为：

```java
if (req.systemBlocks() != null && !req.systemBlocks().isEmpty()) {
    String joined = req.systemBlocks().stream()
        .map(SystemBlock::content)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.joining("\n\n"));
    if (!joined.isBlank()) {
        msgs.add(JSON.createObjectNode()
            .put("role", "system")
            .put("content", joined));
    }
}
```

并删除原 `if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) { ... }`。

- [ ] **Step 7: 跑测试**

Run: `mvn -q test -Dtest='OpenAiRequestMapperTest,AnthropicRequestMapperTest'`
Expected: 全绿

- [ ] **Step 8: 跑全测看还剩哪些红**

Run: `mvn -q test`
Expected 仍红：`AgentLoopTest` 和调用 `new AgentLoop(provider, ..., session, agentConfig, ...)`（5 参）的地方 —— 因为 AgentLoop 的 6 参构造（带 usageSink）还没改。T12 处理。

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java \
        src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java \
        src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java \
        src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java
git commit -m "feat(prompt): 两个 mapper 切 multi-block / 拼第一条 system；Anthropic 加 cache_control"
```

---

## Task 12: ResponseCollector 加 usageSink + AgentLoop 接入 reminder / usageSink

**Files:**
- Modify: `src/main/java/com/maplecode/agent/ResponseCollector.java`
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`
- Modify: `src/test/java/com/maplecode/agent/ResponseCollectorTest.java`
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`

- [ ] **Step 1: 写 ResponseCollector usageSink 测试**

`src/test/java/com/maplecode/agent/ResponseCollectorTest.java` —— 找到现有测试，在文件末尾追加：

```java
@Test
void usageSinkInvokedOnMessageEnd() {
    List<StreamChunk> chunks = List.of(
        new StreamChunk.MessageStart(),
        new StreamChunk.MessageEnd(StopReason.END_TURN, TokenUsage.of(10, 20))
    );
    List<TokenUsage> usages = new ArrayList<>();
    Consumer<TokenUsage> sink = usages::add;

    var col = new ResponseCollector(e -> {}, emptyRegistry(), sink);
    chunks.forEach(col);

    assertEquals(1, usages.size());
    assertEquals(TokenUsage.of(10, 20), usages.get(0));
}

@Test
void usageSinkNullIsOk() {
    var chunks = List.of((StreamChunk) new StreamChunk.MessageEnd(
        StopReason.END_TURN, TokenUsage.of(10, 20)));
    var col = new ResponseCollector(e -> {}, emptyRegistry(), null);
    chunks.forEach(col);   // 不应 NPE
}
```

注意 import 加上：

```java
import com.maplecode.provider.TokenUsage;
import com.maplecode.provider.StreamChunk.StopReason;
import java.util.ArrayList;
import java.util.function.Consumer;
```

（`StopReason`/`TokenUsage` 在已有 import 中则不重复）

- [ ] **Step 2: 跑测试验证 NPE**

Run: `mvn -q test -Dtest=ResponseCollectorTest`
Expected: 编译失败 —— `new ResponseCollector(...)` 没有 3 参版本

- [ ] **Step 3: 改 ResponseCollector**

`src/main/java/com/maplecode/agent/ResponseCollector.java` —— 加 import `com.maplecode.provider.TokenUsage` 与 `java.util.function.Consumer`；加字段与方法：

```java
private final Consumer<TokenUsage> usageSink;   // nullable
private TokenUsage usage;

ResponseCollector(Consumer<AgentEvent> sink, ToolRegistry registry,
                  Consumer<TokenUsage> usageSink) {
    this.sink = sink;
    this.registry = registry;
    this.usageSink = usageSink;
}

/** 旧 2 参构造保留为向后兼容路径（usageSink=null）。 */
ResponseCollector(Consumer<AgentEvent> sink, ToolRegistry registry) {
    this(sink, registry, null);
}
```

`MessageEnd` 分支改为：

```java
case MessageEnd e -> {
    stopReason = e.reason();
    usage = e.usage();
    if (usageSink != null && usage != null) usageSink.accept(usage);
}
```

- [ ] **Step 4: 跑 ResponseCollectorTest**

Run: `mvn -q test -Dtest=ResponseCollectorTest`
Expected: 全绿（旧的 + 新增 2 个）

- [ ] **Step 5: 改 AgentLoop**

`src/main/java/com/maplecode/agent/AgentLoop.java` —— 改 import 区，加 `com.maplecode.prompt.PromptAssembler` 与 `com.maplecode.prompt.PlanModeReminder`：

加字段：

```java
private final Consumer<TokenUsage> usageSink;
```

构造改为 6 参：

```java
public AgentLoop(LlmProvider provider, ToolRegistry registry,
                 ToolExecutor executor, ChatSession session,
                 AgentConfig config, Consumer<TokenUsage> usageSink) {
    this.provider = provider;
    this.registry = registry;
    this.executor = executor;
    this.session = session;
    this.config = config;
    this.usageSink = usageSink;
}

/** 5 参重载（usageSink=null），保留测试路径。 */
public AgentLoop(LlmProvider provider, ToolRegistry registry,
                 ToolExecutor executor, ChatSession session,
                 AgentConfig config) {
    this(provider, registry, executor, session, config, null);
}
```

`run` 内 `session.toRequest(config.model(), config.systemBlocks(), ...)` 用新签名；并在 `provider.stream(req, col)` 之前加 reminder 注入：

```java
// PLAN 模式下追加 reminder（不持久）
var form = PlanModeReminder.decide(
    config.planMode(), config.reminderState(), iteration);
if (form != PlanModeReminder.Form.NONE) {
    String body = (form == PlanModeReminder.Form.FULL)
        ? PlanModeReminder.renderFull()
        : PlanModeReminder.renderBrief();
    req = new PromptAssembler().attachReminder(req, body);
    if (form == PlanModeReminder.Form.FULL) {
        config = config.withReminderState(
            config.reminderState().afterFull(iteration));
    }
}
```

把原 `var req = session.toRequest(config.model(), config.systemBlocks(), config.thinking(), tools);` 这一行紧接在新变量 `tools` 定义之后。

**全部 import 改动预览**：

```java
import com.maplecode.provider.TokenUsage;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.PromptAssembler;
```

并把 `ResponseCollector col = new ResponseCollector(sink, registry);` 改为：

```java
ResponseCollector col = new ResponseCollector(sink, registry, usageSink);
```

- [ ] **Step 6: 改 AgentLoopTest（5 参 → 6 参构造点）**

`src/test/java/com/maplecode/agent/AgentLoopTest.java` —— 所有 `new AgentLoop(provider, ..., session, cfg)` 改为 `new AgentLoop(provider, ..., session, cfg, null)`。grep 找到所有 ~7 个构造点，统一改：

```java
new AgentLoop(provider, registry, executor, session, AgentConfig.defaults(), null)
new AgentLoop(provider, registry, executor, session, cfg, null)
// 等等
```

- [ ] **Step 7: 跑全测**

Run: `mvn -q test`
Expected: **全绿**。如果仍有红，多半是 ReplLoop / App.main 的旧 `new AgentLoop(...)` 调用没改 —— 它们由 T13 处理。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/maplecode/agent/ResponseCollector.java \
        src/main/java/com/maplecode/agent/AgentLoop.java \
        src/test/java/com/maplecode/agent/
git commit -m "feat(prompt): AgentLoop 接入 reminder + ResponseCollector 推送 usage"
```

---

## Task 13: ReplLoop 携带 reminderState + 传 usageSink

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: 修主构造里 AgentLoop 构造点**

`src/main/java/com/maplecode/ui/ReplLoop.java` —— 加 import `com.maplecode.provider.TokenUsage`；把：

```java
this.agent = new AgentLoop(provider, registry, executor, session, agentConfig);
```

改为：

```java
this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
    u -> printer.usage(u));
```

- [ ] **Step 2: /plan 命令复制 reminderState 并重置**

```java
if (trimmed.startsWith("/plan ")) {
    String query = trimmed.substring(6).trim();
    if (query.isEmpty()) { printer.error("/plan requires a query"); continue; }
    agentConfig = new AgentConfig(
        agentConfig.model(), agentConfig.systemBlocks(), agentConfig.thinking(),
        agentConfig.maxIterations(), agentConfig.maxConsecutiveUnknown(),
        PlanMode.PLAN,
        com.maplecode.prompt.PlanModeReminder.State.initial());   // 重置
    agent.updateConfig(agentConfig);
    agent.run(query, printer);
    printer.newline();
    continue;
}
```

- [ ] **Step 3: /do 与 /cancel 保留 reminderState（/cancel 重置回 initial）**

/do 部分（保留 state 不必要因为切回 NORMAL，state 不再用；为清晰仍复制过来以保持 record 不变）：

```java
if (trimmed.equals("/do")) {
    if (agentConfig.planMode() != PlanMode.PLAN) { printer.error("not in plan mode"); continue; }
    String planText = lastAssistantText();
    if (planText == null) { printer.error("no plan to execute"); continue; }
    agent.session().clear();
    agentConfig = new AgentConfig(
        agentConfig.model(), agentConfig.systemBlocks(), agentConfig.thinking(),
        agentConfig.maxIterations(), agentConfig.maxConsecutiveUnknown(),
        PlanMode.NORMAL,
        com.maplecode.prompt.PlanModeReminder.State.initial());
    agent.updateConfig(agentConfig);
    agent.run(planText, printer);
    printer.newline();
    continue;
}
```

/cancel 部分：

```java
if (trimmed.equals("/cancel")) {
    agent.cancel();
    agentConfig = new AgentConfig(
        agentConfig.model(), agentConfig.systemBlocks(), agentConfig.thinking(),
        agentConfig.maxIterations(), agentConfig.maxConsecutiveUnknown(),
        PlanMode.NORMAL,
        com.maplecode.prompt.PlanModeReminder.State.initial());
    agent.updateConfig(agentConfig);
    printer.info("cancelled");
    continue;
}
```

- [ ] **Step 4: 跑全测**

Run: `mvn -q test`
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(prompt): ReplLoop 传 usageSink；/plan /do /cancel 携带 reminderState"
```

---

## Task 14: App.main 启动期组装 systemBlocks

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: 改 App.main**

`src/main/java/com/maplecode/App.java`:

```java
package com.maplecode;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.prompt.DefaultSections;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PromptAssembler;
import com.maplecode.prompt.SectionContext;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.provider.TokenUsage;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.ui.ReplLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class App {

    public static void main(String[] args) throws Exception {
        Path configPath = locateConfig(args);
        if (configPath == null) {
            System.err.println("no config found. ...");
            System.exit(78);
        }
        AppConfig raw = ConfigLoader.load(configPath);
        LlmProvider provider = new ProviderRegistry().create(raw);
        ToolRegistry registry = new ToolRegistry(List.of(
            new com.maplecode.tool.ReadFileTool(),
            new com.maplecode.tool.WriteFileTool(),
            new com.maplecode.tool.EditFileTool(),
            new com.maplecode.tool.ExecTool(),
            new com.maplecode.tool.GlobTool(),
            new com.maplecode.tool.GrepTool()
        ));

        // 启动期组装 systemBlocks：env + tools + 用户覆盖
        Path cwd = Paths.get(System.getProperty("user.dir"));
        DynamicContext env = DynamicContext.capture(cwd);
        List<com.maplecode.tool.Tool> tools = registry.all();
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL, raw.yamlPrompt());
        var sectionCtx = new SectionContext(tools, env, PlanMode.NORMAL);
        var blocks = new PromptAssembler().assemble(sections, sectionCtx);

        AgentConfig config = new AgentConfig(
            raw.model(), blocks, raw.thinking(),
            25, 3, PlanMode.NORMAL,
            com.maplecode.prompt.PlanModeReminder.State.initial());

        ReplLoop repl = new ReplLoop(raw, provider,
            new com.maplecode.ui.StreamPrinter(System.out),
            buildLineReader(), registry, config);
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

注意：
1. **ReplLoop 构造签名变了**：之前由 `ReplLoop.fromConfig` 隐式构 `AgentConfig`，现在由 App.main 直接构好传入。`ReplLoop` 改成接受 `AgentConfig` 显式参数。
2. **TokenUsage / ThinkingConfig 未用 import 清理**：保留 import 或 grep -v 删冗余。

- [ ] **Step 2: 改 ReplLoop 构造签名接受 AgentConfig**

`src/main/java/com/maplecode/ui/ReplLoop.java` —— 加 import `com.maplecode.agent.AgentConfig`。改造：

```java
public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                LineReader reader, ToolRegistry registry, AgentConfig agentConfig) {
    this.appConfig = appConfig;
    this.provider = provider;
    this.printer = printer;
    this.reader = reader;
    this.registry = registry;
    this.executor = new ToolExecutor(registry);
    this.session = new ChatSession();
    this.agentConfig = agentConfig;
    this.agent = new AgentLoop(provider, registry, executor, session, agentConfig,
        u -> printer.usage(u));
}

public static ReplLoop fromConfig(AppConfig config, LlmProvider provider,
                                  ToolRegistry registry) throws java.io.IOException {
    // 保留此重载但不实际工作：
    throw new UnsupportedOperationException("use App.main with explicit AgentConfig");
}
```

> `fromConfig` 保留方法但抛异常，因为已有调用点只可能在测试里。本期测试不调用它。

- [ ] **Step 3: 跑全测**

Run: `mvn -q test`
Expected: 全绿

- [ ] **Step 4: 跑 mvn package 验证 jar 可构建**

Run: `mvn -q package`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/maplecode/App.java \
        src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "feat(prompt): App.main 启动期组装 systemBlocks；ReplLoop 接受显式 AgentConfig"
```

---

## Task 15: 全测与手动 smoke 验证

**Files:** 仅跑命令，不动代码

- [ ] **Step 1: 跑全测**

Run: `mvn -q test`
Expected: 全绿，无 warning 残留。

- [ ] **Step 2: 跑 mvn package 出 jar**

Run: `mvn -q package -DskipTests`
Expected: `target/maple-code-java-0.1.0.jar` 生成

- [ ] **Step 3: Smoke 场景 A（多轮 cache 验证）**

Run:

```bash
echo "列出 src/main/java 下所有 Java 文件的路径" | \
  java -jar target/maple-code-java-0.1.0.jar
```

预期：第一次响应结束打印 `[usage: input=N out=M cache_create=K cache_read=0]`。再发同样问题，预期 `cache_create=0 cache_read=K`（接近 system 全量）。

- [ ] **Step 4: Smoke 场景 B（/clear cache 重建）**

输入 `/clear` → 再问同样问题。预期第二轮 cache_create 再次非零。

- [ ] **Step 5: Smoke 场景 C（Plan Mode reminder 节流）**

输入 `/plan 帮我设计一个计算器`：预期：
1. 系统提示出现完整规划模式段
2. 第一条 reminder 完整（含"规划模式已开启"）
3. 模型只输出文本计划（不调 write_file / edit_file / exec）

再问 5 次任意细节，预期中间出现 4 次精简版 reminder，第 6 轮再来完整。

- [ ] **Step 6: Smoke 场景 D（/do 接力 plan）**

接着 C 的尾巴输入 `/do`，预期：会话清空、plan 文本作为新输入、NORMAL 模式运行，本轮 reminder 不出现。

- [ ] **Step 7: Smoke 场景 E（YAML 自定义）**

`maplecode.yaml` 增加：

```yaml
system_prompt: |
  做且只做单元测试；不要碰 production 代码。
```

重启 maplecode，问"重构 src/main/java/com/maplecode/App.java"，预期模型拒绝（提示需要 production 修改触发自定义段）。

- [ ] **Step 8: Smoke 场景 F（/cancel 重置）**

/plan 进入后立即 `/cancel`，预期 state 复位回 NORMAL；下次 /plan 第一轮 reminder 仍是完整版。

- [ ] **Step 9: 写收尾 commit（如有调整）**

如果有 prompt 微调或 mapper 修缺：

```bash
git add <changed files>
git commit -m "fix(prompt): smoke 验证后的微调"
```

如果全绿无调整，跳过此步。

---

## 验收清单（与 spec §9 对齐）

- [ ] `mvn package` 产出 jar
- [ ] `mvn test` 全绿
- [ ] 默认配置下启动后 systemBlocks 含 8 段（含 EnvironmentSection）；YAML `system_prompt` 非空时多一段 `custom_instruction`
- [ ] AnthropicRequestMapper 输出 wire JSON 含 system array；末段（cacheable 末位）含 `cache_control: ephemeral`
- [ ] OpenAiRequestMapper 输出第一条 message role=system
- [ ] 终端默认显示 `[usage: input=N out=M cache_create=K cache_read=J]`
- [ ] 重启 / /clear 之后首轮 `cache_create > 0`，下一轮 `cache_read > 0`
- [ ] /plan 之后的第 1/6/11 轮 reminder 完整版；其余精简
- [ ] reminder 在 session 历史中不出现
- [ ] YAML 自定义规则生效
- [ ] pom 依赖不变

---

## 后续（不在本期）

- v5：CLAUDE.md / AGENTS.md 等项目指令文件加载
- v5：长期记忆 / 自动会话内记忆
- v5：真实 MCP 接入
- v5：Skill 真实注入
- v5：自动化 LLM 评估
- v5：Anthropic cache 双断点（system + tools）
