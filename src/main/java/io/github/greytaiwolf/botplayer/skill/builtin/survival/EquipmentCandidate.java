package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.Objects;
import java.util.Optional;

/**
 * 不持有 Minecraft 活对象的装备候选快照。
 *
 * <p>不可损坏物品使用 {@code damageable = false} 且剩余耐久为零；可损坏物品的零剩余
 * 耐久表示已经损坏，由策略拒绝。
 */
public record EquipmentCandidate(
        int inventorySlot,
        ItemStackFingerprint itemFingerprint,
        EquipmentSlotKind targetSlot,
        Optional<ToolKind> toolKind,
        int tier,
        double armorPoints,
        double armorToughness,
        double efficiency,
        int remainingDurability,
        boolean damageable,
        boolean canEquip,
        boolean targetBlockedByBinding,
        boolean candidateBindsOnEquip) {
    public static final int MIN_INVENTORY_SLOT = 0;
    public static final int MAX_INVENTORY_SLOT = 40;

    public EquipmentCandidate {
        Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        Objects.requireNonNull(targetSlot, "targetSlot");
        Objects.requireNonNull(toolKind, "toolKind");
        if (inventorySlot < MIN_INVENTORY_SLOT
                || inventorySlot > MAX_INVENTORY_SLOT) {
            throw new IllegalArgumentException(
                    "inventorySlot must be between 0 and 40");
        }
        if (itemFingerprint.isEmpty()) {
            throw new IllegalArgumentException(
                    "equipment candidate must contain an item");
        }
        if (tier < 0) {
            throw new IllegalArgumentException(
                    "equipment tier must not be negative");
        }
        if (remainingDurability < 0) {
            throw new IllegalArgumentException(
                    "remaining durability must not be negative");
        }
        if (!damageable && remainingDurability != 0) {
            throw new IllegalArgumentException(
                    "non-damageable items must use zero remaining durability");
        }
        if (targetSlot == EquipmentSlotKind.MAIN_HAND_TOOL
                && toolKind.isEmpty()) {
            throw new IllegalArgumentException(
                    "main-hand tool candidates require an explicit tool kind");
        }
        if (targetSlot != EquipmentSlotKind.MAIN_HAND_TOOL
                && toolKind.isPresent()) {
            throw new IllegalArgumentException(
                    "only main-hand tool candidates may declare a tool kind");
        }
    }
}
