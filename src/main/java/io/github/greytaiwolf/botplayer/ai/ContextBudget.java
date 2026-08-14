package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * Provider 无关的输入上下文硬预算。
 *
 * <p>没有绑定某个模型 tokenizer 时，估算值采用 UTF-8 字节数。任何常见的 byte-fallback
 * tokenizer 至多会把每个字节拆成一个 token，因此这比按字符或按词估算更保守；它不是账单
 * 计量值。{@code maximumInputTokens} 必须由上层在预留最大输出后传入；本 DTO 不猜测模型
 * 上下文窗口。遇到未配对 surrogate 会拒绝组装，而不是在不同 UTF-8 编码器之间产生歧义。
 */
public record ContextBudget(
        int maximumInputTokens,
        int maximumMessages,
        int maximumCharacters) {
    public static final int MAX_INPUT_TOKENS = 1_048_576;
    public static final int DEFAULT_MAXIMUM_INPUT_TOKENS = 32_768;
    public static final int DEFAULT_MAXIMUM_MESSAGES = 64;
    public static final int DEFAULT_MAXIMUM_CHARACTERS = 131_072;

    public ContextBudget {
        if (maximumInputTokens < 1
                || maximumInputTokens > MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumInputTokens must be between 1 and "
                            + MAX_INPUT_TOKENS);
        }
        if (maximumMessages < 1
                || maximumMessages > AiRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "maximumMessages must be between 1 and "
                            + AiRequest.MAX_MESSAGES);
        }
        if (maximumCharacters < 1
                || maximumCharacters > AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maximumCharacters must be between 1 and "
                            + AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS);
        }
    }

    public static ContextBudget defaults() {
        return new ContextBudget(
                DEFAULT_MAXIMUM_INPUT_TOKENS,
                DEFAULT_MAXIMUM_MESSAGES,
                DEFAULT_MAXIMUM_CHARACTERS);
    }

    /**
     * 返回文本的保守 token 上界，并验证其 Unicode 标量序列完整。
     */
    public static int estimateTextTokens(String value) {
        Objects.requireNonNull(value, "value");
        long estimatedTokens = 0L;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "value must not contain an unpaired surrogate");
                }
                codePoint = Character.toCodePoint(
                        current, value.charAt(index + 1));
                index += 2;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "value must not contain an unpaired surrogate");
            } else {
                codePoint = current;
                index++;
            }
            estimatedTokens += utf8Length(codePoint);
            if (estimatedTokens > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "value token estimate exceeds integer range");
            }
        }
        return (int) estimatedTokens;
    }

    public int estimateTokens(AiMessage message) {
        Objects.requireNonNull(message, "message");
        return estimateTextTokens(message.content());
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }
}
