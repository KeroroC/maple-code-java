package com.maplecode.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentsMdLoaderTest {

    @TempDir
    Path projectRoot;

    @Test
    void allThreeLayersPresentPriorityAndOrder() throws IOException {
        // 项目根
        Files.writeString(projectRoot.resolve("AGENTS.md"), "ROOT");
        // 项目 .maplecode
        Files.createDirectories(projectRoot.resolve(".maplecode"));
        Files.writeString(projectRoot.resolve(".maplecode/AGENTS.md"), "PROJECT");
        // 用户全局
        Path userHome = Files.createTempDirectory("user-home-");
        Files.createDirectories(userHome.resolve(".maplecode"));
        Files.writeString(userHome.resolve(".maplecode/AGENTS.md"), "USER");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        // 项目根在前、用户全局在后
        assertTrue(result.startsWith("ROOT"), "项目根应最前，实际：" + result);
        assertTrue(result.contains("ROOT\n\n---\n\nPROJECT"));
        assertTrue(result.endsWith("USER"), "用户全局应最后，实际：" + result);
    }

    @Test
    void allLayersMissingReturnsEmpty() throws IOException {
        Path userHome = Files.createTempDirectory("user-home-");
        String result = AgentsMdLoader.load(projectRoot, userHome);
        assertEquals("", result);
    }

    @Test
    void projectRootExistsOthersMissing() throws IOException {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "ONLY");
        Path userHome = Files.createTempDirectory("user-home-");
        String result = AgentsMdLoader.load(projectRoot, userHome);
        assertEquals("ONLY", result);
    }

    @Test
    void includeEndToEnd() throws IOException {
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projectRoot.resolve("docs/style.md"), "[style]");
        Files.writeString(projectRoot.resolve("AGENTS.md"),
            "X {{include: docs/style.md}} Y");
        Path userHome = Files.createTempDirectory("user-home-");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        assertEquals("X [style] Y", result);
    }

    @Test
    void ioFailureOnOneLayerSkipsThatLayer() throws IOException {
        // 在 projectRoot 放一个普通目录、试图作为 AGENTS.md
        Files.createDirectories(projectRoot.resolve("AGENTS.md"));
        Files.createDirectories(projectRoot.resolve(".maplecode"));
        Files.writeString(projectRoot.resolve(".maplecode/AGENTS.md"), "PROJECT");
        Path userHome = Files.createTempDirectory("user-home-");

        String result = AgentsMdLoader.load(projectRoot, userHome);

        // 项目根是目录、应该被跳过；项目 .maplecode 还在
        assertFalse(result.contains("AGENTS.md"),
            "目录不应被读为内容");
        assertEquals("PROJECT", result);
    }
}
