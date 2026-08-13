package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SafetyHandoffRequestTest {
    private static final UUID INCIDENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID BOT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Test
    void acceptsAnExactlyGenerationBoundFrame() {
        SafetyFrame frame = frame(BOT_ID, 7L, 42L);
        HazardAssessment hazard = hazard();

        SafetyHandoffRequest request = new SafetyHandoffRequest(
                INCIDENT_ID, BOT_ID, 7L, 42L, hazard, frame);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        INCIDENT_ID, request.incidentId()),
                () -> Assertions.assertEquals(
                        HazardType.FOOD_CRITICAL,
                        request.hazard().type()));
    }

    @Test
    void rejectsIdentityOrTickDrift() {
        SafetyFrame frame = frame(BOT_ID, 7L, 42L);
        HazardAssessment hazard = hazard();

        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new SafetyHandoffRequest(
                                INCIDENT_ID,
                                new UUID(0L, 999L),
                                7L,
                                42L,
                                hazard,
                                frame)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new SafetyHandoffRequest(
                                INCIDENT_ID,
                                BOT_ID,
                                8L,
                                42L,
                                hazard,
                                frame)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new SafetyHandoffRequest(
                                INCIDENT_ID,
                                BOT_ID,
                                7L,
                                43L,
                                hazard,
                                frame)));
    }

    @Test
    void delegatedDecisionIsExplicit() {
        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        SafetyHandoffDecision.DELEGATED.delegated()),
                () -> Assertions.assertTrue(
                        SafetyHandoffDecision.ALREADY_DELEGATED
                                .delegated()),
                () -> Assertions.assertFalse(
                        SafetyHandoffDecision.FALLBACK.delegated()));
    }

    private static HazardAssessment hazard() {
        return new HazardAssessment(
                HazardType.FOOD_CRITICAL,
                HazardSeverity.WARNING,
                0,
                true,
                false,
                Optional.empty(),
                "测试饥饿恢复交接");
    }

    private static SafetyFrame frame(
            UUID botId, long generation, long tick) {
        return new SafetyFrame(
                botId,
                generation,
                tick,
                "minecraft:overworld",
                new GridPoint(0, 64, 0),
                0.0D,
                0.0D,
                0.0D,
                true,
                0.0F,
                20.0F,
                20.0F,
                0.0F,
                0,
                0.0D,
                0.0D,
                0.1D,
                3,
                0.0F,
                300,
                300,
                false,
                false,
                false,
                false,
                0,
                false,
                false,
                List.of(),
                false,
                List.of(),
                false,
                Optional.empty(),
                Optional.empty(),
                0.0F);
    }
}
