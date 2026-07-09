package com.maplecode.command;

import com.maplecode.permission.PermissionMode;

public class ModeCommand implements Command {
    @Override public String name() { return "mode"; }
    @Override public String description() { return "查看或切换权限模式"; }
    @Override public String usage() { return "/mode [strict|default|permissive]"; }
    @Override public CommandType type() { return CommandType.UI_STATE; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (args.isEmpty()) {
            ctx.sendMessage("当前权限模式: " + ctx.getPermissionMode().name().toLowerCase());
            return;
        }
        try {
            PermissionMode mode = PermissionMode.valueOf(args.toUpperCase());
            ctx.setPermissionMode(mode);
            ctx.updateStatusBar();
        } catch (IllegalArgumentException e) {
            ctx.sendError("未知模式: " + args + "。可选: strict, default, permissive");
        }
    }
}
