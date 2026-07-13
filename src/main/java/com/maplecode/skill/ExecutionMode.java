package com.maplecode.skill;

/**
 * Skill 执行模式。
 */
public enum ExecutionMode {

    /**
     * 共享模式：Skill 在当前对话中执行，结果留在主历史里。
     */
    SHARED,

    /**
     * 独立模式：开独立 AgentLoop 实例执行，跑完摘要回流到主对话。
     */
    INDEPENDENT
}
