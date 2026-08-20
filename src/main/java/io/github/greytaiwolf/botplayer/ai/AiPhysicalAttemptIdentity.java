package io.github.greytaiwolf.botplayer.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.Objects;
import java.util.UUID;

/**
 * Redacted, immutable correlation for one distributed physical Provider attempt.
 *
 * <p>The identity deliberately combines the server instance, the exact dispatch owner, the
 * complete safe dispatch receipt, a server-chosen attempt id, the dispatch nonce, the client-side
 * not-after epoch and the earlier physical-start deadline. It carries no prompt, response,
 * credential, endpoint, token amount, or factual HTTP outcome. A future transport must preserve
 * every field exactly; a partial request id or a reconstructed receipt is never enough to settle
 * or start an attempt.
 */
public record AiPhysicalAttemptIdentity(
        UUID serverInstanceId,
        UUID ownerId,
        AiRequestDispatchReceipt dispatchReceipt,
        UUID attemptId,
        UUID nonce,
        long clientNotAfterEpochMillis,
        long physicalStartNotAfterEpochMillis) {
    public AiPhysicalAttemptIdentity {
        requireNonZero(serverInstanceId, "serverInstanceId");
        requireNonZero(ownerId, "ownerId");
        dispatchReceipt = Objects.requireNonNull(dispatchReceipt, "dispatchReceipt");
        requireNonZero(attemptId, "attemptId");
        requireNonZero(nonce, "nonce");
        if (clientNotAfterEpochMillis < 0L) {
            throw new IllegalArgumentException(
                    "clientNotAfterEpochMillis must not be negative");
        }
        if (physicalStartNotAfterEpochMillis < 0L
                || physicalStartNotAfterEpochMillis > clientNotAfterEpochMillis) {
            throw new IllegalArgumentException(
                    "physicalStartNotAfterEpochMillis must be within client not-after");
        }
    }

    /** Creates the only canonical attempt identity for a server-owned client dispatch. */
    public static AiPhysicalAttemptIdentity fromDispatch(
            AiClientRequestDispatch dispatch,
            UUID attemptId,
            long physicalStartNotAfterEpochMillis) {
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        return new AiPhysicalAttemptIdentity(
                checked.serverInstanceId(),
                checked.ownerId(),
                AiRequestDispatchReceipt.fromDispatch(checked),
                attemptId,
                checked.nonce(),
                checked.expiresAtEpochMillis(),
                physicalStartNotAfterEpochMillis);
    }

    /**
     * Returns true only for the full client-visible dispatch correlation that created this value.
     *
     * <p>This is a correlation check, not a client authorization decision. The future lifecycle
     * bridge must still establish that the dispatch belongs to its active server-side session.
     */
    public boolean matches(AiClientRequestDispatch dispatch) {
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        return serverInstanceId.equals(checked.serverInstanceId())
                && ownerId.equals(checked.ownerId())
                && dispatchReceipt.equals(AiRequestDispatchReceipt.fromDispatch(checked))
                && nonce.equals(checked.nonce())
                && clientNotAfterEpochMillis == checked.expiresAtEpochMillis();
    }

    /** Correlates every source-dispatch field while deliberately ignoring only the attempt id. */
    public boolean sameDispatch(AiPhysicalAttemptIdentity other) {
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(other, "other");
        return serverInstanceId.equals(checked.serverInstanceId)
                && ownerId.equals(checked.ownerId)
                && dispatchReceipt.equals(checked.dispatchReceipt)
                && nonce.equals(checked.nonce)
                && clientNotAfterEpochMillis == checked.clientNotAfterEpochMillis
                && physicalStartNotAfterEpochMillis
                == checked.physicalStartNotAfterEpochMillis;
    }

    /** Deliberately omits nonce and all stable owner/request correlation from ordinary logs. */
    @Override
    public String toString() {
        return "AiPhysicalAttemptIdentity[bound=true, clientNotAfterEpochMillis="
                + clientNotAfterEpochMillis + ", physicalStartNotAfterEpochMillis="
                + physicalStartNotAfterEpochMillis + "]";
    }

    private static void requireNonZero(UUID value, String name) {
        UUID checked = Objects.requireNonNull(value, name);
        if (checked.getMostSignificantBits() == 0L
                && checked.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
