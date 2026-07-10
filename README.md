# MapleCode

一个极简的 Java 命令行 AI 对话工具。通过 SSE 流式转发 Anthropic Claude 或 OpenAI Chat Completions 的响应，支持多轮对话记忆、工具调用和 Agent Loop（模型自主循环调工具）。

## 构建

需要 Java 21 和 Maven 3.9+。

```
mvn package
```

产出 `target/maple-code-java-0.1.0.jar`（已 shade，可直接执行）。

## 配置

把 `maplecode.yaml.example` 复制成 `maplecode.yaml`（或 `~/.maplecode/config.yaml`），把 `api_key` 设成 `${ENV_VAR}` 占位符。

```
export ANTHROPIC_API_KEY=sk-ant-...
```

配置文件查找顺序（命中即用）：`--config <path>` → `./maplecode.yaml` → `~/.maplecode/config.yaml`。

OpenAI 配置示例：

```yaml
protocol: openai
model: gpt-4o
base_url: https://api.openai.com/v1
api_key: ${OPENAI_API_KEY}
```

### 系统提示词

顶层 `system_prompt` 字段会作为额外 system block 注入，与内嵌块叠加：

```yaml
system_prompt: |
  You are MapleCode, a helpful coding assistant. Be concise.
```

### Extended Thinking

仅 Anthropic。OpenAI 忽略此块。

| 格式 | 使用场景 |
|---|---|
| `type: adaptive` + `effort: low\|medium\|high` | 所有现行 Claude 模型（Opus 4.7、Opus 4.6、Sonnet 4.6） |
| `type: enabled` + `budget_tokens: N`（>= 1024） | 旧版 —— 仅 Opus 4.6 / Sonnet 4.6；Opus 4.7 返回 HTTP 400 |

## 运行

```
java -jar target/maple-code-java-0.1.0.jar
# 或指定配置文件：
java -jar target/maple-code-java-0.1.0.jar --config /path/to/config.yaml
```

REPL 内：

| 命令 | 说明 |
|---|---|
| `"""` | 开启多行输入；单独一行 `"""` 结束 |
| `/help [command]` | 显示命令帮助（别名：`/h`、`/?`） |
| `/clear` | 清空消息历史 |
| `/compact` | 手动压缩上下文（触发摘要 + offload） |
| `/tools` | 列出可用工具（内置 + MCP） |
| `/plan <query>` | 规划模式（只读工具，模型只分析不执行） |
| `/do` | 执行上一条规划 |
| `/review [关注点]` | 审查当前 Git 变更 |
| `/memory <list\|clear\|extract>` | 记忆管理 |
| `/new` | 归档当前会话并清空 |
| `/resume [id]` | 加载历史会话 |
| `/status` | 显示当前状态（模型、token、模式、目录） |
| `/mode [strict\|default\|permissive]` | 查看或切换权限模式 |
| `/exit` 或 Ctrl+D | 退出 |

**Esc 键：** Agent 流式输出期间单击 Esc 取消当前响应；输入期间 500ms 内双击 Esc 清空输入；多行输入期间双击 Esc 丢弃整段内容。

## 工具系统

支持 6 个核心工具，模型可以自动调用：

| 工具 | 功能 |
|---|---|
| `read_file` | 读取文件内容，支持行号显示和分页 |
| `write_file` | 写入文件（覆盖），父目录必须存在 |
| `edit_file` | 精确替换文件中的文本（严格唯一匹配） |
| `exec` | 执行 shell 命令，支持超时控制 |
| `glob` | 按模式查找文件（支持通配符） |
| `grep` | 按正则表达式搜索代码内容 |

**工具调用流程：**
1. 模型识别需要使用工具时，会发送 tool_use 请求
2. 权限系统检查（黑名单 → 路径沙箱 → 规则 → 模式 → 人回路）
3. 通过后自动执行工具并返回结果
4. 模型根据工具结果继续对话

**错误处理：**
- 工具执行失败时，错误信息会返回给模型，让模型可以调整策略
- 权限拒绝时，`权限拒绝: <原因>` 返回给模型，Agent Loop 不中断
- 所有工具错误都不会中断 REPL，用户可以继续对话

## MCP 客户端

支持 Model Context Protocol (MCP)，可连接外部工具服务器。MCP 工具走完整权限管道，和内置工具一视同仁。

**配置**（三层，优先级从低到高，子 map deep-merge）：

| 文件 | 说明 |
|---|---|
| `~/.maplecode/mcp_servers.yaml` | 用户全局（跨项目共享） |
| `<项目>/.maplecode/mcp_servers.yaml` | 项目级（应入 git） |
| `<项目>/.maplecode/mcp_servers.local.yaml` | 项目本地（应入 .gitignore） |

**Transport 类型：**

| 类型 | 说明 |
|---|---|
| `stdio` | 子进程 + 行分隔 JSON，默认超时 30s |
| `http` | StreamableHttp POST，支持 `${ENV}` 展开 headers |

**示例**（`mcp_servers.yaml`）：

```yaml
servers:
  github:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}
  notion:
    type: http
    url: https://mcp.notion.example.com/mcp
    headers:
      Authorization: "Bearer ${NOTION_TOKEN}"
```

MCP 工具命名空间为 `mcp__<server>__<tool>`，在 `/tools` 中可见。

## 上下文管理

当对话 token 数接近上下文窗口时，自动触发压缩：摘要旧消息 + offload 已执行的工具结果。

```yaml
context_window: 200000          # 输入预算（默认 200000，覆盖 Sonnet 4.6 / Opus 4.7）
summarizer_model: claude-haiku-4-5  # 摘要专用模型（可选，默认复用主模型）
```

也可用 `/compact` 手动触发。

## 记忆系统

每轮 Agent Loop 结束后异步调 LLM 分析对话，自动新增/修改/删除长期记忆。记忆在下次启动时注入系统提示词，实现跨会话知识积累。

```yaml
memory:
  enabled: true
  memory_model: claude-haiku-4-5    # 记忆提取用模型（可选，默认复用主模型）
  max_context_messages: 10          # 提取时看最近几条消息（默认 10）
```

记忆按 scope 分为 `user`（跨项目）和 `project`（当前项目），存储在 `~/.maplecode/memory/`下。用 `/memory list` 查看、`/memory clear` 清空、`/memory extract` 手动触发提取。

## 会话归档

`/new` 将当前会话归档后清空，`/resume` 列出历史会话供选择恢复。归档存储在 `~/.maplecode/sessions/` 下。

```yaml
session_archive:
  enabled: true
  max_sessions: 50            # 最多保留几个归档（默认 50）
```

## 权限系统

五层防御，所有工具调用都经过权限检查：

| 层 | 说明 |
|---|---|
| 黑名单 | 12 条硬编码正则拦截高危命令（rm -rf /、sudo、fork bomb 等），不可配置 |
| 路径沙箱 | 文件操作必须在项目目录内，解析符号链接防逃逸 |
| 规则引擎 | 三层 YAML 规则（用户全局 / 项目 / 项目本地），first-match-wins |
| 权限模式 | strict（未匹配直接拒绝）/ default（未匹配走人回路）/ permissive（未匹配直接放行） |
| 人在回路 | default 模式下弹 4 选 1：本次允许 / 本会话允许 / 本项目允许 / 拒绝 |

**规则文件**（优先级从低到高）：

| 文件 | 说明 |
|---|---|
| `~/.maplecode/permissions.yaml` | 用户全局规则 |
| `<项目>/.maplecode/permissions.yaml` | 项目级规则（应入 git） |
| `<项目>/.maplecode/permissions.local.yaml` | 项目本地规则（应入 .gitignore） |

**规则格式**：

```yaml
rules:
  - tool: exec
    pattern: "git *"
    action: allow
  - tool: read_file
    pattern: "**/.env"
    action: deny
```

exec 工具的 pattern 用 shell glob（`*` = 任意非空格序列），其他工具用标准 glob（`**/*.java`）。

## 测试

```
mvn test
```

仓库里没有 `*IT.java` 集成测试——端到端 smoke 是手工跑。

## 项目结构

### 核心组件

- **Provider 层**：Anthropic 和 OpenAI 双协议支持，统一的 `LlmProvider` 接口
- **工具系统**：`Tool` 接口 + `ToolRegistry` + `ToolExecutor`，支持 6 个内置工具
- **Agent Loop**：`AgentLoop` ReAct 循环，安全分批并发（只读并行 + 有副作用串行），Plan Mode 双层防御
- **权限系统**：五层 `PermissionCheck` 管道 + `PermissionEngine` + 三档模式 + HITL
- **MCP 客户端**：stdio / http 双 transport，工具自动注册到 `ToolRegistry`
- **上下文管理**：`CompactCoordinator` 自动摘要 + offload，token 估算驱动
- **记忆系统**：`MemoryManager` + `MemoryExtractor`，跨会话知识积累
- **会话归档**：`SessionArchive` + `SessionWriter` / `SessionReader`，`/new` 归档 + `/resume` 恢复
- **命令框架**：`Command` 接口 + `CommandRegistry` + `CommandCompleter`，13 个内置斜杠命令
- **流式解析**：`StreamChunk` sealed 接口，支持文本、思考、工具调用三种 chunk 类型
- **会话管理**：`ChatSession` 管理对话历史，支持 `ContentBlock` 多内容类型
- **REPL**：JLine 3 驱动的交互式命令行，`StatusBar` 底部状态栏 + `EscapeController` 按键管理

### 设计文档

- `docs/superpowers/specs/2026-07-01-maple-code-design.md` —— v1 流式 REPL
- `docs/superpowers/specs/2026-07-03-maple-code-tool-system-design.md` —— v2 工具系统
- `docs/superpowers/specs/2026-07-04-maple-code-agent-loop-design.md` —— v3 Agent Loop
- `docs/superpowers/specs/2026-07-06-maple-code-permission-system-design.md` —— v4 权限系统
- `docs/superpowers/specs/2026-07-05-maple-code-system-prompt-design.md` —— v5 系统提示词
- `docs/superpowers/specs/2026-07-06-maple-code-mcp-client-design.md` —— v5 MCP 客户端
- `docs/superpowers/specs/2026-07-07-maple-code-context-management-design.md` —— v6 上下文管理
- `docs/superpowers/specs/2026-07-08-maple-code-agents-md-loader-design.md` —— v7.1 AGENTS.md 加载器
- `docs/superpowers/specs/2026-07-08-maple-code-memory-design.md` —— v7.2 记忆系统
- `docs/superpowers/specs/2026-07-08-maple-code-session-archive-design.md` —— v7.3 会话归档
- `docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md` —— v7.4 命令框架
- `docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md` —— v7.5 TUI 状态栏
- `docs/superpowers/specs/2026-07-10-maple-code-escape-controls-design.md` —— v7.6 Escape 控制
