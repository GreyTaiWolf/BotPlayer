package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.Optional;

/**
 * 多步 SWAP 事务同步清理时的有界端点决定。
 *
 * <p>任何决定最多携带一次点击。{@code observedPrefix == -1} 表示当前布局不是
 * 获准继续的计划前缀。
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

    private InventoryMenuSettlementDecision(
            Outcome outcome,
            Optional<InventoryMenuClickStep> click,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot) {
        this.outcome = Objects.requireNonNull(
                outcome, "outcome");
        this.click = Objects.requireNonNull(click, "click");
        this.observedSnapshot = Objects.requireNonNull(
                observedSnapshot, "observedSnapshot");
        boolean clickRequired =
                outcome == Outcome.CLICK_TO_INITIAL
                        || outcome == Outcome.CLICK_TO_FINAL;
        if (clickRequired != click.isPresent()) {
            throw new IllegalArgumentException(
                    "settlement click does not match its outcome");
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
                observedSnapshot);
    }

    static InventoryMenuSettlementDecision withClick(
            Outcome outcome,
            InventoryMenuClickStep click,
            int observedPrefix,
            InventoryMenuSnapshot observedSnapshot) {
        return new InventoryMenuSettlementDecision(
                outcome,
                Optional.of(Objects.requireNonNull(click, "click")),
                observedPrefix,
                observedSnapshot);
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
}
