package com.maplecode.provider.openai;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OpenAiStreamParserTest {

    private final OpenAiStreamParser parser = new OpenAiStreamParser();

    private List<StreamChunk> feed(String... dataLines) {
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;
        for (String payload : dataLines) {
            parser.feed(new SseEvent("message", payload), sink);
        }
        return out;
    }

    @Test
    void multiple_text_deltas_accumulate_via_consumer() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"index\":0}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"index\":0}]}"
        );
        assertEquals(2, chunks.size());
        assertEquals("Hel", ((StreamChunk.TextDelta) chunks.get(0)).text());
        assertEquals("lo",  ((StreamChunk.TextDelta) chunks.get(1)).text());
    }

    @Test
    void finish_reason_emits_message_end() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\",\"index\":0}]}"
        );
        assertEquals(2, chunks.size());
        assertInstanceOf(StreamChunk.TextDelta.class, chunks.get(0));
        assertInstanceOf(StreamChunk.MessageEnd.class, chunks.get(1));
        assertEquals(StreamChunk.StopReason.STOP,
            ((StreamChunk.MessageEnd) chunks.get(1)).reason());
    }

    @Test
    void done_marker_emits_message_end_stop() {
        var chunks = feed("[DONE]");
        assertEquals(1, chunks.size());
        assertInstanceOf(StreamChunk.MessageEnd.class, chunks.get(0));
        assertEquals(StreamChunk.StopReason.STOP,
            ((StreamChunk.MessageEnd) chunks.get(0)).reason());
    }

    @Test
    void error_object_in_data_becomes_StreamChunk_Error() {
        var chunks = feed(
            "{\"error\":{\"type\":\"invalid_api_key\",\"message\":\"bad key\"}}"
        );
        assertEquals(1, chunks.size());
        var err = (StreamChunk.Error) chunks.get(0);
        assertEquals("invalid_api_key", err.code());
        assertEquals("bad key", err.message());
    }

    @Test
    void empty_delta_emits_no_chunk() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{},\"index\":0}]}"
        );
        assertEquals(0, chunks.size());
    }

    @Test
    void finish_reason_length_maps_to_max_tokens() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\",\"index\":0}]}"
        );
        assertEquals(StreamChunk.StopReason.MAX_TOKENS,
            ((StreamChunk.MessageEnd) chunks.get(0)).reason());
    }
}
