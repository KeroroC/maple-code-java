package com.maplecode.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentEvent;
import com.maplecode.command.CommandRegistry;
import com.maplecode.config.AppConfig;
import com.maplecode.memory.MemoryManager;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplLoopEscapeTest {
    private static ReplLoop newRepl(LineReader reader, LlmProvider provider,
                                    MemoryManager memory, EscapeController escape) {
        var config = mock(AppConfig.class);
        when(config.model()).thenReturn("model");
        var engine = mock(PermissionEngine.class);
        when(engine.mode()).thenReturn(PermissionMode.DEFAULT);
        var registry = new ToolRegistry(List.of());
        return new ReplLoop(config, provider, mock(StreamPrinter.class), reader,
            registry, new ToolExecutor(registry), engine, AgentConfig.defaults(),
            null, null, memory, null, new CommandRegistry(), escape);
    }

    @Test
    void cancelledAgentStopsListenerAndSkipsMemoryExtraction() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("hello", (String) null);
        var memory = mock(MemoryManager.class);
        var escape = mock(EscapeController.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(escape).startAgentStreaming(any(Runnable.class));
        LlmProvider provider = (req, sink) -> {
            sink.accept(new StreamChunk.TextDelta("ignored"));
            sink.accept(new StreamChunk.MessageEnd(
                StreamChunk.StopReason.USER_CANCELLED, null));
        };

        newRepl(reader, provider, memory, escape).run();

        verify(escape).startAgentStreaming(any(Runnable.class));
        verify(escape, atLeastOnce()).stopAgentStreaming();
        verify(memory, never()).extractAsync(any());
    }

    @Test
    void multilineAbortDiscardsAccumulatedLines() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("\"\"\"");
        when(reader.readLine("... ")).thenReturn("first", "");
        var escape = mock(EscapeController.class);
        when(escape.consumeMultilineAbort()).thenReturn(false, true);
        var repl = newRepl(reader, (req, sink) -> {}, null, escape);

        assertEquals("", repl.readMultiline());
        verify(escape).beginMultiline();
        verify(escape).endMultiline();
    }

    @Test
    void toolBatchStopsListenerBeforeNextIteration() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("tools", (String) null);
        var escape = mock(EscapeController.class);
        var calls = new AtomicInteger();
        var json = new ObjectMapper();
        LlmProvider provider = (req, sink) -> {
            if (calls.incrementAndGet() == 1) {
                sink.accept(new StreamChunk.ToolUseStart("t1", "unknown"));
                sink.accept(new StreamChunk.ToolUseEnd("t1", "unknown",
                    json.createObjectNode()));
                sink.accept(new StreamChunk.MessageEnd(
                    StreamChunk.StopReason.TOOL_USE, null));
            } else {
                sink.accept(new StreamChunk.TextDelta("done"));
                sink.accept(new StreamChunk.MessageEnd(
                    StreamChunk.StopReason.END_TURN, null));
            }
        };

        newRepl(reader, provider, null, escape).run();

        var order = inOrder(escape);
        order.verify(escape).startAgentStreaming(any(Runnable.class));
        order.verify(escape).stopAgentStreaming();
    }
}
