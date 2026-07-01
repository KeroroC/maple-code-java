package com.maplecode.provider.anthropic;

import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AnthropicStreamParserTest {

    private final AnthropicStreamParser parser = new AnthropicStreamParser();

    private List<StreamChunk> feed(String... lines) {
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;
        parser.reset();
        // parser 消费的是 SseEvent；测试里我们用 SseStreamReader 内部同样的状态机喂入。
        feedLines(lines, sink);
        return out;
    }

    private void feedLines(String[] lines, Consumer<StreamChunk> sink) {
        String currentEvent = "message";
        StringBuilder data = new StringBuilder();
        boolean hasData = false;

        for (String line : lines) {
            if (line.isEmpty()) {
                if (hasData) {
                    parser.feed(new com.maplecode.http.SseStreamReader.SseEvent(currentEvent, data.toString()), sink);
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
            parser.feed(new com.maplecode.http.SseStreamReader.SseEvent(currentEvent, data.toString()), sink);
        }
    }

    @Test
    void message_start_then_text_delta_then_message_stop() {
        var chunks = feed(
            "event: message_start\n",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m_1\"}}\n",
            "",
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n",
            "",
            "event: content_block_stop\n",
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n",
            "",
            "event: message_stop\n",
            "data: {\"type\":\"message_stop\"}\n",
            ""
        );
        assertEquals(4, chunks.size());
        assertInstanceOf(StreamChunk.MessageStart.class, chunks.get(0));
        assertEquals("Hello", ((StreamChunk.TextDelta) chunks.get(1)).text());
        assertEquals(" world", ((StreamChunk.TextDelta) chunks.get(2)).text());
        assertEquals(StreamChunk.StopReason.END_TURN,
            ((StreamChunk.MessageEnd) chunks.get(3)).reason());
    }

    @Test
    void thinking_blocks_emitted_as_ThinkingDelta() {
        var chunks = feed(
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think...\"}}\n",
            "",
            "event: content_block_stop\n",
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n",
            "",
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}\n",
            "",
            "event: message_stop\n",
            "data: {\"type\":\"message_stop\"}\n",
            ""
        );
        long thinking = chunks.stream().filter(c -> c instanceof StreamChunk.ThinkingDelta).count();
        long text = chunks.stream().filter(c -> c instanceof StreamChunk.TextDelta).count();
        assertEquals(1, thinking, "应恰好一个 ThinkingDelta");
        assertEquals(1, text, "应恰好一个 TextDelta");
        assertEquals("Let me think...", ((StreamChunk.ThinkingDelta) chunks.stream()
            .filter(c -> c instanceof StreamChunk.ThinkingDelta).findFirst().orElseThrow()).text());
        assertEquals("Answer", ((StreamChunk.TextDelta) chunks.stream()
            .filter(c -> c instanceof StreamChunk.TextDelta).findFirst().orElseThrow()).text());
    }

    @Test
    void error_event_becomes_StreamChunk_Error() {
        var chunks = feed(
            "event: error\n",
            "data: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}\n",
            ""
        );
        assertEquals(1, chunks.size());
        var err = (StreamChunk.Error) chunks.get(0);
        assertEquals("invalid_request_error", err.code());
        assertEquals("bad", err.message());
    }

    @Test
    void unknown_event_type_is_ignored() {
        var chunks = feed(
            "event: ping\n",
            "data: {\"foo\":1}\n",
            ""
        );
        assertEquals(0, chunks.size());
    }
}