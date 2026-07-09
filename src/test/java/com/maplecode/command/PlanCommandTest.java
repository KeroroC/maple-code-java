package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PlanCommandTest {

    @Test
    void execute_setsPlanModeAndSendsToAgent() {
        CommandContext ctx = mock(CommandContext.class);

        new PlanCommand().execute("分析这段代码", ctx);

        verify(ctx).setPlanMode(PlanMode.PLAN);
        verify(ctx).sendToAgent("分析这段代码");
    }

    @Test
    void execute_noArgs_sendsError() {
        CommandContext ctx = mock(CommandContext.class);

        new PlanCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
        verify(ctx, never()).sendToAgent(anyString());
    }
}
