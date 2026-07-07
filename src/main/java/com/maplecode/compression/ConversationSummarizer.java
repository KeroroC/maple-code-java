package com.maplecode.compression;

import com.maplecode.provider.*;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock.TextBlock;
import com.maplecode.prompt.SystemBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Second-layer compression: calls LLM to produce a 5-section structured summary,
 * strips scratchpad, computes recency tail, and appends a boundary message.
 */
public final class ConversationSummarizer {

    private static final Pattern SCRATCHPAD = Pattern.compile("(?s)<scratchpad>.*?</scratchpad>");

    private static final String SUMMARY_SYSTEM_PROMPT = """
        You are a conversation state compressor. Your job is to summarize a long
        agent conversation into structured sections so a future model turn can
        continue without re-reading the full transcript.

        STRICT RULES:
        - DO NOT call any tools. Your output must be pure text only.
        - DO NOT invent facts not present in the messages.
        - Preserve exact file paths, function names, error messages, and numeric
          values verbatim.
        - The user messages and assistant intent are sacrosanct — never paraphrase
          the user's original ask.

        PROCESS:
        1. First, write a private scratch analysis in <scratchpad>...</scratchpad>
           tags. This section will be DISCARDED before sending the summary to the
           model. Use it to list: what tools were called, what each tool returned,
           what errors occurred, what the user originally asked.
        2. After the scratchpad, write the formal summary with EXACTLY these 5
           sections, in this order, each starting with "## ":

           ## Intent
           The user's original goal, in one or two sentences. Quote the user's
           exact wording where possible.

           ## Decisions
           Key choices made during the conversation — files selected, approaches
           tried, trade-offs considered. Cite exact file paths.

           ## Open Questions
           Things the agent asked the user but did not get answered; ambiguities
           still unresolved; assumptions made without confirmation.

           ## State
           Current state of the work — what's done, what's in progress, what's
           broken. Include exact file paths of artifacts created/modified.

           ## Next Step
           The single most important concrete action the agent should take next.
           One sentence.

        OUTPUT FORMAT:
        - Output MUST start with <scratchpad>...</scratchpad>.
        - Output MUST then contain exactly 5 "## " sections in the order above.
        - Do not include any prose before the scratchpad or after the last section.
        """;

    private static final String BOUNDARY_MESSAGE = """
        [Compression boundary] Above messages are summarized to fit context window.
        Tool outputs marked "[Offloaded to ...]" were written to disk; to see exact
        code, file contents, or tool output, re-read from those absolute paths
        (they are stable for this session). Do NOT guess code or output from the
        summary — always re-read.
        """;

    private static final String[] REQUIRED_SECTIONS = {
        "## Intent", "## Decisions", "## Open Questions", "## State", "## Next Step"
    };

    private static final String[] REFUSAL_MARKERS = {
        "I can't", "I cannot", "I'm unable"
    };

    private static final int CHARS_PER_TOKEN = 4;

    private final LlmProvider provider;
    private final String mainModel;
    private final String summarizerModel;

    public ConversationSummarizer(LlmProvider provider, String mainModel, String summarizerModelOrNull) {
        this.provider = provider;
        this.mainModel = mainModel;
        this.summarizerModel = summarizerModelOrNull;
    }

    /**
     * Compress messages by generating a summary and computing recency tail.
     *
     * @return [summary USER msg] + recency tail + [boundary USER msg]
     */
    public List<ChatMessage> apply(List<ChatMessage> messages, CompressionConfig config) {
        String rawSummary = callSummarizer(messages);
        String summary = stripScratchpad(rawSummary);
        validateSummary(summary);

        int[] recencySplit = computeRecencySplit(messages, config);
        int tailStart = recencySplit[0];

        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage(Role.USER,
            List.of(new TextBlock("[Conversation summary]\n" + summary))));
        for (int i = tailStart; i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        result.add(new ChatMessage(Role.USER,
            List.of(new TextBlock(BOUNDARY_MESSAGE.strip()))));
        return result;
    }

    private String callSummarizer(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        String model = summarizerModel != null ? summarizerModel : mainModel;

        ChatRequest request = new ChatRequest(
            model,
            List.of(new SystemBlock(SUMMARY_SYSTEM_PROMPT, false, "summarizer")),
            messages,
            null,  // no thinking
            null   // no tools
        );

        provider.stream(request, chunk -> {
            if (chunk instanceof StreamChunk.TextDelta td) {
                sb.append(td.text());
            } else if (chunk instanceof StreamChunk.Error e) {
                throw new CompressionException("Summarizer provider error: " + e.code() + " - " + e.message());
            }
        });

        return sb.toString();
    }

    private String stripScratchpad(String raw) {
        return SCRATCHPAD.matcher(raw).replaceAll("").strip();
    }

    private void validateSummary(String summary) {
        // Check for refusal
        for (String marker : REFUSAL_MARKERS) {
            if (summary.contains(marker)) {
                throw new CompressionException("Summarizer refused: output contains '" + marker + "'");
            }
        }

        // Check required sections
        for (String section : REQUIRED_SECTIONS) {
            if (!summary.contains(section)) {
                throw new CompressionException("Summarizer output missing required section: " + section);
            }
        }
    }

    /**
     * Computes recency split: [0, tailStart) is compressed, [tailStart, size) is tail.
     *
     * @return int[]{startIdx, tailLen}
     */
    int[] computeRecencySplit(List<ChatMessage> messages, CompressionConfig config) {
        int size = messages.size();
        int tailTokenBudget = config.recencyTokens();
        int minMessages = config.recencyMinMessages();

        // Walk backwards from end, accumulate token estimate
        int tokens = 0;
        int tailLen = 0;
        for (int i = size - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(messages.get(i));
            if (tokens + msgTokens > tailTokenBudget && tailLen >= minMessages) {
                break;
            }
            tokens += msgTokens;
            tailLen++;
        }

        // Ensure minimum
        if (tailLen < minMessages) {
            tailLen = Math.min(minMessages, size);
        }

        int startIdx = size - tailLen;
        return new int[]{startIdx, tailLen};
    }

    private int estimateTokens(ChatMessage msg) {
        int chars = 0;
        for (ContentBlock block : msg.blocks()) {
            if (block instanceof TextBlock tb) {
                chars += tb.text().length();
            }
            // ToolUseBlock/ToolResultBlock: rough estimate
            chars += 100; // overhead per block
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }
}
