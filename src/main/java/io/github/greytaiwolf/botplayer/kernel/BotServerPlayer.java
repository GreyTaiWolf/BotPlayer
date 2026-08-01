package io.github.greytaiwolf.botplayer.kernel;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A real online ServerPlayer controlled by BotPlayer.
 *
 * <p>This deliberately does not extend NeoForge FakePlayer and does not report {@code isFakePlayer}.
 * Stats, advancements, inventory NBT, scoreboards, sleeping and other player semantics therefore use
 * the normal vanilla paths.
 */
public final class BotServerPlayer extends ServerPlayer {
    private final BotRuntimeHandle runtimeHandle;
    private int lastClientlessConnectionTick = Integer.MIN_VALUE;
    private boolean suppressNextPlayerDataSave;
    private boolean suppressPlayerDataSaveUntilReleased;

    public BotServerPlayer(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            BotRuntimeHandle runtimeHandle) {
        super(server, level, profile, clientInformation);
        this.runtimeHandle = runtimeHandle;
    }

    public static BotServerPlayer recreateForRespawn(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            BotServerPlayer oldPlayer) {
        BotServerPlayer replacement = new BotServerPlayer(
                server, level, profile, clientInformation, oldPlayer.runtimeHandle);
        if (oldPlayer.suppressPlayerDataSaveUntilReleased) {
            replacement.suppressPlayerDataSaveUntilReleased();
        }
        return replacement;
    }

    public BotRuntimeHandle runtimeHandle() {
        return runtimeHandle;
    }

    /**
     * Arms a one-shot guard used only by lifecycle fail-closed removal when
     * persisting this body could commit an unverified temporary inventory
     * layout.
     */
    public void suppressNextPlayerDataSave() {
        suppressNextPlayerDataSave = true;
    }

    /**
     * 在 no-save 隔离尚未确认移除 exact body 前，拒绝该 body 的每一次保存。
     * 这条 fence 不能被单次 save 消费；只有身份移除确认后才能显式释放。
     */
    public void suppressPlayerDataSaveUntilReleased() {
        suppressPlayerDataSaveUntilReleased = true;
    }

    public boolean consumePlayerDataSaveSuppression() {
        if (suppressPlayerDataSaveUntilReleased) {
            return true;
        }
        boolean suppressed =
                suppressNextPlayerDataSave;
        suppressNextPlayerDataSave = false;
        return suppressed;
    }

    public void clearPlayerDataSaveSuppression() {
        /* 单次 remove 的门闩可清理；跨调用 fence 只能由确认移除路径释放。 */
        suppressNextPlayerDataSave = false;
    }

    public void releasePlayerDataSaveSuppression() {
        suppressNextPlayerDataSave = false;
        suppressPlayerDataSaveUntilReleased = false;
    }

    @Override
    public void tick() {
        /*
         * ServerLevel owns the ServerPlayer housekeeping phase, EntityTickEvent, freeze and
         * ticking-range semantics. The lifecycle manager separately supplies the connection-owned
         * Player#doTick phase that advances LivingEntity physics and tickCount; BotConnection
         * never receives that phase from the physical connection list.
         */
        super.tick();
    }

    /**
     * Runs the connection-owned half of a real player's tick once per absolute server tick.
     *
     * <p>This method does not increment {@link #tickCount} directly. Its guarded {@link #doTick()}
     * call advances that counter exactly once and still emits NeoForge PlayerTickEvent.Pre/Post, so
     * other mods observe the normal event path.
     */
    public void tickClientlessConnectionPhase() {
        MinecraftServer server = getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "BotPlayer connection phase must run on the server thread");
        }
        int currentTick = server.getTickCount();
        if (lastClientlessConnectionTick == currentTick) {
            return;
        }
        lastClientlessConnectionTick = currentTick;

        BotPlayerManagers.find(server)
                .ifPresent(manager -> manager.applyPlayerInput(this));
        doTick();
        BotPlayerManagers.find(server)
                .ifPresent(manager -> manager.syncPlayerInputAfterPhysics(this));
        if (connection instanceof BotGamePacketListener botListener) {
            botListener.tickVirtualProtocol();
        }
    }

    @Override
    public @Nullable Entity changeDimension(@NotNull DimensionTransition transition) {
        Entity result = super.changeDimension(transition);
        if (result == null) {
            return null;
        }

        if (wonGame && connection != null) {
            connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }

        if (connection != null) {
            ServerPlayer currentPlayer = connection.player;
            if (currentPlayer.isChangingDimension()) {
                currentPlayer.hasChangedDimension();
            }
            if (currentPlayer instanceof BotServerPlayer currentBot
                    && currentBot != this) {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onConnectionPlayerReplaced(
                                        this, currentBot));
            }
            return currentPlayer;
        }
        return result;
    }

    @Override
    public @NotNull String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return BotPlayerConfig.SHOW_IN_PLAYER_LIST.get();
    }
}
