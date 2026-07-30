package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 把至多三步的计划前缀同步收敛到最近安全端点。
 *
 * <p>两步计划的中点优先回初态；三步计划的第一步回初态、第二步补到终态。
 * 因此任意获准前缀都只需零或一次点击。若布局已被外部系统合法重排，但
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
                    plan,
                    authorized.getAsInt(),
                    actual);
        }

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

    private static InventoryMenuSettlementDecision
            settleKnownPrefix(
                    InventoryMenuSwapPlan plan,
                    int prefix,
                    InventoryMenuSnapshot actual) {
        int stepCount = plan.orderedSteps().size();
        if (prefix == 0) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome
                            .ALREADY_INITIAL,
                    prefix,
                    actual);
        }
        if (prefix == stepCount) {
            return InventoryMenuSettlementDecision.withoutClick(
                    InventoryMenuSettlementDecision.Outcome
                            .ALREADY_FINAL,
                    prefix,
                    actual);
        }

        int distanceToInitial = prefix;
        int distanceToFinal = stepCount - prefix;
        if (distanceToInitial <= distanceToFinal
                && distanceToInitial == 1) {
            return InventoryMenuSettlementDecision.withClick(
                    InventoryMenuSettlementDecision.Outcome
                            .CLICK_TO_INITIAL,
                    plan.orderedSteps()
                            .get(prefix - 1)
                            .reversed(),
                    prefix,
                    actual);
        }
        if (distanceToFinal == 1) {
            return InventoryMenuSettlementDecision.withClick(
                    InventoryMenuSettlementDecision.Outcome
                            .CLICK_TO_FINAL,
                    plan.orderedSteps().get(prefix),
                    prefix,
                    actual);
        }
        return InventoryMenuSettlementDecision.withoutClick(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                prefix,
                actual);
    }
}
