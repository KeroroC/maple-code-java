package com.maplecode.provider;

/** Provider 返回的 token 用量统计。Anthropic / OpenAI 两边共用。 */
public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int cacheCreationTokens,
    int cacheReadTokens
) {
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, 0, 0);
    }
}
