package com.maplecode.skill;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册表，管理所有可用和已激活的 Skill。
 */
public class SkillRegistry {

    private final Map<String, SkillDef> available;
    private final Map<String, SkillState> active = new ConcurrentHashMap<>();

    /**
     * 创建 SkillRegistry。
     *
     * @param available 所有可用的 Skill 定义（名称 -> 定义）
     */
    public SkillRegistry(Map<String, SkillDef> available) {
        this.available = Map.copyOf(Objects.requireNonNull(available, "available must not be null"));
    }

    /**
     * 获取所有可用的 Skill 定义。
     */
    public Collection<SkillDef> available() {
        return Collections.unmodifiableCollection(available.values());
    }

    /**
     * 按名称查找可用的 Skill 定义。
     */
    public SkillDef find(String name) {
        return available.get(name);
    }

    /**
     * 检查 Skill 是否已激活。
     */
    public boolean isActive(String name) {
        return active.containsKey(name);
    }

    /**
     * 激活 Skill。
     *
     * @param def          Skill 定义
     * @param renderedBody 渲染后的完整指令
     * @return 新的激活状态
     */
    public SkillState activate(SkillDef def, String renderedBody) {
        Objects.requireNonNull(def, "def must not be null");
        Objects.requireNonNull(renderedBody, "renderedBody must not be null");

        SkillState state = SkillState.activate(def, renderedBody);
        active.put(def.name(), state);
        return state;
    }

    /**
     * 停用 Skill。
     *
     * @param name Skill 名称
     * @return 被移除的激活状态，如果不存在返回 null
     */
    public SkillState deactivate(String name) {
        return active.remove(name);
    }

    /**
     * 停用所有 Skill。
     */
    public void deactivateAll() {
        active.clear();
    }

    /**
     * 获取所有已激活的 Skill 状态。
     */
    public Collection<SkillState> active() {
        return Collections.unmodifiableCollection(active.values());
    }

    /**
     * 获取已激活 Skill 的数量。
     */
    public int activeCount() {
        return active.size();
    }

    /**
     * 获取所有已激活 Skill 的工具白名单并集。
     *
     * @return 工具名集合，如果没有任何 Skill 激活或所有激活的 Skill 都没有指定白名单则返回空集合
     */
    public java.util.Set<String> activeToolWhitelist() {
        java.util.Set<String> whitelist = new java.util.HashSet<>();
        for (SkillState state : active.values()) {
            if (state.def().tools() != null) {
                whitelist.addAll(state.def().tools());
            }
        }
        return Collections.unmodifiableSet(whitelist);
    }
}
