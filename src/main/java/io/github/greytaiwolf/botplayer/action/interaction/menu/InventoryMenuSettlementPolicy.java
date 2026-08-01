package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 把有界计划前缀逐 Tick 收敛到固定的最近安全端点。
 *
 * <p>到初态和终态距离相等时固定选初态。每次决策最多只提议一次相邻
 * 点击，由调用方在下一 Tick 用新的精确快照和 stateId 重新授权。若布局已被
 * 外部系统合法重排，但
 * container、cursor 和 41 槽结构化多重集仍安全，则停止旧计划且不再搬动物品。
 */
public final class InventoryMenuSettlementPolicy {
    private InventoryMenuSettlementPolicy() {
    }

    public static InventoryMenuSettlementDecision decide(
            InventoryMenuSwapPlan plan,
            InventoryMenuSnapshot actual,
            InventoryMenuPrefixAuthority authority) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(
                authority, "authority");

        OptionalInt matched =
                plan.matchingPrefixIgnoringState(actual);
        OptionalInt authorized =
                authority.authorizedPrefix(plan, actual);
        if (authorized.isPresent()) {
            return settleKnownPrefix(
                    InventoryMenuSettlementCursor.nearest(
                            plan, authorized.getAsInt()),
                    actual);
        }

        return decideUnauthorized(plan, actual, matched);
    }

    /**
     * 继续一次已冻结端点的收口。权威只能停在游标已确认前缀，
     * 或携带一个沿同一端点方向的 in-flight 相邻目标。
     */
    public static InventoryMenuSettlementDecision decide(
            InventoryMenuSettlementCursor cursor,
            InventoryMenuSnapshot actual,
            InventoryMenuPrefixAuthority authority) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(authority, "authority");
        InventoryMenuSwapPlan plan = cursor.plan();
        OptionalInt matched =
                plan.matchingPrefixIgnoringState(actual);
        if (!cursor.permits(authority)) {
            return unsafe(plan, actual, matched);
        }
        OptionalInt authorized =
                authority.authorizedPrefix(plan, actual);
        if (authorized.isPresent()) {
            InventoryMenuSettlementCursor observed;
            try {
                observed = cursor.observeAuthorizedPrefix(
                        authorized.getAsInt());
            } catch (IllegalArgumentException exception) {
                return unsafe(plan, actual, matched);
            }
            return settleKnownPrefix(observed, actual);
        }

        return decideUnauthorized(plan, actual, matched);
    }

    private static InventoryMenuSettlementDecision
            decideUnauthorized(
                    InventoryMenuSwapPlan plan,
                    InventoryMenuSnapshot actual,
                    OptionalInt matched) {

        /*
         * A temporary touched-slot layout from this plan is not an
         * "external safe rearrangement". Selected-slot or unrelated-slot
         * drift must not wash an unowned intermediate prefix into SAFE.
         * Endpoints are physically safe and may still be committed without
         * another click after the conservation gates below pass.
         */
        OptionalInt touched =
                plan.matchingTouchedPrefix(actual);
        int possiblePrefix = matched.isPresent()
                ? matched.getAsInt()
                : touched.orElse(-1);
        if (possiblePrefix > 0
                && possiblePrefix
                        < plan.orderedSteps().size()) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome.UNSAFE,
                    possiblePrefix,
                    actual);
        }

        if (actual.containerId()
                        == plan.initialSnapshot().containerId()
                && actual.cursor().isEmpty()
                && actual.inventoryMultisetEquals(
                        plan.initialSnapshot())) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome
                            .SAFE_PREFIX_COMMITTED,
                    matched.orElse(-1),
                    actual);
        }
        return InventoryMenuSettlementDecision.withoutClick(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                matched.orElse(-1),
                actual);
    }

    private static InventoryMenuSettlementDecision unsafe(
            InventoryMenuSwapPlan plan,
            InventoryMenuSnapshot actual,
            OptionalInt matched) {
        OptionalInt touched =
                plan.matchingTouchedPrefix(actual);
        int possiblePrefix = matched.isPresent()
                ? matched.getAsInt()
                : touched.orElse(-1);
        return InventoryMenuSettlementDecision.withoutClick(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                possiblePrefix,
                actual);
    }

    private static InventoryMenuSettlementDecision
            settleKnownPrefix(
                    InventoryMenuSettlementCursor cursor,
                    InventoryMenuSnapshot actual) {
        InventoryMenuSwapPlan plan = cursor.plan();
        int prefix = cursor.confirmedPrefix();
        int stepCount = plan.orderedSteps().size();
        if (prefix == 0) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome
                            .ALREADY_INITIAL,
                    prefix,
                    actual,
                    cursor);
        }
        if (prefix == stepCount) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome
                            .ALREADY_FINAL,
                    prefix,
                    actual,
                    cursor);
        }

        if (cursor.endpoint()
                == InventoryMenuSettlementCursor.Endpoint.INITIAL) {
            return InventoryMenuSettlementDecision.withClick(
                    InventoryMenuSettlementDecision.Outcome
                            .CLICK_TO_INITIAL,
                    plan.orderedSteps()
                            .get(prefix - 1)
                            .reversed(),
                    prefix,
                    actual,
                    cursor);
        }
        return InventoryMenuSettlementDecision.withClick(
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_FINAL,
                plan.orderedSteps().get(prefix),
                prefix,
                actual,
                cursor);
    }
}
