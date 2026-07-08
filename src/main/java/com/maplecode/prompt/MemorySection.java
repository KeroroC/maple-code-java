package com.maplecode.prompt;

/**
 * 长期记忆 prompt section。content 为空时 enabled()=false，不注入。
 */
public final class MemorySection implements PromptSection {

    private final String content;

    public MemorySection(String content) {
        this.content = content == null ? "" : content;
    }

    @Override
    public String kind() {
        return "long_term_memory";
    }

    @Override
    public String render(SectionContext ctx) {
        return content;
    }

    @Override
    public boolean cacheable() {
        return true;
    }

    @Override
    public boolean enabled(SectionContext ctx) {
        return !content.isBlank();
    }
}
