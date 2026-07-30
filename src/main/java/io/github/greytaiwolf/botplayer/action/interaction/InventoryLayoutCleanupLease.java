package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable payload armed before an eating run may own temporary inventory
 * layout state.
 */
public record InventoryLayoutCleanupLease(
        UUID runId,
        int sourceInventorySlot,
        int temporaryHotbarSlot,
        ItemStackFingerprint expectedFood,
        InventoryContentsSnapshot inventoryBefore,
        int previousSelectedSlot,
        boolean checkSwap,
        boolean checkSelection) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public InventoryLayoutCleanupLease {
        Objects.requireNonNull(runId, "runId");
        if (ZERO_UUID.equals(runId)) {
            throw new IllegalArgumentException(
                    "run identity must be non-zero");
        }
        Objects.requireNonNull(expectedFood, "expectedFood");
        if (expectedFood.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedFood must not be empty");
        }
        Objects.requireNonNull(
                inventoryBefore, "inventoryBefore");
        if (inventoryBefore.matchingCount(
                        expectedFood)
                < expectedFood.count()) {
            throw new IllegalArgumentException(
                    "inventoryBefore must contain the selected food stack");
        }
        if (sourceInventorySlot != -1
                && (sourceInventorySlot < 9
                        || sourceInventorySlot > 35)) {
            throw new IllegalArgumentException(
                    "sourceInventorySlot must be -1 or a main-inventory slot");
        }
        if (temporaryHotbarSlot != -1
                && (temporaryHotbarSlot < 0
                        || temporaryHotbarSlot > 8)) {
            throw new IllegalArgumentException(
                    "temporaryHotbarSlot must be -1 or a hotbar slot");
        }
        if (sourceInventorySlot >= 0
                && temporaryHotbarSlot < 0) {
            throw new IllegalArgumentException(
                    "main-inventory food requires a temporary hotbar slot");
        }
        if (checkSwap
                != (sourceInventorySlot >= 0)) {
            throw new IllegalArgumentException(
                    "checkSwap must match ownership of a main-inventory source");
        }
        if (previousSelectedSlot < 0
                || previousSelectedSlot > 8) {
            throw new IllegalArgumentException(
                    "previousSelectedSlot must be a hotbar slot");
        }
        if (checkSelection
                && (temporaryHotbarSlot < 0
                        || temporaryHotbarSlot
                                == previousSelectedSlot)) {
            throw new IllegalArgumentException(
                    "checkSelection requires a different skill hotbar slot");
        }
    }
}
