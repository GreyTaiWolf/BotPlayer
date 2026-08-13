package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure full-inventory conservation rule for one already-proven cane drop. */
public final class SugarCaneFarmingInventoryConservation {
    private SugarCaneFarmingInventoryConservation() {
    }

    /**
     * Adds one exact native item entity receipt to the complete inventory
     * multiset. The caller must separately prove the menu/cursor/selected-slot
     * control plane and the source UUID's disappearance.
     */
    public static Optional<InventoryContentsSnapshot> afterCollectedDrop(
            InventoryContentsSnapshot before,
            ItemStackFingerprint drop) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(drop, "drop");
        if (drop.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStackFingerprint> combined = new ArrayList<>(
                before.itemTotals());
        int matching = -1;
        for (int index = 0; index < combined.size(); index++) {
            if (combined.get(index).sameItemAndComponents(drop)) {
                matching = index;
                break;
            }
        }
        try {
            if (matching >= 0) {
                ItemStackFingerprint existing = combined.get(matching);
                combined.set(matching, new ItemStackFingerprint(
                        existing.itemId(),
                        Math.addExact(existing.count(), drop.count()),
                        existing.damage(),
                        existing.componentsDigest()));
            } else {
                if (combined.size()
                        >= InventoryContentsSnapshot.MAX_INPUT_STACKS) {
                    return Optional.empty();
                }
                combined.add(drop);
            }
            return Optional.of(new InventoryContentsSnapshot(combined));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
