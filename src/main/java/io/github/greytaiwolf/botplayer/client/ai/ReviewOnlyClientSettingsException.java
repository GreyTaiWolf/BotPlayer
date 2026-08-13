package io.github.greytaiwolf.botplayer.client.ai;

/** Failure that leaves the review-only client Provider disabled. */
public final class ReviewOnlyClientSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public ReviewOnlyClientSettingsException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public ReviewOnlyClientSettingsException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INITIALIZE,
        CORRUPT_OR_UNSUPPORTED,
        SAVE
    }
}
