package com.maplecode.prompt;

import com.maplecode.agent.PlanMode;
import com.maplecode.tool.Tool;
import java.util.List;

public record SectionContext(
    List<Tool> tools,
    DynamicContext env,
    PlanMode planMode
) {}
