package com.maplecode.command;

/**
 * 控制流异常。/exit 命令抛出，ReplLoop.run() 的 catch 块捕获后正常退出。
 */
public class ExitReplException extends RuntimeException {}
