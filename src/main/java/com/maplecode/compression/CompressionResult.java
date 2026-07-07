package com.maplecode.compression;

public sealed interface CompressionResult
    permits CompressionResult.Noop,
            CompressionResult.ChangedOffloadOnly,
            CompressionResult.ChangedFull,
            CompressionResult.FailedOffload,
            CompressionResult.FailedSummary,
            CompressionResult.SkippedCircuitOpen {

    record Noop() implements CompressionResult {}
    record ChangedOffloadOnly(int offloadedCount) implements CompressionResult {}
    record ChangedFull(int offloadedCount, int summaryInputTokens) implements CompressionResult {}
    record FailedOffload(String reason) implements CompressionResult {}
    record FailedSummary(String reason, int consecutiveFailures) implements CompressionResult {}
    record SkippedCircuitOpen(int consecutiveFailures) implements CompressionResult {}
}
