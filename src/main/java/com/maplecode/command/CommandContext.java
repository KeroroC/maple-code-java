package com.maplecode.command;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.TokenUsage;
import com.maplecode.session.ChatSession;

/**
 * 窄 facade 接口，命令通过它与 UI/Agent/状态交互，不直接依赖 ReplLoop 内部。
 */
public interface CommandContext {
    // ── 输出 ──

    /** 显示普通信息给用户。 */
    void sendMessage(String message);

    /** 显示红色错误信息。 */
    void sendError(String message);

    // ── Agent 交互 ──

    /** 把文本送进对话交给 AI。同步阻塞直到 Agent 完成。 */
    void sendToAgent(String prompt);

    // ── 模式 ──

    void setPlanMode(PlanMode mode);
    PlanMode getPlanMode();

    PermissionMode getPermissionMode();
    void setPermissionMode(PermissionMode mode);

    // ── 状态 ──

    /** 当前 token 用量，可能返回 null（首回合前）。 */
    TokenUsage getTokenUsage();

    /** 刷新底部状态栏。 */
    void updateStatusBar();

    // ── 交互 ──

    /** 读取用户输入（带 prompt），用于 /resume 的交互式选择。 */
    String readLine(String prompt);

    // ── 会话 ──

    ChatSession getSession();
    AgentConfig getAgentConfig();
}
