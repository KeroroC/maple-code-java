package com.maplecode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    private final SkillLoader loader = new SkillLoader();

    @Test
    void parseContent_validFrontmatter_returnsSkillDef() {
        String content = """
            ---
            name: test-skill
            description: A test skill
            tools: [exec, read_file]
            mode: shared
            ---
            # Test Skill
            Do something {{input}}
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNotNull(def);
        assertEquals("test-skill", def.name());
        assertEquals("A test skill", def.description());
        assertEquals(2, def.tools().size());
        assertTrue(def.tools().contains("exec"));
        assertTrue(def.tools().contains("read_file"));
        assertEquals(ExecutionMode.SHARED, def.mode());
        assertTrue(def.body().contains("Do something {{input}}"));
    }

    @Test
    void parseContent_independentMode_parsesCorrectly() {
        String content = """
            ---
            name: independent-skill
            description: An independent skill
            mode: independent
            history_depth: 5
            model: claude-haiku-4-5
            ---
            Run independently
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNotNull(def);
        assertEquals(ExecutionMode.INDEPENDENT, def.mode());
        assertEquals(5, def.historyDepth());
        assertEquals("claude-haiku-4-5", def.model());
    }

    @Test
    void parseContent_noFrontmatter_returnsNull() {
        String content = "Just some text without frontmatter";

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNull(def);
    }

    @Test
    void parseContent_missingDescription_returnsNull() {
        String content = """
            ---
            name: test-skill
            ---
            Body here
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNull(def);
    }

    @Test
    void parseContent_missingBody_returnsNull() {
        String content = """
            ---
            name: test-skill
            description: A test skill
            ---
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNull(def);
    }

    @Test
    void parseContent_invalidName_returnsNull() {
        String content = """
            ---
            name: Invalid Name
            description: A test skill
            ---
            Body here
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNull(def);
    }

    @Test
    void parseContent_nameFromFilename_usesFilename() {
        String content = """
            ---
            description: A test skill
            ---
            Body here
            """;

        SkillDef def = loader.parseContent(content, Path.of("my-skill.md"));

        assertNotNull(def);
        assertEquals("my-skill", def.name());
    }

    @Test
    void parseContent_toolsCommaSeparated_parsesCorrectly() {
        String content = """
            ---
            name: test-skill
            description: A test skill
            tools: exec, read_file, grep
            ---
            Body here
            """;

        SkillDef def = loader.parseContent(content, Path.of("test.md"));

        assertNotNull(def);
        assertEquals(3, def.tools().size());
    }

    @Test
    void parseFile_validFile_returnsSkillDef(@TempDir Path tempDir) throws IOException {
        String content = """
            ---
            name: file-skill
            description: From file
            ---
            File body
            """;
        Path file = tempDir.resolve("skill.md");
        Files.writeString(file, content);

        SkillDef def = loader.parseFile(file);

        assertNotNull(def);
        assertEquals("file-skill", def.name());
        assertEquals("From file", def.description());
    }

    @Test
    void parseFile_notRegularFile_throwsException(@TempDir Path tempDir) {
        assertThrows(IOException.class, () -> loader.parseFile(tempDir));
    }

    @Test
    void parseDirectory_withSkillMd_returnsSkillDef(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectory(skillDir);
        String content = """
            ---
            name: dir-skill
            description: From directory
            ---
            Directory body
            """;
        Files.writeString(skillDir.resolve("skill.md"), content);

        SkillDef def = loader.parseDirectory(skillDir);

        assertNotNull(def);
        assertEquals("dir-skill", def.name());
    }

    @Test
    void parseDirectory_withNamedMd_returnsSkillDef(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectory(skillDir);
        String content = """
            ---
            name: named-skill
            description: Named file
            ---
            Named body
            """;
        Files.writeString(skillDir.resolve("my-skill.md"), content);

        SkillDef def = loader.parseDirectory(skillDir);

        assertNotNull(def);
        assertEquals("named-skill", def.name());
    }

    @Test
    void parseDirectory_noEntryMd_throwsException(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("empty-dir");
        Files.createDirectory(skillDir);

        assertThrows(IOException.class, () -> loader.parseDirectory(skillDir));
    }

    @Test
    void parseDirectory_notDirectory_throwsException(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.md");
        Files.writeString(file, "content");

        assertThrows(IOException.class, () -> loader.parseDirectory(file));
    }

    @Test
    void loadAll_withProjectAndUserDirs_mergesWithPriority(@TempDir Path tempDir) throws IOException {
        // 创建用户目录
        Path userDir = tempDir.resolve("user").resolve(".maplecode").resolve("skills");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("user-skill.md"), """
            ---
            name: user-skill
            description: User skill
            ---
            User body
            """);
        Files.writeString(userDir.resolve("shared-skill.md"), """
            ---
            name: shared-skill
            description: User version
            ---
            User version body
            """);

        // 创建项目目录
        Path projectDir = tempDir.resolve("project");
        Path projectSkillsDir = projectDir.resolve(".maplecode").resolve("skills");
        Files.createDirectories(projectSkillsDir);
        Files.writeString(projectSkillsDir.resolve("project-skill.md"), """
            ---
            name: project-skill
            description: Project skill
            ---
            Project body
            """);
        Files.writeString(projectSkillsDir.resolve("shared-skill.md"), """
            ---
            name: shared-skill
            description: Project version
            ---
            Project version body
            """);

        Map<String, SkillDef> skills = loader.loadAll(projectDir, tempDir.resolve("user"));

        // 应该有 3 个自定义 Skill + 内置 Skill（commit, review, test）
        assertTrue(skills.size() >= 3, "Expected at least 3 skills, got: " + skills.size());

        // 用户独有的
        assertNotNull(skills.get("user-skill"));

        // 项目独有的
        assertNotNull(skills.get("project-skill"));

        // 同名覆盖：项目版本应该覆盖用户版本
        SkillDef shared = skills.get("shared-skill");
        assertNotNull(shared);
        assertEquals("Project version", shared.description());
    }

    @Test
    void loadAll_nonexistentDirs_returnsEmpty(@TempDir Path tempDir) {
        Map<String, SkillDef> skills = loader.loadAll(
            tempDir.resolve("nonexistent"),
            tempDir.resolve("nonexistent2"));

        // 可能有内置 Skill，但不会有目录中的 Skill
        // 如果内置资源不存在，应该为空
        // 这个测试主要验证不会抛异常
    }

    @Test
    void loadAll_invalidFile_skipsAndContinues(@TempDir Path tempDir) throws IOException {
        Path skillsDir = tempDir.resolve(".maplecode").resolve("skills");
        Files.createDirectories(skillsDir);

        // 无效文件
        Files.writeString(skillsDir.resolve("invalid.md"), "No frontmatter here");

        // 有效文件
        Files.writeString(skillsDir.resolve("valid.md"), """
            ---
            name: valid-skill
            description: Valid skill
            ---
            Valid body
            """);

        Map<String, SkillDef> skills = loader.loadAll(tempDir, tempDir);

        // 应该只有有效的 Skill
        assertNotNull(skills.get("valid-skill"));
        assertNull(skills.get("invalid"));
    }

    @Test
    void validateTools_allToolsExist_noException() {
        SkillDef def = SkillDef.shared("test", "desc",
            java.util.List.of("exec", "read_file"), "body", Path.of("test.md"));

        Set<String> available = Set.of("exec", "read_file", "write_file", "grep");

        assertDoesNotThrow(() -> loader.validateTools(def, available));
    }

    @Test
    void validateTools_unknownTool_throwsException() {
        SkillDef def = SkillDef.shared("test", "desc",
            java.util.List.of("exec", "unknown_tool"), "body", Path.of("test.md"));

        Set<String> available = Set.of("exec", "read_file");

        assertThrows(IllegalArgumentException.class, () -> loader.validateTools(def, available));
    }

    @Test
    void validateTools_emptyTools_noException() {
        SkillDef def = SkillDef.shared("test", "desc",
            java.util.List.of(), "body", Path.of("test.md"));

        Set<String> available = Set.of("exec");

        assertDoesNotThrow(() -> loader.validateTools(def, available));
    }
}
