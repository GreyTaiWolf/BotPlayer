package io.github.greytaiwolf.botplayer.navigation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NavigationSettingsTest {
    @Test
    void settingsRejectUnboundedOrIncoherentBudgets() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(49, 8, 2_048, 8_192, 7, 5)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(24, 8, 8_192, 2_048, 7, 5)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(24, 8, 2_048, 8_192, 4, 5)));
    }

    @Test
    void settingsPreserveShortInputLeasesAndSupplyThresholds() {
        NavigationSettings settings =
                settings(24, 8, 2_048, 8_192, 7, 5);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        3, settings.followerInputTicks()),
                () -> Assertions.assertEquals(
                        20, settings.stuckWindowTicks()),
                () -> Assertions.assertTrue(
                        settings.minimumSprintFood()
                                >= settings.minimumTravelFood()));
    }

    private static NavigationSettings settings(
            int horizontalRadius,
            int verticalRadius,
            int perBotCells,
            int globalCells,
            int sprintFood,
            int travelFood) {
        return new NavigationSettings(
                horizontalRadius,
                verticalRadius,
                perBotCells,
                globalCells,
                20,
                50_000,
                2,
                16,
                2_048,
                3,
                20,
                0.45D,
                sprintFood,
                travelFood,
                6.0D);
    }
}
