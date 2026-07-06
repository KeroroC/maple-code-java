# MapleCode

一个极简的 Java 命令行 AI 对话工具。通过 SSE 流式转发 Anthropic Claude 或 OpenAI Chat Completions 的响应，支持多轮对话记忆、工具调用和 Agent Loop（模型自主循环调工具）。

## 构建

需要 Java 21 和 Maven 3.9+。

```
mvn package
```

产出 `target/maple-code-java-0.1.0.jar`。

## 配置

把 `maplecode.yaml.example` 复制成 `maplecode.yaml`（或 `~/.maplecode/config.yaml`），把 `api_key` 设成 `${ENV_VAR}` 占位符。

```
export ANTHROPIC_API_KEY=sk-ant-...
```

OpenAI 配置示例：

```yaml
protocol: openai
model: gpt-4o
base_url: https://api.openai.com/v1
api_key: ${OPENAI_API_KEY}
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

- `"""` 开启多行输入；单独一行 `"""` 结束
- `/clear` —— 清空消息历史
- `/tools` —— 列出可用工具
- `/plan <query>` —— 规划模式（只读工具，模型只分析不执行）
- `/do` —— 执行上一条规划
- `/cancel` —— 取消当前执行
- `/mode [strict|default|permissive]` —— 查看或切换权限模式
- `/exit` 或 Ctrl+D —— 退出

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

## 项目结构

完整文件结构见 `docs/superpowers/specs/2026-07-01-maple-code-design.md` §3。

### 核心组件

- **Provider 层**：Anthropic 和 OpenAI 双协议支持，统一的 `LlmProvider` 接口
- **工具系统**：`Tool` 接口 + `ToolRegistry` + `ToolExecutor`，支持 6 个内置工具
- **Agent Loop**：`AgentLoop` ReAct 循环，安全分批并发（只读并行 + 有副作用串行），Plan Mode 双层防御
- **权限系统**：五层 `PermissionCheck` 管道 + `PermissionEngine` + 三档模式 + HITL
- **流式解析**：`StreamChunk` sealed 接口，支持文本、思考、工具调用三种 chunk 类型
- **会话管理**：`ChatSession` 管理对话历史，支持多轮上下文
- **REPL**：JLine 3 驱动的交互式命令行，支持多行输入和斜杠命令

### 设计文档

- `docs/superpowers/specs/2026-07-01-maple-code-design.md` —— v1 流式 REPL 设计规格
- `docs/superpowers/specs/2026-07-03-maple-code-tool-system-design.md` —— v2 工具系统设计规格
- `docs/superpowers/specs/2026-07-04-maple-code-agent-loop-design.md` —— v3 Agent Loop 设计规格
- `docs/superpowers/specs/2026-07-06-maple-code-permission-system-design.md` —— v4 权限系统设计规格
