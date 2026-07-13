package com.maplecode.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;

/**
 * Skill 加载工具，模型通过此工具加载和激活 Skill。
 *
 * <p>这是系统级工具，不受工具白名单约束。
 */
public class LoadSkillTool implements Tool {

    public static final String NAME = "load_skill";

    private final SkillRegistry registry;

    public LoadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "加载并激活一个 Skill，将其完整指令注入到当前对话上下文。" +
               "可用的 Skills: " + availableSkillsSummary();
    }

    @Override
    public JsonNode inputSchema() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode skillName = mapper.createObjectNode();
        skillName.put("type", "string");
        skillName.put("description", "要加载的 Skill 名称");
        properties.set("skill_name", skillName);

        ObjectNode input = mapper.createObjectNode();
        input.put("type", "string");
        input.put("description", "传递给 Skill 的输入参数，将替换 Skill 指令中的 {{input}} 占位符");
        properties.set("input", input);

        schema.set("properties", properties);

        var required = mapper.createArrayNode();
        required.add("skill_name");
        schema.set("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        // 解析参数
        if (!args.has("skill_name") || args.get("skill_name").asText().isBlank()) {
            return ToolResult.error("Missing required parameter: skill_name");
        }

        String skillName = args.get("skill_name").asText().trim().toLowerCase();
        String input = args.has("input") ? args.get("input").asText() : "";

        // 查找 Skill
        SkillDef def = registry.find(skillName);
        if (def == null) {
            String available = registry.available().stream()
                .map(SkillDef::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
            return ToolResult.error("Unknown skill: " + skillName + ". Available: " + available);
        }

        // 渲染 {{input}} 占位符
        String rendered = def.body().replace("{{input}}", input);

        // 激活 Skill
        registry.activate(def, rendered);

        String toolInfo = "";
        if (def.tools() != null && !def.tools().isEmpty()) {
            toolInfo = " 工具白名单: " + String.join(", ", def.tools());
        }

        String modeInfo = " 执行模式: " + def.mode();
        if (def.mode() == ExecutionMode.INDEPENDENT && def.historyDepth() > 0) {
            modeInfo += " (带 " + def.historyDepth() + " 条历史)";
        }

        return ToolResult.ok("Skill '" + skillName + "' 已激活。" + toolInfo + modeInfo +
                            " 完整指令已加载到系统提示词中。");
    }

    /**
     * 生成可用 Skills 的摘要。
     */
    private String availableSkillsSummary() {
        return registry.available().stream()
            .map(s -> s.name() + ": " + s.description())
            .reduce((a, b) -> a + "; " + b)
            .orElse("(none)");
    }
}
