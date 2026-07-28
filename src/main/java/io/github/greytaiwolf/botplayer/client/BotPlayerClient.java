package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.client.credential.CredentialStoreException;
import io.github.greytaiwolf.botplayer.client.screen.BotInventoryScreen;
import io.github.greytaiwolf.botplayer.inventory.BotPlayerMenus;
import java.nio.file.Path;
import java.util.Optional;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Physical-client bootstrap for local credential storage.
 */
@Mod(value = BotPlayer.MOD_ID, dist = Dist.CLIENT)
public final class BotPlayerClient {
    private static ClientCredentialStore credentialStore;
    private static boolean credentialStoreUnavailable;

    public BotPlayerClient(IEventBus modBus) {
        initializeCredentialStore();
        ClientPayloadHandlers.install(new PhysicalClientPayloadHandler());
        modBus.addListener(BotPlayerClient::registerMenuScreens);
    }

    private static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(
                BotPlayerMenus.BOT_INVENTORY.get(), BotInventoryScreen::new);
    }

    public static synchronized Optional<ClientCredentialStore> credentialStore() {
        if (credentialStore == null && !credentialStoreUnavailable) {
            initializeCredentialStore();
        }
        return Optional.ofNullable(credentialStore);
    }

    private static synchronized void initializeCredentialStore() {
        if (credentialStore != null || credentialStoreUnavailable) {
            return;
        }

        Path directory = FMLPaths.CONFIGDIR.get().resolve(BotPlayer.MOD_ID);
        try {
            credentialStore = new ClientCredentialStore(directory);
        } catch (CredentialStoreException exception) {
            credentialStoreUnavailable = true;
            // Do not attach the exception: malformed local content may include credential text.
            BotPlayer.LOGGER.error(
                    "BotPlayer local credential storage is unavailable; refusing to load or overwrite it");
        }
    }
}
