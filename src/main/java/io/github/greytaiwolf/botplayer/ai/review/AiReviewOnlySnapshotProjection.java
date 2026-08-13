package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P6-R1 唯一允许发往 owner 本地 Provider 的当前感知投影。
 *
 * <p>它刻意不带坐标、背包、实体名称/类型、方块、活动、声音、事件、效果、聊天或记忆。维度 ID
 * 只接受 Minecraft 资源标识符形状；其余字段均为有限数值。调用方必须通过
 * {@link #fromCurrent(ObservationSnapshot, UUID, long, long)} 创建，不能把旧 generation 或旧 Tick
 * 快照改写成当前审阅输入。唯一例外是 owner 手动命令的
 * {@link #fromRequestAiReviewCompletedSnapshot(ObservationSnapshot, UUID, long, long)}：它只接受
 * 已完成的当前或紧邻前一 Tick 快照，且保留原始 snapshot id 与 Tick。
 */
public record AiReviewOnlySnapshotProjection(
        UUID botId,
        long generation,
        long snapshotId,
        long gameTick,
        String dimensionId,
        float health,
        int food,
        int air,
        int threatCount) {
    public static final int MAX_ABSOLUTE_AIR = 1_000_000;
    public static final float MAX_HEALTH = 1_000_000.0F;
    private static final Pattern DIMENSION_ID = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

    public AiReviewOnlySnapshotProjection {
        requireNonZero(botId, "botId");
        if (generation <= 0L || snapshotId <= 0L || gameTick < 0L) {
            throw new IllegalArgumentException("review snapshot identity is invalid");
        }
        dimensionId = requireDimensionId(dimensionId);
        if (!Float.isFinite(health) || health < 0.0F || health > MAX_HEALTH) {
            throw new IllegalArgumentException("review health is outside bounds");
        }
        if (food < 0 || food > 20) {
            throw new IllegalArgumentException("review food is outside bounds");
        }
        if (air < -MAX_ABSOLUTE_AIR || air > MAX_ABSOLUTE_AIR) {
            throw new IllegalArgumentException("review air is outside bounds");
        }
        if (threatCount < 0 || threatCount > ObservationSnapshot.MAX_THREATS) {
            throw new IllegalArgumentException("review threat count is outside bounds");
        }
    }

    /**
     * Projects only the current, exact body snapshot. A missing, stale or mismatched snapshot is
     * rejected rather than refreshed by an ad-hoc world read.
     */
    public static AiReviewOnlySnapshotProjection fromCurrent(
            ObservationSnapshot snapshot,
            UUID expectedBotId,
            long expectedGeneration,
            long currentTick) {
        ObservationSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        UUID checkedBotId = requireNonZero(expectedBotId, "expectedBotId");
        if (expectedGeneration <= 0L || currentTick < 0L) {
            throw new IllegalArgumentException("expected review generation/tick is invalid");
        }
        if (!hasExpectedActiveBody(checked, checkedBotId, expectedGeneration)
                || checked.gameTick() != currentTick) {
            throw new IllegalArgumentException("review snapshot is not current for the active body");
        }
        return project(checked, checkedBotId, expectedGeneration);
    }

    /**
     * The narrowly scoped projection for {@code BotLifecycleManager.requestAiReview}.
     *
     * <p>Perception sampling completes during server-tick post, while a command may run before
     * that phase. This factory therefore accepts only the immutable completed snapshot at the
     * command tick or exactly one tick before it. It never refreshes data from the world, rewrites
     * the snapshot tick/id, accepts a future snapshot, or accepts a snapshot two or more ticks
     * old. Other callers must keep using {@link #fromCurrent(ObservationSnapshot, UUID, long,
     * long)}.
     */
    public static AiReviewOnlySnapshotProjection fromRequestAiReviewCompletedSnapshot(
            ObservationSnapshot snapshot,
            UUID expectedBotId,
            long expectedGeneration,
            long commandTick) {
        ObservationSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        UUID checkedBotId = requireNonZero(expectedBotId, "expectedBotId");
        if (expectedGeneration <= 0L || commandTick < 0L) {
            throw new IllegalArgumentException("expected review generation/tick is invalid");
        }
        if (!hasExpectedActiveBody(checked, checkedBotId, expectedGeneration)) {
            throw new IllegalArgumentException("review snapshot is not for the active body");
        }
        if (checked.gameTick() > commandTick
                || commandTick - checked.gameTick() > 1L) {
            throw new IllegalArgumentException(
                    "review snapshot is not a completed current/previous command snapshot");
        }
        return project(checked, checkedBotId, expectedGeneration);
    }

    private static boolean hasExpectedActiveBody(
            ObservationSnapshot snapshot, UUID expectedBotId, long expectedGeneration) {
        return snapshot.botId().equals(expectedBotId)
                && snapshot.botGeneration() == expectedGeneration;
    }

    private static AiReviewOnlySnapshotProjection project(
            ObservationSnapshot snapshot, UUID botId, long generation) {
        return new AiReviewOnlySnapshotProjection(
                botId,
                generation,
                snapshot.snapshotId(),
                snapshot.gameTick(),
                snapshot.dimension(),
                snapshot.self().health(),
                snapshot.self().food(),
                snapshot.self().air(),
                snapshot.threats().size());
    }

    /** Fixed grammar used as the only dynamic USER message in P6-R1. */
    public String canonicalUserMessage() {
        return "snapshot_id=" + snapshotId
                + "\ntick=" + gameTick
                + "\ndimension_id=" + dimensionId
                + "\nhealth=" + Float.toString(health)
                + "\nfood=" + food
                + "\nair=" + air
                + "\nthreat_count=" + threatCount;
    }

    /** Validates a received USER message without retaining or interpreting any other text. */
    public static boolean isCanonicalUserMessage(String value) {
        try {
            String[] lines = Objects.requireNonNull(value, "value").split("\\n", -1);
            if (lines.length != 7) {
                return false;
            }
            long snapshotId = parsePositiveLong(lines[0], "snapshot_id=");
            long tick = parseNonNegativeLong(lines[1], "tick=");
            String dimensionId = valueAfter(lines[2], "dimension_id=");
            float health = parseCanonicalHealth(valueAfter(lines[3], "health="));
            int food = parseBoundedInt(valueAfter(lines[4], "food="), 0, 20);
            int air = parseBoundedInt(
                    valueAfter(lines[5], "air="), -MAX_ABSOLUTE_AIR, MAX_ABSOLUTE_AIR);
            int threats = parseBoundedInt(
                    valueAfter(lines[6], "threat_count="),
                    0,
                    ObservationSnapshot.MAX_THREATS);
            /* Reuse every invariant, including the narrow dimension grammar. */
            new AiReviewOnlySnapshotProjection(
                    new UUID(0L, 1L),
                    1L,
                    snapshotId,
                    tick,
                    dimensionId,
                    health,
                    food,
                    air,
                    threats);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static long parsePositiveLong(String line, String prefix) {
        long value = parseNonNegativeLong(line, prefix);
        if (value <= 0L) {
            throw new IllegalArgumentException("value must be positive");
        }
        return value;
    }

    private static long parseNonNegativeLong(String line, String prefix) {
        String value = valueAfter(line, prefix);
        if (value.isEmpty() || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("integer is not canonical");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("integer overflow", exception);
        }
    }

    private static int parseBoundedInt(String value, int minimum, int maximum) {
        if (!value.matches("-?(?:0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("integer is not canonical");
        }
        if ("-0".equals(value)) {
            throw new IllegalArgumentException("integer is not canonical");
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("integer overflow", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("integer is outside bounds");
        }
        return parsed;
    }

    private static float parseCanonicalHealth(String value) {
        final float parsed;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("health is malformed", exception);
        }
        if (!Float.isFinite(parsed)
                || parsed < 0.0F
                || parsed > MAX_HEALTH
                || !Float.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("health is not canonical");
        }
        return parsed;
    }

    private static String valueAfter(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new IllegalArgumentException("unexpected review field");
        }
        return line.substring(prefix.length());
    }

    private static String requireDimensionId(String value) {
        String checked = Objects.requireNonNull(value, "dimensionId");
        if (!DIMENSION_ID.matcher(checked).matches()) {
            throw new IllegalArgumentException("dimensionId is invalid");
        }
        return checked;
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID checked = Objects.requireNonNull(value, name);
        if (checked.getMostSignificantBits() == 0L
                && checked.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
        return checked;
    }

    /**
     * The string form intentionally reports no body identity, projected values, dimension id, or
     * user-message text. The accepted receipt has a separate deliberately small numeric summary.
     */
    @Override
    public String toString() {
        return "AiReviewOnlySnapshotProjection[fixedFieldCount=7]";
    }
}
