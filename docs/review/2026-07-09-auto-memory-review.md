# MapleCode 自动记忆子系统代码审查报告

**审查日期**: 2026-07-09
**审查范围**: `compact/`、`memory/`、`session/archive/`、`prompt/MemorySection` 及相关集成层（`App`、`AgentLoop`、`ReplLoop`、`ChatSession`）
**审查文件数**: 31 个主源文件

---

## 一、子系统概览

自动记忆由三个独立但协作的子系统构成：

| 子系统 | 包 | 职责 | 熔断器 |
|--------|----|----|--------|
| 会话内压缩 | `compact/` (12 类) | 双层压缩：Offloader 落盘大块工具结果 → ConversationSummarizer 调 LLM 生成 5 章节摘要 | `FailureCounter` (阈值 3) |
| 跨会话长期记忆 | `memory/` (11 类) | 每轮对话后异步调 LLM 提取记忆操作，持久化为 Markdown，下次启动注入 system prompt | `MemoryFailureCounter` (阈值 3) |
| 会话持久化 | `session/archive/` (5 类) | JSONL 序列化完整对话，`/resume` 恢复，30 天过期清理 | 无 |

---

## 二、问题清单

### 严重程度说明
- **B (Bug)**: 正确性缺陷，会导致功能异常或崩溃
- **M (Medium)**: 健壮性/设计缺陷，在边缘场景或长期运行中暴露问题
- **S (Small)**: 风格/一致性问题，不影响功能

---

### B1. `MemoryStore.toSlug()` 字符串越界异常

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 284-290 行

**代码**:
```java
private static String toSlug(String name) {
    return name.toLowerCase()
               .replaceAll("[^a-z0-9\\-]", "-")
               .replaceAll("-{2,}", "-")
               .replaceAll("^-|-$", "")
               .substring(0, Math.min(20, name.length()));  // BUG
}
```

**问题**: `substring` 的结束索引使用了 `name.length()`（原始 name 长度），而非 slug 化后字符串的长度。当 slug 化使字符串变短（合并连续分隔符、去除首尾分隔符），且 `name.length() > slug.length()` 时，`substring` 越界抛出 `StringIndexOutOfBoundsException`。

**触发条件极易满足**: 任何 name 包含空格或连续非字母字符。例如 `name = "a  b"`（两个空格）：
- replaceAll 后 → `"a--b"` → `"a-b"`（3 字符）
- `substring(0, Math.min(20, 4))` = `substring(0, 4)` → **越界**（长度仅 3）

若 name 全为非 ASCII 字符（如中文），slug 化后为空字符串，同样越界。

**影响**: 该条记忆创建失败（被 `doExtract` 内层 catch 捕获后跳过该 op），记忆静默丢失。不会导致整批失败或触发熔断。

**修复建议**:
```java
private static String toSlug(String name) {
    String slug = name.toLowerCase()
               .replaceAll("[^a-z0-9\\-]", "-")
               .replaceAll("-{2,}", "-")
               .replaceAll("^-|-$", "");
    return slug.substring(0, Math.min(20, slug.length()));
}
```

---

### B2. `TokenEstimator` token 重复计算（估算值偏高约 2 倍）

**文件**: `src/main/java/com/maplecode/compact/TokenEstimator.java` 第 16-30 行

**代码**:
```java
public int estimate(List<ChatMessage> messages, TokenUsage anchor) {
    int anchorTokens = 0;
    if (anchor != null) {
        anchorTokens = anchor.inputTokens()
            + anchor.cacheCreationTokens()
            + anchor.cacheReadTokens();
    }
    long chars = 0;
    for (var msg : messages) {
        for (var block : msg.blocks()) {
            chars += blockChars(block);
        }
    }
    return anchorTokens + (int) (chars / 4);  // 两者相加
}
```

**问题**: `anchorTokens` 是上次请求的**总输入 token**（已包含 system prompt + 全部历史消息），而 `chars / 4` 又把当前**所有消息**的字符重新估算了一遍。两者相加导致历史消息被计算两次，估算值约为实际值的 2 倍。

注意：`inputTokens + cacheCreationTokens + cacheReadTokens` 在 Anthropic 语义下确实是总输入 token（三者不重叠），这一步本身正确。问题出在与 `chars/4` 叠加。

**影响**: 自动压缩在上下文窗口实际使用约 50% 时就触发（默认 window=200000, autoMargin=13000，阈值 187000；但因高估 2 倍，实际 ~93500 token 就触发压缩）。导致过早压缩，浪费 LLM 调用成本，且不必要地丢失上下文精度。

**修复建议**: 二选一——
- 方案 A（推荐）: 只用字符估算，不叠加 anchor：`return (int)(chars / 4) + (anchor != null ? anchor.outputTokens() / 4 : 0);`（仅补上上次输出对本次的增量）
- 方案 B: 只用 anchor 增量：`anchorTokens + 新增消息估算`，需要区分新增消息。

---

### B3. `MemoryManager` 同步/异步并发竞态

**文件**: `src/main/java/com/maplecode/memory/MemoryManager.java` 第 38-52 行

**代码**:
```java
public void extractAsync(List<ChatMessage> recentMessages) {
    if (!config.enabled()) return;
    if (!counter.isOpen()) return;
    executor.submit(() -> doExtract(recentMessages));  // 后台线程
}

public void extractSync(List<ChatMessage> recentMessages) {
    if (!config.enabled()) return;
    if (!counter.isOpen()) { ... }
    doExtract(recentMessages);  // 调用线程（REPL 主线程），不经过 executor
}
```

**问题**: `extractAsync` 提交到单线程 executor（`memory-extractor` 线程），而 `extractSync` 在调用线程（REPL 主线程）直接执行 `doExtract`。两者不互斥——如果用户执行 `/memory extract`（同步）时上一轮的异步提取仍在进行，两个线程会并发调用 `store.executeOp()` → `rebuildIndex()`，导致文件覆盖和索引损坏。`MemoryStore` 的所有方法均无同步保护。

**影响**: 并发执行时记忆文件或 `MEMORY.md` 索引可能损坏（部分写入被覆盖、索引与文件不一致）。

**修复建议**: 让 `extractSync` 也走 executor 队列（保证串行），或对 `doExtract` 加锁：
```java
public void extractSync(List<ChatMessage> recentMessages) {
    // ...
    executor.submit(() -> doExtract(recentMessages)).get(); // 提交并等待
}
```
或在 `doExtract` 上加 `synchronized`。

---

### M1. `CompactCoordinator` 用假请求获取消息列表

**文件**: `src/main/java/com/maplecode/compact/CompactCoordinator.java` 第 85 行

**代码**:
```java
List<ChatMessage> current = session.toRequest("unused", List.of(), null, List.of()).messages();
```

**问题**: 为获取 session 的消息列表而构造一个"假" `ChatRequest`——传 `"unused"` 作为 model、空 system blocks、null thinking、空 tools。这是 leaky abstraction hack。`ChatSession` 内部有 `messages` 字段但缺少直接的 `messages()` getter。

**影响**: 代码可维护性差。若 `ChatRequest` 未来增加参数校验（如 model 非空检查），此处会崩溃。

**修复建议**: 在 `ChatSession` 增加公开方法：
```java
public List<ChatMessage> messages() {
    return Collections.unmodifiableList(messages);
}
```
然后 `CompactCoordinator` 直接调用 `session.messages()`。

---

### M2. 文件写入非原子

**文件**: `memory/MemoryStore.java`（`doCreate` 第 143 行、`doUpdate` 第 162 行、`rebuildIndex` 第 96 行）、`session/archive/SessionWriter.java`（第 21-29 行）

**问题**: 所有文件写入使用 `Files.writeString(target, ...)` 直接写目标文件，而非 write-to-temp + atomic-move。如果写入过程中进程崩溃或断电，会留下截断/损坏的文件。

**影响**: 崩溃时记忆文件或会话存档损坏，下次读取可能失败或读到半截内容。

**修复建议**: 统一使用原子写入模式：
```java
Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
Files.writeString(tmp, content, StandardCharsets.UTF_8);
Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```

---

### M3. `ConversationSummarizer` 拒绝标记误判

**文件**: `src/main/java/com/maplecode/compact/ConversationSummarizer.java` 第 80-82 行、第 147-153 行

**代码**:
```java
private static final String[] REFUSAL_MARKERS = {
    "I can't", "I cannot", "I'm unable"
};
// ...
for (String marker : REFUSAL_MARKERS) {
    if (summary.contains(marker)) {
        throw new CompactException("Summarizer refused: ...");
    }
}
```

**问题**: 用简单子串匹配检测"拒绝"。合法摘要内容若包含这些子串（如 `"The user said I can't access that API"` 或 `"I cannot guarantee the result without re-reading"`），会被误判为拒绝，抛异常触发熔断计数。

**影响**: 正常摘要被误拒，导致压缩失败 + 熔断计数累加。连续 3 次后自动压缩被熔断禁用。

**修复建议**: 改为检查摘要**开头**是否为拒绝话术（而非任意位置子串），或用更精确的模式（如 `^(I can't|I cannot|I'm unable)` 正则），或直接移除拒绝检测——5 章节校验已经足够保证输出质量。

---

### M4. `ConversationSummarizer` 两套 token 估算逻辑

**文件**: `compact/ConversationSummarizer.java` 第 194-204 行 vs `compact/TokenEstimator.java`

**问题**: `ConversationSummarizer` 内部有私有 `estimateTokens(ChatMessage)` 方法（对非 TextBlock 固定加 100 chars），与 `TokenEstimator` 的估算逻辑（ToolUseBlock 做 JSON 序列化算长度、ToolResultBlock 算 content 长度）不一致。`computeRecencySplit` 用粗略估算，`CompactCoordinator` 用另一套。

**影响**: 估算不一致导致 recency 分割点计算与压缩阈值判断使用不同口径，可能产生边界偏差。维护时容易混淆。

**修复建议**: 移除 `ConversationSummarizer.estimateTokens`，统一使用 `TokenEstimator`。给 `TokenEstimator` 增加 `estimateMessage(ChatMessage)` 便捷方法。

---

### M5. `MemoryStore.parseIndex` 依赖 em-dash 分隔符

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 212 行

**代码**:
```java
int dash = line.indexOf(" — ", closeParen);  // em-dash + 空格
```

**问题**: 索引行格式为 `- [name](path) — summary`，解析依赖特定 unicode 字符 ` — `（空格 + em-dash U+2014 + 空格）。如果用户手动编辑 `MEMORY.md` 改用普通连字符 `-` 或 `--`，summary 解析失败（静默返回空字符串）。

**影响**: 手动编辑索引后 summary 丢失，注入 system prompt 的记忆内容缺少摘要。

**修复建议**: 改用更宽松的分隔策略，如 `)` 后取剩余部分作为 summary，或用正则 `^\- \[(.+?)\]\((.+?)\)\s*[—–-]\s*(.+)$` 兼容多种 dash。

---

### M6. `AgentLoop` 首轮不检查压缩

**文件**: `src/main/java/com/maplecode/agent/AgentLoop.java` 第 99 行

**代码**:
```java
if (coord != null && iteration > 0) {
    var outcome = coord.beforeRequest(session, CompactTrigger.AUTO, coord.lastSeenUsage());
    // ...
}
```

**问题**: 第一轮（`iteration == 0`）不检查压缩。如果用户第一条消息本身就超长（如粘贴大文件、大量代码），直接发送超长请求，provider 返回错误。

**影响**: 超长首条消息导致首次请求失败。

**修复建议**: 改为 `iteration >= 0`（即每轮都检查），或至少在首轮也做 token 估算检查。`beforeRequest` 内部已有阈值判断，低 token 时返回 `Noop`，不会误触发。

---

### M7. `SessionReader` 不处理反向孤儿 tool_result

**文件**: `src/main/java/com/maplecode/session/archive/SessionReader.java` 第 72-105 行

**问题**: `truncateOrphanToolUse` 只删除没有对应 `tool_result` 的 `tool_use`（正向孤儿），不处理没有对应 `tool_use` 的 `tool_result`（反向孤儿）。如果存档文件损坏或被手动编辑，出现反向孤儿 `tool_result`，恢复后发送请求时 API 会报错（tool_result 引用了不存在的 tool_use id）。

**影响**: 损坏存档恢复后请求失败。

**修复建议**: 增加反向孤儿清理：
```java
Set<String> orphanResultIds = new HashSet<>(toolResultIds);
orphanResultIds.removeAll(toolUseIds);
// 从 USER 消息中移除指向 orphanResultIds 的 ToolResultBlock
```

---

### M8. `MemoryManager.extractAsync` 未处理 `RejectedExecutionException`

**文件**: `src/main/java/com/maplecode/memory/MemoryManager.java` 第 41 行

**代码**:
```java
public void extractAsync(List<ChatMessage> recentMessages) {
    if (!config.enabled()) return;
    if (!counter.isOpen()) return;
    executor.submit(() -> doExtract(recentMessages));  // 可能抛 RejectedExecutionException
}
```

**问题**: 程序退出时 shutdown hook 调用 `close()` → `executor.shutdownNow()`。如果此时恰好有对话完成触发 `extractAsync`，`executor.submit()` 抛出 `RejectedExecutionException`，未捕获，可能导致 shutdown 线程异常退出。

**影响**: 边缘场景下 shutdown 阶段抛未捕获异常。

**修复建议**:
```java
public void extractAsync(List<ChatMessage> recentMessages) {
    if (!config.enabled()) return;
    if (!counter.isOpen()) return;
    try {
        executor.submit(() -> doExtract(recentMessages));
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
        // executor 已关闭，忽略
    }
}
```

---

### S1. `MemoryExtractor` system prompt 中英混用

**文件**: `src/main/java/com/maplecode/memory/MemoryExtractor.java` 第 30-62 行

**问题**: 英文 system prompt 中嵌入了中文 `"当前已有记忆："`。不致命，但风格不一致，可能影响模型对 prompt 的理解一致性。

**修复建议**: 统一为英文 `"Existing memories:"` 或统一为中文。

---

### S2. `CompactCoordinator` 重复 token 估算

**文件**: `src/main/java/com/maplecode/compact/CompactCoordinator.java` 第 104 行、第 117 行

**代码**:
```java
int afterOffload = estimator.estimate(offloaded, anchor);   // 第 104 行
// ...
int summaryInputTokens = estimator.estimate(offloaded, anchor);  // 第 117 行，重复
```

**问题**: 第 117 行重新计算了 `offloaded` 的 token 估算，与第 104 行完全相同，浪费计算。且变量名 `summaryInputTokens` 语义不准确（实际是 offloaded 的估算值，不是 summarizer 的输入 token）。

**修复建议**: 复用第 104 行的 `afterOffload` 变量。

---

### S3. `MemoryStore` frontmatter 解析对内容含 `---\n` 不健壮

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 321 行

**代码**:
```java
int endIdx = text.indexOf("---\n", FM_START.length());
```

**问题**: 如果记忆 content 本身包含 `"---\n"`（如用户让 LLM 记住一段 Markdown 文档），frontmatter 结束标记会被提前匹配，导致 content 截断。

**影响**: 含 `---` 的记忆内容被截断。

**修复建议**: frontmatter 解析应只在前几行查找结束标记（frontmatter 通常很短），或用 YAML 解析器。

---

## 三、亮点

审查中也发现了一些设计良好的方面：

1. **sealed 类型层次**: `CompactResult`（6 变体）、`MemoryOp`（3 变体）使用 sealed 接口，保证 switch 穷尽性。
2. **双层熔断器**: compact 和 memory 各有独立的 `FailureCounter`，连续失败后自动降级，避免反复打失败的 LLM 调用。
3. **Offloader 的两阶段策略**: 先 offload 单条超大 tool result，再按总量聚合 offload，层次清晰。
4. **ChatSession 的 append-only 不变量**: 只在成功时追加，流异常时不修改 session，用户可安全重试。`replaceAll` 做防御性拷贝。
5. **SessionReader 的孤立 tool_use 清理**: 恢复存档时自动截断无配对 tool_result 的 tool_use，防止 API 报错（虽未处理反向孤儿，见 M7）。
6. **MemoryExtractor 的 JSON 容错解析**: 先直接解析，失败后正则贪婪匹配 + 去除 markdown 代码块，多层降级。

---

## 四、修复优先级建议

| 优先级 | 问题 | 理由 |
|--------|------|------|
| P0 | B2 (token 重复计算) | 影响每次对话，过早压缩浪费成本、损失上下文 |
| P0 | B1 (toSlug 越界) | LLM 返回含空格的 name 即触发，记忆丢失 |
| P1 | B3 (并发竞态) | `/memory extract` + 异步提取并发时损坏数据 |
| P1 | M2 (非原子写入) | 崩溃时数据损坏风险 |
| P2 | M1, M3, M4, M6, M7, M8 | 健壮性与设计改进 |
| P3 | S1, S2, S3 | 代码质量优化 |
