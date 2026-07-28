package io.github.greytaiwolf.botplayer.perception;

import java.util.EnumMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PerceptionBudgetTest {
    @Test
    void enforcesCategoryAndSharedGlobalLimitsWithoutChargingRejectedWork() {
        GlobalPerceptionBudget global = new GlobalPerceptionBudget(3);
        PerceptionBudget first = new PerceptionBudget(global, limits(2));
        PerceptionBudget second = new PerceptionBudget(global, limits(2));

        Assertions.assertTrue(first.tryConsume(BudgetKind.ENTITY_READ, 2));
        Assertions.assertFalse(first.tryConsume(BudgetKind.ENTITY_READ));
        Assertions.assertEquals(2, global.used());
        Assertions.assertEquals(0, first.remaining(BudgetKind.ENTITY_READ));

        Assertions.assertTrue(second.tryConsume(BudgetKind.BLOCK_READ));
        Assertions.assertFalse(second.tryConsume(BudgetKind.RAYCAST));
        Assertions.assertEquals(3, global.used());
        Assertions.assertEquals(2, first.report().used().get(BudgetKind.ENTITY_READ));
        Assertions.assertEquals(1, second.report().used().get(BudgetKind.BLOCK_READ));
        Assertions.assertTrue(first.report().exhausted());
        Assertions.assertTrue(second.report().exhausted());
    }

    @Test
    void validatesCompleteNonNegativeCategoryLimits() {
        GlobalPerceptionBudget global = new GlobalPerceptionBudget(4);
        EnumMap<BudgetKind, Integer> missing =
                new EnumMap<>(BudgetKind.class);
        missing.put(BudgetKind.ENTITY_READ, 1);
        EnumMap<BudgetKind, Integer> negative = limits(1);
        negative.put(BudgetKind.RAYCAST, -1);

        Assertions.assertThrows(
                NullPointerException.class,
                () -> new PerceptionBudget(global, missing));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionBudget(global, negative));
        PerceptionBudget valid = new PerceptionBudget(global, limits(1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> valid.tryConsume(BudgetKind.EVENT_READ, -1));
    }

    @Test
    void rejectsCounterOverflowWithoutCorruptingUsage() {
        GlobalPerceptionBudget global =
                new GlobalPerceptionBudget(Integer.MAX_VALUE);
        PerceptionBudget budget =
                new PerceptionBudget(global, limits(Integer.MAX_VALUE));

        Assertions.assertTrue(budget.tryConsume(BudgetKind.BLOCK_READ));
        Assertions.assertFalse(
                budget.tryConsume(BudgetKind.BLOCK_READ, Integer.MAX_VALUE));
        Assertions.assertEquals(1, global.used());
        Assertions.assertEquals(
                1, budget.report().used().get(BudgetKind.BLOCK_READ));
    }

    private static EnumMap<BudgetKind, Integer> limits(int value) {
        EnumMap<BudgetKind, Integer> limits =
                new EnumMap<>(BudgetKind.class);
        for (BudgetKind kind : BudgetKind.values()) {
            limits.put(kind, value);
        }
        return limits;
    }
}
