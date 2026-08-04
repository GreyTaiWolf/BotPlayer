package io.github.greytaiwolf.botplayer.safety;

import java.util.Objects;
import java.util.UUID;

/**
 * 不携带 Minecraft 活对象的安全恢复请求。
 */
public record SafetyHandoffRequest(
        UUID incidentId,
        UUID botId,
        long botGeneration,
        long currentTick,
        HazardAssessment hazard,
        SafetyFrame frame) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public SafetyHandoffRequest {
        requireNonZero(incidentId, "incidentId");
        requireNonZero(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must not be negative");
        }
        Objects.requireNonNull(hazard, "hazard");
        Objects.requireNonNull(frame, "frame");
        if (!botId.equals(frame.botId())
                || botGeneration != frame.botGeneration()
                || currentTick != frame.gameTick()) {
            throw new IllegalArgumentException(
                    "Safety handoff identity must match its frame");
        }
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
