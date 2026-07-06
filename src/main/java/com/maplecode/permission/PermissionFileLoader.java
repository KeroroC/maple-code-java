package com.maplecode.permission;

import com.maplecode.error.ConfigException;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PermissionFileLoader {

    private static final Set<String> KNOWN_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "exec", "glob", "grep");

    private PermissionFileLoader() {}

    public static RuleSet loadAll(Path projectRoot, Path userFile) {
        Path projectFile  = projectRoot.resolve(".maplecode/permissions.yaml");
        Path projectLocal = projectRoot.resolve(".maplecode/permissions.local.yaml");

        List<Rule> merged = new ArrayList<>();
        merged.addAll(parseFile(userFile).rules().stream().map(PermissionFileLoader::toRule).toList());
        merged.addAll(parseFile(projectFile).rules().stream().map(PermissionFileLoader::toRule).toList());
        merged.addAll(parseFile(projectLocal).rules().stream().map(PermissionFileLoader::toRule).toList());

        for (Rule r : merged) {
            if (!KNOWN_TOOLS.contains(r.toolName())) {
                throw new ConfigException("permission rule references unknown tool: " + r.toolName());
            }
        }
        return new RuleSet(merged);
    }

    static Rule toRule(PermissionFile.RuleEntry e) {
        Rule.Action act;
        try {
            act = Rule.Action.valueOf(e.action().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ConfigException("invalid rule action: " + e.action());
        }
        return new Rule(e.tool(), e.pattern(), act);
    }

    static PermissionFile parseFile(Path path) {
        if (!Files.exists(path)) return new PermissionFile(List.of());
        try (Reader r = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(r);
            if (raw == null) return new PermissionFile(List.of());
            if (!(raw instanceof Map<?, ?> root)) {
                throw new ConfigException("permission file root must be a mapping: " + path);
            }
            Object rulesObj = root.get("rules");
            if (rulesObj == null) return new PermissionFile(List.of());
            if (!(rulesObj instanceof List<?> rulesList)) {
                throw new ConfigException("'rules' must be a list: " + path);
            }
            List<PermissionFile.RuleEntry> entries = new ArrayList<>();
            for (Object item : rulesList) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new ConfigException("rule entry must be a mapping: " + path);
                }
                Object tool = m.get("tool");
                Object pattern = m.get("pattern");
                Object action = m.get("action");
                if (tool == null || pattern == null || action == null) {
                    throw new ConfigException("rule entry missing tool/pattern/action: " + path);
                }
                entries.add(new PermissionFile.RuleEntry(
                    tool.toString(), pattern.toString(), action.toString()));
            }
            return new PermissionFile(entries);
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("failed to read permission file: " + path, e);
        }
    }
}
