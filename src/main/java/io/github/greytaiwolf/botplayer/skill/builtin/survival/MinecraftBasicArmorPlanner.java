package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 从真实玩家背包冻结一条保守、确定性的基础盔甲升级选择。
 *
 * <p>该规划器只识别 {@link ArmorItem} 的原版防御与韧性字段，扫描玩家可携带槽
 * 0..35，不处理工具、副手、模组属性修饰或任何菜单副作用。
 */
public final class MinecraftBasicArmorPlanner {
    private static final List<ArmorTarget> TARGETS = List.of(
            new ArmorTarget(
                    EquipmentSlotKind.HEAD,
                    EquipmentSlot.HEAD,
                    39),
            new ArmorTarget(
                    EquipmentSlotKind.CHEST,
                    EquipmentSlot.CHEST,
                    38),
            new ArmorTarget(
                    EquipmentSlotKind.LEGS,
                    EquipmentSlot.LEGS,
                    37),
            new ArmorTarget(
                    EquipmentSlotKind.FEET,
                    EquipmentSlot.FEET,
                    36));

    private MinecraftBasicArmorPlanner() {
    }

    /**
     * 按 HEAD、CHEST、LEGS、FEET 的固定顺序返回第一条最佳严格升级。
     */
    public static Optional<ArmorUpgradeSelection> plan(
            BotServerPlayer player) {
        requireServerThread(player);
        Inventory inventory = player.getInventory();
        Map<EquipmentSlotKind, CurrentArmor> currentBySlot =
                new EnumMap<>(EquipmentSlotKind.class);
        Map<EquipmentSlotKind, List<EquipmentCandidate>>
                candidatesBySlot =
                        new EnumMap<>(EquipmentSlotKind.class);

        for (ArmorTarget target : TARGETS) {
            currentBySlot.put(
                    target.kind(),
                    captureCurrent(player, target));
            candidatesBySlot.put(
                    target.kind(), new ArrayList<>());
        }

        for (int inventorySlot =
                        ArmorUpgradeSelection.FIRST_CARRIED_SLOT;
                inventorySlot
                        <= ArmorUpgradeSelection.LAST_CARRIED_SLOT;
                inventorySlot++) {
            ItemStack stack = inventory.getItem(inventorySlot);
            ArmorTarget target = targetFor(player, stack);
            if (target == null) {
                continue;
            }
            CurrentArmor current =
                    currentBySlot.get(target.kind());
            if (!current.valid()) {
                continue;
            }
            captureCandidate(
                            player,
                            stack,
                            inventorySlot,
                            target,
                            current.blocksReplacement())
                    .ifPresent(candidatesBySlot
                            .get(target.kind())::add);
        }

        for (ArmorTarget target : TARGETS) {
            CurrentArmor current =
                    currentBySlot.get(target.kind());
            if (!current.valid()) {
                continue;
            }
            Optional<EquipmentCandidate> chosen =
                    BasicEquipmentPolicy.chooseArmorUpgrade(
                            target.kind(),
                            current.candidate(),
                            candidatesBySlot.get(target.kind()));
            if (chosen.isPresent()) {
                EquipmentCandidate candidate =
                        chosen.orElseThrow();
                return Optional.of(new ArmorUpgradeSelection(
                        candidate.inventorySlot(),
                        target.inventorySlot(),
                        candidate));
            }
        }
        return Optional.empty();
    }

    private static CurrentArmor captureCurrent(
            BotServerPlayer player, ArmorTarget target) {
        ItemStack stack =
                player.getItemBySlot(target.minecraftSlot());
        if (stack.isEmpty()) {
            return CurrentArmor.empty();
        }
        if (stack.getCount() != 1
                || !(stack.getItem() instanceof ArmorItem)) {
            return CurrentArmor.invalid();
        }
        try {
            if (player.getEquipmentSlotForItem(stack)
                            != target.minecraftSlot()
                    || !stack.canEquip(
                            target.minecraftSlot(), player)) {
                return CurrentArmor.invalid();
            }
            boolean binding = hasBindingRestriction(stack);
            Optional<EquipmentCandidate> candidate =
                    captureCandidate(
                            player,
                            stack,
                            target.inventorySlot(),
                            target,
                            binding);
            if (candidate.isEmpty()) {
                return CurrentArmor.invalid();
            }
            EquipmentCandidate current =
                    candidate.orElseThrow();
            if (current.damageable()
                    && current.remainingDurability() == 0) {
                return CurrentArmor.invalid();
            }
            return new CurrentArmor(true, candidate);
        } catch (RuntimeException exception) {
            return CurrentArmor.invalid();
        }
    }

    private static Optional<EquipmentCandidate> captureCandidate(
            BotServerPlayer player,
            ItemStack stack,
            int inventorySlot,
            ArmorTarget target,
            boolean targetBlockedByBinding) {
        if (stack.isEmpty()
                || stack.getCount() != 1
                || !(stack.getItem() instanceof ArmorItem armor)) {
            return Optional.empty();
        }
        try {
            if (player.getEquipmentSlotForItem(stack)
                    != target.minecraftSlot()) {
                return Optional.empty();
            }
            boolean damageable = stack.isDamageableItem();
            int remainingDurability = damageable
                    ? Math.max(
                            0,
                            stack.getMaxDamage()
                                    - stack.getDamageValue())
                    : 0;
            return Optional.of(new EquipmentCandidate(
                    inventorySlot,
                    MinecraftActionSnapshot.item(
                            player, stack),
                    target.kind(),
                    Optional.empty(),
                    0,
                    armor.getDefense(),
                    armor.getToughness(),
                    0.0D,
                    remainingDurability,
                    damageable,
                    stack.canEquip(
                            target.minecraftSlot(), player),
                    targetBlockedByBinding,
                    hasBindingRestriction(stack)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static ArmorTarget targetFor(
            BotServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()
                || stack.getCount() != 1
                || !(stack.getItem() instanceof ArmorItem)) {
            return null;
        }
        try {
            EquipmentSlot slot =
                    player.getEquipmentSlotForItem(stack);
            for (ArmorTarget target : TARGETS) {
                if (target.minecraftSlot() == slot) {
                    return target;
                }
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private static boolean hasBindingRestriction(
            ItemStack stack) {
        return EnchantmentHelper.has(
                stack,
                EnchantmentEffectComponents
                        .PREVENT_ARMOR_CHANGE);
    }

    private static void requireServerThread(
            BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "armor planning requires the authoritative server thread");
        }
    }

    private record ArmorTarget(
            EquipmentSlotKind kind,
            EquipmentSlot minecraftSlot,
            int inventorySlot) {
        private ArmorTarget {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(
                    minecraftSlot, "minecraftSlot");
            if (inventorySlot
                    != ArmorUpgradeSelection
                            .targetInventorySlotFor(kind)) {
                throw new IllegalArgumentException(
                        "armor target inventory mapping is inconsistent");
            }
        }
    }

    private record CurrentArmor(
            boolean valid,
            Optional<EquipmentCandidate> candidate) {
        private CurrentArmor {
            Objects.requireNonNull(candidate, "candidate");
            if (!valid && candidate.isPresent()) {
                throw new IllegalArgumentException(
                        "invalid current armor cannot carry a candidate");
            }
        }

        private static CurrentArmor empty() {
            return new CurrentArmor(true, Optional.empty());
        }

        private static CurrentArmor invalid() {
            return new CurrentArmor(false, Optional.empty());
        }

        private boolean blocksReplacement() {
            return candidate
                    .map(EquipmentCandidate::targetBlockedByBinding)
                    .orElse(false);
        }
    }
}
