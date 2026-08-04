package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次精确 cleanup attempt 的不可变回执。
 */
public record ActionCleanupReceipt(
        UUID cleanupId,
        UUID actionId,
        UUID botId,
        long botGeneration,
        ActionCleanupReason reason,
        long requestedTick,
        long attemptedTick,
        int attempt,
        ActionCleanupStatus status,
        long progressRevision,
        long nextRetryTick,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public ActionCleanupReceipt {
        ActionEnvelope.requireNonZero(cleanupId, "cleanupId");
        ActionEnvelope.requireNonZero(actionId, "actionId");
        ActionEnvelope.requireNonZero(botId, "botId");
        if (botGeneration <= 0L
                || attempt < 1
                || attempt
                        > ActionCleanupRequest.MAX_ATTEMPTS
                || progressRevision < 0L
                || requestedTick < 0L
                || attemptedTick < requestedTick) {
            throw new IllegalArgumentException(
                    "cleanup receipt counters are invalid");
        }
        reason = Objects.requireNonNull(reason, "reason");
        status = Objects.requireNonNull(status, "status");
        safeSummary = requireSummary(safeSummary);
        if (reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED
                && (attempt != 1
                        || status != ActionCleanupStatus.COMPLETE)) {
            throw new IllegalArgumentException(
                    "vanilla-death-consumed cleanup must complete on its first attempt");
        }
        if (status == ActionCleanupStatus.PENDING) {
            if (attempt >= ActionCleanupRequest.MAX_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "final cleanup attempt cannot remain PENDING");
            }
            if (nextRetryTick <= attemptedTick) {
                throw new IllegalArgumentException(
                        "PENDING cleanup requires a future retry Tick");
            }
        } else if (nextRetryTick != -1L) {
            throw new IllegalArgumentException(
                    "terminal cleanup cannot expose a retry Tick");
        }
    }

    public static ActionCleanupReceipt complete(
            ActionCleanupRequest request,
            long progressRevision,
            String summary) {
        return terminal(
                request,
                ActionCleanupStatus.COMPLETE,
                progressRevision,
                summary);
    }

    public static ActionCleanupReceipt pending(
            ActionCleanupRequest request,
            long progressRevision,
            long nextRetryTick,
            String summary) {
        Objects.requireNonNull(request, "request");
        if (nextRetryTick <= request.currentTick()) {
            throw new IllegalArgumentException(
                    "PENDING retry must use a future Tick");
        }
        return from(
                request,
                ActionCleanupStatus.PENDING,
                progressRevision,
                nextRetryTick,
                summary);
    }

    public static ActionCleanupReceipt unsafe(
            ActionCleanupRequest request,
            long progressRevision,
            String summary) {
        return terminal(
                request,
                ActionCleanupStatus.UNSAFE,
                progressRevision,
                summary);
    }

    public boolean matches(ActionCleanupRequest request) {
        Objects.requireNonNull(request, "request");
        return cleanupId.equals(request.cleanupId())
                && actionId.equals(request.actionId())
                && botId.equals(request.botId())
                && botGeneration == request.botGeneration()
                && reason == request.reason()
                && requestedTick == request.requestedTick()
                && attemptedTick == request.currentTick()
                && attempt == request.attempt();
    }

    private static ActionCleanupReceipt terminal(
            ActionCleanupRequest request,
            ActionCleanupStatus status,
            long progressRevision,
            String summary) {
        if (status == ActionCleanupStatus.PENDING) {
            throw new IllegalArgumentException(
                    "terminal cleanup status cannot be PENDING");
        }
        return from(
                request,
                status,
                progressRevision,
                -1L,
                summary);
    }

    private static ActionCleanupReceipt from(
            ActionCleanupRequest request,
            ActionCleanupStatus status,
            long progressRevision,
            long nextRetryTick,
            String summary) {
        Objects.requireNonNull(request, "request");
        return new ActionCleanupReceipt(
                request.cleanupId(),
                request.actionId(),
                request.botId(),
                request.botGeneration(),
                request.reason(),
                request.requestedTick(),
                request.currentTick(),
                request.attempt(),
                status,
                progressRevision,
                nextRetryTick,
                summary);
    }

    private static String requireSummary(String summary) {
        Objects.requireNonNull(summary, "safeSummary");
        if (summary.length() > MAX_SUMMARY_LENGTH
                || !summary.equals(summary.strip())
                || summary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed bounded string");
        }
        return summary;
    }
}
