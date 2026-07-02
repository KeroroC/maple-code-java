package com.maplecode.provider;

/**
 * 流式响应中所有可能的事件类型。
 *
 * sealed interface 是 Java 17 引入的特性，通过 permits 关键字
 * 明确列出所有允许实现该接口的子类，编译器据此保证：
 * 1. 类型层次是封闭的——不允许其他类意外实现该接口；
 * 2. switch 表达式可以做到穷尽检查——新增子类时未处理的 switch 会编译报错。
 */
public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR }
}
