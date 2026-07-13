package com.maplecode.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Skill 定义，从 .md 文件解析而来。
 *
 * @param name          唯一标识符，小写字母+连字符
 * @param description   一句话说明，注入到启动时的 Skill 列表
 * @param tools         工具白名单，为空则不限制
 * @param mode          执行模式，默认 SHARED
 * @param historyDepth  independent 模式下带多少历史消息，默认 0
 * @param model         可选指定模型，为空则用主模型
 * @param body          Markdown 正文（含 {{input}} 占位符）
 * @param sourcePath    来源文件路径，用于错误报告
 */
public record SkillDef(
    String name,
    String description,
    List<String> tools,
    ExecutionMode mode,
    int historyDepth,
    String model,
    String body,
    Path sourcePath
) {
    public SkillDef {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                "name must match [a-z][a-z0-9-]*: " + name);
        }

        // 默认值处理
        if (tools == null) tools = List.of();
        if (mode == null) mode = ExecutionMode.SHARED;
        if (historyDepth < 0) historyDepth = 0;
    }

    /**
     * 创建共享模式的 Skill 定义。
     */
    public static SkillDef shared(String name, String description,
                                  List<String> tools, String body, Path sourcePath) {
        return new SkillDef(name, description, tools, ExecutionMode.SHARED,
                           0, null, body, sourcePath);
    }

    /**
     * 创建独立模式的 Skill 定义。
     */
    public static SkillDef independent(String name, String description,
                                       List<String> tools, int historyDepth,
                                       String model, String body, Path sourcePath) {
        return new SkillDef(name, description, tools, ExecutionMode.INDEPENDENT,
                           historyDepth, model, body, sourcePath);
    }
}
