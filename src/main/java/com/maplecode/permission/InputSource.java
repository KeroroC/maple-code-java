package com.maplecode.permission;

import java.util.List;

public interface InputSource {
    String readLine(String prompt);

    /**
     * 交互式选项选择。上下箭头导航，回车确认。
     * 默认实现退化为 readLine + 数字解析。
     */
    default int readChoice(String prompt, List<String> options) {
        System.out.println(prompt);
        for (int i = 0; i < options.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + options.get(i));
        }
        while (true) {
            String input = readLine("  请选择 [1-" + options.size() + "]: ").trim();
            try {
                int choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= options.size()) return choice - 1;
            } catch (NumberFormatException ignored) {}
            System.out.println("  无效输入，请输入 1-" + options.size());
        }
    }
}
