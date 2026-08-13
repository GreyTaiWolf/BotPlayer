package io.github.greytaiwolf.botplayer.skill.builtin.recovery;

import io.github.greytaiwolf.botplayer.action.interaction.ActiveEffectFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * Pure proof for the single vanilla milk-bucket recovery transition.
 *
 * <p>It neither reads Minecraft nor writes inventory/effect state. A handler
 * freezes this object before submitting the mailbox action, then supplies a
 * fresh native inventory snapshot and active-effect fingerprints on completion.
 * Only exactly one selected milk bucket becoming exactly one selected empty
 * bucket while every other slot, control-plane field and effect transition
 * remains valid can complete the skill.
 */
public final class MilkBucketRecoveryProof {
    public static final ResourceId POISON_EFFECT_ID =
            new ResourceId("minecraft:poison");
    /**
     * Prevents a poison tick-down from being mistaken for a milk cure if a
     * queued action is delayed until its bounded action window.
     */
    public static final int MINIMUM_POISON_DURATION_TICKS = 120;

    private final InventoryMenuSnapshot before;
    private final int hotbarSlot;
    private final ItemStackFingerprint expectedMilkBucket;
    private final ItemStackFingerprint expectedEmptyBucket;
    private final ActiveEffectFingerprint poisonBefore;

    private MilkBucketRecoveryProof(
            InventoryMenuSnapshot before,
            int hotbarSlot,
            ItemStackFingerprint expectedMilkBucket,
            ItemStackFingerprint expectedEmptyBucket,
            ActiveEffectFingerprint poisonBefore) {
        this.before = Objects.requireNonNull(before, "before");
        this.hotbarSlot = hotbarSlot;
        this.expectedMilkBucket = Objects.requireNonNull(
                expectedMilkBucket, "expectedMilkBucket");
        this.expectedEmptyBucket = Objects.requireNonNull(
                expectedEmptyBucket, "expectedEmptyBucket");
        this.poisonBefore = Objects.requireNonNull(poisonBefore,
                "poisonBefore");
    }

    /**
     * Captures only the recovery state that can be proved after native use.
     * The active-effect list must contain precisely one stable poison identity;
     * callers keep its duration separately and use the strict action DTO to
     * recheck this exact identity set immediately before dispatch.
     */
    public static MilkBucketRecoveryProof freeze(
            InventoryMenuSnapshot before,
            int hotbarSlot,
            ItemStackFingerprint expectedMilkBucket,
            ItemStackFingerprint expectedEmptyBucket,
            List<ActiveEffectFingerprint> activeEffects,
            int poisonDuration) {
        InventoryMenuSnapshot snapshot = Objects.requireNonNull(before,
                "before");
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            throw new IllegalArgumentException(
                    "hotbarSlot must be between 0 and 8");
        }
        ItemStackFingerprint milk = Objects.requireNonNull(
                expectedMilkBucket, "expectedMilkBucket");
        ItemStackFingerprint bucket = Objects.requireNonNull(
                expectedEmptyBucket, "expectedEmptyBucket");
        if (milk.isEmpty()
                || bucket.isEmpty()
                || milk.count() != 1
                || bucket.count() != 1
                || milk.sameItemAndComponents(bucket)
                || !snapshot.cursor().isEmpty()
                || snapshot.selectedHotbar() != hotbarSlot
                || !snapshot.itemAt(hotbarSlot).equals(milk)) {
            throw new IllegalArgumentException(
                    "native inventory cannot prove exact milk-bucket use");
        }
        List<ActiveEffectFingerprint> effects = List.copyOf(
                Objects.requireNonNull(activeEffects, "activeEffects"));
        if (effects.size() != 1
                || poisonDuration < MINIMUM_POISON_DURATION_TICKS) {
            throw new IllegalArgumentException(
                    "milk recovery requires sustained poison as the only active effect");
        }
        ActiveEffectFingerprint poison = effects.get(0);
        if (!POISON_EFFECT_ID.equals(poison.effectId())) {
            throw new IllegalArgumentException(
                    "milk recovery only accepts minecraft:poison");
        }
        return new MilkBucketRecoveryProof(snapshot, hotbarSlot, milk,
                bucket, poison);
    }

    /**
     * Confirms that native use performed the one approved inventory transition
     * and left no active effects. State ids may advance as vanilla synchronizes,
     * but container identity, selected slot, cursor and every physical slot stay
     * exact.
     */
    public Verification verify(
            InventoryMenuSnapshot after,
            List<ActiveEffectFingerprint> activeEffects) {
        InventoryMenuSnapshot actual = Objects.requireNonNull(after, "after");
        List<ActiveEffectFingerprint> effects = List.copyOf(
                Objects.requireNonNull(activeEffects, "activeEffects"));
        if (actual.containerId() != before.containerId()) {
            return Verification.MENU_CHANGED;
        }
        if (actual.selectedHotbar() != hotbarSlot) {
            return Verification.SELECTION_CHANGED;
        }
        if (!actual.cursor().isEmpty()) {
            return Verification.CURSOR_CHANGED;
        }
        for (int slot = 0; slot < before.inventorySlots().size(); slot++) {
            ItemStackFingerprint expected = slot == hotbarSlot
                    ? expectedEmptyBucket
                    : before.itemAt(slot);
            if (!expected.equals(actual.itemAt(slot))) {
                return slot == hotbarSlot
                        ? Verification.MILK_TRANSITION_MISMATCH
                        : Verification.UNRELATED_INVENTORY_CHANGED;
            }
        }
        return effects.isEmpty()
                ? Verification.VERIFIED
                : Verification.EFFECTS_NOT_CLEARED;
    }

    public ActiveEffectFingerprint poisonBefore() {
        return poisonBefore;
    }

    public enum Verification {
        VERIFIED,
        MENU_CHANGED,
        SELECTION_CHANGED,
        CURSOR_CHANGED,
        MILK_TRANSITION_MISMATCH,
        UNRELATED_INVENTORY_CHANGED,
        EFFECTS_NOT_CLEARED;

        public boolean verified() {
            return this == VERIFIED;
        }
    }
}
