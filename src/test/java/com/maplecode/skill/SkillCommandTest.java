package com.maplecode.skill;

import com.maplecode.command.CommandContext;
import com.maplecode.command.CommandType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillCommandTest {

    private SkillDef createSkillDef(String name, String description, List<String> tools) {
        return new SkillDef(name, description, tools,
                           ExecutionMode.SHARED, 0, null, "Body for " + name, Path.of(name + ".md"));
    }

    @Test
    void name_returnsSkill() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        SkillCommand command = new SkillCommand(registry);

        assertEquals("skill", command.name());
    }

    @Test
    void aliases_returnsSkills() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        SkillCommand command = new SkillCommand(registry);

        assertEquals(List.of("skills"), command.aliases());
    }

    @Test
    void type_returnsLocal() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        SkillCommand command = new SkillCommand(registry);

        assertEquals(CommandType.LOCAL, command.type());
    }

    @Test
    void execute_emptyArgs_showsActiveSkills() {
        SkillRegistry registry = new SkillRegistry(Map.of());
        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("", ctx);

        verify(ctx).sendMessage(contains("没有已激活的 Skills"));
    }

    @Test
    void execute_emptyArgs_withActiveSkill_showsActiveList() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("", ctx);

        verify(ctx).sendMessage(contains("my-skill"));
        verify(ctx).sendMessage(contains("My skill"));
    }

    @Test
    void execute_list_showsAllAvailableSkills() {
        SkillDef skill1 = createSkillDef("skill-1", "First skill", List.of());
        SkillDef skill2 = createSkillDef("skill-2", "Second skill", List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of(
            "skill-1", skill1,
            "skill-2", skill2
        ));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("list", ctx);

        verify(ctx).sendMessage(contains("skill-1"));
        verify(ctx).sendMessage(contains("skill-2"));
        verify(ctx).sendMessage(contains("First skill"));
        verify(ctx).sendMessage(contains("Second skill"));
    }

    @Test
    void execute_list_withActiveSkill_showsCheckmark() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("list", ctx);

        verify(ctx).sendMessage(contains("✓"));
    }

    @Test
    void execute_activate_validSkill_activatesAndShowsMessage() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of("exec"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate my-skill", ctx);

        assertTrue(registry.isActive("my-skill"));
        verify(ctx).sendMessage(contains("已激活"));
        verify(ctx).sendMessage(contains("exec"));
    }

    @Test
    void execute_activate_withInput_passesInput() {
        String body = "Do something with {{input}}";
        SkillDef skill = new SkillDef("my-skill", "My skill", List.of(),
                                      ExecutionMode.SHARED, 0, null, body, Path.of("my-skill.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate my-skill some input", ctx);

        assertTrue(registry.isActive("my-skill"));
        SkillState state = registry.active().iterator().next();
        assertTrue(state.renderedBody().contains("some input"));
    }

    @Test
    void execute_activate_alreadyActive_showsMessage() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate my-skill", ctx);

        verify(ctx).sendMessage(contains("已经激活"));
    }

    @Test
    void execute_activate_unknownSkill_showsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate unknown", ctx);

        verify(ctx).sendError(contains("未知的 Skill"));
    }

    @Test
    void execute_deactivate_activeSkill_deactivatesAndShowsMessage() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));
        registry.activate(skill, "Body");

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("deactivate my-skill", ctx);

        assertFalse(registry.isActive("my-skill"));
        verify(ctx).sendMessage(contains("已停用"));
    }

    @Test
    void execute_deactivate_notActiveSkill_showsError() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("deactivate my-skill", ctx);

        verify(ctx).sendError(contains("未激活"));
    }

    @Test
    void execute_info_validSkill_showsDetails() {
        SkillDef skill = new SkillDef("my-skill", "My skill", List.of("exec"),
                                      ExecutionMode.INDEPENDENT, 5, "claude-haiku-4-5", "Body content", Path.of("test.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("info my-skill", ctx);

        verify(ctx).sendMessage(contains("my-skill"));
        verify(ctx).sendMessage(contains("My skill"));
        verify(ctx).sendMessage(contains("INDEPENDENT"));
        verify(ctx).sendMessage(contains("exec"));
        verify(ctx).sendMessage(contains("历史深度: 5"));
        verify(ctx).sendMessage(contains("claude-haiku-4-5"));
        verify(ctx).sendMessage(contains("Body content"));
    }

    @Test
    void execute_info_unknownSkill_showsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("info unknown", ctx);

        verify(ctx).sendError(contains("未知的 Skill"));
    }

    @Test
    void execute_noSubcommand_activatesDirectly() {
        SkillDef skill = createSkillDef("my-skill", "My skill", List.of());
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("my-skill", ctx);

        assertTrue(registry.isActive("my-skill"));
    }

    @Test
    void execute_activate_emptyArgs_showsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate", ctx);

        verify(ctx).sendError(contains("用法"));
    }

    @Test
    void execute_deactivate_emptyArgs_showsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("deactivate", ctx);

        verify(ctx).sendError(contains("用法"));
    }

    @Test
    void execute_info_emptyArgs_showsError() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("info", ctx);

        verify(ctx).sendError(contains("用法"));
    }

    @Test
    void execute_list_noSkills_showsMessage() {
        SkillRegistry registry = new SkillRegistry(Map.of());

        SkillCommand command = new SkillCommand(registry);
        CommandContext ctx = mock(CommandContext.class);

        command.execute("list", ctx);

        verify(ctx).sendMessage(contains("没有可用的 Skills"));
    }

    @Test
    void execute_activate_independentSkill_runsAndShowsResult() {
        SkillDef skill = new SkillDef("my-skill", "My skill", List.of(),
                                      ExecutionMode.INDEPENDENT, 0, null, "Body with {{input}}", Path.of("my-skill.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        IndependentSkillRunner mockRunner = mock(IndependentSkillRunner.class);
        when(mockRunner.run(any(), any(), any())).thenReturn("独立执行结果");
        command.setRunner(mockRunner);

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getSession()).thenReturn(new com.maplecode.session.ChatSession());

        command.execute("activate my-skill some input", ctx);

        verify(mockRunner).run(eq(skill), eq("some input"), any());
        verify(ctx, times(2)).sendMessage(contains("独立执行"));
        assertFalse(registry.isActive("my-skill")); // 不应激活
    }

    @Test
    void execute_activate_independentSkill_noRunner_showsError() {
        SkillDef skill = new SkillDef("my-skill", "My skill", List.of(),
                                      ExecutionMode.INDEPENDENT, 0, null, "Body", Path.of("my-skill.md"));
        SkillRegistry registry = new SkillRegistry(Map.of("my-skill", skill));

        SkillCommand command = new SkillCommand(registry);
        // 不设置 runner

        CommandContext ctx = mock(CommandContext.class);

        command.execute("activate my-skill", ctx);

        verify(ctx).sendError(contains("独立执行模式未启用"));
        assertFalse(registry.isActive("my-skill"));
    }
}
