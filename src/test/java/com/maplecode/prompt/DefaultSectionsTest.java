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
            List.of(), PlanMode.NORMAL, null);
        assertEquals(8, sections.size(),
            "应当恰好 8 个固定段：identity/constraints/task/action/tool/tone/text/env");
        for (var s : sections) {
            assertFalse(s.render(ctx(PlanMode.NORMAL)).isBlank(),
                "section " + s.kind() + " 输出空");
        }
    }

    @Test
    void environmentIsCacheableFalse() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null);
        var envSection = sections.get(7);
        assertEquals("environment", envSection.kind());
        assertFalse(envSection.cacheable(),
            "EnvironmentSection 必须是 cacheable=false");
    }

    @Test
    void taskModeVariesByPlanMode() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x", LocalDate.now(), LocalDate.now().getDayOfWeek(), LocalTime.now());
        var sections = DefaultSections.standard(env, List.of(), PlanMode.NORMAL, null);
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
            PlanMode.NORMAL, null);
        var withCustom = DefaultSections.standard(env, List.of(),
            PlanMode.NORMAL, "做且只做单元测试");
        assertEquals(8, nullCustom.size());
        assertEquals(9, withCustom.size());
        assertEquals("custom_instruction", withCustom.get(8).kind());
    }
}
