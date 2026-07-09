# MapleCode 自动记忆子系统代码审查报告

**审查日期**: 2026-07-09
**审查范围**: `compact/`、`memory/`、`session/archive/`、`prompt/MemorySection` 及相关集成层（`App`、`AgentLoop`、`ReplLoop`、`ChatSession`、`Anthropic/OpenAi RequestMapper`）
**审查文件数**: 33 个主源文件

> **修复验证**: 2026-07-09 同日完成修复验证。15 个问题中 **10 个已修复**（B1、B2、B3、M1、M2、M3、M4、M7、M8、S2），**5 个未修复**（B4、M5、M6、S1、S3）。`mvn test` 全部通过。下文每个问题均标注修复状态与实际修复方式。

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

### B1. `MemoryStore.toSlug()` 字符串越界异常 — ✅ 已修复

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 286-292 行

**问题**: `substring` 的结束索引使用了 `name.length()`（原始 name 长度），而非 slug 化后字符串的长度。当 slug 化使字符串变短（合并连续分隔符、去除首尾分隔符），且 `name.length() > slug.length()` 时，`substring` 越界抛出 `StringIndexOutOfBoundsException`。

**触发条件极易满足**: 任何 name 包含空格或连续非字母字符。例如 `name = "a  b"`（两个空格）：
- replaceAll 后 → `"a--b"` → `"a-b"`（3 字符）
- `substring(0, Math.min(20, 4))` = `substring(0, 4)` → **越界**（长度仅 3）

若 name 全为非 ASCII 字符（如中文），slug 化后为空字符串，同样越界。

**影响**: 该条记忆创建失败（被 `doExtract` 内层 catch 捕获后跳过该 op），记忆静默丢失。

**修复状态**: ✅ 已修复。先将 slug 化结果赋值给局部变量，再用 `slug.length()` 截取：
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

### B2. `TokenEstimator` token 重复计算（估算值偏高约 2 倍） — ✅ 已修复

**文件**: `src/main/java/com/maplecode/compact/TokenEstimator.java` 第 16-31 行

**问题**: `anchorTokens`（上次请求总输入 token，已含 system prompt + 全部历史消息）与 `chars / 4`（当前所有消息字符估算）直接相加，历史消息被计算两次，估算值约为实际值的 2 倍。

注意：`inputTokens + cacheCreationTokens + cacheReadTokens` 在 Anthropic 语义下确实是总输入 token（三者不重叠），这一步本身正确。问题出在与 `chars/4` 叠加。

**影响**: 自动压缩在上下文窗口实际使用约 50% 时就触发（默认 window=200000, autoMargin=13000，阈值 187000；但因高估 2 倍，实际 ~93500 token 就触发压缩）。导致过早压缩，浪费 LLM 调用成本，且不必要地丢失上下文精度。

**修复状态**: ✅ 已修复。从相加改为取最大值——anchor 是 API 精确值但可能是上一轮的（下界），`chars/4` 是当前全量估算，取 `Math.max` 既不低估也不高估：
```java
return Math.max(anchorTokens, (int) (chars / 4));
```
同时新增 `estimateMessage(ChatMessage)` 便捷方法（第 37-43 行），供 `ConversationSummarizer` 复用（见 M4）。

---

### B3. `MemoryManager` 同步/异步并发竞态 — ✅ 已修复

**文件**: `src/main/java/com/maplecode/memory/MemoryManager.java` 第 38-84 行

**问题**: `extractAsync` 提交到单线程 executor（`memory-extractor` 线程），而 `extractSync` 在调用线程（REPL 主线程）直接执行 `doExtract`。两者不互斥——如果用户执行 `/memory extract`（同步）时上一轮的异步提取仍在进行，两个线程会并发调用 `store.executeOp()` → `rebuildIndex()`，导致文件覆盖和索引损坏。`MemoryStore` 的所有方法均无同步保护。

**影响**: 并发执行时记忆文件或 `MEMORY.md` 索引可能损坏。

**修复状态**: ✅ 已修复。新增 `ReentrantLock storeLock`（第 26 行），在 `doExtract`、`listMemories`、`clearAll` 三个方法中均加 `storeLock.lock()` / `finally { storeLock.unlock(); }` 保护，确保同步与异步路径互斥：
```java
private final ReentrantLock storeLock = new ReentrantLock();

private void doExtract(List<ChatMessage> recentMessages) {
    storeLock.lock();
    try {
        // ... 提取逻辑 ...
    } finally {
        storeLock.unlock();
    }
}
```

---

### B4. 压缩后产生连续 USER 消息，违反角色交替约束 — ❌ 未修复

**文件**: `src/main/java/com/maplecode/compact/ConversationSummarizer.java` 第 108-116 行

**问题**: 压缩产物结构为 `[USER 摘要] + 尾部消息 + [USER 边界]`。两端的摘要和边界消息都是 USER 角色，会产生两处连续 USER：

1. **尾部末条 + 边界 USER（几乎必然）**: `AgentLoop` 在 `iteration > 0` 时调 `beforeRequest`，此时 session 最后一条消息是 `user(tool_result)`（工具执行结果回灌后、下一轮请求前）。尾部保留了这些消息，末条是 USER，紧接边界 USER → 连续 USER。
2. **摘要 USER + 尾部首条（概率较高）**: `computeRecencySplit` 按 token 预算从末尾向前截取，不保证分割点落在 ASSISTANT 消息上。若 `tailStart` 落在 USER 消息上，摘要 USER + 尾部首条 USER → 连续 USER。

**调用链验证**: 复查了 `AnthropicRequestMapper.encodeMessage()`（第 93-116 行）和 `OpenAiRequestMapper.encodeMessage()`（第 91-133 行），两个 provider 映射器均直接逐条编码消息、**无连续同角色合并逻辑**。连续 USER 会原样发送给 API。

**影响**: 取决于 API 行为——Anthropic API 要求 user/assistant 交替，连续 USER 可能被拒绝（400 错误）或被静默合并。若被拒绝，压缩后首次请求失败，触发熔断计数，3 次后自动压缩被永久禁用。即便 API 容错合并，摘要/边界文本与 tool_result 混在同一条 USER 消息中，也会影响模型对上下文的理解。

**修复状态**: ❌ 未修复。`apply()` 方法结构未变，`computeRecencySplit` 未调整分割点的角色约束。

**修复建议**: `computeRecencySplit` 应保证 `tailStart` 落在 ASSISTANT 消息上（向前调整直到角色为 ASSISTANT）；或将摘要消息角色改为 SYSTEM，或用 ASSISTANT 角色包装摘要。最简方案——调整分割点：
```java
// 确保 tailStart 落在 ASSISTANT 消息上，避免与前面的摘要 USER 连续
while (startIdx > 0 && messages.get(startIdx).role() != Role.ASSISTANT) {
    startIdx--;
    tailLen++;
}
```
同时考虑将边界消息移入 system prompt 或合并到尾部最后一条 USER 消息中，避免尾部末条 + 边界的连续。

---

### M1. `CompactCoordinator` 用假请求获取消息列表 — ✅ 已修复

**文件**: `src/main/java/com/maplecode/compact/CompactCoordinator.java` 第 85 行

**问题**: 为获取 session 的消息列表而构造一个"假" `ChatRequest`——传 `"unused"` 作为 model、空 system blocks、null thinking、空 tools。这是 leaky abstraction hack。`ChatSession` 内部有 `messages` 字段但缺少直接的 `messages()` getter。

**影响**: 代码可维护性差。若 `ChatRequest` 未来增加参数校验（如 model 非空检查），此处会崩溃。

**修复状态**: ✅ 已修复。`ChatSession` 新增 `messages()` 公开方法（第 46-48 行），`CompactCoordinator` 直接调用 `session.messages()`：
```java
// ChatSession
public List<ChatMessage> messages() {
    return Collections.unmodifiableList(messages);
}

// CompactCoordinator
List<ChatMessage> current = session.messages();
```

---

### M2. 文件写入非原子 — ✅ 已修复

**文件**: `memory/MemoryStore.java`（`doCreate`、`doUpdate`、`rebuildIndex`）、`session/archive/SessionWriter.java`

**问题**: 所有文件写入使用 `Files.writeString(target, ...)` 直接写目标文件，而非 write-to-temp + atomic-move。如果写入过程中进程崩溃或断电，会留下截断/损坏的文件。

**影响**: 崩溃时记忆文件或会话存档损坏，下次读取可能失败或读到半截内容。

**修复状态**: ✅ 已修复。新增 `com.maplecode.util.IoUtil` 工具类，实现标准 write-to-temp + atomic-move 模式：
```java
public static void atomicWrite(Path target, String content) throws IOException {
    Path dir = target.getParent();
    if (dir != null) Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, ".maplecode-", ".tmp");
    try {
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw e;
    }
}
```
`MemoryStore` 的 `doCreate`（第 145 行）、`doUpdate`（第 164 行）、`rebuildIndex`（第 98 行）和 `SessionWriter.write`（第 30 行）均改用 `IoUtil.atomicWrite()`。跨文件系统时自动降级为非原子移动。

---

### M3. `ConversationSummarizer` 拒绝标记误判 — ✅ 已修复

**文件**: `src/main/java/com/maplecode/compact/ConversationSummarizer.java` 第 146-153 行

**问题**: 用简单子串匹配（`summary.contains(marker)`）检测"拒绝"。合法摘要内容若包含这些子串（如 `"The user said I can't access that API"`），会被误判为拒绝，抛异常触发熔断计数。

**影响**: 正常摘要被误拒，导致压缩失败 + 熔断计数累加。连续 3 次后自动压缩被熔断禁用。

**修复状态**: ✅ 已修复。从 `contains`（任意位置）改为 `startsWith`（仅开头），并做大小写归一化：
```java
private void validateSummary(String summary) {
    String lower = summary.toLowerCase();
    for (String marker : REFUSAL_MARKERS) {
        if (lower.startsWith(marker.toLowerCase())) {
            throw new CompactException("Summarizer refused: output starts with '" + marker + "'");
        }
    }
    // ... 章节校验 ...
}
```

---

### M4. `ConversationSummarizer` 两套 token 估算逻辑 — ✅ 已修复

**文件**: `compact/ConversationSummarizer.java` vs `compact/TokenEstimator.java`

**问题**: `ConversationSummarizer` 内部有私有 `estimateTokens(ChatMessage)` 方法（对非 TextBlock 固定加 100 chars），与 `TokenEstimator` 的估算逻辑不一致。`computeRecencySplit` 用粗略估算，`CompactCoordinator` 用另一套。

**影响**: 估算不一致导致 recency 分割点计算与压缩阈值判断使用不同口径，可能产生边界偏差。

**修复状态**: ✅ 已修复。删除 `ConversationSummarizer` 的私有 `estimateTokens` 方法，改为持有 `TokenEstimator` 实例（第 87 行）并调用 `tokenEstimator.estimateMessage()`（第 177 行）。`TokenEstimator` 新增 `estimateMessage(ChatMessage)` 公开方法（第 37-43 行），全系统统一估算逻辑。

---

### M5. `MemoryStore.parseIndex` 依赖 em-dash 分隔符 — ❌ 未修复

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 214 行

**问题**: 索引行格式为 `- [name](path) — summary`，解析依赖特定 unicode 字符 ` — `（空格 + em-dash U+2014 + 空格）。如果用户手动编辑 `MEMORY.md` 改用普通连字符 `-` 或 `--`，summary 解析失败（静默返回空字符串）。

**影响**: 手动编辑索引后 summary 丢失，注入 system prompt 的记忆内容缺少摘要。

**修复状态**: ❌ 未修复。第 214 行仍用 `line.indexOf(" — ", closeParen)`。

**修复建议**: 改用更宽松的分隔策略，如 `)` 后取剩余部分作为 summary，或用正则兼容多种 dash。

---

### M6. `AgentLoop` 首轮不检查压缩 — ❌ 未修复

**文件**: `src/main/java/com/maplecode/agent/AgentLoop.java` 第 99 行

**问题**: 第一轮（`iteration == 0`）不检查压缩。如果用户第一条消息本身就超长（如粘贴大文件、大量代码），直接发送超长请求，provider 返回错误。

**影响**: 超长首条消息导致首次请求失败。

**修复状态**: ❌ 未修复。第 99 行仍为 `if (coord != null && iteration > 0)`。

**修复建议**: 改为 `iteration >= 0`（每轮都检查）。`beforeRequest` 内部已有阈值判断，低 token 时返回 `Noop`，不会误触发。

---

### M7. `SessionReader` 不处理反向孤儿 tool_result — ✅ 已修复

**文件**: `src/main/java/com/maplecode/session/archive/SessionReader.java` 第 72-112 行

**问题**: `truncateOrphanToolUse` 只删除没有对应 `tool_result` 的 `tool_use`（正向孤儿），不处理没有对应 `tool_use` 的 `tool_result`（反向孤儿）。存档损坏时反向孤儿 `tool_result` 会导致 API 报错。

**影响**: 损坏存档恢复后请求失败。

**修复状态**: ✅ 已修复。新增反向孤儿检测和清理，现在正向和反向孤儿均被处理：
```java
// 正向孤儿：tool_use 没有对应 tool_result
Set<String> orphanToolUseIds = new HashSet<>(toolUseIds);
orphanToolUseIds.removeAll(toolResultIds);
// 反向孤儿：tool_result 没有对应 tool_use
Set<String> orphanToolResultIds = new HashSet<>(toolResultIds);
orphanToolResultIds.removeAll(toolUseIds);

// 清理时同时处理两种孤儿
if (block instanceof ToolUseBlock t && orphanToolUseIds.contains(t.id())) { ... }
else if (block instanceof ToolResultBlock t && orphanToolResultIds.contains(t.toolUseId())) { ... }
```

---

### M8. `MemoryManager.extractAsync` 未处理 `RejectedExecutionException` — ✅ 已修复

**文件**: `src/main/java/com/maplecode/memory/MemoryManager.java` 第 41-49 行

**问题**: 程序退出时 shutdown hook 调用 `close()` → `executor.shutdownNow()`。如果此时恰好有对话完成触发 `extractAsync`，`executor.submit()` 抛出 `RejectedExecutionException`，未捕获。

**影响**: 边缘场景下 shutdown 阶段抛未捕获异常。

**修复状态**: ✅ 已修复。新增 `try-catch(RejectedExecutionException)`：
```java
public void extractAsync(List<ChatMessage> recentMessages) {
    if (!config.enabled()) return;
    if (!counter.isOpen()) return;
    try {
        executor.submit(() -> doExtract(recentMessages));
    } catch (RejectedExecutionException ignored) {
        // executor 已关闭（shutdown 阶段），忽略
    }
}
```

---

### S1. `MemoryExtractor` system prompt 中英混用 — ❌ 未修复

**文件**: `src/main/java/com/maplecode/memory/MemoryExtractor.java` 第 35 行

**问题**: 英文 system prompt 中嵌入了中文 `"当前已有记忆："`。不致命，但风格不一致，可能影响模型对 prompt 的理解一致性。

**修复状态**: ❌ 未修复。第 35 行仍为 `"当前已有记忆："`。

**修复建议**: 统一为英文 `"Existing memories:"`。

---

### S2. `CompactCoordinator` 重复 token 估算 — ✅ 已修复

**文件**: `src/main/java/com/maplecode/compact/CompactCoordinator.java` 第 104、117-118 行

**问题**: 第 117 行重新计算了 `offloaded` 的 token 估算，与第 104 行完全相同，浪费计算。且变量名 `summaryInputTokens` 语义不准确。

**修复状态**: ✅ 已修复。删除重复估算，直接复用第 104 行的 `afterOffload` 变量：
```java
int afterOffload = estimator.estimate(offloaded, anchor);  // 第 104 行
// ...
return new CompactOutcome(
    new CompactResult.ChangedFull(0, afterOffload),  // 复用，不再重复计算
    summarized);
```

---

### S3. `MemoryStore` frontmatter 解析对内容含 `---\n` 不健壮 — ❌ 未修复

**文件**: `src/main/java/com/maplecode/memory/MemoryStore.java` 第 323 行

**问题**: 如果记忆 content 本身包含 `"---\n"`（如用户让 LLM 记住一段 Markdown 文档），frontmatter 结束标记会被提前匹配，导致 content 截断。

**影响**: 含 `---` 的记忆内容被截断。

**修复状态**: ❌ 未修复。第 323 行仍用 `text.indexOf("---\n", FM_START.length())`。

**修复建议**: frontmatter 解析应只在前几行查找结束标记，或用 YAML 解析器。

---

## 三、亮点

审查中也发现了一些设计良好的方面：

1. **sealed 类型层次**: `CompactResult`（6 变体）、`MemoryOp`（3 变体）使用 sealed 接口，保证 switch 穷尽性。
2. **双层熔断器**: compact 和 memory 各有独立的 `FailureCounter`，连续失败后自动降级，避免反复打失败的 LLM 调用。
3. **Offloader 的两阶段策略**: 先 offload 单条超大 tool result，再按总量聚合 offload，层次清晰。
4. **ChatSession 的 append-only 不变量**: 只在成功时追加，流异常时不修改 session，用户可安全重试。`replaceAll` 做防御性拷贝。
5. **SessionReader 的双向孤儿清理**: 恢复存档时自动截断无配对的 tool_use 和 tool_result（M7 修复后已支持双向），防止 API 报错。
6. **MemoryExtractor 的 JSON 容错解析**: 先直接解析，失败后正则贪婪匹配 + 去除 markdown 代码块，多层降级。
7. **IoUtil 原子写入工具**: 修复 M2 时新增的 `IoUtil.atomicWrite()` 采用 temp + atomic-move 模式，跨文件系统自动降级，异常时清理临时文件，设计周全。

---

## 四、修复状态总览

| 编号 | 问题 | 严重度 | 状态 |
|------|------|--------|------|
| B1 | toSlug 越界 | Bug | ✅ 已修复 |
| B2 | token 重复计算 | Bug | ✅ 已修复 |
| B3 | 并发竞态 | Bug | ✅ 已修复 |
| B4 | 连续 USER 消息 | Bug | ❌ 未修复 (P0) |
| M1 | 假请求获取消息 | Medium | ✅ 已修复 |
| M2 | 非原子写入 | Medium | ✅ 已修复 |
| M3 | 拒绝标记误判 | Medium | ✅ 已修复 |
| M4 | 两套 token 估算 | Medium | ✅ 已修复 |
| M5 | parseIndex em-dash | Medium | ❌ 未修复 |
| M6 | 首轮不压缩 | Medium | ❌ 未修复 |
| M7 | 反向孤儿 | Medium | ✅ 已修复 |
| M8 | RejectedExecution | Medium | ✅ 已修复 |
| S1 | prompt 中英混用 | Small | ❌ 未修复 |
| S2 | 重复估算 | Small | ✅ 已修复 |
| S3 | frontmatter 未转义 | Small | ❌ 未修复 |

**统计**: 10 已修复 / 5 未修复。Bug 级 3/4 已修，中等级 6/8 已修，轻微级 1/3 已修。

**未修复项优先级**:
1. **P0 — B4 (连续 USER 消息)**: 唯一未修 Bug，压缩后几乎必然触发，可能导致 API 报错或熔断
2. **P2 — M5, M6**: 健壮性改进
3. **P3 — S1, S3**: 代码质量优化
