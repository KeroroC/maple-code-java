package com.maplecode.fake;

import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class FakeLlmProviderTest {

    @Test
    void emitsPreprogrammedChunks() {
        List<StreamChunk> chunks = List.of(
            new StreamChunk.MessageStart(),
            new StreamChunk.TextDelta("hi"),
            new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN, null)
        );
        LlmProvider fake = new FakeLlmProvider(List.of(chunks));

        var received = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, received::add);

        assertEquals(3, received.size());
    }

    @Test
    void multipleCallsDrainSequence() {
        var chunks1 = List.<StreamChunk>of(new StreamChunk.TextDelta("a"));
        var chunks2 = List.<StreamChunk>of(new StreamChunk.TextDelta("b"));
        LlmProvider fake = new FakeLlmProvider(List.of(chunks1, chunks2));

        var r1 = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, r1::add);
        var r2 = new java.util.ArrayList<StreamChunk>();
        fake.stream(null, r2::add);

        assertEquals("a", ((StreamChunk.TextDelta) r1.get(0)).text());
        assertEquals("b", ((StreamChunk.TextDelta) r2.get(0)).text());
    }

    @Test
    void throwsWhenExhausted() {
        LlmProvider fake = new FakeLlmProvider(List.of());
        assertThrows(NoSuchElementException.class,
            () -> fake.stream(null, c -> {}));
    }
}
