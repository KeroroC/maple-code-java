package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CancelCommandTest {

    @Test
    void execute_cancelsAgent_resetsPlanMode_updatesStatusBar() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);

        new CancelCommand().execute("", ctx);

        verify(ctx).cancelCurrentAgentRun();
        verify(ctx).setPlanMode(PlanMode.NORMAL);
        verify(ctx).updateStatusBar();
    }
}
