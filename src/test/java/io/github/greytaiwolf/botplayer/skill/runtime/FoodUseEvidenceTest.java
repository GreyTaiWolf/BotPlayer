package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ResourceIdEvidence;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FoodUseEvidenceTest {
    private static final String COMPONENTS =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    void acceptsExactSingleConsumptionAndFoodIncrease() {
        FoodUseEvidence.Observation observation =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "2"),
                                evidence("item.after_count", "1"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "minecraft:apple"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "14"))),
                        expected(2));

        Assertions.assertTrue(
                observation.exactItemConsumption());
        Assertions.assertTrue(
                observation.foodLevelIncreased());
    }

    @Test
    void acceptsConsumptionOfTheLastItem() {
        FoodUseEvidence.Observation observation =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "1"),
                                evidence("item.after_count", "0"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "empty"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "empty"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", "empty"),
                                evidence("player.food_before", "4"),
                                evidence("player.food_after", "6"))),
                        expected(1));

        Assertions.assertTrue(
                observation.exactItemConsumption());
        Assertions.assertTrue(
                observation.foodLevelIncreased());
    }

    @Test
    void acceptsTheSharedEncodingForAMaximumLengthItemId() {
        ResourceId itemId = resourceIdWithLength(256);
        String encodedId =
                ResourceIdEvidence.encode(itemId);
        FoodUseEvidence.Observation observation =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "2"),
                                evidence("item.after_count", "1"),
                                evidence("item.before_id", encodedId),
                                evidence("item.after_id", encodedId),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "14"))),
                        expected(itemId, 2));

        Assertions.assertTrue(
                observation.exactItemConsumption());
        Assertions.assertTrue(
                observation.foodLevelIncreased());
    }

    @Test
    void separatesItemConsumptionFromFoodBusinessSuccess() {
        FoodUseEvidence.Observation observation =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "2"),
                                evidence("item.after_count", "1"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "minecraft:apple"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "9"))),
                        expected(2));

        Assertions.assertTrue(
                observation.exactItemConsumption());
        Assertions.assertFalse(
                observation.foodLevelIncreased());
    }

    @Test
    void rejectsAReplacementItemWithTheExpectedCount() {
        FoodUseEvidence.Observation observation =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "2"),
                                evidence("item.after_count", "1"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "minecraft:carrot"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "14"))),
                        expected(2));

        Assertions.assertFalse(
                observation.exactItemConsumption());
        Assertions.assertTrue(
                observation.foodLevelIncreased());
    }

    @Test
    void rejectsMissingMalformedOrWrongCountEvidence() {
        FoodUseEvidence.Observation missing =
                FoodUseEvidence.observe(
                        signal(List.of()), expected(2));
        FoodUseEvidence.Observation malformed =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "two"),
                                evidence("item.after_count", "1"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "minecraft:apple"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "14"))),
                        expected(2));
        FoodUseEvidence.Observation wrongCount =
                FoodUseEvidence.observe(
                        signal(List.of(
                                evidence("item.before_count", "2"),
                                evidence("item.after_count", "0"),
                                evidence("item.before_id", "minecraft:apple"),
                                evidence("item.after_id", "minecraft:apple"),
                                evidence("item.before_damage", "0"),
                                evidence("item.after_damage", "0"),
                                evidence("item.before_components", COMPONENTS),
                                evidence("item.after_components", COMPONENTS),
                                evidence("player.food_before", "10"),
                                evidence("player.food_after", "14"))),
                        expected(2));

        Assertions.assertFalse(
                missing.exactItemConsumption());
        Assertions.assertFalse(
                missing.foodLevelIncreased());
        Assertions.assertFalse(
                malformed.exactItemConsumption());
        Assertions.assertTrue(
                malformed.foodLevelIncreased());
        Assertions.assertFalse(
                wrongCount.exactItemConsumption());
        Assertions.assertTrue(
                wrongCount.foodLevelIncreased());
    }

    private static ActionEvidence evidence(
            String key, String value) {
        return new ActionEvidence(key, value);
    }

    private static ItemStackFingerprint expected(
            int count) {
        return expected(
                new ResourceId("minecraft:apple"), count);
    }

    private static ItemStackFingerprint expected(
            ResourceId itemId, int count) {
        return ItemStackFingerprint.of(
                itemId,
                count,
                0,
                COMPONENTS);
    }

    private static ResourceId resourceIdWithLength(int length) {
        int namespaceLength =
                Math.max(1, length - 1 - 191);
        int pathLength =
                length - namespaceLength - 1;
        return new ResourceId(
                "n".repeat(namespaceLength)
                        + ":"
                        + "p".repeat(pathLength));
    }

    private static SkillSignal signal(
            List<ActionEvidence> evidence) {
        return new SkillSignal(
                new UUID(0L, 1L),
                new UUID(0L, 2L),
                new UUID(0L, 3L),
                1L,
                4L,
                new UUID(0L, 5L),
                SkillSignalType.ACTION,
                SkillSignalStatus.SUCCEEDED,
                SkillFailureCode.NONE,
                evidence,
                "",
                6L);
    }
}
