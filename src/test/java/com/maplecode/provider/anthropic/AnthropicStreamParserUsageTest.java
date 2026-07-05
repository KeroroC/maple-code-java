package com.maplecode.provider.anthropic;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicStreamParserUsageTest {

    private final AnthropicStreamParser parser = new AnthropicStreamParser();

    private List<StreamChunk> feedSse(String rawSse) {
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;
        parser.reset();

        String currentEvent = "message";
        StringBuilder data = new StringBuilder();
        boolean hasData = false;

        for (String line : rawSse.split("\n", -1)) {
            if (line.isEmpty()) {
                if (hasData) {
                    parser.feed(new SseEvent(currentEvent, data.toString()), sink);
                }
                currentEvent = "message";
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) payload = payload.substring(1);
                if (hasData) data.append('\n');
                data.append(payload);
                hasData = true;
            }
        }
        if (hasData) {
            parser.feed(new SseEvent(currentEvent, data.toString()), sink);
        }
        return out;
    }

    @Test
    void messageStartCarriesInputUsage() {
        var chunks = feedSse("""
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":100,"output_tokens":0}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}

            event: message_stop
            data: {"type":"message_stop"}
            """);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertEquals(TokenUsage.of(100, 50), messageEnd.usage());
    }

    @Test
    void noUsageMeansNull() {
        var chunks = feedSse("""
            event: message_start
            data: {"type":"message_start","message":{"id":"m1"}}

            event: message_stop
            data: {"type":"message_stop"}
            """);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertNull(messageEnd.usage());
    }
}
