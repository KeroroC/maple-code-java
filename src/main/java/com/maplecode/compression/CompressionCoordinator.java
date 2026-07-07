package com.maplecode.compression;

import com.maplecode.provider.*;
import com.maplecode.session.ChatSession;

import java.util.List;

/**
 * Orchestrator — the ONLY public entry point for compression.
 * Coordinates offloader (layer 1) and summarizer (layer 2) with a circuit breaker.
 */
public final class CompressionCoordinator {

    /**
     * Outcome of a compression attempt, bundling the result and (if changed) the new messages.
     * The caller is responsible for applying newMessages to the session.
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
     * Called by AgentLoop on each MessageEnd to record the latest token usage anchor.
     */
    public void recordUsage(TokenUsage usage) {
        this.lastSeenUsage = usage;
    }

    /**
     * Read the current anchor for callers that need to pass it to TokenEstimator.
     */
    public TokenUsage lastSeenUsage() {
        return lastSeenUsage;
    }

    /**
     * Called by /clear to reset the failure counter so compression retries are allowed.
     */
    public void resetCounter() {
        ctx.counter().reset();
    }

    /**
     * Shutdown hook — delegates to storage cleanup.
     */
    public void close() {
        ctx.storage().close();
    }

    /**
     * Orchestrates compression before sending a request to the LLM.
     *
     * Flow:
     * 1. Check counter.isTripped() → SKIPPED_CIRCUIT_OPEN (auto only, manual proceeds)
     * 2. Estimate tokens → NOOP if below threshold
     * 3. Run offloader → CHANGED_OFFLOAD_ONLY if enough
     * 4. Run summarizer → CHANGED_FULL; records success/failure on counter
     *
     * @return CompressionOutcome with result and new messages (null if unchanged)
     */
    public CompressionOutcome beforeRequest(ChatSession session, CompressionTrigger trigger,
                                            TokenUsage anchorOverride) {
        var cfg = ctx.config();

        // Step 1: Circuit breaker — AUTO only
        if (trigger == CompressionTrigger.AUTO && ctx.counter().isTripped()) {
            return new CompressionOutcome(
                new CompressionResult.SkippedCircuitOpen(ctx.counter().failures()),
                null);
        }

        // Get current messages from session
        List<ChatMessage> current = session.toRequest("unused", List.of(), null, List.of()).messages();
        TokenUsage anchor = anchorOverride != null ? anchorOverride : lastSeenUsage;

        // Step 2: Estimate tokens
        int estimated = estimator.estimate(current, anchor);
        int threshold = cfg.window() - cfg.marginFor(trigger);
        if (estimated < threshold) {
            return new CompressionOutcome(new CompressionResult.Noop(), null);
        }

        // Step 3: Run offloader (layer 1)
        List<ChatMessage> offloaded = offloader.apply(current, cfg);
        int afterOffload = estimator.estimate(offloaded, anchor);

        if (afterOffload < threshold) {
            // Offload alone was sufficient
            return new CompressionOutcome(
                new CompressionResult.ChangedOffloadOnly(0),
                offloaded);
        }

        // Step 4: Run summarizer (layer 2)
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
