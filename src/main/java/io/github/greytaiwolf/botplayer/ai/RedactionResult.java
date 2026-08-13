package io.github.greytaiwolf.botplayer.ai;

/**
 * 脱敏后的文本及替换次数；结果从不保存被替换的原始值。
 */
public record RedactionResult(String text, int redactionCount) {
    public RedactionResult {
        text = AiChecks.boundedText(
                text,
                "text",
                RedactionFilter.MAX_OUTPUT_CHARACTERS,
                true);
        if (redactionCount < 0) {
            throw new IllegalArgumentException(
                    "redactionCount must not be negative");
        }
    }

    /** 即使已经脱敏，剩余文本也可能是私有上下文，不能默认写出。 */
    @Override
    public String toString() {
        return "RedactionResult[textLength=" + text.length()
                + ", redactionCount=" + redactionCount + "]";
    }
}
