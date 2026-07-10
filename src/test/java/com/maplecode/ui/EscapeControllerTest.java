package com.maplecode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EscapeControllerTest {
    private LineReader reader;
    private Buffer buffer;
    private Terminal terminal;
    private KeyMap<Binding> main;
    private Map<String, Widget> widgets;

    @BeforeEach
    void setUp() {
        reader = mock(LineReader.class);
        buffer = mock(Buffer.class);
        terminal = mock(Terminal.class);
        main = new KeyMap<>();
        widgets = new HashMap<>();
        when(reader.getBuffer()).thenReturn(buffer);
        when(reader.getTerminal()).thenReturn(terminal);
        when(reader.getWidgets()).thenReturn(widgets);
        when(reader.getKeyMaps()).thenReturn(Map.of(LineReader.MAIN, main));
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
}
