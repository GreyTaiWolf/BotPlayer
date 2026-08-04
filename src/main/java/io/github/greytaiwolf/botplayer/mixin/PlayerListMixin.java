package io.github.greytaiwolf.botplayer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.kernel.BotGamePacketListener;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(
            method = "save",
            require = 1,
            cancellable = true,
            at = @At("HEAD"))
    private void botplayer$suppressPersistentlyUnsafePlayerDataSave(
            ServerPlayer player, CallbackInfo callback) {
        if (player instanceof BotServerPlayer botPlayer
                && botPlayer.consumePlayerDataSaveSuppression()) {
            callback.cancel();
        }
    }

    @WrapOperation(
            method = "placeNewPlayer",
            require = 1,
            at =
                    @At(
                            value = "NEW",
                            target =
                                    "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl botplayer$createPacketListener(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie,
            Operation<ServerGamePacketListenerImpl> original) {
        if (player instanceof BotServerPlayer) {
            return new BotGamePacketListener(server, connection, player, cookie);
        }
        return original.call(server, connection, player, cookie);
    }

    @WrapOperation(
            method = "respawn",
            require = 1,
            at =
                    @At(
                            value = "NEW",
                            target =
                                    "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer botplayer$preserveBotTypeOnRespawn(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            Operation<ServerPlayer> original,
            ServerPlayer oldPlayer,
            boolean alive,
            Entity.RemovalReason reason) {
        if (oldPlayer instanceof BotServerPlayer oldBot) {
            return BotServerPlayer.recreateForRespawn(
                    server, level, profile, clientInformation, oldBot);
        }
        return original.call(server, level, profile, clientInformation);
    }

    @WrapOperation(
            method = "remove",
            require = 1,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/players/PlayerList;save(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void botplayer$suppressUnsafePlayerDataSave(
            PlayerList playerList,
            ServerPlayer player,
            Operation<Void> original) {
        if (player
                        instanceof BotServerPlayer botPlayer
                && botPlayer
                        .consumePlayerDataSaveSuppression()) {
            return;
        }
        original.call(playerList, player);
    }
}
