package io.github.greytaiwolf.botplayer.event;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.command.BotPlayerCommands;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
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
            BotPlayerManagers.find(server).ifPresent(manager -> manager.onRespawn(botPlayer));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof BotServerPlayer botPlayer)) {
            return;
        }
        MinecraftServer server = botPlayer.getServer();
        if (server != null) {
            BotPlayerManagers.find(server).ifPresent(manager -> manager.onDeath(botPlayer));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BotPlayerManagers.find(event.getServer()).ifPresent(manager -> manager.shutdown());
        BotPlayerManagers.remove(event.getServer());
    }
}
