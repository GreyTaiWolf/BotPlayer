package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Stateless 1.21.1 bridge from immutable movement intent to a resolved BotServerPlayer.
 *
 * <p>The adapter never stores a player reference and never changes position, velocity, or
 * dimension. Sneak scaling mirrors normal keyboard input. Swimming remains derived vanilla
 * behavior: a swim request becomes sprint intent only while the player is in water and never calls
 * {@code setSwimming} directly.
 */
public final class MinecraftPlayerInputAdapter {
    private static final float SNEAK_INPUT_SCALE = 0.3F;

    private MinecraftPlayerInputAdapter() {}

    public static AppliedInput apply(
            BotServerPlayer player, PlayerInputState input) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        requireServerThread(player);

        float scale = input.sneak() ? SNEAK_INPUT_SCALE : 1.0F;
        float appliedForward = input.forward() * scale;
        float appliedStrafe = input.strafe() * scale;
        boolean appliedSprint = !input.sneak()
                && (input.sprint()
                        || (input.swim() && player.isInWater()));

        player.xxa = appliedStrafe;
        player.yya = 0.0F;
        player.zza = appliedForward;
        player.setJumping(input.jump());
        player.setShiftKeyDown(input.sneak());
        player.setSprinting(appliedSprint);

        return new AppliedInput(
                appliedForward,
                appliedStrafe,
                input.jump(),
                player.isShiftKeyDown(),
                player.isSprinting(),
                input.swim(),
                player.isInWater(),
                player.isSwimming());
    }

    public static AppliedInput clear(BotServerPlayer player) {
        return apply(player, PlayerInputState.IDLE);
    }

    /**
     * Synchronizes the listener baseline and chunk tracking after vanilla physics consumed input.
     *
     * <p>This does not move or teleport the player; it only publishes the position already produced
     * by normal player physics.
     */
    public static void syncAfterPhysics(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread(player);
        if (player.connection == null) {
            throw new IllegalStateException(
                    "Cannot synchronize input movement without a player connection");
        }
        player.connection.resetPosition();
        player.serverLevel().getChunkSource().move(player);
    }

    private static void requireServerThread(BotServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "Player input must be applied on the Minecraft server thread");
        }
    }

    /** Immutable primitive-only evidence from one application. */
    public record AppliedInput(
            float forward,
            float strafe,
            boolean jumpCommanded,
            boolean sneaking,
            boolean sprinting,
            boolean swimCommanded,
            boolean inWater,
            boolean swimming) {}
}
