package com.maplecode.prompt;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerTest {

    private static DynamicContext env() {
        return new DynamicContext(java.nio.file.Path.of("/tmp"), false,
            "x", "x", "x", java.time.LocalDate.now(), java.time.LocalDate.now().getDayOfWeek(),
            java.time.LocalTime.now());
    }

    private static PromptSection fixed(String kind, boolean cacheable) {
        return new PromptSection() {
            @Override public String kind() { return kind; }
            @Override public String render(SectionContext ctx) { return "T:" + kind; }
            @Override public boolean cacheable() { return cacheable; }
        };
    }

    @Test
    void lastCacheableSectionGetsBoundaryTrue() {
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", true), fixed("b", false), fixed("c", true)),
            ctx);
        assertEquals(3, blocks.size());
        assertFalse(blocks.get(0).cacheBoundary());
        assertFalse(blocks.get(1).cacheBoundary());
        assertTrue(blocks.get(2).cacheBoundary());
    }

    @Test
    void noCacheableSectionsMeansNoBoundary() {
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", false), fixed("b", false)), ctx);
        assertTrue(blocks.stream().noneMatch(SystemBlock::cacheBoundary));
    }

    @Test
    void disabledSectionIsSkipped() {
        var disabled = new PromptSection() {
            @Override public String kind() { return "d"; }
            @Override public String render(SectionContext ctx) { return "X"; }
            @Override public boolean enabled(SectionContext ctx) { return false; }
        };
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(
            List.of(fixed("a", true), disabled, fixed("c", true)), ctx);
        assertEquals(2, blocks.size());
    }

    @Test
    void blankRenderIsSkipped() {
        var blank = new PromptSection() {
            @Override public String kind() { return "b"; }
            @Override public String render(SectionContext ctx) { return ""; }
        };
        var a = new PromptAssembler();
        var ctx = new SectionContext(List.of(), env(), PlanMode.NORMAL);
        var blocks = a.assemble(List.of(fixed("a", true), blank, fixed("c", true)), ctx);
        assertEquals(2, blocks.size());
        assertEquals("a", blocks.get(0).kind());
        assertEquals("c", blocks.get(1).kind());
    }

    @Test
    void attachReminderAppendsUserMessageAndDoesNotMutateOriginal() {
        var a = new PromptAssembler();
        var req = new ChatRequest("m", List.of(), new ArrayList<>(), null, List.of());
        ChatRequest out = a.attachReminder(req, "plan mode active");
        assertTrue(req.messages().isEmpty());
        assertEquals(1, out.messages().size());
        ChatMessage m = out.messages().get(0);
        assertEquals(Role.USER, m.role());
        assertInstanceOf(ContentBlock.TextBlock.class, m.blocks().get(0));
        String text = ((ContentBlock.TextBlock) m.blocks().get(0)).text();
        assertTrue(text.contains("<system-reminder>"));
        assertTrue(text.contains("plan mode active"));
        assertTrue(text.contains("</system-reminder>"));
    }

    @Test
    void attachReminderNoOpWhenBodyBlank() {
        var a = new PromptAssembler();
        var req = new ChatRequest("m", List.of(new SystemBlock("x", false, "k")),
            new ArrayList<>(), null, List.of());
        ChatRequest out = a.attachReminder(req, "");
        assertSame(req, out);
    }
}
