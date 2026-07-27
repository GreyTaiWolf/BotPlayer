package io.github.greytaiwolf.botplayer.client.credential;

import java.util.Arrays;
import java.util.Objects;

/**
 * A secret-bearing profile that must remain on the physical client.
 *
 * <p>This class deliberately avoids record-generated representations because those would include
 * the secret.
 */
public final class CredentialProfile {
    public static final String DEEPSEEK_PROVIDER = "deepseek";

    private final String profileId;
    private final String provider;
    private final char[] secret;

    CredentialProfile(String profileId, String provider, char[] secret) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.secret = Objects.requireNonNull(secret, "secret").clone();
    }

    public String profileId() {
        return profileId;
    }

    public String provider() {
        return provider;
    }

    /**
     * Returns a caller-owned copy. Callers should clear it as soon as practical.
     */
    public char[] copySecret() {
        return secret.clone();
    }

    void clearSecret() {
        Arrays.fill(secret, '\0');
    }

    @Override
    public String toString() {
        return "CredentialProfile[profileId=" + profileId
                + ", provider=" + provider
                + ", secret=[REDACTED]]";
    }
}
