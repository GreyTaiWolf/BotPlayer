package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.ArmorUpgradeSelection;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicArmorPlanner;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner.ExactMainHandItem;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.ToolKind;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSnapshot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplate;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplateBuilder;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 将基础盔甲、指定用途工具、封闭精确主手物品及显式普通副手接到 P5A 的通用菜单事务。
 *
 * <p>这个适配器只在服务器线程读取原版 InventoryMenu，并把完整 46 槽快照冻结进
 * {@link MenuTransactionTemplate}。它不会直接写 {@code Inventory}；真正的变更始终
 * 由 {@code WorldMenuTransaction} 调用原版 {@code clicked()} 完成。旧的
 * {@code InventoryMenuSwap} 路径因此保持其原有的 41 槽/无 crafting 槽不变量。
 */
public final class MinecraftEquipmentSkillNodeHandler
        implements SkillNodeHandler {
    /** 节点可表达的四种 P5A 基础装备工作。 */
    public enum Kind {
        BASIC_ARMOR,
        REQUESTED_TOOL,
        REQUESTED_EXACT_MAIN_HAND,
        REQUESTED_OFFHAND
    }

    private static final int MAXIMUM_ACTION_TICKS = 240;

    private final Kind kind;
    private final Resolver resolver;
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final Thread ownerThread;

    public MinecraftEquipmentSkillNodeHandler(
            Kind kind,
            Resolver resolver,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction, actions, signals);
        ownerThread = Thread.currentThread();
    }

    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        Objects.requireNonNull(context, "context");
        return List.of(new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.CONTAINER,
                        "bot:" + context.botId(),
                        "native_inventory"),
                ReservationMode.EXCLUSIVE));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Prepared prepared = prepare(context).orElse(null);
        if (prepared == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "基础装备前置条件不再成立");
        }
        if (prepared.noOperation()) {
            return SkillNodeDirective.complete(prepared.completionSummary());
        }
        /*
         * 重新在 delegate 中抓取一次快照而不是复用这个临时对象。两次读取都发生在
         * 同一服务器线程，第二次若因模组回调或菜单变化不同，template bind 会失败关闭。
         */
        return actionDelegate.begin(context);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        return actionDelegate.signal(context, signal);
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        actionDelegate.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Prepared prepared = prepare(context).orElse(null);
        if (prepared == null || prepared.noOperation()) {
            return Optional.empty();
        }
        BotServerPlayer player = prepared.player();
        ItemStackFingerprint expectedHeld = MinecraftActionSnapshot.item(
                player, player.getMainHandItem());
        WorldInteractionActionSpec.WorldMenuTransaction transaction =
                new WorldInteractionActionSpec.WorldMenuTransaction(
                        WorldInteractionActionSpec.Hand.MAIN_HAND,
                        Optional.empty(),
                        expectedHeld,
                        prepared.template(),
                        new MenuTransactionLimits(
                                prepared.template().orderedSteps().size(),
                                MAXIMUM_ACTION_TICKS));
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                prepared.operationKey(),
                new WorldInteractionAction(transaction),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_MENU,
                "等待原版背包菜单事务完成",
                (ignored, signal) -> verify(
                        player,
                        prepared.generation(),
                        prepared.template(),
                        signal)));
    }

    private SkillNodeDirective verify(
            BotServerPlayer expectedPlayer,
            long expectedGeneration,
            MenuTransactionTemplate template,
            SkillSignal signal) {
        BotServerPlayer current = resolver.resolve(
                expectedPlayer.getUUID(), expectedGeneration).orElse(null);
        if (current != expectedPlayer) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "装备动作完成时 BotPlayer 代际已经变化");
        }
        MenuSnapshot snapshot = captureNativeInventoryMenu(current)
                .orElse(null);
        if (snapshot == null
                || !template.finalLayout().matches(snapshot)
                || signal.evidence().isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版背包菜单动作后的完整布局无法确认");
        }
        return SkillNodeDirective.complete("原版背包菜单装备事务已确认");
    }

    private Optional<Prepared> prepare(SkillNodeContext context) {
        BotServerPlayer player = resolver.resolve(
                context.botId(), context.botGeneration()).orElse(null);
        if (player == null
                || captureNativeInventoryMenu(player).isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case BASIC_ARMOR -> planArmor(player);
            case REQUESTED_TOOL -> planTool(player, context);
            case REQUESTED_EXACT_MAIN_HAND -> planExactMainHand(player,
                    context);
            case REQUESTED_OFFHAND -> planOffhand(player, context);
        };
    }

    private static Optional<Prepared> planArmor(BotServerPlayer player) {
        ArmorUpgradeSelection choice = MinecraftBasicArmorPlanner.plan(player)
                .orElse(null);
        if (choice == null) {
            return Optional.of(Prepared.noOperation(
                    player, "基础盔甲已经无需升级"));
        }
        return template(player,
                choice.sourceInventorySlot(), choice.targetInventorySlot(),
                "equip-armor");
    }

    private static Optional<Prepared> planTool(
            BotServerPlayer player, SkillNodeContext context) {
        String requested = context.node().parameters()
                .value("tool.kind")
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
        if (requested == null) {
            return Optional.empty();
        }
        ToolKind toolKind;
        try {
            toolKind = ToolKind.valueOf(
                    requested.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        MinecraftBasicEquipmentPlanner.ToolSelection choice =
                MinecraftBasicEquipmentPlanner.planTool(player, toolKind)
                        .orElse(null);
        if (choice == null) {
            return Optional.empty();
        }
        if (!choice.requiresInventorySwap()) {
            return Optional.of(Prepared.noOperation(
                    player, "请求的工具已经位于主手选中栏"));
        }
        return template(player,
                choice.sourceInventorySlot(), choice.targetHotbarSlot(),
                "equip-tool");
    }

    private static Optional<Prepared> planExactMainHand(
            BotServerPlayer player, SkillNodeContext context) {
        ExactMainHandItem requested = parseExactMainHandItem(
                context.node().parameters().value(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER)
                        .orElse(null)).orElse(null);
        if (requested == null) {
            return Optional.empty();
        }
        MinecraftBasicEquipmentPlanner.ExactMainHandSelection choice =
                MinecraftBasicEquipmentPlanner.planExactMainHand(
                        player, requested).orElse(null);
        if (choice == null) {
            return Optional.empty();
        }
        if (!choice.requiresInventorySwap()) {
            return Optional.of(Prepared.noOperation(
                    player, "请求的精确物品已经位于主手选中栏"));
        }
        return template(player,
                choice.sourceInventorySlot(), choice.targetHotbarSlot(),
                exactMainHandOperationKey(requested));
    }

    /**
     * {@link ActionBackedSkillNodeHandler.Operation} 的 operation key 不是 ResourceId：它是
     * 有长度及字符集限制的 idempotency-key 片段。nodeId 已经把每个精确装备节点区分开，
     * 这里仍使用封闭的稳定名称，避免把 {@code minecraft:...} 直接带入受限标识符。
     */
    static String exactMainHandOperationKey(ExactMainHandItem requested) {
        return switch (Objects.requireNonNull(requested, "requested")) {
            case CRAFTING_TABLE -> "equip-exact-crafting-table";
            case WOODEN_PICKAXE -> "equip-exact-wooden-pickaxe";
            case FURNACE -> "equip-exact-furnace";
            case STONE_PICKAXE -> "equip-exact-stone-pickaxe";
            case IRON_PICKAXE -> "equip-exact-iron-pickaxe";
        };
    }

    /**
     * 将 SkillParameters 中未经信任的标量映射回 P5A 封闭白名单；这一步不接受
     * 任意 ResourceId、注册表别名或大小写归一化。
     */
    static Optional<ExactMainHandItem> parseExactMainHandItem(
            Object parameter) {
        return parameter instanceof String itemId
                ? ExactMainHandItem.fromItemId(itemId)
                : Optional.empty();
    }

    private static Optional<Prepared> planOffhand(
            BotServerPlayer player, SkillNodeContext context) {
        Integer source = context.node().parameters()
                .value("source.slot")
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        ItemStackFingerprint expected = MinecraftActionSnapshot.item(
                player, player.getInventory().getItem(source));
        MinecraftBasicEquipmentPlanner.OffhandSelection choice =
                MinecraftBasicEquipmentPlanner.planRequestedOffhand(
                        player, source, expected).orElse(null);
        if (choice == null) {
            return Optional.empty();
        }
        return template(player,
                choice.sourceInventorySlot(), choice.targetInventorySlot(),
                "equip-offhand");
    }

    private static Optional<Prepared> template(
            BotServerPlayer player,
            int sourceInventorySlot,
            int targetInventorySlot,
            String operationKey) {
        MenuSnapshot snapshot = captureNativeInventoryMenu(player)
                .orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        int sourceMenuSlot;
        int targetMenuSlot;
        try {
            sourceMenuSlot = PlayerInventoryMenuLayout
                    .menuSlotForInventorySlot(sourceInventorySlot);
            targetMenuSlot = PlayerInventoryMenuLayout
                    .menuSlotForInventorySlot(targetInventorySlot);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return MenuTransactionTemplateBuilder.moveOrSwap(
                        snapshot, sourceMenuSlot, targetMenuSlot)
                .map(value -> new Prepared(
                        player,
                        player.runtimeHandle().generation(),
                        value,
                        operationKey,
                        false,
                        null));
    }

    /**
     * 捕获原生 2×2 菜单的全部 46 槽，而不是旧 InventoryMenuSnapshot 的 41 个库存槽。
     */
    private static Optional<MenuSnapshot> captureNativeInventoryMenu(
            BotServerPlayer player) {
        try {
            InventoryMenu menu = player.inventoryMenu;
            if (player.containerMenu != menu
                    || !menu.stillValid(player)
                    || menu.slots.size()
                            != MenuFamily.INVENTORY_2X2.slotCount()) {
                return Optional.empty();
            }
            ArrayList<ItemStackFingerprint> slots = new ArrayList<>(
                    menu.slots.size());
            for (Slot slot : menu.slots) {
                slots.add(MinecraftActionSnapshot.item(player,
                        slot.getItem()));
            }
            return Optional.of(new MenuSnapshot(
                    MenuFamily.INVENTORY_2X2,
                    menu.containerId,
                    menu.getStateId(),
                    MinecraftActionSnapshot.item(player, menu.getCarried()),
                    slots));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "equipment skill node handler requires server thread");
        }
    }

    /** 生命周期只暴露 generation 绑定的 body 解析，不把活对象保存进 runtime DTO。 */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    private record Prepared(
            BotServerPlayer player,
            long generation,
            MenuTransactionTemplate template,
            String operationKey,
            boolean noOperation,
            String completionSummary) {
        private Prepared {
            Objects.requireNonNull(player, "player");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "equipment generation must be positive");
            }
            if (!noOperation) {
                Objects.requireNonNull(template, "template");
                Objects.requireNonNull(operationKey, "operationKey");
                if (completionSummary != null) {
                    throw new IllegalArgumentException(
                            "mutating equipment action cannot carry completion summary");
                }
            } else {
                Objects.requireNonNull(completionSummary, "completionSummary");
            }
        }

        private static Prepared noOperation(
                BotServerPlayer player, String completionSummary) {
            return new Prepared(
                    player,
                    player.runtimeHandle().generation(),
                    null,
                    null,
                    true,
                    completionSummary);
        }
    }
}
