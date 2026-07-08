package com.maplecode.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IncludeResolver {

    private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}]+)\\}\\}");

    private IncludeResolver() {}

    /**
     * 递归展开 content 中的 {{include:path}} 占位。
     *
     * @param content 原始内容
     * @param baseDir 项目根目录（用于解析相对路径 + 路径逃逸检查）
     * @param visited 已访问的绝对路径集合（防环路）
     * @param depth 当前递归深度（0 起步）
     * @param limits 阈值（maxDepth 等）
     * @return 展开后的内容；失败时占位保留原文
     */
    public static String resolve(String content, Path baseDir, Set<Path> visited,
                                 int depth, IncludeLimits limits) {
        return resolveInternal(content, baseDir, baseDir, visited, depth, limits);
    }

    private static String resolveInternal(String content, Path rootDir, Path currentDir,
                                          Set<Path> visited, int depth, IncludeLimits limits) {
        if (depth >= limits.maxDepth()) {
            return content;  // 深度超限，整段不展开；占位保留在 content 里
        }
        Matcher m = INCLUDE.matcher(content);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            out.append(content, lastEnd, m.start());
            String includePath = m.group(1).trim();
            Path target = currentDir.resolve(includePath).normalize();
            String lineInfo = "line " + lineOf(content, m.start());

            if (!target.startsWith(rootDir)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + currentDir + ":" + lineInfo + ": path escapes base directory");
                out.append(m.group(0));
            } else if (visited.contains(target)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + currentDir + ":" + lineInfo + ": cycle detected (already visited " + target + ")");
                out.append(m.group(0));
            } else if (!Files.exists(target) || !Files.isRegularFile(target)) {
                System.err.println("[agents-md] {{include: " + includePath + "}} at "
                    + currentDir + ":" + lineInfo + ": file not found");
                out.append(m.group(0));
            } else {
                try {
                    String subContent = Files.readString(target);
                    Set<Path> nextVisited = new HashSet<>(visited);
                    nextVisited.add(target);
                    String expanded = resolveInternal(subContent, rootDir, target.getParent(),
                        nextVisited, depth + 1, limits);
                    out.append(expanded);
                } catch (IOException e) {
                    System.err.println("[agents-md] {{include: " + includePath + "}} at "
                        + currentDir + ":" + lineInfo + ": read failed: " + e.getMessage());
                    out.append(m.group(0));
                }
            }
            lastEnd = m.end();
        }
        out.append(content, lastEnd, content.length());
        return out.toString();
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
