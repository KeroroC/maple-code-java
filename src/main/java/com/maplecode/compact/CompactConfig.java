package com.maplecode.compact;

public record CompactConfig(
    int window,
    int autoMargin,
    int manualMargin,
    int singleToolResultOffloadTokens,
    int messageToolResultAggregateTokens,
    int recencyTokens,
    int recencyMinMessages,
    int previewHeadLines,
    int previewTailLines,
    int failureThreshold
) {
    public CompactConfig {
        if (window < 1000) throw new IllegalArgumentException("window must be >= 1000");
        if (autoMargin < 0) throw new IllegalArgumentException("autoMargin must be >= 0");
        if (manualMargin < 0) throw new IllegalArgumentException("manualMargin must be >= 0");
        if (singleToolResultOffloadTokens < 100) throw new IllegalArgumentException("singleToolResultOffloadTokens must be >= 100");
        if (messageToolResultAggregateTokens < singleToolResultOffloadTokens) {
            throw new IllegalArgumentException("messageToolResultAggregateTokens must be >= singleToolResultOffloadTokens");
        }
        if (recencyTokens < 100) throw new IllegalArgumentException("recencyTokens must be >= 100");
        if (recencyMinMessages < 1) throw new IllegalArgumentException("recencyMinMessages must be >= 1");
        if (previewHeadLines < 1) throw new IllegalArgumentException("previewHeadLines must be >= 1");
        if (previewTailLines < 1) throw new IllegalArgumentException("previewTailLines must be >= 1");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
    }

    public static final int DEFAULT_WINDOW = 200_000;
    public static final int DEFAULT_AUTO_MARGIN = 13_000;
    public static final int DEFAULT_MANUAL_MARGIN = 3_000;
    public static final int DEFAULT_SINGLE_TOOL_RESULT_OFFLOAD_TOKENS = 8_000;
    public static final int DEFAULT_MESSAGE_TOOL_RESULT_AGGREGATE_TOKENS = 30_000;
    public static final int DEFAULT_RECENCY_TOKENS = 10_000;
    public static final int DEFAULT_RECENCY_MIN_MESSAGES = 5;
    public static final int DEFAULT_PREVIEW_HEAD_LINES = 8;
    public static final int DEFAULT_PREVIEW_TAIL_LINES = 4;
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    public static CompactConfig fromAppConfig(int yamlContextWindow) {
        int window = yamlContextWindow > 0 ? yamlContextWindow : DEFAULT_WINDOW;
        return new CompactConfig(
            window, DEFAULT_AUTO_MARGIN, DEFAULT_MANUAL_MARGIN,
            DEFAULT_SINGLE_TOOL_RESULT_OFFLOAD_TOKENS,
            DEFAULT_MESSAGE_TOOL_RESULT_AGGREGATE_TOKENS,
            DEFAULT_RECENCY_TOKENS, DEFAULT_RECENCY_MIN_MESSAGES,
            DEFAULT_PREVIEW_HEAD_LINES, DEFAULT_PREVIEW_TAIL_LINES,
            DEFAULT_FAILURE_THRESHOLD);
    }

    public int marginFor(CompactTrigger trigger) {
        return switch (trigger) {
            case AUTO -> autoMargin;
            case MANUAL -> manualMargin;
        };
    }
}
