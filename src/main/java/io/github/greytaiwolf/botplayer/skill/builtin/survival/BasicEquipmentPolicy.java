package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 只消费不可变纯值快照的确定性基础装备策略。
 *
 * <p>本类只负责选出候选，不执行菜单点击、装备动作、持盾或战斗。
 */
public final class BasicEquipmentPolicy {
    private static final Comparator<EquipmentCandidate> CANONICAL_ITEM_ORDER =
            Comparator.comparingInt(EquipmentCandidate::inventorySlot)
                    .thenComparing(candidate ->
                            candidate.itemFingerprint()
                                    .itemId()
                                    .orElseThrow()
                                    .value())
                    .thenComparing(candidate ->
                            candidate.itemFingerprint()
                                    .componentsDigest()
                                    .orElseThrow())
                    .thenComparingInt(candidate ->
                            candidate.itemFingerprint().damage())
                    .thenComparingInt(candidate ->
                            candidate.itemFingerprint().count());

    private static final Comparator<EquipmentCandidate> ARMOR_ORDER =
            Comparator.comparingDouble(EquipmentCandidate::armorPoints)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                            EquipmentCandidate::
                                                    armorToughness)
                                    .reversed())
                    .thenComparing(
                            Comparator.comparingInt(
                                            BasicEquipmentPolicy::
                                                    effectiveDurability)
                                    .reversed())
                    .thenComparing(CANONICAL_ITEM_ORDER);

    private static final Comparator<EquipmentCandidate> TOOL_ORDER =
            Comparator.comparingInt(EquipmentCandidate::tier)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                            EquipmentCandidate::efficiency)
                                    .reversed())
                    .thenComparing(
                            Comparator.comparingInt(
                                            BasicEquipmentPolicy::
                                                    effectiveDurability)
                                    .reversed())
                    .thenComparing(CANONICAL_ITEM_ORDER);

    private BasicEquipmentPolicy() {}

    /**
     * 选择严格优于当前装备的盔甲；等质量候选不会触发无意义换装。
     */
    public static Optional<EquipmentCandidate> chooseArmorUpgrade(
            EquipmentSlotKind targetSlot,
            Optional<EquipmentCandidate> currentEquipment,
            List<EquipmentCandidate> candidates) {
        Objects.requireNonNull(targetSlot, "targetSlot");
        Objects.requireNonNull(currentEquipment, "currentEquipment");
        Objects.requireNonNull(candidates, "candidates");
        if (!targetSlot.isArmorSlot()) {
            throw new IllegalArgumentException(
                    "targetSlot must be an armor slot");
        }

        EquipmentCandidate current = currentEquipment.orElse(null);
        if (current != null && !isComparableCurrentArmor(
                targetSlot, current)) {
            return Optional.empty();
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.targetSlot() == targetSlot)
                .filter(BasicEquipmentPolicy::isEligible)
                .filter(candidate -> current == null
                        || compareArmorQuality(candidate, current) > 0)
                .sorted(ARMOR_ORDER)
                .findFirst();
    }

    /**
     * 按调用方声明的工具用途选择最佳候选，不根据注册名或显示名猜测。
     */
    public static Optional<EquipmentCandidate> chooseTool(
            ToolKind requestedKind,
            List<EquipmentCandidate> candidates) {
        Objects.requireNonNull(requestedKind, "requestedKind");
        Objects.requireNonNull(candidates, "candidates");
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.targetSlot()
                        == EquipmentSlotKind.MAIN_HAND_TOOL)
                .filter(candidate -> candidate.toolKind()
                        .filter(kind -> kind == requestedKind)
                        .isPresent())
                .filter(BasicEquipmentPolicy::isEligible)
                .sorted(TOOL_ORDER)
                .findFirst();
    }

    /**
     * 仅响应绑定了库存槽和物品指纹的显式副手请求。
     *
     * <p>这不是自动持盾策略，也不会根据战斗状态推断副手物品。
     */
    public static Optional<EquipmentCandidate> chooseRequestedOffhand(
            int requestedInventorySlot,
            ItemStackFingerprint expectedItem,
            List<EquipmentCandidate> candidates) {
        Objects.requireNonNull(expectedItem, "expectedItem");
        Objects.requireNonNull(candidates, "candidates");
        if (requestedInventorySlot < EquipmentCandidate.MIN_INVENTORY_SLOT
                || requestedInventorySlot
                        > EquipmentCandidate.MAX_INVENTORY_SLOT
                || expectedItem.isEmpty()) {
            return Optional.empty();
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.targetSlot()
                        == EquipmentSlotKind.OFFHAND)
                .filter(candidate -> candidate.inventorySlot()
                        == requestedInventorySlot)
                .filter(candidate -> candidate.itemFingerprint()
                        .equals(expectedItem))
                .filter(BasicEquipmentPolicy::isEligible)
                .sorted(CANONICAL_ITEM_ORDER)
                .findFirst();
    }

    private static boolean isComparableCurrentArmor(
            EquipmentSlotKind targetSlot,
            EquipmentCandidate current) {
        return current.targetSlot() == targetSlot
                && !current.targetBlockedByBinding()
                && hasFiniteNonNegativeMetrics(current);
    }

    private static boolean isEligible(EquipmentCandidate candidate) {
        return candidate.canEquip()
                && !candidate.targetBlockedByBinding()
                && !candidate.candidateBindsOnEquip()
                && (!candidate.damageable()
                        || candidate.remainingDurability() > 0)
                && hasFiniteNonNegativeMetrics(candidate);
    }

    private static boolean hasFiniteNonNegativeMetrics(
            EquipmentCandidate candidate) {
        return Double.isFinite(candidate.armorPoints())
                && candidate.armorPoints() >= 0.0D
                && Double.isFinite(candidate.armorToughness())
                && candidate.armorToughness() >= 0.0D
                && Double.isFinite(candidate.efficiency())
                && candidate.efficiency() >= 0.0D;
    }

    private static int compareArmorQuality(
            EquipmentCandidate left,
            EquipmentCandidate right) {
        int comparison = Double.compare(
                left.armorPoints(), right.armorPoints());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(
                left.armorToughness(), right.armorToughness());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(
                effectiveDurability(left),
                effectiveDurability(right));
    }

    private static int effectiveDurability(
            EquipmentCandidate candidate) {
        return candidate.damageable()
                ? candidate.remainingDurability()
                : Integer.MAX_VALUE;
    }
}
