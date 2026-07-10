package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayerReaderTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileReturnsEmptyWithoutWarn() {
        Layer layer = LayerReader.read(Layer.empty(tmp.resolve("nope.md")));
        assertFalse(layer.exists());
        assertEquals("", layer.content());
    }

    @Test
    void directoryReturnsEmptyWithWarn() {
        Layer layer = LayerReader.read(Layer.empty(tmp));
        assertFalse(layer.exists(), "directory should not be readable as a file");
    }

    @Test
    void oversizedFileReturnsEmptyWithWarn() throws IOException {
        Path big = tmp.resolve("big.md");
        // 写 1MB+1 byte 触发超 1MB 上限
        byte[] data = new byte[1_048_577];
        Files.write(big, data);
        Layer layer = LayerReader.read(Layer.empty(big));
        assertFalse(layer.exists());
    }

    @Test
    void regularFileIsReadWithContent() throws IOException {
        Path file = tmp.resolve("AGENTS.md");
        Files.writeString(file, "# rules\n- use Java 21");
        Layer layer = LayerReader.read(Layer.empty(file));
        assertTrue(layer.exists());
        assertEquals("# rules\n- use Java 21", layer.content());
    }

    @Test
    void symlinkIsRejected() throws IOException {
        // 创建真实文件和指向它的符号链接
        Path real = tmp.resolve("real.md");
        Files.writeString(real, "secret content");
        Path symlink = tmp.resolve("AGENTS.md");
        Files.createSymbolicLink(symlink, real);

        Layer layer = LayerReader.read(Layer.empty(symlink));
        assertFalse(layer.exists(), "符号链接应被拒绝");
        assertEquals("", layer.content(), "符号链接不应读取内容");
    }
}
