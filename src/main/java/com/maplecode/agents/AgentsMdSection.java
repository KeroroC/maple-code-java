package com.maplecode.agents;

import com.maplecode.prompt.PromptSection;
import com.maplecode.prompt.SectionContext;

public final class AgentsMdSection implements PromptSection {

    private final String content;

    public AgentsMdSection(String content) {
        this.content = content == null ? "" : content;
    }

    @Override
    public String kind() {
        return "agents_md";
    }

    @Override
    public String render(SectionContext ctx) {
        return content;
    }

    @Override
    public boolean cacheable() {
        return true;
    }
}
