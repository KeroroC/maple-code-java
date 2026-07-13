package com.maplecode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SkillDef createSkillDef(String name, String body, List<String> tools) {
        return new SkillDef(name, "Description for " + name, tools,
                           ExecutionMode.SHARED, 0, null, body, Path.of(name + ".md"));
    }

    @Test
    void name_returnsLoadSkill() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        LoadSkillTool tool = new LoadSkillTool(registry);

        assertEquals("load_skill", tool.name());
    }

    @Test
    void description_containsAvailableSkills() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        String desc = tool.description();
        assertTrue(desc.contains("my-skill"));
        assertTrue(desc.contains("Description for my-skill"));
    }

    @Test
    void execute_missingSkillName_returnsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing required parameter: skill_name"));
    }

    @Test
    void execute_blankSkillName_returnsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "   ");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Missing required parameter: skill_name"));
    }

    @Test
    void execute_unknownSkill_returnsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "nonexistent");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown skill: nonexistent"));
    }

    @Test
    void execute_unknownSkill_showsAvailableSkills() {
        SkillDef skill = createSkillDef("available-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("available-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "nonexistent");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("available-skill"));
    }

    @Test
    void execute_validSkill_activatesAndReturnsSuccess() {
        String body = "# My Skill\nDo something with {{input}}";
        SkillDef skill = createSkillDef("my-skill", body, List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "my-skill");
        args.put("input", "test input");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("my-skill"));
        assertTrue(result.content().contains("已激活"));

        // 验证 Skill 已激活
        assertTrue(registry.isActive("my-skill"));
        SkillState state = registry.active().iterator().next();
        assertTrue(state.renderedBody().contains("test input"));
    }

    @Test
    void execute_skillWithTools_showsToolWhitelist() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of("exec", "read_file"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "my-skill");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("exec"));
        assertTrue(result.content().contains("read_file"));
    }

    @Test
    void execute_skillWithoutTools_showsNoWhitelist() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "my-skill");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());
        assertFalse(result.content().contains("工具白名单"));
    }

    @Test
    void execute_independentMode_showsModeInfo() {
        SkillDef skill = new SkillDef("my-skill", "Desc", List.of(),
                                      ExecutionMode.INDEPENDENT, 5, null, "Body", Path.of("test.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "my-skill");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("INDEPENDENT"));
        assertTrue(result.content().contains("5 条历史"));
    }

    @Test
    void execute_inputWithPlaceholder_replacesPlaceholder() {
        String body = "Analyze this code:\n{{input}}\nProvide feedback.";
        SkillDef skill = createSkillDef("review-skill", body, List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("review-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "review-skill");
        args.put("input", "public class Foo {}");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());

        SkillState state = registry.active().iterator().next();
        assertTrue(state.renderedBody().contains("public class Foo {}"));
        assertFalse(state.renderedBody().contains("{{input}}"));
    }

    @Test
    void execute_noInput_usesEmptyString() {
        String body = "Do something {{input}} here";
        SkillDef skill = createSkillDef("my-skill", body, List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "my-skill");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());

        SkillState state = registry.active().iterator().next();
        assertFalse(state.renderedBody().contains("{{input}}"));
        assertTrue(state.renderedBody().contains("Do something  here"));
    }

    @Test
    void execute_skillNameIsCaseInsensitive() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        LoadSkillTool tool = new LoadSkillTool(registry);

        ObjectNode args = mapper.createObjectNode();
        args.put("skill_name", "MY-SKILL");
        ToolResult result = tool.execute(args, ToolContext.defaults(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(registry.isActive("my-skill"));
    }
}
