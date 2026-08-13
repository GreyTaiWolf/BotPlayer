package io.github.greytaiwolf.botplayer.mixin;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a strict consumable immediately before vanilla advances its native use
 * state.
 *
 * <p>This is intentionally injected into {@code updateUsingItem}, rather than
 * around a lifecycle Post tick. A final-tick effect drift can be introduced by
 * normal {@code PlayerTickEvent.Pre} listeners after the lifecycle has last
 * observed the player. Cancelling only this method preserves the outer
 * {@code LivingEntity} tick bookkeeping while preventing vanilla from
 * consuming the item or clearing an unapproved effect.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityUseItemMixin {
    @Inject(
            method = "updateUsingItem(Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void botplayer$beforeNativeItemUseUpdate(
            ItemStack stack, CallbackInfo callbackInfo) {
        if (!((Object) this instanceof BotServerPlayer botPlayer)) {
            return;
        }
        MinecraftServer server = botPlayer.getServer();
        if (server == null) {
            try {
                botPlayer.stopUsingItem();
            } finally {
                callbackInfo.cancel();
            }
            return;
        }
        try {
            boolean reject = BotPlayerManagers.find(server)
                    .map(manager -> manager.beforeNativeItemUseUpdate(botPlayer))
                    /* A Bot body without its server-owned action fence may not consume. */
                    .orElse(true);
            if (reject) {
                /*
                 * The strict backend normally already sent RELEASE_USE_ITEM
                 * before it asks us to suppress vanilla's update.  Calling
                 * stopUsingItem again is harmless there, and is essential for
                 * the stale/no-manager path: merely cancelling this update
                 * would otherwise leave the native use state live forever.
                 */
                try {
                    botPlayer.stopUsingItem();
                } finally {
                    callbackInfo.cancel();
                }
            }
        } catch (RuntimeException exception) {
            /*
             * The hook has reached a strict bot action but could not consult
             * its server-owned fence. Do not let a potentially unsafe
             * consumable advance merely because the observer failed.
             */
            try {
                botPlayer.stopUsingItem();
            } finally {
                callbackInfo.cancel();
            }
        }
    }
}
