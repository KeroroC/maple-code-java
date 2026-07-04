package com.maplecode.fake;

import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * 测试用 LlmProvider：按脚本返回 chunks 序列。
 * <p>
 * 每次 stream() 调用消耗一个脚本（List&lt;StreamChunk&gt;），
 * 全部 chunk push 给 sink。脚本用完抛 NoSuchElementException。
 */
public final class FakeLlmProvider implements LlmProvider {

    private final Deque<List<StreamChunk>> scripts = new ArrayDeque<>();

    public FakeLlmProvider(List<List<StreamChunk>> scripts) {
        this.scripts.addAll(scripts);
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamChunk> sink) {
        var script = scripts.poll();
        if (script == null) {
            throw new NoSuchElementException("FakeLlmProvider: no more scripts");
        }
        for (StreamChunk chunk : script) {
            sink.accept(chunk);
        }
    }
}
