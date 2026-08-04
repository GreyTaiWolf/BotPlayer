package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.UUID;

/**
 * 绑定一次动作终止事务的不可变清理请求。
 *
 * <p>{@code cleanupId} 在全部重试中保持不变。除首次创建外，请求只能消费上一步精确的
 * PENDING 回执后推进，防止跳过后端指定的安全重试 Tick 或伪造 attempt。
 */
public final class ActionCleanupRequest {
    public static final int MAX_ATTEMPTS = 256;

    private final UUID cleanupId;
    private final UUID actionId;
    private final UUID botId;
    private final long botGeneration;
    private final ActionCleanupReason reason;
    private final long requestedTick;
    private final long currentTick;
    private final int attempt;

    private ActionCleanupRequest(
            UUID cleanupId,
            UUID actionId,
            UUID botId,
            long botGeneration,
            ActionCleanupReason reason,
            long requestedTick,
            long currentTick,
            int attempt) {
        ActionEnvelope.requireNonZero(cleanupId, "cleanupId");
        ActionEnvelope.requireNonZero(actionId, "actionId");
        ActionEnvelope.requireNonZero(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        this.reason = Objects.requireNonNull(reason, "reason");
        if (requestedTick < 0L || currentTick < requestedTick) {
            throw new IllegalArgumentException(
                    "cleanup ticks are invalid");
        }
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "cleanup attempt is outside the bounded range");
        }
        if (reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED
                && attempt != 1) {
            throw new IllegalArgumentException(
                    "vanilla-death-consumed cleanup cannot be retried");
        }
        this.cleanupId = cleanupId;
        this.actionId = actionId;
        this.botId = botId;
        this.botGeneration = botGeneration;
        this.requestedTick = requestedTick;
        this.currentTick = currentTick;
        this.attempt = attempt;
    }

    public static ActionCleanupRequest first(
            UUID cleanupId,
            ActionEnvelope envelope,
            ActionCleanupReason reason,
            long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        return new ActionCleanupRequest(
                cleanupId,
                envelope.actionId(),
                envelope.botId(),
                envelope.botGeneration(),
                reason,
                currentTick,
                currentTick,
                1);
    }

    /**
     * 消费本 attempt 的精确 PENDING 回执并创建下一次请求。
     */
    public ActionCleanupRequest next(
            ActionCleanupReceipt receipt, long retryTick) {
        Objects.requireNonNull(receipt, "receipt");
        if (reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED) {
            throw new IllegalStateException(
                    "vanilla-death-consumed cleanup is terminal in one attempt");
        }
        if (!receipt.matches(this)
                || receipt.status() != ActionCleanupStatus.PENDING) {
            throw new IllegalArgumentException(
                    "cleanup retry requires its exact PENDING receipt");
        }
        if (retryTick < receipt.nextRetryTick()
                || retryTick <= currentTick) {
            throw new IllegalArgumentException(
                    "cleanup retry cannot precede its authorized Tick");
        }
        if (attempt >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "cleanup attempt budget is exhausted");
        }
        return new ActionCleanupRequest(
                cleanupId,
                actionId,
                botId,
                botGeneration,
                reason,
                requestedTick,
                retryTick,
                attempt + 1);
    }

    public boolean matches(ActionEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return actionId.equals(envelope.actionId())
                && botId.equals(envelope.botId())
                && botGeneration == envelope.botGeneration();
    }

    public UUID cleanupId() {
        return cleanupId;
    }

    public UUID actionId() {
        return actionId;
    }

    public UUID botId() {
        return botId;
    }

    public long botGeneration() {
        return botGeneration;
    }

    public ActionCleanupReason reason() {
        return reason;
    }

    public long requestedTick() {
        return requestedTick;
    }

    public long currentTick() {
        return currentTick;
    }

    public int attempt() {
        return attempt;
    }

    public boolean vanillaDeathConsumed() {
        return reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED;
    }
}
