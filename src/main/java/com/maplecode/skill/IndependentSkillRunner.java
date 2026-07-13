package com.maplecode.skill;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.AgentEvent;
import com.maplecode.agent.AgentLoop;
import com.maplecode.agent.PlanMode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.LlmProvider;
import com.maplecode.session.ChatSession;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 独立执行模式的 Skill 运行器。
 *
 * <p>创建独立的 AgentLoop 实例来执行 Skill，执行完后返回结果。
 */
public class IndependentSkillRunner {

    private final LlmProvider provider;
    private final ToolRegistry originalRegistry;
    private final String defaultModel;

    public IndependentSkillRunner(LlmProvider provider, ToolRegistry originalRegistry, String defaultModel) {
        this.provider = provider;
        this.originalRegistry = originalRegistry;
        this.defaultModel = defaultModel;
    }

    /**
     * 执行独立模式的 Skill。
     *
     * @param skill        Skill 定义
     * @param input        用户输入
     * @param mainSession  主对话会话（用于提取历史）
     * @return 执行结果文本
     */
    public String run(SkillDef skill, String input, ChatSession mainSession) {
        // 1. 过滤工具白名单
        ToolRegistry filtered = filterTools(originalRegistry, skill.tools());

        // 2. 构建系统提示词
        List<String> systemBlocks = buildSkillSystemBlocks(skill, input);

        // 3. 确定使用的模型
        String model = skill.model() != null ? skill.model() : defaultModel;

        // 4. 创建独立的 AgentConfig
        AgentConfig config = new AgentConfig(
            model,
            systemBlocks.stream().map(text -> new com.maplecode.prompt.SystemBlock(text, false, "skill")).toList(),
            null,
            10,  // Skill 执行通常不需要太多轮
            3,
            PlanMode.NORMAL,
            com.maplecode.prompt.PlanModeReminder.State.initial()
        );

        // 5. 创建独立的 ChatSession，可选择带多少历史
        ChatSession independentSession = new ChatSession();
        if (skill.historyDepth() > 0) {
            List<ChatMessage> recent = mainSession.recentMessages(skill.historyDepth());
            for (ChatMessage msg : recent) {
                if (msg.role() == ChatMessage.Role.USER) {
                    independentSession.appendUser(msg.blocks());
                } else if (msg.role() == ChatMessage.Role.ASSISTANT) {
                    independentSession.appendAssistant(msg.blocks());
                }
            }
        }

        // 6. 创建 ToolExecutor（无权限检查）
        ToolExecutor executor = new ToolExecutor(filtered);

        // 7. 创建结果收集器
        AtomicReference<String> result = new AtomicReference<>("");
        Consumer<AgentEvent> sink = event -> {
            if (event instanceof AgentEvent.TextDelta textDelta) {
                result.updateAndGet(current -> current + textDelta.text());
            }
        };

        // 8. 创建独立的 AgentLoop
        AgentLoop loop = new AgentLoop(provider, filtered, executor,
                                       independentSession, config, usage -> {}, null);

        // 9. 执行
        try {
            loop.run(input, sink);
        } catch (Exception e) {
            return "Skill 执行失败: " + e.getMessage();
        }

        // 10. 返回结果
        return result.get();
    }

    /**
     * 根据工具白名单过滤工具。
     */
    private ToolRegistry filterTools(ToolRegistry registry, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return registry;
        }

        Set<String> allowedTools = Set.copyOf(whitelist);
        List<Tool> filtered = registry.all().stream()
            .filter(tool -> allowedTools.contains(tool.name()))
            .toList();

        return new ToolRegistry(filtered);
    }

    /**
     * 构建 Skill 的系统提示词。
     */
    private List<String> buildSkillSystemBlocks(SkillDef skill, String input) {
        List<String> blocks = new ArrayList<>();

        // 基础身份
        blocks.add("你是一个执行特定 Skill 的 AI 助手。请严格按照以下指令执行任务。");

        // 工具白名单信息
        if (skill.tools() != null && !skill.tools().isEmpty()) {
            blocks.add("**可用工具**: " + String.join(", ", skill.tools()) + "\n" +
                       "**重要**: 只能使用上述列出的工具，不要尝试使用其他工具。");
        }

        // Skill 指令
        String rendered = skill.body().replace("{{input}}", input);
        blocks.add(rendered);

        return blocks;
    }
}
