package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionTest {

    @Test
    void allow_factory_sets_verdict_and_reason() {
        var d = Decision.allow("ok");
        assertEquals(Decision.Verdict.ALLOW, d.verdict());
        assertEquals("ok", d.reason());
    }

    @Test
    void deny_factory_sets_verdict_and_reason() {
        var d = Decision.deny("blocked");
        assertEquals(Decision.Verdict.DENY, d.verdict());
        assertEquals("blocked", d.reason());
    }

    @Test
    void record_equality() {
        assertEquals(Decision.allow("x"), Decision.allow("x"));
        assertNotEquals(Decision.allow("x"), Decision.deny("x"));
    }
}
