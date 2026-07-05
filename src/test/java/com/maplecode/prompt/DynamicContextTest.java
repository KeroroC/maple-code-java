package com.maplecode.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DynamicContextTest {

    @Test
    void captureDetectsNonGitDir(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("not-a-repo");
        Files.createDirectory(cwd);
        var ctx = DynamicContext.capture(cwd);
        assertEquals(cwd, ctx.cwd());
        assertFalse(ctx.isGitRepo());
        assertNotNull(ctx.platform());
        assertNotNull(ctx.javaVersion());
        assertNotNull(ctx.mavenVersion());
        assertNotNull(ctx.date());
        assertNotNull(ctx.time());
    }

    @Test
    void captureDetectsGitRepo(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("repo");
        Files.createDirectory(cwd);
        Files.createDirectory(cwd.resolve(".git"));
        var ctx = DynamicContext.capture(cwd);
        assertTrue(ctx.isGitRepo());
    }
}
