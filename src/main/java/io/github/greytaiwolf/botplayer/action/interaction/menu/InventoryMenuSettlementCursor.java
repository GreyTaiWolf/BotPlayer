package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;

/**
 * 把一次多步菜单收口绑定到首次选定的安全端点。
 *
 * <p>游标同时绑定具体计划对象和已确认前缀。后续 Tick 只能留在
 * 该前缀，或沿冻结端点方向前进一个相邻前缀，因此不能重新按当前
 * 位置选边并在初态与终态之间振荡。
 */
public final class InventoryMenuSettlementCursor {

    public enum Endpoint {
        INITIAL,
        FINAL
    }

    private final InventoryMenuSwapPlan plan;
    private final Endpoint endpoint;
    private final int confirmedPrefix;

    private InventoryMenuSettlementCursor(
            InventoryMenuSwapPlan plan,
            Endpoint endpoint,
            int confirmedPrefix) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.endpoint = Objects.requireNonNull(
                endpoint, "endpoint");
        requirePrefix(plan, confirmedPrefix);
        this.confirmedPrefix = confirmedPrefix;
    }

    static InventoryMenuSettlementCursor nearest(
            InventoryMenuSwapPlan plan, int prefix) {
        Objects.requireNonNull(plan, "plan");
        requirePrefix(plan, prefix);
        int finalPrefix = plan.orderedSteps().size();
        Endpoint selected = prefix <= finalPrefix - prefix
                ? Endpoint.INITIAL
                : Endpoint.FINAL;
        return new InventoryMenuSettlementCursor(
                plan, selected, prefix);
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    public int confirmedPrefix() {
        return confirmedPrefix;
    }

    public boolean atEndpoint() {
        return confirmedPrefix == endpointPrefix();
    }

    /**
     * 返回该决定所提议的一次点击成功后的新游标。
     */
    public InventoryMenuSettlementCursor
            advanceAfterProposedClick() {
        if (atEndpoint()) {
            throw new IllegalStateException(
                    "settlement cursor is already at its endpoint");
        }
        return new InventoryMenuSettlementCursor(
                plan, endpoint, adjacentPrefixTowardEndpoint());
    }

    InventoryMenuSwapPlan plan() {
        return plan;
    }

    boolean permits(InventoryMenuPrefixAuthority authority) {
        if (!authority.bindsSource(plan, confirmedPrefix)) {
            return false;
        }
        return authority.inFlightTargetPrefix().isEmpty()
                || (!atEndpoint()
                        && authority.inFlightTargetPrefix()
                                        .getAsInt()
                                == adjacentPrefixTowardEndpoint());
    }

    InventoryMenuSettlementCursor observeAuthorizedPrefix(
            int prefix) {
        if (prefix == confirmedPrefix) {
            return this;
        }
        if (!atEndpoint()
                && prefix == adjacentPrefixTowardEndpoint()) {
            return new InventoryMenuSettlementCursor(
                    plan, endpoint, prefix);
        }
        throw new IllegalArgumentException(
                "authorized prefix moves away from the frozen endpoint");
    }

    int endpointPrefix() {
        return endpoint == Endpoint.INITIAL
                ? 0
                : plan.orderedSteps().size();
    }

    int adjacentPrefixTowardEndpoint() {
        return endpoint == Endpoint.INITIAL
                ? confirmedPrefix - 1
                : confirmedPrefix + 1;
    }

    private static void requirePrefix(
            InventoryMenuSwapPlan plan, int prefix) {
        if (prefix < 0
                || prefix > plan.orderedSteps().size()) {
            throw new IllegalArgumentException(
                    "prefix is outside the plan");
        }
    }
}
