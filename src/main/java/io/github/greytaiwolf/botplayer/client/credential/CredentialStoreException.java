package io.github.greytaiwolf.botplayer.client.credential;

/**
 * Reports a local credential-store failure without including credential material.
 */
public final class CredentialStoreException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public CredentialStoreException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public CredentialStoreException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INITIALIZE("screen.botplayer.credentials.error.initialize"),
        SAVE("screen.botplayer.credentials.error.save"),
        CORRUPT_OR_UNSUPPORTED(
                "screen.botplayer.credentials.error.corrupt_or_unsupported"),
        PROFILE_MISSING("screen.botplayer.credentials.error.profile_missing"),
        INVALID_PROFILE_ID(
                "screen.botplayer.credentials.error.invalid_profile_id"),
        INVALID_KEY("screen.botplayer.credentials.error.invalid_key"),
        CAPACITY("screen.botplayer.credentials.error.capacity");

        private final String translationKey;

        Reason(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }
}
