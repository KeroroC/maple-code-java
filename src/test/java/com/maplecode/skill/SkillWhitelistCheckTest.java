package com.maplecode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.permission.Decision;
import com.maplecode.permission.PermissionContext;
import com.maplecode.permission.PermissionMode;
import com.maplecode.permission.PermissionRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillWhitelistCheckTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PermissionRequest createRequest(String toolName) {
        return new PermissionRequest(toolName, mapper.createObjectNode(), Path.of("."));
    }

    private PermissionContext createContext() {
        return new PermissionContext(PermissionMode.DEFAULT, Set.of(), Set.of());
    }

    private SkillDef createSkillDef(String name, List<String> tools) {
        return new SkillDef(name, "Description", tools,
                           ExecutionMode.SHARED, 0, null, "Body", Path.of(name + ".md"));
    }

    @Test
    void check_noActiveSkills_returnsEmpty() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);

        Optional<Decision> result = check.check(createRequest("exec"), createContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void check_activeSkillWithoutWhitelist_returnsEmpty() {
        SkillDef skill = createSkillDef("my-skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);
        Optional<Decision> result = check.check(createRequest("exec"), createContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void check_toolInWhitelist_returnsEmpty() {
        SkillDef skill = createSkillDef("my-skill", List.of("exec", "read_file"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);
        Optional<Decision> result = check.check(createRequest("exec"), createContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void check_toolNotInWhitelist_returnsDeny() {
        SkillDef skill = createSkillDef("my-skill", List.of("exec", "read_file"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);
        Optional<Decision> result = check.check(createRequest("write_file"), createContext());

        assertTrue(result.isPresent(), "Decision should be present");
        assertEquals(Decision.Verdict.DENY, result.get().verdict());
        String reason = result.get().reason();
        assertTrue(reason.contains("Skill 工具白名单限制"), "Reason should contain 'Skill 工具白名单限制', but was: " + reason);
        assertTrue(reason.contains("exec"), "Reason should contain 'exec', but was: " + reason);
        assertTrue(reason.contains("read_file"), "Reason should contain 'read_file', but was: " + reason);
    }

    @Test
    void check_multipleSkills_unionWhitelist() {
        SkillDef skill1 = createSkillDef("skill-1", List.of("exec", "read_file"));
        SkillDef skill2 = createSkillDef("skill-2", List.of("read_file", "grep"));
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));
        registry.activate(skill1, "Body 1");
        registry.activate(skill2, "Body 2");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);

        // exec 在 skill-1 的白名单中
        assertTrue(check.check(createRequest("exec"), createContext()).isEmpty());

        // grep 在 skill-2 的白名单中
        assertTrue(check.check(createRequest("grep"), createContext()).isEmpty());

        // read_file 在两个白名单中
        assertTrue(check.check(createRequest("read_file"), createContext()).isEmpty());

        // write_file 不在任何白名单中
        Optional<Decision> result = check.check(createRequest("write_file"), createContext());
        assertTrue(result.isPresent());
        assertEquals(Decision.Verdict.DENY, result.get().verdict());
    }

    @Test
    void check_systemTool_bypassesWhitelist() {
        SkillDef skill = createSkillDef("my-skill", List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);

        // load_skill 是系统级工具，应该绕过白名单
        Optional<Decision> result = check.check(createRequest("load_skill"), createContext());
        assertTrue(result.isEmpty());
    }

    @Test
    void check_deactivatedSkill_removesFromWhitelist() {
        SkillDef skill = createSkillDef("my-skill", List.of("exec", "read_file"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);

        // 激活时 write_file 被拒绝
        assertTrue(check.check(createRequest("write_file"), createContext()).isPresent());

        // 停用后 write_file 应该被允许
        registry.deactivate("my-skill");
        assertTrue(check.check(createRequest("write_file"), createContext()).isEmpty());
    }

    @Test
    void check_oneSkillWithWhitelistOneWithout_onlyFirstRestricts() {
        SkillDef skillWithWhitelist = createSkillDef("restricted", List.of("exec"));
        SkillDef skillWithoutWhitelist = createSkillDef("unrestricted", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of(
            "restricted", skillWithWhitelist,
            "unrestricted", skillWithoutWhitelist
        ));
        registry.activate(skillWithWhitelist, "Body 1");
        registry.activate(skillWithoutWhitelist, "Body 2");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);

        // 有白名单的 Skill 存在时，应该限制
        Optional<Decision> result = check.check(createRequest("read_file"), createContext());
        assertTrue(result.isPresent());
    }

    @Test
    void check_denyDecision_containsCorrectReason() {
        SkillDef skill = createSkillDef("my-skill", List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillWhitelistCheck check = new SkillWhitelistCheck(registry);
        Optional<Decision> result = check.check(createRequest("write_file"), createContext());

        assertTrue(result.isPresent());
        String reason = result.get().reason();
        assertTrue(reason.contains("Skill 工具白名单限制"), "Reason should contain 'Skill 工具白名单限制', but was: " + reason);
        assertTrue(reason.contains("exec"), "Reason should contain 'exec', but was: " + reason);
    }
}
