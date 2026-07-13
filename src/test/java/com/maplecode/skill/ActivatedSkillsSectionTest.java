package com.maplecode.skill;

import com.maplecode.agent.PlanMode;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.SectionContext;
import com.maplecode.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActivatedSkillsSectionTest {

    private SectionContext createContext() {
        return new SectionContext(List.of(), DynamicContext.capture(Path.of(".")), PlanMode.NORMAL);
    }

    private SkillDef createSkillDef(String name, String body, List<String> tools) {
        return new SkillDef(name, "Description for " + name, tools,
                           ExecutionMode.SHARED, 0, null, body, Path.of(name + ".md"));
    }

    @Test
    void kind_returnsActivatedSkills() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);

        assertEquals("activated_skills", section.kind());
    }

    @Test
    void cacheable_returnsFalse() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);

        assertFalse(section.cacheable());
    }

    @Test
    void enabled_noActiveSkills_returnsFalse() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);

        assertFalse(section.enabled(createContext()));
    }

    @Test
    void enabled_withActiveSkills_returnsTrue() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);

        assertTrue(section.enabled(createContext()));
    }

    @Test
    void render_noActiveSkills_returnsNull() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);

        String rendered = section.render(createContext());
        assertNull(rendered);
    }

    @Test
    void render_withActiveSkill_containsSkillName() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("my-skill"));
    }

    @Test
    void render_withActiveSkill_containsRenderedBody() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Do something specific");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("Do something specific"));
    }

    @Test
    void render_withToolWhitelist_containsToolList() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of("exec", "read_file"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("exec"));
        assertTrue(rendered.contains("read_file"));
        assertTrue(rendered.contains("可用工具"));
    }

    @Test
    void render_withoutToolWhitelist_noToolSection() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertFalse(rendered.contains("可用工具"));
    }

    @Test
    void render_independentMode_containsModeInfo() {
        SkillDef skill = new SkillDef("my-skill", "Desc", List.of(),
                                      ExecutionMode.INDEPENDENT, 5, null, "Body", Path.of("test.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("独立执行"));
        assertTrue(rendered.contains("5 条消息"));
    }

    @Test
    void render_independentModeWithModel_containsModelInfo() {
        SkillDef skill = new SkillDef("my-skill", "Desc", List.of(),
                                      ExecutionMode.INDEPENDENT, 0, "claude-haiku-4-5", "Body", Path.of("test.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("claude-haiku-4-5"));
    }

    @Test
    void render_multipleSkills_containsAll() {
        SkillDef skill1 = createSkillDef("skill-1", "Body 1", List.of());
        SkillDef skill2 = createSkillDef("skill-2", "Body 2", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));
        registry.activate(skill1, "Rendered body 1");
        registry.activate(skill2, "Rendered body 2");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("skill-1"));
        assertTrue(rendered.contains("skill-2"));
        assertTrue(rendered.contains("Rendered body 1"));
        assertTrue(rendered.contains("Rendered body 2"));
    }

    @Test
    void render_structureContainsHeaderAndInstructions() {
        SkillDef skill = createSkillDef("my-skill", "Body", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Rendered body");

        ActivatedSkillsSection section = new ActivatedSkillsSection(registry);
        String rendered = section.render(createContext());

        assertNotNull(rendered);
        assertTrue(rendered.contains("# 已激活的 Skills"));
        assertTrue(rendered.contains("## Skill: my-skill"));
        assertTrue(rendered.contains("### 指令"));
    }
}
