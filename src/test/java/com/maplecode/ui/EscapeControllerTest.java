package com.maplecode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EscapeControllerTest {
    private LineReader reader;
    private Buffer buffer;
    private Terminal terminal;
    private KeyMap<Binding> main;
    private Map<String, Widget> widgets;
    private NonBlockingReader terminalReader;
    private Attributes originalAttributes;

    @BeforeEach
    void setUp() {
        reader = mock(LineReader.class);
        buffer = mock(Buffer.class);
        terminal = mock(Terminal.class);
        main = new KeyMap<>();
        widgets = new HashMap<>();
        terminalReader = mock(NonBlockingReader.class);
        originalAttributes = mock(Attributes.class);
        when(reader.getBuffer()).thenReturn(buffer);
        when(reader.getTerminal()).thenReturn(terminal);
        when(reader.getWidgets()).thenReturn(widgets);
        when(reader.getKeyMaps()).thenReturn(Map.of(LineReader.MAIN, main));
        when(terminal.reader()).thenReturn(terminalReader);
        when(terminal.enterRawMode()).thenReturn(originalAttributes);
    }

    @Test
    void installBindsSingleAndDoubleEscAt500ms() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        assertEquals(500L, main.getAmbiguousTimeout());
        assertEquals(new Reference(EscapeController.NOOP_WIDGET),
            main.getBound(KeyMap.esc()));
        assertEquals(new Reference(EscapeController.CLEAR_WIDGET),
            main.getBound(KeyMap.esc() + KeyMap.esc()));
    }

    @Test
    void installKeepsLongerArrowBinding() {
        var up = new Reference(LineReader.UP_HISTORY);
        main.bind(up, KeyMap.esc() + "[A");
        new EscapeController(reader).installInputBindings();
        assertEquals(up, main.getBound(KeyMap.esc() + "[A"));
    }

    @Test
    void clearWidgetClearsNormalBufferWithoutAcceptingLine() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        assertTrue(widgets.get(EscapeController.CLEAR_WIDGET).apply());
        verify(buffer).clear();
        verify(reader, never()).callWidget(LineReader.ACCEPT_LINE);
        assertFalse(controller.consumeMultilineAbort());
    }

    @Test
    void clearWidgetAbortsWholeMultilineInput() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        controller.beginMultiline();
        assertTrue(widgets.get(EscapeController.CLEAR_WIDGET).apply());
        verify(buffer).clear();
        verify(reader).callWidget(LineReader.ACCEPT_LINE);
        assertTrue(controller.consumeMultilineAbort());
        assertFalse(controller.consumeMultilineAbort());
        controller.endMultiline();
    }

    @Test
    void singleEscCancelsOnceAndRestoresTerminal() throws Exception {
        when(terminalReader.read(100L)).thenReturn(27);
        var latch = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var controller = new EscapeController(reader);
        controller.startAgentStreaming(() -> {
            calls.incrementAndGet();
            latch.countDown();
        });
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        controller.stopAgentStreaming();
        assertEquals(1, calls.get());
        verify(terminal).setAttributes(originalAttributes);
    }

    @Test
    void nonEscInputDoesNotCancel() throws Exception {
        when(terminalReader.read(100L))
            .thenReturn((int) 'x')
            .thenReturn(NonBlockingReader.EOF);
        var calls = new AtomicInteger();
        var controller = new EscapeController(reader);
        controller.startAgentStreaming(calls::incrementAndGet);
        verify(terminal, timeout(1000)).setAttributes(originalAttributes);
        controller.stopAgentStreaming();
        assertEquals(0, calls.get());
    }

    @Test
    void repeatedStopRestoresTerminalOnlyOnce() {
        var controller = new EscapeController(reader);
        controller.startAgentStreaming(() -> {});
        controller.stopAgentStreaming();
        controller.stopAgentStreaming();
        verify(terminal, times(1)).setAttributes(originalAttributes);
    }

    @Test
    void listenerFailureRestoresTerminalWithoutCancelling() throws Exception {
        when(terminalReader.read(100L)).thenThrow(new IOException("boom"));
        var calls = new AtomicInteger();
        var controller = new EscapeController(reader);
        controller.startAgentStreaming(calls::incrementAndGet);
        verify(terminal, timeout(1000)).setAttributes(originalAttributes);
        assertEquals(0, calls.get());
    }
}
