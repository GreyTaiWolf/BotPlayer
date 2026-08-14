package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UseItemPreconditionsTest {
    private static final ActiveEffectFingerprint POISON =
            new ActiveEffectFingerprint(
                    new ResourceId("minecraft:poison"),
                    1,
                    80,
                    false,
                    true);

    @Test
    void matchingAllowsCountdownButRequiresTheConfiguredDurationFloor() {
        UseItemPreconditions preconditions = new UseItemPreconditions(
                inventory(), List.of(POISON));

        Assertions.assertTrue(preconditions.matchesActiveEffects(List.of(
                observation("minecraft:poison", 1, 120))));
        Assertions.assertTrue(preconditions.matchesActiveEffects(List.of(
                observation("minecraft:poison", 1, 80))));
        Assertions.assertFalse(preconditions.matchesActiveEffects(List.of(
                observation("minecraft:poison", 1, 79))));
    }

    @Test
    void matchingRejectsIdentityFlagAndSetDrift() {
        UseItemPreconditions preconditions = new UseItemPreconditions(
                inventory(), List.of(POISON));

        Assertions.assertFalse(preconditions.matchesActiveEffects(List.of(
                new ActiveEffectObservation(new ResourceId("minecraft:poison"),
                        0, 120, false, true))));
        Assertions.assertFalse(preconditions.matchesActiveEffects(List.of(
                new ActiveEffectObservation(new ResourceId("minecraft:poison"),
                        1, 120, true, true))));
        Assertions.assertFalse(preconditions.matchesActiveEffects(List.of(
                observation("minecraft:poison", 1, 120),
                observation("minecraft:speed", 0, 120))));
        Assertions.assertFalse(preconditions.matchesActiveEffects(List.of()));
    }

    private static ActiveEffectObservation observation(
            String id, int amplifier, int duration) {
        return new ActiveEffectObservation(new ResourceId(id), amplifier,
                duration, false, true);
    }

    private static InventoryMenuSnapshot inventory() {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int index = 0; index < 41; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        return new InventoryMenuSnapshot(0, 0, 0,
                ItemStackFingerprint.empty(), slots);
    }
}
