package com.maplecode.permission;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 权限检查：12 条硬编码正则黑名单规则，仅拦截 exec 工具。
 * 使用 {@link Pattern#find()} 做子串匹配。
 */
public final class BlacklistCheck implements PermissionCheck {

    private static final List<Rule> RULES = List.of(
        new Rule("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?-r?f?\\s+/(\\s|$|;|\\|)", "rm -rf against root"),
        new Rule(":\\(\\)\\s*\\{\\s*:\\|", "fork bomb"),
        new Rule("\\bmkfs\\.", "filesystem format"),
        new Rule("\\bdd\\s+.*if=/dev/(zero|urandom|random)", "dd disk overwrite"),
        new Rule(">\\s*/dev/sd[a-z]", "redirect to block device"),
        new Rule("\\bsudo\\b", "sudo not allowed"),
        new Rule("\\bchmod\\s+(-[a-zA-Z]*\\s+)*777\\b", "chmod 777"),
        new Rule("\\bcurl\\b.*\\|\\s*(ba)?sh\\b", "curl piped to shell"),
        new Rule("\\bwget\\b.*\\|\\s*(ba)?sh\\b", "wget piped to shell"),
        new Rule("\\bshutdown\\b|\\breboot\\b|\\bhalt\\b|\\bpoweroff\\b", "system power control"),
        new Rule("\\b:\\s*!\\s*\\w+\\s*/", "history expansion"),
        new Rule("\\beval\\b.*\\$\\(", "eval with command substitution")
    );

    private record Rule(String regex, String reason) {}

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!"exec".equals(req.toolName())) return Optional.empty();
        String command = req.args().path("command").asText();
        for (Rule r : RULES) {
            if (Pattern.compile(r.regex).matcher(command).find()) {
                return Optional.of(Decision.deny(
                    "blocked by built-in blacklist: " + r.reason));
            }
        }
        return Optional.empty();
    }
}
