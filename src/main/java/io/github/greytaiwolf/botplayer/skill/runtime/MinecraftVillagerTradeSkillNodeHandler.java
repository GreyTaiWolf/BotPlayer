package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec.WorldVillagerTrade.MerchantOfferState;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VillagerTradeInventoryConservation;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VanillaVillagerTrade;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * 一个极窄的原版村民交易 skill 节点。
 *
 * <p>此 handler 从不写 {@link Villager}、offer 或玩家 inventory。它只在服务器线程冻结一笔
 * 当前可用的单支付 offer，再将 {@link WorldInteractionActionSpec.WorldVillagerTrade} 提交给
 * action backend。backend 通过真实实体 interaction、精确 {@code MerchantMenu} 和原版
 * {@code clicked()} 完成支付/领取；本类只负责参数收紧、实体/背包 reservation 和回执重验。
 */
public final class MinecraftVillagerTradeSkillNodeHandler
        implements SkillNodeHandler {
    /** Compatibility aliases for callers that build the fixed builtin schema. */
    public static final String VILLAGER_ID_PARAMETER =
            VanillaVillagerTrade.VILLAGER_ID_PARAMETER;
    public static final String OFFER_INDEX_PARAMETER =
            VanillaVillagerTrade.OFFER_INDEX_PARAMETER;
    public static final String SOURCE_INVENTORY_SLOT_PARAMETER =
            VanillaVillagerTrade.SOURCE_INVENTORY_SLOT_PARAMETER;
    public static final String OUTPUT_INVENTORY_SLOT_PARAMETER =
            VanillaVillagerTrade.OUTPUT_INVENTORY_SLOT_PARAMETER;

    public static final int MAXIMUM_ACTION_TICKS = 240;
    private static final String ENTITY_RESERVATION_SCOPE =
            "minecraft.villager_trade";
    private static final String INVENTORY_RESERVATION_SCOPE =
            "minecraft.villager_trade.inventory";

    private final Resolver resolver;
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final Thread ownerThread;

    public MinecraftVillagerTradeSkillNodeHandler(
            Resolver resolver,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction,
                Objects.requireNonNull(actions, "actions"),
                Objects.requireNonNull(signals, "signals"));
        ownerThread = Thread.currentThread();
    }

    /**
     * 村民实体和同一个 Bot 的原生玩家背包都独占：前者避免并发 offer/uses 变化，后者让唯一
     * output 格约束在整个 action 中保持可审计。参数不能解析时不申请猜测性 reservation。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
        SkillNodeContext required = Objects.requireNonNull(context, "context");
        if (!isExactTradeNode(required)) {
            return List.of();
        }
        TradeRequest request = parseParameters(required.node().parameters())
                .orElse(null);
        if (request == null) {
            return List.of();
        }
        return List.of(
                new ReservationRequest(
                        new ReservationKey(
                                ReservationKey.Kind.ENTITY,
                                ENTITY_RESERVATION_SCOPE,
                                request.villagerId().toString()),
                        ReservationMode.EXCLUSIVE),
                new ReservationRequest(
                        new ReservationKey(
                                ReservationKey.Kind.CONTAINER,
                                INVENTORY_RESERVATION_SCOPE,
                                "bot:" + context.botId()),
                        ReservationMode.EXCLUSIVE));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        SkillNodeContext required = Objects.requireNonNull(context, "context");
        if (!isExactTradeNode(required)
                || parseParameters(required.node().parameters()).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "村民交易节点的标识、版本或参数不符合精确 schema");
        }
        if (prepare(required).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "村民、offer、等级、背包或原生菜单前置条件不成立");
        }
        /*
         * ActionBacked 在实际提交前会重新 prepare 一次。两个读取都在服务端主线程；任一
         * offer 价格、库存布局或目标实体漂移都会产生新的拒绝，而不是复用第一次对象。
         */
        return actionDelegate.begin(required);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        SkillNodeContext required = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        if (!isExactTradeNode(required)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "村民交易 handler 收到了不同的技能标识或版本");
        }
        return actionDelegate.signal(required, signal);
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        actionDelegate.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Prepared prepared = prepare(context).orElse(null);
        if (prepared == null) {
            return Optional.empty();
        }
        WorldInteractionActionSpec.WorldVillagerTrade action =
                tradeAction(prepared);
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "vanilla-villager-trade",
                new WorldInteractionAction(action),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_MENU,
                "等待原版村民交易完成",
                (signalContext, signal) -> verify(
                        prepared, signalContext, signal)));
    }

    private Optional<Prepared> prepare(SkillNodeContext context) {
        try {
            TradeRequest request = parseParameters(
                    context.node().parameters()).orElse(null);
            if (request == null) {
                return Optional.empty();
            }
            BotServerPlayer player = resolver.resolve(
                    context.botId(), context.botGeneration()).orElse(null);
            if (player == null
                    || player.runtimeHandle().generation()
                            != context.botGeneration()
                    || !hasNativeEmptyInventoryMenu(player)
                    || !MinecraftActionSnapshot.item(
                                    player, player.getMainHandItem())
                            .isEmpty()) {
                return Optional.empty();
            }
            Entity raw = player.serverLevel().getEntity(request.villagerId());
            if (raw == null
                    || raw.isRemoved()
                    || raw.getClass() != Villager.class
                    || !player.canInteractWithEntity(raw, 1.0D)
                    || !player.hasLineOfSight(raw)) {
                return Optional.empty();
            }
            Villager villager = (Villager) raw;
            if (villager.getTradingPlayer() != null) {
                return Optional.empty();
            }
            MerchantOffer offer = merchantOffer(villager, request.offerIndex())
                    .orElse(null);
            if (offer == null || offer.isOutOfStock()) {
                return Optional.empty();
            }
            ItemStackFingerprint cost = MinecraftActionSnapshot.item(
                    player, offer.getCostA());
            ItemStackFingerprint secondCost = MinecraftActionSnapshot.item(
                    player, offer.getCostB());
            ItemStackFingerprint result = MinecraftActionSnapshot.item(
                    player, offer.getResult());
            VillagerData villagerData = villager.getVillagerData();
            int villagerLevel = villagerData.getLevel();
            int villagerXp = villager.getVillagerXp();
            MerchantOfferState offerState = merchantOfferState(
                    player, offer).orElse(null);
            if (offerState == null) {
                return Optional.empty();
            }
            int villagerXpAfter = Math.addExact(
                    villagerXp, offerState.rewardsExperience()
                            ? offerState.xp() : 0);
            if (cost.isEmpty()
                    || cost.count() > WorldInteractionActionSpec
                            .WorldVillagerTrade.MAXIMUM_COST_COUNT
                    || !secondCost.isEmpty()
                    || result.isEmpty()
                    || cost.sameItemAndComponents(result)
                    || villagerLevel < 1
                    || villagerLevel > 5
                    || villagerXp < 0
                    || wouldChangeVillagerLevel(
                            villagerLevel, villagerXp, villagerXpAfter)
                    || offer.getUses() < 0
                    || offer.getMaxUses() < 1
                    || offer.getUses() >= offer.getMaxUses()) {
                return Optional.empty();
            }
            ItemStackFingerprint source = MinecraftActionSnapshot.item(
                    player,
                    player.getInventory().getItem(
                            request.sourceInventorySlot()));
            if (!source.sameItemAndComponents(cost)
                    || source.count() <= cost.count()
                    || !strictPlayerStorageLayout(
                            player, request, result, source)) {
                return Optional.empty();
            }
            return Optional.of(new Prepared(
                    player,
                    context.botGeneration(),
                    request,
                    MinecraftActionSnapshot.entity(player, villager),
                    villager,
                    offer,
                    source,
                    cost,
                    result,
                    villagerData,
                    villagerLevel,
                    villagerXp,
                    villagerXpAfter,
                    offer.getUses(),
                    offer.getMaxUses(),
                    offerState,
                    MinecraftActionSnapshot.inventoryContents(player),
                    MinecraftActionSnapshot.inventoryMenu(player)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** A pure construction seam for schema/action contract tests. */
    static WorldInteractionActionSpec.WorldVillagerTrade tradeAction(
            Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return new WorldInteractionActionSpec.WorldVillagerTrade(
                WorldInteractionActionSpec.Hand.MAIN_HAND,
                prepared.target(),
                prepared.request().offerIndex(),
                prepared.request().sourceInventorySlot(),
                prepared.request().outputInventorySlot(),
                prepared.source(),
                prepared.cost(),
                prepared.result(),
                prepared.offerState(),
                prepared.villagerLevel(),
                prepared.villagerXpBefore(),
                prepared.usesBefore(),
                prepared.maxUses(),
                new MenuTransactionLimits(
                        prepared.cost().count() + 3,
                        MAXIMUM_ACTION_TICKS));
    }

    private SkillNodeDirective verify(
            Prepared prepared,
            SkillNodeContext context,
            SkillSignal signal) {
        TradeRequest currentRequest = parseParameters(
                context.node().parameters()).orElse(null);
        if (!prepared.request().equals(currentRequest)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "村民交易回执时节点参数已改变");
        }
        BotServerPlayer current = resolver.resolve(
                context.botId(), context.botGeneration()).orElse(null);
        if (current != prepared.player()
                || current == null
                || current.runtimeHandle().generation()
                        != prepared.generation()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "村民交易完成时 BotPlayer 代际已变化");
        }
        if (signal.evidence().isEmpty()
                || !hasNativeEmptyInventoryMenu(current)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "村民交易未以空 cursor 的原生背包状态关闭");
        }
        Entity raw = current.serverLevel().getEntity(
                prepared.request().villagerId());
        if (raw != prepared.villager()
                || raw == null
                || raw.isRemoved()
                || raw.getClass() != Villager.class) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.TARGET_GONE,
                    "村民交易完成后目标实体无法确认");
        }
        Villager villager = (Villager) raw;
        MerchantOffer offer = merchantOffer(
                villager, prepared.request().offerIndex()).orElse(null);
        if (offer != prepared.offer()
                || offer == null
                || villager.getTradingPlayer() != null
                || villager.getVillagerData() != prepared.villagerData()
                || villager.getVillagerData().getLevel()
                        != prepared.villagerLevel()
                || villager.getVillagerXp() != prepared.villagerXpAfter()
                || offer.getMaxUses() != prepared.maxUses()
                || offer.getUses() != prepared.usesBefore() + 1
                || !merchantOfferState(current, offer)
                        .filter(prepared.offerState()::equals)
                        .isPresent()
                || !MinecraftActionSnapshot.item(current, offer.getCostA())
                        .equals(prepared.cost())
                || !MinecraftActionSnapshot.item(current, offer.getCostB())
                        .isEmpty()
                || !MinecraftActionSnapshot.item(current, offer.getResult())
                        .equals(prepared.result())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "村民 offer、价格、uses 或等级未保持精确合同");
        }
        InventoryContentsSnapshot after =
                MinecraftActionSnapshot.inventoryContents(current);
        if (!VillagerTradeInventoryConservation.matchesCompletedTrade(
                prepared.inventoryBefore(), after,
                prepared.cost(), prepared.result())
                || !completedInventoryLayoutMatches(current, prepared)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "村民交易后的玩家库存没有精确结算为一笔 offer");
        }
        return SkillNodeDirective.complete("原版村民交易已确认");
    }

    private static boolean wouldChangeVillagerLevel(
            int villagerLevel, int villagerXpBefore, int villagerXpAfter) {
        try {
            if (!VillagerData.canLevelUp(villagerLevel)) {
                return false;
            }
            int threshold = VillagerData.getMinXpPerLevel(villagerLevel + 1);
            return villagerXpBefore >= threshold
                    || villagerXpAfter >= threshold;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static boolean hasNativeEmptyInventoryMenu(BotServerPlayer player) {
        try {
            return player.containerMenu == player.inventoryMenu
                    && player.inventoryMenu.getClass() == InventoryMenu.class
                    && player.inventoryMenu.stillValid(player)
                    && player.inventoryMenu.slots.size()
                            == MenuFamily.INVENTORY_2X2.slotCount()
                    && player.inventoryMenu.getCarried().isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Optional<MerchantOffer> merchantOffer(
            Villager villager, int offerIndex) {
        if (offerIndex < 0 || offerIndex >= villager.getOffers().size()) {
            return Optional.empty();
        }
        MerchantOffer offer = villager.getOffers().get(offerIndex);
        return offer == null || offer.getClass() != MerchantOffer.class
                ? Optional.empty() : Optional.of(offer);
    }

    private static Optional<MerchantOfferState> merchantOfferState(
            BotServerPlayer player, MerchantOffer offer) {
        try {
            return Optional.of(new MerchantOfferState(
                    MinecraftActionSnapshot.item(player, offer.getBaseCostA()),
                    offer.getDemand(),
                    offer.getSpecialPriceDiff(),
                    Float.floatToIntBits(offer.getPriceMultiplier()),
                    offer.getXp(),
                    offer.shouldRewardExp()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** The one empty output slot and no result-identity merge targets make QUICK_MOVE deterministic. */
    private static boolean strictPlayerStorageLayout(
            BotServerPlayer player,
            TradeRequest request,
            ItemStackFingerprint result,
            ItemStackFingerprint source) {
        try {
            for (int slot = WorldInteractionActionSpec.WorldVillagerTrade
                    .FIRST_PLAYER_INVENTORY_SLOT;
                    slot <= WorldInteractionActionSpec.WorldVillagerTrade
                            .LAST_PLAYER_INVENTORY_SLOT;
                    slot++) {
                ItemStackFingerprint current = MinecraftActionSnapshot.item(
                        player, player.getInventory().getItem(slot));
                if (slot == request.sourceInventorySlot()) {
                    if (!current.equals(source)) {
                        return false;
                    }
                } else if (slot == request.outputInventorySlot()) {
                    if (!current.isEmpty()) {
                        return false;
                    }
                } else if (current.isEmpty()
                        || current.sameItemAndComponents(result)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean completedInventoryLayoutMatches(
            BotServerPlayer player, Prepared prepared) {
        try {
            InventoryMenuSnapshot after = MinecraftActionSnapshot.inventoryMenu(
                    player);
            InventoryMenuSnapshot before = prepared.inventoryMenuBefore();
            if (after.containerId() != before.containerId()
                    || after.selectedHotbar() != before.selectedHotbar()
                    || !after.cursor().isEmpty()) {
                return false;
            }
            for (int slot = 0; slot < after.inventorySlots().size(); slot++) {
                ItemStackFingerprint expected = before.itemAt(slot);
                if (slot == prepared.request().sourceInventorySlot()) {
                    expected = new ItemStackFingerprint(
                            prepared.source().itemId(),
                            prepared.source().count() - prepared.cost().count(),
                            prepared.source().damage(),
                            prepared.source().componentsDigest());
                } else if (slot == prepared.request().outputInventorySlot()) {
                    expected = prepared.result();
                }
                if (!after.itemAt(slot).equals(expected)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Only four exact scalar parameters are accepted. UUID text must already be lower-case canonical
     * form so a descriptor cannot alias the same entity under multiple reservation subjects.
     */
    public static Optional<TradeRequest> parseParameters(
            SkillParameters parameters) {
        return VanillaVillagerTrade.parse(parameters).map(request ->
                new TradeRequest(
                        request.villagerId(),
                        request.offerIndex(),
                        request.sourceInventorySlot(),
                        request.outputInventorySlot()));
    }

    private static boolean isExactTradeNode(SkillNodeContext context) {
        return VanillaVillagerTrade.ID.equals(context.node().skillId())
                && VanillaVillagerTrade.VERSION.equals(
                        context.node().skillVersion());
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "villager trade handler requires the server thread");
        }
    }

    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    public record TradeRequest(
            UUID villagerId,
            int offerIndex,
            int sourceInventorySlot,
            int outputInventorySlot) {
        public TradeRequest {
            Objects.requireNonNull(villagerId, "villagerId");
            if (villagerId.getMostSignificantBits() == 0L
                    && villagerId.getLeastSignificantBits() == 0L
                    || offerIndex < 0
                    || offerIndex > VanillaVillagerTrade.MAXIMUM_OFFER_INDEX
                    || sourceInventorySlot < WorldInteractionActionSpec
                            .WorldVillagerTrade.FIRST_PLAYER_INVENTORY_SLOT
                    || sourceInventorySlot > WorldInteractionActionSpec
                            .WorldVillagerTrade.LAST_PLAYER_INVENTORY_SLOT
                    || outputInventorySlot < WorldInteractionActionSpec
                            .WorldVillagerTrade.FIRST_PLAYER_INVENTORY_SLOT
                    || outputInventorySlot > WorldInteractionActionSpec
                            .WorldVillagerTrade.LAST_PLAYER_INVENTORY_SLOT
                    || sourceInventorySlot == outputInventorySlot) {
                throw new IllegalArgumentException(
                        "villager trade request fields are outside the narrow contract");
            }
        }
    }

    private record Prepared(
            BotServerPlayer player,
            long generation,
            TradeRequest request,
            EntityTargetFingerprint target,
            Villager villager,
            MerchantOffer offer,
            ItemStackFingerprint source,
            ItemStackFingerprint cost,
            ItemStackFingerprint result,
            VillagerData villagerData,
            int villagerLevel,
            int villagerXpBefore,
            int villagerXpAfter,
            int usesBefore,
            int maxUses,
            MerchantOfferState offerState,
            InventoryContentsSnapshot inventoryBefore,
            InventoryMenuSnapshot inventoryMenuBefore) {
        private Prepared {
            Objects.requireNonNull(player, "player");
            if (generation <= 0L
                    || villagerLevel < 1 || villagerLevel > 5
                    || villagerXpBefore < 0 || villagerXpAfter < 0
                    || usesBefore < 0 || maxUses < 1
                    || usesBefore >= maxUses) {
                throw new IllegalArgumentException(
                        "villager trade prepared state is invalid");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(villager, "villager");
            Objects.requireNonNull(offer, "offer");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(cost, "cost");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(villagerData, "villagerData");
            Objects.requireNonNull(offerState, "offerState");
            Objects.requireNonNull(inventoryBefore, "inventoryBefore");
            Objects.requireNonNull(inventoryMenuBefore, "inventoryMenuBefore");
        }
    }

}
