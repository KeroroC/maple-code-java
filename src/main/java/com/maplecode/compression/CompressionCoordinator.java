package com.maplecode.compression;

import com.maplecode.provider.*;
import com.maplecode.session.ChatSession;

import java.util.List;

/**
 * 压缩协调器 —— 压缩功能的唯一公开入口。
 * 协调 offloader（第一层）和 summarizer（第二层），并集成熔断器。
 */
public final class CompressionCoordinator {

    /**
     * 压缩尝试的结果，包含压缩结果和（若有变更）新的消息列表。
     * 调用方负责将 newMessages 应用到 session。
     */
    public record CompressionOutcome(CompressionResult result, List<ChatMessage> newMessages) {}

    private final CompressionContext ctx;
    private final Offloader offloader;
    private final ConversationSummarizer summarizer;
    private final TokenEstimator estimator = new TokenEstimator();

    private volatile TokenUsage lastSeenUsage;

    public CompressionCoordinator(CompressionContext ctx, LlmProvider provider,
                                  Offloader offloader, ConversationSummarizer summarizer) {
        this.ctx = ctx;
        this.offloader = offloader;
        this.summarizer = summarizer;
    }

    /**
     * 由 AgentLoop 在每次 MessageEnd 时调用，记录最新的 token 用量锚点。
     */
    public void recordUsage(TokenUsage usage) {
        this.lastSeenUsage = usage;
    }

    /**
     * 读取当前锚点，供需要传递给 TokenEstimator 的调用方使用。
     */
    public TokenUsage lastSeenUsage() {
        return lastSeenUsage;
    }

    /**
     * 由 /clear 命令调用，重置失败计数器以允许压缩重试。
     */
    public void resetCounter() {
        ctx.counter().reset();
    }

    /**
     * 关闭钩子 —— 委托给存储层进行清理。
     */
    public void close() {
        ctx.storage().close();
    }

    /**
     * 在向 LLM 发送请求前协调压缩流程。
     *
     * 流程：
     * 1. 检查 counter.isTripped() → 返回 SKIPPED_CIRCUIT_OPEN（仅自动触发，手动触发继续执行）
     * 2. 估算 token 数 → 低于阈值则返回 NOOP
     * 3. 运行 offloader → 若已足够则返回 CHANGED_OFFLOAD_ONLY
     * 4. 运行 summarizer → 返回 CHANGED_FULL；在计数器上记录成功/失败
     *
     * @return CompressionOutcome，包含结果和新消息列表（无变更时为 null）
     */
    public CompressionOutcome beforeRequest(ChatSession session, CompressionTrigger trigger,
                                            TokenUsage anchorOverride) {
        var cfg = ctx.config();

        // 步骤 1：熔断器检查 —— 仅自动触发
        if (trigger == CompressionTrigger.AUTO && ctx.counter().isTripped()) {
            return new CompressionOutcome(
                new CompressionResult.SkippedCircuitOpen(ctx.counter().failures()),
                null);
        }

        // 获取 session 中的当前消息
        List<ChatMessage> current = session.toRequest("unused", List.of(), null, List.of()).messages();
        TokenUsage anchor = anchorOverride != null ? anchorOverride : lastSeenUsage;

        // 步骤 2：估算 token 数
        int estimated = estimator.estimate(current, anchor);
        int threshold = cfg.window() - cfg.marginFor(trigger);
        if (estimated < threshold) {
            return new CompressionOutcome(new CompressionResult.Noop(), null);
        }

        // 步骤 3：运行 offloader（第一层）
        List<ChatMessage> offloaded;
        try {
            offloaded = offloader.apply(current, cfg);
        } catch (CompressionException e) {
            return new CompressionOutcome(
                new CompressionResult.FailedOffload(e.getMessage()),
                null);
        }
        int afterOffload = estimator.estimate(offloaded, anchor);

        if (afterOffload < threshold) {
            // 仅 offload 即已足够
            return new CompressionOutcome(
                new CompressionResult.ChangedOffloadOnly(0),
                offloaded);
        }

        // 步骤 4：运行 summarizer（第二层）
        try {
            List<ChatMessage> summarized = summarizer.apply(offloaded, cfg);
            ctx.counter().recordSuccess();
            int summaryInputTokens = estimator.estimate(offloaded, anchor);
            return new CompressionOutcome(
                new CompressionResult.ChangedFull(0, summaryInputTokens),
                summarized);
        } catch (CompressionException e) {
            ctx.counter().recordFailure();
            return new CompressionOutcome(
                new CompressionResult.FailedSummary(e.getMessage(), ctx.counter().failures()),
                null);
        }
    }
}
