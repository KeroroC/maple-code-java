package com.maplecode.command;

/**
 * 静态工具类，解析斜杠输入。
 */
public final class CommandParser {
    private CommandParser() {}

    /**
     * 判断输入是否以 / 开头且后面紧跟非空格字符。
     */
    public static boolean isCommand(String input) {
        if (input == null || input.length() < 2) return false;
        return input.charAt(0) == '/' && input.charAt(1) != ' ';
    }

    /**
     * 解析命令名：/ 后、首个空格前，转小写。
     * 输入 "/" → 返回 ""。
     * 输入 "/HELP args" → 返回 "help"。
     */
    public static String parseName(String input) {
        if (input == null || input.length() < 2) return "";
        int spaceIndex = input.indexOf(' ');
        String name = (spaceIndex < 0) ? input.substring(1) : input.substring(1, spaceIndex);
        return name.toLowerCase();
    }

    /**
     * 解析参数：首个空格之后的部分，已 trim。
     * 无参数返回 ""。
     */
    public static String parseArgs(String input) {
        int spaceIndex = input.indexOf(' ');
        if (spaceIndex < 0) return "";
        return input.substring(spaceIndex + 1).trim();
    }
}
