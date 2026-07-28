package io.github.greytaiwolf.botplayer.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Stable identity shared by every ServerPlayer instance created for one bot.
 *
 * <p>A vanilla respawn replaces the ServerPlayer object. AI controllers must keep this handle rather
 * than retaining the old entity. Attach and detach are server-thread lifecycle operations; volatile
 * reads only make diagnostics safe and do not authorize off-thread world access.
 */
public final class BotRuntimeHandle {
    private final UUID botId;
    private final String name;
    @Nullable
    private final UUID ownerId;
    private volatile BotServerPlayer player;
    private volatile long generation;

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

    public long generation() {
        return generation;
    }

    public void attach(BotServerPlayer newPlayer) {
        Objects.requireNonNull(newPlayer, "newPlayer");
        if (!newPlayer.getUUID().equals(botId)) {
            throw new IllegalArgumentException("Cannot attach a player with a different bot id");
        }
        if (newPlayer.runtimeHandle() != this) {
            throw new IllegalArgumentException(
                    "Cannot attach a player owned by a different runtime handle");
        }
        if (player == newPlayer) {
            return;
        }
        generation++;
        player = newPlayer;
    }

    public void detach(BotServerPlayer expectedPlayer) {
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (player == expectedPlayer) {
            generation++;
            player = null;
        }
    }

    /**
     * Invalidates world-local work while retaining the same authoritative player instance.
     */
    public void rotateGeneration(BotServerPlayer expectedPlayer) {
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (player != expectedPlayer) {
            throw new IllegalStateException(
                    "Cannot rotate a generation for a non-authoritative player");
        }
        generation++;
    }
}
