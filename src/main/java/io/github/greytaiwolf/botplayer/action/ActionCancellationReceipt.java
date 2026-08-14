package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative result of cancelling one immutable Action identity.
 *
 * <p>Generation quarantine is a remediation step, not evidence that the
 * requested Action did not start. Callers may treat a receipt as a safe
 * retraction only when its observed identity exactly matches the requested
 * identity and its disposition proves that the Action was removed or fenced
 * before backend start.
 */
public record ActionCancellationReceipt(
        Identity requested,
        Optional<Identity> observed,
        Disposition disposition,
        Optional<ActionMailbox.CancellationStatus> mailboxStatus) {

    public ActionCancellationReceipt {
        requested = Objects.requireNonNull(requested, "requested");
        observed = Objects.requireNonNull(observed, "observed");
        disposition = Objects.requireNonNull(disposition, "disposition");
        mailboxStatus = Objects.requireNonNull(mailboxStatus,
                "mailboxStatus");
        if (disposition.safelyRetracted()
                && !observed.filter(requested::equals).isPresent()) {
            throw new IllegalArgumentException(
                    "safe cancellation receipt requires the exact action identity");
        }
    }

    public static ActionCancellationReceipt exactQueuedRetracted(
            UUID botId, long botGeneration, UUID actionId) {
        Identity identity = new Identity(botId, botGeneration, actionId);
        return new ActionCancellationReceipt(identity, Optional.of(identity),
                Disposition.EXACT_QUEUED_RETRACTED, Optional.empty());
    }

    public static ActionCancellationReceipt fencedBeforeStart(
            UUID botId, long botGeneration, UUID actionId) {
        Identity identity = new Identity(botId, botGeneration, actionId);
        return new ActionCancellationReceipt(identity, Optional.of(identity),
                Disposition.FENCED_BEFORE_START, Optional.empty());
    }

    public static ActionCancellationReceipt unsafe(
            UUID botId, long botGeneration, UUID actionId,
            Disposition disposition) {
        if (Objects.requireNonNull(disposition, "disposition")
                .safelyRetracted()) {
            throw new IllegalArgumentException(
                    "unsafe cancellation receipt requires an unsafe disposition");
        }
        Identity identity = new Identity(botId, botGeneration, actionId);
        return new ActionCancellationReceipt(identity, Optional.of(identity),
                disposition, Optional.empty());
    }

    public static ActionCancellationReceipt unsafe(
            Identity requested, Optional<Identity> observed,
            Disposition disposition) {
        return new ActionCancellationReceipt(requested, observed, disposition,
                Optional.empty());
    }

    public boolean safelyRetracted() {
        return disposition.safelyRetracted()
                && observed.filter(requested::equals).isPresent();
    }

    public boolean matches(UUID botId, long botGeneration, UUID actionId) {
        return requested.equals(new Identity(botId, botGeneration, actionId));
    }

    /** Immutable triple used to reject canonical aliases and cross-generation receipts. */
    public record Identity(UUID botId, long botGeneration, UUID actionId) {
        public Identity {
            ActionEnvelope.requireNonZero(botId, "botId");
            if (botGeneration <= 0L) {
                throw new IllegalArgumentException(
                        "botGeneration must be positive");
            }
            ActionEnvelope.requireNonZero(actionId, "actionId");
        }
    }

    public enum Disposition {
        EXACT_QUEUED_RETRACTED(true),
        FENCED_BEFORE_START(true),
        STARTED(false),
        TERMINAL(false),
        ALIAS(false),
        UNKNOWN(false);

        private final boolean safelyRetracted;

        Disposition(boolean safelyRetracted) {
            this.safelyRetracted = safelyRetracted;
        }

        public boolean safelyRetracted() {
            return safelyRetracted;
        }
    }
}
