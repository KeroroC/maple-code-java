package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class DoCommandTest {

    @Test
    void execute_inPlanMode_extractsPlanAndRuns() {
        ChatSession session = new ChatSession();
        session.appendAssistant(List.of(new ContentBlock.TextBlock("step 1: do this\nstep 2: do that")));

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);
        when(ctx.getSession()).thenReturn(session);

        new DoCommand().execute("", ctx);

        verify(ctx).setPlanMode(PlanMode.NORMAL);
        verify(ctx).sendToAgent("step 1: do this\nstep 2: do that");
    }

    @Test
    void execute_notInPlanMode_sendsError() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.NORMAL);

        new DoCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
        verify(ctx, never()).sendToAgent(anyString());
    }

    @Test
    void execute_noAssistantText_sendsError() {
        ChatSession session = new ChatSession();

        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getPlanMode()).thenReturn(PlanMode.PLAN);
        when(ctx.getSession()).thenReturn(session);

        new DoCommand().execute("", ctx);

        verify(ctx).sendError(anyString());
    }
}
