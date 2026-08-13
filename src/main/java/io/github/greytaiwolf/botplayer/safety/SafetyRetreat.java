package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.Objects;

/**
 * L0 在当前安全帧中已经复核过的短距离撤退输入。
 *
 * <p>它只携带安全邻格与相对输入，不持有 {@code ServerLevel}、实体或路径对象。P5 自卫只能
 * 消费这个 DTO，不能自行猜测方向、改写为固定后退或扩展成追击路线。
 */
public record SafetyRetreat(
        GridPoint target,
        float forwardInput,
        float strafeInput,
        int inputTicks) {
    public static final int MAXIMUM_INPUT_TICKS = 5;

    public SafetyRetreat {
        Objects.requireNonNull(target, "target");
        requireAxis(forwardInput, "forwardInput");
        requireAxis(strafeInput, "strafeInput");
        if (forwardInput == 0.0F && strafeInput == 0.0F) {
            throw new IllegalArgumentException(
                    "retreat input must move toward the safe target");
        }
        if (inputTicks < 1 || inputTicks > MAXIMUM_INPUT_TICKS) {
            throw new IllegalArgumentException(
                    "inputTicks must be between 1 and "
                            + MAXIMUM_INPUT_TICKS);
        }
    }

    private static void requireAxis(float value, String name) {
        if (!Float.isFinite(value) || value < -1.0F || value > 1.0F) {
            throw new IllegalArgumentException(
                    name + " must be finite and between -1 and 1");
        }
    }
}
