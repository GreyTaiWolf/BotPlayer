package io.github.greytaiwolf.botplayer.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HazardEvaluator {
    private final SafetySettings settings;

    public HazardEvaluator(SafetySettings settings) {
        this.settings = java.util.Objects.requireNonNull(
                settings, "settings");
    }

    public List<HazardAssessment> evaluate(SafetyFrame frame) {
        java.util.Objects.requireNonNull(frame, "frame");
        List<HazardAssessment> hazards = new ArrayList<>();
        if (frame.voidExposure()) {
            hazards.add(hazard(
                    HazardType.VOID_EXPOSURE,
                    HazardSeverity.EMERGENCY,
                    0,
                    false,
                    false,
                    Optional.empty(),
                    "玩家身体处于虚空风险高度"));
        }
        if (frame.inLava()) {
            hazards.add(hazard(
                    HazardType.LAVA_CONTACT,
                    HazardSeverity.EMERGENCY,
                    0,
                    true,
                    true,
                    Optional.empty(),
                    "玩家身体接触熔岩"));
        }
        if (frame.onFire()) {
            hazards.add(hazard(
                    HazardType.FIRE_CONTACT,
                    frame.health() <= settings.criticalHealth() * 2.0F
                            ? HazardSeverity.EMERGENCY
                            : HazardSeverity.URGENT,
                    1,
                    true,
                    true,
                    Optional.empty(),
                    "玩家身体正在燃烧"));
        }
        if (frame.suffocating()) {
            hazards.add(hazard(
                    HazardType.SUFFOCATING,
                    HazardSeverity.EMERGENCY,
                    0,
                    true,
                    true,
                    Optional.empty(),
                    "玩家身体正在方块中窒息"));
        }
        if (frame.underWater()
                && frame.air() <= settings.criticalAir()) {
            hazards.add(hazard(
                    HazardType.DROWNING,
                    frame.air() <= 0
                            ? HazardSeverity.EMERGENCY
                            : HazardSeverity.URGENT,
                    Math.max(0, frame.air()),
                    true,
                    frame.air() <= 0,
                    Optional.empty(),
                    "水下空气进入安全阈值"));
        }
        if (frame.frozenTicks() >= settings.criticalFrozenTicks()) {
            hazards.add(hazard(
                    HazardType.FREEZING,
                    HazardSeverity.URGENT,
                    20,
                    true,
                    frame.authoritativeVitalLoss() > 0.0F,
                    Optional.empty(),
                    "冻结 Tick 进入安全阈值"));
        }
        if (frame.unsafeForwardSupport()) {
            hazards.add(hazard(
                    HazardType.FALL_IMMINENT,
                    frame.fallDistance() > 0.0F
                            ? HazardSeverity.EMERGENCY
                            : HazardSeverity.URGENT,
                    0,
                    true,
                    false,
                    Optional.empty(),
                    "前方缺少 policy 允许范围内的稳定支撑"));
        }
        if (frame.health() <= settings.criticalHealth()) {
            hazards.add(hazard(
                    HazardType.HEALTH_CRITICAL,
                    HazardSeverity.URGENT,
                    0,
                    true,
                    frame.authoritativeVitalLoss() > 0.0F,
                    Optional.empty(),
                    "当前生命值进入临界阈值"));
        }
        if (frame.food() <= settings.criticalFood()) {
            hazards.add(hazard(
                    HazardType.FOOD_CRITICAL,
                    HazardSeverity.WARNING,
                    0,
                    true,
                    false,
                    Optional.empty(),
                    "当前食物值进入临界阈值"));
        }
        if (frame.authoritativeVitalLoss() > 0.0F) {
            DamageCandidate damage =
                    frame.recentDamage().orElse(null);
            hazards.add(hazard(
                    damage == null
                            ? HazardType.UNKNOWN_DAMAGE
                            : HazardType.ONGOING_DAMAGE,
                    frame.health() <= settings.criticalHealth()
                            ? HazardSeverity.EMERGENCY
                            : HazardSeverity.URGENT,
                    0,
                    true,
                    true,
                    damage == null
                            ? Optional.empty()
                            : damage.causingEntityId(),
                    damage == null
                            ? "身体权威生命或吸收值下降，但来源未知"
                            : "真实伤害类型=" + damage.damageTypeId()));
        }
        boolean harmful = frame.effects().stream()
                .anyMatch(effect ->
                        effect.category()
                                == EffectSummary.Category.HARMFUL);
        if (harmful) {
            hazards.add(hazard(
                    HazardType.HARMFUL_EFFECT,
                    frame.authoritativeVitalLoss() > 0.0F
                            ? HazardSeverity.URGENT
                            : HazardSeverity.WARNING,
                    20,
                    true,
                    frame.authoritativeVitalLoss() > 0.0F,
                    Optional.empty(),
                    "身体存在动态注册表中的有害效果"));
        }
        for (ThreatSummary threat : frame.threats()) {
            if (threat.kind() == ThreatSummary.Kind.EXPLOSIVE
                    && threat.distance()
                            <= settings.explosionRadius()) {
                hazards.add(hazard(
                        HazardType.EXPLOSION_IMMINENT,
                        HazardSeverity.EMERGENCY,
                        Math.max(
                                0,
                                (int) Math.round(
                                        threat.distance() * 2.0D)),
                        true,
                        false,
                        Optional.of(threat.entityId()),
                        "近场已点燃爆炸物"));
            } else if (threat.kind()
                            == ThreatSummary.Kind.PROJECTILE
                    && threat.approachScore() > 0.0D
                    && threat.distance()
                            <= settings.projectileRadius()) {
                hazards.add(hazard(
                        HazardType.PROJECTILE_IMPACT,
                        HazardSeverity.EMERGENCY,
                        Math.max(
                                0,
                                (int) Math.round(threat.distance())),
                        true,
                        false,
                        Optional.of(threat.entityId()),
                        "弹射物速度朝向玩家身体"));
            } else if (threat.kind()
                            == ThreatSummary.Kind.HOSTILE
                    && threat.targetingBot()
                    && threat.distance()
                            <= settings.hostileRadius()) {
                hazards.add(hazard(
                        HazardType.HOSTILE_TARGETING,
                        threat.distance() <= 3.0D
                                ? HazardSeverity.EMERGENCY
                                : HazardSeverity.URGENT,
                        Math.max(
                                0,
                                (int) Math.round(threat.distance() * 4.0D)),
                        true,
                        false,
                        Optional.of(threat.entityId()),
                        "敌对生物的原版 target 正在指向 bot"));
            }
        }
        hazards.sort(HazardAssessment.PRIORITY);
        return List.copyOf(hazards);
    }

    public Optional<HazardAssessment> primary(SafetyFrame frame) {
        List<HazardAssessment> hazards = evaluate(frame);
        return hazards.isEmpty()
                ? Optional.empty()
                : Optional.of(hazards.getFirst());
    }

    private static HazardAssessment hazard(
            HazardType type,
            HazardSeverity severity,
            int impactTicks,
            boolean reversible,
            boolean alreadyDamaging,
            Optional<java.util.UUID> source,
            String evidence) {
        return new HazardAssessment(
                type,
                severity,
                impactTicks,
                reversible,
                alreadyDamaging,
                source,
                evidence);
    }
}
