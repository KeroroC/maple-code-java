package com.maplecode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PermissionRequestTest {
    @Test
    void stores_all_three_fields() {
        JsonNode args = new ObjectMapper().createObjectNode().put("command", "ls");
        var req = new PermissionRequest("exec", args, Path.of("/tmp"));
        assertEquals("exec", req.toolName());
        assertSame(args, req.args());
        assertEquals(Path.of("/tmp"), req.cwd());
    }
}
