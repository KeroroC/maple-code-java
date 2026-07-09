package com.maplecode.command;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ReviewCommand implements Command {
    @Override public String name() { return "review"; }
    @Override public String description() { return "审查当前 Git 变更"; }
    @Override public String usage() { return "/review [额外关注点]"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    private static final int MAX_DIFF_LENGTH = 15000;

    @Override
    public void execute(String args, CommandContext ctx) {
        String diff = runGitDiff();
        if (diff == null || diff.isBlank()) {
            ctx.sendMessage("没有检测到代码变更。");
            return;
        }
        if (diff.length() > MAX_DIFF_LENGTH) {
            ctx.sendError("代码变更过大（" + diff.length() + " 字符），请先 commit 或指定文件审查。");
            return;
        }

        String focus = args.isEmpty() ? "（无）" : args;
        String prompt = String.format("""
            你是一个资深的代码审查员。请审查以下 Git 代码变更。

            审查维度：
            1. 正确性：逻辑是否有 Bug？边界条件是否处理？
            2. 安全性：是否存在注入风险、权限绕过、敏感信息泄露？
            3. 性能：是否存在不必要的循环、内存泄漏、N+1 查询？
            4. 可读性：命名是否规范？是否有必要的注释？

            额外关注点（来自用户指令）：
            %s

            代码变更内容：
            %s

            输出要求：
            - 如果没有严重问题，简短说明变更意图并给予肯定。
            - 如果发现问题，按严重程度（🔴 严重 / 🟡 警告 / 🔵 建议）列出。
            - 针对每个问题，给出具体的代码行号和修改建议。
            - 不要做泛泛的评价，必须基于 diff 内容。
            """, focus, diff);

        ctx.sendToAgent(prompt);
    }

    private String runGitDiff() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff");
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int exitCode = proc.waitFor();
            return exitCode == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
