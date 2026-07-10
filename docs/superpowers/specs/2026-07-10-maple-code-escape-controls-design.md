# MapleCode Esc 交互控制设计

**日期**：2026-07-10  
**状态**：已获用户设计批准，待书面规格复核  
**范围**：移除 `/cancel` 命令；Agent 流式响应期间单击 Esc 立即取消；用户输入期间 500ms 内双击 Esc 清空输入

## 1. 背景

MapleCode 当前通过同步的 `AgentLoop.run()` 执行模型请求。`/cancel` 命令注册在命令框架中，但 REPL 在 Agent 执行期间不会读取新的命令，因此它无法在运行中真正取消 Agent；空闲时执行 `/cancel` 反而会把取消标志遗留给下一轮。

本次改动以键盘 Esc 取代 `/cancel`：

- Agent 正在流式输出时，按一次 Esc 立即终止当前流式响应和本轮 Agent Loop。
- 用户正在编辑输入时，500ms 内连续按两次 Esc 清空输入区。
- `"""` 多行输入模式下，双击 Esc 丢弃整段已累计内容并返回新的主提示符。

## 2. 目标

1. 删除 `/cancel` 命令及其帮助、补全和测试入口。
2. Agent 流式响应期间单击 Esc 后：
   - 不再输出后续文本；
   - 不执行尚未开始的工具；
   - 不追加部分 assistant 或 tool 消息；
   - 发出 `AgentStop(USER_CANCELLED)`；
   - 不触发本轮长期记忆提取。
3. 输入阶段双击 Esc：
   - 两次 Esc 间隔不超过 500ms；
   - 普通输入清空当前 JLine buffer，但继续停留在当前提示符；
   - 多行输入丢弃整段内容并返回新的 `> `；
   - 单次 Esc 超时后无操作；
   - 方向键等以 Esc 开头的终端序列保持原有行为。
4. 不把本次改动扩大为异步 REPL 或并发输出重构。

## 3. 非目标

- 不支持在工具进程执行期间强制终止 `exec` 子进程。
- 不重构 `AgentLoop` 为后台任务。
- 不改变 Ctrl-C、Ctrl-D、权限确认或状态栏的既有语义。
- 不保留 `/cancel` 的隐藏别名或兼容入口。
- 不缓存 Agent 输出期间输入的普通字符；除 Esc 外的字符直接忽略。

## 4. 方案选择

### 4.1 采用方案：状态化 Esc 控制器

新增 `EscapeController`，在同一终端上管理三个互斥状态：

| 状态 | 输入所有者 | Esc 行为 |
|---|---|---|
| `INPUT` | JLine `readLine()` | 500ms 内双 Esc 清空输入 |
| `AGENT_STREAMING` | `EscapeController` raw-mode 监听线程 | 单 Esc 调用 `AgentLoop.cancel()` |
| `INACTIVE` | 无或权限/HITL JLine | 控制器不读取终端 |

该方案保留同步 Agent 架构，只在模型流式阶段短暂接管终端读取，并在工具执行或权限确认前恢复终端。

### 4.2 未采用方案

1. **异步 Agent + 主线程持续 JLine**：交互统一，但会引入输出并发、权限输入、session 同步和状态栏重绘的大范围重构。
2. **全局 raw-mode 监听线程**：实现较短，但会与 JLine、方向键 Escape 序列和 HITL 输入竞争，可靠性不足。

## 5. 组件设计

### 5.1 `EscapeController`

新增 `src/main/java/com/maplecode/ui/EscapeController.java`，职责如下：

- 在 JLine 可编辑 keymap 上安装两个 widget：
  - 单 Esc：no-op；
  - `ESC ESC`：清空输入。
- 将相关 keymap 的 ambiguous timeout 设置为 500ms，使单 Esc 在超时后执行 no-op，双 Esc 在窗口内执行清空 widget。
- 普通输入时调用 `LineReader.getBuffer().clear()`，让 JLine 重绘空 buffer。
- 多行输入时设置 `multilineAbortRequested`，清空当前 buffer，并调用 `accept-line` 结束当前 `readLine()`；REPL 消费该标志后丢弃累计内容。
- Agent 流式阶段保存原终端属性、调用 `Terminal.enterRawMode()`、启动单个 daemon 监听线程。
- 监听线程从 `Terminal.reader()` 读取字符：读到 Esc 时调用取消回调并结束；其他字符忽略。
- `stopAgentStreaming()` 幂等停止监听并恢复原终端属性。
- 监听 I/O 异常时向 stderr 输出一条警告，恢复终端，不中断 Agent。

控制器不在工具执行或 HITL 权限确认期间读取终端，避免两个消费者竞争同一个输入流。

### 5.2 `AgentLoop` 取消语义

`AgentLoop.run()` 在每轮开始时把 `cancelled` 重置为 `false`，避免一次取消污染后续请求。`cancel()` 仅设置当前运行轮次的取消标志。

调用 provider 时使用 cancellation-aware sink：

```java
Consumer<StreamChunk> cancellableSink = chunk -> {
    if (cancelled) throw new CancellationException("agent cancelled");
    collector.accept(chunk);
};
```

`CancellationException` 可以由真实 SSE provider 或测试 provider 直接传播。`AgentLoop` 捕获后立即发出 `AgentStop(USER_CANCELLED)` 并返回。由于 assistant/tool 内容只在 provider 正常返回后写入 session，因此被取消的部分响应不会持久化。

在 provider 正常返回和工具执行之间再次检查取消标志，覆盖 Esc 恰好发生在最后一个流式 chunk 之后的竞态，确保未开始的工具不会执行。

### 5.3 `SseStreamReader`

`SseStreamReader.read()` 当前会把所有 `RuntimeException` 包装成 `ProviderException`。修改为先捕获并原样抛出 `CancellationException`，其他运行时异常继续包装。这保证取消在 `AgentLoop` 中表现为 `USER_CANCELLED`，而不是 `PROVIDER_ERROR`。

### 5.4 `ReplLoop` 状态切换

`ReplLoop` 使用一个包装后的 `Consumer<AgentEvent>` 同时驱动打印和 Esc 控制器：

- `IterationStart`：进入 `AGENT_STREAMING`，开始监听单 Esc。
- `BatchStart`：退出流式监听并恢复终端，然后执行工具和可能的权限确认。
- `AgentStop`：无条件停止监听和恢复终端。

包装 sink 同时记录最终 stop reason。普通对话完成后，只有非 `USER_CANCELLED` 才调用 `memoryManager.extractAsync(...)`。

输入读取期间，`ReplLoop` 告知控制器是否处于多行模式：

- 主提示符读取：普通输入模式。
- 读到 `"""` 后：进入多行模式。
- 每次续行 `readLine("... ")` 返回后检查并消费整段取消标志；若已设置，丢弃 `StringBuilder` 并返回空输入结果，使主循环显示新的 `> `。
- 正常闭合 `"""` 或 EOF 后退出多行模式。

### 5.5 `App` 与命令框架

- `App` 创建 `LineReader` 后创建 `EscapeController` 并安装输入 key binding。
- `ReplLoop` 构造器接收控制器。
- 不再注册 `CancelCommand`。
- 删除：
  - `src/main/java/com/maplecode/command/CancelCommand.java`
  - `src/test/java/com/maplecode/command/CancelCommandTest.java`
- `/help` 和命令补全依赖 `CommandRegistry` 动态生成，因此删除注册后自动移除 `/cancel`。

## 6. 时序

### 6.1 Agent 流式取消

```text
ReplLoop              EscapeController       AgentLoop             Provider
   |                         |                   |                    |
   | agent.run()             |                   |                    |
   |------------------------>| IterationStart    |                    |
   |                         | enterRawMode      |                    |
   |                         | start listener    |---- stream() ----->|
   |                         |<-- Esc            |<---- chunks -------|
   |                         | cancel() -------->|                    |
   |                         |                   | sink throws         |
   |                         |                   | CancellationException|
   |                         |                   | AgentStop(CANCELLED) |
   |<---------------------------------------------------------------|
   |                         | stop + restore    |                    |
```

### 6.2 多行输入清空

```text
readLine("> ") -> "\"\"\""
enter multiline mode
readLine("... ")
user presses Esc Esc within 500ms
widget: buffer.clear + mark abort + accept-line
ReplLoop consumes abort flag
discard accumulated StringBuilder
leave multiline mode
show new "> " prompt
```

## 7. 竞态与终端恢复

- `startAgentStreaming()` 和 `stopAgentStreaming()` 必须同步且幂等。
- 每次进入 raw mode 都保存进入前的 `Attributes`，停止时只恢复本次保存的值。
- 监听线程读到 Esc 后只设置取消状态，不直接修改 session、printer 或 JLine buffer。
- `AgentStop`、正常文本结束、provider 失败和取消路径都必须进入终端恢复逻辑。
- `BatchStart` 在工具执行前停止监听，确保 HITL 的 `LineReader` 是唯一输入消费者。
- 若监听线程尚未完全退出，停止逻辑以中断/短超时 join 收尾，但不得长期阻塞主线程。

## 8. 错误处理

| 场景 | 行为 |
|---|---|
| raw mode 启动失败 | stderr 警告；本轮继续但 Esc 取消不可用 |
| 监听读取失败 | stderr 警告；恢复终端；Agent 继续 |
| 取消发生在流式 chunk 中间 | 下一个 chunk 边界抛 `CancellationException` |
| 取消发生在最后一个 chunk 后 | provider 返回后的二次检查阻止工具执行 |
| 取消与正常 `AgentStop` 竞争 | 幂等停止监听，最终 stop reason 为 `USER_CANCELLED` |
| 双 Esc 时 buffer 已为空 | 保持为空；普通输入继续，多行输入仍终止整段 |
| 方向键/Alt 序列 | 由更长的既有 key binding 匹配，不触发双 Esc widget |

## 9. 测试设计

遵循 TDD，每项行为先写失败测试并确认失败原因。

### 9.1 `EscapeControllerTest`

- 安装后单 Esc 绑定到 no-op widget。
- `ESC ESC` 绑定到清空 widget，ambiguous timeout 为 500ms。
- 普通模式 widget 清空 buffer，不触发 `accept-line`。
- 多行模式 widget 设置整段取消标志并触发 `accept-line`。
- `consumeMultilineAbort()` 只返回一次 true。
- `startAgentStreaming()` 读到 Esc 时只调用一次取消回调。
- `stopAgentStreaming()` 多次调用仍只恢复一次终端属性。
- 非 Esc 字符不触发取消。

### 9.2 `AgentLoopTest`

- provider 发出首个文本 chunk 后触发 `cancel()`，下一 chunk 导致 `USER_CANCELLED`。
- Esc 后的文本不进入 collector/session。
- 已累积但尚未执行的 tool use 不执行。
- 取消后再次调用 `run()` 可以正常请求 provider，证明状态已复位。
- provider 返回后、工具执行前取消仍阻止工具调用。

### 9.3 `SseStreamReaderTest`

- event sink 抛 `CancellationException` 时原样传播。
- 其他运行时异常仍包装为 `ProviderException`。

### 9.4 `ReplLoop` 与命令测试

- 多行取消后累计内容被丢弃并继续下一次主提示符读取。
- `USER_CANCELLED` 不触发记忆提取。
- AgentEvent 的 `IterationStart`、`BatchStart`、`AgentStop` 分别启动或停止监听。
- `CommandRegistry`、帮助列表和补全候选中不存在 `cancel`。
- 删除旧 `CancelCommandTest`。

### 9.5 回归验证

```bash
mvn test
```

若运行环境为禁止 Mockito self-attach 的较新 JDK，则使用项目当前已验证的测试参数：

```bash
mvn test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

## 10. 文档更新

- 更新 `docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md`，删除 `/cancel`，记录 Esc 取消入口。
- 更新 `docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md`，替换 Ctrl-C/命令取消描述并补充双 Esc 输入清空。
- 更新项目主说明中的 REPL 命令表，确保 `/cancel` 不再被列出。

## 11. 验收标准

1. `/cancel` 被解析为未知命令，且不出现在帮助和 Tab 补全中。
2. Agent 流式输出期间单击 Esc，在下一个流式 chunk 边界停止输出并结束为 `USER_CANCELLED`。
3. 取消后不会执行尚未开始的工具，也不会持久化部分 assistant/tool 消息。
4. 下一次正常输入不受上一轮取消标志影响。
5. 普通输入期间 500ms 内双击 Esc 清空整行并继续输入。
6. 单击 Esc 超时无操作，方向键行为不变。
7. 多行输入期间双击 Esc 丢弃整段内容并返回主提示符。
8. 工具执行和 HITL 权限确认期间不存在竞争终端读取。
9. 所有单元测试和完整 Maven 测试通过。
