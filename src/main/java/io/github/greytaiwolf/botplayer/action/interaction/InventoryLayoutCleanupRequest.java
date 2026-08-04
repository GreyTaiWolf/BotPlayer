package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Exact state retained by a bounded skill for lifecycle-time inventory cleanup.
 */
public record InventoryLayoutCleanupRequest(
        InventoryLayoutCleanupLease lease) {
    public InventoryLayoutCleanupRequest {
        Objects.requireNonNull(lease, "lease");
    }

    public UUID runId() {
        return lease.runId();
    }

    public int sourceInventorySlot() {
        return lease.sourceInventorySlot();
    }

    public int temporaryHotbarSlot() {
        return lease.temporaryHotbarSlot();
    }

    public ItemStackFingerprint expectedFood() {
        return lease.expectedFood();
    }

    public InventoryContentsSnapshot inventoryBefore() {
        return lease.inventoryBefore();
    }

    public int previousSelectedSlot() {
        return lease.previousSelectedSlot();
    }

    public boolean checkSwap() {
        return lease.checkSwap();
    }

    public boolean checkSelection() {
        return lease.checkSelection();
    }
}
