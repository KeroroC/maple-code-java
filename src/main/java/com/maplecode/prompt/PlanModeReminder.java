package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;

public final class PlanModeReminder {

    public static final int REPEAT_INTERVAL = 5;

    public enum Form { FULL, BRIEF, NONE }

    public record State(int fullInserts, int lastFullIteration) {
        public static State initial() { return new State(0, 0); }
        public State afterFull(int iter) {
            return new State(fullInserts + 1, iter);
        }
    }

    private PlanModeReminder() {}

    public static Form decide(PlanMode mode, State state, int iteration) {
        if (mode != PlanMode.PLAN) return Form.NONE;
        if (state.fullInserts() == 0) return Form.FULL;
        if (iteration - state.lastFullIteration > REPEAT_INTERVAL) return Form.FULL;
        return Form.BRIEF;
    }

    public static String renderFull() {
        return "规划模式已开启。禁止调用 write_file / edit_file / exec。\n"
             + "仅可使用 read_file / glob / grep。输出一份可执行计划，"
             + "列出每个步骤及对应工具调用，完成后停止。";
    }

    public static String renderBrief() {
        return "规划模式仍处于激活状态，仅可调用只读工具。";
    }
}
