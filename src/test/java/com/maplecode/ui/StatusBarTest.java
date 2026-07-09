package com.maplecode.ui;

import com.maplecode.provider.TokenUsage;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StatusBarTest {

    // --- render tests ---

    @Test
    void renderIncludesModelAndMode() {
        var state = new StatusBar.StatusState("claude-4-sonnet", null, "normal", "/home/user/project");
        AttributedString result = StatusBar.render(state);
        String plain = result.toString();
        assertTrue(plain.contains("claude-4-sonnet"), "should contain model name");
        assertTrue(plain.contains("normal"), "should contain mode");
        assertTrue(plain.contains("/home/user/project"), "should contain working dir");
        assertTrue(plain.contains("tok:-/-"), "null usage should show tok:-/-");
    }

    @Test
    void renderWithUsage_showsAbbreviatedTokens() {
        var usage = new TokenUsage(1234, 5678, 0, 0);
        var state = new StatusBar.StatusState("model", usage, "plan", "~/proj");
        String plain = StatusBar.render(state).toString();
        assertTrue(plain.contains("tok:1.2k/5.7k"), "should contain abbreviated tokens: " + plain);
    }

    // --- formatUsage tests ---

    @Test
    void formatUsage_nullReturnsDash() {
        assertEquals("tok:-/-", StatusBar.formatUsage(null));
    }

    @Test
    void formatUsage_zeroReturnsZero() {
        var usage = new TokenUsage(0, 0, 0, 0);
        assertEquals("tok:0/0", StatusBar.formatUsage(usage));
    }

    @Test
    void formatUsage_largeValuesAbbreviated() {
        var usage = new TokenUsage(1234, 5678, 0, 0);
        String result = StatusBar.formatUsage(usage);
        assertTrue(result.contains("1.2k"), "should contain abbreviated input: " + result);
        assertTrue(result.contains("5.7k"), "should contain abbreviated output: " + result);
    }

    // --- abbreviate tests ---

    @Test
    void abbreviate_smallValuesUnchanged() {
        assertEquals("999", StatusBar.abbreviate(999));
        assertEquals("0", StatusBar.abbreviate(0));
        assertEquals("1.0k", StatusBar.abbreviate(1000));
        assertEquals("12k", StatusBar.abbreviate(12000));
    }

    @Test
    void abbreviate_boundaryAt10000() {
        assertEquals("10.0k", StatusBar.abbreviate(9999));
        assertEquals("10k", StatusBar.abbreviate(10000));
    }

    // --- coloredMode tests ---

    @Test
    void coloredMode_planIsYellow() {
        AttributedString result = StatusBar.coloredMode("plan");
        String ansi = result.toAnsi();
        // Should contain ANSI yellow escape code and mode text
        assertTrue(ansi.contains("plan"), "should contain mode text: " + ansi);
        assertNotEquals("plan", ansi, "should have ANSI color codes");
    }

    @Test
    void coloredMode_strictIsRed() {
        AttributedString result = StatusBar.coloredMode("strict");
        String ansi = result.toAnsi();
        assertTrue(ansi.contains("strict"), "should contain mode text: " + ansi);
        assertNotEquals("strict", ansi, "should have ANSI color codes");
    }

    @Test
    void coloredMode_normalIsDefault() {
        AttributedString result = StatusBar.coloredMode("normal");
        // normal mode uses default style, so toAnsi should be just the text
        assertEquals("normal", result.toString());
    }

    @Test
    void coloredMode_compoundPlanStrictIsRed() {
        AttributedString result = StatusBar.coloredMode("plan:strict");
        String ansi = result.toAnsi();
        assertTrue(ansi.contains("plan:strict"), "should contain full mode text: " + ansi);
        assertNotEquals("plan:strict", ansi, "should have ANSI color codes (red for strict)");
    }

    @Test
    void coloredMode_permissiveIsGreen() {
        AttributedString result = StatusBar.coloredMode("permissive");
        String ansi = result.toAnsi();
        assertTrue(ansi.contains("permissive"), "should contain mode text: " + ansi);
        assertNotEquals("permissive", ansi, "should have ANSI color codes");
    }

    // --- unsupported terminal tests ---

    @Test
    void unsupportedTerminal_allOperationsAreNoOp() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getStringCapability(any())).thenReturn(null);
        when(terminal.getSize()).thenReturn(new Size(80, 24));

        StatusBar statusBar = new StatusBar(terminal);

        assertFalse(statusBar.isSupported());

        // update and resize should not throw
        var state = new StatusBar.StatusState("model", null, "normal", "/tmp");
        assertDoesNotThrow(() -> statusBar.update(state));
        assertDoesNotThrow(statusBar::resize);
    }
}
