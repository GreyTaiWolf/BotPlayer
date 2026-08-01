package io.github.greytaiwolf.botplayer.action.interaction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryLayoutCleanupResultTest {
    @Test
    void ordinarySuccessGateRejectsExternalDeathConsumption() {
        Assertions.assertTrue(
                InventoryLayoutCleanupResult.RESTORED
                        .isOrdinaryCleanupSuccess());
        Assertions.assertTrue(
                InventoryLayoutCleanupResult.ALREADY_SAFE
                        .isOrdinaryCleanupSuccess());
        Assertions.assertTrue(
                InventoryLayoutCleanupResult
                        .SAFE_LAYOUT_COMMITTED
                        .isOrdinaryCleanupSuccess());
        Assertions.assertFalse(
                InventoryLayoutCleanupResult
                        .VANILLA_DEATH_CONSUMED
                        .isOrdinaryCleanupSuccess());
        Assertions.assertFalse(
                InventoryLayoutCleanupResult.STALE
                        .isOrdinaryCleanupSuccess());
        Assertions.assertFalse(
                InventoryLayoutCleanupResult.BLOCKED
                        .isOrdinaryCleanupSuccess());
        Assertions.assertFalse(
                InventoryLayoutCleanupResult.UNSAFE
                        .isOrdinaryCleanupSuccess());
    }
}
