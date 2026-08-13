package io.github.greytaiwolf.botplayer.skill.builtin.recovery;

import io.github.greytaiwolf.botplayer.action.interaction.ActiveEffectFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MilkBucketRecoveryProofTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ItemStackFingerprint MILK = stack(
            "minecraft:milk_bucket", 1);
    private static final ItemStackFingerprint BUCKET = stack(
            "minecraft:bucket", 1);
    private static final ItemStackFingerprint COBBLESTONE = stack(
            "minecraft:cobblestone", 1);
    private static final ActiveEffectFingerprint POISON =
            new ActiveEffectFingerprint(
                    MilkBucketRecoveryProof.POISON_EFFECT_ID,
                    0,
                    81,
                    false,
                    true);

    @Test
    void acceptsOnlyExactMilkToBucketTransitionAndClearedEffects() {
        InventoryMenuSnapshot before = snapshot(3, 4,
                ItemStackFingerprint.empty(), MILK,
                ItemStackFingerprint.empty());
        MilkBucketRecoveryProof proof = MilkBucketRecoveryProof.freeze(
                before,
                3,
                MILK,
                BUCKET,
                List.of(POISON),
                MilkBucketRecoveryProof.MINIMUM_POISON_DURATION_TICKS);
        InventoryMenuSnapshot after = snapshot(3, 5,
                ItemStackFingerprint.empty(), BUCKET,
                ItemStackFingerprint.empty());

        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.VERIFIED,
                proof.verify(after, List.of()));
        Assertions.assertEquals(POISON, proof.poisonBefore());
    }

    @Test
    void rejectsControlInventoryAndEffectDrift() {
        InventoryMenuSnapshot before = snapshot(2, 4,
                ItemStackFingerprint.empty(), MILK,
                ItemStackFingerprint.empty());
        MilkBucketRecoveryProof proof = MilkBucketRecoveryProof.freeze(
                before,
                2,
                MILK,
                BUCKET,
                List.of(POISON),
                180);

        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.CURSOR_CHANGED,
                proof.verify(snapshot(2, 5, COBBLESTONE, BUCKET,
                        ItemStackFingerprint.empty()), List.of()));
        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.SELECTION_CHANGED,
                proof.verify(snapshot(1, 5, ItemStackFingerprint.empty(),
                        BUCKET, ItemStackFingerprint.empty()), List.of()));
        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.UNRELATED_INVENTORY_CHANGED,
                proof.verify(snapshot(2, 5, ItemStackFingerprint.empty(),
                        BUCKET, COBBLESTONE), List.of()));
        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.MILK_TRANSITION_MISMATCH,
                proof.verify(snapshot(2, 5, ItemStackFingerprint.empty(),
                        MILK, ItemStackFingerprint.empty()), List.of()));
        Assertions.assertEquals(MilkBucketRecoveryProof.Verification.EFFECTS_NOT_CLEARED,
                proof.verify(snapshot(2, 5, ItemStackFingerprint.empty(),
                        BUCKET, ItemStackFingerprint.empty()), List.of(POISON)));
    }

    @Test
    void refusesUnsafeInitialEffectOrInventoryState() {
        InventoryMenuSnapshot nativeMilk = snapshot(0, 1,
                ItemStackFingerprint.empty(), MILK,
                ItemStackFingerprint.empty());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MilkBucketRecoveryProof.freeze(nativeMilk, 0, MILK,
                        BUCKET, List.of(), 180));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MilkBucketRecoveryProof.freeze(nativeMilk, 0, MILK,
                        BUCKET, List.of(POISON), 20));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MilkBucketRecoveryProof.freeze(
                        snapshot(0, 1, COBBLESTONE, MILK,
                                ItemStackFingerprint.empty()),
                        0, MILK, BUCKET, List.of(POISON), 180));
        ActiveEffectFingerprint speed = new ActiveEffectFingerprint(
                new ResourceId("minecraft:speed"), 0, 0, false, true);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MilkBucketRecoveryProof.freeze(nativeMilk, 0, MILK,
                        BUCKET, List.of(POISON, speed), 180));
    }

    private static InventoryMenuSnapshot snapshot(
            int selected,
            int stateId,
            ItemStackFingerprint cursor,
            ItemStackFingerprint selectedStack,
            ItemStackFingerprint extra) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int slot = 0; slot < 41; slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(selected, selectedStack);
        if (!extra.isEmpty()) {
            slots.set(12, extra);
        }
        return new InventoryMenuSnapshot(0, stateId, selected, cursor, slots);
    }

    private static ItemStackFingerprint stack(String itemId, int count) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0,
                DIGEST);
    }
}
