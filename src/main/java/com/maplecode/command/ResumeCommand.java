package com.maplecode.command;

import com.maplecode.session.archive.SessionArchive;
import com.maplecode.session.archive.SessionMeta;

import java.util.List;

public class ResumeCommand implements Command {
    private final SessionArchive archive;

    public ResumeCommand(SessionArchive archive) {
        this.archive = archive;
    }

    @Override public String name() { return "resume"; }
    @Override public String description() { return "加载历史会话"; }
    @Override public String usage() { return "/resume [id]"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (archive == null) {
            ctx.sendError("会话归档未启用。");
            return;
        }

        if (args.isEmpty()) {
            List<SessionMeta> recent = archive.listRecent(10);
            if (recent.isEmpty()) {
                ctx.sendMessage("没有历史会话。");
                return;
            }
            for (int i = 0; i < recent.size(); i++) {
                SessionMeta meta = recent.get(i);
                ctx.sendMessage(String.format("  [%d] %s (%d msgs, %s)",
                    i + 1, meta.id(), meta.messageCount(),
                    formatAge(meta.lastActivity())));
            }
            String selection = ctx.readLine("Select [1-" + recent.size() + "]: ");
            try {
                int index = Integer.parseInt(selection.trim()) - 1;
                if (index < 0 || index >= recent.size()) {
                    ctx.sendError("无效选择。");
                    return;
                }
                String id = recent.get(index).id();
                ctx.getSession().replaceAll(archive.load(id));
                ctx.sendMessage("已加载会话: " + id);
            } catch (NumberFormatException e) {
                ctx.sendError("请输入数字。");
            }
        } else {
            try {
                ctx.getSession().replaceAll(archive.load(args));
                ctx.sendMessage("已加载会话: " + args);
            } catch (Exception e) {
                ctx.sendError("加载失败: " + e.getMessage());
            }
        }
    }

    private String formatAge(java.time.Instant lastActivity) {
        long ageMs = System.currentTimeMillis() - lastActivity.toEpochMilli();
        if (ageMs < 60_000) return "just now";
        if (ageMs < 3_600_000) return (ageMs / 60_000) + "m ago";
        if (ageMs < 86_400_000) return (ageMs / 3_600_000) + "h ago";
        return (ageMs / 86_400_000) + "d ago";
    }
}
