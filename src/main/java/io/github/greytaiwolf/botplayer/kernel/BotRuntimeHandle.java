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
    /*
     * 恢复 floor 后生命周期至少还需要一次 attach 与一次 detach；允许把 detached
     * handle 停在 Long.MAX_VALUE，但绝不允许下一次 ++ 悄悄翻成负数。
     */
    private static final long MAXIMUM_REBASE_FLOOR = Long.MAX_VALUE - 2L;
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

    /**
     * 在进程重启后的首次 attach 前，把内存 generation 下限重新锚定到耐久协议已经
     * 见过的代际。随后 {@link #attach(BotServerPlayer)} 仍会递增一次，因此新 body
     * 必定严格晚于 checkpoint/tombstone 中的旧 body。
     *
     * <p>这不是一般性的 generation 跳转入口：已有 body 时拒绝调用，且绝不降低当前
     * 内存代际。生命周期只能从经过完整性校验的持久记录传入 floor。
     */
    public void rebaseGenerationFloorBeforeAttach(long durableFloor) {
        if (durableFloor < 0L
                || durableFloor > MAXIMUM_REBASE_FLOOR) {
            throw new IllegalArgumentException(
                    "durable generation floor must allow one attach and detach");
        }
        if (player != null) {
            throw new IllegalStateException(
                    "Cannot rebase generation while a player is attached");
        }
        if (generation < durableFloor) {
            generation = durableFloor;
        }
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
        generation = nextGeneration("attach");
        player = newPlayer;
    }

    public void detach(BotServerPlayer expectedPlayer) {
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (player == expectedPlayer) {
            generation = nextGeneration("detach");
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
        generation = nextGeneration("rotate");
    }

    private long nextGeneration(String operation) {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Bot runtime generation is exhausted during " + operation);
        }
        try {
            return Math.incrementExact(generation);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Bot runtime generation overflow during " + operation,
                    exception);
        }
    }
}
