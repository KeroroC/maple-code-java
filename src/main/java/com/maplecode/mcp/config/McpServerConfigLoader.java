package com.maplecode.mcp.config;

import com.maplecode.config.ConfigLoader;
import com.maplecode.error.ConfigException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class McpServerConfigLoader {

    private static final String SERVERS_KEY = "servers";

    public List<McpServerSpec> loadAll(Path cwd, Path userFile) {
        Map<String, Map<String, Object>> merged = new HashMap<>();
        // 优先级：低 → 高；后写入覆盖先写入。
        mergeLayer(loadOrEmpty(userFile), merged);
        mergeLayer(loadOrEmpty(cwd.resolve(".maplecode/mcp_servers.yaml")), merged);
        mergeLayer(loadOrEmpty(cwd.resolve(".maplecode/mcp_servers.local.yaml")), merged);

        List<McpServerSpec> out = new ArrayList<>();
        for (var e : merged.entrySet()) {
            out.add(parseEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Map<String, Map<String, Object>> loadOrEmpty(Path p) {
        if (!Files.exists(p)) return Map.of();
        try (Reader r = Files.newBufferedReader(p)) {
            Object raw = new Yaml().load(r);
            if (raw == null) return Map.of();
            if (!(raw instanceof Map<?, ?> root))
                throw new ConfigException("mcp config root must be mapping: " + p);
            Object servers = root.get(SERVERS_KEY);
            if (servers == null) return Map.of();
            if (!(servers instanceof Map<?, ?> sm))
                throw new ConfigException("mcp servers must be a map: " + p);
            Map<String, Map<String, Object>> result = new HashMap<>();
            for (var e : sm.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> sv))
                    throw new ConfigException("server '" + e.getKey() + "' must be a map");
                Map<String, Object> casted = new HashMap<>();
                for (var k : sv.entrySet()) {
                    casted.put(k.getKey().toString(), k.getValue());
                }
                result.put(e.getKey().toString(), casted);
            }
            return result;
        } catch (IOException e) {
            throw new ConfigException("failed to read mcp config: " + p, e);
        }
    }

    private static void mergeLayer(Map<String, Map<String, Object>> layer,
                                   Map<String, Map<String, Object>> into) {
        for (var e : layer.entrySet()) {
            into.merge(e.getKey(), new HashMap<>(e.getValue()),
                (oldV, newV) -> {
                    var merged = new HashMap<>(oldV);
                    merged.putAll(newV);
                    return merged;
                });
        }
    }

    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
        "type", "command", "args", "env", "url", "headers", "enabled");

    private static McpServerSpec parseEntry(String name, Map<String, Object> v) {
        warnUnknownKeys(name, v);
        boolean enabled = ConfigLoader.isEnabled(v.get("enabled"));
        String type = stringOrThrow(name, v, "type");
        switch (type) {
            case "stdio" -> {
                String command = stringOrThrow(name, v, "command");
                List<String> args = listOrEmpty(v.get("args"), name, "args");
                Map<String, String> env = stringMapOrEmpty(v.get("env"), name, "env");
                Map<String, String> expanded = new HashMap<>();
                env.forEach((k, val) -> expanded.put(k, ConfigLoader.expandEnv(val)));
                return new McpServerSpec.Stdio(name, command, args, expanded, enabled);
            }
            case "http" -> {
                String url = stringOrThrow(name, v, "url");
                Map<String, String> headers = stringMapOrEmpty(v.get("headers"), name, "headers");
                Map<String, String> expanded = new HashMap<>();
                headers.forEach((k, val) -> expanded.put(k, ConfigLoader.expandEnv(val)));
                return new McpServerSpec.Http(name, url, expanded, enabled);
            }
            default -> throw new ConfigException(
                "mcp server '" + name + "': type must be stdio|http, got " + type);
        }
    }

    private static void warnUnknownKeys(String name, Map<String, Object> v) {
        for (String key : v.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                System.err.println("[mcp:" + name + "] WARN: unknown config key '" + key + "', ignored");
            }
        }
    }

    private static String stringOrThrow(String name, Map<String, Object> v, String key) {
        Object val = v.get(key);
        if (val == null)
            throw new ConfigException("mcp server '" + name + "': missing '" + key + "'");
        return val.toString();
    }

    private static List<String> listOrEmpty(Object o, String serverName, String key) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (var x : l) out.add(x.toString());
            return out;
        }
        throw new ConfigException("mcp server '" + serverName + "': '" + key + "' must be a list");
    }

    private static Map<String, String> stringMapOrEmpty(Object o, String serverName, String key) {
        if (o == null) return Map.of();
        if (o instanceof Map<?, ?> m) {
            Map<String, String> out = new HashMap<>();
            for (var e : m.entrySet()) out.put(e.getKey().toString(), e.getValue().toString());
            return out;
        }
        throw new ConfigException("mcp server '" + serverName + "': '" + key + "' must be a map of strings");
    }
}
