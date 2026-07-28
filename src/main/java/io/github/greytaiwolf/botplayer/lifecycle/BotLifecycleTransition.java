package io.github.greytaiwolf.botplayer.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record BotLifecycleTransition(
        UUID botId,
        long generation,
        BotLifecycleState previous,
        BotLifecycleState current,
        long serverTick) {
    public BotLifecycleTransition {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick must not be negative");
        }
        previous.requireTransitionTo(current);
    }
}
