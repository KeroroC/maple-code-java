# MapleCode MCP 客户端设计（v5）

> 日期：2026-07-06
> 阶段：v5（MCP Client + 工具发现）
> 取代：无（首版）
> 前序：v1 流式 REPL · v2 工具系统 · v3 Agent Loop · v4 权限系统

## 1. 目标与非目标

### 1.1 目标

让 MapleCode 在启动时按 MCP（Model Context Protocol）发现外部 server 提供的工具，并把它们无缝接入既有 `ToolRegistry`。模型在工具列表里看到的工具名是稳定的（`mcp__<server>__<tool>`），于是它的所有调用都走完整 `PermissionEngine` 管道、和 6 个内置工具一样对待。

### 1.2 非目标（这一版显式不做）

- 不实现 MCP `resources` / `prompts` / `sampling` / `roots` 能力
- 不实现 server 健康检查、自动重连或指数退避
- 不处理 server→client notifications（包括 `tools/list_changed` 和 progress 通知）
- 不实现 Streamable HTTP 的可恢复断点续传（resumability / event-id 重放）
- 不实现 MCP auth（OAuth / Dynamic Client Registration）；仅用静态 `headers` + `${ENV}` 展开
- 不改 `ToolRegistry` 既有 4 个方法（`all` / `get` / `isReadOnly` / `readOnly`）的对外契约；只把硬编码 `READ_ONLY` 常量改成可注入集合参数（带向后兼容默认）
- 不持久化 MCP session state 到磁盘；每次启动 fresh
- 不在进程 stdin/stdout 写任何会污染 MCP wire 的调试日志；诊断走 `[mcp:<server>:stderr] ` 前缀到 stderr

## 2. 架构

### 2.1 装配图

```
App.main
  ├─ (现有 LlmProvider / PermissionEngine 装配不变)
  ├─ McpServerConfigLoader.loadAll(cwd, userMcpFile)
  │     ~/.maplecode/mcp_servers.yaml          (低)
  │       > <cwd>/.maplecode/mcp_servers.yaml  (中)
  │       > <cwd>/.maplecode/mcp_servers.local.yaml (高)
  ├─ McpClientBootstrap.start(specs, totalTimeoutMs)
  │     对每个 spec 并发：
  │       - 创建 transport（Stdio | StreamableHttp）
  │       - initialize handshake（含 protocolVersion 校验）
  │       - tools/list
  │     任一 server 失败 → WARN 一行 → 丢弃；其余继续
  ├─ McpClient (per server)
  │     - 持有 transport + JsonRpc
  │     - 缓存 serverInfo / capabilities / sessionId
  │     - callTool(name, args) -> 标准化字符串结果
  ├─ McpToolAdapter (匿名 Tool, per 远端 tool)
  │     - name() = mcp__<server>__<tool>
  │     - description() = "[mcp:<server>] " + 原 description
  │     - execute(args, ctx) → McpClient.callTool + content[] text-only 提取
  ├─ ToolRegistry    ← 注册 6 个内置 + 所有 MCP tool（构造期 name 冲突检查仍触发）
  ├─ PermissionEngine, ToolExecutor       ← 既有不变
  ├─ shutdownHook    ← Runtime.addShutdownHook 关 stdio 进程 / http 资源
```

### 2.2 抽象层级

| 组件 | 职责 | 不做什么 |
|---|---|---|
| `McpServerSpec` | 一条 YAML 条目的不可变 record；紧凑构造器做字段校验 | 不持有连接 / 状态 |
| `McpTransport` (interface) | `send(json)` + `close(cause)` + 暴露 `Consumer<JsonNode>` 进站回调 | 不懂协议语义 |
| `JsonRpcRequest` / `Response` / `Error` | wire 视图 + 序列化 / 反序列化（jackson） | 不做 IO |
| `JsonRpc` | id 配对、待回包队列、per-call 超时清理 | 不做 IO；用 transport 回调推入站 |
| `McpTransport.Stdio` | `ProcessBuilder` 启子进程，写行分隔 JSON 到 stdin，stdout 行线程读 | 不懂协议 |
| `McpTransport.StreamableHttp` | `HttpClient` POST + 解析 SSE / 单帧 JSON 响应；保留 `mcp-session-id` | 不懂协议 |
| `McpClient` | initialize 握手、能力声明、`listTools()` 缓存、`callTool(name, args)` 入口 | 不懂 transport 细节 |
| `McpToolAdapter` | 把 `McpToolDesc` 包装成既有 `Tool` 接口；执行时提取 text-only content | 不持有持久状态 |
| `McpClientBootstrap` | 并发拉起每 server，总预算降级；返回 `Map<String, McpClient>` | 不做单 server 重试 |

### 2.3 package 布局

```
com.maplecode.mcp
├── config
│   ├── McpServerConfigLoader
│   └── McpServerSpec (sealed record)
├── transport
│   ├── McpTransport (interface)
│   ├── Stdio
│   └── StreamableHttp
├── rpc
│   ├── JsonRpc
│   ├── JsonRpcRequest / Response / Error (records)
│   └── McpTimeoutException / McpConnectionException / McpProtocolException
├── client
│   ├── McpClient
│   └── McpClientBootstrap
└── adapter
    └── McpToolAdapter
```

## 3. wire 格式 + 传输

### 3.1 JSON-RPC 2.0

请求：
```json
{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
 "params": {"name": "create_issue", "arguments": {"repo": "foo"}}}
```

成功响应：
```json
{"jsonrpc": "2.0", "id": 1,
 "result": {"content": [{"type": "text", "text": "ok"}], "isError": false}}
```

错误响应（MCP 扩展 -32000..-32099 用来给 server 自定义错误）：
```json
{"jsonrpc": "2.0", "id": 1, "error": {"code": -32601, "message": "Method not found"}}
```

通知（server→client，无 `id`）——本版**整段丢弃**。

### 3.2 `JsonRpc` 运行时状态

- `AtomicLong nextId = 1`
- `ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending`
- 一个 daemon `ScheduledExecutorService` 1 pool，专门跑 per-call 超时
- 入口 `send(method, params) -> JsonNode future`:
  1. `nextId.getAndIncrement()` 拿到 id，构造 request
  2. 把 future 放进 `pending`
  3. 注册 per-call 超时（`AppConfig.Timeouts.readDuration`）→ 超时则 `pending.remove(id).completeExceptionally(McpTimeoutException)`
  4. 序列化后扔给 `transport.send(jsonNode)`；返回 future
- 入口 `handle(JsonNode frame)`:
  - 有 `id` 且 `pending` 包含 → 配对，写 result 或 error
  - 有 `id` 但 `pending` 不含 → 已超时 / 启动期外 → 静默丢弃
  - 无 `id` → 通知，丢弃

### 3.3 Stdio transport

- 构造接 `McpServerSpec.Stdio`：`new ProcessBuilder(command, args).redirectError(PIPE)`
- `send(json)`：把 `\n` 终止的单行 JSON 写到 subprocess stdin（`BufferedWriter`，显式 flush 每帧）
- daemon 线程读 stdout：`BufferedReader.readLine()` → j 反序列化 → `JsonRpc.handle`
- stderr 行原样转 `System.err`，前缀 `[<server>:stderr] `
- 进程退出（exit code / killed）→ `JsonRpc.failAllPending(McpConnectionException)`
- `close(cause)`：写 EOF、`destroyForcibly` 进程

### 3.4 Streamable HTTP transport

- 构造接 `McpServerSpec.Http`，用项目现有 `AppConfig.Timeouts.connectDuration` 配置 `HttpClient.connectTimeout`
- `send(json)`：POST 到 `url`
  - header：`Content-Type: application/json`、`Accept: application/json, text/event-stream`
  - 自定义 headers（已走 `${ENV}` 展开）
  - 如已收到 server `initialize` 响应里的 `Mcp-Session-Id` header，则透传
- 解析响应：
  - `Content-Type: text/event-stream` → 用现有 `SseStreamReader` 按行切 `eventType=message data=...`，data 字段再解析为 JSON-RPC
  - `Content-Type: application/json` → 直接当单帧解析
- Streamable HTTP 在本版等同于"一次 POST 一次 response stream"；没有双向事件流在 V1 处理
- HTTP 401 / 500 等错误 → `IOException`，由 bootstrap 视为启动失败

### 3.5 错误处理（按阶段划分）

| 阶段 | 出错情形 | 检测点 | 上抛 / 上抛形态 |
|---|---|---|---|
| 配置加载 | 字段缺失 / 类型错 / env 缺失 | `McpServerSpec` 紧凑构造器 / `McpServerConfigLoader` | `ConfigException`（exit code 78） |
| 启动连接 | stdio spawn 失败 / http 连接超时 | `StdioTransport` / `StreamableHttp` 构造 & 首 send | bootstrap 捕获 → WARN，丢弃该 server |
| 握手 | 返回不支持 protocolVersion / capabilities 缺 `tools` | `McpClient` initialize 路径 | bootstrap 捕获 → WARN，丢弃该 server |
| 单次调用 | 连接已掉 | `tool.execute` → `McpConnectionException` | `ToolExecutor` 兜底 → `ToolResult.error("mcp[<server>] connection lost: ...")` |
| 单次调用 | 超时 | `JsonRpc` per-call timer | `McpTimeoutException` → `ToolResult.error("mcp[<server>:<tool>] call timed out after Ns")` |
| 单次调用 | server JSON-RPC error | `JsonRpc.handle` 解析 error 字段 | 抛 `McpProtocolException(code, msg)` → `ToolResult.error("mcp[<server>:<tool>] server error: <msg> (code N)")` |
| 单次调用 | 运行期 HTTP 4xx/5xx（session 过期 / server 暂时不可用） | StreamableHttp 解析响应 | `IOException` → 抛 `McpConnectionException` → `ToolResult.error("mcp[<server>] HTTP NNN: <reason>")` |

错误消息永远只点 server 名 + tool 名，不回贴原始参数原文（避免日志泄漏）。

## 4. 配置格式 + 合并

### 4.1 搜索顺序

照搬 `PermissionFileLoader` 模式：

| 优先级 | 路径 | 用途 |
|---|---|---|
| 低 | `~/.maplecode/mcp_servers.yaml` | 用户全局（跨项目共享） |
| 中 | `<cwd>/.maplecode/mcp_servers.yaml` | 项目级（应入 git） |
| 高 | `<cwd>/.maplecode/mcp_servers.local.yaml` | 项目本地（应入 .gitignore） |

合并：每层是顶层 `servers:` map（key = server name）。后出现的覆盖先出现的同 key；同 server 的 stdio / http 子 map 也 deep-merge。

### 4.2 主 `maplecode.yaml` 加的开关

```yaml
mcp_servers:
  enabled: true           # 默认 true；false 时跳过整个 MCP 阶段
  startup_timeout_ms: 5000  # 单 server 启动总预算，默认 5000
```

不开新顶层 key 都放进 `mcp_servers` 块里。

### 4.3 server 文件 schema

```yaml
# ~/.maplecode/mcp_servers.yaml
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

```yaml
# <cwd>/.maplecode/mcp_servers.yaml
servers:
  projectdb:
    type: stdio
    command: ./scripts/mcp-pg
    args: []
```

### 4.4 字段校验（`McpServerSpec` 紧凑构造器强制）

| 字段 | 约束 |
|---|---|
| `name`（来自 server 的 key） | 匹配 `[a-zA-Z0-9_-]+`；长度 1–32 |
| `type` | 必须是 `"stdio"` 或 `"http"` |
| stdio → `command` | 非空字符串 |
| stdio → `args` | 字符串数组（可空） |
| stdio → `env` | `Map<String,String>`；每个 value 走 `ConfigLoader.expandEnv`（把 `expandEnv` 改成 package-private） |
| http → `url` | 非空、以 `http://` 或 `https://` 开头 |
| http → `headers` | `Map<String,String>`；value 走 `ConfigLoader.expandEnv` |
| 任一独立字段不在合法集合 | throw `ConfigException`（沿用现有错误模式，退出码 78） |

### 4.5 ToolRegistry 冲突（启动期校验）

合并完成的最终 server 列表调 `tools/list`，拿到 `McpToolDesc` 后拼成 `mcp__<server>__<tool>`，然后和内置 6 个工具 + 同项目内其他 server 的 tool 名一起塞给 `new ToolRegistry(all)`。构造器保留现有 `throw new IllegalArgumentException("duplicate tool name: " + ...)`，错误信息升级：

```
duplicate tool name 'read_file': already registered as built-in earlier;
remove the MCP server 'foo' tool 'read_file' or rename it in mcp_servers.yaml.
```

### 4.6 ToolRegistry 的 `isReadOnly` 调整

把硬编码 `Set.of("read_file", "glob", "grep")` 改成可注入集合参数，**默认值仍是原 `READ_ONLY` 常量**：

```java
public ToolRegistry(List<Tool> tools) { this(tools, READ_ONLY_DEFAULT); }
public ToolRegistry(List<Tool> tools, Set<String> readOnlyNames) { ... }
```

MCP tool 不进集合 → 全部按 unsafe 算 → `Batch.partition` 串行。语义上向后兼容（既有三个工具名仍在默认集合）。

## 5. App.main 接入

```java
// 1. 解析 mcp_servers 配置
Path userMcpFile = Paths.get(System.getProperty("user.home"),
                              ".maplecode", "mcp_servers.yaml");
List<McpServerSpec> specs = new McpServerConfigLoader().loadAll(cwd, userMcpFile);

// 2. 启动；任一失败仅 WARN，丢弃该 server
Duration totalTimeout = Duration.ofMillis(specs.isEmpty() ? 0 : 5000);
Map<String, McpClient> clients = new McpClientBootstrap().start(specs, totalTimeout);

// 3. 把 MCP tool 包成 Tool 接口
List<Tool> builtins = List.of(
    new ReadFileTool(), new WriteFileTool(), new EditFileTool(),
    new ExecTool(),    new GlobTool(),     new GrepTool());
List<Tool> mcpTools = clients.values().stream()
    .flatMap(c -> c.cachedTools().stream().map(t -> McpToolAdapter.of(c, t)))
    .toList();
List<Tool> allTools = Stream.concat(builtins.stream(), mcpTools.stream()).toList();
ToolRegistry registry = new ToolRegistry(allTools);  // 构造期 name dup 校验仍触发

// 4. JVM 退出关所有 stdio 进程 / http 资源
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    for (var c : clients.values()) c.close();
}, "mcp-shutdown"));
```

LlmProvider / PermissionEngine / ToolExecutor / AgentLoop 不改。

## 6. 测试

### 6.1 单元测试矩阵

| 测试类 | 必须覆盖 |
|---|---|
| `McpServerConfigLoaderTest` | (a) 单层加载；(b) 三层 deep-merge；(c) 字段缺失 / 类型错 → `ConfigException`；(d) `${VAR}` 展开、env 缺失抛错 |
| `JsonRpcTest` | (a) 单请求单响应；(b) 100 个并发 send 都按 id 配对回 future；(c) 超时触发，pending map 不留尸；(d) server error frame → future 异常；(e) orphan response 静默丢弃；(f) notification 无 id 静默丢弃 |
| `StdioTransportTest` | (a) 启动 echo/cat-like fixture，写读往返；(b) 进程 crash → `failAllPending` 完整；(c) `close()` 杀进程 |
| `StreamableHttpTransportTest` | (a) `sun.net.httpserver.HttpServer` bind 0.0.0.0:0 喂 SSE 多帧拼一帧；(b) `application/json` 单帧；(c) HTTP 401/500 → IOException；(d) `Mcp-Session-Id` 透传 |
| `McpClientTest` | (a) protocolVersion 不在白名单 → `McpProtocolException`；(b) capabilities 缺 `tools` → 抛；(c) `tools/call` 远端 error code 化异常；(d) `tools/call` 拿回 `content[]` → text-only 提取 |
| `McpClientBootstrapTest` | (a) 3 server 并发启动，全部在预算内 → 全活；(b) 1 server sleep 永远不响应 → 降级，其他 OK；(c) 全失败 → 返回空 map，`App.main` 不退出（仅 WARN） |
| `McpToolAdapterTest` | (a) `name()` 返回 `mcp__server__tool`；(b) `description()` 含 `[mcp:server] ` 前缀；(c) `execute` 透传 JsonNode，返回 ok/error；(d) 非 text content 块走占位 |
| `McpToolRegistryCollisionTest` | 改 `new ToolRegistry(...)`，喂内置 + 故意撞名 MCP → 拿到带同类提示的异常 |

`@TempDir` + `Files.writeString` 是配置文件测试的标准写法（与 `ConfigLoaderTest` 同模板）。

### 6.2 手工 smoke（不写 IT 集成测试）

stdio：

```bash
npm install -g @modelcontextprotocol/server-filesystem
# 写入 ~/.maplecode/mcp_servers.yaml：
cat > ~/.maplecode/mcp_servers.yaml <<EOF
servers:
  fs:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "$HOME/Downloads"]
EOF
mvn package
java -jar target/maple-code-java-0.1.0.jar
# REPL 里：list files matching *.txt in Downloads
# 模型调用 mcp__fs__list_directory → 应该返回 Downloads 下的目录
```

http：起一个 echo server（如 smithers 之类），写到 `~/.maplecode/mcp_servers.yaml`：

```yaml
servers:
  echo:
    type: http
    url: http://localhost:8080/mcp
    headers:
      X-Auth: "Bearer test"
```

跑同样 jar，REPL 里调模型 `mcp__echo__*` 验证。

预期：

- 模型在工具列表里看到 `mcp__fs__list_directory` 等
- 调用返回非 `ToolResult.error`
- Ctrl+C 退出 stdio 进程被 destroy

### 6.3 退出码

- 配置错误 → 78（沿用 sysexits.h `EX_CONFIG`）
- 单 server 启动 / 握手失败 → 仅 stderr WARN，App 不退出
- 模型调用 MCP tool 时连接掉 / 超时 / server error → `ToolResult.error`，AgentLoop 继续

## 7. 接受标准

实现完成必须同时满足：

1. `mvn test` 全绿，新加测试 ≥ 30 个（含核心 8 个测试类矩阵）
2. `mvn package` 生成可执行的 shaded jar
3. 至少 2 种 server 类型（stdio + http）的手工 smoke 跑通
4. `ToolRegistry` 现有 4 个 method 行为不变（`all`、`get`、`isReadOnly`、`readOnly`）；`isReadOnly` 在默认构造里仍只读这三个内置名
5. MCP 工具调用的错误信息统一前缀 `mcp[<server>]` 或 `mcp[<server>:<tool>]`
6. 启动期 YAML 校验抛 `ConfigException`，仍走退出码 78
7. 单 server 启动 / 握手失败不影响其他 server 与 REPL 启动
8. `maplecode.yaml.example` 加 `mcp_servers` 块；`maplecode.yaml.example` 不变除了加那一节
