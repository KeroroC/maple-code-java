# MapleCode TUI 状态栏与输入框设计

## 1. 概述

为 MapleCode REPL 添加类 Claude Code 的 TUI 体验：底部固定状态栏 + 带边框的输入区域。利用 JLine 3.27.0 内置的 `Status` 类（scroll region 技巧）实现固定状态栏，ANSI box-drawing 字符实现输入框边框。

## 2. 目标

- 底部固定状态栏，显示模型名称、Token 用量、当前模式、工作目录
- 带 box-drawing 边框的输入区域，视觉上与输出区分离
- Agent 输出时隐藏输入框和状态栏，输出区利用全部终端空间
- Agent 结束后恢复状态栏和输入框
- 不新增外部依赖（仅用 jline-terminal 已有的 Status 类）
- 支持终端 resize
- dumb terminal 降级：状态栏不可用时静默跳过，不影响核心功能

## 3. 非目标

- Alternate screen buffer
- 输出区边框
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
│ ╭─ > _                                               │  ← 输入框
│ ───────────────────────────────────────────────────  │  ← 状态栏
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

Token 用量格式：`tok:{input}k/{output}k`，数值 < 1000 显示原始值，>= 1000 显示 `x.yk`。

### 4.2 输入框

使用 Unicode box-drawing 字符：

**单行模式：**
```
╭─ > 用户输入的文本
╰──
```

**多行模式（`"""` 触发）：**
```
╭─ > """
│ 第一行
│ 第二行
╰──
```

- 上边框：`╭─ > ` 作为 `readLine()` 的 prompt 前缀，由 `print` 输出
- `readLine()` 的 prompt 设为 `│ ` （多行续行）或空字符串（单行，上边框已含 prompt）
- 下边框：`╰──` 在 `readLine()` 返回后输出

**实现细节：**
- 单行模式：`reader.readLine("╭─ > ")`，结束后 `println("╰──")`
- 多行模式：首行 `reader.readLine("╭─ > ")`，续行 `reader.readLine("│ ")`，结束后 `println("╰──")`
- prompt 直接传入 `readLine()`，由 JLine 处理光标定位和编辑，不额外 print 边框前缀
- 下边框在 `readLine()` 返回后输出

### 4.3 交互流程

```
启动:
  创建 Terminal, LineReader, Status
  status.update(初始状态)
  打印 banner

每轮循环:
  1. status.hide()               // 隐藏状态栏，释放底部空间
  2. readLine("╭─ > ")           // 用户输入（prompt 含上边框）
  3. println("╰──")              // 打印输入框下边框
  4. status.show()               // 恢复状态栏
  5. agent.run(input, sink)      // Agent 执行，输出在 scroll region 内滚动
  6. 更新 status 内容            // token 用量、模式等
  7. 回到 1
```

**Agent 运行期间：**
- Status 处于 hidden 状态，输出利用全部终端高度
- StreamPrinter 的输出直接到 stdout，在 scroll region 内自然滚动
- Agent 结束后 status.show() 恢复状态栏

**Ctrl-C 处理：**
- 输入时 Ctrl-C：`UserInterruptException` → `agent.cancel()` → 回到步骤 1
- Agent 运行时 Ctrl-C：`agent.cancel()`（现有逻辑不变）

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
├── void hide()
├── void show()
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

public void hide() {
    if (!supported) return;
    status.hide();
}

public void show() {
    if (!supported) return;
    if (state != null) update(state);
}
```

**渲染逻辑：**
- 使用 `AttributedStringBuilder` 构建带样式的单行文本
- 模型名：粗体
- Token 用量：默认样式
- 模式：根据模式着色（plan=黄色，strict=红色，permissive=绿色，normal=默认）
- 工作目录：灰色
- 分隔符 ` │ `：灰色

### 5.2 修改类：`ReplLoop`

**变更点：**

1. **构造器**：新增 `Terminal terminal` 参数（用于创建 StatusBar）
2. **新增字段**：`StatusBar statusBar`
3. **`run()` 方法**：
   - 启动时初始化 StatusBar
   - 每轮循环：`statusBar.hide()` → 输入 → `statusBar.show()` → Agent → 更新状态
4. **`readMultiline()` 方法**：
   - 首行 prompt 改为 `"╭─ > "`
   - 多行续行 prompt 改为 `"│ "`
   - 输入结束后打印 `"╰──"`
5. **状态更新**：
   - 监听 `TokenUsage`（从 `usageSink` 获取）
   - 监听模式变化（`/mode`、`/plan`、`/do`、`/cancel`）
   - 工作目录在启动时获取，运行中不变

### 5.3 修改类：`StreamPrinter`

**最小变更：**

StreamPrinter 的输出方式不变（仍用 `PrintStream`），因为 JLine Status 的 scroll region 机制对 stdout 输出透明。

但需要移除 `usage()` 方法中的 `[usage: ...]` 打印——token 用量改为显示在状态栏。或者保留两处（状态栏 + 详细日志），取决于用户偏好。

**建议：** 保留 `usage()` 方法的详细输出（给需要看完整信息的用户），状态栏显示精简摘要。

### 5.4 修改类：`App`

**变更点：**

1. `buildLineReader()` 返回 `Terminal` 和 `LineReader`（或让 ReplLoop 自己获取 terminal）
2. 传递 `Terminal` 给 `ReplLoop` 构造器

**方案：** ReplLoop 通过 `reader.getTerminal()` 获取 Terminal，无需修改 `buildLineReader()`。

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
| tmux | ✅ | 完全支持 |
| screen | ✅ | 完全支持 |
| dumb / CI / piped | ❌ | 降级（无状态栏） |
| VS Code integrated terminal | ✅ | 完全支持 |

### 6.3 降级策略

当 `StatusBar.isSupported() == false`：
- 状态栏不显示（所有 update/hide/show 为 no-op）
- 输入框边框仍正常显示（纯 ANSI print，不依赖终端能力）
- 输出行为与当前版本完全一致

## 7. 终端 Resize 处理

```java
// 在 ReplLoop.run() 中注册 SIGWINCH handler
terminal.handle(Terminal.Signal.WINCH, sig -> {
    statusBar.resize();
});
```

`StatusBar.resize()` 调用 `status.resize()`，JLine Status 内部会重新计算 scroll region 并重绘。

## 8. 测试策略

### 8.1 单元测试

- **`StatusBarTest`**：
  - mock Terminal（不支持 scroll region）→ 验证降级行为（no-op）
  - mock Terminal（支持 scroll region）→ 验证 `update()` 调用 `Status.update()`
  - 验证 `StatusState` 渲染逻辑（token 格式化、路径缩略、模式着色）

- **`ReplLoopTest`**（集成级）：
  - mock StatusBar → 验证 hide/show 调用时机
  - 验证输入框边框输出

### 8.2 手工测试

- 在 iTerm2 中运行，验证状态栏固定在底部
- 验证 Agent 输出时状态栏隐藏
- 验证 Agent 结束后状态栏恢复并更新 token 用量
- 验证 `/mode`、`/plan` 等命令触发状态栏更新
- 验证终端 resize 后状态栏重绘
- 在 dumb terminal 中运行，验证降级正常

## 9. 实现计划

### Phase 1：StatusBar 核心
- 新增 `StatusBar.java` + `StatusState` record
- 实现 `update()` / `hide()` / `show()` / `resize()`
- 单元测试

### Phase 2：ReplLoop 集成
- 修改 `ReplLoop` 构造器，添加 `StatusBar` 字段
- 修改 `run()` 循环：hide → input → show → agent → update
- 修改 `readMultiline()`：输入框边框
- 注册 SIGWINCH handler

### Phase 3：状态数据流
- Token 用量：从 `usageSink` 捕获并传递给 StatusBar
- 模式变化：`/mode`、`/plan`、`/do`、`/cancel` 后更新状态
- 工作目录：启动时设置

### Phase 4：润色与测试
- Token 数值格式化（k 后缀）
- 路径缩略（~ 替代 home）
- 终端 resize 测试
- 降级测试

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Status scroll region 与 System.out 冲突 | 输出错位 | JLine Status 设计为与 stdout 共存，save/restore cursor 保证安全 |
| 输入框边框在窄终端上截断 | 视觉不美观 | 边框宽度 = min(内容长度, terminal width - 2) |
| readLine() prompt 影响 Status scroll region | 行数计算错误 | hide() 在 readLine 前调用，readLine 期间无 Status 干扰 |
| TokenUsage 在 streaming 期间不可用 | 状态栏显示旧数据 | 显示上一轮的 token 用量，Agent 结束后更新 |
