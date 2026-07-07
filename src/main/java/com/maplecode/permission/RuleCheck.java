package com.maplecode.permission;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Optional;

public final class RuleCheck implements PermissionCheck {

    private final RuleSet ruleSet;

    public RuleCheck(RuleSet ruleSet) { this.ruleSet = ruleSet; }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        String pattern = extractPattern(req);
        if (pattern == null) return Optional.empty();

        for (Rule r : ruleSet.rules()) {
            if (!r.toolName().equals(req.toolName())) continue;
            if (matches(req.toolName(), r.pattern(), pattern)) {
                return Optional.of(switch (r.action()) {
                    case ALLOW -> Decision.allow("rule match: " + r);
                    case DENY  -> Decision.deny("rule match: " + r);
                });
            }
        }
        return Optional.empty();
    }

    static String extractPattern(PermissionRequest req) {
        return switch (req.toolName()) {
            case "exec" -> req.args().path("command").asText();
            case "glob" -> req.args().path("pattern").asText();
            case "grep" -> req.args().has("path") ? req.args().get("path").asText() : ".";
            case "read_file", "write_file", "edit_file" -> req.args().path("path").asText();
            default -> null;
        };
    }

    static boolean matches(String tool, String rulePattern, String actual) {
        if (rulePattern.equals(actual)) return true;
        if (tool.equals("exec")) {
            return shellGlobMatch(rulePattern, actual);
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + rulePattern)
            .matches(Path.of(actual));
    }

    public static boolean shellGlobMatch(String pattern, String input) {
        String[] patTokens = pattern.split(" ", -1);
        String[] inTokens  = input.split(" ", -1);
        return matchTokens(patTokens, 0, inTokens, 0);
    }

    private static boolean matchTokens(String[] pat, int pi, String[] in, int ii) {
        while (pi < pat.length && ii < in.length) {
            if (pat[pi].equals("*")) {
                // * 匹配零个或多个输入 token
                pi++;
                while (ii <= in.length) {
                    if (matchTokens(pat, pi, in, ii)) return true;
                    ii++;
                }
                return false;
            } else if (pat[pi].equals("?")) {
                // ? 恰好匹配一个输入 token
                pi++;
                ii++;
            } else {
                // 字面量匹配
                if (!pat[pi].equals(in[ii])) return false;
                pi++;
                ii++;
            }
        }
        // 消耗 pattern 末尾的 *
        while (pi < pat.length && pat[pi].equals("*")) pi++;
        return pi == pat.length && ii == in.length;
    }
}
