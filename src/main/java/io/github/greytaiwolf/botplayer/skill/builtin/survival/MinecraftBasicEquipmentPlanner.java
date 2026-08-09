package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
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
 * 从真实玩家库存冻结工具、P5A 精确主手物品或普通副手的确定性选择。
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
     * 将一项 P5A 编译期认可的精确物品切入当前主手热栏位。
     *
     * <p>该入口刻意不接收任意 {@link ResourceId}：只有
     * {@link ExactMainHandItem} 的五项固定白名单可以进入菜单事务。选择始终冻结源槽的
     * 完整 {@link ItemStackFingerprint}（包括 component digest），因此同 item id 但组件、
     * 耐久或数量不同的堆叠不能在动作开始后替换原先观察到的物品。
     *
     * <p>若所选热栏已经是请求的精确物品，会返回 source 与 target 相同的选择，调用方必须
     * 将其作为无点击的安全完成，而不是从另一个同类堆叠进行多余交换。
     *
     * @param player 权威服务器线程上的 BotPlayer
     * @param requestedItem P5A 的封闭精确物品白名单项
     * @return 可审核的主手选择；未发现请求项或任何快照前提不成立时为空
     */
    public static Optional<ExactMainHandSelection> planExactMainHand(
            BotServerPlayer player, ExactMainHandItem requestedItem) {
        requireServerThread(player);
        Objects.requireNonNull(requestedItem, "requestedItem");

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
            ExactMainHandSelection selection = selectExactMainHand(
                    menuSnapshot, requestedItem).orElse(null);
            if (selection == null) {
                return Optional.empty();
            }
            ItemStackFingerprint actual = MinecraftActionSnapshot.item(
                    player,
                    inventory.getItem(selection.sourceInventorySlot()));
            if (!actual.equals(selection.expectedItem())) {
                return Optional.empty();
            }
            return Optional.of(selection);
        } catch (RuntimeException exception) {
            /*
             * 注册表、data component 或菜单读取故障不能放宽成按显示名/类别的猜测；
             * 主手切换没有副作用，因而保守拒绝本次快照。
             */
            return Optional.empty();
        }
    }

    /**
     * 仅消费不可变快照的精确主手选择。保留为 package-private，以便纯 Java 合同测试覆盖
     * “没有目标 / 已在选中位 / 主背包换入”三个路径，而无需构造 Minecraft 活玩家。
     */
    static Optional<ExactMainHandSelection> selectExactMainHand(
            InventoryMenuSnapshot menuSnapshot,
            ExactMainHandItem requestedItem) {
        Objects.requireNonNull(menuSnapshot, "menuSnapshot");
        Objects.requireNonNull(requestedItem, "requestedItem");
        if (!menuSnapshot.cursor().isEmpty()) {
            return Optional.empty();
        }
        int targetHotbarSlot = menuSnapshot.selectedHotbar();
        ItemStackFingerprint selected = menuSnapshot.itemAt(targetHotbarSlot);
        if (isExactMainHandItem(selected, requestedItem)) {
            return Optional.of(new ExactMainHandSelection(
                    menuSnapshot,
                    requestedItem,
                    targetHotbarSlot,
                    targetHotbarSlot,
                    selected));
        }
        for (int inventorySlot = FIRST_CARRIED_SLOT;
                inventorySlot <= LAST_CARRIED_SLOT;
                inventorySlot++) {
            ItemStackFingerprint candidate = menuSnapshot.itemAt(
                    inventorySlot);
            if (isExactMainHandItem(candidate, requestedItem)) {
                return Optional.of(new ExactMainHandSelection(
                        menuSnapshot,
                        requestedItem,
                        inventorySlot,
                        targetHotbarSlot,
                        candidate));
            }
        }
        return Optional.empty();
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

    private static boolean isExactMainHandItem(
            ItemStackFingerprint fingerprint,
            ExactMainHandItem requestedItem) {
        return !fingerprint.isEmpty()
                && fingerprint.itemId()
                        .filter(requestedItem.itemId()::equals)
                        .isPresent();
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
     * P5A 封闭白名单中的精确主手物品。
     *
     * <p>这里的枚举既是 parser 的唯一真相，也是 planner 的唯一请求类型；不能用
     * {@code ResourceId} 替代它来扩大为任意主手切换能力。
     */
    public enum ExactMainHandItem {
        WOODEN_PICKAXE("minecraft:wooden_pickaxe"),
        STONE_PICKAXE("minecraft:stone_pickaxe"),
        IRON_PICKAXE("minecraft:iron_pickaxe"),
        CRAFTING_TABLE("minecraft:crafting_table"),
        FURNACE("minecraft:furnace");

        private final ResourceId itemId;

        ExactMainHandItem(String itemId) {
            this.itemId = new ResourceId(itemId);
        }

        public ResourceId itemId() {
            return itemId;
        }

        /**
         * 严格解析编译进 DAG 的参数值；未知、非规范或非白名单值一律拒绝。
         */
        public static Optional<ExactMainHandItem> fromItemId(
                String itemId) {
            if (itemId == null) {
                return Optional.empty();
            }
            return switch (itemId) {
                case "minecraft:wooden_pickaxe" ->
                        Optional.of(WOODEN_PICKAXE);
                case "minecraft:stone_pickaxe" ->
                        Optional.of(STONE_PICKAXE);
                case "minecraft:iron_pickaxe" ->
                        Optional.of(IRON_PICKAXE);
                case "minecraft:crafting_table" ->
                        Optional.of(CRAFTING_TABLE);
                case "minecraft:furnace" -> Optional.of(FURNACE);
                default -> Optional.empty();
            };
        }
    }

    /**
     * 精确物品到当前主手选中热栏位的纯值映射。
     */
    public record ExactMainHandSelection(
            InventoryMenuSnapshot menuSnapshot,
            ExactMainHandItem requestedItem,
            int sourceInventorySlot,
            int targetHotbarSlot,
            ItemStackFingerprint expectedItem) {
        public ExactMainHandSelection {
            Objects.requireNonNull(menuSnapshot, "menuSnapshot");
            Objects.requireNonNull(requestedItem, "requestedItem");
            Objects.requireNonNull(expectedItem, "expectedItem");
            if (!isCarriedSlot(sourceInventorySlot)
                    || !PlayerInventoryMenuLayout
                            .isHotbarInventorySlot(targetHotbarSlot)
                    || targetHotbarSlot != menuSnapshot.selectedHotbar()
                    || !menuSnapshot.cursor().isEmpty()
                    || expectedItem.isEmpty()
                    || expectedItem.itemId()
                            .filter(requestedItem.itemId()::equals)
                            .isEmpty()
                    || !menuSnapshot.itemAt(sourceInventorySlot)
                            .equals(expectedItem)) {
                throw new IllegalArgumentException(
                        "exact main-hand selection must bind a whitelisted full source fingerprint");
            }
        }

        /**
         * 源已是所选热栏时没有可安全且必要的菜单点击。
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
