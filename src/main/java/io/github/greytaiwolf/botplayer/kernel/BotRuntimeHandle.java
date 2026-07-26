package io.github.greytaiwolf.botplayer.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Stable identity shared by every ServerPlayer instance created for one bot.
 *
 * <p>A vanilla respawn replaces the ServerPlayer object. AI controllers must keep this handle rather
 * than retaining the old entity.
 */
public final class BotRuntimeHandle {
    private final UUID botId;
    private final String name;
    @Nullable
    private final UUID ownerId;
    private volatile BotServerPlayer player;

    public BotRuntimeHandle(UUID botId, String name, @Nullable UUID ownerId) {
        this.botId = Objects.requireNonNull(botId);
        this.name = Objects.requireNonNull(name);
        this.ownerId = ownerId;
    }

    public UUID botId() {
        return botId;
    }

    public String name() {
        return name;
    }

    public Optional<UUID> ownerId() {
        return Optional.ofNullable(ownerId);
    }

    public Optional<BotServerPlayer> player() {
        return Optional.ofNullable(player);
    }

    public void attach(BotServerPlayer newPlayer) {
        if (!newPlayer.getUUID().equals(botId)) {
            throw new IllegalArgumentException("Cannot attach a player with a different bot id");
        }
        player = newPlayer;
    }

    public void detach(BotServerPlayer expectedPlayer) {
        if (player == expectedPlayer) {
            player = null;
        }
    }
}
