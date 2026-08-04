package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;

/**
 * Pure finite-state classifier for the two slots owned by the bounded eating
 * transaction at a lifecycle boundary.
 */
public final class InventoryLayoutCleanupPolicy {
    private InventoryLayoutCleanupPolicy() {}

    public static boolean conservesInventory(
            InventoryLayoutCleanupRequest request,
            InventoryContentsSnapshot currentInventory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(
                currentInventory, "currentInventory");
        Conservation conservation =
                conserveInventory(
                        request.inventoryBefore(),
                        currentInventory,
                        request.expectedFood());
        return conservation.conserved()
                && conservation.consumedCount() >= 0
                && conservation.consumedCount() <= 1;
    }

    public static Assessment assess(
            InventoryLayoutCleanupRequest request,
            ItemStackFingerprint source,
            ItemStackFingerprint temporary,
            InventoryContentsSnapshot currentInventory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(temporary, "temporary");
        Objects.requireNonNull(
                currentInventory, "currentInventory");
        Conservation conservation =
                conserveInventory(
                        request.inventoryBefore(),
                        currentInventory,
                        request.expectedFood());
        int consumed = conservation.consumedCount();
        if (!conservation.conserved()
                || consumed < 0
                || consumed > 1) {
            return new Assessment(
                    Decision.UNSAFE,
                    consumed,
                    -1);
        }

        int expectedRemainder =
                request.expectedFood().count() - consumed;
        if (expectedRemainder == 0) {
            if (containsExpectedFood(
                            source, request.expectedFood())
                    || containsExpectedFood(
                            temporary,
                            request.expectedFood())) {
                return new Assessment(
                        Decision.UNSAFE,
                        consumed,
                        expectedRemainder);
            }
            return new Assessment(
                    temporary.isEmpty()
                            ? source.isEmpty()
                                    ? Decision.ALREADY_SAFE
                                    : Decision
                                            .SAFE_LAYOUT_COMMITTED
                            : Decision.UNSAFE,
                    consumed,
                    expectedRemainder);
        }

        boolean sourceHasRemainder =
                matchesRemainder(
                        source,
                        request.expectedFood(),
                        expectedRemainder);
        boolean temporaryHasRemainder =
                matchesRemainder(
                        temporary,
                        request.expectedFood(),
                        expectedRemainder);
        boolean sourceContainsExpected =
                containsExpectedFood(
                        source, request.expectedFood());
        boolean temporaryContainsExpected =
                containsExpectedFood(
                        temporary,
                        request.expectedFood());
        if ((sourceContainsExpected
                        && !sourceHasRemainder)
                || (temporaryContainsExpected
                        && !temporaryHasRemainder)) {
            return new Assessment(
                    Decision.UNSAFE,
                    consumed,
                    expectedRemainder);
        }
        if (sourceHasRemainder
                && !temporaryHasRemainder) {
            return new Assessment(
                    temporary.isEmpty()
                            ? Decision.ALREADY_SAFE
                            : Decision.UNSAFE,
                    consumed,
                    expectedRemainder);
        }
        if (temporaryHasRemainder
                && source.isEmpty()) {
            return new Assessment(
                    Decision.TEMPORARY_LAYOUT,
                    consumed,
                    expectedRemainder);
        }
        if (temporaryHasRemainder
                && !sourceContainsExpected) {
            return new Assessment(
                    Decision.SAFE_LAYOUT_COMMITTED,
                    consumed,
                    expectedRemainder);
        }
        return new Assessment(
                Decision.UNSAFE,
                consumed,
                expectedRemainder);
    }

    private static Conservation conserveInventory(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot current,
            ItemStackFingerprint expectedFood) {
        int consumed =
                before.matchingCount(expectedFood)
                        - current.matchingCount(
                                expectedFood);
        if (consumed < 0 || consumed > 1) {
            return new Conservation(consumed, false);
        }
        for (ItemStackFingerprint baseline :
                before.itemTotals()) {
            if (baseline.sameItemAndComponents(
                    expectedFood)) {
                continue;
            }
            if (current.matchingCount(baseline)
                    != baseline.count()) {
                return new Conservation(
                        consumed, false);
            }
        }
        for (ItemStackFingerprint actual :
                current.itemTotals()) {
            if (actual.sameItemAndComponents(
                            expectedFood)
                    || before.containsIdentity(actual)) {
                continue;
            }
            return new Conservation(
                    consumed, false);
        }
        return new Conservation(consumed, true);
    }

    public static boolean matchesRemainder(
            ItemStackFingerprint actual,
            ItemStackFingerprint expectedFood,
            int expectedCount) {
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(
                expectedFood, "expectedFood");
        if (expectedCount < 1) {
            return false;
        }
        return actual.sameItemAndComponents(expectedFood)
                && actual.count() == expectedCount;
    }

    private static boolean containsExpectedFood(
            ItemStackFingerprint actual,
            ItemStackFingerprint expectedFood) {
        return !actual.isEmpty()
                && actual.sameItemAndComponents(expectedFood);
    }

    public record Assessment(
            Decision decision,
            int consumedCount,
            int expectedRemainderCount) {
        public Assessment {
            Objects.requireNonNull(decision, "decision");
        }
    }

    public enum Decision {
        ALREADY_SAFE,
        TEMPORARY_LAYOUT,
        SAFE_LAYOUT_COMMITTED,
        UNSAFE
    }

    private record Conservation(
            int consumedCount, boolean conserved) {}
}
