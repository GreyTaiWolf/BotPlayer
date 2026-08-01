package io.github.greytaiwolf.botplayer.action.interaction;

/**
 * Result of the narrow synchronous inventory cleanup used at a lifecycle boundary.
 */
public enum InventoryLayoutCleanupResult {
    RESTORED,
    ALREADY_SAFE,
    SAFE_LAYOUT_COMMITTED,
    /**
     * Vanilla death with {@code keepInventory=false} has consumed the exact
     * armed layout outside the normal physical-compensation handler.
     *
     * <p>This is a lifecycle-only receipt. It must never be treated as a
     * successful ordinary cleanup result.
     */
    VANILLA_DEATH_CONSUMED,
    STALE,
    BLOCKED,
    UNSAFE;

    public boolean isOrdinaryCleanupSuccess() {
        return this == RESTORED
                || this == ALREADY_SAFE
                || this == SAFE_LAYOUT_COMMITTED;
    }
}
