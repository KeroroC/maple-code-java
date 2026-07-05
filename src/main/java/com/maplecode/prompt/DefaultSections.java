package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import com.maplecode.tool.Tool;

import java.util.ArrayList;
import java.util.List;

public final class DefaultSections {

    private DefaultSections() {}

    public static final PromptSection IDENTITY = new Section("identity", """
            你是 MapleCode，一个运行在终端的 AI 编程助手。""", true, true);

    public static final PromptSection SYSTEM_CONSTRAINTS = new Section("constraints", """
            请遵循：
            - 不确定时，先读相关文件再行动，不要凭空猜测 API、路径或代码内容。
            - 仅使用已注册的工具；不要伪造工具结果。
            - 引用文件路径时优先使用工作目录相对的相对路径。""", true, true);

    public static final PromptSection TASK_MODE = new PlanModeAwareSection("task_mode");

    public static final PromptSection ACTION_EXECUTION = new Section("action_execution", """
            执行原则：
            - 多步任务先列出计划再按顺序执行；不要把所有步骤一口气说出。
            - 调用工具前先说明目的，调用后说明观察到的关键结果。
            - 工具返回错误时，先分析根因再决定是否重试。""", true, true);

    public static final PromptSection TOOL_USAGE = new ToolAwareSection("tool_usage");

    public static final PromptSection TONE_STYLE = new Section("tone_style", """
            风格：
            - 中文短句优先；标点用中文全角。
            - 技术名词保留英文（如 cache、token、schema）。
            - 段落用空行分隔。""", true, true);

    public static final PromptSection TEXT_OUTPUT = new Section("text_output", """
            输出格式：
            - 代码块包裹路径和命令。
            - 列表用 `- `，不要数字编号（除非按步骤）。
            - 不要把工具调用 JSON 完整回显；只说结论。""", true, true);

    public static final PromptSection ENVIRONMENT = new EnvSection();

    /** v5 占位：已激活 Skill 段。本期 enabled()=false，不挂进 standard()。 */
    public static final PromptSection ACTIVATED_SKILLS = new Section("activated_skills", "", true, false);

    /** v5 占位：长期记忆段。本期 enabled()=false，不挂进 standard()。 */
    public static final PromptSection LONG_TERM_MEMORY = new Section("long_term_memory", "", true, false);

    public static List<PromptSection> standard(DynamicContext env, List<Tool> tools,
                                               PlanMode planMode, String customInstruction) {
        List<PromptSection> list = new ArrayList<>(List.of(
            IDENTITY, SYSTEM_CONSTRAINTS, TASK_MODE, ACTION_EXECUTION,
            TOOL_USAGE, TONE_STYLE, TEXT_OUTPUT, ENVIRONMENT));
        if (customInstruction != null && !customInstruction.isBlank()) {
            list.add(new CustomInstructionSection(customInstruction));
        }
        return list;
    }

    // -------- 具体类（package-private） --------

    static final class Section implements PromptSection {
        private final String kind;
        private final String text;
        private final boolean cacheable;
        private final boolean enabled;
        Section(String kind, String text, boolean cacheable, boolean enabled) {
            this.kind = kind; this.text = text;
            this.cacheable = cacheable; this.enabled = enabled;
        }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) { return text; }
        @Override public boolean cacheable() { return cacheable; }
        @Override public boolean enabled(SectionContext ctx) { return enabled; }
    }

    static final class PlanModeAwareSection implements PromptSection {
        private final String kind;
        PlanModeAwareSection(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) {
            return ctx.planMode() == PlanMode.PLAN
                ? "你处于规划模式，仅可使用 read_file / glob / grep。输出一份可执行计划。"
                : "你处于执行模式，可以读写文件、执行命令，完成用户的任务即可。";
        }
    }

    static final class ToolAwareSection implements PromptSection {
        private final String kind;
        ToolAwareSection(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public String render(SectionContext ctx) {
            StringBuilder toolNames = new StringBuilder();
            for (var t : ctx.tools()) {
                if (toolNames.length() > 0) toolNames.append(", ");
                toolNames.append(t.name());
            }
            return "工具使用约定：\n"
                 + "- 优先使用专用工具：读文件用 read_file，搜索用 glob/grep，"
                 + "写文件用 write_file，精确修改用 edit_file。\n"
                 + "- edit_file 之前必须先 read_file 目标文件，确认实际内容。\n"
                 + "- exec 跑长命令加 timeout，不要用 exec 模拟 ls/find/grep。\n"
                 + "- 可用工具：" + toolNames + "。\n"
                 + "- 工具返回错误时按错误信息调整调用。";
        }
    }

    static final class EnvSection implements PromptSection {
        @Override public String kind() { return "environment"; }
        @Override public String render(SectionContext ctx) {
            var e = ctx.env();
            return "## Environment\n"
                 + "- Working directory: " + e.cwd() + "\n"
                 + "- Git repo: " + (e.isGitRepo() ? "yes" : "no") + "\n"
                 + "- Platform: " + e.platform() + "\n"
                 + "- Runtime: Java " + e.javaVersion()
                 + ", Maven " + e.mavenVersion() + "\n"
                 + "- Date: " + e.date() + " (" + e.dayOfWeek() + ")\n"
                 + "- Time: " + e.time();
        }
        @Override public boolean cacheable() { return false; }
    }

    static final class CustomInstructionSection implements PromptSection {
        private final String text;
        CustomInstructionSection(String text) { this.text = text; }
        @Override public String kind() { return "custom_instruction"; }
        @Override public String render(SectionContext ctx) { return text; }
        @Override public boolean cacheable() { return true; }
    }
}
