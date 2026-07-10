# Slash Command 代码审查报告

**日期**：2026-07-09
**范围**：`com.maplecode.command` 包（斜杠命令框架 + 14 个命令）、`ReplLoop` 分发器、`AgentLoop` 取消逻辑、`App` 装配
**结论**：框架设计良好（接口/注册中心/解析器职责清晰、冲突检测到位），但存在 **2 个阻断级集成/逻辑缺陷** 和若干中低危问题，建议优先修复。

---

## 🔴 阻断级（必须修）

### 1. Tab 补全功能完全未接入（死代码）
- **位置**：`App.java:129` 构建 `reader`，无 `.completer(...)`；`cmdRegistry` 在 `App.java:206` 才创建；全仓搜索 `new CommandCompleter` / `setCompleter` / `.completer(` —— **除 `CommandCompleter` 类自身外无任何引用**。
- **事实**：`CommandCompleter` 从未被实例化，也从未挂载到 `LineReader`。设计文档 §7.2、§9 明确要求接入，但实现未落实。
- **影响**：在 REPL 中按 Tab **完全没有命令补全**，等于该功能不存在。
- **修复**：在 `cmdRegistry` 注册完成后加一行
  ```java
  reader.setCompleter(new CommandCompleter(cmdRegistry));
  ```
  （注意 `reader` 在 `App.java:129` 创建、需保证 `setCompleter` 在 `repl.run()` 之前执行。）

### 2. 即便接入，补全匹配逻辑有 bug（前导 `/` 导致永远无候选）
- **位置**：`CommandCompleter.java:30,33`
  ```java
  String partial = line.word().toLowerCase();   // 例如 "/he"
  for (String name : registry.completableNames()) {   // 例如 "help"
      if (name.startsWith(partial)) { ... }   // "help".startsWith("/he") == false
  }
  ```
- **原因**：JLine 默认解析器以空白分词，`/he` 是一个词，词内**包含前导 `/`**；而候选名（`completableNames()`）是不带 `/` 的裸名，且候选展示值才是 `"/" + name`。于是过滤条件 `name.startsWith(partial)` 恒成立 false → 任何输入都产生 0 个候选。
- **影响**：与设计文档 §7.1 的示例（`/he` → `/help`）直接矛盾，补全即便被接入也无效。
- **修复**（二选一）：
  ```java
  // 方案 A：用带斜杠的候选值做前缀匹配
  if (("/" + name).startsWith(partial)) { candidates.add(new Candidate("/" + name)); }
  // 方案 B：先剥掉前导斜杠
  String partial = line.word().toLowerCase().replaceFirst("^/", "");
  if (name.startsWith(partial)) { candidates.add(new Candidate("/" + name)); }
  ```
- **测试缺口**：设计文档 §12 要求 `CommandCompleter` 单测（mock `ParsedLine` 验证 candidates），但实际只存在 `CommandParserTest` 与 `HelpCommandTest`。无单测覆盖，bug 长期未被发现。

---

## 🟠 高危

### 3. `cancelled` 标志永不复位 → 一次取消后下条输入被静默丢弃
- **位置**：`AgentLoop.java:32`（`private volatile boolean cancelled;`）、`:63`（`cancel()` 置 true）、`:110`（`if (cancelled) break;`）。**全程无任何 `cancelled = false`**。
- **关键事实**：`ReplLoop` 在构造时 `new AgentLoop(...)` 只建**一个实例**复用（`:69`），不是每次请求新建。
- **复现链**：
  1. 用户在某轮按 Ctrl+C（`ReplLoop.java:210` → `agent.cancel()`）或空闲时键入 `/cancel`（`CancelCommand` → `agent.cancel()`）”→ `cancelled = true`。
  2. 下一次 `agent.run(...)` 进入 `runInternal`，`while` 首次迭代即命中 `if (cancelled) break;`（`USER_CANCELLED`）→ **不做任何工作直接返回**。
  3. 用户下一条真实提问被静默吞掉。
- **为什么测试没发现**：`AgentLoopTest.cancelBeforeRunEmitsUserCancelled` 每个用例 `new AgentLoop(...)`，从不测试“取消后又一次 run”，掩盖了单例复用下的缺陷。
- **修复**：在 `run()` 开头复位（每次 turn 独立）：
  ```java
  public void run(String userInput, Consumer<AgentEvent> sink) {
      cancelled = false;   // 新增
      running = true;
      try { runInternal(userInput, sink); }
      finally { running = false; }
  }
  ```
  > 注意：并发 `cancel()` 在 run 期间置 true 仍会被本次 turn 的迭代边界正确捕获；下一 turn 由开头复位，互不干扰。

---

## 🟡 中危

### 4. `/do` 用“最后一条 assistant 文本”反推计划，脆弱
- **位置**：`DoCommand.java:22`（`lastAssistantText(ctx.getSession())`）、`:28-30`。
- **问题**：`/do` 依赖 session 里**最后一条** assistant 文本作为计划。但 `/plan` 进入 PLAN 模式后，用户可继续在 plan 模式下追问——此时最后一条 assistant 文本变成追问答案，而非原始计划。`/do` 会去“执行”错误文本。
- **修复**：在 `/plan` 执行时把计划文本显式保存（如 `CommandContext` 暴露 `setLastPlan(String)` / `getLastPlan()`，或 `ReplLoop` 维护一个 plan 引用），`/do` 直接取该引用，而非事后从 session 反推。

### 5. `/clear`、`/new` 未真正重置状态栏显示的 token 用量
- **位置**：`ClearCommand.java:20-24`、`NewCommand.java`（均只调 `coord.recordUsage(null)`）。
- **问题**：`coord.recordUsage(null)` 只影响 `CompactCoordinator.lastSeenUsage`（压缩锚点）。而状态栏展示的是 `ReplLoop.lastTokenUsage`（由 `usageSink` 更新），`CommandContext` 也只暴露只读的 `getTokenUsage()`。结果：`/clear` 后状态栏**仍然显示旧 token 数**，违背设计 §10.2/§10.3 第 3 步“清 token 用量”。
- **修复**：给 `CommandContext` 增加 `resetTokenUsage()`（由 `ReplLoop` 把 `lastTokenUsage = null` 并刷状态栏），`/clear`、`/new`、`/do` 调用它。

### 6. 分发器只 catch `ExitReplException`，命令抛其它异常会击穿 REPL
- **位置**：`ReplLoop.java:191-195`
  ```java
  try { cmd.get().execute(args, commandContext); }
  catch (ExitReplException e) { break; }
  ```
- **问题**：`run()` 的 try 只捕获 `UserInterruptException`（`ReplLoop.java:209`）。任何命令里抛出的 `RuntimeException`/`Error`（如 `/compact` 的 `beforeRequest` 链路、`/memory extract` 的 `extractSync`、`/resume` 的 `load` 未捕获路径等）会逃出 while 循环、终止整个 REPL 进程。
- **修复**：在 `ExitReplException` 分支旁加 `catch (Exception e) { ctx.sendError("命令执行失败: " + e.getMessage()); }` 兜底，保证单条命令故障不拖垮整个程序。

### 7. `/cancel` 的“中断当前执行”实际上几乎不可达
- **位置**：`CancelCommand.java:13-17`、`ReplLoop` 主循环单线程同步。
- **分析**：`agent.run` 在 `CommandContextImpl.sendToAgent` 中**同步阻塞**整个 REPL 线程；运行期间不会读输入，用户根本无法键入 `/cancel`。真正能中断的只有 Ctrl+C。因此 `/cancel` 作为“命令”只能在 agent **空闲**时被键入，此时 `cancelCurrentAgentRun()` 是空操作，而 `setPlanMode(NORMAL)` 反而会把仍想留在 plan 模式的用户意外踢回 normal。
- **建议**：要么文档明确“中断用 Ctrl+C、/cancel 仅复位 plan 模式”，要么把 agent 执行改为非阻塞/另起输入线程以支持运行期键入 `/cancel`。

---

## 🟢 低危 / 建议

8. **`/help [command]` 带斜杠失效**：`HelpCommand.java:24` 直接 `registry.lookup(args)`，若用户输 `/help /mode`，args=`/mode` 找不到。应先 `args.stripLeading('/')`。
9. **`/review` 只看 `git diff`**：未含已 `git add` 的暂存变更、不含未跟踪文件，且 `MAX_DIFF_LENGTH=15000` 硬编码魔数（应读配置）。建议 `git diff HEAD` 并考虑 `--stat` 处理未跟踪。
10. **PROMPT 命令不触发记忆抽取**：`ReplLoop.java:204-206` 仅普通对话调 `memoryManager.extractAsync`，`/plan`、`/do`、`/review` 走 agent 却不抽取，行为不一致。
11. **代码重复**：`ReplLoop.formatRelativeTime` 与 `ResumeCommand.formatAge` 功能重叠、措辞不同；建议抽公共工具。
12. **文档漂移**：AGENTS.md / 项目 CLAUDE.md 的“REPL 斜杠命令”表仅列 8 个命令，缺 `/help`、`/new`、`/resume`、`/memory`、`/status`、`/review` 共 6 个；命令框架已是 v8（14 命令），主指引文档未同步。

---

## 修复优先级建议
1. 接回 `CommandCompleter` 到 `LineReader`（#1）+ 修正匹配（#2）→ 补全可用。
2. `AgentLoop.run()` 开头 `cancelled = false`（#3）→ 取消后 REPL 不再吞输入。
3. 加固分发器异常兜底（#6）+ 暴露 `resetTokenUsage`（#5）。
4. 显式保存计划引用（#4）。
5. 其余低危逐项清理 + 补文档与单测。
