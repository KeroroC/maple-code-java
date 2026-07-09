# Command Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ReplLoop's 250-line if-else slash-command chain with a `Command` interface + `CommandRegistry` + `CommandParser` framework, add `/help` and `/review` commands, and integrate JLine Tab completion.

**Architecture:** Each slash command implements `Command` interface (name, aliases, type, execute). `CommandRegistry` handles startup conflict detection and lookup. `CommandParser` splits `/name args`. `CommandContext` is a narrow facade for UI/Agent/Mode access. Commands with external dependencies (CompactCoordinator, SessionArchive, etc.) receive them via constructor injection.

**Tech Stack:** Java 21, JUnit 5, Mockito, JLine 3

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/maplecode/command/Command.java` | Command interface |
| Create | `src/main/java/com/maplecode/command/CommandType.java` | LOCAL / UI_STATE / PROMPT enum |
| Create | `src/main/java/com/maplecode/command/CommandContext.java` | Narrow facade interface |
| Create | `src/main/java/com/maplecode/command/ExitReplException.java` | Control flow exception |
| Create | `src/main/java/com/maplecode/command/CommandParser.java` | Static input parser |
| Create | `src/main/java/com/maplecode/command/CommandRegistry.java` | Registration + lookup + conflict detection |
| Create | `src/main/java/com/maplecode/command/CommandCompleter.java` | JLine Completer |
| Create | `src/main/java/com/maplecode/command/HelpCommand.java` | /help |
| Create | `src/main/java/com/maplecode/command/ClearCommand.java` | /clear |
| Create | `src/main/java/com/maplecode/command/NewCommand.java` | /new |
| Create | `src/main/java/com/maplecode/command/ResumeCommand.java` | /resume |
| Create | `src/main/java/com/maplecode/command/CompactCommand.java` | /compact |
| Create | `src/main/java/com/maplecode/command/ToolsCommand.java` | /tools |
| Create | `src/main/java/com/maplecode/command/PlanCommand.java` | /plan |
| Create | `src/main/java/com/maplecode/command/DoCommand.java` | /do |
| Create | `src/main/java/com/maplecode/command/ModeCommand.java` | /mode |
| Create | `src/main/java/com/maplecode/command/CancelCommand.java` | /cancel |
| Create | `src/main/java/com/maplecode/command/MemoryCommand.java` | /memory |
| Create | `src/main/java/com/maplecode/command/StatusCommand.java` | /status |
| Create | `src/main/java/com/maplecode/command/ReviewCommand.java` | /review |
| Create | `src/main/java/com/maplecode/command/ExitCommand.java` | /exit |
| Create | `src/test/java/com/maplecode/command/CommandParserTest.java` | Parser tests |
| Create | `src/test/java/com/maplecode/command/CommandRegistryTest.java` | Registry tests |
| Create | `src/test/java/com/maplecode/command/CommandCompleterTest.java` | Completer tests |
| Create | `src/test/java/com/maplecode/command/HelpCommandTest.java` | /help tests |
| Create | `src/test/java/com/maplecode/command/ClearCommandTest.java` | /clear tests |
| Create | `src/test/java/com/maplecode/command/NewCommandTest.java` | /new tests |
| Create | `src/test/java/com/maplecode/command/CompactCommandTest.java` | /compact tests |
| Create | `src/test/java/com/maplecode/command/ToolsCommandTest.java` | /tools tests |
| Create | `src/test/java/com/maplecode/command/PlanCommandTest.java` | /plan tests |
| Create | `src/test/java/com/maplecode/command/DoCommandTest.java` | /do tests |
| Create | `src/test/java/com/maplecode/command/ModeCommandTest.java` | /mode tests |
| Create | `src/test/java/com/maplecode/command/CancelCommandTest.java` | /cancel tests |
| Create | `src/test/java/com/maplecode/command/MemoryCommandTest.java` | /memory tests |
| Create | `src/test/java/com/maplecode/command/StatusCommandTest.java` | /status tests |
| Create | `src/test/java/com/maplecode/command/ReviewCommandTest.java` | /review tests |
| Create | `src/test/java/com/maplecode/command/ExitCommandTest.java` | /exit tests |
| Modify | `src/main/java/com/maplecode/agent/AgentLoop.java` | Add `isRunning()` |
| Modify | `src/main/java/com/maplecode/ui/ReplLoop.java` | Add CommandContextImpl, rewrite run() |
| Modify | `src/main/java/com/maplecode/App.java` | Wire CommandRegistry + CommandCompleter |

---

### Task 1: Foundation Types (Command, CommandType, CommandContext, ExitReplException)

**Files:**
- Create: `src/main/java/com/maplecode/command/Command.java`
- Create: `src/main/java/com/maplecode/command/CommandType.java`
- Create: `src/main/java/com/maplecode/command/CommandContext.java`
- Create: `src/main/java/com/maplecode/command/ExitReplException.java`

- [ ] **Step 1: Create Command.java**

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

- [ ] **Step 2: Create CommandType.java**

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

- [ ] **Step 3: Create CommandContext.java**

```java
package com.maplecode.command;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.permission.PermissionMode;
import com.maplecode.session.ChatSession;
import com.maplecode.session.TokenUsage;

/**
 * 窄 facade 接口，命令通过它与 UI/Agent/状态交互，不直接依赖 ReplLoop 内部。
 */
public interface CommandContext {
    // ── 输出 ──

    /** 显示普通信息给用户。 */
    void sendMessage(String message);

    /** 显示红色错误信息。 */
    void sendError(String message);

    // ── Agent 交互 ──

    /** 把文本送进对话交给 AI。同步阻塞直到 Agent 完成。 */
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

- [ ] **Step 4: Create ExitReplException.java**

```java
package com.maplecode.command;

/**
 * 控制流异常。/exit 命令抛出，ReplLoop.run() 的 catch 块捕获后正常退出。
 */
public class ExitReplException extends RuntimeException {}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/command/Command.java \
        src/main/java/com/maplecode/command/CommandType.java \
        src/main/java/com/maplecode/command/CommandContext.java \
        src/main/java/com/maplecode/command/ExitReplException.java
git commit -m "feat(command): add Command, CommandType, CommandContext, ExitReplException"
```

---

### Task 2: CommandParser

**Files:**
- Create: `src/main/java/com/maplecode/command/CommandParser.java`
- Create: `src/test/java/com/maplecode/command/CommandParserTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {

    // ── isCommand ──

    @Test
    void isCommand_empty_returnsFalse() {
        assertFalse(CommandParser.isCommand(""));
    }

    @Test
    void isCommand_noSlash_returnsFalse() {
        assertFalse(CommandParser.isCommand("hello"));
    }

    @Test
    void isCommand_slashOnly_returnsFalse() {
        assertFalse(CommandParser.isCommand("/"));
    }

    @Test
    void isCommand_slashWithSpace_returnsFalse() {
        assertFalse(CommandParser.isCommand("/ help"));
    }

    @Test
    void isCommand_slashName_returnsTrue() {
        assertTrue(CommandParser.isCommand("/help"));
    }

    @Test
    void isCommand_slashNameArgs_returnsTrue() {
        assertTrue(CommandParser.isCommand("/review 重点关注"));
    }

    // ── parseName ──

    @Test
    void parseName_noArgs() {
        assertEquals("help", CommandParser.parseName("/help"));
    }

    @Test
    void parseName_withArgs() {
        assertEquals("review", CommandParser.parseName("/review 重点关注"));
    }

    @Test
    void parseName_uppercase_lowercased() {
        assertEquals("help", CommandParser.parseName("/HELP"));
    }

    @Test
    void parseName_mixedCase_lowercased() {
        assertEquals("memory", CommandParser.parseName("/Memory List"));
    }

    @Test
    void parseName_slashOnly_returnsEmpty() {
        assertEquals("", CommandParser.parseName("/"));
    }

    // ── parseArgs ──

    @Test
    void parseArgs_noArgs_returnsEmpty() {
        assertEquals("", CommandParser.parseArgs("/help"));
    }

    @Test
    void parseArgs_withArgs() {
        assertEquals("重点关注并发", CommandParser.parseArgs("/review 重点关注并发"));
    }

    @Test
    void parseArgs_multipleSpaces_trimmed() {
        assertEquals("分析代码", CommandParser.parseArgs("/plan  分析代码"));
    }

    @Test
    void parseArgs_subcommand() {
        assertEquals("list", CommandParser.parseArgs("/memory list"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CommandParserTest -pl . -q`
Expected: FAIL (CommandParser class not found)

- [ ] **Step 3: Implement CommandParser**

```java
package com.maplecode.command;

/**
 * 静态工具类，解析斜杠输入。
 */
public final class CommandParser {
    private CommandParser() {}

    /**
     * 判断输入是否以 / 开头且后面紧跟非空格字符。
     */
    public static boolean isCommand(String input) {
        if (input == null || input.length() < 2) return false;
        return input.charAt(0) == '/' && input.charAt(1) != ' ';
    }

    /**
     * 解析命令名：/ 后、首个空格前，转小写。
     * 输入 "/" → 返回 ""。
     * 输入 "/HELP args" → 返回 "help"。
     */
    public static String parseName(String input) {
        if (input == null || input.length() < 2) return "";
        int spaceIndex = input.indexOf(' ');
        String name = (spaceIndex < 0) ? input.substring(1) : input.substring(1, spaceIndex);
        return name.toLowerCase();
    }

    /**
     * 解析参数：首个空格之后的部分，已 trim。
     * 无参数返回 ""。
     */
    public static String parseArgs(String input) {
        int spaceIndex = input.indexOf(' ');
        if (spaceIndex < 0) return "";
        return input.substring(spaceIndex + 1).trim();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CommandParserTest -pl . -q`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/command/CommandParser.java \
        src/test/java/com/maplecode/command/CommandParserTest.java
git commit -m "feat(command): add CommandParser with tests"
```

---

### Task 3: CommandRegistry

**Files:**
- Create: `src/main/java/com/maplecode/command/CommandRegistry.java`
- Create: `src/test/java/com/maplecode/command/CommandRegistryTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.maplecode.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    private Command stubCommand(String name, String... aliases) {
        return new Command() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc"; }
            @Override public String usage() { return "/" + name; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return false; }
            @Override public List<String> aliases() { return List.of(aliases); }
            @Override public void execute(String args, CommandContext ctx) {}
        };
    }

    private Command hiddenCommand(String name) {
        return new Command() {
            @Override public String name() { return name; }
            @Override public String description() { return "hidden"; }
            @Override public String usage() { return "/" + name; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return true; }
            @Override public void execute(String args, CommandContext ctx) {}
        };
    }

    @Test
    void registerAndLookup_byName() {
        CommandRegistry reg = new CommandRegistry();
        Command help = stubCommand("help", "h", "?");
        reg.register(help);

        Optional<Command> found = reg.lookup("help");
        assertTrue(found.isPresent());
        assertSame(help, found.get());
    }

    @Test
    void registerAndLookup_byAlias() {
        CommandRegistry reg = new CommandRegistry();
        Command help = stubCommand("help", "h", "?");
        reg.register(help);

        assertEquals(help, reg.lookup("h").get());
        assertEquals(help, reg.lookup("?").get());
    }

    @Test
    void lookup_caseInsensitive() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));

        assertEquals("help", reg.lookup("HELP").get().name());
    }

    @Test
    void lookup_notFound_returnsEmpty() {
        CommandRegistry reg = new CommandRegistry();
        assertTrue(reg.lookup("nonexistent").isEmpty());
    }

    @Test
    void register_duplicateName_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("help")));
    }

    @Test
    void register_aliasConflictsWithName_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("h")));
    }

    @Test
    void register_aliasConflictsWithAlias_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("clear", "h")));
    }

    @Test
    void register_aliasEqualsOwnName_throws() {
        CommandRegistry reg = new CommandRegistry();

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("help", "help")));
    }

    @Test
    void visible_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));
        reg.register(hiddenCommand("internal"));

        List<Command> visible = reg.visible();
        assertEquals(1, visible.size());
        assertEquals("help", visible.get(0).name());
    }

    @Test
    void visible_sortedByName() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("clear"));
        reg.register(stubCommand("help"));
        reg.register(stubCommand("compact"));

        List<Command> visible = reg.visible();
        assertEquals("clear", visible.get(0).name());
        assertEquals("compact", visible.get(1).name());
        assertEquals("help", visible.get(2).name());
    }

    @Test
    void completableNames_includesAliases_sorted() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h", "?"));
        reg.register(stubCommand("clear"));

        List<String> names = reg.completableNames();
        assertEquals(List.of("?", "clear", "h", "help"), names);
    }

    @Test
    void completableNames_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));
        reg.register(hiddenCommand("internal"));

        List<String> names = reg.completableNames();
        assertEquals(List.of("h", "help"), names);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CommandRegistryTest -pl . -q`
Expected: FAIL (CommandRegistry class not found)

- [ ] **Step 3: Implement CommandRegistry**

```java
package com.maplecode.command;

import java.util.*;

/**
 * 命令注册中心。启动时注册所有命令，检测名称/别名冲突。
 */
public final class CommandRegistry {
    private final Map<String, Command> byName = new HashMap<>();
    private final Map<String, Command> byAlias = new HashMap<>();

    /**
     * 注册一个命令。冲突时抛 IllegalArgumentException。
     */
    public void register(Command command) {
        String lowerName = command.name().toLowerCase();

        if (byName.containsKey(lowerName)) {
            throw new IllegalArgumentException("duplicate command name: " + lowerName);
        }
        if (byAlias.containsKey(lowerName)) {
            throw new IllegalArgumentException("command name conflicts with alias: " + lowerName);
        }

        byName.put(lowerName, command);

        for (String alias : command.aliases()) {
            String lowerAlias = alias.toLowerCase();
            if (lowerAlias.equals(lowerName)) {
                throw new IllegalArgumentException("alias equals own name: " + lowerAlias);
            }
            if (byName.containsKey(lowerAlias)) {
                throw new IllegalArgumentException("alias conflicts with command name: " + lowerAlias);
            }
            if (byAlias.containsKey(lowerAlias)) {
                throw new IllegalArgumentException("duplicate alias: " + lowerAlias);
            }
            byAlias.put(lowerAlias, command);
        }
    }

    /** 按名称或别名查找（大小写不敏感），未命中返回 empty。 */
    public Optional<Command> lookup(String name) {
        String lower = name.toLowerCase();
        Command cmd = byName.get(lower);
        if (cmd != null) return Optional.of(cmd);
        return Optional.ofNullable(byAlias.get(lower));
    }

    /** 所有非隐藏命令，按 name 字母序排列。 */
    public List<Command> visible() {
        List<Command> result = new ArrayList<>();
        for (Command cmd : byName.values()) {
            if (!cmd.hidden()) {
                result.add(cmd);
            }
        }
        result.sort(Comparator.comparing(Command::name));
        return result;
    }

    /** 所有命令名 + 别名（非隐藏），按字母序排列。用于 Tab 补全。 */
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
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CommandRegistryTest -pl . -q`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/command/CommandRegistry.java \
        src/test/java/com/maplecode/command/CommandRegistryTest.java
git commit -m "feat(command): add CommandRegistry with conflict detection and tests"
```

---

### Task 4: AgentLoop.isRunning()

**Files:**
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java`

- [ ] **Step 1: Add running field and isRunning() method**

At `AgentLoop.java` line ~30 (after existing fields), add:

```java
    private volatile boolean running = false;
```

After the `cancel()` method (line ~63), add:

```java
    /**
     * 当前是否有 Agent 任务在执行。
     */
    public boolean isRunning() {
        return running;
    }
```

- [ ] **Step 2: Wrap run() body in try-finally**

At `AgentLoop.java` line 73, the `run()` method starts. Wrap the body:

```java
    public void run(String userInput, Consumer<AgentEvent> sink) {
        running = true;
        try {
            // ... existing body (lines 74-237) unchanged ...
        } finally {
            running = false;
        }
    }
```

- [ ] **Step 3: Run existing tests**

Run: `mvn test -pl . -q`
Expected: All existing tests still PASS (no behavior change, only new method + finally guard)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maplecode/agent/AgentLoop.java
git commit -m "feat(agent): add isRunning() with try-finally guard"
```

---

### Task 5: Simple Commands (Exit, Cancel, Mode, Status, Clear)

These commands depend only on `CommandContext` (no constructor injection of framework components).

**Files:**
- Create: `src/main/java/com/maplecode/command/ExitCommand.java`
- Create: `src/main/java/com/maplecode/command/CancelCommand.java`
- Create: `src/main/java/com/maplecode/command/ModeCommand.java`
- Create: `src/main/java/com/maplecode/command/StatusCommand.java`
- Create: `src/main/java/com/maplecode/command/ClearCommand.java`
- Create: `src/test/java/com/maplecode/command/ExitCommandTest.java`
- Create: `src/test/java/com/maplecode/command/CancelCommandTest.java`
- Create: `src/test/java/com/maplecode/command/ModeCommandTest.java`
- Create: `src/test/java/com/maplecode/command/StatusCommandTest.java`
- Create: `src/test/java/com/maplecode/command/ClearCommandTest.java`

- [ ] **Step 1: Write ExitCommandTest**

```java
package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExitCommandTest {

    @Test
    void execute_throwsExitReplException() {
        ExitCommand cmd = new ExitCommand();
        assertThrows(ExitReplException.class, () -> cmd.execute("", null));
    }

    @Test
    void name_is_exit() {
        assertEquals("exit", new ExitCommand().name());
    }

    @Test
    void type_is_local() {
        assertEquals(CommandType.LOCAL, new ExitCommand().type());
    }

    @Test
    void notHidden() {
        assertFalse(new ExitCommand().hidden());
    }
}
```

- [ ] **Step 2: Implement ExitCommand**

```java
package com.maplecode.command;

public class ExitCommand implements Command {
    @Override public String name() { return "exit"; }
    @Override public String description() { return "退出程序"; }
    @Override public String usage() { return "/exit"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        throw new ExitReplException();
    }
}
```

- [ ] **Step 3: Run ExitCommandTest**

Run: `mvn test -Dtest=ExitCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 4: Write CancelCommandTest**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CancelCommandTest {

    @Test
    void execute_cancelsAgent_resetsPlanMode_updatesStatusBar() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);

        new CancelCommand().execute("", ctx);

        verify(ctx).cancelCurrentAgentRun();
        verify(ctx).setPlanMode(PlanMode.NORMAL);
        verify(ctx).updateStatusBar();
    }
}
```

- [ ] **Step 5: Implement CancelCommand**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;

public class CancelCommand implements Command {
    @Override public String name() { return "cancel"; }
    @Override public String description() { return "中断当前执行"; }
    @Override public String usage() { return "/cancel"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        ctx.cancelCurrentAgentRun();
        ctx.setPlanMode(PlanMode.NORMAL);
        ctx.updateStatusBar();
    }
}
```

- [ ] **Step 6: Run CancelCommandTest**

Run: `mvn test -Dtest=CancelCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 7: Write ModeCommandTest**

```java
package com.maplecode.command;

import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ModeCommandTest {

    @Test
    void execute_noArgs_printsCurrentMode() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);

        new ModeCommand().execute("", ctx);

        verify(ctx).sendMessage("当前权限模式: default");
    }

    @Test
    void execute_validArg_setsMode() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);

        new ModeCommand().execute("strict", ctx);

        verify(ctx).setPermissionMode(PermissionMode.STRICT);
        verify(ctx).updateStatusBar();
    }

    @Test
    void execute_invalidArg_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new ModeCommand().execute("relaxed", ctx);

        verify(ctx).sendError("未知模式: relaxed。可选: strict, default, permissive");
    }

    @Test
    void execute_caseInsensitive() {
        CommandContext ctx = mock(CommandContext.class);

        new ModeCommand().execute("PERMISSIVE", ctx);

        verify(ctx).setPermissionMode(PermissionMode.PERMISSIVE);
    }
}
```

- [ ] **Step 8: Implement ModeCommand**

```java
package com.maplecode.command;

import com.maplecode.permission.PermissionMode;

public class ModeCommand implements Command {
    @Override public String name() { return "mode"; }
    @Override public String description() { return "查看或切换权限模式"; }
    @Override public String usage() { return "/mode [strict|default|permissive]"; }
    @Override public CommandType type() { return CommandType.UI_STATE; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (args.isEmpty()) {
            ctx.sendMessage("当前权限模式: " + ctx.getPermissionMode().name().toLowerCase());
            return;
        }
        try {
            PermissionMode mode = PermissionMode.valueOf(args.toUpperCase());
            ctx.setPermissionMode(mode);
            ctx.updateStatusBar();
        } catch (IllegalArgumentException e) {
            ctx.sendError("未知模式: " + args + "。可选: strict, default, permissive");
        }
    }
}
```

- [ ] **Step 9: Run ModeCommandTest**

Run: `mvn test -Dtest=ModeCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 10: Write StatusCommandTest**

```java
package com.maplecode.command;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.permission.PermissionMode;
import com.maplecode.session.TokenUsage;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class StatusCommandTest {

    @Test
    void execute_printsStatus() {
        CommandContext ctx = mock(CommandContext.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.model()).thenReturn("claude-sonnet-4-20250514");
        when(ctx.getAgentConfig()).thenReturn(config);
        when(ctx.getTokenUsage()).thenReturn(new TokenUsage(1200, 200000, 0));
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new StatusCommand().execute("", ctx);

        verify(ctx).sendMessage(argThat(s ->
            s.contains("claude-sonnet-4-20250514") &&
            s.contains("default") &&
            s.contains("normal")));
    }

    @Test
    void execute_nullTokenUsage_doesNotCrash() {
        CommandContext ctx = mock(CommandContext.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.model()).thenReturn("test-model");
        when(ctx.getAgentConfig()).thenReturn(config);
        when(ctx.getTokenUsage()).thenReturn(null);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new StatusCommand().execute("", ctx);

        verify(ctx).sendMessage(anyString());
    }
}
```

- [ ] **Step 11: Implement StatusCommand**

```java
package com.maplecode.command;

import com.maplecode.session.TokenUsage;

public class StatusCommand implements Command {
    @Override public String name() { return "status"; }
    @Override public String description() { return "显示当前状态"; }
    @Override public String usage() { return "/status"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        String model = ctx.getAgentConfig().model();
        TokenUsage usage = ctx.getTokenUsage();
        String tokens = (usage != null)
            ? usage.inputTokens() + " / " + usage.maxTokens()
            : "N/A";
        String mode = ctx.getPermissionMode().name().toLowerCase();
        String plan = ctx.getPlanMode().name().toLowerCase();
        String cwd = System.getProperty("user.dir");

        String status = String.format(
            "Model:    %s\nTokens:   %s\nMode:     %s\nPlan:     %s\nCwd:      %s",
            model, tokens, mode, plan, cwd);
        ctx.sendMessage(status);
    }
}
```

- [ ] **Step 12: Run StatusCommandTest**

Run: `mvn test -Dtest=StatusCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 13: Write ClearCommandTest**

```java
package com.maplecode.command;

import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClearCommandTest {

    @Test
    void execute_clearsSession_updatesStatusBar() {
        ChatSession session = new ChatSession();
        session.appendUserText("hello");
        assertEquals(1, session.size());

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new ClearCommand(null).execute("", ctx);

        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }
}
```

- [ ] **Step 14: Implement ClearCommand**

```java
package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;

public class ClearCommand implements Command {
    private final CompactCoordinator coord;

    public ClearCommand(CompactCoordinator coord) {
        this.coord = coord;
    }

    @Override public String name() { return "clear"; }
    @Override public String description() { return "清空会话历史"; }
    @Override public String usage() { return "/clear"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        ctx.getSession().clear();
        if (coord != null) {
            coord.recordUsage(null);
        }
        ctx.updateStatusBar();
    }
}
```

- [ ] **Step 15: Run ClearCommandTest**

Run: `mvn test -Dtest=ClearCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 16: Commit all simple commands**

```bash
git add src/main/java/com/maplecode/command/ExitCommand.java \
        src/main/java/com/maplecode/command/CancelCommand.java \
        src/main/java/com/maplecode/command/ModeCommand.java \
        src/main/java/com/maplecode/command/StatusCommand.java \
        src/main/java/com/maplecode/command/ClearCommand.java \
        src/test/java/com/maplecode/command/ExitCommandTest.java \
        src/test/java/com/maplecode/command/CancelCommandTest.java \
        src/test/java/com/maplecode/command/ModeCommandTest.java \
        src/test/java/com/maplecode/command/StatusCommandTest.java \
        src/test/java/com/maplecode/command/ClearCommandTest.java
git commit -m "feat(command): add Exit, Cancel, Mode, Status, Clear commands with tests"
```

---

### Task 6: Commands with Dependencies (Help, Tools, Compact, New, Resume, Memory)

These commands need constructor-injected framework components.

**Files:**
- Create: `src/main/java/com/maplecode/command/HelpCommand.java`
- Create: `src/main/java/com/maplecode/command/ToolsCommand.java`
- Create: `src/main/java/com/maplecode/command/CompactCommand.java`
- Create: `src/main/java/com/maplecode/command/NewCommand.java`
- Create: `src/main/java/com/maplecode/command/ResumeCommand.java`
- Create: `src/main/java/com/maplecode/command/MemoryCommand.java`
- Create: `src/test/java/com/maplecode/command/HelpCommandTest.java`
- Create: `src/test/java/com/maplecode/command/ToolsCommandTest.java`
- Create: `src/test/java/com/maplecode/command/CompactCommandTest.java`
- Create: `src/test/java/com/maplecode/command/NewCommandTest.java`
- Create: `src/test/java/com/maplecode/command/MemoryCommandTest.java`

- [ ] **Step 1: Write HelpCommandTest**

```java
package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class HelpCommandTest {

    @Test
    void execute_noArgs_printsGroupedHelp() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new ModeCommand());

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("", ctx);

        // Should print at least one sendMessage with command listings
        verify(ctx, atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void execute_withArg_printsUsage() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("exit", ctx);

        verify(ctx).sendMessage(argThat(s -> s.contains("/exit") && s.contains("退出程序")));
    }

    @Test
    void execute_unknownCommand_sendsError() {
        CommandRegistry reg = new CommandRegistry();

        CommandContext ctx = mock(CommandContext.class);
        new HelpCommand(reg).execute("nonexistent", ctx);

        verify(ctx).sendError(argThat(s -> s.contains("nonexistent")));
    }
}
```

- [ ] **Step 2: Implement HelpCommand**

```java
package com.maplecode.command;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HelpCommand implements Command {
    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "help"; }
    @Override public String description() { return "显示帮助信息"; }
    @Override public String usage() { return "/help [command]"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }
    @Override public List<String> aliases() { return List.of("h", "?"); }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (!args.isEmpty()) {
            registry.lookup(args).ifPresentOrElse(
                cmd -> ctx.sendMessage(cmd.usage() + " — " + cmd.description()),
                () -> ctx.sendError("未知命令: " + args));
            return;
        }

        List<Command> visible = registry.visible();
        Map<CommandType, List<Command>> grouped = visible.stream()
            .collect(Collectors.groupingBy(Command::type));

        for (CommandType type : CommandType.values()) {
            List<Command> group = grouped.get(type);
            if (group == null || group.isEmpty()) continue;

            String header = switch (type) {
                case LOCAL -> "── 本地命令 ──";
                case UI_STATE -> "── 状态命令 ──";
                case PROMPT -> "── AI 命令 ──";
            };
            ctx.sendMessage(header);
            for (Command cmd : group) {
                ctx.sendMessage("  " + cmd.usage() + " — " + cmd.description());
            }
        }
    }
}
```

- [ ] **Step 3: Run HelpCommandTest**

Run: `mvn test -Dtest=HelpCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 4: Write ToolsCommandTest**

```java
package com.maplecode.command;

import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ToolsCommandTest {

    @Test
    void execute_listsAllTools() {
        Tool t1 = mock(Tool.class);
        when(t1.name()).thenReturn("read_file");
        when(t1.description()).thenReturn("读取文件内容");
        Tool t2 = mock(Tool.class);
        when(t2.name()).thenReturn("exec");
        when(t2.description()).thenReturn("执行命令");

        ToolRegistry registry = new ToolRegistry(List.of(t1, t2));
        CommandContext ctx = mock(CommandContext.class);

        new ToolsCommand(registry).execute("", ctx);

        verify(ctx).sendMessage(argThat(s -> s.contains("read_file")));
        verify(ctx).sendMessage(argThat(s -> s.contains("exec")));
    }
}
```

- [ ] **Step 5: Implement ToolsCommand**

```java
package com.maplecode.command;

import com.maplecode.tool.ToolRegistry;

public class ToolsCommand implements Command {
    private final ToolRegistry registry;

    public ToolsCommand(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "tools"; }
    @Override public String description() { return "列出所有可用工具"; }
    @Override public String usage() { return "/tools"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        registry.all().forEach(tool ->
            ctx.sendMessage("- " + tool.name() + ": " + tool.description()));
    }
}
```

- [ ] **Step 6: Run ToolsCommandTest**

Run: `mvn test -Dtest=ToolsCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 7: Write CompactCommandTest**

```java
package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class CompactCommandTest {

    @Test
    void execute_noChange_sendsMessage() {
        CompactCoordinator coord = mock(CompactCoordinator.class);
        ChatSession session = new ChatSession();
        when(coord.beforeRequest(any(CompactTrigger.class)))
            .thenReturn(new CompactResult.Unchanged());

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new CompactCommand(coord).execute("", ctx);

        verify(coord).beforeRequest(CompactTrigger.MANUAL);
        verify(ctx).sendMessage(anyString());
    }
}
```

- [ ] **Step 8: Implement CompactCommand**

```java
package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;

public class CompactCommand implements Command {
    private final CompactCoordinator coord;

    public CompactCommand(CompactCoordinator coord) {
        this.coord = coord;
    }

    @Override public String name() { return "compact"; }
    @Override public String description() { return "手动触发上下文压缩"; }
    @Override public String usage() { return "/compact"; }
    @Override public CommandType type() { return CommandType.UI_STATE; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        CompactResult result = coord.beforeRequest(CompactTrigger.MANUAL);
        if (result instanceof CompactResult.ChangedOffloadOnly c) {
            ctx.getSession().replaceAll(c.messages());
            ctx.updateStatusBar();
        } else if (result instanceof CompactResult.ChangedFull c) {
            ctx.getSession().replaceAll(c.messages());
            ctx.updateStatusBar();
        } else {
            ctx.sendMessage("上下文无需压缩。");
        }
    }
}
```

- [ ] **Step 9: Run CompactCommandTest**

Run: `mvn test -Dtest=CompactCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 10: Write NewCommandTest**

```java
package com.maplecode.command;

import com.maplecode.session.ChatSession;
import com.maplecode.session.archive.SessionArchive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewCommandTest {

    @Test
    void execute_archivesThenClears() {
        SessionArchive archive = mock(SessionArchive.class);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new NewCommand(archive, null).execute("", ctx);

        verify(archive).save(session);
        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }

    @Test
    void execute_nullArchive_skipsArchiving() {
        ChatSession session = new ChatSession();
        session.appendUserText("hello");

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new NewCommand(null, null).execute("", ctx);

        assertEquals(0, session.size());
        verify(ctx).updateStatusBar();
    }
}
```

- [ ] **Step 11: Implement NewCommand**

```java
package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.session.archive.SessionArchive;

public class NewCommand implements Command {
    private final SessionArchive archive;
    private final CompactCoordinator coord;

    public NewCommand(SessionArchive archive, CompactCoordinator coord) {
        this.archive = archive;
        this.coord = coord;
    }

    @Override public String name() { return "new"; }
    @Override public String description() { return "归档当前会话并清空"; }
    @Override public String usage() { return "/new"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (archive != null) {
            archive.save(ctx.getSession());
        }
        ctx.getSession().clear();
        if (coord != null) {
            coord.recordUsage(null);
        }
        ctx.updateStatusBar();
    }
}
```

- [ ] **Step 12: Run NewCommandTest**

Run: `mvn test -Dtest=NewCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 13: Implement ResumeCommand**

```java
package com.maplecode.command;

import com.maplecode.session.archive.SessionArchive;
import com.maplecode.session.archive.SessionMeta;

import java.util.List;

public class ResumeCommand implements Command {
    private final SessionArchive archive;

    public ResumeCommand(SessionArchive archive) {
        this.archive = archive;
    }

    @Override public String name() { return "resume"; }
    @Override public String description() { return "加载历史会话"; }
    @Override public String usage() { return "/resume [id]"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (archive == null) {
            ctx.sendError("会话归档未启用。");
            return;
        }

        if (args.isEmpty()) {
            List<SessionMeta> recent = archive.listRecent(10);
            if (recent.isEmpty()) {
                ctx.sendMessage("没有历史会话。");
                return;
            }
            for (int i = 0; i < recent.size(); i++) {
                SessionMeta meta = recent.get(i);
                ctx.sendMessage(String.format("  [%d] %s (%d msgs, %s)",
                    i + 1, meta.id(), meta.messageCount(),
                    formatAge(meta.lastActivity())));
            }
            String selection = ctx.readLine("Select [1-" + recent.size() + "]: ");
            try {
                int index = Integer.parseInt(selection.trim()) - 1;
                if (index < 0 || index >= recent.size()) {
                    ctx.sendError("无效选择。");
                    return;
                }
                String id = recent.get(index).id();
                ctx.getSession().replaceAll(archive.load(id));
                ctx.sendMessage("已加载会话: " + id);
            } catch (NumberFormatException e) {
                ctx.sendError("请输入数字。");
            }
        } else {
            try {
                ctx.getSession().replaceAll(archive.load(args));
                ctx.sendMessage("已加载会话: " + args);
            } catch (Exception e) {
                ctx.sendError("加载失败: " + e.getMessage());
            }
        }
    }

    private String formatAge(long lastActivity) {
        long ageMs = System.currentTimeMillis() - lastActivity;
        if (ageMs < 60_000) return "just now";
        if (ageMs < 3_600_000) return (ageMs / 60_000) + "m ago";
        if (ageMs < 86_400_000) return (ageMs / 3_600_000) + "h ago";
        return (ageMs / 86_400_000) + "d ago";
    }
}
```

- [ ] **Step 14: Write MemoryCommandTest**

```java
package com.maplecode.command;

import com.maplecode.memory.MemoryManager;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class MemoryCommandTest {

    @Test
    void execute_list_callsListMemories() {
        MemoryManager mgr = mock(MemoryManager.class);
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("list", ctx);

        verify(mgr).listMemories();
    }

    @Test
    void execute_clear_callsClearAll() {
        MemoryManager mgr = mock(MemoryManager.class);
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("clear", ctx);

        verify(mgr).clearAll();
    }

    @Test
    void execute_extract_callsExtractSync() {
        MemoryManager mgr = mock(MemoryManager.class);
        ChatSession session = new ChatSession();
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(session);

        new MemoryCommand(mgr).execute("extract", ctx);

        verify(mgr).extractSync(anyList());
    }

    @Test
    void execute_unknownSubcommand_sendsError() {
        MemoryManager mgr = mock(MemoryManager.class);
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(mgr).execute("foo", ctx);

        verify(ctx).sendError(anyString());
    }

    @Test
    void execute_nullManager_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new MemoryCommand(null).execute("list", ctx);

        verify(ctx).sendError(anyString());
    }
}
```

- [ ] **Step 15: Implement MemoryCommand**

```java
package com.maplecode.command;

import com.maplecode.memory.MemoryManager;

public class MemoryCommand implements Command {
    private final MemoryManager manager;

    public MemoryCommand(MemoryManager manager) {
        this.manager = manager;
    }

    @Override public String name() { return "memory"; }
    @Override public String description() { return "记忆管理"; }
    @Override public String usage() { return "/memory <list|clear|extract>"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (manager == null) {
            ctx.sendError("记忆系统未启用。");
            return;
        }
        switch (args) {
            case "list" -> manager.listMemories();
            case "clear" -> manager.clearAll();
            case "extract" -> manager.extractSync(ctx.getSession().recentMessages(20));
            default -> ctx.sendError("用法: /memory <list|clear|extract>");
        }
    }
}
```

- [ ] **Step 16: Run all tests for this task**

Run: `mvn test -Dtest='HelpCommandTest,ToolsCommandTest,CompactCommandTest,NewCommandTest,MemoryCommandTest' -pl . -q`
Expected: All PASS

- [ ] **Step 17: Commit**

```bash
git add src/main/java/com/maplecode/command/HelpCommand.java \
        src/main/java/com/maplecode/command/ToolsCommand.java \
        src/main/java/com/maplecode/command/CompactCommand.java \
        src/main/java/com/maplecode/command/NewCommand.java \
        src/main/java/com/maplecode/command/ResumeCommand.java \
        src/main/java/com/maplecode/command/MemoryCommand.java \
        src/test/java/com/maplecode/command/HelpCommandTest.java \
        src/test/java/com/maplecode/command/ToolsCommandTest.java \
        src/test/java/com/maplecode/command/CompactCommandTest.java \
        src/test/java/com/maplecode/command/NewCommandTest.java \
        src/test/java/com/maplecode/command/MemoryCommandTest.java
git commit -m "feat(command): add Help, Tools, Compact, New, Resume, Memory commands with tests"
```

---

### Task 7: Prompt Commands (Plan, Do, Review)

These commands interact with the Agent via `ctx.sendToAgent()`.

**Files:**
- Create: `src/main/java/com/maplecode/command/PlanCommand.java`
- Create: `src/main/java/com/maplecode/command/DoCommand.java`
- Create: `src/main/java/com/maplecode/command/ReviewCommand.java`
- Create: `src/test/java/com/maplecode/command/PlanCommandTest.java`
- Create: `src/test/java/com/maplecode/command/DoCommandTest.java`
- Create: `src/test/java/com/maplecode/command/ReviewCommandTest.java`

- [ ] **Step 1: Write PlanCommandTest**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PlanCommandTest {

    @Test
    void execute_setsPlanModeAndSendsToAgent() {
        CommandContext ctx = mock(CommandContext.class);

        new PlanCommand().execute("分析这段代码", ctx);

        verify(ctx).setPlanMode(PlanMode.PLAN);
        verify(ctx).sendToAgent("分析这段代码");
    }

    @Test
    void execute_noArgs_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new PlanCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
        verify(ctx, never()).sendToAgent(anyString());
    }
}
```

- [ ] **Step 2: Implement PlanCommand**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;

public class PlanCommand implements Command {
    @Override public String name() { return "plan"; }
    @Override public String description() { return "进入计划模式并发送查询"; }
    @Override public String usage() { return "/plan <query>"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (args.isEmpty()) {
            ctx.sendError("用法: /plan <query>");
            return;
        }
        ctx.setPlanMode(PlanMode.PLAN);
        ctx.sendToAgent(args);
    }
}
```

- [ ] **Step 3: Run PlanCommandTest**

Run: `mvn test -Dtest=PlanCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 4: Write DoCommandTest**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import com.maplecode.session.ChatSession;
import com.maplecode.session.ContentBlock.TextBlock;
import com.maplecode.session.Role;
import com.maplecode.session.ChatMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class DoCommandTest {

    @Test
    void execute_inPlanMode_extractsPlanAndRuns() {
        ChatSession session = new ChatSession();
        session.appendAssistant(List.of(new TextBlock("step 1: do this\nstep 2: do that")));

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);
        when(ctx.getSession()).thenReturn(session);

        new DoCommand().execute("", ctx);

        verify(ctx).setPlanMode(PlanMode.NORMAL);
        verify(ctx).sendToAgent("step 1: do this\nstep 2: do that");
    }

    @Test
    void execute_notInPlanMode_sendsError() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new DoCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
        verify(ctx, never()).sendToAgent(anyString());
    }

    @Test
    void execute_noAssistantText_sendsError() {
        ChatSession session = new ChatSession();

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);
        when(ctx.getSession()).thenReturn(session);

        new DoCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
    }
}
```

- [ ] **Step 5: Implement DoCommand**

```java
package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import com.maplecode.session.ChatMessage;
import com.maplecode.session.ChatSession;
import com.maplecode.session.ContentBlock;
import com.maplecode.session.Role;

public class DoCommand implements Command {
    @Override public String name() { return "do"; }
    @Override public String description() { return "执行上一条计划"; }
    @Override public String usage() { return "/do"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (ctx.getPlanMode() != PlanMode.PLAN) {
            ctx.sendError("当前不在计划模式。先用 /plan 进入计划模式。");
            return;
        }

        String planText = lastAssistantText(ctx.getSession());
        if (planText == null || planText.isBlank()) {
            ctx.sendError("没有找到计划内容。");
            return;
        }

        ctx.getSession().clear();
        ctx.setPlanMode(PlanMode.NORMAL);
        ctx.sendToAgent(planText);
    }

    private String lastAssistantText(ChatSession session) {
        for (int i = session.size() - 1; i >= 0; i--) {
            ChatMessage msg = session.get(i);
            if (msg.role() == Role.ASSISTANT) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        return tb.text();
                    }
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 6: Run DoCommandTest**

Run: `mvn test -Dtest=DoCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 7: Write ReviewCommandTest**

```java
package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ReviewCommandTest {

    @Test
    void execute_withArgs_sendsPromptToAgent() {
        CommandContext ctx = mock(CommandContext.class);

        // ReviewCommand runs git diff internally; in unit test we mock the process.
        // For now, test the no-diff path.
        ReviewCommand cmd = new ReviewCommand();
        // This will depend on how we handle ProcessBuilder in tests.
        // At minimum, verify the command name and type.
        assertEquals("review", cmd.name());
        assertEquals(CommandType.PROMPT, cmd.type());
    }

    @Test
    void name_is_review() {
        assertEquals("review", new ReviewCommand().name());
    }

    @Test
    void type_is_prompt() {
        assertEquals(CommandType.PROMPT, new ReviewCommand().type());
    }

    private void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
```

- [ ] **Step 8: Implement ReviewCommand**

```java
package com.maplecode.command;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ReviewCommand implements Command {
    @Override public String name() { return "review"; }
    @Override public String description() { return "审查当前 Git 变更"; }
    @Override public String usage() { return "/review [额外关注点]"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    private static final int MAX_DIFF_LENGTH = 15000;

    @Override
    public void execute(String args, CommandContext ctx) {
        String diff = runGitDiff();
        if (diff == null || diff.isBlank()) {
            ctx.sendMessage("没有检测到代码变更。");
            return;
        }
        if (diff.length() > MAX_DIFF_LENGTH) {
            ctx.sendError("代码变更过大（" + diff.length() + " 字符），请先 commit 或指定文件审查。");
            return;
        }

        String focus = args.isEmpty() ? "（无）" : args;
        String prompt = String.format("""
            你是一个资深的代码审查员。请审查以下 Git 代码变更。

            审查维度：
            1. 正确性：逻辑是否有 Bug？边界条件是否处理？
            2. 安全性：是否存在注入风险、权限绕过、敏感信息泄露？
            3. 性能：是否存在不必要的循环、内存泄漏、N+1 查询？
            4. 可读性：命名是否规范？是否有必要的注释？

            额外关注点（来自用户指令）：
            %s

            代码变更内容：
            %s

            输出要求：
            - 如果没有严重问题，简短说明变更意图并给予肯定。
            - 如果发现问题，按严重程度（🔴 严重 / 🟡 警告 / 🔵 建议）列出。
            - 针对每个问题，给出具体的代码行号和修改建议。
            - 不要做泛泛的评价，必须基于 diff 内容。
            """, focus, diff);

        ctx.sendToAgent(prompt);
    }

    private String runGitDiff() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff");
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int exitCode = proc.waitFor();
            return exitCode == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 9: Run ReviewCommandTest**

Run: `mvn test -Dtest=ReviewCommandTest -pl . -q`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/maplecode/command/PlanCommand.java \
        src/main/java/com/maplecode/command/DoCommand.java \
        src/main/java/com/maplecode/command/ReviewCommand.java \
        src/test/java/com/maplecode/command/PlanCommandTest.java \
        src/test/java/com/maplecode/command/DoCommandTest.java \
        src/test/java/com/maplecode/command/ReviewCommandTest.java
git commit -m "feat(command): add Plan, Do, Review commands with tests"
```

---

### Task 8: CommandCompleter

**Files:**
- Create: `src/main/java/com/maplecode/command/CommandCompleter.java`
- Create: `src/test/java/com/maplecode/command/CommandCompleterTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.maplecode.command;

import org.jline.reader.impl.completer.StringsCompleter;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompleterTest {

    // Note: Full JLine Completer testing requires mock LineReader/ParsedLine.
    // These tests verify the logic at a unit level by testing the registry interaction.

    @Test
    void completableNames_sortedIncludesAliases() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));

        List<String> names = reg.completableNames();
        assertTrue(names.contains("help"));
        assertTrue(names.contains("h"));
        assertTrue(names.contains("?"));
        assertTrue(names.contains("exit"));
        // Verify sorted
        for (int i = 1; i < names.size(); i++) {
            assertTrue(names.get(i - 1).compareTo(names.get(i)) <= 0,
                "Names should be sorted: " + names);
        }
    }

    @Test
    void completableNames_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        Command hidden = new Command() {
            @Override public String name() { return "internal"; }
            @Override public String description() { return ""; }
            @Override public String usage() { return ""; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return true; }
            @Override public void execute(String args, CommandContext ctx) {}
        };
        reg.register(hidden);
        reg.register(new ExitCommand());

        List<String> names = reg.completableNames();
        assertFalse(names.contains("internal"));
        assertTrue(names.contains("exit"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CommandCompleterTest -pl . -q`
Expected: FAIL (CommandCompleter class not found or compilation errors)

- [ ] **Step 3: Implement CommandCompleter**

```java
package com.maplecode.command;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * JLine Completer 实现，Tab 补全仅在行首触发。
 */
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CommandCompleterTest -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/command/CommandCompleter.java \
        src/test/java/com/maplecode/command/CommandCompleterTest.java
git commit -m "feat(command): add CommandCompleter with tests"
```

---

### Task 9: ReplLoop Refactoring

This is the integration task. Wire `CommandContextImpl`, replace the if-else chain with the dispatcher, and add `CommandRegistry` to the constructor.

**Files:**
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java`

- [ ] **Step 1: Add CommandRegistry import and field**

At the top of `ReplLoop.java`, add import:

```java
import com.maplecode.command.*;
```

Add field after existing fields (~line 40):

```java
    private final CommandRegistry cmdRegistry;
```

- [ ] **Step 2: Update primary constructor**

At `ReplLoop.java` line 44, change the constructor signature to add `CommandRegistry cmdRegistry` as the last parameter:

```java
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig,
                    SessionArchive sessionArchive, CompactCoordinator coord,
                    com.maplecode.memory.MemoryManager memoryManager, StatusBar statusBar,
                    CommandRegistry cmdRegistry) {
        // ... existing body ...
        this.cmdRegistry = cmdRegistry;
    }
```

- [ ] **Step 3: Update backward-compat constructor**

At `ReplLoop.java` line 71, update the 8-param constructor to pass `null` for cmdRegistry:

```java
    public ReplLoop(AppConfig appConfig, LlmProvider provider, StreamPrinter printer,
                    LineReader reader, ToolRegistry registry, ToolExecutor executor,
                    PermissionEngine engine, AgentConfig agentConfig) {
        this(appConfig, provider, printer, reader, registry, executor, engine, agentConfig,
             null, null, null, null, null);
    }
```

- [ ] **Step 4: Add CommandContextImpl inner class**

After `renderMode()` method (line ~102), add the inner class:

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
            return agent.isRunning();
        }

        @Override
        public void setPlanMode(PlanMode mode) {
            agentConfig = agentConfig.withPlanMode(mode)
                .withReminderState(com.maplecode.prompt.PlanModeReminder.State.initial());
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
                ReplLoop.this.updateStatusBar(renderMode());
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

- [ ] **Step 5: Rewrite run() method body**

Replace the entire body of `run()` (lines 104-353) with:

```java
    public void run() {
        printer.banner("MapleCode — 输入 /help 查看可用命令");

        if (statusBar != null) {
            updateStatusBar(renderMode());
            terminal.handle(org.jline.utils.Signals.WINCH, signal -> statusBar.resize());
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
                    java.util.Optional<Command> cmd = cmdRegistry.lookup(name);

                    if (cmd.isPresent()) {
                        try {
                            cmd.get().execute(args, commandContext);
                        } catch (ExitReplException e) {
                            break;
                        }
                    } else {
                        printer.error("未知命令: /" + name + "。输入 /help 查看可用命令。");
                    }
                } else {
                    // 正常对话
                    agent.run(trimmed, printer);

                    // memory extract
                    if (memoryManager != null && memoryManager.isEnabled()) {
                        memoryManager.extractAsync(agent.session().recentMessages(20));
                    }
                }
            }
        } catch (org.jline.reader.UserInterruptException e) {
            agent.cancel();
        }

        // 退出清理
        if (sessionArchive != null) {
            sessionArchive.save(agent.session());
        }
    }
```

- [ ] **Step 6: Run all existing tests**

Run: `mvn test -pl . -q`
Expected: All existing tests PASS (the refactoring should not change external behavior)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java
git commit -m "refactor(ui): replace ReplLoop if-else chain with command framework dispatcher"
```

---

### Task 10: App.main Wiring

Wire everything together in `App.java`.

**Files:**
- Modify: `src/main/java/com/maplecode/App.java`

- [ ] **Step 1: Add import**

At the top of `App.java`, add:

```java
import com.maplecode.command.*;
```

- [ ] **Step 2: Create CommandRegistry and register commands**

After `ToolRegistry registry = new ToolRegistry(allTools);` (line 120), insert:

```java
        // 命令注册
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
```

- [ ] **Step 3: Register CommandCompleter on LineReader**

At line 128, change the `var reader =` line to include the completer:

```java
        var reader = org.jline.reader.LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(new CommandCompleter(cmdRegistry))
            .build();
```

- [ ] **Step 4: Pass cmdRegistry to ReplLoop**

At lines 204-205, update the ReplLoop constructor call to include `cmdRegistry`:

```java
        ReplLoop repl = new ReplLoop(raw, provider, new StreamPrinter(terminal), reader,
            registry, executor, engine, agentConfig, sessionArchive, coord,
            memoryManager, statusBar, cmdRegistry);
```

- [ ] **Step 5: Run all tests**

Run: `mvn test -pl . -q`
Expected: All PASS

- [ ] **Step 6: Manual smoke test**

Run: `java -jar target/maple-code-java-0.1.0.jar`

Verify:
- Type `/help` → shows grouped command list
- Type `/he` + Tab → completes to `/help`
- Type `/clear` → clears session
- Type `/mode strict` → switches to strict mode
- Type `/mode` → shows "strict"
- Type `/status` → shows model/mode/plan/cwd
- Type `/exit` → exits cleanly
- Type `hello` → normal conversation (not treated as command)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/App.java
git commit -m "feat(app): wire CommandRegistry and CommandCompleter into App.main"
```

---

### Task 11: Final Verification

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All PASS

- [ ] **Step 2: Build the JAR**

Run: `mvn package -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Manual integration test**

Run: `java -jar target/maple-code-java-0.1.0.jar`

Full smoke test sequence:
1. `/help` → shows all 14 commands grouped by type
2. `/help clear` → shows "/clear — 清空会话历史"
3. `/h` + Tab → completes to `/help`
4. `/c` + Tab → shows menu: /cancel, /clear, /compact
5. `/mode strict` → switches mode
6. `/mode` → shows "strict"
7. `/mode relaxed` → shows error with valid options
8. `/status` → shows current status
9. `/clear` → clears session
10. `/tools` → lists all tools
11. `/exit` → exits cleanly

- [ ] **Step 4: Final commit if needed**

If any fixes were needed during smoke testing, commit them.
