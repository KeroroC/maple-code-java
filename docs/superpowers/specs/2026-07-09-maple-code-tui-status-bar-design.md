# MapleCode TUI 状态栏与输入框设计

## 1. 概述

为 MapleCode REPL 添加类 Claude Code 的 TUI 体验：底部固定状态栏 + 带上边框的输入区域。利用 JLine 3.27.0 内置的 `Status` 类（scroll region 技巧）实现固定状态栏，Unicode box-drawing 字符实现输入框上边框。

## 2. 目标

- 底部固定状态栏，显示模型名称、Token 用量、当前模式、工作目录
- 输入区简洁 prompt（`>`），视觉上与输出区分离
- 状态栏在 REPL 整个生命周期内保持开启，Agent 输出在 scroll region 上方滚动
- StreamPrinter 通过 `terminal.writer()` 输出，与 Status 共享 JLine 内部同步机制
- 不新增外部依赖（仅用 jline-terminal 已有的 Status 类）
- 支持终端 resize
- dumb terminal 降级：状态栏不可用时静默跳过，不影响核心功能

## 3. 非目标

- Alternate screen buffer
- 输出区边框
- 输入框下边框（多行模式下难以维护闭合，收益低）
- 鼠标支持
- 语法高亮
- Tab 补全（后续迭代）

## 4. 屏幕布局

```
┌──────────────────────────────────────────────────────┐
│ (输出区 - 自然滚动，JLine Status scroll region 管理)  │
│                                                      │
│ > 帮我看看这个文件                                    │
│ Assistant: 我来读取一下...                            │
│ ⚙ read_file /tmp/test.txt                            │
│ ✓ read_file                                          │
│                                                      │
│ > _                                                  │  ← 输入框（prompt）
│ ───────────────────────────────────────────────────  │  ← 状态栏（JLine Status，始终可见）
│ claude-sonnet-4 │ tok:1.2k/3.4k │ plan │ ~/proj     │
└──────────────────────────────────────────────────────┘
```

### 4.1 状态栏内容

一行文本，各字段用 ` │ ` 分隔：

| 字段 | 来源 | 示例 |
|------|------|------|
| 模型名称 | `AppConfig.model()` | `claude-sonnet-4` |
| Token 用量 | `TokenUsage`（来自 `IterationEnd`） | `tok:1.2k/3.4k` |
| 当前模式 | `AgentConfig.planMode()` + `PermissionEngine.mode()` | `plan` / `strict` |
| 工作目录 | `System.getProperty("user.dir")` | `~/projects/myapp` |

**Token 用量格式：**
- `tok:-/-`：尚未获取到任何 token 数据（初始状态）
- `tok:0/0`：已获取但值为零
- `tok:123/456`：数值 < 1000 显示原始值
- `tok:1.2k/3.4k`：数值 >= 1000 显示 `x.yk`

### 4.2 输入框

简洁 prompt，无额外边框装饰：

**单行模式：**
```
> 用户输入的文本
```

**多行模式（`"""` 触发）：**
```
> """
... 第一行
... 第二行
... 第三行
```

**实现细节：**
- 首行 prompt：`"> "` — 直接传入 `readLine()`，JLine 处理光标和编辑
- 续行 prompt：`"... "` — 多行模式下使用
- `readLine()` 返回后直接进入 Agent 执行

### 4.3 交互流程

```
启动:
  创建 Terminal, LineReader, StatusBar
  status.update(初始状态)
  打印 banner

每轮循环:
  1. readLine("> ")                    // 用户输入（状态栏始终在底部可见）
  2. agent.run(input, sink)           // Agent 执行，输出在 scroll region 上方滚动
  3. 更新 status 内容                 // token 用量、模式等
  4. 回到 1
```

**关键设计：状态栏始终可见**
- 状态栏在 REPL 整个生命周期内保持开启，绝不 hide/show
- Agent 输出时，文本在状态栏上方的 scroll region 内自然滚动
- 这避免了 hide/show 导致的屏幕跳动和重绘闪烁
- 用户始终能看到模型、token 用量等关键信息

**StreamPrinter 输出方式：**
- StreamPrinter 通过 `terminal.writer()` 输出，而非 `System.out`
- JLine 内部对 `Status.update()` 和 `terminal.writer()` 有一定的同步机制
- 避免终端控制序列交织导致光标错位

**Esc 键处理：**
- Agent 流式输出期间单击 Esc：立即取消当前流式响应（通过 `EscapeController`）
- 用户输入期间 500ms 内双击 Esc：清空输入
- 多行输入期间双击 Esc：丢弃整段内容并返回主提示符

### 4.4 多行输入触发机制

当前实现使用 `"""` 作为多行触发器，逻辑在 `ReplLoop.readMultiline()` 中：

```java
private String readMultiline() {
    String first = reader.readLine("> ");
    if (first == null) return null;
    if (!first.equals("\"\"\"")) return first;  // 非多行，直接返回
    StringBuilder sb = new StringBuilder();
    while (true) {
        String line = reader.readLine("... ");     // 续行 prompt
        if (line == null) return null;
        if (line.equals("\"\"\"")) break;        // 关闭多行
        sb.append(line).append('\n');
    }
    // ...
}
```

这不需要 JLine 的 `Reading` 或 `isReadingInputComplete` 机制——多行逻辑完全在应用层实现，每次 `readLine()` 都是单行读取。JLine 的 history 也正常工作（每行独立记录）。

## 5. 架构设计

### 5.1 新增类：`StatusBar`

位置：`src/main/java/com/maplecode/ui/StatusBar.java`

```
StatusBar
├── Status status          // JLine Status 实例
├── Terminal terminal      // JLine Terminal
├── boolean supported      // 终端是否支持 scroll region
├── StatusState state      // 当前状态数据
│
├── StatusBar(Terminal terminal)
├── void update(StatusState state)
├── void resize()
└── boolean isSupported()
```

**`StatusState` record：**
```java
record StatusState(
    String model,
    TokenUsage usage,      // nullable
    String mode,           // "normal" / "plan" + permission mode
    String workingDir      // 缩略路径（~ 替代 home）
) {}
```

**核心实现逻辑：**

```java
public StatusBar(Terminal terminal) {
    this.terminal = terminal;
    this.status = Status.getStatus(terminal);
    this.supported = status != null;  // Status.getStatus 返回 null 如果终端不支持
}

public void update(StatusState state) {
    if (!supported) return;
    this.state = state;
    List<AttributedString> lines = List.of(render(state));
    status.update(lines);
}

public void resize() {
    if (!supported) return;
    status.resize();
    if (state != null) update(state);  // resize 后重绘
}
```

**渲染逻辑：**
- 使用 `AttributedStringBuilder` 构建带样式的单行文本
- 模型名：粗体
- Token 用量：默认样式
- 模式：根据模式着色（plan=黄色，strict=红色，permissive=绿色，normal=默认）
- 工作目录：灰色
- 分隔符 ` │ `：灰色

**注意：不再提供 `hide()` / `show()` 方法** — 状态栏在 REPL 生命周期内始终可见。

### 5.2 修改类：`StreamPrinter`

**核心变更：引入 `Terminal` 输出**

StreamPrinter 需要从 `System.out` 切换到 `terminal.writer()`，以避免与 Status 的终端控制序列交织。

```java
public final class StreamPrinter implements Consumer<AgentEvent> {

    private final PrintWriter writer;  // 替代 PrintStream out

    /** 使用 terminal.writer() 创建（生产环境） */
    public StreamPrinter(Terminal terminal) {
        this.writer = terminal.writer();
    }

    /** 指定 PrintWriter 创建（测试环境） */
    public StreamPrinter(PrintWriter writer) {
        this.writer = writer;
    }

    /** 向后兼容：使用 System.out（无 Terminal 时的降级） */
    public StreamPrinter() {
        this(new PrintWriter(System.out));
    }
    // ...
}
```

**变更点：**
- `PrintStream out` → `PrintWriter writer`
- `write()` / `writeThinking()` / `toolStart()` / `toolEnd()` 等方法改用 `writer.print()` / `writer.flush()`
- `error()` / `info()` / `usage()` 等方法改用 `writer.println()`
- 构造器接受 `Terminal`（生产）或 `PrintWriter`（测试）

**兼容性：**
- 无参构造器 `new StreamPrinter()` 保持不变，使用 `System.out` 包装
- 测试中可以注入 `PrintWriter` 到 `ByteArrayOutputStream`

### 5.3 修改类：`ReplLoop`

**变更点：**

1. **构造器**：接受 `StatusBar` 参数（由 App 创建后传入）
2. **新增字段**：`StatusBar statusBar`
3. **`run()` 方法**：
   - 启动时设置初始状态
   - 每轮循环：输入 → Agent → 更新状态（无 hide/show）
   - 注册 SIGWINCH handler
4. **`readMultiline()` 方法**：
   - 首行 prompt 改为 `"> "`
   - 多行续行 prompt 改为 `"... "`
   - 移除 `╰──` 下边框
5. **状态更新**：
   - Token 用量：从 `usageSink` 捕获
   - 模式变化：`/mode`、`/plan`、`/do` 后调用 `statusBar.update()`
   - 工作目录：启动时设置

### 5.4 修改类：`App`

**变更点：**

1. `buildLineReader()` 已创建 Terminal，但当前不暴露。需要改为返回 Terminal + LineReader
2. 创建 `StreamPrinter(terminal)` — 使用 terminal.writer()
3. 创建 `StatusBar(terminal)` — 传给 ReplLoop

**具体改动：**
```java
// App.main() 中：
var terminal = org.jline.terminal.TerminalBuilder.builder().system(true).build();
var reader = LineReaderBuilder.builder().terminal(terminal).build();
var statusBar = new StatusBar(terminal);
var printer = new StreamPrinter(terminal);

ReplLoop repl = new ReplLoop(raw, provider, printer, reader, registry, executor,
    engine, agentConfig, sessionArchive, coord, memoryManager, statusBar);
```

### 5.5 依赖变更

无需新增 Maven 依赖。`Status` 类在 `jline-terminal` 中（已通过 `jline-reader` 传递依赖）。

## 6. 终端兼容性

### 6.1 能力检测

`Status.getStatus(terminal)` 内部检查以下终端能力：
- `change_scroll_region` (csr)
- `save_cursor` (sc)
- `restore_cursor` (rc)
- `cursor_address` (cup)

如果不支持，`Status.getStatus()` 返回 `null`，StatusBar 的所有操作变为 no-op。

### 6.2 已知兼容终端

| 终端 | scroll region | 状态 |
|------|:---:|------|
| iTerm2 | ✅ | 完全支持 |
| macOS Terminal.app | ✅ | 完全支持 |
| Windows Terminal (WSL) | ✅ | 完全支持 |
| VS Code integrated terminal | ✅ | 完全支持 |
| tmux | ✅ | 完全支持 |
| screen | ✅ | 完全支持 |
| dumb / CI / piped | ❌ | 降级（无状态栏） |

### 6.3 Windows 兼容性

Windows 下较老的 cmd.exe 或 ConPTY 模拟环境中，scroll region (DECSTBM) 支持可能不稳定，状态栏可能漂移到屏幕中间。

**缓解措施：**
- `StatusBar` 构造器中，除了 `status != null` 检查外，增加输出验证
- 提供系统属性 `maplecode.statusbar.force=false`，用户可手动禁用
- 如果检测到 Windows + 非 Windows Terminal 组合，打印一行警告到 stderr

### 6.4 降级策略

当 `StatusBar.isSupported() == false`：
- 状态栏不显示（所有 update 为 no-op）
- 输入框上边框仍正常显示（纯 print，不依赖终端能力）
- 输出行为与当前版本完全一致（StreamPrinter 回退到 System.out）

## 7. 终端 Resize 处理

```java
// 在 ReplLoop.run() 中注册 SIGWINCH handler
terminal.handle(Terminal.Signal.WINCH, sig -> {
    statusBar.resize();
});
```

`StatusBar.resize()` 调用 `status.resize()`，JLine Status 内部会重新计算 scroll region 并重绘。

**已知限制：** 如果用户正在 `readLine()` 中编辑长文本时发生 resize，JLine 可能出现重绘错误。这是 JLine 的已知限制，属于可接受范围——resize 期间用户通常不会在编辑。

## 8. 测试策略

### 8.1 单元测试

- **`StatusBarTest`**：
  - mock Terminal（不支持 scroll region）→ 验证降级行为（no-op）
  - mock Terminal（支持 scroll region）→ 验证 `update()` 调用 `Status.update()`
  - 验证 `StatusState` 渲染逻辑（token 格式化、路径缩略、模式着色）
  - 验证 token 为 null/零时的格式：`tok:-/-` / `tok:0/0`

- **`StreamPrinterTest`**：
  - 注入 `PrintWriter(ByteArrayOutputStream)` → 验证输出内容
  - 验证 `terminal.writer()` 构造器路径

- **`ReplLoopTest`**（集成级）：
  - mock StatusBar → 验证 `update()` 调用时机（不在 Agent 期间调用 hide/show）
  - 验证输入框 prompt 内容（`>` / `...`）

### 8.2 手工测试

- 在 iTerm2 中运行，验证状态栏始终固定在底部
- 验证 Agent 流式输出时状态栏保持不动，文本在上方滚动
- 验证 Agent 结束后状态栏更新 token 用量
- 验证 `/mode`、`/plan` 等命令触发状态栏更新
- 验证终端 resize 后状态栏重绘
- 在 dumb terminal 中运行，验证降级正常
- 在 tmux 中运行，验证 scroll region 嵌套正常

## 9. 实现计划

### Phase 1：StatusBar 核心
- 新增 `StatusBar.java` + `StatusState` record
- 实现 `update()` / `resize()`
- 单元测试

### Phase 2：StreamPrinter 改造
- 引入 `Terminal` / `PrintWriter` 构造器
- `PrintStream` → `PrintWriter`
- 更新 `App.java` 中的创建方式
- 确保测试不受影响

### Phase 3：ReplLoop 集成
- 修改 `ReplLoop` 构造器，添加 `StatusBar` 参数
- 修改 `readMultiline()`：prompt 改为 `> ` / `... `
- 修改 `run()` 循环：去掉 hide/show，状态栏始终可见
- 注册 SIGWINCH handler

### Phase 4：状态数据流
- Token 用量：从 `usageSink` 捕获并传递给 StatusBar
- 模式变化：`/mode`、`/plan`、`/do` 后更新状态
- 工作目录：启动时设置

### Phase 5：润色与测试
- Token 数值格式化（k 后缀、null/零处理）
- 路径缩略（~ 替代 home）
- 终端 resize 测试
- 降级测试
- Windows 兼容性检查

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| StreamPrinter 切换到 terminal.writer() 后测试困难 | 测试需要 mock Terminal | 提供 PrintWriter 构造器，测试注入 ByteArrayOutputStream |
| Status scroll region 与 terminal.writer() 的同步 | 潜在光标错位 | JLine 内部对两者有同步机制；已验证 Status 源码的 save/restore cursor 流程 |
| 输入框 prompt 含 Unicode 宽字符 | JLine 光标定位偏移 | box-drawing 字符在主流终端中宽度一致（1 列）；JLine 3.27.0 对 Unicode 宽度处理成熟 |
| Windows ConPTY scroll region 不稳定 | 状态栏漂移 | 增加 Windows 终端类型检测 + 手动禁用参数 |
| TokenUsage 在 streaming 期间不可用 | 状态栏显示旧数据 | 显示上一轮的 token 用量，Agent 结束后更新 |
| readLine 编辑长文本时 resize | 重绘错误 | JLine 已知限制，可接受范围 |
