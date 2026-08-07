package io.github.greytaiwolf.botplayer.ai;

/**
 * Provider-neutral token 计数；缓存命中属于输入 token 的子集。
 */
public record AiTokenUsage(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens) {
    public static final long MAX_TOKENS_PER_FIELD = 1_000_000_000L;

    public AiTokenUsage {
        requireTokenCount(inputTokens, "inputTokens");
        requireTokenCount(outputTokens, "outputTokens");
        requireTokenCount(cachedInputTokens, "cachedInputTokens");
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException(
                    "cachedInputTokens must not exceed inputTokens");
        }
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public static AiTokenUsage empty() {
        return new AiTokenUsage(0L, 0L, 0L);
    }

    private static void requireTokenCount(long value, String name) {
        if (value < 0L || value > MAX_TOKENS_PER_FIELD) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and "
                            + MAX_TOKENS_PER_FIELD);
        }
    }
}
