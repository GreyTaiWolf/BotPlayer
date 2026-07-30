package io.github.greytaiwolf.botplayer.action.interaction;

/**
 * Result of the narrow synchronous inventory cleanup used at a lifecycle boundary.
 */
public enum InventoryLayoutCleanupResult {
    RESTORED,
    ALREADY_SAFE,
    SAFE_LAYOUT_COMMITTED,
    STALE,
    BLOCKED,
    UNSAFE
}
