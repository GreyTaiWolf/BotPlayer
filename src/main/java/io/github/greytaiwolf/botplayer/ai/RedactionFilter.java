package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * 面向普通日志的保守脱敏器。
 *
 * <p>它处理 Authorization/Bearer、常见凭据字段、URL query 参数、`sk-` 类密钥和 JWT。
 * 超过上限的文本整体拒绝展示而不是截断，因为截断可能保留末尾 secret。它不是允许记录
 * prompt 或原始 Provider 响应的许可；调用方仍应只记录结构化、安全字段。
 */
public final class RedactionFilter {
    public static final int MAX_INPUT_CHARACTERS = 262_144;
    public static final int MAX_OUTPUT_CHARACTERS = 262_144;
    public static final String REDACTED = "[REDACTED]";
    public static final String OVERSIZED = "[REDACTED_OVERSIZE]";

    private static final String[] SENSITIVE_FIELDS = {
            "proxy-authorization",
            "authorization",
            "x-api-key",
            "api-key",
            "api_key",
            "apikey",
            "access_token",
            "refresh_token",
            "credential",
            "password",
            "secret",
            "token",
            "key"
    };

    private RedactionFilter() {
        throw new AssertionError("No instances");
    }

    /**
     * 以有限、确定性的方式替换文本中的疑似 secret。
     */
    public static RedactionResult redact(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > MAX_INPUT_CHARACTERS) {
            return new RedactionResult(OVERSIZED, 1);
        }
        StringBuilder output = new StringBuilder(source.length());
        int redactions = 0;
        int index = 0;
        while (index < source.length()) {
            Replacement replacement = findReplacement(source, index);
            if (replacement == null) {
                if (wouldExceedOutput(output.length(), 1, 0)) {
                    return new RedactionResult(OVERSIZED, Math.max(1, redactions));
                }
                output.append(source.charAt(index));
                index++;
                continue;
            }
            int preservedLength = replacement.start() - index;
            if (wouldExceedOutput(
                    output.length(), preservedLength,
                    replacement.replacement().length())) {
                return new RedactionResult(OVERSIZED, redactions + 1);
            }
            output.append(source, index, replacement.start());
            output.append(replacement.replacement());
            index = replacement.endExclusive();
            redactions++;
        }
        return new RedactionResult(output.toString(), redactions);
    }

    private static boolean wouldExceedOutput(
            int currentLength, int preservedLength, int replacementLength) {
        long nextLength = (long) currentLength
                + preservedLength
                + replacementLength;
        return nextLength > MAX_OUTPUT_CHARACTERS;
    }

    private static Replacement findReplacement(String source, int index) {
        Replacement bearer = findBearerOrBasic(source, index);
        if (bearer != null) {
            return bearer;
        }
        Replacement assignment = findSensitiveAssignment(source, index);
        if (assignment != null) {
            return assignment;
        }
        return findStandaloneSecret(source, index);
    }

    private static Replacement findBearerOrBasic(String source, int index) {
        int keywordLength = matchKeyword(source, index, "bearer");
        if (keywordLength == 0) {
            keywordLength = matchKeyword(source, index, "basic");
        }
        if (keywordLength == 0 || !isBoundaryBefore(source, index)) {
            return null;
        }
        int valueStart = skipWhitespace(source, index + keywordLength);
        if (valueStart == index + keywordLength
                || valueStart >= source.length()) {
            return null;
        }
        int valueEnd = consumeSensitiveValue(source, valueStart);
        if (valueEnd == valueStart) {
            return null;
        }
        return new Replacement(valueStart, valueEnd, REDACTED);
    }

    private static Replacement findSensitiveAssignment(
            String source, int index) {
        if (!isBoundaryBefore(source, index)) {
            return null;
        }
        String field = matchingSensitiveField(source, index);
        if (field == null) {
            return null;
        }
        int afterField = index + field.length();
        if (afterField < source.length()
                && isQuote(source.charAt(afterField))) {
            afterField++;
        }
        afterField = skipWhitespace(source, afterField);
        if (afterField >= source.length()
                || (source.charAt(afterField) != ':'
                && source.charAt(afterField) != '=')) {
            return null;
        }
        int valueStart = skipWhitespace(source, afterField + 1);
        if (valueStart >= source.length()
                || isSensitiveValueTerminator(source.charAt(valueStart))) {
            return null;
        }
        char first = source.charAt(valueStart);
        if (isQuote(first)) {
            int contentStart = valueStart + 1;
            int valueEnd = consumeQuotedValue(source, valueStart, first);
            if (valueEnd <= contentStart) {
                return null;
            }
            boolean closed = valueEnd <= source.length()
                    && source.charAt(valueEnd - 1) == first;
            int contentEnd = closed ? valueEnd - 1 : valueEnd;
            return new Replacement(contentStart, contentEnd, REDACTED);
        }
        int valueEnd = consumeSensitiveValue(source, valueStart);
        if (valueEnd == valueStart) {
            return null;
        }
        return new Replacement(valueStart, valueEnd, REDACTED);
    }

    private static Replacement findStandaloneSecret(String source, int index) {
        if (!isBoundaryBefore(source, index)) {
            return null;
        }
        if (matchesIgnoreCase(source, index, "sk-")
                || matchesIgnoreCase(source, index, "rk-")
                || matchesIgnoreCase(source, index, "ghp_")
                || matchesIgnoreCase(source, index, "xoxb-")) {
            int end = consumeSecretToken(source, index);
            if (end - index >= 12) {
                return new Replacement(index, end, REDACTED);
            }
        }
        if (source.startsWith("eyJ", index)) {
            int end = consumeSecretToken(source, index);
            if (end - index >= 20
                    && countCharacter(source, index, end, '.') >= 2) {
                return new Replacement(index, end, REDACTED);
            }
        }
        return null;
    }

    private static String matchingSensitiveField(String source, int index) {
        for (String field : SENSITIVE_FIELDS) {
            if (matchesIgnoreCase(source, index, field)) {
                int after = index + field.length();
                if (after >= source.length()
                        || !isWordCharacter(source.charAt(after))) {
                    return field;
                }
            }
        }
        return null;
    }

    private static int matchKeyword(
            String source, int index, String keyword) {
        if (!matchesIgnoreCase(source, index, keyword)) {
            return 0;
        }
        int after = index + keyword.length();
        return after >= source.length()
                || !isWordCharacter(source.charAt(after))
                ? keyword.length()
                : 0;
    }

    private static boolean matchesIgnoreCase(
            String source, int index, String expected) {
        if (index < 0 || index + expected.length() > source.length()) {
            return false;
        }
        return source.regionMatches(true, index, expected, 0, expected.length());
    }

    private static boolean isBoundaryBefore(String source, int index) {
        return index == 0 || !isWordCharacter(source.charAt(index - 1));
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean isQuote(char value) {
        return value == '\'' || value == '"';
    }

    private static int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length()
                && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int consumeQuotedValue(
            String source, int start, char quote) {
        int current = start + 1;
        boolean escaped = false;
        while (current < source.length()) {
            char value = source.charAt(current);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote) {
                return current + 1;
            } else if (value == '\n' || value == '\r') {
                return current;
            }
            current++;
        }
        return current;
    }

    /**
     * 敏感赋值必须宁可多遮盖也不能因空格把 scheme 与真正凭据拆开。
     */
    private static int consumeSensitiveValue(String source, int start) {
        if (start >= source.length()) {
            return start;
        }
        if (isQuote(source.charAt(start))) {
            return consumeQuotedValue(source, start, source.charAt(start));
        }
        int current = start;
        while (current < source.length()
                && !isSensitiveValueTerminator(source.charAt(current))) {
            if (Character.isWhitespace(source.charAt(current))
                    && startsAnotherAssignment(source, current)) {
                break;
            }
            current++;
        }
        return current;
    }

    /**
     * 空格不应把 Bearer/Basic 的 scheme 与凭据拆开，但相邻的 `key=value` 仍应独立处理，
     * 以维持多个字段的逐项脱敏和输出上限。
     */
    private static boolean startsAnotherAssignment(String source, int index) {
        int current = skipWhitespace(source, index);
        if (current >= source.length()) {
            return false;
        }
        boolean quoted = isQuote(source.charAt(current));
        char quote = quoted ? source.charAt(current++) : '\0';
        int nameStart = current;
        while (current < source.length()
                && (isWordCharacter(source.charAt(current))
                || source.charAt(current) == '-')) {
            current++;
        }
        if (current == nameStart) {
            return false;
        }
        if (quoted) {
            if (current >= source.length() || source.charAt(current) != quote) {
                return false;
            }
            current++;
        }
        current = skipWhitespace(source, current);
        return current < source.length()
                && (source.charAt(current) == ':' || source.charAt(current) == '=');
    }

    private static int consumeSecretToken(String source, int start) {
        int current = start;
        while (current < source.length()) {
            char value = source.charAt(current);
            if (!(Character.isLetterOrDigit(value)
                    || value == '_'
                    || value == '-'
                    || value == '.')) {
                break;
            }
            current++;
        }
        return current;
    }

    private static boolean isSensitiveValueTerminator(char value) {
        return value == '&'
                || value == ';'
                || value == ','
                || value == '}'
                || value == ']'
                || value == ')'
                || value == '\n'
                || value == '\r';
    }

    private static int countCharacter(
            String source, int start, int endExclusive, char expected) {
        int count = 0;
        for (int index = start; index < endExclusive; index++) {
            if (source.charAt(index) == expected) {
                count++;
            }
        }
        return count;
    }

    private record Replacement(
            int start, int endExclusive, String replacement) {
        private Replacement {
            if (start < 0 || endExclusive < start) {
                throw new IllegalArgumentException("invalid replacement range");
            }
            Objects.requireNonNull(replacement, "replacement");
        }
    }
}
