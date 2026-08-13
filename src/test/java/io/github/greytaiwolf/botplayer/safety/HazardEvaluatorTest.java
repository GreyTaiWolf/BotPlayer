package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HazardEvaluatorTest {
    private static final UUID BOT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000411");

    @Test
    void immediateLavaOutranksSupplyWarnings() {
        SafetyFrame frame = frame(
                3.0F,
                2,
                300,
                true,
                false,
                false,
                List.of(),
                List.of(),
                0.0F,
                Optional.empty());

        List<HazardAssessment> hazards =
                new HazardEvaluator(settings()).evaluate(frame);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        HazardType.LAVA_CONTACT,
                        hazards.getFirst().type()),
                () -> Assertions.assertEquals(
                        HazardSeverity.EMERGENCY,
                        hazards.getFirst().severity()),
                () -> Assertions.assertTrue(
                        hazards.stream().anyMatch(value ->
                                value.type()
                                        == HazardType.FOOD_CRITICAL)));
    }

    @Test
    void dynamicHarmfulEffectAndUnknownDamageRemainVisible() {
        EffectSummary moddedEffect = new EffectSummary(
                "examplemod:corrosion",
                EffectSummary.Category.HARMFUL,
                1,
                100,
                false,
                true);
        SafetyFrame frame = frame(
                15.0F,
                20,
                300,
                false,
                false,
                false,
                List.of(moddedEffect),
                List.of(),
                2.0F,
                Optional.empty());

        List<HazardAssessment> hazards =
                new HazardEvaluator(settings()).evaluate(frame);

        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        hazards.stream().anyMatch(value ->
                                value.type()
                                        == HazardType.HARMFUL_EFFECT)),
                () -> Assertions.assertTrue(
                        hazards.stream().anyMatch(value ->
                                value.type()
                                        == HazardType.UNKNOWN_DAMAGE)));
    }

    @Test
    void targetingHostileUsesAuthoritativeTargetFlag() {
        ThreatSummary zombie = new ThreatSummary(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000412"),
                ThreatSummary.Kind.HOSTILE,
                new GridPoint(2, 64, 0),
                2.0D,
                0.0D,
                true);
        SafetyFrame frame = frame(
                20.0F,
                20,
                300,
                false,
                false,
                false,
                List.of(),
                List.of(zombie),
                0.0F,
                Optional.empty());

        HazardAssessment primary =
                new HazardEvaluator(settings())
                        .primary(frame)
                        .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        HazardType.HOSTILE_TARGETING,
                        primary.type()),
                () -> Assertions.assertEquals(
                        zombie.entityId(),
                        primary.sourceEntityId().orElseThrow()));
    }

    private static SafetySettings settings() {
        return new SafetySettings(
                4.0F,
                4,
                40,
                100,
                12.0D,
                10.0D,
                10.0D,
                12.0D,
                32,
                256,
                20,
                6,
                3,
                3);
    }

    private static SafetyFrame frame(
            float health,
            int food,
            int air,
            boolean inLava,
            boolean onFire,
            boolean unsafeSupport,
            List<EffectSummary> effects,
            List<ThreatSummary> threats,
            float vitalLoss,
            Optional<DamageCandidate> damage) {
        return new SafetyFrame(
                BOT_ID,
                1L,
                10L,
                "minecraft:overworld",
                new GridPoint(0, 64, 0),
                0.0D,
                0.0D,
                0.0D,
                true,
                0.0F,
                health,
                20.0F,
                0.0F,
                0,
                0.0D,
                0.0D,
                0.1D,
                food,
                5.0F,
                air,
                300,
                onFire,
                inLava,
                air < 300,
                false,
                0,
                unsafeSupport,
                false,
                effects,
                false,
                threats,
                false,
                Optional.empty(),
                damage,
                vitalLoss);
    }
}
