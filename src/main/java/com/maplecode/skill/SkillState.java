package com.maplecode.skill;

import java.time.Instant;
import java.util.Objects;

/**
 * 已激活 Skill 的状态。
 *
 * @param def           Skill 定义
 * @param renderedBody  渲染后的完整指令（已替换 {{input}}）
 * @param activatedAt   激活时间
 */
public record SkillState(
    SkillDef def,
    String renderedBody,
    Instant activatedAt
) {
    public SkillState {
        Objects.requireNonNull(def, "def must not be null");
        Objects.requireNonNull(renderedBody, "renderedBody must not be null");
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
    }

    /**
     * 创建新的激活状态。
     */
    public static SkillState activate(SkillDef def, String renderedBody) {
        return new SkillState(def, renderedBody, Instant.now());
    }
}
