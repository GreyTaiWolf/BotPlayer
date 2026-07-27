package io.github.greytaiwolf.botplayer.client.credential;

import java.util.Objects;
import java.util.UUID;

/**
 * A non-secret reference from one bot to a local credential profile and an independent agent.
 */
public record BotCredentialBinding(
        CredentialBindingKey key, String profileId, UUID agentId) {
    public BotCredentialBinding {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(agentId, "agentId");
    }
}
