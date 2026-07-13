package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSectionsAgentsTest {

    private static DynamicContext env() {
        return new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
    }

    @Test
    void agentsMdSectionIsBetweenTextOutputAndEnvironment() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "rules", null, null);

        // 找到 agents_md 段
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AgentsMdSection 不在列表中"));

        int agentsMdIdx = sections.indexOf(agentsMd);
        int textOutputIdx = -1;
        int memoryIdx = -1;
        for (int i = 0; i < sections.size(); i++) {
            if ("text_output".equals(sections.get(i).kind())) textOutputIdx = i;
            if ("long_term_memory".equals(sections.get(i).kind())) memoryIdx = i;
        }
        assertTrue(textOutputIdx >= 0, "text_output 段缺失");
        assertTrue(memoryIdx >= 0, "long_term_memory 段缺失");
        assertEquals(textOutputIdx + 1, agentsMdIdx,
            "AgentsMdSection 应紧跟 text_output 之后");
        assertEquals(agentsMdIdx + 1, memoryIdx,
            "MemorySection 应紧跟 agents_md 之后");
    }

    @Test
    void emptyAgentsMdStillProducesSection() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, null, null, null);
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow();
        assertEquals("", agentsMd.render(new SectionContext(
            List.of(), env(), PlanMode.NORMAL)));
    }

    @Test
    void nonEmptyAgentsMdRendersContent() {
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "Use Java 21.\nAvoid global state.", null, null);
        var agentsMd = sections.stream()
            .filter(s -> "agents_md".equals(s.kind()))
            .findFirst()
            .orElseThrow();
        assertEquals("Use Java 21.\nAvoid global state.",
            agentsMd.render(new SectionContext(
                List.of(), env(), PlanMode.NORMAL)));
    }
}
