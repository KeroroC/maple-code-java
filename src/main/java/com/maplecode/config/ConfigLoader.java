package com.maplecode.config;

import com.maplecode.error.ConfigException;
import com.maplecode.provider.ThinkingConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}");
    private static final int DEFAULT_CONNECT_SECONDS = 10;
    private static final int DEFAULT_READ_SECONDS = 60;

    private ConfigLoader() {}

    public static AppConfig load(Path path) {
        try (Reader r = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(r);
            if (!(raw instanceof Map<?, ?> map)) {
                throw new ConfigException("config root must be a mapping");
            }
            return parse(map);
        } catch (IOException e) {
            throw new ConfigException("failed to read config: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AppConfig parse(Map<?, ?> root) {
        String protocol = requireString(root, "protocol");
        String model = requireString(root, "model");
        String baseUrl = requireString(root, "base_url");
        String apiKey = expandEnv(requireString(root, "api_key"));

        String systemPrompt = optionalString(root, "system_prompt");
        ThinkingConfig thinking = parseThinking(optionalMap(root, "extended_thinking"));

        Map<?, ?> timeoutsMap = optionalMap(root, "timeouts");
        int connect = timeoutsMap != null && timeoutsMap.get("connect_seconds") instanceof Number n
            ? n.intValue() : DEFAULT_CONNECT_SECONDS;
        int read = timeoutsMap != null && timeoutsMap.get("read_seconds") instanceof Number n2
            ? n2.intValue() : DEFAULT_READ_SECONDS;

        return new AppConfig(protocol, model, baseUrl, apiKey, systemPrompt,
            thinking, new AppConfig.Timeouts(connect, read));
    }

    @SuppressWarnings("unchecked")
    private static ThinkingConfig parseThinking(Map<?, ?> m) {
        if (m == null) return null;
        String typeStr = optionalString(m, "type");
        if (typeStr == null) return null;
        ThinkingConfig.Type type = switch (typeStr) {
            case "adaptive" -> ThinkingConfig.Type.ADAPTIVE;
            case "enabled"  -> ThinkingConfig.Type.ENABLED;
            default -> throw new ConfigException(
                "extended_thinking.type must be 'adaptive' or 'enabled', got: " + typeStr);
        };
        Integer budget = optionalInt(m, "budget_tokens");
        ThinkingConfig.Effort effort = null;
        String effortStr = optionalString(m, "effort");
        if (effortStr != null) {
            effort = switch (effortStr) {
                case "low"    -> ThinkingConfig.Effort.LOW;
                case "medium" -> ThinkingConfig.Effort.MEDIUM;
                case "high"   -> ThinkingConfig.Effort.HIGH;
                default -> throw new ConfigException(
                    "extended_thinking.effort must be low|medium|high, got: " + effortStr);
            };
        }

        if (type == ThinkingConfig.Type.ENABLED) {
            System.err.println("warning: extended_thinking.type=enabled is deprecated for "
                + "Opus 4.6 / Sonnet 4.6 and returns HTTP 400 on Opus 4.7. "
                + "Prefer:\n"
                + "    type: adaptive\n"
                + "    effort: high");
        }

        // ThinkingConfig compact constructor handles all cross-field validation.
        return new ThinkingConfig(type, budget, effort);
    }

    private static String expandEnv(String value) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String var = matcher.group(1);
            String env = System.getenv(var);
            if (env == null) {
                throw new ConfigException("environment variable not set: " + var);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(env));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String requireString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException("missing required field: " + key);
        }
        return v.toString();
    }

    private static String optionalString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer optionalInt(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> optionalMap(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> map ? map : null;
    }
}
