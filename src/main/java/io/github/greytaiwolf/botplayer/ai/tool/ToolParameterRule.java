package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 单个工具参数的静态、纯 Java schema。
 *
 * <p>对象和数组必须递归给出完整 schema，不能把嵌套 JSON 当作任意数据透传。规则不包含世界
 * 坐标、背包、权限或活动 Minecraft 对象。
 */
public record ToolParameterRule(
        ToolValueType type,
        boolean required,
        ToolStringSemantics stringSemantics,
        OptionalLong minimumInteger,
        OptionalLong maximumInteger,
        int maximumStringCharacters,
        int maximumCollectionEntries,
        Set<String> allowedStringValues,
        Map<String, ToolParameterRule> objectFields,
        Optional<ToolParameterRule> arrayElementRule) {
    public ToolParameterRule {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(stringSemantics, "stringSemantics");
        minimumInteger = Objects.requireNonNull(
                minimumInteger, "minimumInteger");
        maximumInteger = Objects.requireNonNull(
                maximumInteger, "maximumInteger");
        Objects.requireNonNull(allowedStringValues, "allowedStringValues");
        allowedStringValues = copyAllowedValues(allowedStringValues);
        Objects.requireNonNull(objectFields, "objectFields");
        objectFields = copyObjectFields(objectFields);
        arrayElementRule = Objects.requireNonNull(
                arrayElementRule, "arrayElementRule");

        switch (type) {
            case STRING -> validateStringRule(
                    stringSemantics,
                    minimumInteger,
                    maximumInteger,
                    maximumStringCharacters,
                    maximumCollectionEntries,
                    allowedStringValues,
                    objectFields,
                    arrayElementRule);
            case INTEGER -> validateIntegerRule(
                    stringSemantics,
                    minimumInteger,
                    maximumInteger,
                    maximumStringCharacters,
                    maximumCollectionEntries,
                    allowedStringValues,
                    objectFields,
                    arrayElementRule);
            case ARRAY -> validateArrayRule(
                    stringSemantics,
                    minimumInteger,
                    maximumInteger,
                    maximumStringCharacters,
                    maximumCollectionEntries,
                    allowedStringValues,
                    objectFields,
                    arrayElementRule);
            case OBJECT -> validateObjectRule(
                    stringSemantics,
                    minimumInteger,
                    maximumInteger,
                    maximumStringCharacters,
                    maximumCollectionEntries,
                    allowedStringValues,
                    objectFields,
                    arrayElementRule);
            case NULL, BOOLEAN -> validateSimpleRule(
                    stringSemantics,
                    minimumInteger,
                    maximumInteger,
                    maximumStringCharacters,
                    maximumCollectionEntries,
                    allowedStringValues,
                    objectFields,
                    arrayElementRule);
        }
    }

    public static ToolParameterRule requiredString(int maximumCharacters) {
        return stringRule(true, maximumCharacters, Set.of());
    }

    public static ToolParameterRule optionalString(int maximumCharacters) {
        return stringRule(false, maximumCharacters, Set.of());
    }

    /** 对物品、方块等可信 resource id 参数使用此窄语义，而非任意文本。 */
    public static ToolParameterRule requiredResourceIdentifier(
            int maximumCharacters) {
        return resourceIdentifierRule(true, maximumCharacters);
    }

    public static ToolParameterRule optionalResourceIdentifier(
            int maximumCharacters) {
        return resourceIdentifierRule(false, maximumCharacters);
    }

    public static ToolParameterRule requiredStringOneOf(
            int maximumCharacters, Set<String> allowedValues) {
        return stringRule(true, maximumCharacters, allowedValues);
    }

    public static ToolParameterRule requiredInteger(long minimum, long maximum) {
        return integerRule(true, minimum, maximum);
    }

    public static ToolParameterRule optionalInteger(long minimum, long maximum) {
        return integerRule(false, minimum, maximum);
    }

    public static ToolParameterRule requiredBoolean() {
        return simpleRule(ToolValueType.BOOLEAN, true);
    }

    public static ToolParameterRule optionalBoolean() {
        return simpleRule(ToolValueType.BOOLEAN, false);
    }

    public static ToolParameterRule requiredArray(
            int maximumEntries, ToolParameterRule elementRule) {
        return arrayRule(true, maximumEntries, elementRule);
    }

    public static ToolParameterRule optionalArray(
            int maximumEntries, ToolParameterRule elementRule) {
        return arrayRule(false, maximumEntries, elementRule);
    }

    public static ToolParameterRule requiredObject(
            int maximumEntries, Map<String, ToolParameterRule> fields) {
        return objectRule(true, maximumEntries, fields);
    }

    public static ToolParameterRule optionalObject(
            int maximumEntries, Map<String, ToolParameterRule> fields) {
        return objectRule(false, maximumEntries, fields);
    }

    private static ToolParameterRule stringRule(
            boolean required, int maximumCharacters, Set<String> allowedValues) {
        return new ToolParameterRule(
                ToolValueType.STRING,
                required,
                ToolStringSemantics.FREE_TEXT,
                OptionalLong.empty(),
                OptionalLong.empty(),
                maximumCharacters,
                0,
                allowedValues,
                Map.of(),
                Optional.empty());
    }

    private static ToolParameterRule resourceIdentifierRule(
            boolean required, int maximumCharacters) {
        return new ToolParameterRule(
                ToolValueType.STRING,
                required,
                ToolStringSemantics.RESOURCE_IDENTIFIER,
                OptionalLong.empty(),
                OptionalLong.empty(),
                maximumCharacters,
                0,
                Set.of(),
                Map.of(),
                Optional.empty());
    }

    private static ToolParameterRule integerRule(
            boolean required, long minimum, long maximum) {
        return new ToolParameterRule(
                ToolValueType.INTEGER,
                required,
                ToolStringSemantics.NONE,
                OptionalLong.of(minimum),
                OptionalLong.of(maximum),
                0,
                0,
                Set.of(),
                Map.of(),
                Optional.empty());
    }

    private static ToolParameterRule simpleRule(
            ToolValueType type, boolean required) {
        return new ToolParameterRule(
                type,
                required,
                ToolStringSemantics.NONE,
                OptionalLong.empty(),
                OptionalLong.empty(),
                0,
                0,
                Set.of(),
                Map.of(),
                Optional.empty());
    }

    private static ToolParameterRule arrayRule(
            boolean required, int maximumEntries, ToolParameterRule elementRule) {
        return new ToolParameterRule(
                ToolValueType.ARRAY,
                required,
                ToolStringSemantics.NONE,
                OptionalLong.empty(),
                OptionalLong.empty(),
                0,
                maximumEntries,
                Set.of(),
                Map.of(),
                Optional.of(Objects.requireNonNull(elementRule, "elementRule")));
    }

    private static ToolParameterRule objectRule(
            boolean required,
            int maximumEntries,
            Map<String, ToolParameterRule> fields) {
        return new ToolParameterRule(
                ToolValueType.OBJECT,
                required,
                ToolStringSemantics.NONE,
                OptionalLong.empty(),
                OptionalLong.empty(),
                0,
                maximumEntries,
                Set.of(),
                fields,
                Optional.empty());
    }

    private static Set<String> copyAllowedValues(Set<String> values) {
        Set<String> copied = new LinkedHashSet<>();
        for (String value : values) {
            copied.add(Objects.requireNonNull(value, "allowed string value"));
        }
        return Set.copyOf(copied);
    }

    private static Map<String, ToolParameterRule> copyObjectFields(
            Map<String, ToolParameterRule> fields) {
        if (fields.size() > ToolValue.MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException(
                    "objectFields exceeds maximum size "
                            + ToolValue.MAX_COLLECTION_ENTRIES);
        }
        Map<String, ToolParameterRule> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ToolParameterRule> entry : fields.entrySet()) {
            String name = ToolChecks.parameterName(
                    entry.getKey(), "object field name");
            ToolParameterRule rule = Objects.requireNonNull(
                    entry.getValue(), "object field rule");
            if (copied.put(name, rule) != null) {
                throw new IllegalArgumentException(
                        "duplicate object field " + name);
            }
        }
        return Map.copyOf(copied);
    }

    private static void validateStringRule(
            ToolStringSemantics stringSemantics,
            OptionalLong minimumInteger,
            OptionalLong maximumInteger,
            int maximumStringCharacters,
            int maximumCollectionEntries,
            Set<String> allowedStringValues,
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        requireNoIntegerRange(minimumInteger, maximumInteger);
        if (stringSemantics == ToolStringSemantics.NONE) {
            throw new IllegalArgumentException(
                    "STRING rules require string semantics");
        }
        if (maximumStringCharacters < 1
                || maximumStringCharacters > ToolValue.MAX_STRING_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maximumStringCharacters must be between 1 and "
                            + ToolValue.MAX_STRING_CHARACTERS);
        }
        requireZero(maximumCollectionEntries, "maximumCollectionEntries");
        requireNoChildren(objectFields, arrayElementRule);
        for (String value : allowedStringValues) {
            if (value.isEmpty() || value.length() > maximumStringCharacters) {
                throw new IllegalArgumentException(
                        "allowed string value exceeds configured length");
            }
        }
    }

    private static void validateIntegerRule(
            ToolStringSemantics stringSemantics,
            OptionalLong minimumInteger,
            OptionalLong maximumInteger,
            int maximumStringCharacters,
            int maximumCollectionEntries,
            Set<String> allowedStringValues,
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        requireNoStringSemantics(stringSemantics);
        if (minimumInteger.isEmpty() || maximumInteger.isEmpty()
                || minimumInteger.getAsLong() > maximumInteger.getAsLong()) {
            throw new IllegalArgumentException(
                    "integer rules require an ordered minimum and maximum");
        }
        requireZero(maximumStringCharacters, "maximumStringCharacters");
        requireZero(maximumCollectionEntries, "maximumCollectionEntries");
        requireEmpty(allowedStringValues, "allowedStringValues");
        requireNoChildren(objectFields, arrayElementRule);
    }

    private static void validateArrayRule(
            ToolStringSemantics stringSemantics,
            OptionalLong minimumInteger,
            OptionalLong maximumInteger,
            int maximumStringCharacters,
            int maximumCollectionEntries,
            Set<String> allowedStringValues,
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        requireNoStringSemantics(stringSemantics);
        requireNoIntegerRange(minimumInteger, maximumInteger);
        requireZero(maximumStringCharacters, "maximumStringCharacters");
        requireCollectionMaximum(maximumCollectionEntries);
        requireEmpty(allowedStringValues, "allowedStringValues");
        if (!objectFields.isEmpty() || arrayElementRule.isEmpty()) {
            throw new IllegalArgumentException(
                    "ARRAY rules require exactly one element rule");
        }
    }

    private static void validateObjectRule(
            ToolStringSemantics stringSemantics,
            OptionalLong minimumInteger,
            OptionalLong maximumInteger,
            int maximumStringCharacters,
            int maximumCollectionEntries,
            Set<String> allowedStringValues,
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        requireNoStringSemantics(stringSemantics);
        requireNoIntegerRange(minimumInteger, maximumInteger);
        requireZero(maximumStringCharacters, "maximumStringCharacters");
        requireCollectionMaximum(maximumCollectionEntries);
        requireEmpty(allowedStringValues, "allowedStringValues");
        if (objectFields.size() > maximumCollectionEntries
                || arrayElementRule.isPresent()) {
            throw new IllegalArgumentException(
                    "OBJECT rules require bounded fields and no array element rule");
        }
    }

    private static void validateSimpleRule(
            ToolStringSemantics stringSemantics,
            OptionalLong minimumInteger,
            OptionalLong maximumInteger,
            int maximumStringCharacters,
            int maximumCollectionEntries,
            Set<String> allowedStringValues,
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        requireNoStringSemantics(stringSemantics);
        requireNoIntegerRange(minimumInteger, maximumInteger);
        requireZero(maximumStringCharacters, "maximumStringCharacters");
        requireZero(maximumCollectionEntries, "maximumCollectionEntries");
        requireEmpty(allowedStringValues, "allowedStringValues");
        requireNoChildren(objectFields, arrayElementRule);
    }

    private static void requireNoIntegerRange(
            OptionalLong minimumInteger, OptionalLong maximumInteger) {
        if (minimumInteger.isPresent() || maximumInteger.isPresent()) {
            throw new IllegalArgumentException(
                    "only INTEGER rules may define an integer range");
        }
    }

    private static void requireNoStringSemantics(
            ToolStringSemantics stringSemantics) {
        if (stringSemantics != ToolStringSemantics.NONE) {
            throw new IllegalArgumentException(
                    "only STRING rules may define string semantics");
        }
    }

    private static void requireCollectionMaximum(int value) {
        if (value < 1 || value > ToolValue.MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException(
                    "maximumCollectionEntries must be between 1 and "
                            + ToolValue.MAX_COLLECTION_ENTRIES);
        }
    }

    private static void requireNoChildren(
            Map<String, ToolParameterRule> objectFields,
            Optional<ToolParameterRule> arrayElementRule) {
        if (!objectFields.isEmpty() || arrayElementRule.isPresent()) {
            throw new IllegalArgumentException(
                    "this rule type must not define child schemas");
        }
    }

    private static void requireZero(int value, String name) {
        if (value != 0) {
            throw new IllegalArgumentException(name + " must be zero for this type");
        }
    }

    private static void requireEmpty(Set<String> values, String name) {
        if (!values.isEmpty()) {
            throw new IllegalArgumentException(name + " must be empty for this type");
        }
    }
}
