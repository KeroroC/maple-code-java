package com.maplecode.command;

import java.util.List;

/**
 * 斜杠命令契约。每个内置命令实现此接口。
 * 在 App.main 启动阶段注册到 CommandRegistry。
 */
public interface Command {
    /** 命令名称（小写），如 "help"、"memory"。 */
    String name();

    /** 一句话描述，用于 /help 列表。 */
    String description();

    /** 用法示例，如 "/help [command]"、"/memory <list|clear|extract>"。 */
    String usage();

    /** 命令类型，纯元数据，用于 /help 分类显示。 */
    CommandType type();

    /**
     * 是否隐藏。隐藏命令不参与 /help 列表和 Tab 补全。
     * 用于内部命令或别名命令。
     */
    boolean hidden();

    /**
     * 别名列表。默认返回空集合（不可变）。
     * 实现时必须返回非 null。
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 执行命令。
     *
     * @param args 命令名之后的原始字符串（已 trim），空输入为 ""
     * @param ctx  UI 控制接口
     * @throws ExitReplException 终止 REPL 主循环（/exit 专用）
     */
    void execute(String args, CommandContext ctx);
}
