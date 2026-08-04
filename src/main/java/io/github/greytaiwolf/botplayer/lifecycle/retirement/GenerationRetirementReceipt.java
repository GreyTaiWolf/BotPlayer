package io.github.greytaiwolf.botplayer.lifecycle.retirement;

import java.util.Objects;

/** 一次精确 generation 退役 attempt 的不可变回执。 */
public record GenerationRetirementReceipt(
        GenerationRetirementKey key,
        long startedTick,
        long deadlineTick,
        long attemptedTick,
        int attempt,
        GenerationRetirementStatus status,
        GenerationRetirementFailure failure,
        long progressRevision,
        long nextRetryTick) {
    public GenerationRetirementReceipt {
        key = Objects.requireNonNull(key, "key");
        status = Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if (startedTick < 0L
                || deadlineTick < startedTick
                || attemptedTick < startedTick
                || attempt < 1
                || attempt > GenerationRetirementTicket.MAX_ATTEMPTS
                || progressRevision < 0L) {
            throw new IllegalArgumentException(
                    "retirement receipt counters are invalid");
        }
        if (status == GenerationRetirementStatus.PENDING) {
            if (attempt
                    >= GenerationRetirementTicket.MAX_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "final retirement attempt cannot remain PENDING");
            }
            if (nextRetryTick <= attemptedTick
                    || exceedsExpiryBoundary(
                            nextRetryTick, deadlineTick)) {
                throw new IllegalArgumentException(
                        "PENDING retirement requires a bounded future retry Tick");
            }
        } else if (nextRetryTick != -1L) {
            throw new IllegalArgumentException(
                    "terminal retirement cannot expose a retry Tick");
        }
        if ((status == GenerationRetirementStatus.UNSAFE)
                != (failure != GenerationRetirementFailure.NONE)) {
            throw new IllegalArgumentException(
                    "only UNSAFE retirement can carry a failure kind");
        }
    }

    public boolean matches(GenerationRetirementTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        return key.equals(ticket.key())
                && startedTick == ticket.startedTick()
                && deadlineTick == ticket.deadlineTick()
                && attemptedTick == ticket.currentTick()
                && attempt == ticket.attempt();
    }

    private static boolean exceedsExpiryBoundary(
            long retryTick, long deadlineTick) {
        return retryTick > deadlineTick
                && retryTick - deadlineTick > 1L;
    }
}
