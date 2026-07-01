package com.maplecode.provider;

import java.util.function.Consumer;

public interface LlmProvider {
    /**
     * Streams a chat completion. Each chunk is pushed synchronously to the sink.
     * Transport / protocol / HTTP errors throw ProviderException.
     */
    void stream(ChatRequest request, Consumer<StreamChunk> sink);
}
