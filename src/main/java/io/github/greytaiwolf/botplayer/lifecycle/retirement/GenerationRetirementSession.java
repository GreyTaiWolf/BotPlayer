package io.github.greytaiwolf.botplayer.lifecycle.retirement;

import java.util.Objects;
import java.util.Optional;

/**
 * generation 退役事务的不可变纯 Java 状态机。
 *
 * <p>该模型不执行任何 Minecraft 生命周期动作。调用者提交一次物理清理观察，
 * 模型返回新的 Session 与精确回执；原 Session 永远不被修改。
 */
public final class GenerationRetirementSession {

    public enum UpdateKind {
        ACCEPTED,
        REPLAY,
        STICKY,
        FAILED_CLOSED
    }

    public record Update(
            GenerationRetirementSession session,
            GenerationRetirementReceipt receipt,
            UpdateKind kind) {
        public Update {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private final GenerationRetirementKey key;
    private final long startedTick;
    private final long deadlineTick;
    private final GenerationRetirementStatus status;
    private final int attempt;
    private final long progressRevision;
    private final long nextRetryTick;
    private final GenerationRetirementTicket lastTicket;
    private final GenerationRetirementReceipt lastReceipt;

    private GenerationRetirementSession(
            GenerationRetirementKey key,
            long startedTick,
            long deadlineTick,
            GenerationRetirementStatus status,
            int attempt,
            long progressRevision,
            long nextRetryTick,
            GenerationRetirementTicket lastTicket,
            GenerationRetirementReceipt lastReceipt) {
        this.key = Objects.requireNonNull(key, "key");
        this.startedTick = startedTick;
        this.deadlineTick = deadlineTick;
        this.status = Objects.requireNonNull(status, "status");
        this.attempt = attempt;
        this.progressRevision = progressRevision;
        this.nextRetryTick = nextRetryTick;
        this.lastTicket = lastTicket;
        this.lastReceipt = lastReceipt;
    }

    public static GenerationRetirementSession open(
            GenerationRetirementKey key,
            long startedTick,
            long deadlineTick) {
        Objects.requireNonNull(key, "key");
        if (startedTick < 0L || deadlineTick < startedTick) {
            throw new IllegalArgumentException(
                    "retirement session ticks are invalid");
        }
        return new GenerationRetirementSession(
                key,
                startedTick,
                deadlineTick,
                GenerationRetirementStatus.PENDING,
                0,
                0L,
                startedTick,
                null,
                null);
    }

    /**
     * 接收一次有界观察。PENDING 可继续、完成或失败；终态只会返回原回执。
     */
    public Update observe(
            GenerationRetirementTicket ticket,
            GenerationRetirementStatus observedStatus,
            long observedProgressRevision,
            long observedNextRetryTick) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(observedStatus, "observedStatus");

        if (lastTicket != null && lastTicket.equals(ticket)) {
            return new Update(this, lastReceipt, UpdateKind.REPLAY);
        }
        if (status != GenerationRetirementStatus.PENDING) {
            return new Update(this, lastReceipt, UpdateKind.STICKY);
        }
        if (!key.equals(ticket.key())
                || startedTick != ticket.startedTick()
                || deadlineTick != ticket.deadlineTick()) {
            return failClosedForConflict(ticket.currentTick());
        }
        requireNextTicket(ticket);
        long acceptedRevision = Math.max(
                progressRevision, observedProgressRevision);
        if (ticket.currentTick() > deadlineTick) {
            return accepted(
                    ticket,
                    GenerationRetirementStatus.UNSAFE,
                    GenerationRetirementFailure.DEADLINE_EXCEEDED,
                    acceptedRevision,
                    -1L);
        }
        if (observedStatus == GenerationRetirementStatus.PENDING
                && ticket.currentTick() == Long.MAX_VALUE) {
            return accepted(
                    ticket,
                    GenerationRetirementStatus.UNSAFE,
                    GenerationRetirementFailure.DEADLINE_EXCEEDED,
                    acceptedRevision,
                    -1L);
        }
        if (observedStatus == GenerationRetirementStatus.PENDING
                && ticket.attempt()
                        >= GenerationRetirementTicket.MAX_ATTEMPTS) {
            return accepted(
                    ticket,
                    GenerationRetirementStatus.UNSAFE,
                    GenerationRetirementFailure
                            .ATTEMPT_BUDGET_EXHAUSTED,
                    acceptedRevision,
                    -1L);
        }
        if (observedProgressRevision < progressRevision) {
            throw new IllegalArgumentException(
                    "retirement progress revision cannot move backwards");
        }
        if (observedStatus == GenerationRetirementStatus.PENDING) {
            if (observedNextRetryTick <= ticket.currentTick()
                    || observedNextRetryTick < nextRetryTick
                    || exceedsExpiryBoundary(
                            observedNextRetryTick,
                            deadlineTick)) {
                throw new IllegalArgumentException(
                        "retirement retry Tick cannot move backwards or escape its deadline");
            }
        } else if (observedNextRetryTick != -1L) {
            throw new IllegalArgumentException(
                    "terminal retirement cannot expose a retry Tick");
        }
        return accepted(
                ticket,
                observedStatus,
                observedStatus == GenerationRetirementStatus.UNSAFE
                        ? GenerationRetirementFailure.OBSERVED_UNSAFE
                        : GenerationRetirementFailure.NONE,
                acceptedRevision,
                observedNextRetryTick);
    }

    public GenerationRetirementKey key() {
        return key;
    }

    public long startedTick() {
        return startedTick;
    }

    public long deadlineTick() {
        return deadlineTick;
    }

    public GenerationRetirementStatus status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public long progressRevision() {
        return progressRevision;
    }

    public long nextRetryTick() {
        return nextRetryTick;
    }

    public Optional<GenerationRetirementReceipt> lastReceipt() {
        return Optional.ofNullable(lastReceipt);
    }

    private void requireNextTicket(
            GenerationRetirementTicket ticket) {
        if (ticket.attempt() != attempt + 1) {
            throw new IllegalArgumentException(
                    "retirement attempt must advance exactly once");
        }
        if (lastReceipt == null) {
            if (ticket.currentTick() < startedTick) {
                throw new IllegalArgumentException(
                        "first retirement attempt precedes its start");
            }
            return;
        }
        if (ticket.currentTick() < nextRetryTick
                || ticket.currentTick()
                        <= lastReceipt.attemptedTick()) {
            throw new IllegalArgumentException(
                    "retirement attempt precedes its authorized retry Tick");
        }
    }

    private Update accepted(
            GenerationRetirementTicket ticket,
            GenerationRetirementStatus acceptedStatus,
            GenerationRetirementFailure failure,
            long acceptedRevision,
            long acceptedNextRetryTick) {
        GenerationRetirementReceipt receipt =
                new GenerationRetirementReceipt(
                        key,
                        startedTick,
                        deadlineTick,
                        ticket.currentTick(),
                        ticket.attempt(),
                        acceptedStatus,
                        failure,
                        acceptedRevision,
                        acceptedNextRetryTick);
        GenerationRetirementSession next =
                new GenerationRetirementSession(
                        key,
                        startedTick,
                        deadlineTick,
                        acceptedStatus,
                        ticket.attempt(),
                        acceptedRevision,
                        acceptedNextRetryTick,
                        ticket,
                        receipt);
        return new Update(next, receipt, UpdateKind.ACCEPTED);
    }

    private Update failClosedForConflict(long attemptedTick) {
        int failedAttempt = attempt + 1;
        GenerationRetirementReceipt receipt =
                new GenerationRetirementReceipt(
                        key,
                        startedTick,
                        deadlineTick,
                        Math.max(
                                startedTick,
                                Math.max(
                                        attemptedTick,
                                        lastReceipt == null
                                                ? startedTick
                                                : lastReceipt.attemptedTick())),
                        failedAttempt,
                        GenerationRetirementStatus.UNSAFE,
                        GenerationRetirementFailure.AUTHORITY_CONFLICT,
                        progressRevision,
                        -1L);
        GenerationRetirementSession failed =
                new GenerationRetirementSession(
                        key,
                        startedTick,
                        deadlineTick,
                        GenerationRetirementStatus.UNSAFE,
                        failedAttempt,
                        progressRevision,
                        -1L,
                        null,
                        receipt);
        return new Update(
                failed, receipt, UpdateKind.FAILED_CLOSED);
    }

    private static boolean exceedsExpiryBoundary(
            long retryTick, long deadlineTick) {
        return retryTick > deadlineTick
                && retryTick - deadlineTick > 1L;
    }
}
