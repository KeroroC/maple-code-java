package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConcatenatorTest {

    @Test
    void singleLayerNoSeparator() {
        String result = Concatenator.join(List.of("hello"));
        assertEquals("hello", result);
    }

    @Test
    void multipleLayersJoinedWithSeparator() {
        String result = Concatenator.join(List.of("A", "B", "C"));
        assertEquals("A\n\n---\n\nB\n\n---\n\nC", result);
    }

    @Test
    void emptyAndBlankLayersFiltered() {
        String result = Concatenator.join(List.of("", "  ", "real", "\n\t\n", "more"));
        assertEquals("real\n\n---\n\nmore", result);
    }

    @Test
    void totalSizeUnderLimitNotTruncated() {
        // 64KB - 1 字节
        String padding = "a".repeat(65_535);
        String result = Concatenator.join(List.of(padding));
        assertEquals(65_535, result.getBytes(StandardCharsets.UTF_8).length);
        assertFalse(result.contains("[truncated:"));
    }

    @Test
    void totalSizeOverLimitTruncated() {
        // 70KB 超过 64KB 上限
        String padding = "a".repeat(70_000);
        String result = Concatenator.join(List.of(padding));
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        // 截到 64KB + 截断尾标（[truncated: AGENTS.md total > 64KB] = 35 字节）
        assertTrue(bytes.length <= 65_536 + 50,
            "截断后字节数应 ≤ 上限 + 尾标：实际 " + bytes.length);
        assertTrue(result.contains("[truncated: AGENTS.md total > 64KB]"),
            "应包含截断尾标");
    }
}
