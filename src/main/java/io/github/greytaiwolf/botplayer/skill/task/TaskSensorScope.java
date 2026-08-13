package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已合法定位的局部范围；它不是全区块扫描许可。
 */
public record TaskSensorScope(
        String dimensionId,
        int centerX,
        int centerY,
        int centerZ,
        int radius,
        long scopeRevision) {
    public static final int MAX_RADIUS = 128;

    private static final Pattern DIMENSION_ID = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

    public TaskSensorScope {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (!DIMENSION_ID.matcher(dimensionId).matches()
                || dimensionId.contains("..")) {
            throw new IllegalArgumentException(
                    "dimensionId must be a safe resource location");
        }
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException(
                    "radius must be between 0 and " + MAX_RADIUS);
        }
        if (scopeRevision < 1L) {
            throw new IllegalArgumentException(
                    "scopeRevision must be positive");
        }
    }
}
