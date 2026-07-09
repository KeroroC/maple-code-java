package com.maplecode.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IoUtilTest {

    @Test
    void atomicWriteCreatesFile(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out.txt");
        IoUtil.atomicWrite(target, "hello world");

        assertEquals("hello world", Files.readString(target));
    }

    @Test
    void atomicWriteOverwritesExisting(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out.txt");
        Files.writeString(target, "old content");

        IoUtil.atomicWrite(target, "new content");

        assertEquals("new content", Files.readString(target));
    }

    @Test
    void atomicWriteCreatesParentDirectories(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("a/b/c/out.txt");
        IoUtil.atomicWrite(target, "nested");

        assertEquals("nested", Files.readString(target));
        assertTrue(Files.isDirectory(tmp.resolve("a/b/c")));
    }

    @Test
    void atomicWriteLeavesNoTempFilesOnSuccess(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out.txt");
        IoUtil.atomicWrite(target, "content");

        long tempFiles = Files.list(tmp)
            .filter(p -> p.toString().endsWith(".tmp"))
            .count();
        assertEquals(0, tempFiles, "no .tmp files should remain after success");
    }

    @Test
    void atomicWritePreservesOldContentOnWriteFailure(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out.txt");
        Files.writeString(target, "precious data");

        // 写入到一个不存在的父目录的父目录（不可写的路径）
        // 用 symlink 指向一个文件来强制失败
        Path notADir = tmp.resolve("notADir");
        Files.writeString(notADir, "I am a file");
        Path badTarget = notADir.resolve("sub/out.txt");

        assertThrows(IOException.class, () -> IoUtil.atomicWrite(badTarget, "fail"));

        // 原文件不应被破坏
        assertEquals("precious data", Files.readString(target));
    }
}
