package io.github.greytaiwolf.botplayer.skill.builtin.breeding;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * 两次喂食各自使用的原生背包精确扣款证明。
 *
 * <p>它有意不以“库存总数减少”代替证明：选择槽、cursor、container 身份和 41 个库存槽
 * 中除固定主手槽外的每一项都必须保持不变。这样即使原版实体交互本身只提供泛化的可观察结果，
 * skill 也不会把别的物品移动、菜单漂移或额外扣款误当成小麦喂食成功。
 */
public final class CowBreedingInventoryProof {
    private final InventoryMenuSnapshot before;
    private final int foodSlot;
    private final ItemStackFingerprint expectedWheat;

    private CowBreedingInventoryProof(
            InventoryMenuSnapshot before,
            int foodSlot,
            ItemStackFingerprint expectedWheat) {
        this.before = Objects.requireNonNull(before, "before");
        this.foodSlot = foodSlot;
        this.expectedWheat = Objects.requireNonNull(
                expectedWheat, "expectedWheat");
    }

    public static CowBreedingInventoryProof freeze(
            InventoryMenuSnapshot before,
            int foodSlot,
            ItemStackFingerprint expectedWheat) {
        InventoryMenuSnapshot snapshot = Objects.requireNonNull(
                before, "before");
        if (foodSlot < 0 || foodSlot > 8) {
            throw new IllegalArgumentException(
                    "foodSlot must be a hotbar inventory slot");
        }
        ItemStackFingerprint wheat = Objects.requireNonNull(
                expectedWheat, "expectedWheat");
        if (wheat.isEmpty()
                || !snapshot.cursor().isEmpty()
                || snapshot.selectedHotbar() != foodSlot
                || !snapshot.itemAt(foodSlot).sameItemAndComponents(wheat)
                || snapshot.itemAt(foodSlot).count() < 1) {
            throw new IllegalArgumentException(
                    "native inventory cannot prove an exact wheat debit");
        }
        return new CowBreedingInventoryProof(snapshot, foodSlot, wheat);
    }

    /**
     * 验证一次交互只从固定选择槽减少一个完全相同的小麦；没有任何自动整理、拾取、cursor
     * 残留或其他槽位写入可以通过该检查。
     */
    public Verification verifyOneDebit(InventoryMenuSnapshot after) {
        InventoryMenuSnapshot actual = Objects.requireNonNull(after, "after");
        if (!before.itemAt(foodSlot).sameItemAndComponents(expectedWheat)) {
            return Verification.FOOD_DEBIT_MISMATCH;
        }
        if (actual.containerId() != before.containerId()) {
            return Verification.MENU_CHANGED;
        }
        if (actual.selectedHotbar() != foodSlot) {
            return Verification.SELECTION_CHANGED;
        }
        if (!actual.cursor().isEmpty()) {
            return Verification.CURSOR_CHANGED;
        }
        List<ItemStackFingerprint> beforeSlots = before.inventorySlots();
        List<ItemStackFingerprint> afterSlots = actual.inventorySlots();
        for (int slot = 0; slot < beforeSlots.size(); slot++) {
            ItemStackFingerprint expected = slot == foodSlot
                    ? decrementedFood()
                    : beforeSlots.get(slot);
            if (!expected.equals(afterSlots.get(slot))) {
                return slot == foodSlot
                        ? Verification.FOOD_DEBIT_MISMATCH
                        : Verification.UNRELATED_INVENTORY_CHANGED;
            }
        }
        return Verification.VERIFIED;
    }

    /** 等待原版 child 出现时，库存控制面仍必须与第二次动作完成时完全一致。 */
    public boolean layoutRemainsStable(InventoryMenuSnapshot after) {
        return before.layoutEqualsIgnoringState(
                Objects.requireNonNull(after, "after"));
    }

    private ItemStackFingerprint decrementedFood() {
        ItemStackFingerprint food = before.itemAt(foodSlot);
        if (food.count() == 1) {
            return ItemStackFingerprint.empty();
        }
        return new ItemStackFingerprint(
                food.itemId(),
                food.count() - 1,
                food.damage(),
                food.componentsDigest());
    }

    public enum Verification {
        VERIFIED,
        MENU_CHANGED,
        SELECTION_CHANGED,
        CURSOR_CHANGED,
        FOOD_DEBIT_MISMATCH,
        UNRELATED_INVENTORY_CHANGED;

        public boolean verified() {
            return this == VERIFIED;
        }
    }
}
