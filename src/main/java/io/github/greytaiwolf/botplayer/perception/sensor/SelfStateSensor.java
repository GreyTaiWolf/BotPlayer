package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SelfObservation;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 读取 bot 自身权威生存状态，不扫描世界或其他实体。
 */
public final class SelfStateSensor implements BotSensor {
    private static final int MAX_EFFECT_READS = 64;

    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(1, 1, 1);

    private final SensorSchedule schedule;

    public SelfStateSensor() {
        this(DEFAULT_SCHEDULE);
    }

    public SelfStateSensor(SensorSchedule schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.SELF_STATE;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        return SensorCost.none();
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        BotServerPlayer player = context.player();
        Map<String, String> effects = new TreeMap<>();
        boolean truncated = false;
        int effectReads = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effectReads++ >= MAX_EFFECT_READS) {
                truncated = true;
                break;
            }
            try {
                String effectId = BuiltInRegistries.MOB_EFFECT
                        .getKey(effect.getEffect().value())
                        .toString();
                effects.put(
                        effectId,
                        "amplifier="
                                + effect.getAmplifier()
                                + ",duration="
                                + effect.getDuration()
                                + ",ambient="
                                + effect.isAmbient()
                                + ",visible="
                                + effect.isVisible());
            } catch (RuntimeException exception) {
                // 单个第三方效果异常时仅省略该效果。
                truncated = true;
            }
        }
        try {
            SelfObservation observation = new SelfObservation(
                    SensorSupport.point(player.position()),
                    SensorSupport.point(player.getDeltaMovement()),
                    player.getYRot(),
                    player.getXRot(),
                    player.getHealth(),
                    player.getMaxHealth(),
                    player.getFoodData().getFoodLevel(),
                    player.getFoodData().getSaturationLevel(),
                    player.getAirSupply(),
                    player.getMaxAirSupply(),
                    player.isOnFire(),
                    player.isUnderWater(),
                    player.onGround(),
                    player.isSprinting(),
                    player.isCrouching(),
                    SensorSupport.lowerCaseName(player.getPose()),
                    effects);
            return new SensorResult.SelfState(
                    observation, truncated);
        } catch (RuntimeException exception) {
            return new SensorResult.Unavailable(
                    id(),
                    SensorResult.Reason.SENSOR_FAILURE,
                    true);
        }
    }
}
