package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerAgentsTest {

    private static DynamicContext env() {
        return new DynamicContext(Path.of("/tmp"), false,
            "x", "x", "x",
            LocalDate.of(2026, 7, 8), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0));
    }

    @Test
    void cacheBoundaryFallsAfterAgentsMdOrAtCustomInstructionTail() {
        // 不带 customInstruction：cacheBoundary 应在 agents_md 段（最后一个 cacheable 段）
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, null, "rules");
        var blocks = new PromptAssembler().assemble(sections,
            new SectionContext(List.of(), env(), PlanMode.NORMAL));

        // 找到 cacheBoundary=true 的 block
        int boundaryIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).cacheBoundary()) {
                boundaryIdx = i;
                break;
            }
        }
        assertTrue(boundaryIdx >= 0, "至少应有一个 cacheBoundary=true 的 block");
        assertEquals("agents_md", blocks.get(boundaryIdx).kind(),
            "无 customInstruction 时 cacheBoundary 应落在 agents_md 末尾");
    }

    @Test
    void cacheBoundaryFallsAtCustomInstructionWhenPresent() {
        // 带 customInstruction：cacheBoundary 应在 custom_instruction（更后的 cacheable）
        var sections = DefaultSections.standard(env(), List.of(),
            PlanMode.NORMAL, "做且只做单元测试", "rules");
        var blocks = new PromptAssembler().assemble(sections,
            new SectionContext(List.of(), env(), PlanMode.NORMAL));

        int boundaryIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).cacheBoundary()) {
                boundaryIdx = i;
                break;
            }
        }
        assertTrue(boundaryIdx >= 0);
        assertEquals("custom_instruction", blocks.get(boundaryIdx).kind(),
            "有 customInstruction 时 cacheBoundary 应在它末尾（更后的 cacheable）");
    }
}
