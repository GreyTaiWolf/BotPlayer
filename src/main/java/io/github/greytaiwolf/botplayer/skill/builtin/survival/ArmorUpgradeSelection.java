package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import java.util.Objects;

/**
 * 一次尚未执行的基础盔甲升级选择。
 *
 * <p>源槽是原版玩家可携带槽 0..35，目标槽是同一 {@code Inventory}
 * 的盔甲槽 36..39。该值只冻结规划结果，不执行菜单点击。
 */
public record ArmorUpgradeSelection(
        int sourceInventorySlot,
        int targetInventorySlot,
        EquipmentCandidate candidate) {
    public static final int FIRST_CARRIED_SLOT = 0;
    public static final int LAST_CARRIED_SLOT = 35;

    public ArmorUpgradeSelection {
        Objects.requireNonNull(candidate, "candidate");
        if (sourceInventorySlot < FIRST_CARRIED_SLOT
                || sourceInventorySlot > LAST_CARRIED_SLOT) {
            throw new IllegalArgumentException(
                    "sourceInventorySlot must be in 0..35");
        }
        if (!candidate.targetSlot().isArmorSlot()) {
            throw new IllegalArgumentException(
                    "candidate must target an armor slot");
        }
        if (candidate.inventorySlot() != sourceInventorySlot) {
            throw new IllegalArgumentException(
                    "candidate inventory slot must match the source slot");
        }
        if (targetInventorySlot
                != targetInventorySlotFor(candidate.targetSlot())) {
            throw new IllegalArgumentException(
                    "target inventory slot must match the armor slot");
        }
        if (candidate.itemFingerprint().count() != 1) {
            throw new IllegalArgumentException(
                    "armor upgrade candidate must contain exactly one item");
        }
        if (!candidate.canEquip()
                || candidate.targetBlockedByBinding()
                || candidate.candidateBindsOnEquip()
                || (candidate.damageable()
                        && candidate.remainingDurability() == 0)) {
            throw new IllegalArgumentException(
                    "armor upgrade candidate must be eligible");
        }
    }

    public static int targetInventorySlotFor(
            EquipmentSlotKind targetSlot) {
        Objects.requireNonNull(targetSlot, "targetSlot");
        return switch (targetSlot) {
            case HEAD -> 39;
            case CHEST -> 38;
            case LEGS -> 37;
            case FEET -> 36;
            case OFFHAND, MAIN_HAND_TOOL ->
                    throw new IllegalArgumentException(
                            "targetSlot must be an armor slot");
        };
    }
}
