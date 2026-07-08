package com.maplecode.compact;

public sealed interface CompactResult
    permits CompactResult.Noop,
            CompactResult.ChangedOffloadOnly,
            CompactResult.ChangedFull,
            CompactResult.FailedOffload,
            CompactResult.FailedSummary,
            CompactResult.SkippedCircuitOpen {

    record Noop() implements CompactResult {}
    record ChangedOffloadOnly(int offloadedCount) implements CompactResult {}
    record ChangedFull(int offloadedCount, int summaryInputTokens) implements CompactResult {}
    record FailedOffload(String reason) implements CompactResult {}
    record FailedSummary(String reason, int consecutiveFailures) implements CompactResult {}
    record SkippedCircuitOpen(int consecutiveFailures) implements CompactResult {}
}
