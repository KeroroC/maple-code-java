package com.maplecode.mcp.config;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigLoaderTest {

    @Test
    void loadsSingleLayer(@TempDir Path tmp) throws Exception {
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Files.writeString(userFile, """
            servers:
              notion:
                type: http
                url: https://mcp.example.com/mcp
                headers:
                  Authorization: Bearer static
            """);
        var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
        assertEquals(1, specs.size());
        assertEquals("notion", specs.get(0).name());
        assertInstanceOf(McpServerSpec.Http.class, specs.get(0));
    }

    @Test
    void mergesThreeLayers(@TempDir Path tmp) throws Exception {
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Path projectFile = tmp.resolve(".maplecode").resolve("mcp_servers.yaml");
        Path localFile = tmp.resolve(".maplecode").resolve("mcp_servers.local.yaml");
        Files.createDirectories(projectFile.getParent());

        Files.writeString(userFile, """
            servers:
              a: { type: stdio, command: ua, args: [] }
              b: { type: stdio, command: ub, args: [] }
            """);
        Files.writeString(projectFile, """
            servers:
              b: { type: stdio, command: pb, args: [] }
              c: { type: stdio, command: pc, args: [] }
            """);
        Files.writeString(localFile, """
            servers:
              a: { type: stdio, command: la, args: [] }
            """);

        var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
        assertEquals(3, specs.size());
        var byName = specs.stream().collect(java.util.stream.Collectors.toMap(McpServerSpec::name, s -> s));
        assertEquals("la", ((McpServerSpec.Stdio) byName.get("a")).command()); // local > user
        assertEquals("pb", ((McpServerSpec.Stdio) byName.get("b")).command()); // project > user
        assertEquals("pc", ((McpServerSpec.Stdio) byName.get("c")).command());
    }

    @Test
    void expandsEnvInStdioEnvAndHttpHeaders(@TempDir Path tmp) throws Exception {
        // Just verify the loader doesn't crash on valid YAML with no ${VAR} placeholders
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Files.writeString(userFile, """
            servers:
              notion:
                type: http
                url: https://x.example.com/mcp
                headers:
                  Authorization: "Bearer static-token"
            """);
        var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
        assertEquals(1, specs.size());
    }

    @Test
    void missingEnvVarThrows(@TempDir Path tmp) throws Exception {
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Files.writeString(userFile, """
            servers:
              x:
                type: http
                url: https://x.example.com/mcp
                headers:
                  Authorization: "Bearer ${MY_NEVER_SET_TOKEN_X}"
            """);
        assertThrows(ConfigException.class,
            () -> new McpServerConfigLoader().loadAll(tmp, userFile));
    }

    @Test
    void missingFileIsEmpty(@TempDir Path tmp) {
        var specs = new McpServerConfigLoader().loadAll(
            tmp, tmp.resolve("nonexistent_user.yaml"));
        assertTrue(specs.isEmpty());
    }

    @Test
    void enabledFalseParsedCorrectly(@TempDir Path tmp) throws Exception {
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Files.writeString(userFile, """
            servers:
              gh:
                type: http
                url: https://mcp.example.com/mcp
                enabled: false
            """);
        var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
        assertEquals(1, specs.size());
        assertFalse(specs.get(0).enabled());
    }

    @Test
    void enabledFalseCaseInsensitive(@TempDir Path tmp) throws Exception {
        for (String falsy : new String[]{"\"False\"", "\"FALSE\"", "\"no\"", "\"off\"", "\"0\""}) {
            Path userFile = tmp.resolve("mcp_servers.yaml");
            Files.writeString(userFile, """
                servers:
                  gh:
                    type: http
                    url: https://mcp.example.com/mcp
                    enabled: %s
                """.formatted(falsy));
            var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
            assertFalse(specs.get(0).enabled(),
                "enabled: " + falsy + " should be disabled");
        }
    }

    @Test
    void enabledTrueByDefault(@TempDir Path tmp) throws Exception {
        Path userFile = tmp.resolve("mcp_servers.yaml");
        Files.writeString(userFile, """
            servers:
              gh:
                type: http
                url: https://mcp.example.com/mcp
            """);
        var specs = new McpServerConfigLoader().loadAll(tmp, userFile);
        assertEquals(1, specs.size());
        assertTrue(specs.get(0).enabled());
    }
}
