package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, slot-order-independent item totals for a bounded player
 * inventory.
 *
 * <p>Stacks with the same item, damage and component digest are combined.
 * This lets lifecycle compensation prove item conservation even when vanilla
 * or another actor has split, merged or moved stacks between slots.
 */
public final class InventoryContentsSnapshot {
    public static final int MAX_INPUT_STACKS = 41;

    private final List<ItemStackFingerprint> itemTotals;

    public InventoryContentsSnapshot(
            List<ItemStackFingerprint> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        if (stacks.size() > MAX_INPUT_STACKS) {
            throw new IllegalArgumentException(
                    "inventory snapshot exceeds the bounded player inventory");
        }

        List<ItemStackFingerprint> totals = new ArrayList<>();
        for (ItemStackFingerprint stack : stacks) {
            ItemStackFingerprint required =
                    Objects.requireNonNull(
                            stack, "inventory stack");
            if (required.isEmpty()) {
                continue;
            }
            int matchingIndex = matchingIndex(
                    totals, required);
            if (matchingIndex < 0) {
                totals.add(required);
                continue;
            }
            ItemStackFingerprint existing =
                    totals.get(matchingIndex);
            totals.set(
                    matchingIndex,
                    new ItemStackFingerprint(
                            existing.itemId(),
                            Math.addExact(
                                    existing.count(),
                                    required.count()),
                            existing.damage(),
                            existing.componentsDigest()));
        }
        totals.sort(Comparator
                .comparing((ItemStackFingerprint fingerprint) ->
                        fingerprint.itemId()
                                .orElseThrow()
                                .value())
                .thenComparingInt(
                        ItemStackFingerprint::damage)
                .thenComparing(fingerprint ->
                        fingerprint.componentsDigest()
                                .orElseThrow()));
        itemTotals = List.copyOf(totals);
    }

    public List<ItemStackFingerprint> itemTotals() {
        return itemTotals;
    }

    public int matchingCount(
            ItemStackFingerprint expected) {
        Objects.requireNonNull(expected, "expected");
        if (expected.isEmpty()) {
            return 0;
        }
        int index = matchingIndex(
                itemTotals, expected);
        return index < 0
                ? 0
                : itemTotals.get(index).count();
    }

    public boolean containsIdentity(
            ItemStackFingerprint expected) {
        return matchingCount(expected) > 0;
    }

    private static int matchingIndex(
            List<ItemStackFingerprint> fingerprints,
            ItemStackFingerprint expected) {
        for (int index = 0;
                index < fingerprints.size();
                index++) {
            if (fingerprints
                    .get(index)
                    .sameItemAndComponents(expected)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other
                                instanceof
                                InventoryContentsSnapshot snapshot
                        && itemTotals.equals(
                                snapshot.itemTotals);
    }

    @Override
    public int hashCode() {
        return itemTotals.hashCode();
    }

    @Override
    public String toString() {
        return "InventoryContentsSnapshot[itemTotals="
                + itemTotals
                + "]";
    }
}
