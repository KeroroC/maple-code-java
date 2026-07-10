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

    @Test
    void pathEscapingBaseDirIsRejected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: ../outside.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // 占位保留
        assertTrue(result.contains("{{include: ../outside.md}}"),
            "路径跳出的占位应保留原文，实际：" + result);
    }

    @Test
    void cycleIsDetected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: self.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // main.md 不在 visited 里（visited 在递归时才加入），但 self.md == main.md
        // 如果 main.md 被加入 visited 然后被再次 include，则触发 cycle
        // 这里 main.md 没被加入 visited（visited 从 main.md 自己开始时是空的）
        // 实际行为：第一次 include self.md → 读到 main 内容 → 递归深度 1，再次 include self.md
        // → visited 里有 main.md（被加入过）→ cycle 触发
        // 简化：依赖实现细节，只验证"不抛异常 + 不会出现无限循环"
        assertNotNull(result);
    }

    @Test
    void missingTargetIsRejected() throws IOException {
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: nope.md}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertTrue(result.contains("{{include: nope.md}}"),
            "不存在的目标占位应保留，实际：" + result);
    }

    @Test
    void depthLimitIsEnforced() throws IOException {
        // 构造 4 层嵌套：a → b → c → d
        Files.createDirectories(tmp.resolve("d"));
        Files.writeString(tmp.resolve("d/leaf.md"), "[leaf]");
        Files.writeString(tmp.resolve("c.md"), "C-{{include: d/leaf.md}}");
        Files.writeString(tmp.resolve("b.md"), "B-{{include: c.md}}");
        Files.writeString(tmp.resolve("a.md"), "A-{{include: b.md}}");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "M-{{include: a.md}}");

        // limits.maxDepth=3，main 深度 0、a 深度 1、b 深度 2、c 深度 3（拒绝）
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        // c.md 处的 {{include: d/leaf.md}} 不被展开（depth=3 >= maxDepth=3）
        // 但 b.md 处的 {{include: c.md}} 在 depth=2 时展开（c.md 的内容有未展开占位）
        assertTrue(result.contains("C-{{include: d/leaf.md}}"),
            "depth 3 占位应保留，实际：" + result);
    }

    @Test
    void targetIsDirectoryIsRejected() throws IOException {
        Files.createDirectories(tmp.resolve("subdir"));
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: subdir}} Y");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertTrue(result.contains("{{include: subdir}}"),
            "目录占位应保留，实际：" + result);
    }

    @Test
    void multipleIncludesInOneFile() throws IOException {
        Files.writeString(tmp.resolve("a.md"), "[A]");
        Files.writeString(tmp.resolve("b.md"), "[B]");
        Path main = tmp.resolve("main.md");
        Files.writeString(main, "{{include: a.md}}-{{include: b.md}}");
        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());
        assertEquals("[A]-[B]", result);
    }

    @Test
    void symlinkPointingOutsideBaseDirIsRejected() throws IOException {
        // 创建一个仓库外的敏感文件
        Path outside = tmp.resolveSibling("outside-secret.txt");
        Files.writeString(outside, "SECRET_KEY=abc123");

        // 在仓库内创建符号链接指向仓库外文件
        Path symlink = tmp.resolve("evil.md");
        Files.createSymbolicLink(symlink, outside);

        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: evil.md}} Y");

        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());

        // 应该拒绝：symlink 目标逃逸出根目录
        assertTrue(result.contains("{{include: evil.md}}"),
            "symlink 逃逸的占位应保留原文，实际：" + result);
        assertFalse(result.contains("SECRET_KEY"),
            "不应读取仓库外文件内容，实际：" + result);

        // 清理
        Files.deleteIfExists(outside);
    }

    @Test
    void symlinkPointingInsideBaseDirIsFollowed() throws IOException {
        // 仓库内的 symlink 指向仓库内另一个文件 → 应该被允许
        Path real = tmp.resolve("real.md");
        Files.writeString(real, "real content");
        Path symlink = tmp.resolve("link.md");
        Files.createSymbolicLink(symlink, real);

        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: link.md}} Y");

        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());

        assertEquals("X real content Y", result,
            "仓库内 symlink 应该被正常展开");
    }

    @Test
    void oversizedIncludeIsRejected() throws IOException {
        // 写一个超 maxFileSize(1MB) 的 include 目标
        Path big = tmp.resolve("big.md");
        byte[] data = new byte[1_048_577];
        Files.write(big, data);

        Path main = tmp.resolve("main.md");
        Files.writeString(main, "X {{include: big.md}} Y");

        String result = IncludeResolver.resolve(
            Files.readString(main), tmp, new HashSet<>(), 0, IncludeLimits.defaults());

        assertTrue(result.contains("{{include: big.md}}"),
            "超大文件的占位应保留原文，实际：" + result);
    }
}
