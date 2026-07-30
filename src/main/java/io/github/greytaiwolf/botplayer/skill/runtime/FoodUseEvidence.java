package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ResourceIdEvidence;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Interprets immutable evidence captured at the exact action verification boundary.
 */
final class FoodUseEvidence {
    private FoodUseEvidence() {}

    static Observation observe(
            SkillSignal signal,
            ItemStackFingerprint expectedBefore) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(
                expectedBefore, "expectedBefore");
        if (expectedBefore.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedBefore must not be empty");
        }
        int expectedBeforeCount =
                expectedBefore.count();
        OptionalInt itemBefore = integer(
                signal, "item.before_count");
        OptionalInt itemAfter = integer(
                signal, "item.after_count");
        OptionalInt foodBefore = integer(
                signal, "player.food_before");
        OptionalInt foodAfter = integer(
                signal, "player.food_after");
        boolean beforeIdentity =
                identityMatches(
                        signal,
                        "item.before",
                        expectedBefore);
        boolean afterIdentity =
                expectedBeforeCount == 1
                        ? emptyIdentityMatches(
                                signal, "item.after")
                        : identityMatches(
                                signal,
                                "item.after",
                                expectedBefore);
        return new Observation(
                beforeIdentity
                        && afterIdentity
                        && itemBefore.isPresent()
                        && itemAfter.isPresent()
                        && itemBefore.orElseThrow()
                                == expectedBeforeCount
                        && itemAfter.orElseThrow()
                                == expectedBeforeCount - 1,
                foodBefore.isPresent()
                        && foodAfter.isPresent()
                        && foodAfter.orElseThrow()
                                > foodBefore.orElseThrow());
    }

    private static boolean identityMatches(
            SkillSignal signal,
            String prefix,
            ItemStackFingerprint expected) {
        return text(signal, prefix + "_id")
                        .filter(value ->
                                value.equals(
                                        ResourceIdEvidence.encode(
                                                expected.itemId()
                                                        .orElseThrow())))
                        .isPresent()
                && text(signal, prefix + "_damage")
                        .filter(value ->
                                value.equals(Integer.toString(
                                        expected.damage())))
                        .isPresent()
                && text(signal, prefix + "_components")
                        .filter(value ->
                                value.equals(
                                        expected.componentsDigest()
                                                .orElseThrow()))
                        .isPresent();
    }

    private static boolean emptyIdentityMatches(
            SkillSignal signal, String prefix) {
        return text(signal, prefix + "_id")
                        .filter("empty"::equals)
                        .isPresent()
                && text(signal, prefix + "_damage")
                        .filter("empty"::equals)
                        .isPresent()
                && text(signal, prefix + "_components")
                        .filter("empty"::equals)
                        .isPresent();
    }

    private static OptionalInt integer(
            SkillSignal signal, String key) {
        Optional<String> value = text(
                signal, key);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(
                    value.orElseThrow());
            return parsed < 0
                    ? OptionalInt.empty()
                    : OptionalInt.of(parsed);
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    private static Optional<String> text(
            SkillSignal signal, String key) {
        for (ActionEvidence evidence : signal.evidence()) {
            if (!evidence.key().equals(key)) {
                continue;
            }
            return Optional.of(
                    evidence.value());
        }
        return Optional.empty();
    }

    record Observation(
            boolean exactItemConsumption,
            boolean foodLevelIncreased) {}
}
