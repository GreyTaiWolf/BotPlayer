package io.github.greytaiwolf.botplayer.lifecycle;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

public final class BotPlayerManagers {
    private static final Map<MinecraftServer, BotLifecycleManager> MANAGERS =
            new IdentityHashMap<>();

    private BotPlayerManagers() {}

    public static synchronized BotLifecycleManager get(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, BotLifecycleManager::new);
    }

    public static synchronized Optional<BotLifecycleManager> find(MinecraftServer server) {
        return Optional.ofNullable(MANAGERS.get(server));
    }

    public static synchronized void remove(MinecraftServer server) {
        MANAGERS.remove(server);
    }
}
