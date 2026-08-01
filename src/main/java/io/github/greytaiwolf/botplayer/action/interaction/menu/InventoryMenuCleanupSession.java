package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.ActionCleanupReceipt;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionCleanupRequest;
import io.github.greytaiwolf.botplayer.action.ActionCleanupStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次菜单动作终止事务的纯 Java 票据与收口游标。
 *
 * <p>该对象不执行点击。它只保证 cleanupId/attempt 不被换票，同一请求重放
 * 返回同一回执，物理进度单调递增，并且首次选择的收口端点不会在后续 Tick
 * 改变。
 */
public final class InventoryMenuCleanupSession {

    public enum BeginStatus {
        NEW_ATTEMPT,
        REPLAY,
        REJECTED
    }

    public record BeginResult(
            BeginStatus status,
            Optional<ActionCleanupReceipt> replayReceipt) {
        public BeginResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(
                    replayReceipt, "replayReceipt");
            if ((status == BeginStatus.REPLAY)
                    != replayReceipt.isPresent()) {
                throw new IllegalArgumentException(
                        "only a replay may carry a cached receipt");
            }
        }
    }

    private UUID cleanupId;
    private ActionCleanupReason reason;
    private long requestedTick = -1L;
    private int lastAttempt;
    private ActionCleanupReceipt lastReceipt;
    private ActionCleanupRequest openAttempt;
    private long progressRevision;
    private long lastClickDispatchTick = -1L;
    private boolean clickDispatchOpen;
    private InventoryMenuSettlementCursor settlementCursor;

    public BeginResult begin(ActionCleanupRequest request) {
        Objects.requireNonNull(request, "request");
        if (lastReceipt != null
                && lastReceipt.matches(request)) {
            return new BeginResult(
                    BeginStatus.REPLAY,
                    Optional.of(lastReceipt));
        }
        if (lastReceipt != null
                && lastReceipt.status()
                        != ActionCleanupStatus.PENDING) {
            return rejected();
        }
        if (openAttempt != null) {
            return rejected();
        }
        if (cleanupId == null) {
            if (request.attempt() != 1) {
                return rejected();
            }
            cleanupId = request.cleanupId();
            reason = request.reason();
            requestedTick = request.requestedTick();
            openAttempt = request;
            return new BeginResult(
                    BeginStatus.NEW_ATTEMPT,
                    Optional.empty());
        }
        if (!cleanupId.equals(request.cleanupId())
                || reason != request.reason()
                || requestedTick != request.requestedTick()
                || request.attempt() != lastAttempt + 1
                || lastReceipt == null
                || lastReceipt.status()
                        != ActionCleanupStatus.PENDING
                || request.currentTick()
                        < lastReceipt.nextRetryTick()) {
            return rejected();
        }
        openAttempt = request;
        return new BeginResult(
                BeginStatus.NEW_ATTEMPT,
                Optional.empty());
    }

    public ActionCleanupReceipt remember(
            ActionCleanupRequest request,
            ActionCleanupReceipt receipt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receipt, "receipt");
        if (cleanupId == null
                || !cleanupId.equals(request.cleanupId())
                || openAttempt == null
                || !sameRequest(openAttempt, request)
                || !receipt.matches(request)
                || receipt.progressRevision()
                        != progressRevision) {
            throw new IllegalArgumentException(
                    "cleanup receipt does not belong to this session");
        }
        lastAttempt = request.attempt();
        lastReceipt = receipt;
        openAttempt = null;
        return receipt;
    }

    public long progressRevision() {
        return progressRevision;
    }

    /**
     * 同一菜单事务在一个服务器 Tick 内至多派发一次原生点击。
     */
    public boolean mayDispatchClickAt(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must not be negative");
        }
        return !clickDispatchOpen
                && currentTick > lastClickDispatchTick;
    }

    /**
     * 在进入可能同步重入的原生点击前预占本 Tick；即使点击在变更前抛错，
     * 本 Tick 也不能再次派发点击。
     */
    public void beginClickDispatch(long currentTick) {
        if (!mayDispatchClickAt(currentTick)) {
            throw new IllegalArgumentException(
                    "menu click dispatch requires a later Tick");
        }
        lastClickDispatchTick = currentTick;
        clickDispatchOpen = true;
    }

    public void endClickDispatch(long currentTick) {
        if (!clickDispatchOpen
                || currentTick != lastClickDispatchTick) {
            throw new IllegalArgumentException(
                    "menu click dispatch is not open for this Tick");
        }
        clickDispatchOpen = false;
    }

    public boolean clickDispatchOpen() {
        return clickDispatchOpen;
    }

    public Optional<InventoryMenuSettlementCursor>
            settlementCursor() {
        return Optional.ofNullable(settlementCursor);
    }

    /**
     * 冻结首次决定携带的端点；重复观察必须仍是同一计划、方向和前缀。
     */
    public InventoryMenuSettlementCursor observe(
            InventoryMenuSettlementDecision decision) {
        InventoryMenuSettlementCursor observed =
                Objects.requireNonNull(decision, "decision")
                        .settlementCursor()
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "decision has no settlement cursor"));
        if (settlementCursor == null) {
            settlementCursor = observed;
            return observed;
        }
        requireSameCursor(settlementCursor, observed);
        return settlementCursor;
    }

    /**
     * 确认决定中唯一一次相邻点击已经真实到达目标前缀。
     */
    public InventoryMenuSettlementCursor confirmProposedClick(
            InventoryMenuSettlementDecision decision) {
        InventoryMenuSettlementCursor source = observe(decision);
        if (decision.click().isEmpty()) {
            throw new IllegalArgumentException(
                    "confirmed cleanup progress requires one proposed click");
        }
        settlementCursor = source.advanceAfterProposedClick();
        progressRevision++;
        return settlementCursor;
    }

    /**
     * 记录一次已精确确认的前向相邻目标；正常返回与异常后解析只能二选一记账。
     */
    public void recordConfirmedForwardProgress() {
        progressRevision++;
    }

    public void clearSettlementCursor() {
        settlementCursor = null;
    }

    private static BeginResult rejected() {
        return new BeginResult(
                BeginStatus.REJECTED,
                Optional.empty());
    }

    private static boolean sameRequest(
            ActionCleanupRequest first,
            ActionCleanupRequest second) {
        return first.cleanupId().equals(second.cleanupId())
                && first.actionId().equals(second.actionId())
                && first.botId().equals(second.botId())
                && first.botGeneration()
                        == second.botGeneration()
                && first.reason() == second.reason()
                && first.requestedTick()
                        == second.requestedTick()
                && first.currentTick() == second.currentTick()
                && first.attempt() == second.attempt();
    }

    private static void requireSameCursor(
            InventoryMenuSettlementCursor expected,
            InventoryMenuSettlementCursor observed) {
        if (expected.plan() != observed.plan()
                || expected.endpoint() != observed.endpoint()
                || expected.confirmedPrefix()
                        != observed.confirmedPrefix()) {
            throw new IllegalArgumentException(
                    "cleanup cannot replace its frozen settlement cursor");
        }
    }
}
