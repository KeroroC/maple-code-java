package com.maplecode.prompt;

/**
 * 系统提示词部分接口，用于定义系统提示词的各个组成部分。
 * <p>
 * 每个实现代表系统提示词中的一个逻辑部分（如身份、约束、工具使用说明等），
 * 可以根据上下文动态生成内容，并支持缓存和条件启用。
 */
public interface PromptSection {
    
    /**
     * 返回此部分的类型标识符。
     * <p>
     * 用于唯一标识提示词部分的类型，例如 "identity"、"constraints"、"tool_usage" 等。
     * 主要用于调试、日志记录和可能的缓存键生成。
     * 
     * @return 此部分的类型标识字符串
     */
    String kind();
    
    /**
     * 根据上下文渲染并返回此部分的文本内容。
     * <p>
     * 此方法负责生成实际的提示词文本。实现可以根据 {@link SectionContext} 中的信息
     * （如可用工具、环境信息、计划模式等）动态生成内容。
     * 
     * @param ctx 包含渲染所需上下文信息的对象
     * @return 此部分渲染后的文本内容
     */
    String render(SectionContext ctx);
    
    /**
     * 指示此部分是否可以被缓存。
     * <p>
     * 如果返回 {@code true}，则在相同的上下文条件下，此部分的渲染结果可以被缓存
     * 以避免重复计算。默认返回 {@code true}。
     * <p>
     * 对于内容会随时间或环境变化的部分（如包含当前时间、日期的环境信息），
     * 应返回 {@code false} 以确保每次都能获取最新内容。
     * 
     * @return 如果此部分可以被缓存则返回 {@code true}，否则返回 {@code false}
     */
    default boolean cacheable() { return true; }
    
    /**
     * 指示此部分在当前上下文下是否应该被启用。
     * <p>
     * 允许根据上下文条件动态地启用或禁用某些提示词部分。例如：
     * <ul>
     *   <li>某些功能尚未实现时可以暂时禁用相关部分</li>
     *   <li>用户自定义指令为空时可以禁用相应部分</li>
     *   <li>特定模式下可能需要禁用某些部分</li>
     * </ul>
     * 
     * 默认返回 {@code true}，表示在所有情况下都启用。
     * 
     * @param ctx 用于判断是否启用此部分的上下文信息
     * @return 如果此部分在当前上下文下应该被启用则返回 {@code true}，否则返回 {@code false}
     */
    default boolean enabled(SectionContext ctx) { return true; }
}
