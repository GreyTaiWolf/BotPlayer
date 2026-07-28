package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 单次采样期间使用的主线程上下文。
 *
 * <p>该对象故意保留 Minecraft 活动引用，因此不能缓存、跨 Tick 保存或交给异步线程。
 * 每个访问器都会重新验证服务器线程和维度，泄漏到异步线程时默认失败。
 */
public final class SensorContext {
    public static final int MAX_FOCUS_POSITIONS = 64;
    public static final int MAX_RECENT_EVENTS = 512;

    private final BotServerPlayer player;
    private final ServerLevel level;
    private final long gameTick;
    private final Optional<BlockPos> lookedAtBlock;
    private final List<BlockPos> focusPositions;
    private final List<PerceivedEvent> recentEvents;

    public SensorContext(BotServerPlayer player, long gameTick) {
        this(
                player,
                gameTick,
                Optional.empty(),
                List.of(),
                List.of());
    }

    public SensorContext(
            BotServerPlayer player,
            long gameTick,
            Optional<BlockPos> lookedAtBlock,
            List<BlockPos> focusPositions,
            List<PerceivedEvent> recentEvents) {
        this.player = Objects.requireNonNull(player, "player");
        if (gameTick < 0) {
            throw new IllegalArgumentException("gameTick must not be negative");
        }
        this.gameTick = gameTick;
        requireMainThread(player);
        this.level = player.serverLevel();
        this.lookedAtBlock = copyPosition(
                Objects.requireNonNull(lookedAtBlock, "lookedAtBlock"));
        this.focusPositions = copyPositions(focusPositions);
        this.recentEvents = copyEvents(recentEvents);
    }

    public BotServerPlayer player() {
        assertMainThread();
        return player;
    }

    public ServerLevel level() {
        assertMainThread();
        return level;
    }

    public long gameTick() {
        assertMainThread();
        return gameTick;
    }

    public Optional<BlockPos> lookedAtBlock() {
        assertMainThread();
        return lookedAtBlock;
    }

    public List<BlockPos> focusPositions() {
        assertMainThread();
        return focusPositions;
    }

    public List<PerceivedEvent> recentEvents() {
        assertMainThread();
        return recentEvents;
    }

    public void assertMainThread() {
        requireMainThread(player);
        if (player.serverLevel() != level) {
            throw new IllegalStateException(
                    "sensor context became stale after a dimension change");
        }
    }

    private static Optional<BlockPos> copyPosition(
            Optional<BlockPos> position) {
        return position.map(BlockPos::immutable);
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        Objects.requireNonNull(positions, "focusPositions");
        if (positions.size() > MAX_FOCUS_POSITIONS) {
            throw new IllegalArgumentException(
                    "focusPositions exceeds maximum size "
                            + MAX_FOCUS_POSITIONS);
        }
        LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
        positions.forEach(position -> unique.add(
                Objects.requireNonNull(position, "focus position").immutable()));
        /*
         * 调用方按证据新旧提供确定性顺序；这里保留首次出现顺序，不能按坐标
         * 重排后让低预算采样选中更旧的焦点。
         */
        return List.copyOf(unique);
    }

    private static List<PerceivedEvent> copyEvents(
            List<PerceivedEvent> events) {
        Objects.requireNonNull(events, "recentEvents");
        if (events.size() > MAX_RECENT_EVENTS) {
            throw new IllegalArgumentException(
                    "recentEvents exceeds maximum size "
                            + MAX_RECENT_EVENTS);
        }
        events.forEach(event ->
                Objects.requireNonNull(event, "recent event"));
        return List.copyOf(events);
    }

    private static void requireMainThread(BotServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "sensor context requires the authoritative server thread");
        }
    }
}
