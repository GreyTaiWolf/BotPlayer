package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 为一次狭义的原版 {@code MerchantMenu} 交易建立完整、逐点击的菜单计划。
 *
 * <p>该 builder 故意没有模拟任意 merchant UI。它只允许单支付 offer，支付物从一个比
 * price 更大的玩家堆叠中逐右键投入；所有其他可交易背包格都必须占用，且不能出现同身份的
 * result。于是最后一次 {@code QUICK_MOVE} 只能把 result 放到指定且唯一的空 output 格，
 * 不会在取消边界把已领取结果悬在 cursor 上。offer 产生/消耗的可见 result 与支付物扣除由
 * 显式守恒差额表示，其余每一步都严格守恒。
 */
public final class MerchantTradeMenuPlanBuilder {
    public static final int PAYMENT_A_SLOT = 0;
    public static final int PAYMENT_B_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    public static final int FIRST_PLAYER_SLOT = 3;
    public static final int LAST_PLAYER_SLOT = 38;

    private MerchantTradeMenuPlanBuilder() {
    }

    /**
     * 由已经打开、完整读取并验证为精确 {@link MenuFamily#MERCHANT} 的快照生成计划。
     *
     * <p>调用方仍须在真正 {@code clicked()} 之前检查原版 offer 身份、menu 类与实体绑定。
     * 空结果代表外部状态/布局不满足这条极窄交易合同，而不是允许退化成模糊 quick-move。
     */
    public static Optional<MenuTransactionTemplate> build(
            MenuSnapshot snapshot,
            ItemStackFingerprint cost,
            ItemStackFingerprint result,
            int sourceSlot,
            int outputSlot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(result, "result");
        try {
            if (snapshot.family() != MenuFamily.MERCHANT
                    || cost.isEmpty()
                    || result.isEmpty()
                    || cost.count() > 16
                    || cost.sameItemAndComponents(result)
                    || !snapshot.carried().isEmpty()
                    || !isPlayerSlot(sourceSlot)
                    || !isPlayerSlot(outputSlot)
                    || sourceSlot == outputSlot
                    || !snapshot.itemAt(PAYMENT_A_SLOT).isEmpty()
                    || !snapshot.itemAt(PAYMENT_B_SLOT).isEmpty()
                    || !snapshot.itemAt(RESULT_SLOT).isEmpty()) {
                return Optional.empty();
            }

            ItemStackFingerprint source = snapshot.itemAt(sourceSlot);
            if (source.isEmpty()
                    || !source.sameItemAndComponents(cost)
                    || source.count() <= cost.count()
                    || !snapshot.itemAt(outputSlot).isEmpty()
                    || !hasUniqueOutputCapacity(snapshot, result, outputSlot)) {
                return Optional.empty();
            }

            MenuLayout current = MenuLayout.from(snapshot);
            List<MenuTemplateStep> steps = new ArrayList<>(
                    cost.count() + 3);

            MenuLayout pickedUp = replace(
                    current,
                    sourceSlot,
                    ItemStackFingerprint.empty(),
                    source);
            addPickup(steps, current, pickedUp, sourceSlot,
                    MenuConservationRule.strict());
            current = pickedUp;

            for (int paid = 1; paid <= cost.count(); paid++) {
                ItemStackFingerprint partialCost = withCount(cost, paid);
                ItemStackFingerprint remaining = withCount(
                        source, source.count() - paid);
                MenuLayout afterPayment = replace(
                        current,
                        PAYMENT_A_SLOT,
                        partialCost,
                        remaining);
                if (paid == cost.count()) {
                    afterPayment = replaceSlot(
                            afterPayment, RESULT_SLOT, result);
                }
                addPickup(steps, current, afterPayment, PAYMENT_A_SLOT,
                        paid == cost.count()
                                ? resultAppeared(result)
                                : MenuConservationRule.strict());
                current = afterPayment;
            }

            MenuLayout returnedRemainder = replace(
                    current,
                    sourceSlot,
                    withCount(source, source.count() - cost.count()),
                    ItemStackFingerprint.empty());
            addPickup(steps, current, returnedRemainder, sourceSlot,
                    MenuConservationRule.strict());
            current = returnedRemainder;

            MenuLayout completed = replaceSlot(
                    replaceSlot(
                            replaceSlot(current,
                                    PAYMENT_A_SLOT,
                                    ItemStackFingerprint.empty()),
                            PAYMENT_B_SLOT,
                            ItemStackFingerprint.empty()),
                    RESULT_SLOT,
                    ItemStackFingerprint.empty());
            completed = replaceSlot(completed, outputSlot, result);
            addQuickMove(steps, current, completed, RESULT_SLOT,
                    paymentConsumed(cost));

            return Optional.of(new MenuTransactionTemplate(
                    MenuFamily.MERCHANT,
                    MenuLayout.from(snapshot),
                    steps,
                    completed));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasUniqueOutputCapacity(
            MenuSnapshot snapshot,
            ItemStackFingerprint result,
            int outputSlot) {
        for (int slot = FIRST_PLAYER_SLOT;
                slot <= LAST_PLAYER_SLOT;
                slot++) {
            ItemStackFingerprint item = snapshot.itemAt(slot);
            if (slot == outputSlot) {
                if (!item.isEmpty()) {
                    return false;
                }
                continue;
            }
            /*
             * A non-empty result-identical stack could accept a QUICK_MOVE merge
             * before the requested empty output slot. Reject it even if it happens
             * to be full now: item max-stack data belongs to the native menu, and
             * this pure contract deliberately does not guess it.
             */
            if (item.isEmpty() || item.sameItemAndComponents(result)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlayerSlot(int slot) {
        return slot >= FIRST_PLAYER_SLOT && slot <= LAST_PLAYER_SLOT
                && MenuFamily.MERCHANT.isPlayerInventorySlot(slot);
    }

    private static MenuConservationRule resultAppeared(
            ItemStackFingerprint result) {
        return new MenuConservationRule(Map.of(
                MenuItemKey.from(result).orElseThrow(),
                (long) result.count()));
    }

    private static MenuConservationRule paymentConsumed(
            ItemStackFingerprint cost) {
        return new MenuConservationRule(Map.of(
                MenuItemKey.from(cost).orElseThrow(),
                -((long) cost.count())));
    }

    private static void addPickup(
            List<MenuTemplateStep> steps,
            MenuLayout before,
            MenuLayout after,
            int slot,
            MenuConservationRule conservation) {
        steps.add(new MenuTemplateStep(
                new MenuClick(slot, MenuClickType.PICKUP,
                        slot == PAYMENT_A_SLOT
                                && !before.carried().isEmpty() ? 1 : 0),
                before,
                after,
                conservation));
    }

    private static void addQuickMove(
            List<MenuTemplateStep> steps,
            MenuLayout before,
            MenuLayout after,
            int slot,
            MenuConservationRule conservation) {
        steps.add(new MenuTemplateStep(
                new MenuClick(slot, MenuClickType.QUICK_MOVE, 0),
                before,
                after,
                conservation));
    }

    private static ItemStackFingerprint withCount(
            ItemStackFingerprint item, int count) {
        if (count == 0) {
            return ItemStackFingerprint.empty();
        }
        return new ItemStackFingerprint(
                item.itemId(), count, item.damage(), item.componentsDigest());
    }

    private static MenuLayout replace(
            MenuLayout before,
            int slot,
            ItemStackFingerprint replacement,
            ItemStackFingerprint carried) {
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(slot, Objects.requireNonNull(replacement, "replacement"));
        return new MenuLayout(before.family(),
                Objects.requireNonNull(carried, "carried"), slots);
    }

    private static MenuLayout replaceSlot(
            MenuLayout before,
            int slot,
            ItemStackFingerprint replacement) {
        return replace(before, slot, replacement, before.carried());
    }
}
