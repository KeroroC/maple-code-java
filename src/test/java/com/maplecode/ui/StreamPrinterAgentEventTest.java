package com.maplecode.ui;

import com.maplecode.agent.AgentEvent;
import com.maplecode.provider.StreamChunk.StopReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamPrinterAgentEventTest {

    @Test
    void textDeltaWritesRaw() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.TextDelta("hello"));
        assertEquals("hello", out.toString());
    }

    @Test
    void thinkingDeltaWritesDim() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ThinkingDelta("hmm"));
        assertTrue(out.toString().contains("hmm"));
        assertTrue(out.toString().contains("\033[90m"));
    }

    @Test
    void toolCallStartWritesArgSummary() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolCallStart("t1", "read_file", "/tmp/foo"));
        var s = out.toString();
        assertTrue(s.contains("read_file"));
        assertTrue(s.contains("/tmp/foo"));
    }

    @Test
    void toolResultSuccessWritesGreenCheck() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolResult("t1", "read_file", false, "content"));
        assertTrue(out.toString().contains("✓"));
        assertTrue(out.toString().contains("read_file"));
    }

    @Test
    void toolResultErrorWritesRedX() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.ToolResult("t1", "read_file", true, "file not found"));
        assertTrue(out.toString().contains("✗"));
        assertTrue(out.toString().contains("file not found"));
    }

    @Test
    void agentStopWritesBracketedMessage() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.AgentStop(StopReason.MAX_ITERATIONS, "cap"));
        var s = out.toString();
        assertTrue(s.contains("[agent stopped"));
        assertTrue(s.contains("MAX_ITERATIONS"));
    }

    @Test
    void silentEventsWriteNothing() {
        var out = new ByteArrayOutputStream();
        var printer = new StreamPrinter(new PrintStream(out));
        printer.accept(new AgentEvent.IterationStart(0));
        printer.accept(new AgentEvent.IterationEnd(0, StopReason.END_TURN, List.of(), null));
        printer.accept(new AgentEvent.BatchStart(2, 1));
        printer.accept(new AgentEvent.BatchEnd(3, 0));
        printer.accept(new AgentEvent.ToolCallEnd("t1", "read_file", null));
        assertEquals("", out.toString());
    }
}
