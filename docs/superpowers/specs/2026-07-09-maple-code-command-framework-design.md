# v8 斜杠命令框架设计规格

**日期**：2026-07-09
**范围**：为 MapleCode 实现斜杠命令注册与分发框架。将现有 13 个内联命令（`/exit`, `/clear`, `/new`, `/resume`, `/compact`, `/tools`, `/plan`, `/do`, `/mode`, `/cancel`, `/memory`）从 `ReplLoop` 的 if-else 链中抽出，重构为 `Command` 接口 + `CommandRegistry` 注册中心 + `CommandParser` 解析器的标准化框架。新增 `/help` 和 `/review` 两个命令。支持 Tab 补全。
**不做**：用户自定义命令、动态生成提示词、命令级权限控制、Skill 系统（留给后续阶段）。

---

## 1. 目标与非目标

**目标**

- `Command` 接口定义命令契约：名称、别名、描述、用法、类型、隐藏标志、执行函数
- `CommandRegistry` 启动时注册所有命令，名称/别名冲突检测（撞名直接抛异常，不等运行时）
- `CommandParser` 解析斜杠输入：`/name args`，命令名大小写不敏感
- `CommandContext` 窄接口，命令只通过它与 UI/Agent/状态交互，不直接依赖 `ReplLoop` 内部
- 控制流异常 `ExitReplException` 供 `/exit` 命令终止 REPL 主循环
- `CommandCompleter` 集成 JLine 的 `Completer` 接口，Tab 补全仅在行首触发，隐藏命令不参与
- 14 个内置命令：`/help`, `/clear`, `/new`, `/resume`, `/compact`, `/tools`, `/plan`, `/do`, `/mode`, `/cancel`, `/memory`, `/status`, `/review`, `/exit`
- `ReplLoop.run()` 从 ~300 行 if-else 链瘦身到 ~50 行分发器

**非目标**

- 用户自定义命令（插件系统）
- 动态生成提示词
- 命令级权限控制（哪些命令可用、哪些需要确认）
- 命令历史统计
- 异步命令执行（当前 agent.run 是同步阻塞，/cancel 通过取消标志实现）

---

## 2. 包结构

```
com.maplecode.command/
  Command              — 命令接口
  CommandType          — 枚举：LOCAL / UI_STATE / PROMPT
  CommandContext       — 窄 facade 接口（命令视角）
  CommandRegistry      — 注册中心（冲突检测 + 查找 + 补全）
  CommandParser        — 静态工具类（斜杠输入解析）
  CommandCompleter     — JLine Completer 实现
  ExitReplException    — 控制流异常（/exit 专用）
  HelpCommand          — /help
  ClearCommand         — /clear
  NewCommand           — /new
  ResumeCommand        — /resume
  CompactCommand       — /compact
  ToolsCommand         — /tools
  PlanCommand          — /plan
  DoCommand            — /do
  ModeCommand          — /mode
  CancelCommand        — /cancel
  MemoryCommand        — /memory
  StatusCommand        — /status
  ReviewCommand        — /review
  ExitCommand          — /exit
```

---

## 3. Command 接口

```java
package com.maplecode.command;

import java.util.List;

/**
 * 斜杠命令契约。每个内置命令实现此接口。
 * 在 App.main 启动阶段注册到 CommandRegistry。
 */
public interface Command {
    /** 命令名称（小写），如 "help"、"memory"。 */
    String name();

    /** 一句话描述，用于 /help 列表。 */
    String description();

    /** 用法示例，如 "/help [command]"、"/memory <list|clear|extract>"。 */
    String usage();

    /** 命令类型，纯元数据，用于 /help 分类显示。 */
    CommandType type();

    /**
     * 是否隐藏。隐藏命令不参与 /help 列表和 Tab 补全。
     * 用于内部命令或别名命令。
     */
    boolean hidden();

    /**
     * 别名列表。默认返回空集合（不可变）。
     * 实现时必须返回非 null。
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 执行命令。
     *
     * @param args 命令名之后的原始字符串（已 trim），空输入为 ""
     * @param ctx  UI 控制接口
     * @throws ExitReplException 终止 REPL 主循环（/exit 专用）
     */
    void execute(String args, CommandContext ctx);
}
```

### 3.1 CommandType 枚举

```java
package com.maplecode.command;

/**
 * 命令分类。纯元数据，分发器不按它路由，仅用于 /help 分组显示。
 */
public enum CommandType {
    /** 纯本地操作，不涉及 Agent 交互。 */
    LOCAL,
    /** 影响界面状态（session、压缩、权限模式等）。 */
    UI_STATE,
    /** 预设提示词送给 AI 执行。 */
    PROMPT
}
```

### 3.2 ExitReplException

```java
package com.maplecode.command;

/**
 * 控制流异常。/exit 命令抛出，ReplLoop.run() 的 catch 块捕获后正常退出。
 * 继承 RuntimeException，无需显式声明。
 */
public class ExitReplException extends RuntimeException {}
```

---

## 4. CommandContext 接口

窄 facade，命令通过它与外部交互，不直接依赖 `ReplLoop` 内部字段。

```java
package com.maplecode.command;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.permission.PermissionMode;
import com.maplecode.session.ChatSession;
import com.maplecode.session.TokenUsage;

public interface CommandContext {
    // ── 输出 ──

    /** 显示普通信息给用户。 */
    void sendMessage(String message);

    /** 显示红色错误信息。 */
    void sendError(String message);

    // ── Agent 交互 ──

    /**
     * 把文本送进对话交给 AI。同步阻塞直到 Agent 完成。
     * 用于 /plan、/do、/review 等 PROMPT 类型命令。
     */
    void sendToAgent(String prompt);

    /** 中断正在执行的 Agent 任务（设置取消标志）。 */
    void cancelCurrentAgentRun();

    /** 当前是否有 Agent 任务在执行。 */
    boolean isAgentRunning();

    // ── 模式 ──

    void setPlanMode(PlanMode mode);
    PlanMode getPlanMode();

    PermissionMode getPermissionMode();
    void setPermissionMode(PermissionMode mode);

    // ── 状态 ──

    /** 当前 token 用量，可能返回 null（首回合前）。 */
    TokenUsage getTokenUsage();

    /** 刷新底部状态栏。 */
    void updateStatusBar();

    // ── 交互 ──

    /** 读取用户输入（带 prompt），用于 /resume 的交互式选择。 */
    String readLine(String prompt);

    // ── 会话 ──

    ChatSession getSession();
    AgentConfig getAgentConfig();
}
```

`ReplLoop` 通过私有内部类 `private class CommandContextImpl implements CommandContext` 实现此接口，桥接到已有的 `printer`、`agent`、`agentConfig`、`engine`、`statusBar` 等字段。

---

## 5. CommandRegistry

```java
package com.maplecode.command;

import java.util.*;

public final class CommandRegistry {
    private final Map<String, Command> byName;    // name → Command（全小写）
    private final Map<String, Command> byAlias;   // alias → Command（全小写）

    /**
     * 构造时注册所有命令。
     * 冲突检测：name/alias 不能与已有 name/alias 重复，alias 不能等于自身 name。
     * 冲突时抛 IllegalArgumentException，启动即失败。
     */
    public CommandRegistry(List<Command> commands) { ... }

    /** 按名称或别名查找（大小写不敏感），未命中返回 empty。 */
    public Optional<Command> lookup(String name) { ... }

    /** 所有非隐藏命令，按 name 字母序排列。用于 /help 列表。 */
    public List<Command> visible() { ... }

    /**
     * 所有命令名 + 别名（非隐藏），按字母序排列。
     * 用于 Tab 补全。
     */
    public List<String> completableNames() { ... }
}
```

### 5.1 冲突检测算法

```
for each command in commands:
    lowerName = command.name().toLowerCase()

    // 1. name 不能与已有 name 冲突
    if byName.containsKey(lowerName): throw
    // 2. name 不能与已有 alias 冲突
    if byAlias.containsKey(lowerName): throw

    byName.put(lowerName, command)

    for each alias in command.aliases():
        lowerAlias = alias.toLowerCase()
        // 3. alias 不能等于自身 name（防循环）
        if lowerAlias.equals(lowerName): throw
        // 4. alias 不能与已有 name 冲突
        if byName.containsKey(lowerAlias): throw
        // 5. alias 不能与已有 alias 冲突
        if byAlias.containsKey(lowerAlias): throw

        byAlias.put(lowerAlias, command)
```

### 5.2 查找逻辑

```
lookup(inputName):
    lower = inputName.toLowerCase()
    cmd = byName.get(lower)
    if cmd != null: return Optional.of(cmd)
    cmd = byAlias.get(lower)
    return Optional.ofNullable(cmd)
```

### 5.3 completableNames 排序

```java
public List<String> completableNames() {
    List<String> names = new ArrayList<>();
    for (Command cmd : byName.values()) {
        if (!cmd.hidden()) {
            names.add(cmd.name());
            names.addAll(cmd.aliases());
        }
    }
    Collections.sort(names);
    return names;
}
```

---

## 6. CommandParser

静态工具类，解析斜杠输入。

```java
package com.maplecode.command;

public final class CommandParser {
    private CommandParser() {}

    /** 判断输入是否以 / 开头且后面紧跟非空格字符。 */
    public static boolean isCommand(String input) { ... }

    /**
     * 解析命令名：/ 后、首个空格前，转小写。
     * 输入 "/" → 返回 ""。
     * 输入 "/HELP args" → 返回 "help"。
     */
    public static String parseName(String input) { ... }

    /**
     * 解析参数：首个空格之后的部分，已 trim。
     * 无参数返回 ""。
     * 输入 "/review 重点关注并发" → 返回 "重点关注并发"。
     * 输入 "/clear" → 返回 ""。
     */
    public static String parseArgs(String input) { ... }
}
```

### 6.1 解析规则

| 输入 | isCommand | parseName | parseArgs |
|------|-----------|-----------|-----------|
| `""` | false | — | — |
| `hello` | false | — | — |
| `/` | false | — | — |
| `/help` | true | `"help"` | `""` |
| `/HELP` | true | `"help"` | `""` |
| `/review 重点` | true | `"review"` | `"重点"` |
| `/memory list` | true | `"memory"` | `"list"` |
| `/plan  分析这段代码` | true | `"plan"` | `"分析这段代码"` |

注意：`/` 后无内容（只有空格或空）不算命令。`parseName` 返回空串时，`registry.lookup("")` 返回 empty，走"未知命令"分支。

---

## 7. CommandCompleter

JLine `Completer` 实现，Tab 补全仅在行首触发。

```java
package com.maplecode.command;

import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.StringsCompleter;
import java.util.List;

public class CommandCompleter implements Completer {
    private final CommandRegistry registry;

    public CommandCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();

        // 只在行首的 / 开头触发
        if (!buffer.startsWith("/")) return;

        // 光标必须在第一个 word 上（命令名本身），不能在参数区域
        if (line.wordIndex() > 0) return;

        String partial = line.word().toLowerCase();
        for (String name : registry.completableNames()) {
            if (name.startsWith(partial)) {
                candidates.add(new Candidate("/" + name));
            }
        }
    }
}
```

### 7.1 补全行为

| 用户输入 | 光标位置 | 触发？ | 补全结果 |
|----------|----------|--------|----------|
| `/he` | 行首 | 是 | `/help`（单匹配直接补全） |
| `/c` | 行首 | 是 | `/clear`, `/compact`, `/cancel`（多匹配弹菜单） |
| `/review 看看 /cl` | `/cl` 处 | 否 | `wordIndex() > 0`，不触发 |
| `帮我跑 /he` | 行首 | 否 | `startsWith("/")` 为 false |
| `/hidden` | 行首 | 否 | `completableNames()` 已过滤隐藏命令 |

### 7.2 注册

在 `App.main` 创建 `LineReader` 时注册：

```java
reader = LineReaderBuilder.builder()
    .terminal(terminal)
    .completer(new CommandCompleter(cmdRegistry))
    .build();
```

---

## 8. ReplLoop 改造

### 8.1 构造器变更

新增参数 `CommandRegistry cmdRegistry`：

```java
public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                LineReader reader, ToolRegistry registry, ToolExecutor executor,
                PermissionEngine engine, AgentConfig agentConfig,
                SessionArchive sessionArchive, CompactCoordinator coord,
                MemoryManager memoryManager, StatusBar statusBar,
                CommandRegistry cmdRegistry)
```

### 8.2 CommandContextImpl 内部类

```java
private class CommandContextImpl implements CommandContext {
    @Override
    public void sendMessage(String message) { printer.info(message); }

    @Override
    public void sendError(String message) { printer.error(message); }

    @Override
    public void sendToAgent(String prompt) {
        agent.run(prompt, printer);
    }

    @Override
    public void cancelCurrentAgentRun() {
        agent.cancel();
    }

    @Override
    public boolean isAgentRunning() {
        return agent.isRunning();  // 需要在 AgentLoop 上暴露此方法
    }

    @Override
    public void setPlanMode(PlanMode mode) {
        agentConfig = agentConfig.withPlanMode(mode).withReminderState(State.initial());
        agent.updateConfig(agentConfig);
    }

    @Override
    public PlanMode getPlanMode() { return agentConfig.planMode(); }

    @Override
    public PermissionMode getPermissionMode() { return engine.mode(); }

    @Override
    public void setPermissionMode(PermissionMode mode) { engine.setMode(mode); }

    @Override
    public TokenUsage getTokenUsage() { return lastTokenUsage; }

    @Override
    public void updateStatusBar() {
        if (statusBar != null) {
            updateStatusBar(renderMode());
        }
    }

    @Override
    public String readLine(String prompt) { return reader.readLine(prompt); }

    @Override
    public ChatSession getSession() { return agent.session(); }

    @Override
    public AgentConfig getAgentConfig() { return agentConfig; }
}
```

### 8.3 run() 方法瘦身

原 ~300 行 if-else 链替换为 ~50 行分发器：

```java
public void run() {
    // banner（从 registry 生成 help 摘要）
    printer.banner("MapleCode — 输入 /help 查看可用命令");

    // statusBar 初始化（不变）
    if (statusBar != null) {
        updateStatusBar(renderMode());
        // WINCH handler...
    }

    CommandContextImpl commandContext = new CommandContextImpl();

    try {
        while (true) {
            String input = readMultiline();
            if (input == null) break;

            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            // ── 分流器 ──
            if (CommandParser.isCommand(trimmed)) {
                String name = CommandParser.parseName(trimmed);
                String args = CommandParser.parseArgs(trimmed);
                Optional<Command> cmd = cmdRegistry.lookup(name);

                if (cmd.isPresent()) {
                    try {
                        cmd.get().execute(args, commandContext);
                    } catch (ExitReplException e) {
                        break;  // /exit 正常退出
                    }
                } else {
                    printer.error("未知命令: /" + name + "。输入 /help 查看可用命令。");
                }
            } else {
                // 正常对话
                agent.run(trimmed, printer);

                // memory extract（不变）
                if (memoryManager != null && memoryManager.isEnabled()) {
                    memoryManager.extractAsync(agent.session().recentMessages(20));
                }
            }
        }
    } catch (UserInterruptException e) {
        agent.cancel();
    }

    // 退出清理（不变）
    if (sessionArchive != null) {
        sessionArchive.save(agent.session());
    }
}
```

---

## 9. App.main 装配

在 tool registry 创建之后、ReplLoop 创建之前，插入命令注册：

```java
// 现有: ToolRegistry 创建
// ↓ 新增
CommandRegistry cmdRegistry = new CommandRegistry();
cmdRegistry.register(new HelpCommand(cmdRegistry));
cmdRegistry.register(new ClearCommand(coord));
cmdRegistry.register(new NewCommand(sessionArchive, coord));
cmdRegistry.register(new ResumeCommand(sessionArchive));
cmdRegistry.register(new CompactCommand(coord));
cmdRegistry.register(new ToolsCommand(registry));
cmdRegistry.register(new PlanCommand());
cmdRegistry.register(new DoCommand());
cmdRegistry.register(new ModeCommand());
cmdRegistry.register(new CancelCommand());
cmdRegistry.register(new MemoryCommand(memoryManager));
cmdRegistry.register(new StatusCommand());
cmdRegistry.register(new ReviewCommand());
cmdRegistry.register(new ExitCommand());

// LineReader 创建时注册补全器
reader = LineReaderBuilder.builder()
    .terminal(terminal)
    .completer(new CommandCompleter(cmdRegistry))
    .build();

// 现有: ReplLoop 创建，传入 cmdRegistry
```

### 9.1 依赖注入顺序

各命令的框架组件依赖通过**构造器注入**，`CommandContext` 只暴露 UI/Agent/Mode/Session/交互 这些通用能力。`CommandRegistry` 提供 `register(Command)` 方法，内部做同样的冲突检测。

---

## 10. 14 个内置命令规格

### 10.0 依赖注入原则

`CommandContext` 是窄 facade，只暴露 UI/Agent/Mode/Session/交互 这些通用能力。各命令需要的框架组件依赖通过**构造器注入**：

| 命令 | 构造器注入的依赖 |
|------|------------------|
| `/help` | `CommandRegistry` |
| `/clear` | `CompactCoordinator`（nullable） |
| `/new` | `SessionArchive`（nullable）、`CompactCoordinator`（nullable） |
| `/resume` | `SessionArchive`（nullable） |
| `/compact` | `CompactCoordinator`（nullable） |
| `/tools` | `ToolRegistry` |
| `/memory` | `MemoryManager`（nullable） |
| 其余命令 | 无额外依赖，只用 `CommandContext` |

### 10.1 /help

| 属性 | 值 |
|------|-----|
| name | `help` |
| aliases | `["h", "?"]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/help [command]` |

**execute 逻辑**：
- args 为空 → 遍历 `registry.visible()`，按 `CommandType` 分组打印，每组显示 name + description
- args 非空 → `registry.lookup(args)`，找到则打印 usage + description，未找到则报错

### 10.2 /clear

| 属性 | 值 |
|------|-----|
| name | `clear` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/clear` |

**execute 逻辑**：
1. `ctx.getSession().clear()`
2. 重置 compact 计数器（通过构造器注入的 `CompactCoordinator`）
3. 清 token 用量
4. `ctx.updateStatusBar()`

### 10.3 /new

| 属性 | 值 |
|------|-----|
| name | `new` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/new` |

**execute 逻辑**：
1. 如果构造器注入的 `SessionArchive` 非 null，归档当前 session
2. `ctx.getSession().clear()`
3. 重置 compact 计数器（通过构造器注入的 `CompactCoordinator`）和 token 用量
4. `ctx.updateStatusBar()`

### 10.4 /resume

| 属性 | 值 |
|------|-----|
| name | `resume` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/resume [id]` |

**execute 逻辑**：
- 无参：列出最近 10 条归档（编号 + 摘要 + 相对时间），通过 `ctx.readLine("Select [1-N]: ")` 读取用户选择
- 有参：按 ID 前缀加载，`ctx.getSession().replaceAll(loaded)`

### 10.5 /compact

| 属性 | 值 |
|------|-----|
| name | `compact` |
| aliases | `[]` |
| type | `UI_STATE` |
| hidden | `false` |
| usage | `/compact` |

**execute 逻辑**：
1. 调构造器注入的 `CompactCoordinator.beforeRequest(CompactTrigger.MANUAL)`
2. 如果结果是 `ChangedOffloadOnly` 或 `ChangedFull`，替换 session
3. `ctx.updateStatusBar()`

### 10.6 /tools

| 属性 | 值 |
|------|-----|
| name | `tools` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/tools` |

**execute 逻辑**：
- 遍历构造器注入的 `ToolRegistry.all()`，打印每个 tool 的 name 和 description

### 10.7 /plan

| 属性 | 值 |
|------|-----|
| name | `plan` |
| aliases | `[]` |
| type | `PROMPT` |
| hidden | `false` |
| usage | `/plan <query>` |

**execute 逻辑**：
1. args 为空 → 报错提示用法
2. `ctx.setPlanMode(PlanMode.PLAN)`（内部会设 agentConfig + reset reminder state）
3. `ctx.sendToAgent(args)`

### 10.8 /do

| 属性 | 值 |
|------|-----|
| name | `do` |
| aliases | `[]` |
| type | `PROMPT` |
| hidden | `false` |
| usage | `/do` |

**execute 逻辑**（含防御）：
1. `if (ctx.getPlanMode() != PLAN)` → `ctx.sendError("当前不在计划模式。先用 /plan 进入计划模式。")`, return
2. `planText = lastAssistantText(ctx.getSession())` — private 方法，遍历 session 反向找最后一条 assistant text block
3. `if (planText == null || planText.isBlank())` → `ctx.sendError("没有找到计划内容。")`, return
4. `ctx.getSession().clear()`
5. `ctx.setPlanMode(PlanMode.NORMAL)`
6. `ctx.sendToAgent(planText)`

### 10.9 /mode

| 属性 | 值 |
|------|-----|
| name | `mode` |
| aliases | `[]` |
| type | `UI_STATE` |
| hidden | `false` |
| usage | `/mode [strict|default|permissive]` |

**execute 逻辑**：
- 无参 → 打印当前权限模式
- 有参 → 解析 `PermissionMode.valueOf(args.toUpperCase())`，调 `ctx.setPermissionMode(mode)` + `ctx.updateStatusBar()`
- 无效参数 → 报错提示用法

### 10.10 /cancel

| 属性 | 值 |
|------|-----|
| name | `cancel` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/cancel` |

**execute 逻辑**：
1. `ctx.cancelCurrentAgentRun()`
2. `ctx.setPlanMode(PlanMode.NORMAL)`
3. `ctx.updateStatusBar()`

### 10.11 /memory

| 属性 | 值 |
|------|-----|
| name | `memory` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/memory <list|clear|extract>` |

**execute 逻辑**：
- 构造器注入 `MemoryManager`（nullable）
- args = `"list"` → `memoryManager.listMemories()`
- args = `"clear"` → `memoryManager.clearAll()`
- args = `"extract"` → `memoryManager.extractSync(ctx.getSession().recentMessages(20))`
- 其他 → 报错提示用法

### 10.12 /status

| 属性 | 值 |
|------|-----|
| name | `status` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/status` |

**execute 逻辑**：
- 从 `ctx` 读取 model、token 用量、权限模式、plan 模式、cwd
- 格式化输出：

```
Model:    claude-sonnet-4-20250514
Tokens:   1.2k / 200k
Mode:     default
Plan:     normal
Cwd:      /Users/xxx/project
```

### 10.13 /review

| 属性 | 值 |
|------|-----|
| name | `review` |
| aliases | `[]` |
| type | `PROMPT` |
| hidden | `false` |
| usage | `/review [额外关注点]` |

**execute 逻辑**：
1. 跑 `git diff` 获取变更内容（通过 `ProcessBuilder`，工作目录为 `System.getProperty("user.dir")`）
2. diff 为空 → `ctx.sendMessage("没有检测到代码变更。")`, return
3. 拼装审查提示词模板（见下）
4. `ctx.sendToAgent(prompt)`

**提示词模板**：

```
你是一个资深的代码审查员。请审查以下 Git 代码变更。

审查维度：
1. 正确性：逻辑是否有 Bug？边界条件是否处理？
2. 安全性：是否存在注入风险、权限绕过、敏感信息泄露？
3. 性能：是否存在不必要的循环、内存泄漏、N+1 查询？
4. 可读性：命名是否规范？是否有必要的注释？

额外关注点（来自用户指令）：
{args}

代码变更内容：
{diff}

输出要求：
- 如果没有严重问题，简短说明变更意图并给予肯定。
- 如果发现问题，按严重程度（🔴 严重 / 🟡 警告 / 🔵 建议）列出。
- 针对每个问题，给出具体的代码行号和修改建议。
- 不要做泛泛的评价，必须基于 diff 内容。
```

args 为空时，"额外关注点"行显示"（无）"。

### 10.14 /exit

| 属性 | 值 |
|------|-----|
| name | `exit` |
| aliases | `[]` |
| type | `LOCAL` |
| hidden | `false` |
| usage | `/exit` |

**execute 逻辑**：
- 抛出 `new ExitReplException()`
- `ReplLoop.run()` 的 catch 块捕获，走正常退出流程（归档 session 等）

---

## 11. AgentLoop 补充

`CommandContext.isAgentRunning()` 需要 `AgentLoop` 暴露运行状态：

```java
// AgentLoop 新增
private volatile boolean running = false;

public boolean isAgentRunning() {
    return running;
}
```

在 `run()` 方法入口设 `running = true`，出口（包括异常）设 `running = false`。

---

## 12. 测试策略

| 测试目标 | 方式 |
|----------|------|
| `CommandParser` | 单元测试：各种输入 → isCommand/parseName/parseArgs |
| `CommandRegistry` | 单元测试：注册 → lookup；冲突 → 异常；completableNames → 排序 |
| `CommandCompleter` | 单元测试：mock ParsedLine，验证 candidates |
| 各 Command 实现 | 单元测试：mock CommandContext，验证 execute 行为 |
| 分发器集成 | 手工 smoke test：/help, /clear, /plan, /do, /review, /exit |

Command 实现测试模板（mock ctx）：

```java
@Test
void clearCommand_clearsSession() {
    ChatSession session = new ChatSession();
    session.appendUserText("hello");
    CommandContext ctx = mock(CommandContext.class);
    when(ctx.getSession()).thenReturn(session);

    new ClearCommand().execute("", ctx);

    assertEquals(0, session.size());
    verify(ctx).updateStatusBar();
}
```

---

## 13. 迁移路径

1. 创建 `com.maplecode.command` 包
2. 实现 `Command`、`CommandType`、`CommandContext`、`ExitReplException`
3. 实现 `CommandRegistry`、`CommandParser`
4. 实现 `CommandCompleter`
5. 逐个迁移现有命令（从 ReplLoop if-else 链中抽出）
6. 实现新命令（`/help`、`/status`、`/review`）
7. 改造 `ReplLoop`：构造器加参数、实现 `CommandContextImpl`、run() 瘦身
8. 改造 `App.main`：创建 registry、注册补全器
9. `AgentLoop` 暴露 `isRunning()`
10. 跑全部现有测试，确保不回归
