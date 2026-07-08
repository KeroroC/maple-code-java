package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class IncludeResolverTest {

    @TempDir
    Path tmp;

    @Test
    void simpleIncludeIsExpanded() throws IOException {
        Path sub = tmp.resolve("sub.md");
        Files.writeString(sub, "subcontent");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "before {{include: sub.md}} after");

        String result = IncludeResolver.resolve(
            Files.readString(main),
            tmp,
            new HashSet<>(),
            0,
            IncludeLimits.defaults());

        assertEquals("before subcontent after", result);
    }

    @Test
    void nestedIncludeIsRecursivelyExpanded() throws IOException {
        Path tip = tmp.resolve("shared/tip.md");
        Files.createDirectories(tmp.resolve("shared"));
        Files.writeString(tip, "[tip]");
        Path style = tmp.resolve("docs/style.md");
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(style, "style-{{include: ../shared/tip.md}}-end");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: docs/style.md}} Y");

        String result = IncludeResolver.resolve(
            Files.readString(main),
            tmp,
            new HashSet<>(),
            0,
            IncludeLimits.defaults());

        assertEquals("X style-[tip]-end Y", result);
    }
}
