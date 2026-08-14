package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 不可信工具参数的不可变值树。
 *
 * <p>这个类型刻意不暴露 {@code JsonElement} 或 {@code Object}。解析器和 Firewall 因此只会
 * 面对明确、可穷举的 JSON 值类型；它本身不表示已通过权限或世界状态校验。
 */
public sealed interface ToolValue permits ToolValue.NullValue,
        ToolValue.BooleanValue,
        ToolValue.IntegerValue,
        ToolValue.StringValue,
        ToolValue.ArrayValue,
        ToolValue.ObjectValue {
    int MAX_STRING_CHARACTERS = 65_536;
    int MAX_COLLECTION_ENTRIES = 128;
    int MAX_TREE_DEPTH = 16;
    int MAX_TREE_NODES = 4_096;

    ToolValueType type();

    /** JSON {@code null}；默认 Firewall 规则不会允许它。 */
    record NullValue() implements ToolValue {
        @Override
        public ToolValueType type() {
            return ToolValueType.NULL;
        }

        @Override
        public String toString() {
            return "ToolValue[type=NULL]";
        }
    }

    /** JSON 布尔值。 */
    record BooleanValue(boolean value) implements ToolValue {
        @Override
        public ToolValueType type() {
            return ToolValueType.BOOLEAN;
        }

        @Override
        public String toString() {
            return "ToolValue[type=BOOLEAN]";
        }
    }

    /** 64 位 JSON 整数；小数和科学计数法在 Codec 层拒绝。 */
    record IntegerValue(long value) implements ToolValue {
        @Override
        public ToolValueType type() {
            return ToolValueType.INTEGER;
        }

        @Override
        public String toString() {
            return "ToolValue[type=INTEGER]";
        }
    }

    /** 受硬长度限制的 JSON 字符串。 */
    record StringValue(String value) implements ToolValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
            if (value.length() > MAX_STRING_CHARACTERS) {
                throw new IllegalArgumentException(
                        "string value exceeds maximum length "
                                + MAX_STRING_CHARACTERS);
            }
            if (!ToolChecks.hasOnlyUnicodeScalars(value)) {
                throw new IllegalArgumentException(
                        "string value must not contain an unpaired surrogate");
            }
        }

        @Override
        public ToolValueType type() {
            return ToolValueType.STRING;
        }

        @Override
        public String toString() {
            return "ToolValue[type=STRING, length=" + value.length() + "]";
        }
    }

    /** 受硬元素上限限制的 JSON 数组。 */
    record ArrayValue(List<ToolValue> values) implements ToolValue {
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_COLLECTION_ENTRIES) {
                throw new IllegalArgumentException(
                        "array value exceeds maximum size "
                                + MAX_COLLECTION_ENTRIES);
            }
            List<ToolValue> copied = new ArrayList<>(values.size());
            for (ToolValue value : values) {
                copied.add(Objects.requireNonNull(value, "array value"));
            }
            values = List.copyOf(copied);
        }

        @Override
        public ToolValueType type() {
            return ToolValueType.ARRAY;
        }

        @Override
        public String toString() {
            return "ToolValue[type=ARRAY, entryCount=" + values.size() + "]";
        }
    }

    /** 受硬成员上限限制、键名受约束的 JSON 对象。 */
    record ObjectValue(Map<String, ToolValue> values) implements ToolValue {
        public ObjectValue {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_COLLECTION_ENTRIES) {
                throw new IllegalArgumentException(
                        "object value exceeds maximum size "
                                + MAX_COLLECTION_ENTRIES);
            }
            Map<String, ToolValue> copied = new LinkedHashMap<>();
            for (Map.Entry<String, ToolValue> entry : values.entrySet()) {
                String name = ToolChecks.parameterName(
                        entry.getKey(), "object parameter name");
                ToolValue value = Objects.requireNonNull(
                        entry.getValue(), "object parameter value");
                if (copied.put(name, value) != null) {
                    throw new IllegalArgumentException(
                            "duplicate object parameter " + name);
                }
            }
            values = Map.copyOf(copied);
        }

        @Override
        public ToolValueType type() {
            return ToolValueType.OBJECT;
        }

        @Override
        public String toString() {
            return "ToolValue[type=OBJECT, entryCount=" + values.size() + "]";
        }
    }
}
