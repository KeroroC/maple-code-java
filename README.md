# MapleCode

一个极简的 Java 命令行 AI 对话工具。通过 SSE 流式转发 Anthropic Claude 或 OpenAI Chat Completions 的响应，支持多轮对话记忆和工具调用。

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
2. 程序自动执行工具并返回结果
3. 模型根据工具结果继续对话

**错误处理：**
- 工具执行失败时，错误信息会返回给模型，让模型可以调整策略
- 所有工具错误都不会中断 REPL，用户可以继续对话

## 测试

```
mvn test
```

## 项目结构

完整文件结构见 `docs/superpowers/specs/2026-07-01-maple-code-design.md` §3。

### 核心组件

- **Provider 层**：Anthropic 和 OpenAI 双协议支持，统一的 `LlmProvider` 接口
- **工具系统**：`Tool` 接口 + `ToolRegistry` + `ToolExecutor`，支持 6 个内置工具
- **流式解析**：`StreamChunk` sealed 接口，支持文本、思考、工具调用三种 chunk 类型
- **会话管理**：`ChatSession` 管理对话历史，支持多轮上下文
- **REPL**：JLine 3 驱动的交互式命令行，支持多行输入和工具调用

### 设计文档

- `docs/superpowers/specs/2026-07-01-maple-code-design.md` —— v1 设计规格
- `docs/superpowers/specs/2026-07-03-maple-code-tool-system-design.md` —— 工具系统设计规格
- `docs/superpowers/plans/2026-07-03-maple-code-tool-system.md` —— 工具系统实现计划
