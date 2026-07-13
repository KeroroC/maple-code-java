package com.maplecode.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 加载器，负责从三级目录扫描并解析 Skill 文件。
 *
 * <p>优先级：项目目录 > 用户目录 > 内置资源。同名按优先级覆盖。
 * 解析失败的单个文件打印 stderr 警告，跳过不阻断整体。
 */
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN =
        Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    private static final Pattern FIELD_PATTERN =
        Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*:\\s*(.+)$");

    /**
     * 加载所有 Skill，按优先级合并。
     *
     * @param projectDir 项目根目录
     * @param userHome   用户主目录
     * @return 名称到 SkillDef 的映射
     */
    public Map<String, SkillDef> loadAll(Path projectDir, Path userHome) {
        Map<String, SkillDef> result = new HashMap<>();

        // 1. 内置资源（最低优先级）
        loadFromResources(result);

        // 2. 用户目录
        Path userSkillsDir = userHome.resolve(".maplecode").resolve("skills");
        loadFromDirectory(result, userSkillsDir);

        // 3. 项目目录（最高优先级）
        Path projectSkillsDir = projectDir.resolve(".maplecode").resolve("skills");
        loadFromDirectory(result, projectSkillsDir);

        return result;
    }

    /**
     * 从 classpath 加载内置 Skill。
     */
    private void loadFromResources(Map<String, SkillDef> result) {
        String[] builtinSkills = {"commit.md", "review.md", "test.md"};
        for (String name : builtinSkills) {
            try (var is = getClass().getResourceAsStream("/skills/" + name)) {
                if (is != null) {
                    String content = new String(is.readAllBytes());
                    SkillDef def = parseContent(content, Path.of("classpath:/skills/" + name));
                    if (def != null) {
                        result.put(def.name(), def);
                    }
                }
            } catch (Exception e) {
                System.err.println("[skill] WARN: failed to load builtin skill " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * 从目录加载 Skill。
     */
    private void loadFromDirectory(Map<String, SkillDef> result, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                try {
                    SkillDef def = null;
                    if (Files.isRegularFile(entry) && entry.toString().endsWith(".md")) {
                        def = parseFile(entry);
                    } else if (Files.isDirectory(entry)) {
                        def = parseDirectory(entry);
                    }

                    if (def != null) {
                        result.put(def.name(), def);
                    }
                } catch (Exception e) {
                    System.err.println("[skill] WARN: failed to load " + entry + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // 目录不存在或无法读取，静默跳过
        }
    }

    /**
     * 解析单个 .md 文件。
     */
    public SkillDef parseFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a regular file: " + file);
        }
        String content = Files.readString(file);
        return parseContent(content, file);
    }

    /**
     * 解析目录型 Skill（找入口 .md）。
     *
     * <p>入口文件优先级：
     * 1. skill.md
     * 2. 与目录同名的 .md 文件
     */
    public SkillDef parseDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        // 尝试 skill.md
        Path skillMd = dir.resolve("skill.md");
        if (Files.isRegularFile(skillMd)) {
            return parseFile(skillMd);
        }

        // 尝试与目录同名的 .md
        String dirName = dir.getFileName().toString();
        Path namedMd = dir.resolve(dirName + ".md");
        if (Files.isRegularFile(namedMd)) {
            return parseFile(namedMd);
        }

        throw new IOException("No entry .md found in directory: " + dir);
    }

    /**
     * 解析文件内容为 SkillDef。
     */
    SkillDef parseContent(String content, Path sourcePath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            System.err.println("[skill] WARN: no frontmatter found in " + sourcePath);
            return null;
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2).trim();

        try {
            return parseFrontmatter(frontmatter, body, sourcePath);
        } catch (Exception e) {
            System.err.println("[skill] WARN: invalid frontmatter in " + sourcePath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析 YAML frontmatter。
     *
     * <p>简单实现，不依赖 YAML 库，只支持基本字段。
     */
    private SkillDef parseFrontmatter(String frontmatter, String body, Path sourcePath) {
        String name = null;
        String description = null;
        List<String> tools = new ArrayList<>();
        ExecutionMode mode = ExecutionMode.SHARED;
        int historyDepth = 0;
        String model = null;

        String[] lines = frontmatter.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            Matcher fieldMatcher = FIELD_PATTERN.matcher(line);
            if (!fieldMatcher.matches()) {
                continue;
            }

            String key = fieldMatcher.group(1).toLowerCase();
            String value = fieldMatcher.group(2).trim();

            // 去除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }

            switch (key) {
                case "name":
                    name = value;
                    break;
                case "description":
                    description = value;
                    break;
                case "tools":
                    tools = parseToolsList(value);
                    break;
                case "mode":
                    mode = ExecutionMode.valueOf(value.toUpperCase());
                    break;
                case "history_depth":
                case "historydepth":
                    historyDepth = Integer.parseInt(value);
                    break;
                case "model":
                    model = value;
                    break;
                default:
                    // 忽略未知字段
                    break;
            }
        }

        if (name == null || name.isBlank()) {
            // 如果没有指定 name，从文件名推断
            String fileName = sourcePath.getFileName().toString();
            name = fileName.replace(".md", "").toLowerCase();
            name = name.replaceAll("[^a-z0-9-]", "-");
        }

        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }

        if (body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }

        return new SkillDef(name, description, tools, mode, historyDepth, model, body, sourcePath);
    }

    /**
     * 解析工具列表字符串。
     *
     * <p>支持格式：
     * - YAML 列表：[exec, read_file, grep]
     * - 逗号分隔：exec, read_file, grep
     */
    private List<String> parseToolsList(String value) {
        List<String> tools = new ArrayList<>();

        // 去除方括号
        value = value.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }

        // 按逗号分割
        String[] parts = value.split(",");
        for (String part : parts) {
            String tool = part.trim();
            if (!tool.isEmpty()) {
                tools.add(tool);
            }
        }

        return tools;
    }

    /**
     * 验证工具白名单中的工具是否存在。
     *
     * @param skill           Skill 定义
     * @param availableTools  可用工具名集合
     * @throws IllegalArgumentException 如果白名单中有不存在的工具
     */
    public void validateTools(SkillDef skill, java.util.Set<String> availableTools) {
        if (skill.tools() == null || skill.tools().isEmpty()) {
            return;
        }

        for (String tool : skill.tools()) {
            if (!availableTools.contains(tool)) {
                throw new IllegalArgumentException(
                    "Skill '" + skill.name() + "' references unknown tool: " + tool);
            }
        }
    }
}
