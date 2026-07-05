package com.maplecode.prompt;

public interface PromptSection {
    String kind();
    String render(SectionContext ctx);
    default boolean cacheable() { return true; }
    default boolean enabled(SectionContext ctx) { return true; }
}
