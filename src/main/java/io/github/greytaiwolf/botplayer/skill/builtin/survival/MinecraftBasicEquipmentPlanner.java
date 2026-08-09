package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 从真实玩家库存冻结工具或普通副手的确定性选择。
 *
 * <p>本类只读取原版玩家快照，不会改变选中栏、背包、装备槽或菜单。工具仅接受
 * {@link TieredItem} 的精确原版类别；副手必须由调用方同时给出源库存槽和完整物品
 * 指纹，不含任何自动持盾或战斗推断。
 */
public final class MinecraftBasicEquipmentPlanner {
    private static final int FIRST_CARRIED_SLOT =
            ArmorUpgradeSelection.FIRST_CARRIED_SLOT;
    private static final int LAST_CARRIED_SLOT =
            ArmorUpgradeSelection.LAST_CARRIED_SLOT;

    private MinecraftBasicEquipmentPlanner() {
    }

    /**
     * 选择指定用途的最佳原版工具，并冻结其到当前主手热栏位的映射。
     *
     * <p>即使候选已经位于当前选中栏，也会返回一份选择；调用方可通过
     * {@link ToolSelection#requiresInventorySwap()} 区分无需点击的情况。
     *
     * @param player 权威服务器线程上的 BotPlayer
     * @param requestedKind 调用方声明的工具用途
     * @return 可审核的主手选择；发现异常、未知物品或没有合格工具时为空
     */
    public static Optional<ToolSelection> planTool(
            BotServerPlayer player, ToolKind requestedKind) {
        requireServerThread(player);
        Objects.requireNonNull(requestedKind, "requestedKind");

        try {
            Inventory inventory = player.getInventory();
            InventoryMenuSnapshot menuSnapshot =
                    MinecraftActionSnapshot.inventoryMenu(player);
            int targetHotbarSlot = menuSnapshot.selectedHotbar();
            if (!PlayerInventoryMenuLayout
                    .isHotbarInventorySlot(targetHotbarSlot)
                    || targetHotbarSlot != inventory.selected
                    || !menuSnapshot.cursor().isEmpty()) {
                return Optional.empty();
            }

            List<EquipmentCandidate> candidates = new ArrayList<>();
            for (int inventorySlot = FIRST_CARRIED_SLOT;
                    inventorySlot <= LAST_CARRIED_SLOT;
                    inventorySlot++) {
                captureToolCandidate(
                        player,
                        inventory.getItem(inventorySlot),
                        inventorySlot,
                        menuSnapshot.itemAt(inventorySlot))
                        .ifPresent(candidates::add);
            }

            return BasicEquipmentPolicy
                    .chooseTool(requestedKind, candidates)
                    .map(candidate -> new ToolSelection(
                            menuSnapshot,
                            requestedKind,
                            candidate.inventorySlot(),
                            targetHotbarSlot,
                            candidate));
        } catch (RuntimeException exception) {
            /*
             * 模组 Item、data component 或注册表查询可能在读取期抛异常。规划器
             * 没有执行副作用，因此保守地拒绝本次快照比把不完整的证据交给菜单层安全。
             */
            return Optional.empty();
        }
    }

    /**
     * 冻结一次显式普通副手请求。
     *
     * <p>请求只能指向可携带槽 0..35，且传入指纹必须逐字段等于当前物品。盾牌
     * 明确留给 P5C，本方法不会把它当作普通副手自动化的一部分。
     *
     * @param player 权威服务器线程上的 BotPlayer
     * @param requestedInventorySlot 调用方明确声明的源库存槽
     * @param expectedItem 调用方在本次动作前已绑定的完整物品指纹
     * @return 到库存副手槽 40 的可审核映射；任一前提不成立时为空
     */
    public static Optional<OffhandSelection> planRequestedOffhand(
            BotServerPlayer player,
            int requestedInventorySlot,
            ItemStackFingerprint expectedItem) {
        requireServerThread(player);
        Objects.requireNonNull(expectedItem, "expectedItem");
        if (!isCarriedSlot(requestedInventorySlot) || expectedItem.isEmpty()) {
            return Optional.empty();
        }

        try {
            Inventory inventory = player.getInventory();
            InventoryMenuSnapshot menuSnapshot =
                    MinecraftActionSnapshot.inventoryMenu(player);
            if (!menuSnapshot.cursor().isEmpty()) {
                return Optional.empty();
            }
            ItemStack source = inventory.getItem(requestedInventorySlot);
            if (!isSupportedOrdinaryOffhand(source)
                    || hasBindingRestriction(source)
                    || hasBindingRestriction(player.getOffhandItem())) {
                return Optional.empty();
            }

            ItemStackFingerprint actual =
                    MinecraftActionSnapshot.item(player, source);
            if (!actual.equals(expectedItem)
                    || !actual.equals(menuSnapshot.itemAt(
                            requestedInventorySlot))) {
                return Optional.empty();
            }

            EquipmentCandidate candidate = new EquipmentCandidate(
                    requestedInventorySlot,
                    actual,
                    EquipmentSlotKind.OFFHAND,
                    Optional.empty(),
                    0,
                    0.0D,
                    0.0D,
                    0.0D,
                    remainingDurability(source),
                    source.isDamageableItem(),
                    true,
                    false,
                    false);
            return BasicEquipmentPolicy.chooseRequestedOffhand(
                            requestedInventorySlot,
                            expectedItem,
                            List.of(candidate))
                    .map(chosen -> new OffhandSelection(
                            menuSnapshot,
                            requestedInventorySlot,
                            PlayerInventoryMenuLayout
                                    .OFFHAND_INVENTORY_SLOT,
                            expectedItem,
                            chosen));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<EquipmentCandidate> captureToolCandidate(
            BotServerPlayer player,
            ItemStack stack,
            int inventorySlot,
            ItemStackFingerprint expectedSnapshot) {
        Objects.requireNonNull(expectedSnapshot, "expectedSnapshot");
        if (!isCarriedSlot(inventorySlot)
                || stack.isEmpty()
                || stack.getCount() != 1
                || hasBindingRestriction(stack)) {
            return Optional.empty();
        }

        try {
            Optional<ToolKind> toolKind = vanillaToolKind(stack.getItem());
            if (toolKind.isEmpty()
                    || !(stack.getItem() instanceof TieredItem tiered)
                    || !stack.isDamageableItem()) {
                return Optional.empty();
            }
            int tier = vanillaTierRank(tiered.getTier());
            if (tier < 0) {
                return Optional.empty();
            }
            int remainingDurability = remainingDurability(stack);
            if (remainingDurability <= 0) {
                return Optional.empty();
            }
            double efficiency = tiered.getTier().getSpeed();
            if (!Double.isFinite(efficiency) || efficiency < 0.0D) {
                return Optional.empty();
            }
            ItemStackFingerprint actual =
                    MinecraftActionSnapshot.item(player, stack);
            if (!actual.equals(expectedSnapshot)) {
                return Optional.empty();
            }
            return Optional.of(new EquipmentCandidate(
                    inventorySlot,
                    actual,
                    EquipmentSlotKind.MAIN_HAND_TOOL,
                    toolKind,
                    tier,
                    0.0D,
                    0.0D,
                    efficiency,
                    remainingDurability,
                    true,
                    /*
                     * 主手是选中的热栏位，不是 NeoForge canEquip 所定义的盔甲槽；
                     * 原版背包菜单可以把合格工具放入该热栏位。
                     */
                    true,
                    false,
                    false));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 只接受 Mojang 原版注册项和精确的原版工具类别；不能靠注册名或显示名猜测
     * 用途。
     */
    static Optional<ToolKind> vanillaToolKind(Item item) {
        Objects.requireNonNull(item, "item");
        try {
            if (!"minecraft".equals(BuiltInRegistries.ITEM
                    .getKey(item).getNamespace())) {
                return Optional.empty();
            }
            return vanillaToolKindForClass(item.getClass());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 精确原版类别到用途的固定映射。此方法不读取注册表，便于纯单元测试审计。
     */
    static Optional<ToolKind> vanillaToolKindForClass(
            Class<? extends Item> itemClass) {
        Objects.requireNonNull(itemClass, "itemClass");
        if (itemClass == PickaxeItem.class) {
            return Optional.of(ToolKind.PICKAXE);
        }
        if (itemClass == AxeItem.class) {
            return Optional.of(ToolKind.AXE);
        }
        if (itemClass == ShovelItem.class) {
            return Optional.of(ToolKind.SHOVEL);
        }
        if (itemClass == HoeItem.class) {
            return Optional.of(ToolKind.HOE);
        }
        if (itemClass == SwordItem.class) {
            return Optional.of(ToolKind.WEAPON);
        }
        return Optional.empty();
    }

    /**
     * 原版金工具仅有木级采掘能力；速度仍由策略作为同 tier 的次级排序条件。
     */
    static int vanillaTierRank(Tier tier) {
        Objects.requireNonNull(tier, "tier");
        if (tier == Tiers.WOOD || tier == Tiers.GOLD) {
            return 0;
        }
        if (tier == Tiers.STONE) {
            return 1;
        }
        if (tier == Tiers.IRON) {
            return 2;
        }
        if (tier == Tiers.DIAMOND) {
            return 3;
        }
        if (tier == Tiers.NETHERITE) {
            return 4;
        }
        return -1;
    }

    private static boolean isSupportedOrdinaryOffhand(ItemStack stack) {
        if (stack.isEmpty()
                || stack.getCount() <= 0
                || stack.getItem() instanceof ShieldItem) {
            return false;
        }
        try {
            return "minecraft".equals(BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getNamespace());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int remainingDurability(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return 0;
        }
        return Math.max(0,
                stack.getMaxDamage() - stack.getDamageValue());
    }

    private static boolean hasBindingRestriction(ItemStack stack) {
        return EnchantmentHelper.has(stack,
                EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private static boolean isCarriedSlot(int inventorySlot) {
        return inventorySlot >= FIRST_CARRIED_SLOT
                && inventorySlot <= LAST_CARRIED_SLOT;
    }

    private static void requireServerThread(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "equipment planning requires the authoritative server thread");
        }
    }

    /**
     * 主手工具选择及其到当前热栏选中位的纯值映射。
     */
    public record ToolSelection(
            InventoryMenuSnapshot menuSnapshot,
            ToolKind requestedKind,
            int sourceInventorySlot,
            int targetHotbarSlot,
            EquipmentCandidate candidate) {
        public ToolSelection {
            Objects.requireNonNull(menuSnapshot, "menuSnapshot");
            Objects.requireNonNull(requestedKind, "requestedKind");
            Objects.requireNonNull(candidate, "candidate");
            if (!isCarriedSlot(sourceInventorySlot)
                    || !PlayerInventoryMenuLayout
                            .isHotbarInventorySlot(targetHotbarSlot)
                    || targetHotbarSlot
                            != menuSnapshot.selectedHotbar()
                    || !menuSnapshot.cursor().isEmpty()) {
                throw new IllegalArgumentException(
                        "tool selection slots must map carried inventory to hotbar");
            }
            if (candidate.inventorySlot() != sourceInventorySlot
                    || candidate.targetSlot()
                            != EquipmentSlotKind.MAIN_HAND_TOOL
                    || candidate.toolKind()
                            .filter(kind -> kind == requestedKind)
                            .isEmpty()
                    || candidate.itemFingerprint().count() != 1
                    || !menuSnapshot.itemAt(sourceInventorySlot)
                            .equals(candidate.itemFingerprint())
                    || !isEligible(candidate)) {
                throw new IllegalArgumentException(
                        "tool selection candidate is not eligible");
            }
        }

        /**
         * 候选已经在主手选中位时不需要菜单 SWAP。
         */
        public boolean requiresInventorySwap() {
            return sourceInventorySlot != targetHotbarSlot;
        }
    }

    /**
     * 显式普通副手请求及其到原版库存槽 40 的纯值映射。
     */
    public record OffhandSelection(
            InventoryMenuSnapshot menuSnapshot,
            int sourceInventorySlot,
            int targetInventorySlot,
            ItemStackFingerprint expectedItem,
            EquipmentCandidate candidate) {
        public OffhandSelection {
            Objects.requireNonNull(menuSnapshot, "menuSnapshot");
            Objects.requireNonNull(expectedItem, "expectedItem");
            Objects.requireNonNull(candidate, "candidate");
            if (!isCarriedSlot(sourceInventorySlot)
                    || targetInventorySlot
                            != PlayerInventoryMenuLayout
                                    .OFFHAND_INVENTORY_SLOT
                    || !menuSnapshot.cursor().isEmpty()
                    || expectedItem.isEmpty()) {
                throw new IllegalArgumentException(
                        "offhand selection must use an explicit carried source");
            }
            if (candidate.inventorySlot() != sourceInventorySlot
                    || candidate.targetSlot() != EquipmentSlotKind.OFFHAND
                    || !candidate.itemFingerprint().equals(expectedItem)
                    || !menuSnapshot.itemAt(sourceInventorySlot)
                            .equals(expectedItem)
                    || !isEligible(candidate)) {
                throw new IllegalArgumentException(
                        "offhand selection candidate is not eligible");
            }
        }
    }

    private static boolean isEligible(EquipmentCandidate candidate) {
        return candidate.canEquip()
                && !candidate.targetBlockedByBinding()
                && !candidate.candidateBindsOnEquip()
                && (!candidate.damageable()
                        || candidate.remainingDurability() > 0)
                && Double.isFinite(candidate.armorPoints())
                && candidate.armorPoints() >= 0.0D
                && Double.isFinite(candidate.armorToughness())
                && candidate.armorToughness() >= 0.0D
                && Double.isFinite(candidate.efficiency())
                && candidate.efficiency() >= 0.0D;
    }
}
