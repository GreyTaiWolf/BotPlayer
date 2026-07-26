package io.github.greytaiwolf.botplayer.kernel;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
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
        return new BotServerPlayer(
                server, level, profile, clientInformation, oldPlayer.runtimeHandle);
    }

    public BotRuntimeHandle runtimeHandle() {
        return runtimeHandle;
    }

    @Override
    public void tick() {
        super.tick();
        doTick();

        int refreshInterval = BotPlayerConfig.CHUNK_TRACKING_REFRESH_TICKS.get();
        if (connection != null && server.getTickCount() % refreshInterval == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
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
            if (currentPlayer instanceof BotServerPlayer currentBot) {
                runtimeHandle.attach(currentBot);
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
