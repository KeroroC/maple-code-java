package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanModeReminderTest {

    @Test
    void normalModeAlwaysNone() {
        for (int i = 0; i < 20; i++) {
            assertEquals(PlanModeReminder.Form.NONE,
                PlanModeReminder.decide(PlanMode.NORMAL,
                    PlanModeReminder.State.initial(), i));
        }
    }

    @Test
    void planModeFirstIterationFull() {
        assertEquals(PlanModeReminder.Form.FULL,
            PlanModeReminder.decide(PlanMode.PLAN,
                PlanModeReminder.State.initial(), 0));
    }

    @Test
    void planModeSubsequentBrief() {
        var s1 = PlanModeReminder.State.initial().afterFull(0);
        for (int i = 1; i <= 4; i++) {
            assertEquals(PlanModeReminder.Form.BRIEF,
                PlanModeReminder.decide(PlanMode.PLAN, s1, i),
                "iter=" + i);
        }
    }

    @Test
    void planModeEveryFiveFullRepeat() {
        var s = PlanModeReminder.State.initial().afterFull(0);
        // iter 1..4: brief
        for (int i = 1; i <= 4; i++) {
            assertEquals(PlanModeReminder.Form.BRIEF,
                PlanModeReminder.decide(PlanMode.PLAN, s, i), "iter=" + i);
        }
        // iter 5: full (5-0=5 >= REPEAT_INTERVAL=5)
        assertEquals(PlanModeReminder.Form.FULL,
            PlanModeReminder.decide(PlanMode.PLAN, s, 5));
        // after full at iter 5, update state
        var s2 = s.afterFull(5);
        // iter 6..9: brief (relative to lastFull=5)
        for (int i = 6; i <= 9; i++) {
            assertEquals(PlanModeReminder.Form.BRIEF,
                PlanModeReminder.decide(PlanMode.PLAN, s2, i), "iter=" + i);
        }
        // iter 10: full (10-5=5 >= REPEAT_INTERVAL=5)
        assertEquals(PlanModeReminder.Form.FULL,
            PlanModeReminder.decide(PlanMode.PLAN, s2, 10));
    }

    @Test
    void renderFullNonBlankAndContainsKeywords() {
        String r = PlanModeReminder.renderFull();
        assertFalse(r.isBlank());
        assertTrue(r.contains("规划"));
        assertTrue(r.contains("write_file"));
        assertTrue(r.contains("read_file"));
    }

    @Test
    void renderBriefNonBlank() {
        assertFalse(PlanModeReminder.renderBrief().isBlank());
    }
}
