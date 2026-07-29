package io.github.greytaiwolf.botplayer.navigation;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record NavigationRequest(
        UUID navigationId,
        UUID botId,
        long botGeneration,
        NavigationGoal goal,
        NavigationPolicy policy,
        long deadlineTick,
        int maximumDurationTicks,
        String idempotencyKey) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    public NavigationRequest {
        requireUuid(navigationId, "navigationId");
        requireUuid(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(policy, "policy");
        if (deadlineTick < 0L) {
            throw new IllegalArgumentException("deadlineTick must not be negative");
        }
        if (maximumDurationTicks < 1 || maximumDurationTicks > 72_000) {
            throw new IllegalArgumentException(
                    "maximumDurationTicks must be between 1 and 72000");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be 1-128 safe ASCII characters");
        }
    }

    private static void requireUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
    }
}
