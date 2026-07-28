package io.github.greytaiwolf.botplayer.perception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PerceptionSettingsTest {
    @Test
    void acceptsExactlyOneCompletePlayerInventoryBudget() {
        PerceptionSettings settings = settings(
                64,
                41,
                38.0D,
                45.0D,
                50.0D,
                45.0D);

        Assertions.assertEquals(41, settings.inventoryReadsPerBot());
        Assertions.assertEquals(
                41,
                settings.budgetLimits(PerceptionPressure.CRITICAL)
                        .get(BudgetKind.INVENTORY_SLOT));
    }

    @Test
    void rejectsAnInventoryBudgetThatCannotCoverAllPlayerSlots() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        64,
                        40,
                        38.0D,
                        45.0D,
                        50.0D,
                        45.0D));
    }

    @Test
    void rejectsARecentEventWindowLargerThanTheRetainedRing() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        65,
                        41,
                        38.0D,
                        45.0D,
                        50.0D,
                        45.0D));
    }

    @Test
    void rejectsInvalidMsptHysteresisOrdering() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(
                                64,
                                41,
                                45.0D,
                                45.0D,
                                50.0D,
                                46.0D)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(
                                64,
                                41,
                                38.0D,
                                50.0D,
                                50.0D,
                                45.0D)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(
                                64,
                                41,
                                40.0D,
                                45.0D,
                                50.0D,
                                39.0D)));
    }

    @Test
    void requiresEnoughGlobalWorkForOneCompleteInventorySnapshot() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        64,
                        41,
                        38.0D,
                        45.0D,
                        50.0D,
                        45.0D,
                        63));
        PerceptionSettings minimum = settings(
                64,
                41,
                38.0D,
                45.0D,
                50.0D,
                45.0D,
                64);

        Assertions.assertEquals(64, minimum.globalWorkPerTick());
        Assertions.assertEquals(
                256,
                minimum.budgetLimits(PerceptionPressure.NORMAL)
                        .get(BudgetKind.ENTITY_SCAN));
        Assertions.assertEquals(
                64,
                minimum.budgetLimits(PerceptionPressure.CRITICAL)
                        .get(BudgetKind.ENTITY_SCAN));
    }

    private static PerceptionSettings settings(
            int recentEventLimit,
            int inventoryReadsPerBot,
            double recoverMspt,
            double degradeMspt,
            double criticalMspt,
            double criticalRecoverMspt) {
        return settings(
                recentEventLimit,
                inventoryReadsPerBot,
                recoverMspt,
                degradeMspt,
                criticalMspt,
                criticalRecoverMspt,
                4_096);
    }

    private static PerceptionSettings settings(
            int recentEventLimit,
            int inventoryReadsPerBot,
            double recoverMspt,
            double degradeMspt,
            double criticalMspt,
            double criticalRecoverMspt,
            int globalWorkPerTick) {
        return new PerceptionSettings(
                32.0D,
                24.0D,
                1,
                32.0D,
                64,
                128,
                64,
                64,
                inventoryReadsPerBot,
                globalWorkPerTick,
                1_024,
                64,
                1_024,
                2_048,
                2_048,
                recentEventLimit,
                200,
                degradeMspt,
                criticalMspt,
                recoverMspt,
                criticalRecoverMspt);
    }
}
