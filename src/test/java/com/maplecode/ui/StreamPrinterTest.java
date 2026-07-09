package com.maplecode.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.*;

class StreamPrinterTest {

    private StreamPrinter createPrinter(ByteArrayOutputStream baos) {
        return new StreamPrinter(new PrintWriter(baos, true));
    }

    @Test
    void writeOutputsToPrintWriter() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.write("hello ");
        printer.write("world");
        assertEquals("hello world", baos.toString());
    }

    @Test
    void infoPrintsLine() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.info("test message");
        assertTrue(baos.toString().contains("test message"));
    }

    @Test
    void errorContainsCrossMark() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.error("something failed");
        String output = baos.toString();
        assertTrue(output.contains("✗"), "should contain cross mark: " + output);
        assertTrue(output.contains("something failed"), "should contain error message: " + output);
    }

    @Test
    void toolStartContainsGearSymbol() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.toolStart("read_file", "/tmp/x");
        String output = baos.toString();
        assertTrue(output.contains("⚙"), "should contain gear symbol: " + output);
        assertTrue(output.contains("read_file"), "should contain tool name: " + output);
    }

    @Test
    void toolEndSuccessContainsCheckmark() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.toolEnd("read_file", true, null);
        String output = baos.toString();
        assertTrue(output.contains("✓"), "should contain checkmark: " + output);
    }

    @Test
    void toolEndFailureContainsCrossMark() {
        var baos = new ByteArrayOutputStream();
        var printer = createPrinter(baos);
        printer.toolEnd("read_file", false, "not found");
        String output = baos.toString();
        assertTrue(output.contains("✗"), "should contain cross mark: " + output);
        assertTrue(output.contains("not found"), "should contain error detail: " + output);
    }
}
