package com.maplecode.provider.anthropic;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicStreamParserToolTest {

    private final AnthropicStreamParser parser = new AnthropicStreamParser();

    private static SseEvent ev(String type, String data) {
        return new SseEvent(type, data);
    }

    @Test
    void tool_use_start_delta_stop_emits_three_chunks() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> c = out::add;

        parser.feed(ev("content_block_start",
            "{\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\"}}"), c);
        parser.feed(ev("content_block_delta",
            "{\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}"), c);
        parser.feed(ev("content_block_delta",
            "{\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"/tmp/x\\\"}\"}}"), c);
        parser.feed(ev("content_block_stop", "{\"index\":1}"), c);

        // 1 个 ToolUseStart, 2 个 ToolUseDelta, 1 个 ToolUseEnd
        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long deltas = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseDelta).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        assertEquals(1, starts);
        assertEquals(2, deltas);
        assertEquals(1, ends);

        StreamChunk.ToolUseEnd end = (StreamChunk.ToolUseEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).findFirst().orElseThrow();
        assertEquals("tu_1", end.id());
        assertEquals("read_file", end.name());
        assertEquals("/tmp/x", end.input().get("path").asText());
    }

    @Test
    void stop_reason_tool_use_maps_to_TOOL_USE() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        parser.feed(ev("message_delta",
            "{\"delta\":{\"stop_reason\":\"tool_use\"}}"), out::add);
        parser.feed(ev("message_stop", "{}"), out::add);
        StreamChunk.MessageEnd me = (StreamChunk.MessageEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.MessageEnd).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.TOOL_USE, me.reason());
    }

    @Test
    void invalid_partial_json_emits_error_chunk_not_throw() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        parser.feed(ev("content_block_start",
            "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"foo\"}}"), out::add);
        parser.feed(ev("content_block_delta",
            "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{not json\"}}"), out::add);
        parser.feed(ev("content_block_stop", "{\"index\":0}"), out::add);
        // 应有 Error chunk，不抛
        assertTrue(out.stream().anyMatch(c2 -> c2 instanceof StreamChunk.Error),
            "expected Error chunk, got: " + out);
    }
}
