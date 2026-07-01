package com.maplecode.provider;

public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR }
}
