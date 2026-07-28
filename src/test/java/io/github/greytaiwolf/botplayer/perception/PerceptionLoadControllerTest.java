package io.github.greytaiwolf.botplayer.perception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PerceptionLoadControllerTest {
    @Test
    void degradedPressureUsesEwmaAndRecoversOnlyBelowTheLowerThreshold() {
        PerceptionLoadController controller =
                new PerceptionLoadController(45.0D, 50.0D, 38.0D, 45.0D);

        Assertions.assertEquals(
                PerceptionPressure.DEGRADED, recordMillis(controller, 46));
        Assertions.assertEquals(
                PerceptionPressure.DEGRADED, recordMillis(controller, 40));
        Assertions.assertEquals(44.8D, controller.smoothedMspt(), 0.0001D);

        PerceptionPressure pressure = controller.pressure();
        for (int index = 0; index < 8 && pressure != PerceptionPressure.NORMAL; index++) {
            pressure = recordMillis(controller, 20);
        }

        Assertions.assertEquals(PerceptionPressure.NORMAL, pressure);
        Assertions.assertTrue(controller.smoothedMspt() <= 38.0D);
    }

    @Test
    void criticalPressureFallsThroughDegradedBeforeNormal() {
        PerceptionLoadController controller =
                new PerceptionLoadController(45.0D, 50.0D, 38.0D, 45.0D);

        Assertions.assertEquals(
                PerceptionPressure.CRITICAL, recordMillis(controller, 60));
        PerceptionPressure pressure = controller.pressure();
        for (int index = 0;
                index < 8 && pressure == PerceptionPressure.CRITICAL;
                index++) {
            pressure = recordMillis(controller, 35);
        }

        Assertions.assertEquals(PerceptionPressure.DEGRADED, pressure);
        Assertions.assertTrue(controller.smoothedMspt() > 38.0D);
        for (int index = 0; index < 8 && pressure != PerceptionPressure.NORMAL; index++) {
            pressure = recordMillis(controller, 35);
        }
        Assertions.assertEquals(PerceptionPressure.NORMAL, pressure);
    }

    @Test
    void rejectsInvalidThresholdsAndDurations() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionLoadController(45.0D, 45.0D, 38.0D, 40.0D));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionLoadController(45.0D, 50.0D, 45.0D, 47.0D));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionLoadController(
                        Double.NaN, 50.0D, 38.0D, 45.0D));

        PerceptionLoadController controller =
                new PerceptionLoadController(45.0D, 50.0D, 38.0D, 45.0D);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> controller.recordTickDurationNanos(-1L));
    }

    private static PerceptionPressure recordMillis(
            PerceptionLoadController controller, long milliseconds) {
        return controller.recordTickDurationNanos(milliseconds * 1_000_000L);
    }
}
