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
        rule("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?-r?f?\\s+/(\\s|$|;|\\|)", "删除根目录"),
        rule(":\\(\\)\\s*\\{\\s*:\\|", "fork 炸弹"),
        rule("\\bmkfs\\.", "格式化文件系统"),
        rule("\\bdd\\s+.*if=/dev/(zero|urandom|random)", "dd 覆写磁盘"),
        rule(">\\s*/dev/sd[a-z]", "重定向到块设备"),
        rule("\\bsudo\\b", "不允许使用 sudo"),
        rule("\\bchmod\\s+(-[a-zA-Z]*\\s+)*777\\b", "chmod 777"),
        rule("\\bcurl\\b.*\\|\\s*(ba)?sh\\b", "curl 管道到 shell"),
        rule("\\bwget\\b.*\\|\\s*(ba)?sh\\b", "wget 管道到 shell"),
        rule("\\bshutdown\\b|\\breboot\\b|\\bhalt\\b|\\bpoweroff\\b", "系统关机/重启"),
        rule("\\b:\\s*!\\s*\\w+\\s*/", "history 展开"),
        rule("\\beval\\b.*\\$\\(", "eval 命令替换")
    );

    private record Rule(Pattern pattern, String reason) {}

    private static Rule rule(String regex, String reason) {
        return new Rule(Pattern.compile(regex), reason);
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!"exec".equals(req.toolName())) return Optional.empty();
        String command = req.args().path("command").asText();
        for (Rule r : RULES) {
            if (r.pattern().matcher(command).find()) {
                return Optional.of(Decision.deny(
                    "内置黑名单拦截: " + r.reason));
            }
        }
        return Optional.empty();
    }
}
