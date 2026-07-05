package com.maplecode.provider.anthropic;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicStreamParserCacheTest {

    @Test
    void messageStartCarriesCacheTokens() {
        var parser = new AnthropicStreamParser();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;

        parser.feed(new SseEvent("message_start",
            "{\"type\":\"message_start\","
          + "\"message\":{\"usage\":{\"input_tokens\":2000,"
          + "\"cache_creation_input_tokens\":1800,"
          + "\"cache_read_input_tokens\":0}}}"),
            sink);
        parser.feed(new SseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":300}}"),
            sink);
        parser.feed(new SseEvent("message_stop",
            "{\"type\":\"message_stop\"}"), sink);

        var msgEnd = (StreamChunk.MessageEnd) out.get(out.size() - 1);
        TokenUsage u = msgEnd.usage();
        assertNotNull(u);
        assertEquals(2000, u.inputTokens());
        assertEquals(300, u.outputTokens());
        assertEquals(1800, u.cacheCreationTokens());
        assertEquals(0, u.cacheReadTokens());
    }

    @Test
    void messageStartCacheReadOnSecondCall() {
        var parser = new AnthropicStreamParser();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;

        parser.feed(new SseEvent("message_start",
            "{\"type\":\"message_start\","
          + "\"message\":{\"usage\":{\"input_tokens\":2000,"
          + "\"cache_creation_input_tokens\":0,"
          + "\"cache_read_input_tokens\":1800}}}"),
            sink);
        parser.feed(new SseEvent("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":150}}"),
            sink);
        parser.feed(new SseEvent("message_stop",
            "{\"type\":\"message_stop\"}"), sink);

        var msgEnd = (StreamChunk.MessageEnd) out.get(out.size() - 1);
        TokenUsage u = msgEnd.usage();
        assertEquals(0, u.cacheCreationTokens());
        assertEquals(1800, u.cacheReadTokens());
    }
}
