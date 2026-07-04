package com.maplecode.provider.openai;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiStreamParserUsageTest {

    private final OpenAiStreamParser parser = new OpenAiStreamParser();

    private static SseEvent ev(String data) {
        return new SseEvent("", data);
    }

    @Test
    void lastChunkWithUsagePropagates() {
        parser.reset();
        List<StreamChunk> chunks = new ArrayList<>();
        Consumer<StreamChunk> sink = chunks::add;

        parser.feed(ev("{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}"), sink);
        parser.feed(ev("{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"), sink);
        parser.feed(ev("{\"id\":\"x\",\"choices\":[],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10}}"), sink);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertEquals(new TokenUsage(30, 10), messageEnd.usage());
    }

    @Test
    void noUsageChunkMeansNull() {
        parser.reset();
        List<StreamChunk> chunks = new ArrayList<>();
        Consumer<StreamChunk> sink = chunks::add;

        parser.feed(ev("{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}"), sink);
        parser.feed(ev("{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"), sink);
        parser.feed(ev("[DONE]"), sink);

        var messageEnd = chunks.stream()
            .filter(c -> c instanceof StreamChunk.MessageEnd)
            .map(c -> (StreamChunk.MessageEnd) c)
            .findFirst()
            .orElseThrow();

        assertNull(messageEnd.usage());
    }
}
