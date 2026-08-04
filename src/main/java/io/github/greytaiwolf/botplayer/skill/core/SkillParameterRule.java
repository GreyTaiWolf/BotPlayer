package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 参数规则只描述纯 Java 标量，不接收活动游戏对象。
 */
public sealed interface SkillParameterRule
        permits SkillParameterRule.StringRule,
                SkillParameterRule.IntegerRule,
                SkillParameterRule.LongRule,
                SkillParameterRule.DecimalRule,
                SkillParameterRule.BooleanRule {
    int MAX_ALLOWED_VALUES = 64;

    boolean required();

    SkillParameterType type();

    Optional<SkillParameterViolation> validate(
            String field, Object value);

    record StringRule(
            boolean required,
            int minimumLength,
            int maximumLength,
            Set<String> allowedValues)
            implements SkillParameterRule {
        public StringRule {
            if (minimumLength < 0
                    || maximumLength < minimumLength
                    || maximumLength
                            > SkillParameters.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "string length bounds are invalid");
            }
            Objects.requireNonNull(allowedValues, "allowedValues");
            if (allowedValues.size() > MAX_ALLOWED_VALUES) {
                throw new IllegalArgumentException(
                        "allowedValues exceeds maximum size "
                                + MAX_ALLOWED_VALUES);
            }
            Set<String> sorted = new TreeSet<>();
            for (String value : allowedValues) {
                Objects.requireNonNull(value, "allowed value");
                if (value.length() < minimumLength
                        || value.length() > maximumLength
                        || value.codePoints()
                                .anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(
                            "allowed string value violates the rule bounds");
                }
                sorted.add(value);
            }
            allowedValues = Collections.unmodifiableSet(
                    new LinkedHashSet<>(sorted));
        }

        @Override
        public SkillParameterType type() {
            return SkillParameterType.STRING;
        }

        @Override
        public Optional<SkillParameterViolation> validate(
                String field, Object value) {
            Optional<SkillParameterViolation> mismatch =
                    requireType(field, type(), value);
            if (mismatch.isPresent()) {
                return mismatch;
            }
            String text = (String) value;
            if (text.length() < minimumLength) {
                return violation(
                        field,
                        SkillParameterViolation.Code.TOO_SHORT,
                        "参数长度低于下限");
            }
            if (text.length() > maximumLength) {
                return violation(
                        field,
                        SkillParameterViolation.Code.TOO_LONG,
                        "参数长度超过上限");
            }
            if (!allowedValues.isEmpty()
                    && !allowedValues.contains(text)) {
                return violation(
                        field,
                        SkillParameterViolation.Code.VALUE_NOT_ALLOWED,
                        "参数值不在允许集合中");
            }
            return Optional.empty();
        }
    }

    record IntegerRule(
            boolean required, int minimum, int maximum)
            implements SkillParameterRule {
        public IntegerRule {
            if (maximum < minimum) {
                throw new IllegalArgumentException(
                        "integer bounds are invalid");
            }
        }

        @Override
        public SkillParameterType type() {
            return SkillParameterType.INTEGER;
        }

        @Override
        public Optional<SkillParameterViolation> validate(
                String field, Object value) {
            Optional<SkillParameterViolation> mismatch =
                    requireType(field, type(), value);
            if (mismatch.isPresent()) {
                return mismatch;
            }
            int number = (Integer) value;
            return number < minimum || number > maximum
                    ? violation(
                            field,
                            SkillParameterViolation.Code.OUT_OF_RANGE,
                            "整数参数超出允许范围")
                    : Optional.empty();
        }
    }

    record LongRule(
            boolean required, long minimum, long maximum)
            implements SkillParameterRule {
        public LongRule {
            if (maximum < minimum) {
                throw new IllegalArgumentException(
                        "long bounds are invalid");
            }
        }

        @Override
        public SkillParameterType type() {
            return SkillParameterType.LONG;
        }

        @Override
        public Optional<SkillParameterViolation> validate(
                String field, Object value) {
            Optional<SkillParameterViolation> mismatch =
                    requireType(field, type(), value);
            if (mismatch.isPresent()) {
                return mismatch;
            }
            long number = value instanceof Integer integer
                    ? integer.longValue()
                    : (Long) value;
            return number < minimum || number > maximum
                    ? violation(
                            field,
                            SkillParameterViolation.Code.OUT_OF_RANGE,
                            "长整数参数超出允许范围")
                    : Optional.empty();
        }
    }

    record DecimalRule(
            boolean required, double minimum, double maximum)
            implements SkillParameterRule {
        public DecimalRule {
            if (!Double.isFinite(minimum)
                    || !Double.isFinite(maximum)
                    || maximum < minimum) {
                throw new IllegalArgumentException(
                        "decimal bounds are invalid");
            }
        }

        @Override
        public SkillParameterType type() {
            return SkillParameterType.DECIMAL;
        }

        @Override
        public Optional<SkillParameterViolation> validate(
                String field, Object value) {
            Optional<SkillParameterViolation> mismatch =
                    requireType(field, type(), value);
            if (mismatch.isPresent()) {
                return mismatch;
            }
            double number = ((Number) value).doubleValue();
            return number < minimum || number > maximum
                    ? violation(
                            field,
                            SkillParameterViolation.Code.OUT_OF_RANGE,
                            "小数参数超出允许范围")
                    : Optional.empty();
        }
    }

    record BooleanRule(boolean required)
            implements SkillParameterRule {
        @Override
        public SkillParameterType type() {
            return SkillParameterType.BOOLEAN;
        }

        @Override
        public Optional<SkillParameterViolation> validate(
                String field, Object value) {
            return requireType(field, type(), value);
        }
    }

    private static Optional<SkillParameterViolation> requireType(
            String field,
            SkillParameterType type,
            Object value) {
        SkillParameters.requireName(field);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        return type.accepts(value)
                ? Optional.empty()
                : violation(
                        field,
                        SkillParameterViolation.Code.TYPE_MISMATCH,
                        "参数类型与 schema 不匹配");
    }

    private static Optional<SkillParameterViolation> violation(
            String field,
            SkillParameterViolation.Code code,
            String summary) {
        return Optional.of(
                new SkillParameterViolation(field, code, summary));
    }
}
