package com.maplecode.compression;

public record CompressionContext(
    CompressionConfig config,
    CompressionStorage storage,
    FailureCounter counter
) {}
