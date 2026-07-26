package io.github.greytaiwolf.botplayer.mixin;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes only the normal completion path of ServerPlayer.die.
 *
 * <p>NeoForge can cancel death through an early return. A TAIL injection does not run on that early
 * return, so a canceled death cannot incorrectly schedule a bot respawn.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {
    @Inject(method = "die", at = @At("TAIL"), require = 1)
    private void botplayer$afterCompletedDeath(DamageSource source, CallbackInfo callbackInfo) {
        if (!((Object) this instanceof BotServerPlayer botPlayer)) {
            return;
        }
        MinecraftServer server = botPlayer.getServer();
        if (server != null) {
            BotPlayerManagers.find(server).ifPresent(manager -> manager.onDeath(botPlayer));
        }
    }
}
