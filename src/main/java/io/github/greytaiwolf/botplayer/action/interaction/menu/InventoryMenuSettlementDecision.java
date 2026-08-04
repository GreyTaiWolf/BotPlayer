package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.Optional;

/**
 * 多步 SWAP 事务逐 Tick 清理时的有界端点决定。
 *
 * <p>任何决定最多携带一次相邻点击。{@code CLICK_TO_INITIAL} 与
 * {@code CLICK_TO_FINAL} 表示固定收口方向，不保证该次点击直接到达端点；
 * {@code observedPrefix == -1} 表示当前布局不是获准继续的计划前缀。
 */
public final class InventoryMenuSettlementDecision {

    public enum Outcome {
        ALREADY_INITIAL,
        ALREADY_FINAL,
        CLICK_TO_INITIAL,
        CLICK_TO_FINAL,
        SAFE_PREFIX_COMMITTED,
        UNSAFE
    }

    private final Outcome outcome;
    private final Optional<InventoryMenuClickStep> click;
    private final int observedPrefix;
    private final InventoryMenuSnapshot observedSnapshot;
    private final Optional<InventoryMenuSettlementCursor>
            settlementCursor;

    private InventoryMenuSettlementDecision(
            Outcome outcome,
            Optional<InventoryMenuClickStep> click,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot,
            Optional<InventoryMenuSettlementCursor>
                    settlementCursor) {
        this.outcome = Objects.requireNonNull(
                outcome, "outcome");
        this.click = Objects.requireNonNull(click, "click");
        this.observedSnapshot = Objects.requireNonNull(
                observedSnapshot, "observedSnapshot");
        this.settlementCursor = Objects.requireNonNull(
                settlementCursor, "settlementCursor");
        boolean clickRequired =
                outcome == Outcome.CLICK_TO_INITIAL
                        || outcome == Outcome.CLICK_TO_FINAL;
        if (clickRequired != click.isPresent()) {
            throw new IllegalArgumentException(
                    "settlement click does not match its outcome");
        }
        boolean cursorRequired =
                outcome != Outcome.SAFE_PREFIX_COMMITTED
                        && outcome != Outcome.UNSAFE;
        if (cursorRequired != settlementCursor.isPresent()) {
            throw new IllegalArgumentException(
                    "settlement cursor does not match its outcome");
        }
        if (observedPrefix < -1) {
            throw new IllegalArgumentException(
                    "observedPrefix must be -1 or a plan prefix");
        }
        this.observedPrefix = observedPrefix;
    }

    static InventoryMenuSettlementDecision withoutClick(
            Outcome outcome,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot) {
        return new InventoryMenuSettlementDecision(
                outcome,
                Optional.empty(),
                observedPrefix,
                observedSnapshot,
                Optional.empty());
    }

    static InventoryMenuSettlementDecision withoutClick(
            Outcome outcome,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot,
            InventoryMenuSettlementCursor settlementCursor) {
        return new InventoryMenuSettlementDecision(
                outcome,
                Optional.empty(),
                observedPrefix,
                observedSnapshot,
                Optional.of(Objects.requireNonNull(
                        settlementCursor, "settlementCursor")));
    }

    static InventoryMenuSettlementDecision withClick(
            Outcome outcome,
            InventoryMenuClickStep click,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot,
            InventoryMenuSettlementCursor settlementCursor) {
        return new InventoryMenuSettlementDecision(
                outcome,
                Optional.of(Objects.requireNonNull(click, "click")),
                observedPrefix,
                observedSnapshot,
                Optional.of(Objects.requireNonNull(
                        settlementCursor, "settlementCursor")));
    }

    public Outcome outcome() {
        return outcome;
    }

    public Optional<InventoryMenuClickStep> click() {
        return click;
    }

    public int observedPrefix() {
        return observedPrefix;
    }

    /**
     * 生产层必须在真实点击前再次精确核对该快照及所有动态权限。
     */
    public InventoryMenuSnapshot observedSnapshot() {
        return observedSnapshot;
    }

    /**
     * 获准前缀决定会携带冻结端点游标；外部安全停止或
     * 不安全布局不携带游标。
     */
    public Optional<InventoryMenuSettlementCursor>
            settlementCursor() {
        return settlementCursor;
    }
}
