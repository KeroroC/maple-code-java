package com.maplecode.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryCategoryTest {

    @Test
    void userAndFeedbackAreUserScope() {
        assertEquals(MemoryScope.USER, MemoryCategory.USER.scope());
        assertEquals(MemoryScope.USER, MemoryCategory.FEEDBACK.scope());
    }

    @Test
    void projectAndReferenceAreProjectScope() {
        assertEquals(MemoryScope.PROJECT, MemoryCategory.PROJECT.scope());
        assertEquals(MemoryScope.PROJECT, MemoryCategory.REFERENCE.scope());
    }

    @Test
    void dirNameMatchesLowercase() {
        assertEquals("user", MemoryCategory.USER.dirName());
        assertEquals("feedback", MemoryCategory.FEEDBACK.dirName());
        assertEquals("project", MemoryCategory.PROJECT.dirName());
        assertEquals("reference", MemoryCategory.REFERENCE.dirName());
    }

    @Test
    void fromDirName_roundTrips() {
        for (var cat : MemoryCategory.values()) {
            assertEquals(cat, MemoryCategory.fromDirName(cat.dirName()));
        }
    }

    @Test
    void fromDirName_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> MemoryCategory.fromDirName("bogus"));
    }
}
