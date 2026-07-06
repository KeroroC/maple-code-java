package com.maplecode.permission;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionFileLoaderTest {

    @Test
    void missing_user_file_skipped(@TempDir Path tmp) throws Exception {
        var rs = PermissionFileLoader.loadAll(tmp, tmp.resolve("nope.yaml"));
        assertTrue(rs.rules().isEmpty());
    }

    @Test
    void loads_project_file(@TempDir Path tmp) throws Exception {
        var dot = tmp.resolve(".maplecode");
        Files.createDirectories(dot);
        Files.writeString(dot.resolve("permissions.yaml"), """
            rules:
              - tool: exec
                pattern: "git *"
                action: allow
            """);
        var rs = PermissionFileLoader.loadAll(tmp, tmp.resolve("nope-user.yaml"));
        assertEquals(1, rs.rules().size());
        assertEquals("exec", rs.rules().get(0).toolName());
    }

    @Test
    void merges_user_project_local_with_local_highest_priority(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: exec
                pattern: "git *"
                action: deny
            """);
        var dot = tmp.resolve(".maplecode");
        Files.createDirectories(dot);
        Files.writeString(dot.resolve("permissions.yaml"), """
            rules:
              - tool: exec
                pattern: "ls *"
                action: deny
            """);
        Files.writeString(dot.resolve("permissions.local.yaml"), """
            rules:
              - tool: exec
                pattern: "git *"
                action: allow
            """);
        var rs = PermissionFileLoader.loadAll(tmp, user);
        assertEquals(3, rs.rules().size());
        assertEquals("git *", rs.rules().get(0).pattern());
        assertEquals(Rule.Action.DENY, rs.rules().get(0).action());
        assertEquals("git *", rs.rules().get(2).pattern());
        assertEquals(Rule.Action.ALLOW, rs.rules().get(2).action());
    }

    @Test
    void unknown_tool_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: Bash
                pattern: "git *"
                action: allow
            """);
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }

    @Test
    void invalid_action_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, """
            rules:
              - tool: exec
                pattern: "ls"
                action: maybe
            """);
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }

    @Test
    void malformed_yaml_throws_config_exception(@TempDir Path tmp) throws Exception {
        var user = tmp.resolve("user.yaml");
        Files.writeString(user, "rules: [unclosed");
        assertThrows(ConfigException.class,
            () -> PermissionFileLoader.loadAll(tmp, user));
    }
}
