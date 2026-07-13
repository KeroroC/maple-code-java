package com.maplecode.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private SkillDef createSkillDef(String name, List<String> tools) {
        return new SkillDef(name, "Description for " + name, tools,
                           ExecutionMode.SHARED, 0, null, "Body for " + name, Path.of(name + ".md"));
    }

    @Test
    void available_returnsAllLoadedSkills() {
        SkillDef skill1 = createSkillDef("skill-1", List.of());
        SkillDef skill2 = createSkillDef("skill-2", List.of());

        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));

        Collection<SkillDef> available = registry.available();
        assertEquals(2, available.size());
        assertTrue(available.contains(skill1));
        assertTrue(available.contains(skill2));
    }

    @Test
    void find_existingSkill_returnsDef() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillDef found = registry.find("my-skill");
        assertNotNull(found);
        assertEquals("my-skill", found.name());
    }

    @Test
    void find_nonexistentSkill_returnsNull() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillDef found = registry.find("nonexistent");
        assertNull(found);
    }

    @Test
    void activate_newSkill_returnsState() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillState state = registry.activate(skill, "Rendered body");

        assertNotNull(state);
        assertEquals("my-skill", state.def().name());
        assertEquals("Rendered body", state.renderedBody());
        assertNotNull(state.activatedAt());
    }

    @Test
    void activate_alreadyActive_replacesState() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillState state1 = registry.activate(skill, "Body 1");
        SkillState state2 = registry.activate(skill, "Body 2");

        assertEquals("Body 2", state2.renderedBody());
        assertEquals(1, registry.activeCount());
    }

    @Test
    void isActive_afterActivate_returnsTrue() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        assertFalse(registry.isActive("my-skill"));
        registry.activate(skill, "Body");
        assertTrue(registry.isActive("my-skill"));
    }

    @Test
    void deactivate_existingSkill_returnsState() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        registry.activate(skill, "Body");
        SkillState removed = registry.deactivate("my-skill");

        assertNotNull(removed);
        assertEquals("my-skill", removed.def().name());
        assertFalse(registry.isActive("my-skill"));
    }

    @Test
    void deactivate_nonexistentSkill_returnsNull() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillState removed = registry.deactivate("nonexistent");
        assertNull(removed);
    }

    @Test
    void deactivateAll_clearsAllActiveSkills() {
        SkillDef skill1 = createSkillDef("skill-1", List.of());
        SkillDef skill2 = createSkillDef("skill-2", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));

        registry.activate(skill1, "Body 1");
        registry.activate(skill2, "Body 2");
        assertEquals(2, registry.activeCount());

        registry.deactivateAll();
        assertEquals(0, registry.activeCount());
        assertFalse(registry.isActive("skill-1"));
        assertFalse(registry.isActive("skill-2"));
    }

    @Test
    void active_returnsAllActiveStates() {
        SkillDef skill1 = createSkillDef("skill-1", List.of());
        SkillDef skill2 = createSkillDef("skill-2", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));

        registry.activate(skill1, "Body 1");
        registry.activate(skill2, "Body 2");

        Collection<SkillState> active = registry.active();
        assertEquals(2, active.size());
    }

    @Test
    void activeToolWhitelist_withTools_returnsUnion() {
        SkillDef skill1 = createSkillDef("skill-1", List.of("exec", "read_file"));
        SkillDef skill2 = createSkillDef("skill-2", List.of("read_file", "grep"));
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));

        registry.activate(skill1, "Body 1");
        registry.activate(skill2, "Body 2");

        Set<String> whitelist = registry.activeToolWhitelist();
        assertEquals(3, whitelist.size());
        assertTrue(whitelist.contains("exec"));
        assertTrue(whitelist.contains("read_file"));
        assertTrue(whitelist.contains("grep"));
    }

    @Test
    void activeToolWhitelist_noActiveSkills_returnsEmpty() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        Set<String> whitelist = registry.activeToolWhitelist();
        assertTrue(whitelist.isEmpty());
    }

    @Test
    void activeToolWhitelist_noToolsSpecified_returnsEmpty() {
        SkillDef skill = createSkillDef("skill-1", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("skill-1", skill));

        registry.activate(skill, "Body");

        Set<String> whitelist = registry.activeToolWhitelist();
        assertTrue(whitelist.isEmpty());
    }

    @Test
    void constructor_nullAvailable_throwsException() {
        assertThrows(NullPointerException.class, () -> new SkillRegistry(null));
    }

    @Test
    void activate_nullDef_throwsException() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        assertThrows(NullPointerException.class, () -> registry.activate(null, "Body"));
    }

    @Test
    void activate_nullBody_throwsException() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        assertThrows(NullPointerException.class, () -> registry.activate(skill, null));
    }
}
