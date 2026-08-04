package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 把可继续解释的菜单前缀绑定到一份计划和精确 stateId 栅栏。
 *
 * <p>稳定权威只接受已确认前缀的精确快照；in-flight 权威额外接受一次相邻点击
 * 可能产生的目标前缀。调用者不能构造非相邻或过宽的前缀集合。
 */
public final class InventoryMenuPrefixAuthority {
    private final InventoryMenuSwapPlan plan;
    private final int sourcePrefix;
    private final InventoryMenuSnapshot sourceSnapshot;
    private final OptionalInt inFlightTargetPrefix;
    private final InventoryMenuSnapshot inFlightTargetSnapshot;

    private InventoryMenuPrefixAuthority(
            InventoryMenuSwapPlan plan,
            int sourcePrefix,
            InventoryMenuSnapshot sourceSnapshot,
            OptionalInt inFlightTargetPrefix,
            InventoryMenuSnapshot inFlightTargetSnapshot) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.sourceSnapshot = Objects.requireNonNull(
                sourceSnapshot, "sourceSnapshot");
        this.inFlightTargetPrefix = Objects.requireNonNull(
                inFlightTargetPrefix,
                "inFlightTargetPrefix");
        this.inFlightTargetSnapshot = inFlightTargetSnapshot;
        requirePrefix(plan, sourcePrefix);
        if (!plan.snapshotAtPrefix(sourcePrefix)
                .layoutEqualsIgnoringState(sourceSnapshot)) {
            throw new IllegalArgumentException(
                    "source snapshot does not match its plan prefix");
        }
        if (inFlightTargetPrefix.isPresent()) {
            int target = inFlightTargetPrefix.getAsInt();
            requirePrefix(plan, target);
            if (Math.abs(target - sourcePrefix) != 1) {
                throw new IllegalArgumentException(
                        "in-flight target must be one adjacent prefix");
            }
            if (inFlightTargetSnapshot == null
                    || !plan.snapshotAtPrefix(target)
                            .layoutEqualsIgnoringState(
                                    inFlightTargetSnapshot)) {
                throw new IllegalArgumentException(
                        "in-flight target snapshot does not match its plan prefix");
            }
        } else if (inFlightTargetSnapshot != null) {
            throw new IllegalArgumentException(
                    "stable authority cannot carry an in-flight target snapshot");
        }
        this.sourcePrefix = sourcePrefix;
    }

    public static InventoryMenuPrefixAuthority stable(
            InventoryMenuSwapPlan plan,
            int sourcePrefix,
            InventoryMenuSnapshot sourceSnapshot) {
        return new InventoryMenuPrefixAuthority(
                plan,
                sourcePrefix,
                sourceSnapshot,
                OptionalInt.empty(),
                null);
    }

    public static InventoryMenuPrefixAuthority inFlight(
            InventoryMenuSwapPlan plan,
            int sourcePrefix,
            int targetPrefix,
            InventoryMenuSnapshot sourceSnapshot,
            InventoryMenuSnapshot targetSnapshot) {
        return new InventoryMenuPrefixAuthority(
                plan,
                sourcePrefix,
                sourceSnapshot,
                OptionalInt.of(targetPrefix),
                Objects.requireNonNull(
                        targetSnapshot, "targetSnapshot"));
    }

    OptionalInt authorizedPrefix(
            InventoryMenuSwapPlan requestedPlan,
            InventoryMenuSnapshot actual) {
        Objects.requireNonNull(requestedPlan, "requestedPlan");
        Objects.requireNonNull(actual, "actual");
        if (plan != requestedPlan) {
            return OptionalInt.empty();
        }
        OptionalInt matched =
                plan.matchingPrefixIgnoringState(actual);
        if (matched.isEmpty()) {
            return OptionalInt.empty();
        }
        int prefix = matched.getAsInt();
        if (prefix == sourcePrefix
                && actual.equals(sourceSnapshot)) {
            return matched;
        }
        return inFlightTargetPrefix.isPresent()
                        && prefix
                                == inFlightTargetPrefix
                                        .getAsInt()
                        && actual.equals(
                                inFlightTargetSnapshot)
                ? matched
                : OptionalInt.empty();
    }

    boolean bindsSource(
            InventoryMenuSwapPlan requestedPlan,
            int requestedSourcePrefix) {
        return plan == requestedPlan
                && sourcePrefix == requestedSourcePrefix;
    }

    OptionalInt inFlightTargetPrefix() {
        return inFlightTargetPrefix;
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
