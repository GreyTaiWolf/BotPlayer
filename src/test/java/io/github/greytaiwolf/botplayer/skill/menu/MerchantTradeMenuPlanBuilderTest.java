package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MerchantTradeMenuPlanBuilderTest {
    private static final ItemStackFingerprint COST =
            stack("minecraft:wheat", 5, 'a');
    private static final ItemStackFingerprint SOURCE =
            stack("minecraft:wheat", 7, 'a');
    private static final ItemStackFingerprint RESULT =
            stack("minecraft:emerald", 1, 'b');
    private static final int SOURCE_SLOT = 3;
    private static final int OUTPUT_SLOT = 30;

    @Test
    void buildsBoundedRightClickPaymentAndTerminalNativeQuickMove() {
        MenuSnapshot opened = strictOpened();
        MenuTransactionPlan plan = MerchantTradeMenuPlanBuilder.build(
                opened, COST, RESULT, SOURCE_SLOT, OUTPUT_SLOT)
                .orElseThrow()
                .bind(opened)
                .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(COST.count() + 3,
                        plan.orderedSteps().size()),
                () -> Assertions.assertEquals(
                        new MenuClick(SOURCE_SLOT, MenuClickType.PICKUP, 0),
                        plan.orderedSteps().get(0).click()),
                () -> Assertions.assertEquals(
                        new MenuClick(
                                MerchantTradeMenuPlanBuilder.PAYMENT_A_SLOT,
                                MenuClickType.PICKUP,
                                1),
                        plan.orderedSteps().get(1).click()),
                () -> Assertions.assertEquals(
                        new MenuClick(SOURCE_SLOT, MenuClickType.PICKUP, 0),
                        plan.orderedSteps().get(COST.count() + 1).click()),
                () -> Assertions.assertEquals(
                        new MenuClick(
                                MerchantTradeMenuPlanBuilder.RESULT_SLOT,
                                MenuClickType.QUICK_MOVE,
                                0),
                        plan.orderedSteps().get(COST.count() + 2).click()),
                () -> Assertions.assertEquals(stack("minecraft:wheat", 2, 'a'),
                        plan.finalSnapshot().itemAt(SOURCE_SLOT)),
                () -> Assertions.assertEquals(RESULT,
                        plan.finalSnapshot().itemAt(OUTPUT_SLOT)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(
                                MerchantTradeMenuPlanBuilder.PAYMENT_A_SLOT)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(
                                MerchantTradeMenuPlanBuilder.RESULT_SLOT)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().carried()));

        MenuTransaction transaction = MenuTransaction.opening(
                MenuFamily.MERCHANT,
                new MenuTransactionLimits(COST.count() + 3, 30),
                0L);
        Assertions.assertTrue(transaction.observeOpened(opened, 0L));
        transaction.beginPlanning(0L);
        Assertions.assertTrue(transaction.installPlan(plan, 0L));
        MenuSnapshot current = opened;
        for (int index = 0; index < plan.orderedSteps().size(); index++) {
            Assertions.assertEquals(plan.orderedSteps().get(index).click(),
                    transaction.issueNextClick(index + 1L, current).orElseThrow());
            current = plan.orderedSteps().get(index).expectedAfter();
            Assertions.assertTrue(transaction.acknowledge(current,
                    index + 1L));
        }
        Assertions.assertTrue(transaction.verify(current, 20L));
        Assertions.assertTrue(transaction.closeConfirmed(20L));
        Assertions.assertEquals(MenuTransactionState.COMPLETED,
                transaction.state());
    }

    @Test
    void rejectsAnyAmbiguousQuickMoveDestinationOrDirtyMerchantState() {
        MenuSnapshot opened = strictOpened();
        List<ItemStackFingerprint> alternateEmpty = new ArrayList<>(
                opened.slots());
        alternateEmpty.set(31, ItemStackFingerprint.empty());
        List<ItemStackFingerprint> resultMergeTarget = new ArrayList<>(
                opened.slots());
        resultMergeTarget.set(31, RESULT);
        List<ItemStackFingerprint> dirtyInput = new ArrayList<>(opened.slots());
        dirtyInput.set(MerchantTradeMenuPlanBuilder.PAYMENT_A_SLOT,
                stack("minecraft:wheat", 1, 'a'));
        List<ItemStackFingerprint> exactSourceCost = new ArrayList<>(
                opened.slots());
        exactSourceCost.set(SOURCE_SLOT, COST);

        Assertions.assertAll(
                () -> Assertions.assertTrue(MerchantTradeMenuPlanBuilder.build(
                        snapshot(alternateEmpty), COST, RESULT,
                        SOURCE_SLOT, OUTPUT_SLOT).isEmpty()),
                () -> Assertions.assertTrue(MerchantTradeMenuPlanBuilder.build(
                        snapshot(resultMergeTarget), COST, RESULT,
                        SOURCE_SLOT, OUTPUT_SLOT).isEmpty()),
                () -> Assertions.assertTrue(MerchantTradeMenuPlanBuilder.build(
                        snapshot(dirtyInput), COST, RESULT,
                        SOURCE_SLOT, OUTPUT_SLOT).isEmpty()),
                () -> Assertions.assertTrue(MerchantTradeMenuPlanBuilder.build(
                        snapshot(exactSourceCost), COST, RESULT,
                        SOURCE_SLOT, OUTPUT_SLOT).isEmpty()));
    }

    private static MenuSnapshot strictOpened() {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int slot = 0; slot < MenuFamily.MERCHANT.slotCount(); slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(SOURCE_SLOT, SOURCE);
        for (int slot = MerchantTradeMenuPlanBuilder.FIRST_PLAYER_SLOT;
                slot <= MerchantTradeMenuPlanBuilder.LAST_PLAYER_SLOT;
                slot++) {
            if (slot != SOURCE_SLOT && slot != OUTPUT_SLOT) {
                slots.set(slot, stack("minecraft:cobblestone", 64, 'c'));
            }
        }
        return snapshot(slots);
    }

    private static MenuSnapshot snapshot(List<ItemStackFingerprint> slots) {
        return new MenuSnapshot(
                MenuFamily.MERCHANT,
                7,
                11,
                ItemStackFingerprint.empty(),
                slots);
    }

    private static ItemStackFingerprint stack(
            String itemId, int count, char digest) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0,
                String.valueOf(digest).repeat(64));
    }
}
