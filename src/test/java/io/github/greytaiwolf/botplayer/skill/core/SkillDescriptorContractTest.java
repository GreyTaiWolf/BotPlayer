package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillDescriptorContractTest {
    private static final SkillId SKILL_ID =
            new SkillId("botplayer", "resource/find");
    private static final SkillVersion VERSION =
            new SkillVersion(1, 2, 3);

    @Test
    void acceptsBoundedDescriptorAndKeepsCapabilitiesImmutable() {
        SkillDescriptor descriptor = new SkillDescriptor(
                SKILL_ID,
                VERSION,
                SkillCategory.RESOURCE,
                schema(),
                SkillRiskLevel.LOW,
                Set.of(
                        new SkillId(
                                "botplayer", "capability/navigation")),
                1_200,
                3,
                true);

        Assertions.assertEquals(SKILL_ID, descriptor.id());
        Assertions.assertEquals("1.2.3", descriptor.version().toString());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> descriptor.requiredCapabilities().clear());
    }

    @Test
    void validatesRequiredUnknownTypeRangeAndAllowedValues() {
        SkillParameterSchema schema = schema();

        SkillParameterSchema.Validation valid = schema.validate(
                new SkillParameters(Map.of(
                        "amount", 4,
                        "target", "oak")));
        Assertions.assertTrue(valid.valid());

        SkillParameterSchema.Validation invalid = schema.validate(
                new SkillParameters(Map.of(
                        "amount", 0,
                        "target", "spruce",
                        "unknown", true)));
        Assertions.assertFalse(invalid.valid());
        Assertions.assertEquals(
                Set.of(
                        SkillParameterViolation.Code.OUT_OF_RANGE,
                        SkillParameterViolation.Code.VALUE_NOT_ALLOWED,
                        SkillParameterViolation.Code.UNKNOWN_PARAMETER),
                invalid.violations().stream()
                        .map(SkillParameterViolation::code)
                        .collect(java.util.stream.Collectors.toSet()));

        SkillParameterSchema.Validation missing = schema.validate(
                new SkillParameters(Map.of("amount", "four")));
        Assertions.assertTrue(missing.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillParameterViolation.Code
                                        .MISSING_REQUIRED));
        Assertions.assertTrue(missing.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillParameterViolation.Code
                                        .TYPE_MISMATCH));
    }

    @Test
    void rejectsUnsafeIdentifiersValuesAndDescriptorBudgets() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SkillId.parse("MissingNamespace"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillId("BotPlayer", "find"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillVersion(-1, 0, 0));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillParameters(
                        Map.of("value", Double.NaN)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillParameters(
                        Map.of("value", new Object())));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> descriptor(0, 0));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> descriptor(1, 17));

        java.util.Map<String, Object> tooManyParameters =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, SkillParameterRule> tooManyRules =
                new java.util.LinkedHashMap<>();
        java.util.Set<SkillId> tooManyCapabilities =
                new java.util.LinkedHashSet<>();
        for (int index = 0;
                index <= SkillParameters.MAX_PARAMETERS;
                index++) {
            String name = "p" + index;
            tooManyParameters.put(name, index);
            tooManyRules.put(
                    name,
                    new SkillParameterRule.BooleanRule(false));
            tooManyCapabilities.add(
                    new SkillId(
                            "botplayer", "capability/c" + index));
        }
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillParameters(tooManyParameters));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillParameterSchema(tooManyRules));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillDescriptor(
                        SKILL_ID,
                        VERSION,
                        SkillCategory.RESOURCE,
                        schema(),
                        SkillRiskLevel.LOW,
                        tooManyCapabilities,
                        20,
                        0,
                        false));
    }

    @Test
    void registryPinsExactVersionsAndNeverReplacesConflict() {
        SkillRegistry registry = new SkillRegistry(2);
        SkillDescriptor first = descriptor(20, 1);
        SkillDescriptor conflict = new SkillDescriptor(
                first.id(),
                first.version(),
                first.category(),
                first.parameterSchema(),
                SkillRiskLevel.HIGH,
                first.requiredCapabilities(),
                first.maximumRunTicks(),
                first.maximumRetries(),
                first.resumable());
        SkillDescriptor secondVersion = new SkillDescriptor(
                first.id(),
                new SkillVersion(2, 0, 0),
                first.category(),
                first.parameterSchema(),
                first.risk(),
                first.requiredCapabilities(),
                first.maximumRunTicks(),
                first.maximumRetries(),
                first.resumable());

        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(first));
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.ALREADY_REGISTERED,
                registry.register(first));
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.VERSION_CONFLICT,
                registry.register(conflict));
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(secondVersion));
        Assertions.assertEquals(
                secondVersion,
                registry.latest(SKILL_ID).orElseThrow());
        Assertions.assertEquals(2, registry.size());

        SkillDescriptor third = new SkillDescriptor(
                new SkillId("botplayer", "resource/collect"),
                VERSION,
                SkillCategory.RESOURCE,
                SkillParameterSchema.empty(),
                SkillRiskLevel.LOW,
                Set.of(),
                20,
                0,
                false);
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.CAPACITY_EXCEEDED,
                registry.register(third));
        Assertions.assertFalse(
                registry.containsId(third.id()));
    }

    @Test
    void parametersAndSchemaAreDefensivelyCopied() {
        java.util.Map<String, Object> values =
                new java.util.LinkedHashMap<>();
        values.put("target", "oak");
        SkillParameters parameters = new SkillParameters(values);
        values.put("target", "birch");

        Assertions.assertEquals(
                "oak", parameters.value("target").orElseThrow());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> parameters.values().put("amount", 2));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> schema().rules().clear());
    }

    private static SkillParameterSchema schema() {
        return new SkillParameterSchema(Map.of(
                "target",
                new SkillParameterRule.StringRule(
                        true,
                        1,
                        16,
                        Set.of("oak", "birch")),
                "amount",
                new SkillParameterRule.IntegerRule(
                        false, 1, 64)));
    }

    private static SkillDescriptor descriptor(
            int maximumRunTicks, int maximumRetries) {
        return new SkillDescriptor(
                SKILL_ID,
                VERSION,
                SkillCategory.RESOURCE,
                schema(),
                SkillRiskLevel.LOW,
                Set.of(),
                maximumRunTicks,
                maximumRetries,
                true);
    }
}
