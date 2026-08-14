package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于一份刚读取的完整快照构造最小的原版 PICKUP 模板。
 *
 * <p>这不是通用物品模拟器：只表达“完整堆叠从 source 移到空 target”或“两个完整堆叠
 * 交换”，以及从一份完整 source 堆叠精确取出有界数量到空 target 的 P5A 可审计原语。
 * 后者固定为先左键取起 source、逐次右键放入一个物品、再把余量放回 source。拖拽、
 * 克隆、丢弃、pickup-all 和向非空 target 合并一律不在这里表示。
 */
public final class MenuTransactionTemplateBuilder {
    private MenuTransactionTemplateBuilder() {
    }

    /**
     * 为一次 source→target 的完整堆叠移动/交换生成逐点击、逐布局模板。
     *
     * <p>初始 cursor 必须为空。source 为空、目标与源相同或菜单布局不满足时返回空，
     * 由上层把它视为外部变化而不是猜测继续。
     */
    public static Optional<MenuTransactionTemplate> moveOrSwap(
            MenuSnapshot snapshot, int sourceSlot, int targetSlot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MenuFamily family = snapshot.family();
        family.requireSlot(sourceSlot);
        family.requireSlot(targetSlot);
        if (sourceSlot == targetSlot || !snapshot.carried().isEmpty()) {
            return Optional.empty();
        }
        ItemStackFingerprint source = snapshot.itemAt(sourceSlot);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        ItemStackFingerprint target = snapshot.itemAt(targetSlot);
        MenuLayout initial = MenuLayout.from(snapshot);
        List<MenuTemplateStep> steps = new ArrayList<>(
                target.isEmpty() ? 2 : 3);

        MenuLayout pickupSource = replace(
                initial, sourceSlot, ItemStackFingerprint.empty(), source);
        addPickup(steps, initial, pickupSource, sourceSlot);

        MenuLayout placeOrSwap = replace(
                pickupSource, targetSlot, source, target);
        addPickup(steps, pickupSource, placeOrSwap, targetSlot);

        MenuLayout finalLayout = placeOrSwap;
        if (!target.isEmpty()) {
            finalLayout = replace(
                    placeOrSwap,
                    sourceSlot,
                    target,
                    ItemStackFingerprint.empty());
            addPickup(steps, placeOrSwap, finalLayout, sourceSlot);
        }
        return Optional.of(new MenuTransactionTemplate(
                family, initial, steps, finalLayout));
    }

    /**
     * 将 source 中恰好 {@code amount} 个物品移入空 target，并在每次右键后严格核验
     * cursor、source 与 target 的完整布局。
     *
     * <p>数量必须小于等于 source 当前堆叠；source 恰好等于该数量时退化为两次左键的
     * 完整移动。非空 target 被保守拒绝，避免在不知道原版最大堆叠数量或组件合并规则时
     * 猜测合并后的布局。
     */
    public static Optional<MenuTransactionTemplate> moveExactAmount(
            MenuSnapshot snapshot,
            int sourceSlot,
            int targetSlot,
            int amount) {
        Objects.requireNonNull(snapshot, "snapshot");
        MenuFamily family = snapshot.family();
        family.requireSlot(sourceSlot);
        family.requireSlot(targetSlot);
        if (amount < 1
                || sourceSlot == targetSlot
                || !snapshot.carried().isEmpty()) {
            return Optional.empty();
        }
        ItemStackFingerprint source = snapshot.itemAt(sourceSlot);
        ItemStackFingerprint target = snapshot.itemAt(targetSlot);
        if (source.isEmpty() || !target.isEmpty() || source.count() < amount) {
            return Optional.empty();
        }
        if (source.count() == amount) {
            return moveOrSwap(snapshot, sourceSlot, targetSlot);
        }

        MenuLayout initial = MenuLayout.from(snapshot);
        List<MenuTemplateStep> steps = new ArrayList<>(amount + 2);
        MenuLayout current = replace(
                initial, sourceSlot, ItemStackFingerprint.empty(), source);
        addPickup(steps, initial, current, sourceSlot);

        for (int transferred = 1; transferred <= amount; transferred++) {
            MenuLayout next = replace(
                    current,
                    targetSlot,
                    withCount(source, transferred),
                    withCount(source, source.count() - transferred));
            addRightPickup(steps, current, next, targetSlot);
            current = next;
        }

        MenuLayout finalLayout = replace(
                current,
                sourceSlot,
                withCount(source, source.count() - amount),
                ItemStackFingerprint.empty());
        addPickup(steps, current, finalLayout, sourceSlot);
        return Optional.of(new MenuTransactionTemplate(
                family, initial, steps, finalLayout));
    }

    private static void addPickup(
            List<MenuTemplateStep> steps,
            MenuLayout before,
            MenuLayout after,
            int slot) {
        steps.add(new MenuTemplateStep(
                new MenuClick(slot, MenuClickType.PICKUP, 0),
                before,
                after,
                MenuConservationRule.strict()));
    }

    private static void addRightPickup(
            List<MenuTemplateStep> steps,
            MenuLayout before,
            MenuLayout after,
            int slot) {
        steps.add(new MenuTemplateStep(
                new MenuClick(slot, MenuClickType.PICKUP, 1),
                before,
                after,
                MenuConservationRule.strict()));
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
        return new MenuLayout(
                before.family(),
                Objects.requireNonNull(carried, "carried"),
                slots);
    }
}
