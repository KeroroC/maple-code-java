package com.maplecode.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewCommandTest {

    @Test
    void name_is_review() {
        assertEquals("review", new ReviewCommand().name());
    }

    @Test
    void type_is_prompt() {
        assertEquals(CommandType.PROMPT, new ReviewCommand().type());
    }

    @Test
    void notHidden() {
        assertFalse(new ReviewCommand().hidden());
    }
}
