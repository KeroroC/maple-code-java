package com.maplecode.skill;

import com.maplecode.prompt.PromptSection;
import com.maplecode.prompt.SectionContext;

import java.util.Collection;

/**
 * 已激活 Skills 的提示词段。
 *
 * <p>将所有已激活的 Skill 指令注入到系统提示词中。
 * 这是动态内容，不可缓存。
 */
public class ActivatedSkillsSection implements PromptSection {

    private final SkillRegistry registry;

    public ActivatedSkillsSection(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String kind() {
        return "activated_skills";
    }

    @Override
    public String render(SectionContext ctx) {
        Collection<SkillState> active = registry.active();
        if (active.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 已激活的 Skills\n\n");
        sb.append("以下是当前激活的 Skills 的完整指令。请严格按照这些指令执行任务。\n\n");

        for (SkillState state : active) {
            SkillDef def = state.def();
            sb.append("## Skill: ").append(def.name()).append("\n");

            // 工具白名单信息
            if (def.tools() != null && !def.tools().isEmpty()) {
                sb.append("**可用工具**: ").append(String.join(", ", def.tools())).append("\n");
                sb.append("**重要**: 只能使用上述列出的工具，不要尝试使用其他工具。\n");
            }

            // 执行模式信息
            if (def.mode() == ExecutionMode.INDEPENDENT) {
                sb.append("**执行模式**: 独立执行\n");
                if (def.historyDepth() > 0) {
                    sb.append("**历史深度**: ").append(def.historyDepth()).append(" 条消息\n");
                }
                if (def.model() != null) {
                    sb.append("**指定模型**: ").append(def.model()).append("\n");
                }
            }

            sb.append("\n### 指令\n\n");
            sb.append(state.renderedBody()).append("\n\n");
            sb.append("---\n\n");
        }

        return sb.toString();
    }

    @Override
    public boolean enabled(SectionContext ctx) {
        return registry.activeCount() > 0;
    }

    @Override
    public boolean cacheable() {
        return false;  // 动态内容，不可缓存
    }
}
