package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IoInterfaceSmokeTest {

    static class TestInput implements InputSource {
        final java.util.Queue<String> q;
        TestInput(java.util.Queue<String> q) { this.q = q; }
        @Override public String readLine(String prompt) {
            String s = q.poll();
            if (s == null) throw new java.util.NoSuchElementException("no more input");
            return s;
        }
    }

    static class TestOutput implements OutputSink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(String line) { lines.add(line); }
    }

    @Test
    void interfaces_are_implementable() {
        TestOutput out = new TestOutput();
        out.println("hello");
        assertEquals(List.of("hello"), out.lines);

        TestInput in = new TestInput(new java.util.ArrayDeque<>(List.of("answer")));
        assertEquals("answer", in.readLine("> "));
    }
}
