package com.maplecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.compact.CompactResult;
import com.maplecode.provider.StreamChunk.StopReason;
import com.maplecode.provider.TokenUsage;

import java.util.List;

/**
 * Agent 向外推送的事件类型。所有订阅者（UI / 日志 / 测试）通过
 * Consumer&lt;AgentEvent&gt; 接收。
 * <p>
 * sealed 强制每个 switch 穷尽；新增变体时所有订阅者编译失败即被强制更新。
 */
public sealed interface AgentEvent
    permits AgentEvent.TextDelta,
            AgentEvent.ThinkingDelta,
            AgentEvent.ToolCallStart,
            AgentEvent.ToolCallEnd,
            AgentEvent.ToolResult,
            AgentEvent.IterationStart,
            AgentEvent.IterationEnd,
            AgentEvent.BatchStart,
            AgentEvent.BatchEnd,
            AgentEvent.AgentStop,
            AgentEvent.CompactApplied {

    record TextDelta(String text) implements AgentEvent {}
    record ThinkingDelta(String text) implements AgentEvent {}

    record ToolCallStart(String id, String name, String argSummary) implements AgentEvent {}
    record ToolCallEnd(String id, String name, JsonNode input) implements AgentEvent {}

    record ToolResult(String toolUseId, String name, boolean isError, String content)
        implements AgentEvent {}

    record IterationStart(int iteration) implements AgentEvent {}
    record IterationEnd(int iteration, StopReason stopReason,
                        List<String> toolUseIds, TokenUsage usage) implements AgentEvent {}

    record BatchStart(int safeCount, int unsafeCount) implements AgentEvent {}
    record BatchEnd(int totalTools, int failedTools) implements AgentEvent {}

    record AgentStop(StopReason reason, String detail) implements AgentEvent {}

    record CompactApplied(CompactResult result) implements AgentEvent {}
}
