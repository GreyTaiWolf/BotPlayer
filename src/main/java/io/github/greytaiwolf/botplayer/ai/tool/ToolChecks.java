package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** P6 工具 DTO 的共享、无副作用输入检查。 */
final class ToolChecks {
    static final Pattern TOOL_NAME =
            Pattern.compile("[A-Za-z0-9_.:/-]{1,128}");
    static final Pattern CALL_ID =
            Pattern.compile("[A-Za-z0-9_.:/-]{1,128}");
    static final Pattern PARAMETER_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private ToolChecks() {
        throw new AssertionError("No instances");
    }

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    static String toolName(String value, String name) {
        return requirePattern(value, name, TOOL_NAME, "a tool name");
    }

    static String callId(String value, String name) {
        return requirePattern(value, name, CALL_ID, "a call identifier");
    }

    static String parameterName(String value, String name) {
        return requirePattern(value, name, PARAMETER_NAME, "a parameter name");
    }

    /** JSON 字符串必须由有效 Unicode scalar 组成，不能携带孤立 surrogate。 */
    static boolean hasOnlyUnicodeScalars(String value) {
        Objects.requireNonNull(value, "value");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在不为整段输入分配 UTF-8 临时数组的前提下，计算至多 {@code maximum + 1} 个字节。
     *
     * <p>Provider 文本本身不可信。{@code String#getBytes(UTF_8)} 会先为全部文本分配 byte[]，
     * 这会让“先检查大小”在超大输入面前失去意义。这里只需区分是否越界，因而在达到上限
     * 后立刻停止。孤立 surrogate 与 JDK UTF-8 默认编码一样按单字节替换符计数。</p>
     */
    static int utf8LengthAtMost(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int encodedLength;
            if (current <= 0x007F) {
                encodedLength = 1;
            } else if (current <= 0x07FF) {
                encodedLength = 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    encodedLength = 4;
                    index++;
                } else {
                    encodedLength = 1;
                }
            } else if (Character.isLowSurrogate(current)) {
                encodedLength = 1;
            } else {
                encodedLength = 3;
            }
            if (encodedLength > maximum - length) {
                return maximum + 1;
            }
            length += encodedLength;
        }
        return length;
    }

    /** 与 {@link #utf8LengthAtMost(String, String, int)} 相同，但仅计入半开区间。 */
    static int utf8LengthRangeAtMost(
            String value, int startInclusive, int endExclusive, int maximum) {
        Objects.requireNonNull(value, "value");
        if (startInclusive < 0 || endExclusive < startInclusive
                || endExclusive > value.length()) {
            throw new IllegalArgumentException("UTF-8 range is outside the input");
        }
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        int length = 0;
        for (int index = startInclusive; index < endExclusive; index++) {
            char current = value.charAt(index);
            int encodedLength;
            if (current <= 0x007F) {
                encodedLength = 1;
            } else if (current <= 0x07FF) {
                encodedLength = 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 < endExclusive
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    encodedLength = 4;
                    index++;
                } else {
                    encodedLength = 1;
                }
            } else if (Character.isLowSurrogate(current)) {
                encodedLength = 1;
            } else {
                encodedLength = 3;
            }
            if (encodedLength > maximum - length) {
                return maximum + 1;
            }
            length += encodedLength;
        }
        return length;
    }

    private static String requirePattern(
            String value, String name, Pattern pattern, String expected) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must contain " + expected);
        }
        return value;
    }
}
