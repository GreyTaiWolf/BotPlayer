package io.github.greytaiwolf.botplayer.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Provisional stable player UUID generation used until the roster SavedData migration lands.
 *
 * <p>Names are immutable in this phase. A later roster schema will store a random bot id and player
 * UUID once, allowing display-name changes without changing playerdata ownership.
 */
public final class BotIdentityIds {
    private static final String NAMESPACE = "io.github.greytaiwolf.botplayer/player/";

    private BotIdentityIds() {}

    public static UUID forImmutableName(String name) {
        String key = NAMESPACE + name.toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
