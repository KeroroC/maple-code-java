# MapleCode — 权限系统设计规格（阶段四）

**日期**：2026-07-06
**范围**：在 v3 工具 + Agent Loop 基础上增加五层防御的权限系统（黑名单 + 路径沙箱 + 规则引擎 + 模式 + 人在回路）。规则三层 YAML 文件（用户全局 / 项目 / 项目本地）。所有 6 个工具均过权限检查。
**不做**：网络请求限制、资源配额、审计日志、会话持久化以外的规则持久化、运行时切换 provider。

---

## 1. 目标与非目标

**目标**
- 五层 `PermissionCheck` 串成管道：`BlacklistCheck → SandboxCheck → RuleCheck → ModeCheck → HitlCheck`
- 三档权限模式 `strict / default / permissive`，启动从 YAML 读，`/mode` 斜杠热切
- 三层规则 YAML：`~/.maplecode/permissions.yaml` / `<cwd>/.maplecode/permissions.yaml` / `<cwd>/.maplecode/permissions.local.yaml`；优先级：local > project > user
- HITL 4 选 1：允许本次 / 允许本会话 / 允许本项目 / 拒绝
- 拒绝路径返回 `ToolResult.error("permission denied: <reason>")` 回灌到模型，Agent Loop 不中断
- 黑名单硬拦截，不可被任何配置放开
- 路径沙箱对所有路径工具生效，先 `toRealPath()` 再 `startsWith()` 防 symlink 逃逸
- 所有现有工具测试一行不动（`ToolExecutor` 单参构造器保留）

**非目标**
- 网络请求限制、API key 配额
- 资源配额（CPU/内存/磁盘）
- 审计日志（不写 ~/.maplecode/audit.log 之类）
- 规则 UI 编辑器（只能手改 YAML）
- 运行时切换 provider
- 多模态输入、插件系统

---

## 2. 新增 / 修改的抽象

### 2.1 新增包 `com.maplecode.permission`

```java
// 一次工具调用的请求快照
public record PermissionRequest(
    String toolName,
    com.fasterxml.jackson.databind.JsonNode args,
    java.nio.file.Path cwd
) {}

// 决策结果
public record Decision(Verdict verdict, String reason) {
    public enum Verdict { ALLOW, DENY }
    public static Decision allow(String why) { return new Decision(Verdict.ALLOW, why); }
    public static Decision deny(String why)  { return new Decision(Verdict.DENY, why); }
}

// 单层检查
public interface PermissionCheck {
    Optional<Decision> check(PermissionRequest req, PermissionContext ctx);
}

// 模式
public enum PermissionMode { STRICT, DEFAULT, PERMISSIVE }

// 会话级累积决策
public record ToolCall(String toolName, String pattern) {}

// per-call 上下文视图（每次 engine.check() 都新建）
public final class PermissionContext {
    private final PermissionMode mode;
    private final Set<ToolCall> sessionAllow;
    private final Set<ToolCall> sessionDeny;
    // getters
}

// 引擎：构造时一次性注入所有 check + RuleSet + 默认 mode
public final class PermissionEngine {
    public PermissionEngine(
        List<PermissionCheck> checks,
        PermissionMode defaultMode
    ) {}
    public Decision check(PermissionRequest req) { ... }
    public void setMode(PermissionMode m) { ... }
    public PermissionMode mode() { ... }
    public void permitForSession(ToolCall tc) { ... }      // HITL 选 2 时调
    public void persistProjectAllow(String tool, String pattern) { ... }  // HITL 选 3 时调
}
```

### 2.2 修改 `AppConfig`

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
    PermissionMode permissionMode      // 新增；缺省 DEFAULT
) {}
```

### 2.3 修改 `ToolExecutor`

```java
public final class ToolExecutor {
    private final ToolRegistry registry;
    private final PermissionEngine engine;  // nullable

    public ToolExecutor(ToolRegistry registry) { this(registry, null); }
    public ToolExecutor(ToolRegistry registry, PermissionEngine engine) { ... }

    public ToolResult run(String name, JsonNode args) {
        var toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) { /* Unknown tool: ... */ }

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
            return ToolResult.error("internal error: ...");
        }
    }
}
```

### 2.4 修改 `App.java`

`HitlCheck` 需要 engine 引用来调 `permitForSession` / `persistProjectAllow`，但 engine 构造时又需要 HitlCheck——这是循环依赖。**解决方案**：构造期 HitlCheck 拿一个 `setEngine` 后置 setter；engine 构造完后调一次 `hitlCheck.setEngine(engine)`。

```java
public static void main(String[] args) throws Exception {
    // ... 现有 config/provider/registry 加载 ...

    RuleSet ruleSet = PermissionFileLoader.loadAll(cwd);
    PermissionMode mode = raw.permissionMode();

    HitlCheck hitlCheck = new HitlCheck(input, output);
    PermissionEngine engine = new PermissionEngine(List.of(
        new BlacklistCheck(),
        new SandboxCheck(cwd),
        new RuleCheck(ruleSet),
        new ModeCheck(),
        hitlCheck
    ), mode);
    hitlCheck.setEngine(engine);  // 后置注入打破循环

    ToolExecutor executor = new ToolExecutor(registry, engine);
    ReplLoop repl = new ReplLoop(raw, provider, printer, lineReader,
        registry, executor, engine, agentConfig);  // + engine 参数
    repl.run();
}
```

`HitlCheck.check()` 在调用 `engine.permitForSession` / `engine.persistProjectAllow` 前必须确保 `setEngine` 已调用——否则 NPE。`engine.check()` 不会回调 HitlCheck 自己调 HitlCheck 之前 engine 已经构造完，setEngine 一定先完成。

### 2.5 修改 `ReplLoop.java`

新增 `/mode` 命令分支（详见 §6.3）。ReplLoop 构造器增加 `PermissionEngine engine` 参数。

---

## 3. 第一层：BlacklistCheck

**职责**：硬拦截已知高危 shell 命令。**不可被任何配置放开**。

**适用范围**：仅 `exec` 工具；其他工具返回 `Optional.empty()` 透传。

**硬编码规则**（12 条，存为不可变 `List<Rule>`）：

| Regex | 原因 |
|---|---|
| `\brm\s+(-[a-zA-Z]*f[a-zA-Z]*\s+)?-r?f?\s+/(\s\|$\|;\|\\|)` | rm -rf against root |
| `:\(\)\s*\{\s*:\|` | fork bomb |
| `\bmkfs\.` | filesystem format |
| `\bdd\s+.*if=/dev/(zero\|urandom\|random)` | dd disk overwrite |
| `>\s*/dev/sd[a-z]` | redirect to block device |
| `\bsudo\b` | sudo not allowed |
| `\bchmod\s+(-[a-zA-Z]*\s+)*777\b` | chmod 777 |
| `\bcurl\b.*\|\s*(ba)?sh\b` | curl piped to shell |
| `\bwget\b.*\|\s*(ba)?sh\b` | wget piped to shell |
| `\bshutdown\b\|\breboot\b\|\bhalt\b\|\bpoweroff\b` | system power control |
| `\b:\s*!\s*\w+\s*/` | history expansion |
| `\beval\b.*\$\(` | eval with command substitution |

**匹配方式**：`Pattern.compile(regex).matcher(command).find()`（子串匹配）。

**Decision**：DENY，reason = `"blocked by built-in blacklist: <reason>"`。

---

## 4. 第二层：SandboxCheck

**职责**：所有路径工具调用必须落在「启动时 cwd 的真实路径」之内。

**适用范围**：`read_file` / `write_file` / `edit_file`（参数 `path`）；`glob`（参数 `pattern`，特殊处理）；`grep`（参数 `path`，缺省 `.`，特殊处理）。`exec` 跳过。

**实现要点**：

```java
public final class SandboxCheck implements PermissionCheck {
    private static final Set<String> PATH_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "glob", "grep");

    private final Path sandboxRoot;  // cwd.toRealPath()，构造期解析一次

    public SandboxCheck(Path cwd) {
        try { this.sandboxRoot = cwd.toRealPath(); }
        catch (IOException e) {
            throw new IllegalStateException("cannot resolve sandbox root: " + cwd, e);
        }
    }

    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!PATH_TOOLS.contains(req.toolName())) return Optional.empty();

        JsonNode pathNode = switch (req.toolName()) {
            case "glob"  -> req.args().get("pattern");
            case "grep"  -> req.args().has("path") ? req.args().get("path") : null;
            default      -> req.args().get("path");
        };
        if (pathNode == null || pathNode.isNull()) return Optional.empty();

        String raw = pathNode.asText();
        Path requested = raw.startsWith("/") ? Path.of(raw) : ctx.cwd().resolve(raw);

        // glob/grep 的 pattern 用 normalize 前缀判断（pattern 不是具体文件）
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
        try { real = requested.toRealPath(); }
        catch (NoSuchFileException e) {
            return Optional.empty();  // 路径不存在不归沙箱管——让工具自己报
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

**关键决策**：
- `sandboxRoot` 构造期 `toRealPath()` 解析一次
- glob/grep 用 `normalize()` 而非 `toRealPath()`——pattern 不是具体文件，不存在
- 路径不存在（`NoSuchFileException`）→ empty，让工具层自己报
- exec 跳过沙箱——它的动作是命令本身，危险由黑名单兜底

---

## 5. 第三层：RuleCheck

**职责**：根据三层 YAML 规则做 first-match-wins 匹配。

### 5.1 规则加载

```java
public final class PermissionFileLoader {

    private static final Path USER_GLOBAL = Paths.get(
        System.getProperty("user.home"), ".maplecode", "permissions.yaml");

    public static RuleSet loadAll(Path projectRoot) {
        Path projectFile  = projectRoot.resolve(".maplecode/permissions.yaml");
        Path projectLocal = projectRoot.resolve(".maplecode/permissions.local.yaml");

        List<Rule> merged = new ArrayList<>();
        merged.addAll(loadFile(USER_GLOBAL).rules().stream().map(toRule).toList());
        merged.addAll(loadFile(projectFile).rules().stream().map(toRule).toList());
        merged.addAll(loadFile(projectLocal).rules().stream().map(toRule).toList());

        // 校验所有 tool 名是已知工具
        Set<String> known = Set.of("read_file", "write_file", "edit_file",
                                   "exec", "glob", "grep");
        for (Rule r : merged) {
            if (!known.contains(r.toolName())) {
                throw new ConfigException("permission rule references unknown tool: "
                    + r.toolName());
            }
        }
        return new RuleSet(List.copyOf(merged));
    }
}
```

不存在的文件**跳过**（首次使用没文件是正常状态）。

### 5.2 YAML 格式

```yaml
rules:
  - tool: exec
    pattern: "git *"
    action: allow
  - tool: read_file
    pattern: "**/.env"
    action: deny
```

```java
public record PermissionFile(List<RuleEntry> rules) {
    public record RuleEntry(String tool, String pattern, String action) {}
}

record Rule(String toolName, String pattern, Action action) {
    enum Action { ALLOW, DENY }
}
```

### 5.3 匹配逻辑

```java
public final class RuleCheck implements PermissionCheck {
    private final RuleSet ruleSet;

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

    private String extractPattern(PermissionRequest req) {
        return switch (req.toolName()) {
            case "exec"   -> req.args().path("command").asText();
            case "glob"   -> req.args().path("pattern").asText();
            case "grep"   -> req.args().has("path") ? req.args().get("path").asText() : ".";
            case "read_file", "write_file", "edit_file" -> req.args().path("path").asText();
            default       -> null;
        };
    }

    private boolean matches(String tool, String rulePattern, String actual) {
        if (rulePattern.equals(actual)) return true;
        if (tool.equals("exec")) {
            return shellGlobMatch(rulePattern, actual);  // 自实现：`*` → `[^ ]*`
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + rulePattern)
            .matches(Path.of(actual));
    }
}
```

**shell glob 翻译规则**（自实现，约 30 行）：
- `*` → `[^ ]*`（任意非空格序列）
- `?` → `[^ ]`
- 其他字符按字面匹配，正则特殊字符转义
- 全串匹配（`Matcher.matches()`）

**优先级**：合并顺序为 user → project → local；循环 first-match-wins，所以 local 优先于 project 优先于 user。

---

## 6. 第四层：ModeCheck + `/mode` 斜杠命令

### 6.1 ModeCheck

```java
public final class ModeCheck implements PermissionCheck {
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

### 6.2 AppConfig 新字段

YAML：
```yaml
permission_mode: default    # strict | default | permissive；缺省 default
```

`ConfigLoader`：
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

### 6.3 `/mode` 斜杠命令（ReplLoop）

```java
if (trimmed.startsWith("/mode")) {
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

**关键决策**：
- `PermissionEngine.mode` 字段用 `volatile`（不是锁）——并发 check 用 stale read 最多一次，最坏情况下次看到新值
- `/mode` 不持久化；只改运行中的 mode，重启回到 YAML 配置
- `/mode` 不清空 session allow/deny 集合

---

## 7. 第五层：HitlCheck

**职责**：default 模式 + 规则未命中时弹 prompt 给用户。

### 7.1 输入输出抽象

```java
public interface InputSource {
    String readLine(String prompt) throws UserInterruptException;
}
public interface OutputSink {
    void println(String line);
}
```

生产实现用 JLine 的 `LineReader` + `PrintStream`；单测用 `StringReader` + `StringWriter`。

### 7.2 HitlCheck 实现

```java
public final class HitlCheck implements PermissionCheck {

    private final InputSource input;
    private final OutputSink output;
    private final PermissionEngine engine;

    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        // 第一步：session 集合优先
        String pattern = extractPattern(req);
        if (pattern != null) {
            ToolCall tc = new ToolCall(req.toolName(), pattern);
            if (ctx.sessionAllows().contains(tc))
                return Optional.of(Decision.allow("session allow"));
            if (ctx.sessionDenies().contains(tc))
                return Optional.of(Decision.deny("session deny"));
        }

        // 第二步：弹 prompt
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
        } catch (UserInterruptException e) {
            return Optional.of(Decision.deny("user interrupted at permission prompt"));
        }

        return switch (choice) {
            case "1" -> Optional.of(Decision.allow("user allowed once"));
            case "2" -> {
                if (pattern != null) engine.permitForSession(new ToolCall(req.toolName(), pattern));
                yield Optional.of(Decision.allow("user allowed for session"));
            }
            case "3" -> {
                if (pattern != null) {
                    try {
                        engine.persistProjectAllow(req.toolName(), pattern);
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
}
```

### 7.3 `PermissionEngine.persistProjectAllow`

往 `<cwd>/.maplecode/permissions.local.yaml` 追加一条 YAML 条目（line append 方式）：

```java
public void persistProjectAllow(String tool, String pattern) throws IOException {
    Path file = Path.of(System.getProperty("user.dir"))
        .resolve(".maplecode/permissions.local.yaml");
    Files.createDirectories(file.getParent());
    String entry = String.format(
        "  - tool: %s%n    pattern: %s%n    action: allow%n",
        tool, escapeYamlString(pattern));
    boolean isNew = !Files.exists(file);
    if (isNew) {
        // 文件不存在则先写 rules: 头再加条目，保证 YAML 合法
        Files.writeString(file, "rules:\n" + entry, StandardOpenOption.CREATE);
    } else {
        Files.writeString(file, entry, StandardOpenOption.APPEND);
    }
}

private static String escapeYamlString(String s) {
    // 必须用双引号包：含 `:` / `#` / 开头特殊字符 / 换行 / 引号自身
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                   .replace("\n", "\\n").replace("\r", "\\r") + "\"";
}
```

`permission` 行格式：
```yaml
rules:
  - tool: exec
    pattern: "git status"
    action: allow
```

每次追加新条目到 `rules:` 列表末尾。

### 7.4 session allow 粒度

- 存的是 `(toolName, pattern)` 二元组
- 匹配复用 `RuleCheck.matches()` 同款逻辑（exec 用 shell glob，其他用 PathMatcher）
- 一次 session allow 适用于「toolName + 这个 pattern」所有后续调用

---

## 8. 端到端数据流

```
App.main
  ├─ ConfigLoader.load(path) → AppConfig(含 permissionMode)
  ├─ ProviderRegistry.create(raw) → LlmProvider
  ├─ ToolRegistry(6 个 tool)
  ├─ PermissionFileLoader.loadAll(cwd) → RuleSet
  ├─ PermissionEngine(List.of(
  │     BlacklistCheck,
  │     SandboxCheck(cwd),
  │     RuleCheck(ruleSet),
  │     ModeCheck,
  │     HitlCheck(input, output, engine)
  │   ), mode)
  ├─ ToolExecutor(registry, engine)
  └─ ReplLoop(..., registry, executor, agentConfig)
        └─ /mode 命令 → engine.setMode(...)
        └─ AgentLoop(...)
              └─ executeOne → executor.run(name, args)
                    └─ engine.check(req) → Decision
                    └─ allow → tool.execute | deny → ToolResult.error("permission denied: ...")
```

---

## 9. 错误处理

| 场景 | 行为 | 退出码 |
|---|---|---|
| 工具名未知 | `ToolResult.error("Unknown tool: X. Available: ...")` | 0 |
| 黑名单命中 | `ToolResult.error("permission denied: blocked by built-in blacklist: ...")` | 0 |
| 路径越界 | `ToolResult.error("permission denied: path escapes sandbox: ...")` | 0 |
| 路径解析失败 | `ToolResult.error("permission denied: cannot resolve path: ...")` | 0 |
| 规则 deny | `ToolResult.error("permission denied: rule match: ...")` | 0 |
| strict 无规则 | `ToolResult.error("permission denied: no matching rule and mode is strict")` | 0 |
| HITL 选 4 | `ToolResult.error("permission denied: user denied")` | 0 |
| HITL 选错 | `ToolResult.error("permission denied: invalid choice 'X', denying")` | 0 |
| HITL Ctrl-C | `ToolResult.error("permission denied: user interrupted at permission prompt")` | 0 |
| 启动 YAML 解析失败 | `ConfigException` | 78 |
| 启动 rule 引用未知 tool | `ConfigException("permission rule references unknown tool: X")` | 78 |
| 启动 sandbox root 无法解析 | `IllegalStateException` | 78 |
| `/mode` 错参数 | REPL 打印 `/mode <strict\|default\|permissive>` | 0 |
| `/mode` 无参数 | REPL 打印 `current mode: <X>` | 0 |
| 写 local.yaml 失败 | 降级到 session allow + stderr warning | 0 |

**关键原则**：
- 所有运行时权限错包成 `ToolResult.error` 回灌，REPL 不退出
- `permission denied:` 前缀让模型一眼区分权限拒绝 vs 工具失败
- 写文件失败不破坏决策——降级到 session allow
- 退出码 78 只留给启动期配置错（沿用现有约定）

---

## 10. 文件清单

### 10.1 新增

```
src/main/java/com/maplecode/permission/
├── PermissionRequest.java
├── Decision.java
├── PermissionCheck.java
├── PermissionContext.java
├── PermissionMode.java
├── PermissionEngine.java
├── ToolCall.java
├── RuleSet.java
├── Rule.java
├── PermissionFile.java
├── PermissionFileLoader.java
├── InputSource.java
├── OutputSink.java
├── JLineInputSource.java
├── PrintStreamOutputSink.java
├── BlacklistCheck.java
├── SandboxCheck.java
├── RuleCheck.java
├── ModeCheck.java
└── HitlCheck.java
```

### 10.2 修改

```
src/main/java/com/maplecode/
├── config/AppConfig.java       ← + permissionMode 字段
├── config/ConfigLoader.java    ← + permission_mode YAML 解析
├── tool/ToolExecutor.java      ← + engine 字段 + 6 参构造器 + check 调用
├── ui/ReplLoop.java            ← + /mode 命令分支
└── App.java                    ← + PermissionEngine 构造 + 注入 executor
```

### 10.3 测试新增

```
src/test/java/com/maplecode/permission/
├── BlacklistCheckTest.java
├── SandboxCheckTest.java
├── RuleCheckTest.java
├── ModeCheckTest.java
├── HitlCheckTest.java
├── PermissionFileLoaderTest.java
├── PermissionEngineTest.java
├── PermissionContextTest.java
├── ShellGlobMatchTest.java          # exec 的 shell glob 自实现测试
└── EscapeYamlStringTest.java       # local.yaml 转义测试

src/test/java/com/maplecode/tool/
└── ToolExecutorPermissionTest.java  # engine 注入路径

src/test/java/com/maplecode/config/
└── ConfigLoaderPermissionTest.java  # permission_mode YAML 解析
```

### 10.4 测试不动

所有现有 `*ToolTest.java`（6 个工具）、`ToolExecutorTest.java`、`ToolRegistry*Test.java` 不动一行——`ToolExecutor(registry)` 单参构造器保留。

---

## 11. 验收清单

- [ ] `mvn package` 产出可执行 jar
- [ ] `mvn test` 全绿（含 10 个新 permission 测试 + 1 个 ToolExecutor 新测试 + 1 个 ConfigLoader 新测试；现有 14 个工具/执行器测试零改动通过）
- [ ] `~/.maplecode/permissions.yaml` 写 `exec(git *): allow`，执行 `git status` 不弹 prompt、放行
- [ ] 删掉该规则，执行 `git status` → HITL 弹 4 选 1 → 选 3 → 重启 → 不弹 prompt
- [ ] 启动 `permission_mode: strict` → 执行 `ls` → deny
- [ ] `/mode permissive` → 紧接着 `ls` → 放行
- [ ] 执行 `rm -rf /tmp/foo` → 黑名单 deny
- [ ] `read_file(/etc/passwd)` → 沙箱 deny
- [ ] HITL 期间 Ctrl-C → deny + reason `user interrupted at permission prompt`
- [ ] 写 local.yaml 失败（权限拒绝目录）→ 降级到 session allow + warning
- [ ] 规则引用未知 tool（如 `Bash(git *)`）→ 启动报 ConfigException 退出码 78
- [ ] `permission_mode: invalid` → 启动报 ConfigException 退出码 78
- [ ] 并发：100 个并发 thread 各加 100 个 session allow，最终 size = 10000（`engine.permitForSession` 内部用 `ConcurrentHashMap.newKeySet()`）
- [ ] pom 依赖不变（snakeyaml 已在 classpath；无新依赖）

---

## 12. 设计决策记录

| 决策 | 原因 |
|---|---|
| 五层独立 `PermissionCheck` 接口而非一条规则链 | HITL（带交互）、黑名单（不可配置）天然不适合塞进通用规则 |
| `PermissionCheck` 非 sealed | 同 Tool 的理由——单测要匿名 mock |
| `PermissionContext` per-call 新建 | 避免 Batch.partition() 的 parallelStream 撞共享可变状态 |
| Master state 在 `PermissionEngine` 内部（`sessionAllow` / `sessionDeny` 都是 `ConcurrentHashMap.newKeySet()`） | per-call ctx 是视图，写操作走 engine thread-safe set |
| `mode` 用 `volatile` 而非锁 | stale read 顶多让一次 check 用旧 mode，可接受 |
| glob/grep pattern 用 `normalize()` 前缀判断而非 `toRealPath()` | pattern 不是具体文件，toRealPath 无意义 |
| 路径不存在 → empty，让工具层报错 | 沙箱只关心「存在但越界」 |
| 写 local.yaml 用 line append 而非 snakeyaml dump | 避免 dump 重排其他条目，git diff 友好 |
| 写 local.yaml 时若文件不存在则先写 `rules:\n` 头 | 保证新文件就是合法 YAML |
| HitlCheck 用 setEngine 后置注入打破构造期循环 | engine 构造需要 HitlCheck；HitlCheck 又需要 engine 引用 |
| exec 用自实现 shell glob 而非 `PathMatcher` | exec 命令含空格，`PathMatcher` 不适合匹配整串 |
| 黑名单 regex 用 `.find()` 而非 `.matches()` | 危险模式出现在命令任意位置都应拦 |
| `/mode` 不持久化 | 避免「忘了切回 strict」类的隐式行为 |
| 写 local.yaml 失败降级到 session allow | best-effort：不让磁盘 IO 错误吃掉用户的「允许」意图 |
| `permission denied:` 前缀 | 让模型一眼区分权限拒绝 vs 工具失败 |