package io.github.greytaiwolf.botplayer.client.ai;

/**
 * The sole persisted local control for the P6-R1 Provider path.
 *
 * <p>This intentionally contains no credential, endpoint, model, provider, profile, prompt, or
 * tool fields. A server payload cannot write or synchronize this value.
 */
public record ReviewOnlyClientSettings(boolean enabled) {
    public static final ReviewOnlyClientSettings DEFAULT = new ReviewOnlyClientSettings(false);
}
