package io.github.greytaiwolf.botplayer.skill.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 默认拒绝未知字段的有界参数 schema。
 */
public record SkillParameterSchema(
        Map<String, SkillParameterRule> rules) {
    public static final int MAX_RULES = 64;
    public static final int MAX_VIOLATIONS = 128;

    private static final SkillParameterSchema EMPTY =
            new SkillParameterSchema(Map.of());

    public SkillParameterSchema {
        Objects.requireNonNull(rules, "rules");
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException(
                    "rules exceeds maximum size " + MAX_RULES);
        }
        Map<String, SkillParameterRule> sorted = new TreeMap<>();
        rules.forEach((name, rule) -> {
            SkillParameters.requireName(name);
            sorted.put(
                    name,
                    Objects.requireNonNull(rule, "parameter rule"));
        });
        rules = Collections.unmodifiableMap(
                new LinkedHashMap<>(sorted));
    }

    public static SkillParameterSchema empty() {
        return EMPTY;
    }

    public Optional<SkillParameterRule> rule(String name) {
        SkillParameters.requireName(name);
        return Optional.ofNullable(rules.get(name));
    }

    public Validation validate(SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        List<SkillParameterViolation> violations =
                new ArrayList<>();
        for (Map.Entry<String, SkillParameterRule> entry :
                rules.entrySet()) {
            String name = entry.getKey();
            SkillParameterRule rule = entry.getValue();
            Optional<Object> value = parameters.value(name);
            if (value.isEmpty()) {
                if (rule.required()) {
                    add(
                            violations,
                            new SkillParameterViolation(
                                    name,
                                    SkillParameterViolation.Code
                                            .MISSING_REQUIRED,
                                    "缺少必需参数"));
                }
                continue;
            }
            rule.validate(name, value.orElseThrow())
                    .ifPresent(violation ->
                            add(violations, violation));
        }
        for (String name : parameters.values().keySet()) {
            if (!rules.containsKey(name)) {
                add(
                        violations,
                        new SkillParameterViolation(
                                name,
                                SkillParameterViolation.Code
                                    .UNKNOWN_PARAMETER,
                                "schema 不接受该参数"));
            }
        }
        return new Validation(violations);
    }

    private static void add(
            List<SkillParameterViolation> violations,
            SkillParameterViolation violation) {
        if (violations.size() < MAX_VIOLATIONS) {
            violations.add(violation);
        }
    }

    public record Validation(
            List<SkillParameterViolation> violations) {
        public Validation {
            violations = List.copyOf(
                    Objects.requireNonNull(
                            violations, "violations"));
            if (violations.size() > MAX_VIOLATIONS) {
                throw new IllegalArgumentException(
                        "violations exceeds maximum size "
                                + MAX_VIOLATIONS);
            }
        }

        public boolean valid() {
            return violations.isEmpty();
        }
    }
}
