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
 * 交换”这一条 P5A 可审计原语。部分堆叠、拖拽、克隆、丢弃和 pickup-all 一律不在这里
 * 表示，调用方应使用专门且带显式守恒规则的 adapter。
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
