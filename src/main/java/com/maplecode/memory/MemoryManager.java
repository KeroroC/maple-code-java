package com.maplecode.memory;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.LlmProvider;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 记忆管理门面，协调 MemoryExtractor（LLM 调用）和 MemoryStore（文件 I/O）。
 * 使用单线程 ExecutorService 保证串行文件 I/O。
 * MemoryFailureCounter 提供熔断语义（连续 3 次失败触发熔断）。
 */
public final class MemoryManager implements Closeable {

    private final MemoryConfig config;
    private final LlmProvider provider;
    private final MemoryStore store;
    private final String mainModel;
    private final MemoryFailureCounter counter = new MemoryFailureCounter();
    private final ReentrantLock storeLock = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-extractor");
        t.setDaemon(true);
        return t;
    });

    public MemoryManager(MemoryConfig config, LlmProvider provider, MemoryStore store, String mainModel) {
        this.config = config;
        this.provider = provider;
        this.store = store;
        this.mainModel = mainModel;
    }

    /** 异步触发记忆提取（ReplLoop 调用）。 */
    public void extractAsync(List<ChatMessage> recentMessages) {
        if (!config.enabled()) return;
        if (!counter.isOpen()) return;
        executor.submit(() -> doExtract(recentMessages));
    }

    /** 同步触发（/memory extract 命令）。 */
    public void extractSync(List<ChatMessage> recentMessages) {
        if (!config.enabled()) return;
        if (!counter.isOpen()) {
            System.err.println("[memory] WARN: circuit breaker open, skipping extraction");
            return;
        }
        doExtract(recentMessages);
    }

    private void doExtract(List<ChatMessage> recentMessages) {
        storeLock.lock();
        try {
            String model = config.memoryModel() != null ? config.memoryModel() : mainModel;
            List<MemoryEntry> allEntries = new ArrayList<>(store.loadIndex(MemoryScope.USER));
            allEntries.addAll(store.loadIndex(MemoryScope.PROJECT));
            var extractor = new MemoryExtractor(provider, model, allEntries);
            MemoryOpsResult result = extractor.extract(recentMessages);

            for (MemoryOp op : result.ops()) {
                try {
                    store.executeOp(op);
                } catch (Exception e) {
                    System.err.println("[memory] WARN: op failed: " + e.getMessage());
                }
            }
            counter.recordSuccess();
        } catch (Exception e) {
            counter.recordFailure();
            System.err.println("[memory] WARN: extraction failed (" + counter.failures() + "): " + e.getMessage());
        } finally {
            storeLock.unlock();
        }
    }

    /** 列出所有记忆。 */
    public String listMemories() {
        storeLock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (MemoryScope scope : MemoryScope.values()) {
                String text = store.loadIndexText(scope);
                if (!text.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.length() == 0 ? "(no memories)" : sb.toString();
        } finally {
            storeLock.unlock();
        }
    }

    /** 清空所有记忆。 */
    public void clearAll() {
        storeLock.lock();
        try {
            store.clearAll(MemoryScope.USER);
            store.clearAll(MemoryScope.PROJECT);
        } finally {
            storeLock.unlock();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
