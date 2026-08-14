package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.BotPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Physical-client lifecycle hooks for client-sponsored request cancellation. */
@EventBusSubscriber(modid = BotPlayer.MOD_ID, value = Dist.CLIENT)
public final class BotPlayerClientEvents {
    private BotPlayerClientEvents() {}

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BotPlayerClient.beginAiClientConnection();
    }

    /**
     * A disconnect invalidates the owner-client session before any late Provider completion can be
     * handed to a new connection.
    */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BotPlayerClient.clearAiRequestSessions();
    }
}
