package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionModeTest {
    @Test
    void has_three_values() {
        assertEquals(3, PermissionMode.values().length);
        assertNotNull(PermissionMode.valueOf("STRICT"));
        assertNotNull(PermissionMode.valueOf("DEFAULT"));
        assertNotNull(PermissionMode.valueOf("PERMISSIVE"));
    }
}
