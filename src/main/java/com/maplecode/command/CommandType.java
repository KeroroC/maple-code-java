package com.maplecode.command;

/**
 * 命令分类。纯元数据，分发器不按它路由，仅用于 /help 分组显示。
 */
public enum CommandType {
    /** 纯本地操作，不涉及 Agent 交互。 */
    LOCAL,
    /** 影响界面状态（session、压缩、权限模式等）。 */
    UI_STATE,
    /** 预设提示词送给 AI 执行。 */
    PROMPT
}
