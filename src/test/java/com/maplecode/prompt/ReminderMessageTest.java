package com.maplecode.prompt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReminderMessageTest {

    @Test
    void wrapContainsBodyAndTags() {
        String wrapped = ReminderMessage.wrap("规划模式仍激活");
        assertTrue(wrapped.startsWith("<system-reminder>\n"));
        assertTrue(wrapped.contains("规划模式仍激活"));
        assertTrue(wrapped.endsWith("\n</system-reminder>"));
    }

    @Test
    void wrapEmptyBody() {
        String wrapped = ReminderMessage.wrap("");
        assertEquals("<system-reminder>\n\n</system-reminder>", wrapped);
    }
}
