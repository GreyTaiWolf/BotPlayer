package io.github.greytaiwolf.botplayer.client.credential;

import java.util.Objects;
import java.util.UUID;

/**
 * A client-local binding scope. The server instance prevents same-named bots on different servers
 * from sharing a binding accidentally.
 */
public record CredentialBindingKey(UUID serverInstanceId, UUID ownerUuid, UUID botId) {
    public CredentialBindingKey {
        Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(botId, "botId");
    }
}
