package com.maplecode.command;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class StatusCommandTest {

    @Test
    void execute_printsStatus() {
        CommandContext ctx = mock(CommandContext.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.model()).thenReturn("claude-sonnet-4-20250514");
        when(ctx.getAgentConfig()).thenReturn(config);
        when(ctx.getTokenUsage()).thenReturn(new TokenUsage(1200, 200000, 0, 0));
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new StatusCommand().execute("", ctx);

        verify(ctx).sendMessage(argThat(s ->
            s.contains("claude-sonnet-4-20250514") &&
            s.contains("default") &&
            s.contains("normal")));
    }

    @Test
    void execute_nullTokenUsage_doesNotCrash() {
        CommandContext ctx = mock(CommandContext.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.model()).thenReturn("test-model");
        when(ctx.getAgentConfig()).thenReturn(config);
        when(ctx.getTokenUsage()).thenReturn(null);
        when(ctx.getPermissionMode()).thenReturn(PermissionMode.DEFAULT);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new StatusCommand().execute("", ctx);

        verify(ctx).sendMessage(anyString());
    }
}
