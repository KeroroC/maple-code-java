package com.maplecode.http;

import com.maplecode.error.ProviderException;
import com.maplecode.http.SseStreamReader.SseEvent;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SseStreamReaderTest {

    private List<SseEvent> feed(SseStreamReader reader, String... lines) {
        HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(Stream.of(lines));
        var out = new java.util.ArrayList<SseEvent>();
        reader.read(resp, out::add);
        return out;
    }

    @Test
    void single_data_line_with_implicit_event() {
        var events = feed(new SseStreamReader(), "data: hello\n", "");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).eventType());  // SSE spec default
        assertEquals("hello", events.get(0).data());
    }

    @Test
    void explicit_event_type() {
        var events = feed(new SseStreamReader(),
            "event: ping\n", "data: {\"x\":1}\n", "");
        assertEquals(1, events.size());
        assertEquals("ping", events.get(0).eventType());
        assertEquals("{\"x\":1}", events.get(0).data());
    }

    @Test
    void multiline_data_is_joined_with_newline() {
        var events = feed(new SseStreamReader(),
            "data: line1\n", "data: line2\n", "data: line3\n", "");
        assertEquals(1, events.size());
        assertEquals("line1\nline2\nline3", events.get(0).data());
    }

    @Test
    void comment_lines_are_ignored() {
        var events = feed(new SseStreamReader(),
            ": this is a comment\n", "data: real\n", "");
        assertEquals(1, events.size());
        assertEquals("real", events.get(0).data());
    }

    @Test
    void heartbeat_comment_does_not_emit_event() {
        var events = feed(new SseStreamReader(),
            ": heartbeat\n", ": heartbeat\n", "data: only-real\n", "");
        assertEquals(1, events.size());
        assertEquals("only-real", events.get(0).data());
    }

    @Test
    void done_marker_is_emitted_as_event_with_done_data() {
        var events = feed(new SseStreamReader(),
            "data: [DONE]\n", "");
        assertEquals(1, events.size());
        assertEquals("[DONE]", events.get(0).data());
    }

    @Test
    void multiple_events_separated_by_blank_lines() {
        var events = feed(new SseStreamReader(),
            "event: a\n", "data: 1\n",
            "",
            "event: b\n", "data: 2\n",
            "");
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).eventType());
        assertEquals("1", events.get(0).data());
        assertEquals("b", events.get(1).eventType());
        assertEquals("2", events.get(1).data());
    }

    @Test
    void empty_data_field_still_emits_event() {
        var events = feed(new SseStreamReader(),
            "event: keepalive\n", "data:\n", "");
        assertEquals(1, events.size());
        assertEquals("keepalive", events.get(0).eventType());
        assertEquals("", events.get(0).data());
    }

    @Test
    void cancellationFromEventSinkPropagatesUnwrapped() {
        HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(Stream.of("data: one", ""));
        var expected = new CancellationException("cancelled");
        var actual = assertThrows(CancellationException.class,
            () -> new SseStreamReader().read(resp, event -> { throw expected; }));
        assertSame(expected, actual);
    }

    @Test
    void otherRuntimeFailureIsStillWrapped() {
        HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(Stream.of("data: one", ""));
        assertThrows(ProviderException.class,
            () -> new SseStreamReader().read(resp,
                event -> { throw new IllegalStateException("boom"); }));
    }
}
