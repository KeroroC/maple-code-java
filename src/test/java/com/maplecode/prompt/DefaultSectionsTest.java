package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSectionsTest {

    private static SectionContext ctx(PlanMode mode) {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "darwin (arm64)", "21.0.5", "3.9.6",
            LocalDate.of(2026, 7, 5), DayOfWeek.SATURDAY, LocalTime.of(10, 0));
        return new SectionContext(List.of(), env, mode);
    }

    @Test
    void fixedSectionsExistAndProduceNonEmptyText() {
        var sections = DefaultSections.standard(
            new DynamicContext(Path.of("/tmp"), false,
                "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now()),
            List.of(), PlanMode.NORMAL, null, null);
        assertEquals(9, sections.size(),
            "应当恰好 9 个固定段：identity/constraints/task/action/tool/tone/text/agents_md/env");
        for (var s : sections) {
            if ("agents_md".equals(s.kind())) continue;  // agentsMd=null 时内容为空，属正常
            assertFalse(s.render(ctx(PlanMode.NORMAL)).isBlank(),
                "section " + s.kind() + " 输出空");
        }
    }

    @Test
    void environmentIsCacheableFalse() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null, null);
        var envSection = sections.get(8);
        assertEquals("environment", envSection.kind());
        assertFalse(envSection.cacheable(),
            "EnvironmentSection 必须是 cacheable=false");
    }

    @Test
    void taskModeVariesByPlanMode() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null, null);
        var taskMode = sections.get(2);
        assertEquals("task_mode", taskMode.kind());
        assertNotEquals(taskMode.render(ctx(PlanMode.NORMAL)),
                        taskMode.render(ctx(PlanMode.PLAN)));
    }

    @Test
    void customInstructionAppendedWhenProvided() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now());
        var nullCustom = DefaultSections.standard(env, List.of(),
            PlanMode.NORMAL, null, null);
        var withCustom = DefaultSections.standard(env, List.of(),
            PlanMode.NORMAL, "做且只做单元测试", null);
        assertEquals(9, nullCustom.size());
        assertEquals(10, withCustom.size());
        assertEquals("custom_instruction", withCustom.get(9).kind());
    }
}
