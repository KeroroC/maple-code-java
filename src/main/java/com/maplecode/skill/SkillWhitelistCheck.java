package com.maplecode.skill;

import com.maplecode.permission.Decision;
import com.maplecode.permission.PermissionCheck;
import com.maplecode.permission.PermissionContext;
import com.maplecode.permission.PermissionRequest;

import java.util.Optional;
import java.util.Set;

/**
 * Skill 工具白名单检查。
 *
 * <p>当有 Skill 激活且指定了工具白名单时，只允许使用白名单中的工具。
 * 系统级工具（如 load_skill）不受此限制。
 */
public class SkillWhitelistCheck implements PermissionCheck {

    /**
     * 系统级工具，不受白名单约束。
     */
    private static final Set<String> SYSTEM_TOOLS = Set.of(
        LoadSkillTool.NAME  // "load_skill"
    );

    private final SkillRegistry registry;

    public SkillWhitelistCheck(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        String toolName = req.toolName();

        // 系统级工具不受白名单约束
        if (SYSTEM_TOOLS.contains(toolName)) {
            return Optional.empty();
        }

        // 获取当前激活的 Skill 的工具白名单并集
        Set<String> whitelist = registry.activeToolWhitelist();

        // 如果没有激活的 Skill，或者所有激活的 Skill 都没有指定白名单，不干预
        if (whitelist.isEmpty()) {
            return Optional.empty();
        }

        // 检查工具是否在白名单中
        if (whitelist.contains(toolName)) {
            return Optional.empty();  // 允许，继续下一个 check
        } else {
            return Optional.of(Decision.deny(
                "Skill 工具白名单限制: 当前激活的 Skills 只允许使用 " + whitelist));
        }
    }
}
