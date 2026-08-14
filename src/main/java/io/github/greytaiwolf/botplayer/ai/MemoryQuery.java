package io.github.greytaiwolf.botplayer.ai;

/**
 * 发给可选记忆检索器的最小、带预算查询。
 */
public record MemoryQuery(
        String query,
        int maximumEntries,
        int maximumEstimatedTokens,
        int maximumCharacters) {
    public static final int MAX_QUERY_LENGTH = 8_192;
    public static final int MAX_ENTRIES = 32;

    public MemoryQuery {
        query = AiChecks.boundedText(
                query,
                "query",
                MAX_QUERY_LENGTH,
                false);
        ContextBudget.estimateTextTokens(query);
        if (maximumEntries < 1 || maximumEntries > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "maximumEntries must be between 1 and "
                            + MAX_ENTRIES);
        }
        if (maximumEstimatedTokens < 1
                || maximumEstimatedTokens > ContextBudget.MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumEstimatedTokens must be between 1 and "
                            + ContextBudget.MAX_INPUT_TOKENS);
        }
        if (maximumCharacters < 1
                || maximumCharacters > AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maximumCharacters must be between 1 and "
                            + AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS);
        }
    }

    /** 检索词可能来自聊天或世界文本，日志只保留长度与预算。 */
    @Override
    public String toString() {
        return "MemoryQuery[queryLength=" + query.length()
                + ", maximumEntries=" + maximumEntries
                + ", maximumEstimatedTokens=" + maximumEstimatedTokens
                + ", maximumCharacters=" + maximumCharacters + "]";
    }
}
