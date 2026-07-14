package com.maplecode.skill;

import com.maplecode.command.Command;
import com.maplecode.command.CommandContext;
import com.maplecode.command.CommandType;
import com.maplecode.session.ChatSession;

import java.util.Collection;
import java.util.List;

/**
 * Skill 管理命令。
 *
 * <p>支持的子命令：
 * <ul>
 *   <li>/skill - 显示已激活的 Skills</li>
 *   <li>/skill list - 列出所有可用的 Skills</li>
 *   <li>/skill activate &lt;name&gt; [input] - 激活 Skill</li>
 *   <li>/skill deactivate &lt;name&gt; - 停用 Skill</li>
 *   <li>/skill info &lt;name&gt; - 查看 Skill 详情</li>
 * </ul>
 */
public class SkillCommand implements Command {

    private final SkillRegistry registry;
    private IndependentSkillRunner runner;

    public SkillCommand(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 设置独立模式运行器。在 App.main 装配阶段调用。
     */
    public void setRunner(IndependentSkillRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "管理 Skills（/skill list|activate|deactivate|info）";
    }

    @Override
    public String usage() {
        return "/skill [list|activate <name> [input]|deactivate <name>|info <name>]";
    }

    @Override
    public CommandType type() {
        return CommandType.LOCAL;
    }

    @Override
    public boolean hidden() {
        return false;
    }

    @Override
    public List<String> aliases() {
        return List.of("skills");
    }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (args.isBlank()) {
            showActive(ctx);
            return;
        }

        String[] parts = args.split("\\s+", 2);
        String subcommand = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (subcommand) {
            case "list":
                listSkills(ctx);
                break;
            case "activate":
                activateSkill(rest, ctx);
                break;
            case "deactivate":
                deactivateSkill(rest, ctx);
                break;
            case "info":
                showInfo(rest, ctx);
                break;
            default:
                // 如果没有子命令，尝试将整个 args 作为 Skill 名称激活
                activateSkill(args, ctx);
                break;
        }
    }

    private void showActive(CommandContext ctx) {
        Collection<SkillState> active = registry.active();
        if (active.isEmpty()) {
            ctx.sendMessage("没有已激活的 Skills。使用 /skill list 查看可用的 Skills。");
            return;
        }

        ctx.sendMessage("已激活的 Skills:");
        for (SkillState state : active) {
            SkillDef def = state.def();
            String toolInfo = def.tools().isEmpty() ? "" : " [工具: " + String.join(", ", def.tools()) + "]";
            String modeInfo = " [" + def.mode() + "]";
            ctx.sendMessage("  • " + def.name() + ": " + def.description() + toolInfo + modeInfo);
        }
    }

    private void listSkills(CommandContext ctx) {
        Collection<SkillDef> available = registry.available();
        if (available.isEmpty()) {
            ctx.sendMessage("没有可用的 Skills。");
            return;
        }

        ctx.sendMessage("可用的 Skills:");
        for (SkillDef def : available) {
            String activeMarker = registry.isActive(def.name()) ? " ✓" : "";
            String toolInfo = def.tools().isEmpty() ? "" : " [工具: " + String.join(", ", def.tools()) + "]";
            String modeInfo = " [" + def.mode() + "]";
            ctx.sendMessage("  • " + def.name() + ": " + def.description() + toolInfo + modeInfo + activeMarker);
        }
        ctx.sendMessage("\n使用 /skill activate <name> 激活 Skill。");
    }

    private void activateSkill(String args, CommandContext ctx) {
        if (args.isBlank()) {
            ctx.sendError("用法: /skill activate <name> [input]");
            return;
        }

        String[] parts = args.split("\\s+", 2);
        String skillName = parts[0].toLowerCase();
        String input = parts.length > 1 ? parts[1].trim() : "";

        SkillDef def = registry.find(skillName);
        if (def == null) {
            ctx.sendError("未知的 Skill: " + skillName);
            listAvailableNames(ctx);
            return;
        }

        if (registry.isActive(skillName)) {
            ctx.sendMessage("Skill '" + skillName + "' 已经激活。");
            return;
        }

        // 独立模式：直接执行并返回结果
        if (def.mode() == ExecutionMode.INDEPENDENT) {
            if (runner == null) {
                ctx.sendError("独立执行模式未启用（runner 未注入）。");
                return;
            }
            ChatSession session = ctx.getSession();
            ctx.sendMessage("⏳ 正在独立执行 Skill '" + skillName + "'...");
            String result = runner.run(def, input, session);
            ctx.sendMessage(result);
            return;
        }

        // 共享模式：渲染 {{input}} 占位符，注入到系统提示词
        String rendered = def.body().replace("{{input}}", input);

        // 激活 Skill
        registry.activate(def, rendered);

        String toolInfo = def.tools().isEmpty() ? "" : " 工具白名单: " + String.join(", ", def.tools());
        String modeInfo = " 执行模式: " + def.mode();
        ctx.sendMessage("✓ Skill '" + skillName + "' 已激活。" + toolInfo + modeInfo);
    }

    private void deactivateSkill(String args, CommandContext ctx) {
        if (args.isBlank()) {
            ctx.sendError("用法: /skill deactivate <name>");
            return;
        }

        String skillName = args.toLowerCase().trim();
        SkillState removed = registry.deactivate(skillName);
        if (removed == null) {
            ctx.sendError("Skill '" + skillName + "' 未激活。");
        } else {
            ctx.sendMessage("✓ Skill '" + skillName + "' 已停用。");
        }
    }

    private void showInfo(String args, CommandContext ctx) {
        if (args.isBlank()) {
            ctx.sendError("用法: /skill info <name>");
            return;
        }

        String skillName = args.toLowerCase().trim();
        SkillDef def = registry.find(skillName);
        if (def == null) {
            ctx.sendError("未知的 Skill: " + skillName);
            listAvailableNames(ctx);
            return;
        }

        ctx.sendMessage("Skill: " + def.name());
        ctx.sendMessage("描述: " + def.description());
        ctx.sendMessage("来源: " + def.sourcePath());
        ctx.sendMessage("模式: " + def.mode());
        if (!def.tools().isEmpty()) {
            ctx.sendMessage("工具: " + String.join(", ", def.tools()));
        }
        if (def.mode() == ExecutionMode.INDEPENDENT) {
            ctx.sendMessage("历史深度: " + def.historyDepth());
            if (def.model() != null) {
                ctx.sendMessage("指定模型: " + def.model());
            }
        }
        ctx.sendMessage("已激活: " + (registry.isActive(skillName) ? "是" : "否"));
        ctx.sendMessage("\n--- Skill 指令 ---");
        ctx.sendMessage(def.body());
    }

    private void listAvailableNames(CommandContext ctx) {
        Collection<SkillDef> available = registry.available();
        if (!available.isEmpty()) {
            String names = available.stream()
                .map(SkillDef::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            ctx.sendMessage("可用的 Skills: " + names);
        }
    }
}
