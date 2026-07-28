package io.github.greytaiwolf.botplayer.lifecycle;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.Objects;
import java.util.Optional;

public record BotActionTarget(
        BotActionTargetStatus status,
        long actualGeneration,
        Optional<BotServerPlayer> player) {
    public BotActionTarget {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(player, "player");
        if (actualGeneration < 0) {
            throw new IllegalArgumentException(
                    "actualGeneration must not be negative");
        }
        if ((status == BotActionTargetStatus.ACTIVE) != player.isPresent()) {
            throw new IllegalArgumentException(
                    "Only ACTIVE action targets may contain a player");
        }
    }
}
