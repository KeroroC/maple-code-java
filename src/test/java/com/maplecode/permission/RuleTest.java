package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuleTest {
    @Test
    void stores_tool_pattern_action() {
        var r = new Rule("exec", "git *", Rule.Action.ALLOW);
        assertEquals("exec", r.toolName());
        assertEquals("git *", r.pattern());
        assertEquals(Rule.Action.ALLOW, r.action());
    }

    @Test
    void action_enum_has_allow_and_deny() {
        assertEquals(2, Rule.Action.values().length);
    }

    @Test
    void record_equality() {
        assertEquals(new Rule("exec", "ls", Rule.Action.ALLOW),
                     new Rule("exec", "ls", Rule.Action.ALLOW));
        assertNotEquals(new Rule("exec", "ls", Rule.Action.ALLOW),
                        new Rule("exec", "ls", Rule.Action.DENY));
    }

    @Test
    void rule_set_stores_ordered_list() {
        var rs = new RuleSet(List.of(
            new Rule("exec", "ls", Rule.Action.ALLOW),
            new Rule("exec", "rm", Rule.Action.DENY)));
        assertEquals(2, rs.rules().size());
        assertEquals("ls", rs.rules().get(0).pattern());
    }
}
