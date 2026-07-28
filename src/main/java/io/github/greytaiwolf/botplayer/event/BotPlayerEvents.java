package io.github.greytaiwolf.botplayer.event;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.command.BotPlayerCommands;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = BotPlayer.MOD_ID)
public final class BotPlayerEvents {
    private BotPlayerEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BotPlayerCommands.register(event);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        BotPlayerManagers.get(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BotPlayerManagers.find(event.getServer()).ifPresent(manager -> manager.tick());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof BotServerPlayer botPlayer)) {
            return;
        }
        MinecraftServer server = botPlayer.getServer();
        if (server != null) {
            BotPlayerManagers.find(server)
                    .ifPresent(
                            manager ->
                                    manager.onRespawnCandidate(botPlayer));
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(
            PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            if (player instanceof BotServerPlayer botPlayer) {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onChangedDimension(botPlayer));
            } else {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onRealPlayerChangedDimension(
                                        player));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player instanceof BotServerPlayer) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            BotPlayerManagers.find(server)
                    .ifPresent(manager -> manager.onRealPlayerLogout(player));
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)
                || viewer instanceof BotServerPlayer
                || !(event.getTarget() instanceof BotServerPlayer bot)
                || event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().isEmpty()) {
            return;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        BotInventorySessionManager.OpenStatus status =
                BotPlayerManagers.find(server)
                        .map(manager -> manager.openInventory(viewer, bot))
                        .orElse(BotInventorySessionManager.OpenStatus.SERVER_STOPPING);
        if (status == BotInventorySessionManager.OpenStatus.OPENING
                || status
                        == BotInventorySessionManager.OpenStatus
                                .EXISTING_SESSION) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        try {
            BotPlayerManagers.find(event.getServer()).ifPresent(manager -> manager.shutdown());
        } finally {
            BotPlayerManagers.remove(event.getServer());
        }
    }
}
