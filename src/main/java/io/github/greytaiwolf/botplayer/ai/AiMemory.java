package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可进入 P6 请求上下文的一条有界记忆。
 *
 * <p>当前 P6 只使用 {@link MemoryRetriever#empty()}。这个 DTO 预留给 P7 审核后的检索器，
 * 所有内容一律作为不可信 USER 数据包传递，不能授予权限或改变固定系统规则。当前
 * {@link AiMessage} 没有 tool_call_id，不能伪装为 Provider 协议中的 TOOL 响应。
 */
public record AiMemory(String sourceId, double confidence, String content) {
    public static final int MAX_SOURCE_ID_LENGTH = 64;
    public static final int MAX_CONTENT_LENGTH = 8_192;

    private static final Pattern SOURCE_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public AiMemory {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!SOURCE_ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException(
                    "sourceId must contain 1-"
                            + MAX_SOURCE_ID_LENGTH
                            + " lower-case source characters");
        }
        if (!Double.isFinite(confidence)
                || confidence < 0.0D
                || confidence > 1.0D) {
            throw new IllegalArgumentException(
                    "confidence must be finite and between 0 and 1");
        }
        if (confidence == 0.0D) {
            confidence = 0.0D;
        }
        content = AiChecks.boundedText(
                content,
                "content",
                MAX_CONTENT_LENGTH,
                false);
        ContextBudget.estimateTextTokens(content);
    }

    /**
     * 将记忆封装成 JSON 数据包，而不是让内容模拟 SYSTEM 指令。
     */
    public AiMessage asContextMessage() {
        StringBuilder formatted = new StringBuilder(
                content.length() + sourceId.length() + 128);
        formatted.append("{\"kind\":\"untrusted_memory\",\"source\":");
        appendJsonString(formatted, sourceId);
        formatted.append(",\"confidence\":").append(confidence);
        formatted.append(",\"content\":");
        appendJsonString(formatted, content);
        formatted.append('}');
        return new AiMessage(AiMessageRole.USER, formatted.toString());
    }

    /** P7 记忆内容和来源标识都不应出现在普通日志。 */
    @Override
    public String toString() {
        return "AiMemory[sourceIdLength=" + sourceId.length()
                + ", confidence=" + confidence
                + ", contentLength=" + content.length() + "]";
    }

    private static void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (current < 0x20) {
                        appendUnicodeEscape(output, current);
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder output, char value) {
        output.append("\\u");
        String hexadecimal = Integer.toHexString(value);
        for (int index = hexadecimal.length(); index < 4; index++) {
            output.append('0');
        }
        output.append(hexadecimal);
    }
}
