# MapleCode

一个极简的 Java 命令行 AI 对话工具。通过 SSE 流式转发 Anthropic Claude 或 OpenAI Chat Completions 的响应，支持多轮对话记忆。v1 不做 tool use。

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
- `/exit` 或 Ctrl+D —— 退出

## 测试

```
mvn test
```

## 项目结构

完整文件结构见 `docs/superpowers/specs/2026-07-01-maple-code-design.md` §3；v1 明确不做的功能（tool use、持久化等）见 §12。