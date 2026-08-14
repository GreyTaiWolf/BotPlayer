package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SafetyFrame(
        UUID botId,
        long botGeneration,
        long gameTick,
        String dimension,
        GridPoint position,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean onGround,
        float fallDistance,
        float health,
        float maximumHealth,
        float absorption,
        int armor,
        double armorToughness,
        double knockbackResistance,
        double movementSpeed,
        int food,
        float saturation,
        int air,
        int maximumAir,
        boolean onFire,
        boolean inLava,
        boolean underWater,
        boolean suffocating,
        int frozenTicks,
        boolean unsafeForwardSupport,
        boolean voidExposure,
        List<EffectSummary> effects,
        boolean effectsTruncated,
        List<ThreatSummary> threats,
        boolean threatCoverageIncomplete,
        Optional<SafetyRetreat> safeRetreat,
        Optional<DamageCandidate> recentDamage,
        float authoritativeVitalLoss) {
    public SafetyFrame {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L || gameTick < 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive and tick non-negative");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(velocityX)
                || !Double.isFinite(velocityY)
                || !Double.isFinite(velocityZ)
                || !Float.isFinite(fallDistance)
                || !Float.isFinite(health)
                || !Float.isFinite(maximumHealth)
                || !Float.isFinite(absorption)
                || !Double.isFinite(armorToughness)
                || !Double.isFinite(knockbackResistance)
                || !Double.isFinite(movementSpeed)
                || !Float.isFinite(saturation)
                || !Float.isFinite(authoritativeVitalLoss)) {
            throw new IllegalArgumentException(
                    "safety frame contains non-finite numbers");
        }
        effects = List.copyOf(
                Objects.requireNonNull(effects, "effects"));
        threats = List.copyOf(
                Objects.requireNonNull(threats, "threats"));
        safeRetreat = Objects.requireNonNull(safeRetreat, "safeRetreat");
        if (threatCoverageIncomplete && safeRetreat.isPresent()) {
            throw new IllegalArgumentException(
                    "incomplete threat coverage cannot prove a retreat path");
        }
        Objects.requireNonNull(recentDamage, "recentDamage");
    }

    /** 使用同一权威帧的已复核撤退候选创建副本。 */
    public SafetyFrame withSafeRetreat(Optional<SafetyRetreat> value) {
        return new SafetyFrame(
                botId,
                botGeneration,
                gameTick,
                dimension,
                position,
                velocityX,
                velocityY,
                velocityZ,
                onGround,
                fallDistance,
                health,
                maximumHealth,
                absorption,
                armor,
                armorToughness,
                knockbackResistance,
                movementSpeed,
                food,
                saturation,
                air,
                maximumAir,
                onFire,
                inLava,
                underWater,
                suffocating,
                frozenTicks,
                unsafeForwardSupport,
                voidExposure,
                effects,
                effectsTruncated,
                threats,
                threatCoverageIncomplete,
                value,
                recentDamage,
                authoritativeVitalLoss);
    }
}
