package com.maplecode.provider.openai;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamParserToolTest {

    private final OpenAiStreamParser parser = new OpenAiStreamParser();

    private static SseEvent ev(String data) {
        return new SseEvent("", data);
    }

    @Test
    void tool_calls_split_across_deltas_emits_chunks() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> c = out::add;

        // 第一个 delta：id + name 出现，arguments 为空
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]},"
                + "\"finish_reason\":null}]}"), c);
        // 第二个 delta：完整 arguments
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}"), c);
        // 结束
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"), c);
        parser.feed(ev("[DONE]"), c);

        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long deltas = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseDelta).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        long me = out.stream().filter(c2 -> c2 instanceof StreamChunk.MessageEnd).count();
        assertTrue(starts >= 1, "ToolUseStart count; got: " + out);
        assertTrue(ends >= 1, "ToolUseEnd count; got: " + out);
        assertEquals(1, me, "MessageEnd count");

        StreamChunk.ToolUseEnd end = (StreamChunk.ToolUseEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).findFirst().orElseThrow();
        assertEquals("call_1", end.id());
        assertEquals("read_file", end.name());

        StreamChunk.MessageEnd me2 = (StreamChunk.MessageEnd) out.stream()
            .filter(c2 -> c2 instanceof StreamChunk.MessageEnd).findFirst().orElseThrow();
        assertEquals(StreamChunk.StopReason.TOOL_USE, me2.reason());
    }

    @Test
    void multiple_parallel_tool_calls_all_flushed() {
        parser.reset();
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> c = out::add;

        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"foo\",\"arguments\":\"\"}},"
                + "{\"index\":1,\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"bar\",\"arguments\":\"\"}}"
                + "]},\"finish_reason\":null}]}"), c);
        parser.feed(ev(
            "{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"function\":{\"arguments\":\"{}\"}}"
                + "]},\"finish_reason\":\"tool_calls\"}]}"), c);
        parser.feed(ev("[DONE]"), c);

        long starts = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseStart).count();
        long ends = out.stream().filter(c2 -> c2 instanceof StreamChunk.ToolUseEnd).count();
        assertEquals(2, starts);
        assertEquals(2, ends);
    }
}
