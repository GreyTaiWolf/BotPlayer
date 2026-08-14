package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Optional strict preconditions for a native {@code USE_ITEM} action.
 *
 * <p>This is a pure transport value. The action backend compares it during
 * validation, immediately before it sends the vanilla packet, and while a
 * strict multi-tick item use remains active. It is deliberately opt-in so
 * pre-existing generic item uses retain their previous contract, while
 * safety-critical consumers can bind an exact native inventory menu and a
 * bounded, canonical active-effect set.
 */
public record UseItemPreconditions(
        InventoryMenuSnapshot nativeInventoryMenu,
        List<ActiveEffectFingerprint> activeEffects) {
    public static final int MAXIMUM_ACTIVE_EFFECTS = 16;

    public UseItemPreconditions {
        nativeInventoryMenu = Objects.requireNonNull(
                nativeInventoryMenu, "nativeInventoryMenu");
        Objects.requireNonNull(activeEffects, "activeEffects");
        if (activeEffects.size() > MAXIMUM_ACTIVE_EFFECTS) {
            throw new IllegalArgumentException(
                    "activeEffects exceeds maximum size "
                            + MAXIMUM_ACTIVE_EFFECTS);
        }
        List<ActiveEffectFingerprint> copied = new ArrayList<>(
                activeEffects.size());
        Set<ResourceId> seenIds = new LinkedHashSet<>();
        for (ActiveEffectFingerprint effect : activeEffects) {
            ActiveEffectFingerprint nonNull = Objects.requireNonNull(
                    effect, "active effect fingerprint");
            if (!seenIds.add(nonNull.effectId())) {
                throw new IllegalArgumentException(
                        "activeEffects must not contain duplicate effect ids");
            }
            copied.add(nonNull);
        }
        copied.sort(Comparator.naturalOrder());
        activeEffects = List.copyOf(copied);
    }

    /**
     * Pure matching policy used by the Minecraft backend after it has converted
     * live effects into bounded immutable observations. Extra, missing, duplicate
     * or short-lived effects never satisfy a strict precondition.
     */
    public boolean matchesActiveEffects(
            List<ActiveEffectObservation> observedEffects) {
        Objects.requireNonNull(observedEffects, "observedEffects");
        if (observedEffects.size() > MAXIMUM_ACTIVE_EFFECTS) {
            return false;
        }
        List<ActiveEffectObservation> copied = new ArrayList<>(
                observedEffects.size());
        Set<ResourceId> seenIds = new LinkedHashSet<>();
        for (ActiveEffectObservation observation : observedEffects) {
            if (observation == null
                    || !seenIds.add(observation.effectId())) {
                return false;
            }
            copied.add(observation);
        }
        copied.sort(Comparator.naturalOrder());
        if (activeEffects.size() != copied.size()) {
            return false;
        }
        for (int index = 0; index < activeEffects.size(); index++) {
            ActiveEffectFingerprint expected = activeEffects.get(index);
            ActiveEffectObservation observed = copied.get(index);
            if (!expected.effectId().equals(observed.effectId())
                    || expected.amplifier() != observed.amplifier()
                    || expected.ambient() != observed.ambient()
                    || expected.visible() != observed.visible()
                    || observed.remainingDurationTicks()
                            < expected
                                    .minimumRemainingDurationTicks()) {
                return false;
            }
        }
        return true;
    }
}
