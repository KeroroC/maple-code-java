package com.maplecode.compact;

public record CompactContext(
    CompactConfig config,
    CompactStorage storage,
    FailureCounter counter
) {}
