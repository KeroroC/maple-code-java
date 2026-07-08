package com.maplecode.agents;

import com.maplecode.agent.PlanMode;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.SectionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentsMdSectionTest {

    private static SectionContext ctx() {
        var env = new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
        return new SectionContext(List.of(), env, PlanMode.NORMAL);
    }

    @Test
    void renderReturnsContent() {
        var section = new AgentsMdSection("hello world");
        assertEquals("hello world", section.render(ctx()));
    }

    @Test
    void nullContentRenderedAsEmpty() {
        var section = new AgentsMdSection(null);
        assertEquals("", section.render(ctx()));
    }

    @Test
    void kindIsAgentsMd() {
        var section = new AgentsMdSection("anything");
        assertEquals("agents_md", section.kind());
    }

    @Test
    void isCacheable() {
        var section = new AgentsMdSection("anything");
        assertTrue(section.cacheable());
    }
}
