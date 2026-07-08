# MapleCode AGENTS.md 多层加载器设计（v7.1）

> 日期：2026-07-08
> 阶段：v7.1（Project Instructions · AGENTS.md 多层加载）
> 取代：无（首版）
> 前序：v1 流式 REPL · v2 工具系统 · v3 Agent Loop · v4 权限系统 · v5 系统提示词 · v5 MCP 客户端 · v6 上下文管理
> 后续：v7.2 会话存档与恢复 · v7.3 自动长期记忆

## 1. 目标与非目标

### 1.1 目标

让 MapleCode 在启动时按优先级自动加载最多 3 个手写 Markdown 文件，拼成一段项目指令注入到 system prompt 末尾（`ENVIRONMENT` 之前），让模型从「每次失忆」变成「一启动就懂这个项目」。

具体行为：

1. **3 层加载**：项目根 `AGENTS.md` → `<cwd>/.maplecode/AGENTS.md` → `~/.maplecode/AGENTS.md`，优先级高 → 低。
2. **`{{include:path}}` 引用**：支持在其他 Markdown 中拼入子文件；递归深度限 3 层、visited 集合防环路、禁止 `..` 跳出 baseDir。
3. **拼接 + 截断**：3 层用 `\n\n---\n\n` 拼接，空层过滤；总字节上限 64KB，超限 stderr WARN 并截断。
4. **PromptSection 接入**：作为 `AgentsMdSection` 实现 `PromptSection` 接口，插入 `DefaultSections.standard()` 7 固定 section 之后、`ENVIRONMENT` 之前。
5. **容错优先**：AGENTS.md 是补充信息，**任何加载错误都不阻塞 REPL 启动**——所有失败 stderr WARN，缺哪层静默跳哪层。

### 1.2 非目标（这一版显式不做）

- 不做 `/reload` 命令；AGENTS.md 启动期一次性加载，`/clear` 不重读
- 不做 AGENTS.md 自动生成（v7.3 自动记忆才是源头）；v7.1 仅消费手写文件
- 不做 `CLAUDE.md` / `README.md` / `AGENT.md` 等多文件名 fallback；固定 `AGENTS.md`
- 不做 token 估算式精确截断（v6 `TokenEstimator` 不用）；用字节上限近似
- 不做基于文件 mtime 的"是否需要重读"判断；启动期读一次后永不变
- 不做"哪个项目根"的递归向上查找（v1 风格的 `find-up`）；只读 `cwd` 直属项目根
- 不动 v6 压缩、v5 cache、v4 权限、MCP 客户端
- 不在 `~/.maplecode/memory/` 下做任何事（v7.3 范围）
- 不实现多 agent / 多会话共享 AGENTS.md 解析结果

## 2. 架构

### 2.1 装配图

```
App.main
  ├─ DynamicContext.capture(cwd)                       // v5 已有
  ├─ AgentsMdLoader.load(cwd, userHome) → String      // v7.1 新增
  │     ├─ LayerReader.read(path) → Layer             // 单层读取
  │     ├─ IncludeResolver.resolve(content, baseDir)  // {{include:...}} 展开
  │     └─ Concatenator.join(layers) → String         // --- 拼接
  ├─ List<PromptSection> sections = DefaultSections.standard(
  │       env, tools, planMode, customInstruction, agentsMd)  // v5 standard 扩 5 参
  │     └─ new AgentsMdSection(agentsMd) 插在 ENVIRONMENT 之前
  └─ PromptAssembler.assemble(sections, ctx) → List<SystemBlock>  // v5 已有
```

### 2.2 抽象层级

| 组件 | 职责 | 不做什么 |
|---|---|---|
| `Layer` (record) | 一层 AGENTS.md 的不可变快照：`record(Path absolutePath, String content, boolean exists)`；构造期 `exists=false` 表示"未读到内容" | 不持有 IO 状态 |
| `LayerReader` | 读单个文件：`Layer read(Layer empty)`（输入是 `exists=false` 的占位 Layer，输出是填充后的 Layer）；不存在返 `empty(path)`；IOException / 超大 → WARN + `empty()` | 不递归、不解析 include |
| `IncludeResolver` | 在 content 里找 `{{include:path}}` 占位、递归展开；做路径校验 + 深度限 + 环检 | 不读其他文件之外的资源 |
| `IncludeLimits` (record) | 不可变阈值：`int maxDepth, int maxFileSize, int maxTotalBytes` | 不持有状态 |
| `AgentsMdLoader` | 唯一公开入口：3 层 LayerReader + IncludeResolver + Concatenator 编排 | 不持 cwd、userHome 等可变状态（入参传入） |
| `Concatenator` (静态方法) | `String join(List<String> layers)`：空层过滤、`\n\n---\n\n` 拼接、超 64KB 截断 | 不读文件、不解析 |
| `AgentsMdSection` | v5 `PromptSection` 实现：构造期接 content，`render` 直接返 content，`cacheable=true` | 不重新解析 content |
| `AgentsMdException` | 包内异常，loader 在"无法降级"时抛 | — |

### 2.3 package 布局

```
com.maplecode.agents
├── Layer.java                       (record)
├── LayerReader.java                 (单文件读取 + 容错)
├── IncludeLimits.java               (record + defaults())
├── IncludeResolver.java             (递归展开 + 校验)
├── Concatenator.java                (静态方法 join + 截断)
├── AgentsMdLoader.java              (公开入口)
├── AgentsMdSection.java             (实现 PromptSection)
└── AgentsMdException.java           (运行时异常)
```

`AgentsMdSection` 放 `agents` 包下以内聚所有 AGENTS.md 相关代码。与 v5 的 11 个静态 section（嵌套在 `DefaultSections.java`）性质不同，`AgentsMdSection` 是动态构造（每 session 一实例），放 agents 包更符合职责划分。

### 2.4 加载顺序与优先级

```
优先级从高到低：
  1. <cwd>/AGENTS.md                          // 项目根（最高）
  2. <cwd>/.maplecode/AGENTS.md               // 项目 .maplecode/ 目录
  3. <userHome>/.maplecode/AGENTS.md          // 用户全局（最低）
```

3 层全部参与拼接（高优先级在前）。任一层缺失、读失败、include 失败都**静默跳过该层**，不影响其他层。

## 3. 数据流

### 3.1 `AgentsMdLoader.load(cwd, userHome)` 主流程

```java
public String load(Path cwd, Path userHome) {
    // 三层占位 Layer（exists=false, content=""）
    List<Layer> placeholders = List.of(
        new Layer(cwd.resolve("AGENTS.md"), "", false),                            // 1
        new Layer(cwd.resolve(".maplecode/AGENTS.md"), "", false),                 // 2
        new Layer(userHome.resolve(".maplecode/AGENTS.md"), "", false)            // 3
    );
    // 读取：不存在 → empty(); IO 错 → empty() + WARN
    List<Layer> populated = placeholders.stream()
        .map(LayerReader::read)
        .toList();
    // 解析 include：跳过空层，对存在层递归展开
    List<String> expanded = populated.stream()
        .filter(Layer::exists)
        .map(layer -> IncludeResolver.resolve(
            layer.content(),
            layer.absolutePath().getParent(),   // baseDir
            new HashSet<>(),                    // visited（每次 load 新建）
            0,                                 // depth
            IncludeLimits.defaults()))
        .toList();
    // 拼接：过滤空串 + --- 拼接 + 截断
    return Concatenator.join(expanded);
}
```

### 3.2 `LayerReader.read(Layer)` 行为

```
1. if !Files.exists(path) → 返 Layer.empty(path)
2. if !Files.isRegularFile(path) → WARN + 返 Layer.empty(path)
3. long size = Files.size(path)
4. if size > IncludeLimits.maxFileSize (1MB) → WARN + 返 Layer.empty(path)
5. try: String content = Files.readString(path, UTF_8)
6. catch IOException e → WARN + 返 Layer.empty(path)
7. 返 new Layer(path, content, true)
```

**stderr WARN 格式**：`[agents-md] <absolutePath>: <reason>`，reason 例子：
- `not found`（不写 WARN，静默——多数项目没 AGENTS.md）
- `not a regular file`（如指向目录）
- `file too large: 1234567 bytes (max 1048576)`
- `read failed: <exception class>: <message>`

**注意**：第 1 步（不存在）**不**写 WARN。其他失败都写。

### 3.3 `IncludeResolver.resolve(content, baseDir, visited, depth, limits)` 递归流程

```java
private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}]+)\\}\\}");

public static String resolve(String content, Path baseDir, Set<Path> visited,
                             int depth, IncludeLimits limits) {
    if (depth >= limits.maxDepth()) return content;  // 深度超限，整段不展开
    Matcher m = INCLUDE.matcher(content);
    StringBuilder out = new StringBuilder();
    int lastEnd = 0;
    while (m.find()) {
        out.append(content, lastEnd, m.start());
        String includePath = m.group(1).trim();
        Path target = baseDir.resolve(includePath).normalize();
        String lineInfo = lineInfoOf(content, m.start());  // "line N"

        if (target.startsWith("..") || !target.startsWith(baseDir)) {
            // 路径跳出 baseDir
            System.err.println("[agents-md] {{include: " + includePath + "}} at " +
                baseDir + ":" + lineInfo + ": path escapes base directory");
            out.append(m.group(0));   // 占位保留
        } else if (visited.contains(target)) {
            // 环路
            System.err.println("[agents-md] {{include: " + includePath + "}} at " +
                baseDir + ":" + lineInfo + ": cycle detected (already visited " + target + ")");
            out.append(m.group(0));
        } else if (!Files.exists(target) || !Files.isRegularFile(target)) {
            // 文件不存在
            System.err.println("[agents-md] {{include: " + includePath + "}} at " +
                baseDir + ":" + lineInfo + ": file not found");
            out.append(m.group(0));
        } else {
            // 读 + 递归
            try {
                String subContent = Files.readString(target, UTF_8);
                Set<Path> nextVisited = new HashSet<>(visited);
                nextVisited.add(target);
                String expanded = resolve(subContent, target.getParent(),
                    nextVisited, depth + 1, limits);
                out.append(expanded);
            } catch (IOException e) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at " +
                    baseDir + ":" + lineInfo + ": read failed: " + e.getMessage());
                out.append(m.group(0));
            }
        }
        lastEnd = m.end();
    }
    out.append(content, lastEnd, content.length());
    return out.toString();
}
```

**关键点**：
- 正则 `\{\{include:([^}]+)\}\}` 排除 `}`，不贪婪
- `target.startsWith(baseDir)` 检查防 `..` 跳出（用 `Path.normalize()` 先解 `..`）
- visited 是绝对路径集合，跨递归层级累积
- 占位保留策略：失败时**保留原文 `{{include:path}}`**，让模型看见

### 3.4 `Concatenator.join(List<String>)` 拼接

```java
public static String join(List<String> layers) {
    List<String> nonEmpty = layers.stream()
        .filter(s -> s != null && !s.isBlank())
        .toList();
    String joined = String.join("\n\n---\n\n", nonEmpty);
    byte[] bytes = joined.getBytes(UTF_8);
    if (bytes.length > IncludeLimits.defaults().maxTotalBytes()) {
        System.err.println("[agents-md] total size " + bytes.length +
            " bytes exceeds max 65536; truncating");
        // 简单按字节截断到 maxTotalBytes，再追加尾标
        String truncated = new String(bytes, 0,
            IncludeLimits.defaults().maxTotalBytes(), UTF_8);
        return truncated + "\n\n[truncated: AGENTS.md total > 64KB]";
    }
    return joined;
}
```

**为什么按字节不按 char**：`Files.readString` 不带 BOM 是 UTF-8 多字节字符；按 char 截断可能在多字节字符中间切断，UTF-8 decode 会抛 `MalformedInputException`。按字节截到 `maxTotalBytes` 是个安全下界（实际字符数 ≤ 字节数），不会出现无效序列。

### 3.5 拼接示例

```
<cwd>/AGENTS.md 内容:
---
# Project Rules
- Use Java 21.
{{include: docs/style.md}}
---

<cwd>/.maplecode/AGENTS.md 不存在

<userHome>/.maplecode/AGENTS.md 内容:
---
# User Preferences
- 中文短句优先。
---

拼接后:
---
# Project Rules
- Use Java 21.
- Prefer records over classes.
- Avoid static singletons.

---

# User Preferences
- 中文短句优先。
---
```

如果 `<cwd>/docs/style.md>` 内容是:
```
- Prefer records over classes.
- Avoid static singletons.
```

## 4. 配置

v7.1 **无新 YAML 字段**。AGENTS.md 路径是固定的：
- `<cwd>/AGENTS.md`
- `<cwd>/.maplecode/AGENTS.md`
- `<userHome>/.maplecode/AGENTS.md`

阈值（`IncludeLimits`）是**硬编码常量**：
```java
public static IncludeLimits defaults() {
    return new IncludeLimits(3, 1_048_576, 65_536);
}
```

不做配置项的理由：v7.1 是"消费手写文件"的简单机制，引入配置反而增加复杂度。`maplecode.yaml` 暂不动；v7.2 / v7.3 引入配置时再统一加。

## 5. App.main / DefaultSections / AgentConfig 接入

### 5.1 `App.main` 改动

在 `DynamicContext.capture(cwd)` 之后、`DefaultSections.standard(...)` 之前加：

```java
// v7.1 新增
String agentsMd = AgentsMdLoader.load(
    Paths.get(System.getProperty("user.dir")),
    Paths.get(System.getProperty("user.home"))
);

// v5 已有，签名扩为 5 参
List<PromptSection> sections = DefaultSections.standard(
    env, tools, PlanMode.NORMAL, yamlPrompt, agentsMd);
```

### 5.2 `DefaultSections.standard()` 签名扩展

```java
public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                           PlanMode planMode,
                                           String customInstruction,
                                           String agentsMd) {
    List<PromptSection> list = new ArrayList<>(List.of(
        IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
        TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT,
        new AgentsMdSection(agentsMd),    // v7.1 新增
        ENVIRONMENT));
    if (customInstruction != null && !customInstruction.isBlank()) {
        list.add(new CustomInstructionSection(customInstruction));
    }
    list.add(new ActivatedSkillsSection());
    list.add(new LongTermMemorySection());
    return list;
}
```

**Breaking change**：v5 的 4 参构造器 `standard(env, tools, planMode, customInstruction)` **被移除**。所有调用点（仓内只有 `App.main`）改为 5 参。`DefaultSectionsTest` 全部更新。

### 5.3 `AgentsMdSection` 实现

```java
public final class AgentsMdSection implements PromptSection {
    private final String content;

    public AgentsMdSection(String content) {
        this.content = content == null ? "" : content;
    }

    @Override public String kind() { return "agents_md"; }
    @Override public String render(SectionContext ctx) { return content; }
    @Override public boolean cacheable() { return true; }
}
```

### 5.4 cache 影响

`PromptAssembler.assemble` 的现有代码：
```java
for (PromptSection s : sections) {
    if (!s.enabled(ctx)) continue;
    ...
    blocks.add(...);
    if (s.cacheable()) lastCacheableIdx = blocks.size() - 1;  // 持续更新
}
```

新顺序：`TEXT_OUTPUT` (T) → `AgentsMdSection` (T) → `ENVIRONMENT` (F) → `CustomInstruction`? → `ActivatedSkills` (T) → `LongTermMemory` (T)

`lastCacheableIdx` 跟踪到**最后一个 `cacheable()` 返回 true 的 section**——也就是 `LongTermMemorySection`（v5 占位返空但仍 cacheable）。`AgentsMdSection` 之后的 `LongTermMemorySection` 是 cacheable=true，会把 `lastCacheableIdx` 推后。

**实际行为**：cache 断点设在 `LongTermMemorySection`（v5 占位返空）末尾，等同设在 `AgentsMdSection` 之后。`AgentsMdSection` 与前面 7 段一起被缓存，**改 AGENTS.md 会让 cache 失效**（前 7 段重传）；不改则完整命中。

**为什么不调整 `PromptAssembler`**：v7.1 不动 v5 设计，行为正确（cache 命中正常工作）。优化 cache 断点（双断点）放 v7.x 性能优化阶段。

**为什么不调整 `PromptAssembler`**：v7.1 不动 v5 设计，行为正确（cache 命中正常工作）。优化 cache 断点（双断点）放 v7.x 性能优化阶段。

### 5.5 mapper 行为

- `AnthropicRequestMapper`：systemBlocks list 多一段，正常输出；cacheBoundary 在 AGENTS.md 末尾生效
- `OpenAiRequestMapper`：systemBlocks 用 `\n\n` 拼成单条 system message，AGENTS.md 自然衔接
- **两 mapper 代码零改动**

### 5.6 `AgentConfig` / `ChatRequest` / `ChatSession` 行为

- `AgentConfig.systemBlocks` 类型不变（`List<SystemBlock>`）
- `ChatRequest` 构造点不变
- `ChatSession` 不感知 AGENTS.md（启动期已并入 systemBlocks）
- **`/clear` / `/exit` 不重读 AGENTS.md**——`AgentsMdSection` 持有 `App.main` 启动期算好的 content 引用，跨整 session 生命周期

## 6. 测试

### 6.1 单元测试矩阵

| 测试类 | 必覆盖 |
|---|---|
| `LayerTest` | (a) 构造时 `exists=false` 不报错；(b) record 字段访问正常 |
| `LayerReaderTest` | (a) 不存在 → `Layer.empty`；(b) 是目录 → WARN + empty；(c) 文件 > 1MB → WARN + empty；(d) IOException（mock Files）→ WARN + empty；(e) 正常读取 → exists=true |
| `IncludeLimitsTest` | (a) `defaults()` 返 `(3, 1MB, 64KB)`；(b) record 字段访问 |
| `IncludeResolverTest` | (a) 简单 include → 展开；(b) 二层嵌套 → 递归展开；(c) 三层嵌套 → 展开；(d) 深度 4 拒绝 + 占位保留；(e) include 路径 `../foo` 跳出 → 拒绝 + 占位保留；(f) include 绝对路径 `/etc/passwd` → 拒绝（target 不在 baseDir 下）；(g) include 同一文件两次 → 第二次 visited 拒绝；(h) include 目标不存在 → 拒绝 + 占位保留；(i) include 目标是目录 → 拒绝；(j) include read IOException → 拒绝 + 占位保留 |
| `ConcatenatorTest` | (a) 单层 → 不加分隔符；(b) 多层 → `---` 拼接；(c) 空层过滤（list 含 null / blank）；(d) 拼接后 > 64KB → 截断 + 尾标；(e) 拼接后 = 64KB → 不截断 |
| `AgentsMdLoaderTest` | (a) 3 层全存在 → 优先级正确拼接；(b) 项目根存在 + .maplecode 缺失 + 用户存在 → 2 层；(c) 全缺失 → 空串；(d) 某层 IOException → WARN + 跳过该层；(e) 拼接超 64KB → 截断 + WARN；(f) 3 层 include 都生效（end-to-end 集成）；(g) 输出不含 `{{include:...}}` 占位（除非校验失败） |
| `AgentsMdSectionTest` | (a) `render(任何 ctx)` 返 content；(b) null content → render 返 ""；(c) `kind()` 返 "agents_md"；(d) `cacheable()` 返 true |
| `DefaultSectionsAgentsTest` | (a) 5 参 `standard(..., agentsMd)` 列表含 `AgentsMdSection`；(b) `AgentsMdSection` 位置在 ENVIRONMENT 之前；(c) 空 agentsMd 不影响其他 section；(d) 旧 4 参构造器**编译失败**（已移除） |
| `PromptAssemblerAgentsTest` | (a) 含 `AgentsMdSection` 时 `lastCacheableIdx` 指向 `AgentsMdSection`；(b) `cacheBoundary=true` 标在 AGENTS.md 末尾；(c) ENVIRONMENT (cacheable=false) 之后 lastCacheableIdx 不更新 |

### 6.2 手工 smoke（仓内无 IT）

| 场景 | 期望 |
|---|---|
| 1. 启动 + 项目根有 `AGENTS.md` | REPL 启动如常，模型能引用其内容（问"项目用什么 Java"） |
| 2. 启动 + 3 层全有 | 拼接顺序对，模型看到全部 |
| 3. 启动 + 项目根有 + .maplecode 缺 + 用户有 | 2 层拼接，stderr 干净 |
| 4. 启动 + 全无 | 空，stderr 干净 |
| 5. include 正常 | 嵌套内容展开 |
| 6. include 目标不存在 | stderr `[agents-md] {{include: ...}} at ...: line N: file not found`；模型看到 `{{include: ...}}` 文本 |
| 7. include 环路 | stderr `[agents-md] ...: cycle detected (already visited ...)` |
| 8. include 路径跳出 | stderr `[agents-md] ...: path escapes base directory` |
| 9. AGENTS.md 拼接 > 64KB | stderr `[agents-md] total size ... bytes exceeds max 65536; truncating` |
| 10. cache 检查 | 启动首轮 `cache_creation>0`；改 AGENTS.md 重启后 `cache_creation>0`；不改重启 `cache_read>0` |

### 6.3 兼容性

- `ChatRequest` / `ChatSession` / `Tool` / `ContentBlock` / `StreamChunk` 签名零变
- `AnthropicRequestMapper` / `OpenAiRequestParser` 零变
- `AgentConfig` / `ResponseCollector` / `AgentLoop` 零变
- **唯一 breaking**：`DefaultSections.standard` 从 4 参 → 5 参。仓内调用点 1 处（`App.main`）+ 测试 N 处全改
- `mvn test` 全绿，新加测试 ≥ 25 个（覆盖上述 9 个测试类矩阵）

## 7. 接受标准

实现完成必须同时满足：

1. `mvn test` 全绿，新加测试 ≥ 25 个
2. `mvn package` 产出可执行 shaded jar，启动无新警告
3. 手工 smoke 10 步全跑通
4. `DefaultSections.standard` 5 参签名落地；`App.main` 调 `AgentsMdLoader.load(cwd, userHome)` 后传入
5. `AgentsMdSection` 位置正确（`TEXT_OUTPUT` 之后、`ENVIRONMENT` 之前）
6. cacheBoundary 落在 AGENTS.md 末尾
7. 任何 AGENTS.md 加载错误（不存在 / 权限 / 超大 / include 失败）**不**阻塞 REPL 启动
8. 所有 `agents` 包内日志走 stderr，前缀 `[agents-md]`；**绝不写 stdout**
9. v6 压缩、v5 prompt、MCP 客户端、v4 权限、v3 Agent Loop 既有测试 0 回归
10. include 路径校验严格：`..` 跳出、绝对路径、环路、超深 都正确拒绝 + 占位保留 + WARN

## 8. 文件清单

### 8.1 新增

```
src/main/java/com/maplecode/agents/
├── Layer.java                       (record)
├── LayerReader.java                 (单文件读取 + 容错)
├── IncludeLimits.java               (record + defaults())
├── IncludeResolver.java             (递归展开 + 校验)
├── Concatenator.java                (静态方法 join + 截断)
├── AgentsMdLoader.java              (公开入口)
├── AgentsMdSection.java             (实现 PromptSection)
└── AgentsMdException.java           (运行时异常)

src/test/java/com/maplecode/agents/
├── LayerTest.java
├── LayerReaderTest.java
├── IncludeLimitsTest.java
├── IncludeResolverTest.java
├── ConcatenatorTest.java
├── AgentsMdLoaderTest.java
└── AgentsMdSectionTest.java

src/test/java/com/maplecode/prompt/
├── DefaultSectionsAgentsTest.java
└── PromptAssemblerAgentsTest.java
```

### 8.2 修改

```
src/main/java/com/maplecode/
├── prompt/DefaultSections.java      ← standard() 4 参 → 5 参（加 agentsMd）
└── App.java                          ← 启动期 AgentsMdLoader.load

src/test/java/com/maplecode/prompt/
└── DefaultSectionsTest.java         ← 4 参 → 5 参更新所有调用点
```

## 9. 设计决策记录

1. **3 层固定、不支持自定义路径** —— 路径是社区惯例（Claude Code / Cursor / Aider 都用 `AGENTS.md`），引入配置反而分裂
2. **`{{include:path}}` 占位 + 失败保留原文** —— 模型能看见未解析指令，不脑补"该路径存在"
3. **`..` 跳出 baseDir 用 `target.startsWith(baseDir)` 校验** —— `Path.normalize()` 后再做 startsWith，能挡住 `../` 和绝对路径
4. **visited 是 `Set<Path>` 绝对路径** —— 跨递归累积；同一会话内同文件不会重复 include
5. **visited 不在 session 间共享** —— 启动期 `AgentsMdLoader.load` 一次性，`new HashSet<>()` 每次新
6. **截断按字节不按 char** —— 避免 UTF-8 多字节字符中间切断抛 `MalformedInputException`
7. **AGENTS.md 加载失败永不影响启动** —— 退一档不致命；"用户没写 AGENTS.md" 是常见场景
8. **AGENTS.md 放 `ENVIRONMENT` 之前** —— 项目指令是"项目背景"的延伸；时间/cwd 是运行时信息放最后
9. **AGENTS.md 是 cacheable** —— 启动后不变，可以稳定缓存
10. **不动 v5 `PromptAssembler` 的 cache 策略** —— v7.1 不动 v5 既有逻辑；性能优化放后续
11. **不做 `/reload` 命令** —— YAGNI；改 AGENTS.md 重启进程
12. **`DefaultSections.standard` 4 参 → 5 参 breaking** —— 仓内只有 1 个生产调用点 + 测试，可控
13. **`AgentsMdException` 整期可能不抛** —— 所有路径都已降级；保留为未来 edge case
14. **WARN 输出带行号 `at <file>:line N`** —— 用 `lineInfoOf(content, offset)` 数 `\n` 算行号；模型 / 用户能定位

## 10. 后续阶段预告（不在 v7.1）

- **v7.2 会话存档与恢复**：JSONL 追加写到 `~/.maplecode/sessions/<id>.jsonl`；`/resume [id]` 加载、`/new` 新会话；坏行跳过、orphan tool_use 截断、token 超限先压一次、30 天过期清理
- **v7.3 自动长期记忆**：4 类（user/feedback/project/reference）× 2 scope（用户级/项目级）；每轮 Agent Loop 自然停下后异步调 LLM；输出 `{"ops": [...]}` JSON 清单；MEMORY.md 索引 ≤ 200 行 / 25KB
