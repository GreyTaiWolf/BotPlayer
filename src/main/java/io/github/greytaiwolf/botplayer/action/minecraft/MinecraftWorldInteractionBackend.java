package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReceipt;
import io.github.greytaiwolf.botplayer.action.ActionCleanupRequest;
import io.github.greytaiwolf.botplayer.action.ActionCleanupStatus;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ResourceIdEvidence;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityLocalHit;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupFence;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupPolicy;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.ActiveEffectObservation;
import io.github.greytaiwolf.botplayer.action.interaction.UseItemPreconditions;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec.WorldVillagerTrade.MerchantOfferState;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuClickStep;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuCleanupSession;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuPrefixAuthority;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSettlementDecision;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSettlementCursor;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSettlementPolicy;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuTransaction;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuTransactionState;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.interaction.menu.FurnaceKind;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VillagerTradeInventoryConservation;
import io.github.greytaiwolf.botplayer.skill.menu.CraftingPreviewResolver;
import io.github.greytaiwolf.botplayer.skill.menu.MenuClick;
import io.github.greytaiwolf.botplayer.skill.menu.MenuClickDispatchBoundary;
import io.github.greytaiwolf.botplayer.skill.menu.MenuClickType;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSnapshot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransaction;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionFailure;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionPlan;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionState;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplate;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplateBuilder;
import io.github.greytaiwolf.botplayer.skill.menu.MerchantTradeMenuPlanBuilder;
import io.github.greytaiwolf.botplayer.skill.menu.P5ACraftingMenuPlanBuilder;
import io.github.greytaiwolf.botplayer.skill.menu.P5AFurnaceMenuPlanBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 通过真人玩家使用的服务端入口执行 P2-C、P5A 与受限 P5B 世界交互。
 *
 * <p>全部状态均为短生命周期、generation 隔离并限制在服务器主线程。第一次副作用前先登记
 * 状态，使 packet handler 重入生命周期逻辑时，运行时清理仍能撤销持续使用或方块破坏。
 */
final class MinecraftWorldInteractionBackend implements ActionBackend {
    private static final int MAX_COMPLETED_MENU_CLEANUPS = 256;
    private static final int VERIFY_BREAK_BASE_EVIDENCE_ITEMS = 3;
    private static final double MIN_AIM_VECTOR_LENGTH_SQUARED = 1.0E-12D;
    private static final double AIM_AND_PLACE_LOOK_TOLERANCE_DEGREES = 0.5D;
    private static final long FURNACE_POLL_INTERVAL_TICKS = 20L;
    private static final Set<String> VANILLA_DIRECTION_NAMES = Set.of(
            "down", "up", "north", "south", "west", "east");
    private static final Set<String> HORIZONTAL_DIRECTION_NAMES = Set.of(
            "north", "south", "west", "east");
    private static final Set<String> BOOLEAN_PROPERTY_VALUES = Set.of(
            "false", "true");
    private static final Set<String> CHEST_PROPERTY_NAMES = Set.of(
            "facing", "type", "waterlogged");
    private static final Set<String> BARREL_PROPERTY_NAMES = Set.of(
            "facing", "open");
    private static final Set<String> ENDER_CHEST_PROPERTY_NAMES = Set.of(
            "facing", "waterlogged");
    private static final Set<String> SHULKER_PROPERTY_NAMES = Set.of("facing");
    private static final Set<String> VANILLA_SHULKER_IDS = Set.of(
            "minecraft:shulker_box",
            "minecraft:white_shulker_box",
            "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",
            "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",
            "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",
            "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box",
            "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",
            "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",
            "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",
            "minecraft:black_shulker_box");
    private final BotLifecycleManager lifecycleManager;
    private final Map<ActionKey, InteractionState> active = new LinkedHashMap<>();
    private final Map<ActionKey, ActionCleanupReceipt>
            completedMenuCleanups = new LinkedHashMap<>();
    private final InventoryLayoutCleanupFence
            inventoryLayoutCleanupFence =
                    new InventoryLayoutCleanupFence();
    /**
     * 窄的 package test seam：生产构造器始终绑定真实原版 click + broadcast 路径。
     * 它不暴露给 Action/Skill，不能用于绕过 {@link MenuTransaction} 的 snapshot/ACK 合同。
     */
    private final WorldMenuClickDispatcher worldMenuClickDispatcher;
    private int packetSequence;

    MinecraftWorldInteractionBackend(BotLifecycleManager lifecycleManager) {
        this(
                lifecycleManager,
                MinecraftWorldInteractionBackend::dispatchVanillaWorldMenuClick);
    }

    MinecraftWorldInteractionBackend(
            BotLifecycleManager lifecycleManager,
            WorldMenuClickDispatcher worldMenuClickDispatcher) {
        this.lifecycleManager =
                Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        this.worldMenuClickDispatcher = Objects.requireNonNull(
                worldMenuClickDispatcher, "worldMenuClickDispatcher");
    }

    /**
     * Last-chance strict-use fence invoked immediately before vanilla advances
     * an item's native use state.
     *
     * <p>The normal action-runtime tick runs after the bot player's native
     * {@code doTick()}. That is intentionally too late for the final tick of a
     * consumable: a mod can add an unapproved effect during
     * {@code PlayerTickEvent.Pre}, then vanilla can consume milk and clear it
     * before the ordinary post-tick verifier observes the drift. The mixin
     * hook calls this method directly before {@code LivingEntity} updates the
     * use item. It never performs a world mutation other than the same native
     * release/stop cleanup already used for an in-flight strict-use failure.
     *
     * @return {@code true} when vanilla must skip this item-use update because
     *     a strict action was stopped fail-closed
     */
    boolean beforeNativeItemUseUpdate(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        boolean interrupted = false;
        long generation = player.runtimeHandle().generation();
        long currentTick = nativeUseCurrentTickOrInvalid(player);
        for (InteractionState state : List.copyOf(active.values())) {
            if (!state.botId.equals(player.getUUID())
                    || state.botGeneration != generation
                    || !state.startedUsing
                    || !(state.spec
                            instanceof WorldInteractionActionSpec.UseItem
                                    useItem)
                    || useItem.strictPreconditions().isEmpty()) {
                continue;
            }
            /*
             * A nested update after this exact use has entered vanilla
             * completion must not obtain a second opportunity to consume the
             * stale stack held by the outer updateUsingItem call.
             */
            if (state.strictUseCompletionPhase
                    .hasEnteredNativeCompletion()) {
                return true;
            }
            if (state.strictUseStopReason == StrictUseStopReason.NONE
                    && StrictNativeUseTimingFence.allows(
                            state.envelope, state.startedTick, currentTick)
                    && strictUseStillMatches(player, useItem)) {
                continue;
            }
            /*
             * Record the failed strict native fence before cleanup. A
             * cancellation-fenced state was marked earlier by lifecycle code
             * and must stay distinct: the action runtime still needs to drain
             * its accepted cancellation rather than reinterpret this stop as
             * a precondition failure. At an action time boundary the runtime
             * later owns the terminal timeout outcome.
             */
            if (state.strictUseStopReason == StrictUseStopReason.NONE) {
                state.strictUseStopReason =
                        StrictUseStopReason.PRECONDITION_DRIFT;
            }
            try {
                interruptStrictUse(player, state);
            } catch (RuntimeException exception) {
                try {
                    player.stopUsingItem();
                } catch (RuntimeException ignored) {
                    // The caller will cancel vanilla's update as a fail-closed fence.
                }
            }
            interrupted = true;
        }
        return interrupted;
    }

    /**
     * Last exact recheck before vanilla invokes {@code completeUsingItem()}.
     *
     * <p>The normal update hook protects the beginning of a native use. Both
     * NeoForge's item-use tick event and item implementations can re-enter
     * application code after that point, so a strict consumable must be
     * rechecked again at the sole completion invocation. Once this method
     * returns {@code false} for a strict state, that state has crossed an
     * irreversible completion boundary: later cancellation must await the
     * actual action outcome rather than enqueue a cancellation ahead of it.
     *
     * @return {@code true} when vanilla must skip the pending completion call
     */
    boolean beforeNativeItemUseCompletion(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        long generation = player.runtimeHandle().generation();
        long currentTick = nativeUseCurrentTickOrInvalid(player);
        List<InteractionState> strictStates = new ArrayList<>();
        boolean reject = false;
        for (InteractionState state : List.copyOf(active.values())) {
            if (!state.botId.equals(player.getUUID())
                    || state.botGeneration != generation
                    || !isActiveStrictNaturalUse(state)) {
                continue;
            }
            strictStates.add(state);
            if (state.strictUseCompletionPhase
                    .hasEnteredNativeCompletion()
                    || state.strictUseStopReason
                            != StrictUseStopReason.NONE
                    || !(state.spec
                            instanceof WorldInteractionActionSpec.UseItem
                                    useItem)
                    || !StrictNativeUseTimingFence.allows(
                            state.envelope, state.startedTick, currentTick)
                    || !strictUseStillMatches(player, useItem)) {
                reject = true;
            }
        }
        if (strictStates.isEmpty()) {
            return false;
        }
        if (reject) {
            for (InteractionState state : strictStates) {
                if (state.strictUseCompletionPhase
                        .hasEnteredNativeCompletion()) {
                    continue;
                }
                if (state.strictUseStopReason == StrictUseStopReason.NONE) {
                    state.strictUseStopReason =
                            StrictUseStopReason.PRECONDITION_DRIFT;
                }
                try {
                    interruptStrictUse(player, state);
                } catch (RuntimeException exception) {
                    try {
                        player.stopUsingItem();
                    } catch (RuntimeException ignored) {
                        // The Mixin cancels the outer update as the final fence.
                    }
                }
            }
            return true;
        }
        strictStates.forEach(state -> state.strictUseCompletionPhase =
                StrictUseCompletionPhase.ENTERED);
        return false;
    }

    /**
     * Marks one already-active strict use so the native-use mixin rejects it
     * before the next vanilla update.
     *
     * <p>This intentionally only records an exact active strict-natural
     * {@link WorldInteractionActionSpec.UseItem} key. It does not guess from a
     * bot id, touch a queued action, mutate inventory/effects, or stop a
     * non-strict or non-natural legacy use. The marker is consumed by
     * {@link #beforeNativeItemUseUpdate(BotServerPlayer)} at the actual native
     * consumption boundary.
     */
    MinecraftActionBackend.StrictUseCancellationFenceStatus
            fenceStrictNativeItemUseCancellation(
                    ActionEnvelope expected) {
        ActionEnvelope required = Objects.requireNonNull(expected,
                "expected");
        InteractionState state = active.get(ActionKey.from(required));
        if (isActiveStrictNaturalUse(state)) {
            if (!required.equals(state.envelope)) {
                return MinecraftActionBackend
                        .StrictUseCancellationFenceStatus
                        .ACTIVE_STRICT_USE_MISMATCH;
            }
            if (state.strictUseCompletionPhase
                    .hasEnteredNativeCompletion()) {
                return MinecraftActionBackend
                        .StrictUseCancellationFenceStatus
                        .NATIVE_COMPLETION_ENTERED;
            }
            state.strictUseStopReason =
                    StrictUseStopReason.CANCELLATION_FENCED;
            return MinecraftActionBackend.StrictUseCancellationFenceStatus
                    .MARKED;
        }
        boolean otherStrictUseActive = active.values().stream().anyMatch(
                candidate -> candidate.botId.equals(required.botId())
                        && candidate.botGeneration
                                == required.botGeneration()
                        && isActiveStrictNaturalUse(candidate));
        return otherStrictUseActive
                ? MinecraftActionBackend.StrictUseCancellationFenceStatus
                        .ACTIVE_STRICT_USE_MISMATCH
                : MinecraftActionBackend.StrictUseCancellationFenceStatus
                        .NO_ACTIVE_STRICT_USE;
    }

    private static boolean isActiveStrictNaturalUse(
            InteractionState state) {
        return state != null
                && state.startedUsing
                && state.spec instanceof WorldInteractionActionSpec.UseItem
                        useItem
                && useItem.mode()
                        == WorldInteractionActionSpec.ItemUseMode
                                .FINISH_NATURALLY
                && useItem.strictPreconditions().isPresent();
    }

    private static long nativeUseCurrentTickOrInvalid(BotServerPlayer player) {
        try {
            return Objects.requireNonNull(player, "player")
                    .serverLevel()
                    .getServer()
                    .getTickCount();
        } catch (RuntimeException exception) {
            return -1L;
        }
    }

    @Override
    public BackendResult validate(ActionEnvelope envelope, long currentTick) {
        Optional<BotServerPlayer> resolved = resolveActive(envelope);
        if (resolved.isEmpty()) {
            return BackendResult.stale(
                    envelope, "Bot generation is no longer authoritative");
        }
        if (!(envelope.action() instanceof WorldInteractionAction action)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSUPPORTED,
                    "Minecraft world backend does not support this action");
        }

        BotServerPlayer player = resolved.orElseThrow();
        if (!lifecycleManager.mayActionMutateInventory(
                envelope.botId(), envelope.botGeneration())) {
            return failure(
                    envelope,
                    ActionFailureCode.CHANNEL_BUSY,
                    "Bot inventory is write-locked by an open viewer");
        }
        return validateSpec(envelope, player, action.spec());
    }

    @Override
    public BackendResult start(ActionEnvelope envelope, long currentTick) {
        Optional<BotServerPlayer> resolved = resolveActive(envelope);
        if (resolved.isEmpty()) {
            return BackendResult.stale(
                    envelope, "Bot generation changed before action start");
        }
        if (!(envelope.action() instanceof WorldInteractionAction action)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSUPPORTED,
                    "Minecraft world backend does not support this action");
        }

        BotServerPlayer player = resolved.orElseThrow();
        if (action.spec()
                instanceof WorldInteractionActionSpec
                        .SwapInventoryHotbar
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .InventoryMenuSwap
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .WorldMenuTransaction
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .WorldMenuTransfer
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .WorldMenuRecipe
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .WorldVillagerTrade
                || action.spec()
                        instanceof WorldInteractionActionSpec.PlaceBlock
                || action.spec()
                        instanceof WorldInteractionActionSpec.AimAndPlaceBlock
                || action.spec()
                        instanceof WorldInteractionActionSpec.BreakBlock
                || action.spec()
                        instanceof WorldInteractionActionSpec.AttackEntity
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .InteractEntity
                || action.spec()
                        instanceof WorldInteractionActionSpec.UseItem
                                useItem
                        && useItem.strictPreconditions().isPresent()) {
            if (!lifecycleManager.mayActionMutateInventory(
                    envelope.botId(), envelope.botGeneration())) {
                return failure(
                        envelope,
                        ActionFailureCode.CHANNEL_BUSY,
                        "Bot inventory is write-locked by an open viewer");
            }
        }
        if (requiresStartRevalidation(action.spec())) {
            BackendResult revalidated =
                    validateSpec(envelope, player, action.spec());
            if (revalidated.step() != BackendStep.ACCEPTED) {
                return revalidated;
            }
        }
        ActionKey key = ActionKey.from(envelope);
        InteractionState state =
                InteractionState.capture(
                        player,
                        action.spec(),
                        currentTick,
                        envelope.botId(),
                        envelope.botGeneration(),
                        envelope);
        if (active.putIfAbsent(key, state) != null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World action was started more than once");
        }

        try {
            BackendResult dispatch = dispatchStart(envelope, player, state);
            if (dispatch.step() != BackendStep.ACCEPTED) {
                active.remove(key, state);
                return dispatch;
            }
        } catch (MenuPreconditionChangedException exception) {
            /*
             * 世界菜单的右键已经可能成功打开窗口。不能像纯 InventoryMenu 计划那样
             * 清掉副作用标志，否则失败路径会把窗口留给下一动作。
             */
            if (state.spec
                    instanceof WorldInteractionActionSpec.WorldVillagerTrade
                            trade) {
                closeVillagerTradeDuringCleanup(player, state, trade);
            } else if (state.spec
                            instanceof WorldInteractionActionSpec
                                    .WorldMenuTransaction
                    || state.spec
                            instanceof WorldInteractionActionSpec
                                    .WorldMenuTransfer
                    || state.spec
                            instanceof WorldInteractionActionSpec
                                    .WorldMenuRecipe
                    || state.spec
                            instanceof WorldInteractionActionSpec.PlaceBlock
                    || state.spec
                            instanceof WorldInteractionActionSpec.AimAndPlaceBlock) {
                closeWorldMenuDuringCleanup(player, state);
            } else {
                state.sideEffectDispatched = false;
            }
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    exception.safeSummary());
        }
        return requiresTicks(state.spec)
                ? BackendResult.running(envelope)
                : BackendResult.readyToVerify(envelope);
    }

    @Override
    public BackendResult tick(
            ActionEnvelope envelope, long startedTick, long currentTick) {
        Optional<BotServerPlayer> resolved = resolveActive(envelope);
        if (resolved.isEmpty()) {
            return BackendResult.stale(
                    envelope, "Bot generation changed while action was running");
        }
        InteractionState state = active.get(ActionKey.from(envelope));
        if (state == null || state.startedTick != startedTick) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World action has no matching active state");
        }

        BotServerPlayer player = resolved.orElseThrow();
        return switch (state.spec) {
            case WorldInteractionActionSpec.BreakBlock breakBlock ->
                    tickBreak(
                            envelope,
                            player,
                            state,
                            breakBlock,
                            currentTick);
            case WorldInteractionActionSpec.UseItem useItem ->
                    tickUse(
                            envelope,
                            player,
                            state,
                            useItem,
                            currentTick);
            case WorldInteractionActionSpec.PickupWait pickupWait ->
                    tickPickup(
                            envelope,
                            player,
                            state,
                            pickupWait,
                            currentTick);
            case WorldInteractionActionSpec.InventoryMenuSwap menuSwap ->
                    tickMenuSwap(
                            envelope,
                            player,
                            state,
                            menuSwap,
                            currentTick);
            case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                    tickWorldMenuTransaction(
                            envelope,
                            player,
                            state,
                            menu.template().family(),
                            currentTick);
            case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                    tickWorldMenuTransaction(
                            envelope,
                            player,
                            state,
                            menu.family(),
                            currentTick);
            case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                    tickWorldMenuRecipe(
                            envelope, player, state, menu, currentTick);
            case WorldInteractionActionSpec.WorldVillagerTrade trade ->
                    tickWorldVillagerTrade(
                            envelope, player, state, trade, currentTick);
            default -> BackendResult.readyToVerify(envelope);
        };
    }

    @Override
    public BackendResult verify(ActionEnvelope envelope, long currentTick) {
        Optional<BotServerPlayer> resolved = resolveActive(envelope);
        if (resolved.isEmpty()) {
            return BackendResult.stale(
                    envelope, "Bot generation changed before verification");
        }
        InteractionState state = active.get(ActionKey.from(envelope));
        if (state == null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World action has no state to verify");
        }

        BotServerPlayer player = resolved.orElseThrow();
        return switch (state.spec) {
            case WorldInteractionActionSpec.SelectHotbar selectHotbar ->
                    verifySelect(envelope, player, selectHotbar);
            case WorldInteractionActionSpec.SwapInventoryHotbar swap ->
                    verifySwap(envelope, player, state, swap);
            case WorldInteractionActionSpec.InventoryMenuSwap menuSwap ->
                    verifyMenuSwap(envelope, player, state, menuSwap);
            case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                    verifyWorldMenuTransaction(
                            envelope, player, state, menu.template().family());
            case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                    verifyWorldMenuTransaction(
                            envelope, player, state, menu.family());
            case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                    verifyWorldMenuRecipe(envelope, player, state, menu);
            case WorldInteractionActionSpec.WorldVillagerTrade trade ->
                    verifyWorldVillagerTrade(envelope, player, state, trade);
            case WorldInteractionActionSpec.UseItem useItem ->
                    verifyUse(envelope, player, state, useItem);
            case WorldInteractionActionSpec.ReleaseUse releaseUse ->
                    verifyRelease(envelope, player, releaseUse);
            case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                    verifyUseOn(envelope, player, state, useOnBlock);
            case WorldInteractionActionSpec.PlaceBlock placeBlock ->
                    verifyPlaceBlock(envelope, player, state, placeBlock);
            case WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace ->
                    verifyAimAndPlaceBlock(
                            envelope, player, state, aimAndPlace);
            case WorldInteractionActionSpec.BreakBlock breakBlock ->
                    verifyBreak(envelope, player, state, breakBlock);
            case WorldInteractionActionSpec.AttackEntity attackEntity ->
                    verifyAttack(envelope, player, state, attackEntity);
            case WorldInteractionActionSpec.InteractEntity interactEntity ->
                    verifyInteract(envelope, player, state, interactEntity);
            case WorldInteractionActionSpec.DropSelected dropSelected ->
                    verifyDrop(envelope, player, state, dropSelected);
            case WorldInteractionActionSpec.PickupWait pickupWait ->
                    verifyPickup(envelope, player, state, pickupWait);
        };
    }

    @Override
    public ActionCleanupReceipt cleanupStep(
            ActionEnvelope envelope,
            ActionCleanupRequest request) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(request, "request");
        if (!request.matches(envelope)) {
            throw new IllegalArgumentException(
                    "cleanup request does not match the action envelope");
        }

        ActionKey key = ActionKey.from(envelope);
        if (request.vanillaDeathConsumed()) {
            active.remove(key);
            completedMenuCleanups.remove(key);
            return ActionCleanupReceipt.complete(
                    request,
                    0L,
                    "Vanilla death consumed world action body state");
        }
        InteractionState state = active.get(key);
        ActionCleanupReceipt completed =
                completedMenuCleanups.get(key);
        if (state == null && completed != null) {
            return completed.matches(request)
                    ? completed
                    : ActionCleanupReceipt.unsafe(
                            request,
                            completed.progressRevision(),
                            "Completed menu cleanup identity changed");
        }
        if (state == null
                || !(state.spec
                        instanceof WorldInteractionActionSpec
                                .InventoryMenuSwap menuSwap)) {
            return ActionBackend.super.cleanupStep(
                    envelope, request);
        }
        InventoryMenuCleanupSession.BeginResult begin =
                state.menuCleanupSession.begin(request);
        if (begin.status()
                == InventoryMenuCleanupSession.BeginStatus.REPLAY) {
            return begin.replayReceipt().orElseThrow();
        }
        if (begin.status()
                == InventoryMenuCleanupSession.BeginStatus.REJECTED) {
            return ActionCleanupReceipt.unsafe(
                    request,
                    state.menuCleanupSession.progressRevision(),
                    "Menu cleanup identity or attempt changed");
        }

        ActionCleanupReceipt receipt;
        try {
            receipt = cleanupMenuSwapStep(
                    envelope,
                    key,
                    state,
                    menuSwap,
                    request);
        } catch (RuntimeException exception) {
            receipt = unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup adapter failed closed");
        }
        receipt = state.menuCleanupSession.remember(
                request, receipt);
        if (receipt.status() != ActionCleanupStatus.PENDING) {
            rememberCompletedMenuCleanup(key, receipt);
        }
        return receipt;
    }

    private void rememberCompletedMenuCleanup(
            ActionKey key, ActionCleanupReceipt receipt) {
        completedMenuCleanups.put(key, receipt);
        while (completedMenuCleanups.size()
                > MAX_COMPLETED_MENU_CLEANUPS) {
            ActionKey eldest = completedMenuCleanups
                    .keySet()
                    .iterator()
                    .next();
            completedMenuCleanups.remove(eldest);
        }
    }

    private ActionCleanupReceipt cleanupMenuSwapStep(
            ActionEnvelope envelope,
            ActionKey key,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap,
            ActionCleanupRequest request) {
        InventoryMenuTransaction transaction =
                state.menuTransaction;
        if (request.reason() == ActionCleanupReason.SUCCEEDED) {
            if (transaction == null
                    || transaction.state()
                            != InventoryMenuTransactionState
                                    .COMMITTED) {
                return unsafeMenuCleanup(
                        state,
                        request,
                        "Successful menu action lacks a committed transaction");
            }
            active.remove(key, state);
            return ActionCleanupReceipt.complete(
                    request,
                    state.menuCleanupSession.progressRevision(),
                    "Committed menu transaction released");
        }
        if (!state.sideEffectDispatched) {
            active.remove(key, state);
            return ActionCleanupReceipt.complete(
                    request,
                    state.menuCleanupSession.progressRevision(),
                    "Menu transaction stopped before its first click");
        }
        if (transaction == null
                || transaction.state().terminal()) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup lost its live transaction cursor");
        }
        if (state.menuCleanupSession.clickDispatchOpen()) {
            return pendingMenuCleanup(
                    state,
                    request,
                    "Menu cleanup is waiting for an open menu click to return");
        }

        Optional<BotServerPlayer> resolved =
                lifecycleManager.resolveCleanupTarget(
                        envelope.botId(), envelope.botGeneration());
        if (resolved.isEmpty()) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup has no authoritative player body");
        }
        BotServerPlayer player = resolved.orElseThrow();
        boolean replacementInheritor =
                lifecycleManager
                        .isStagedReplacementCleanupTarget(
                                envelope.botId(),
                                envelope.botGeneration(),
                                player);

        InventoryMenuSnapshot actual;
        try {
            actual = MinecraftActionSnapshot.inventoryMenu(
                    player);
        } catch (RuntimeException exception) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup could not capture its authoritative snapshot");
        }
        InventoryMenuSwapPlan plan = menuSwap.plan();
        if (!menuCleanupControlPlaneSafe(
                player, state, plan, actual)) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup control plane or conservation proof changed");
        }

        InventoryMenuSettlementDecision decision;
        try {
            decision = menuCleanupDecision(
                    state,
                    plan,
                    transaction,
                    actual,
                    replacementInheritor);
        } catch (RuntimeException exception) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup could not bind one exact plan prefix");
        }

        return switch (decision.outcome()) {
            case UNSAFE -> unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup observed an unauthorized plan prefix");
            case SAFE_PREFIX_COMMITTED -> {
                state.menuTransaction = transaction.cancel();
                clearMenuSettlementState(state);
                active.remove(key, state);
                yield ActionCleanupReceipt.complete(
                        request,
                        state.menuCleanupSession.progressRevision(),
                        "Menu layout changed externally but remained conserved");
            }
            case ALREADY_INITIAL, ALREADY_FINAL ->
                    finishMenuCleanupEndpoint(
                            key,
                            state,
                            plan,
                            decision,
                            actual,
                            request);
            case CLICK_TO_INITIAL, CLICK_TO_FINAL ->
                    !state.menuCleanupSession
                            .mayDispatchClickAt(
                                    request.currentTick())
                            ? deferMenuCleanupAfterSameTickClick(
                                    state,
                                    decision,
                                    request)
                            : applyMenuCleanupClick(
                                    key,
                                    player,
                                    state,
                                    plan,
                                    decision,
                                    request);
        };
    }

    private static ActionCleanupReceipt
            deferMenuCleanupAfterSameTickClick(
                    InteractionState state,
                    InventoryMenuSettlementDecision decision,
                    ActionCleanupRequest request) {
        state.menuCleanupSession.observe(decision);
        return pendingMenuCleanup(
                state,
                request,
                "Menu cleanup deferred after a same-Tick menu click");
    }

    private InventoryMenuSettlementDecision menuCleanupDecision(
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuTransaction transaction,
            InventoryMenuSnapshot actual,
            boolean replacementInheritor) {
        resolveForwardMenuInFlight(
                state,
                plan,
                transaction,
                actual,
                replacementInheritor);

        InventoryMenuSettlementCursor settlementCursor =
                state.menuCleanupSession
                        .settlementCursor()
                        .orElse(null);
        int stablePrefix = settlementCursor == null
                ? state.menuTransaction.confirmedClicks()
                : settlementCursor.confirmedPrefix();
        InventoryMenuSnapshot stableSnapshot =
                Objects.requireNonNull(
                        state.menuLastSnapshot,
                        "menuLastSnapshot");
        boolean reboundStable = consumeReplacementRebind(
                state,
                plan,
                stablePrefix,
                actual,
                replacementInheritor);
        if (reboundStable) {
            stableSnapshot = actual;
            state.menuLastSnapshot = actual;
        }
        InventoryMenuPrefixAuthority authority =
                InventoryMenuPrefixAuthority.stable(
                        plan, stablePrefix, stableSnapshot);
        if (settlementCursor == null) {
            return InventoryMenuSettlementPolicy.decide(
                    plan, actual, authority);
        }
        return InventoryMenuSettlementPolicy.decide(
                settlementCursor,
                actual,
                authority);
    }

    private void resolveForwardMenuInFlight(
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuTransaction transaction,
            InventoryMenuSnapshot actual,
            boolean replacementInheritor) {
        int source = transaction.confirmedClicks();
        if (state.menuForwardInFlight != source) {
            return;
        }
        InventoryMenuSnapshot sourceSnapshot =
                Objects.requireNonNull(
                        state.menuLastSnapshot,
                        "menuLastSnapshot");
        boolean reboundSource = consumeReplacementRebind(
                state,
                plan,
                source,
                actual,
                replacementInheritor);
        if (actual.equals(sourceSnapshot) || reboundSource) {
            if (reboundSource) {
                state.menuLastSnapshot = actual;
            }
            state.menuForwardInFlight = -1;
            state.menuForwardTargetSnapshot = null;
            return;
        }

        int target = source + 1;
        InventoryMenuSnapshot targetSnapshot =
                state.menuForwardTargetSnapshot;
        boolean exactTarget = targetSnapshot != null
                && actual.equals(targetSnapshot);
        boolean reboundTarget =
                target <= plan.orderedSteps().size()
                        && consumeReplacementRebind(
                                state,
                                plan,
                                target,
                                actual,
                                replacementInheritor);
        if (!exactTarget && !reboundTarget) {
            throw new IllegalStateException(
                    "forward menu in-flight state is neither exact source nor target");
        }
        state.menuTransaction = transaction.confirmNext(
                plan.orderedSteps().get(source));
        state.menuLastSnapshot = actual;
        state.menuForwardInFlight = -1;
        state.menuForwardTargetSnapshot = null;
        state.menuCleanupSession
                .recordConfirmedForwardProgress();
    }

    private static boolean consumeReplacementRebind(
            InteractionState state,
            InventoryMenuSwapPlan plan,
            int prefix,
            InventoryMenuSnapshot actual,
            boolean replacementInheritor) {
        if (!replacementInheritor
                || state.menuReplacementRebound
                || !plan.snapshotAtPrefix(prefix)
                        .layoutEqualsIgnoringState(actual)) {
            return false;
        }
        state.menuReplacementRebound = true;
        return true;
    }

    private boolean menuCleanupControlPlaneSafe(
            BotServerPlayer player,
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuSnapshot actual) {
        return lifecycleManager.mayCleanupMutateInventory(
                        state.botId, state.botGeneration)
                && player.containerMenu == player.inventoryMenu
                && isCurrentCleanupTarget(state, player)
                && nativeCraftSlotsEmpty(player)
                && actual.cursor().isEmpty()
                && actual.containerId()
                        == plan.initialSnapshot().containerId()
                && actual.inventoryMultisetEquals(
                        plan.initialSnapshot())
                && MinecraftInteractionView
                        .inventoryMultisetDigest(player)
                        .equals(state.inventoryMultisetBefore);
    }

    private ActionCleanupReceipt applyMenuCleanupClick(
            ActionKey key,
            BotServerPlayer player,
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuSettlementDecision decision,
            ActionCleanupRequest request) {
        InventoryMenuSettlementCursor cursor =
                decision.settlementCursor().orElseThrow();
        InventoryMenuClickStep step =
                decision.click().orElseThrow();
        InventoryMenuSnapshot before =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (!before.equals(decision.observedSnapshot())
                || !menuCleanupControlPlaneSafe(
                        player, state, plan, before)
                || !before.layoutEqualsIgnoringState(
                        step.before())
                || !menuStepAllowedNow(player, step)) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup click precondition changed");
        }

        InventoryMenuSettlementCursor advanced =
                cursor.advanceAfterProposedClick();
        state.menuCleanupSession.observe(decision);
        state.menuSettlementSourcePrefix =
                cursor.confirmedPrefix();
        state.menuSettlementTargetPrefix =
                advanced.confirmedPrefix();
        state.menuSettlementSourceSnapshot = before;
        state.menuSettlementTargetSnapshot = null;
        state.menuForwardInFlight = -1;
        state.menuCleanupSession.beginClickDispatch(
                request.currentTick());

        try {
            player.inventoryMenu.clicked(
                    step.menuSlot(),
                    step.hotbarButton(),
                    ClickType.SWAP,
                    player);
            player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException ignored) {
            // The exact authoritative post-call snapshot distinguishes
            // a before-mutation throw from an after-mutation throw.
        } finally {
            state.menuCleanupSession.endClickDispatch(
                    request.currentTick());
        }

        InventoryMenuSnapshot after;
        try {
            after = MinecraftActionSnapshot.inventoryMenu(
                    player);
        } catch (RuntimeException exception) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup click outcome could not be observed");
        }
        if (!menuCleanupControlPlaneSafe(
                player, state, plan, after)) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup authority changed during its click");
        }
        if (after.equals(before)) {
            clearMenuSettlementInFlight(state);
            state.menuLastSnapshot = before;
            return pendingMenuCleanup(
                    state,
                    request,
                    "Menu cleanup click stopped before mutation");
        }
        if (!after.layoutEqualsIgnoringState(step.after())) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup click reached neither adjacent snapshot");
        }

        state.menuSettlementTargetSnapshot = after;
        InventoryMenuSettlementCursor confirmed =
                state.menuCleanupSession
                        .confirmProposedClick(decision);
        if (confirmed.confirmedPrefix()
                != advanced.confirmedPrefix()) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup progress cursor diverged");
        }
        state.menuLastSnapshot = after;
        if (advanced.endpoint()
                        == InventoryMenuSettlementCursor.Endpoint.FINAL) {
            InventoryMenuTransaction transaction =
                    state.menuTransaction;
            if (transaction.confirmedClicks()
                    != cursor.confirmedPrefix()) {
                return unsafeMenuCleanup(
                        state,
                        request,
                        "Final-directed cleanup lost its transaction cursor");
            }
            state.menuTransaction = transaction.confirmNext(
                    plan.orderedSteps().get(
                            cursor.confirmedPrefix()));
        }
        clearMenuSettlementInFlight(state);
        if (advanced.atEndpoint()) {
            InventoryMenuSettlementDecision endpointDecision =
                    InventoryMenuSettlementPolicy.decide(
                            advanced,
                            after,
                            InventoryMenuPrefixAuthority.stable(
                                    plan,
                                    advanced.confirmedPrefix(),
                                    after));
            return finishMenuCleanupEndpoint(
                    key,
                    state,
                    plan,
                    endpointDecision,
                    after,
                    request);
        }
        return pendingMenuCleanup(
                state,
                request,
                "Menu cleanup advanced one adjacent prefix");
    }

    private ActionCleanupReceipt finishMenuCleanupEndpoint(
            ActionKey key,
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuSettlementDecision decision,
            InventoryMenuSnapshot settled,
            ActionCleanupRequest request) {
        InventoryMenuSettlementCursor cursor =
                decision.settlementCursor().orElseThrow();
        if (!cursor.atEndpoint()
                || decision.observedPrefix()
                        != cursor.confirmedPrefix()
                || !settled.equals(
                        decision.observedSnapshot())) {
            return unsafeMenuCleanup(
                    state,
                    request,
                    "Menu cleanup endpoint proof is inconsistent");
        }
        InventoryMenuTransaction transaction =
                Objects.requireNonNull(
                        state.menuTransaction,
                        "menuTransaction");
        if (cursor.endpoint()
                == InventoryMenuSettlementCursor.Endpoint.FINAL) {
            if (cursor.confirmedPrefix()
                            != plan.orderedSteps().size()
                    || transaction.state()
                            != InventoryMenuTransactionState
                                    .VERIFYING
                    || transaction.confirmedClicks()
                            != plan.orderedSteps().size()) {
                return unsafeMenuCleanup(
                        state,
                        request,
                        "Final menu endpoint lacks an exact transaction proof");
            }
            state.menuTransaction = transaction.commit();
        } else {
            if (cursor.confirmedPrefix() != 0) {
                return unsafeMenuCleanup(
                        state,
                        request,
                        "Initial menu endpoint has a non-zero prefix");
            }
            state.menuTransaction = transaction.cancel();
        }
        state.menuLastSnapshot = settled;
        clearMenuSettlementState(state);
        active.remove(key, state);
        return ActionCleanupReceipt.complete(
                request,
                state.menuCleanupSession.progressRevision(),
                cursor.endpoint()
                                == InventoryMenuSettlementCursor
                                        .Endpoint.INITIAL
                        ? "Menu cleanup restored the initial endpoint"
                        : "Menu cleanup committed the final endpoint");
    }

    private static ActionCleanupReceipt pendingMenuCleanup(
            InteractionState state,
            ActionCleanupRequest request,
            String summary) {
        if (request.attempt()
                >= ActionCleanupRequest.MAX_ATTEMPTS) {
            return ActionCleanupReceipt.unsafe(
                    request,
                    state.menuCleanupSession.progressRevision(),
                    "Menu cleanup exhausted its bounded attempts");
        }
        return ActionCleanupReceipt.pending(
                request,
                state.menuCleanupSession.progressRevision(),
                request.currentTick() + 1L,
                summary);
    }

    private static ActionCleanupReceipt unsafeMenuCleanup(
            InteractionState state,
            ActionCleanupRequest request,
            String summary) {
        return ActionCleanupReceipt.unsafe(
                request,
                state.menuCleanupSession.progressRevision(),
                summary);
    }

    @Override
    public void cleanup(
            ActionEnvelope envelope,
            ActionCleanupReason reason,
            long currentTick) {
        Objects.requireNonNull(reason, "reason");
        ActionKey key = ActionKey.from(envelope);
        if (reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED) {
            active.remove(key);
            completedMenuCleanups.remove(key);
            return;
        }
        InteractionState state = active.get(key);
        if (state == null) {
            return;
        }

        Optional<BotServerPlayer> resolved =
                lifecycleManager.resolveCleanupTarget(
                        envelope.botId(), envelope.botGeneration());
        if (resolved.isPresent()) {
            BotServerPlayer player = resolved.orElseThrow();
            cleanupPlayerState(
                    player,
                    state,
                    reason,
                    currentTick,
                    lifecycleManager
                            .isStagedReplacementCleanupTarget(
                                    envelope.botId(),
                                    envelope.botGeneration(),
                                    player));
        } else if (requiresResolvedCleanupTarget(state, reason)) {
            throw new IllegalStateException(
                    "Cannot resolve the authoritative player body required for action cleanup");
        }
        active.remove(key, state);
    }

    @Override
    public boolean forceSafeReset(
            UUID botId, long botGeneration, long currentTick) {
        Optional<BotServerPlayer> resolved =
                lifecycleManager.resolveCleanupTarget(botId, botGeneration);
        if (resolved.isEmpty()) {
            return false;
        }

        BotServerPlayer player = resolved.orElseThrow();
        boolean replacementInheritor =
                lifecycleManager
                        .isStagedReplacementCleanupTarget(
                                botId,
                                botGeneration,
                                player);
        try {
            player.stopUsingItem();
            for (Map.Entry<ActionKey, InteractionState> entry :
                    List.copyOf(active.entrySet())) {
                ActionKey key = entry.getKey();
                if (key.botId.equals(botId)
                        && key.botGeneration == botGeneration) {
                    cleanupPlayerState(
                            player,
                            entry.getValue(),
                            ActionCleanupReason.FAILED,
                            currentTick,
                            replacementInheritor);
                    active.remove(key, entry.getValue());
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    boolean openSkillInventoryLayout(
            UUID botId,
            long botGeneration,
            InventoryLayoutCleanupLease layoutLease) {
        if (lifecycleManager
                .resolveActionTarget(
                        botId, botGeneration)
                .isEmpty()) {
            return false;
        }
        InventoryLayoutCleanupFence.ArmResult result =
                inventoryLayoutCleanupFence.arm(
                        botId,
                        botGeneration,
                        layoutLease);
        return result
                        == InventoryLayoutCleanupFence
                                .ArmResult.ARMED
                || result
                        == InventoryLayoutCleanupFence
                                .ArmResult.ALREADY_ARMED;
    }

    InventoryLayoutCleanupResult
            consumeVanillaDeathSkillInventoryLayout(
                    UUID botId,
                    long botGeneration,
                    InventoryLayoutCleanupLease layoutLease) {
        return inventoryLayoutCleanupFence.consumeVanillaDeath(
                botId,
                botGeneration,
                layoutLease);
    }

    void releaseSkillInventoryLayout(
            UUID botId, long botGeneration, UUID runId) {
        inventoryLayoutCleanupFence.release(
                botId, botGeneration, runId);
    }

    void closeSkillInventoryGeneration(
            UUID botId, long botGeneration) {
        inventoryLayoutCleanupFence.closeGeneration(
                botId, botGeneration);
    }

    void closeSkillInventoryFences() {
        inventoryLayoutCleanupFence.clear();
    }

    InventoryLayoutCleanupResult cleanupSkillInventoryLayout(
            UUID botId,
            long botGeneration,
            InventoryLayoutCleanupRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<InventoryLayoutCleanupResult> completed =
                inventoryLayoutCleanupFence.completedResult(
                        botId,
                        botGeneration,
                        request.lease());
        if (completed.isPresent()) {
            return completed.orElseThrow();
        }
        if (inventoryLayoutCleanupFence.begin(
                        botId,
                        botGeneration,
                        request.lease())
                != InventoryLayoutCleanupFence.StartResult
                        .STARTED) {
            return InventoryLayoutCleanupResult.STALE;
        }
        InventoryLayoutCleanupResult result;
        try {
            result = cleanupSkillInventoryLayoutStarted(
                    botId, botGeneration, request);
        } catch (RuntimeException exception) {
            result = InventoryLayoutCleanupResult.UNSAFE;
        }
        if (result
                == InventoryLayoutCleanupResult.BLOCKED) {
            return inventoryLayoutCleanupFence.retry(
                            botId,
                            botGeneration,
                            request.runId())
                    ? result
                    : InventoryLayoutCleanupResult.UNSAFE;
        }
        if (!inventoryLayoutCleanupFence.complete(
                botId,
                botGeneration,
                request.runId(),
                result)) {
            return InventoryLayoutCleanupResult.UNSAFE;
        }
        return result;
    }

    private InventoryLayoutCleanupResult
            cleanupSkillInventoryLayoutStarted(
                    UUID botId,
                    long botGeneration,
                    InventoryLayoutCleanupRequest request) {
        Optional<BotServerPlayer> resolved =
                lifecycleManager.resolveCleanupTarget(
                        botId, botGeneration);
        if (resolved.isEmpty()) {
            return InventoryLayoutCleanupResult.STALE;
        }

        BotServerPlayer player = resolved.orElseThrow();
        if (!lifecycleManager.mayCleanupMutateInventory(
                        botId, botGeneration)
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || player.isUsingItem()
                || active.keySet().stream().anyMatch(key ->
                        key.botId.equals(botId)
                                && key.botGeneration
                                        == botGeneration)) {
            return InventoryLayoutCleanupResult.BLOCKED;
        }
        boolean committed = false;
        boolean restored = false;
        InventoryContentsSnapshot currentInventory =
                MinecraftInteractionView.inventoryContents(
                        player);
        if (!InventoryLayoutCleanupPolicy
                .conservesInventory(
                        request,
                        currentInventory)) {
            return InventoryLayoutCleanupResult.UNSAFE;
        }
        if (request.checkSwap()) {
            InventoryLayoutCleanupResult swap =
                    cleanupSkillSwap(player, request);
            if (!successfulSkillLayoutCleanup(swap)) {
                return swap;
            }
            committed |= swap
                    == InventoryLayoutCleanupResult
                            .SAFE_LAYOUT_COMMITTED;
            restored |= swap
                    == InventoryLayoutCleanupResult.RESTORED;
        }
        if (request.checkSelection()) {
            InventoryLayoutCleanupResult selection =
                    cleanupSkillSelection(player, request);
            if (!successfulSkillLayoutCleanup(
                    selection)) {
                return selection;
            }
            committed |= selection
                    == InventoryLayoutCleanupResult
                            .SAFE_LAYOUT_COMMITTED;
            restored |= selection
                    == InventoryLayoutCleanupResult.RESTORED;
        }
        if (committed) {
            return InventoryLayoutCleanupResult
                    .SAFE_LAYOUT_COMMITTED;
        }
        return restored
                ? InventoryLayoutCleanupResult.RESTORED
                : InventoryLayoutCleanupResult.ALREADY_SAFE;
    }

    private InventoryLayoutCleanupResult cleanupSkillSwap(
            BotServerPlayer player,
            InventoryLayoutCleanupRequest request) {
        ItemStackFingerprint source = inventoryItem(
                player, request.sourceInventorySlot());
        ItemStackFingerprint temporary = inventoryItem(
                player, request.temporaryHotbarSlot());
        InventoryContentsSnapshot contentsBefore =
                MinecraftInteractionView.inventoryContents(
                        player);
        InventoryLayoutCleanupPolicy.Assessment assessment =
                InventoryLayoutCleanupPolicy.assess(
                        request,
                        source,
                        temporary,
                        contentsBefore);
        switch (assessment.decision()) {
            case ALREADY_SAFE -> {
                return InventoryLayoutCleanupResult.ALREADY_SAFE;
            }
            case SAFE_LAYOUT_COMMITTED -> {
                return InventoryLayoutCleanupResult
                        .SAFE_LAYOUT_COMMITTED;
            }
            case UNSAFE -> {
                return InventoryLayoutCleanupResult.UNSAFE;
            }
            case TEMPORARY_LAYOUT -> {
                // Continue with the single exact compensating swap.
            }
        }

        try {
            player.inventoryMenu.clicked(
                    request.sourceInventorySlot(),
                    request.temporaryHotbarSlot(),
                    ClickType.SWAP,
                    player);
            player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException exception) {
            // Verify the actual post-call state below: a vanilla handler may
            // mutate before throwing.
        }

        ItemStackFingerprint sourceAfter = inventoryItem(
                player, request.sourceInventorySlot());
        ItemStackFingerprint temporaryAfter = inventoryItem(
                player, request.temporaryHotbarSlot());
        InventoryContentsSnapshot contentsAfter =
                MinecraftInteractionView.inventoryContents(
                        player);
        if (isExpectedFoodRemainder(
                        sourceAfter,
                        request.expectedFood(),
                        assessment
                                .expectedRemainderCount())
                && temporaryAfter.isEmpty()
                && contentsAfter.equals(
                        contentsBefore)) {
            return InventoryLayoutCleanupResult.RESTORED;
        }
        if (sourceAfter.equals(source)
                && temporaryAfter.equals(temporary)
                && contentsAfter.equals(
                        contentsBefore)) {
            return InventoryLayoutCleanupResult.BLOCKED;
        }
        return InventoryLayoutCleanupResult.UNSAFE;
    }

    private InventoryLayoutCleanupResult cleanupSkillSelection(
            BotServerPlayer player,
            InventoryLayoutCleanupRequest request) {
        int current = player.getInventory().selected;
        if (current == request.previousSelectedSlot()) {
            return InventoryLayoutCleanupResult.ALREADY_SAFE;
        }
        if (current != request.temporaryHotbarSlot()) {
            return InventoryLayoutCleanupResult
                    .SAFE_LAYOUT_COMMITTED;
        }
        try {
            player.connection.handleSetCarriedItem(
                    new ServerboundSetCarriedItemPacket(
                            request.previousSelectedSlot()));
        } catch (RuntimeException exception) {
            // Inspect the authoritative selected slot after a handler error.
        }
        int selectedAfter =
                player.getInventory().selected;
        if (selectedAfter
                == request.previousSelectedSlot()) {
            return InventoryLayoutCleanupResult.RESTORED;
        }
        if (selectedAfter
                == request.temporaryHotbarSlot()) {
            return InventoryLayoutCleanupResult.BLOCKED;
        }
        return InventoryLayoutCleanupResult.UNSAFE;
    }

    private static boolean successfulSkillLayoutCleanup(
            InventoryLayoutCleanupResult result) {
        return result == InventoryLayoutCleanupResult.RESTORED
                || result
                        == InventoryLayoutCleanupResult.ALREADY_SAFE
                || result
                        == InventoryLayoutCleanupResult
                                .SAFE_LAYOUT_COMMITTED;
    }

    private static ItemStackFingerprint inventoryItem(
            BotServerPlayer player, int slot) {
        return MinecraftInteractionView.itemFingerprint(
                player, player.getInventory().getItem(slot));
    }

    private static boolean isExpectedFoodRemainder(
            ItemStackFingerprint actual,
            ItemStackFingerprint expectedFood,
            int expectedCount) {
        return InventoryLayoutCleanupPolicy
                .matchesRemainder(
                        actual,
                        expectedFood,
                        expectedCount);
    }

    private BackendResult validateSpec(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec spec) {
        if (player.isDeadOrDying() || player.isSpectator()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot game mode cannot perform this interaction");
        }

        return switch (spec) {
            case WorldInteractionActionSpec.SelectHotbar selectHotbar ->
                    validateSelect(
                            envelope, player, selectHotbar);
            case WorldInteractionActionSpec.SwapInventoryHotbar swap ->
                    validateSwap(envelope, player, swap);
            case WorldInteractionActionSpec.InventoryMenuSwap menuSwap ->
                    validateMenuSwap(envelope, player, menuSwap);
            case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                    validateWorldMenuTransaction(envelope, player, menu);
            case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                    validateWorldMenuTransfer(envelope, player, menu);
            case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                    validateWorldMenuRecipe(envelope, player, menu);
            case WorldInteractionActionSpec.WorldVillagerTrade trade ->
                    validateWorldVillagerTrade(envelope, player, trade);
            case WorldInteractionActionSpec.UseItem useItem ->
                    validateHeldUse(envelope, player, useItem);
            case WorldInteractionActionSpec.ReleaseUse releaseUse ->
                    validateRelease(envelope, player, releaseUse);
            case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                    validateBlockInteraction(
                            envelope,
                            player,
                            useOnBlock.target(),
                            MinecraftInteractionView.hand(useOnBlock.hand()),
                            useOnBlock.expectedHeldItem());
            case WorldInteractionActionSpec.PlaceBlock placeBlock ->
                    validatePlaceBlock(envelope, player, placeBlock);
            case WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace ->
                    validateAimAndPlaceBlock(
                            envelope, player, aimAndPlace);
            case WorldInteractionActionSpec.BreakBlock breakBlock ->
                    validateBreakBlock(envelope, player, breakBlock);
            case WorldInteractionActionSpec.AttackEntity attackEntity ->
                    validateEntity(
                            envelope,
                            player,
                            attackEntity.target(),
                            true);
            case WorldInteractionActionSpec.InteractEntity interactEntity ->
                    validateEntity(
                            envelope,
                            player,
                            interactEntity.target(),
                            false,
                            MinecraftInteractionView.hand(
                                    interactEntity.hand()),
                            interactEntity.expectedHeldItem());
            case WorldInteractionActionSpec.DropSelected dropSelected ->
                    requireFingerprint(
                            envelope,
                            MinecraftInteractionView.itemFingerprint(
                                    player,
                                    player.getInventory().getSelected()),
                            dropSelected.expectedSelected(),
                            "Selected drop stack changed");
            case WorldInteractionActionSpec.PickupWait pickupWait ->
                    validatePickup(envelope, player, pickupWait);
        };
    }

    private BackendResult validateSelect(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.SelectHotbar selectHotbar) {
        if (player.getInventory().selected
                == selectHotbar.slot()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Requested hotbar slot is already selected");
        }
        return requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(selectHotbar.slot())),
                selectHotbar.expectedSlotItem(),
                "Selected hotbar precondition changed");
    }

    private BackendResult validateSwap(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.SwapInventoryHotbar swap) {
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot inventory menu is not the active menu");
        }
        BackendResult source = requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.sourceInventorySlot())),
                swap.expectedSource(),
                "Source inventory stack changed before swap");
        if (source.step() == BackendStep.FAILED) {
            return source;
        }
        return requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.targetHotbarSlot())),
                swap.expectedTarget(),
                "Target hotbar stack changed before swap");
    }

    private BackendResult validateMenuSwap(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap) {
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot native inventory menu is not active");
        }
        InventoryMenuSwapPlan plan = menuSwap.plan();
        if (plan.operation()
                        == InventoryMenuSwapPlan.Operation
                                .SWAP_SEQUENCE
                && plan.touchedInventorySlots().stream()
                        .anyMatch(
                                PlayerInventoryMenuLayout
                                        ::isEquipmentInventorySlot)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSUPPORTED,
                    "Generic menu sequences cannot touch equipment slots");
        }
        InventoryMenuSnapshot actual =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (!actual.equals(plan.initialSnapshot())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory menu snapshot changed before transaction");
        }
        if (!nativeCraftSlotsEmpty(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory crafting slots must be empty for a menu transaction");
        }
        if (!menuPlanHasReversiblePermissions(player, plan)) {
            return failure(
                    envelope,
                    ActionFailureCode.PERMISSION_DENIED,
                    "Inventory menu transaction is not safely reversible");
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult validateWorldMenuTransaction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldMenuTransaction menu) {
        if (menu.template().family() == MenuFamily.MERCHANT) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSUPPORTED,
                    "MerchantMenu requires the dedicated vanilla villager trade action");
        }
        if (menu.template().family() == MenuFamily.INVENTORY_2X2) {
            if (player.containerMenu != player.inventoryMenu) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Native inventory menu is not active for 2x2 transaction");
            }
            if (!MinecraftInteractionView.itemFingerprint(
                            player, player.getItemInHand(
                                    MinecraftInteractionView.hand(menu.hand())))
                    .equals(menu.expectedHeldItem())) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Native inventory held-item fingerprint changed before transaction");
            }
            Optional<MenuSnapshot> snapshot = snapshotMenu(
                    player, MenuFamily.INVENTORY_2X2);
            if (snapshot.isEmpty()
                    || menu.template().bind(snapshot.orElseThrow())
                            .isEmpty()) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Inventory menu layout changed before transaction");
            }
            return BackendResult.accepted(envelope);
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "A world menu transaction requires the native menu before opening");
        }
        BlockHitTarget opener = menu.opener().orElseThrow();
        return validateBlockInteraction(
                envelope,
                player,
                opener,
                MinecraftInteractionView.hand(menu.hand()),
                menu.expectedHeldItem());
    }

    /**
     * transfer 的模板必须在容器真正打开后从完整快照构造；打开前只验证目标方块、手持
     * 物和 native inventory 起点，绝不直接读取未打开容器。
     */
    private BackendResult validateWorldMenuTransfer(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldMenuTransfer menu) {
        if (menu.family() == MenuFamily.MERCHANT) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSUPPORTED,
                    "MerchantMenu cannot use the generic world transfer action");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "A world menu transfer requires the native menu before opening");
        }
        if (!isAllowedVanillaContainerTarget(
                menu.opener().target(), menu.family())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "World menu transfer target is outside the P5B vanilla container whitelist");
        }
        if (isEnderChestTarget(
                        menu.opener().target().state().blockId().value(),
                        menu.opener().target().state().properties())
                && !isExactEnderChestBlockEntity(player, menu.opener().target())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Ender chest transfer target has no exact vanilla block entity");
        }
        return validateBlockInteraction(
                envelope,
                player,
                menu.opener(),
                MinecraftInteractionView.hand(menu.hand()),
                menu.expectedHeldItem());
    }

    /**
     * 配方 action 在打开前只相信玩家当前 native menu、精确命中的原版方块和手持指纹；
     * source stack、grid/cursor 和 furnace 状态都必须在窗口真正打开后再观察。
     */
    private BackendResult validateWorldMenuRecipe(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldMenuRecipe menu) {
        if (menu.recipe().family() == MenuFamily.INVENTORY_2X2) {
            if (player.containerMenu != player.inventoryMenu) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Native inventory menu is not active for inventory recipe");
            }
            if (!MinecraftInteractionView.itemFingerprint(
                            player,
                            player.getItemInHand(
                                    MinecraftInteractionView.hand(menu.hand())))
                    .equals(menu.expectedHeldItem())) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Inventory recipe held-item fingerprint changed before transaction");
            }
            return snapshotMenu(player, MenuFamily.INVENTORY_2X2)
                            .isPresent()
                    ? BackendResult.accepted(envelope)
                    : failure(
                            envelope,
                            ActionFailureCode.PRECONDITION_FAILED,
                            "Inventory recipe layout changed before transaction");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "A world recipe requires the native menu before opening");
        }
        BlockHitTarget opener = menu.opener().orElseThrow();
        if (!isAllowedVanillaWorkstationTarget(
                opener.target(), menu.recipe())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "World recipe target is outside the strict vanilla workstation whitelist");
        }
        return validateBlockInteraction(
                envelope,
                player,
                opener,
                MinecraftInteractionView.hand(menu.hand()),
                menu.expectedHeldItem());
    }

    /**
     * 交易不是泛用 {@code Merchant} 自动化。开始前就必须同时证明：空 native 背包 cursor、
     * 精确原版 {@link Villager}、冻结 offer/等级、空主手，以及能让 result QUICK_MOVE 落到唯一
     * output 格的完整 36 格玩家布局。任何一项不能证明都会在调用原版 interaction packet 前失败。
     */
    private BackendResult validateWorldVillagerTrade(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldVillagerTrade trade) {
        if (!exactNativeInventoryMenuHasEmptyCursor(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager trade requires the native inventory menu with an empty cursor");
        }
        if (!MinecraftInteractionView.itemFingerprint(
                        player, player.getMainHandItem())
                .isEmpty()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager trade requires an empty main hand");
        }
        Entity resolved = MinecraftInteractionView.entity(
                        player, trade.villager())
                .orElse(null);
        if (resolved == null || resolved.getClass() != Villager.class) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Villager trade target is not the exact vanilla Villager class");
        }
        Villager villager = (Villager) resolved;
        if (!player.canInteractWithEntity(villager, 1.0D)
                || !player.hasLineOfSight(villager)
                || villager.getTradingPlayer() != null) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager trade target is unavailable, out of reach, or already trading");
        }
        MerchantOffer offer = merchantOfferAt(villager, trade.offerIndex())
                .orElse(null);
        if (offer == null) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager offer is unavailable before trade");
        }
        MerchantOfferState offerState = merchantOfferStaticState(
                player, offer).orElse(null);
        if (offerState == null
                || !merchantOfferMatches(player, villager, offer, trade,
                        trade.expectedOfferUses(), trade.expectedVillagerXp(),
                        true)
                || villagerTradeWouldChangeLevel(
                        villager, offerState,
                        trade.expectedVillagerXp())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager offer, price, stock, XP, or level changed before trade");
        }
        if (!strictVillagerTradeInventoryLayout(player, trade)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager trade player inventory layout is not uniquely auditable");
        }
        return BackendResult.accepted(envelope);
    }

    private static Optional<MerchantOffer> merchantOfferAt(
            Villager villager, int offerIndex) {
        Objects.requireNonNull(villager, "villager");
        if (offerIndex < 0 || offerIndex >= villager.getOffers().size()) {
            return Optional.empty();
        }
        MerchantOffer offer = villager.getOffers().get(offerIndex);
        return offer == null || offer.getClass() != MerchantOffer.class
                ? Optional.empty() : Optional.of(offer);
    }

    /**
     * 确认当前 offer 仍是动作冻结的单支付、可用原版 offer。uses 是单独参数，因为最后一次
     * QUICK_MOVE 后它恰好允许加一；其余字段在整个会话内绝不允许漂移。
     */
    private static boolean merchantOfferMatches(
            BotServerPlayer player,
            Villager villager,
            MerchantOffer offer,
            WorldInteractionActionSpec.WorldVillagerTrade trade,
            int expectedUses,
            int expectedVillagerXp,
            boolean requireInStock) {
        try {
            return villager.getVillagerData().getLevel()
                            == trade.expectedVillagerLevel()
                    && villager.getVillagerXp() == expectedVillagerXp
                    && (!requireInStock || !offer.isOutOfStock())
                    && offer.getUses() == expectedUses
                    && offer.getMaxUses() == trade.expectedOfferMaxUses()
                    && MinecraftInteractionView.itemFingerprint(
                                    player, offer.getCostA())
                            .equals(trade.expectedCost())
                    && MinecraftInteractionView.itemFingerprint(
                                    player, offer.getCostB())
                            .isEmpty()
                    && MinecraftInteractionView.itemFingerprint(
                                    player, offer.getResult())
                            .equals(trade.expectedResult())
                    && merchantOfferStaticState(player, offer)
                            .filter(trade.expectedOfferState()::equals)
                            .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * The narrow slice intentionally refuses a trade that can level the villager. The real
     * {@code MerchantMenu} trade still grants its normal XP below the threshold, but refusing a
     * level transition keeps the frozen offer list and level contract stable through close and
     * verification.
     */
    private static boolean villagerTradeWouldChangeLevel(
            Villager villager,
            MerchantOfferState offer,
            int expectedVillagerXp) {
        try {
            VillagerData data = villager.getVillagerData();
            int level = data.getLevel();
            if (!VillagerData.canLevelUp(level)) {
                return false;
            }
            int threshold = VillagerData.getMinXpPerLevel(level + 1);
            int xpAfter = villagerXpAfterTrade(
                    expectedVillagerXp, offer).orElseThrow();
            return expectedVillagerXp >= threshold || xpAfter >= threshold;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static Optional<Integer> villagerXpAfterTrade(
            int xpBefore, MerchantOfferState offer) {
        Objects.requireNonNull(offer, "offer");
        if (xpBefore < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Math.addExact(
                    xpBefore, offer.rewardsExperience() ? offer.xp() : 0));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static Optional<MerchantOfferState> merchantOfferStaticState(
            BotServerPlayer player, MerchantOffer offer) {
        try {
            ItemStackFingerprint baseCost = MinecraftInteractionView
                    .itemFingerprint(player, offer.getBaseCostA());
            float priceMultiplier = offer.getPriceMultiplier();
            int xp = offer.getXp();
            if (baseCost.isEmpty()
                    || !Float.isFinite(priceMultiplier)
                    || priceMultiplier < 0.0F
                    || xp < 0) {
                return Optional.empty();
            }
            return Optional.of(new MerchantOfferState(
                    baseCost,
                    offer.getDemand(),
                    offer.getSpecialPriceDiff(),
                    Float.floatToIntBits(priceMultiplier),
                    xp,
                    offer.shouldRewardExp()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 原版 MerchantMenu 的 result QUICK_MOVE 先尝试合并、再从末尾向前寻找空 player slot。
     * 这里只留下一个指定空格，且禁止其他格出现相同 result identity，因此没有模糊落点。
     */
    private static boolean strictVillagerTradeInventoryLayout(
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldVillagerTrade trade) {
        try {
            for (int inventorySlot =
                    WorldInteractionActionSpec.WorldVillagerTrade
                            .FIRST_PLAYER_INVENTORY_SLOT;
                    inventorySlot <= WorldInteractionActionSpec
                            .WorldVillagerTrade
                            .LAST_PLAYER_INVENTORY_SLOT;
                    inventorySlot++) {
                ItemStackFingerprint current =
                        MinecraftInteractionView.itemFingerprint(
                                player,
                                player.getInventory().getItem(inventorySlot));
                if (inventorySlot == trade.sourceInventorySlot()) {
                    if (!current.equals(trade.expectedSource())) {
                        return false;
                    }
                    continue;
                }
                if (inventorySlot == trade.outputInventorySlot()) {
                    if (!current.isEmpty()) {
                        return false;
                    }
                    continue;
                }
                if (current.isEmpty()
                        || current.sameItemAndComponents(
                                trade.expectedResult())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean menuPlanHasReversiblePermissions(
            BotServerPlayer player, InventoryMenuSwapPlan plan) {
        InventoryMenuSnapshot initial = plan.initialSnapshot();
        for (int inventorySlot : plan.touchedInventorySlots()) {
            Slot slot = player.inventoryMenu.getSlot(
                    PlayerInventoryMenuLayout
                            .menuSlotForInventorySlot(inventorySlot));
            ItemStack original =
                    player.getInventory().getItem(inventorySlot);
            if (!original.isEmpty()
                    && (!slot.mayPickup(player)
                            || !mayPlaceCompleteStack(
                                    slot, original))) {
                return false;
            }
        }
        for (InventoryMenuClickStep step : plan.orderedSteps()) {
            Slot clicked =
                    player.inventoryMenu.getSlot(step.menuSlot());
            Slot hotbar = player.inventoryMenu.getSlot(
                    PlayerInventoryMenuLayout
                            .menuSlotForInventorySlot(
                                    step.hotbarButton()));
            ItemStack clickedIncoming = stackForFingerprint(
                    player,
                    initial,
                    step.before().itemAt(step.hotbarButton()));
            ItemStack hotbarIncoming = stackForFingerprint(
                    player,
                    initial,
                    step.before().itemAt(
                            step.clickedInventorySlot()));
            ItemStack clickedOriginal = stackForFingerprint(
                    player,
                    initial,
                    step.before().itemAt(
                            step.clickedInventorySlot()));
            ItemStack hotbarOriginal = stackForFingerprint(
                    player,
                    initial,
                    step.before().itemAt(
                            step.hotbarButton()));
            if (clickedIncoming == null
                    || hotbarIncoming == null
                    || clickedOriginal == null
                    || hotbarOriginal == null
                    || !mayPlaceCompleteStack(
                            clicked, clickedIncoming)
                    || !mayPlaceCompleteStack(
                            hotbar, hotbarIncoming)
                    || !mayPlaceCompleteStack(
                            clicked, clickedOriginal)
                    || !mayPlaceCompleteStack(
                            hotbar, hotbarOriginal)) {
                return false;
            }
        }
        if ((plan.operation()
                                == InventoryMenuSwapPlan.Operation
                                        .MAIN_TO_EQUIPMENT
                        || plan.operation()
                                == InventoryMenuSwapPlan.Operation
                                        .HOTBAR_TO_EQUIPMENT)
                && !player.isCreative()) {
            int equipmentSlot = plan.orderedSteps().stream()
                    .mapToInt(
                            InventoryMenuClickStep::
                                    clickedInventorySlot)
                    .filter(
                            PlayerInventoryMenuLayout::
                                    isArmorInventorySlot)
                    .findFirst()
                    .orElse(-1);
            if (equipmentSlot >= 0) {
                ItemStack equipped =
                        stackForFingerprint(
                                player,
                                initial,
                                plan.finalSnapshot()
                                        .itemAt(equipmentSlot));
                if (equipped == null
                        || EnchantmentHelper.has(
                                equipped,
                                EnchantmentEffectComponents
                                        .PREVENT_ARMOR_CHANGE)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean mayPlaceCompleteStack(
            Slot slot, ItemStack stack) {
        return stack.isEmpty()
                || (slot.mayPlace(stack)
                        && stack.getCount()
                                <= Math.min(
                                        slot.getMaxStackSize(),
                                        stack.getMaxStackSize()));
    }

    private static ItemStack stackForFingerprint(
            BotServerPlayer player,
            InventoryMenuSnapshot initial,
            ItemStackFingerprint fingerprint) {
        if (fingerprint.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (int inventorySlot = 0;
                inventorySlot
                        < PlayerInventoryMenuLayout
                                .INVENTORY_SLOT_COUNT;
                inventorySlot++) {
            if (initial.itemAt(inventorySlot).equals(fingerprint)) {
                return player.getInventory()
                        .getItem(inventorySlot)
                        .copy();
            }
        }
        return null;
    }

    private static boolean nativeCraftSlotsEmpty(
            BotServerPlayer player) {
        for (int menuSlot = 0; menuSlot <= 4; menuSlot++) {
            if (!player.inventoryMenu
                    .getSlot(menuSlot)
                    .getItem()
                    .isEmpty()) {
                return false;
            }
        }
        return player.inventoryMenu.getCarried().isEmpty();
    }

    private BackendResult validateHeldUse(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.UseItem useItem) {
        if (player.isUsingItem()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot is already using an item");
        }
        ItemStack stack =
                player.getItemInHand(
                        MinecraftInteractionView.hand(useItem.hand()));
        BackendResult fingerprint = requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(player, stack),
                useItem.expectedHeldItem(),
                "Held item changed before use");
        if (fingerprint.step() == BackendStep.FAILED) {
            return fingerprint;
        }
        if (!stack.isEmpty()
                && player.getCooldowns().isOnCooldown(stack.getItem())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Held item cooldown is still active");
        }
        return useItem.strictPreconditions()
                .map(preconditions -> validateUseItemPreconditions(
                        envelope, player, preconditions))
                .orElseGet(() -> BackendResult.accepted(envelope));
    }

    /**
     * Strict item users freeze the native menu and the complete bounded active
     * effect identity set. This check is intentionally run by {@link #validate},
     * again from {@link #start} immediately before {@code handleUseItem}, and
     * while a strict natural use remains active. A request which drifted while
     * queued never reaches the vanilla packet path; a state which drifts during
     * a multi-tick use is released before vanilla can consume an unapproved
     * effect or inventory state.
     */
    private BackendResult validateUseItemPreconditions(
            ActionEnvelope envelope,
            BotServerPlayer player,
            UseItemPreconditions expected) {
        try {
            InventoryMenu nativeMenu = player.inventoryMenu;
            if (player.containerMenu == nativeMenu
                    && nativeMenu.getClass() == InventoryMenu.class
                    && nativeMenu.stillValid(player)
                    && nativeMenu.slots.size()
                            == MenuFamily.INVENTORY_2X2.slotCount()
                    && nativeMenu.getCarried().isEmpty()) {
                return validateExactUseItemPreconditions(
                        envelope, player, expected);
            }
        } catch (RuntimeException exception) {
            // A broken or replaced menu is never a safe strict-use boundary.
        }
        return failure(
                envelope,
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                "Strict item use requires an exact valid native inventory menu with an empty cursor");
    }

    private BackendResult validateExactUseItemPreconditions(
            ActionEnvelope envelope,
            BotServerPlayer player,
            UseItemPreconditions expected) {
        InventoryMenuSnapshot actualMenu;
        try {
            actualMenu = MinecraftActionSnapshot.inventoryMenu(player);
        } catch (RuntimeException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Strict item use could not capture the native inventory menu");
        }
        if (!expected.nativeInventoryMenu().equals(actualMenu)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Strict item use inventory menu precondition changed");
        }
        Optional<List<ActiveEffectObservation>> actualEffects =
                activeEffectObservations(player);
        if (actualEffects.isEmpty()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Strict item use active-effect snapshot is unavailable");
        }
        if (!expected.matchesActiveEffects(actualEffects.orElseThrow())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Strict item use active-effect precondition changed");
        }
        return BackendResult.accepted(envelope);
    }

    /**
     * Equivalent to the strict portion of {@link #tickUse}, but deliberately
     * side-effect free so it is safe to call from the native-use mixin hook.
     */
    private static boolean strictUseStillMatches(
            BotServerPlayer player,
            WorldInteractionActionSpec.UseItem useItem) {
        try {
            if (!player.isUsingItem()
                    || player.getUsedItemHand()
                            != MinecraftInteractionView.hand(useItem.hand())
                    || !MinecraftInteractionView.itemFingerprint(
                                    player, player.getUseItem())
                            .sameItemAndComponents(
                                    useItem.expectedHeldItem())) {
                return false;
            }
            UseItemPreconditions expected =
                    useItem.strictPreconditions().orElseThrow();
            InventoryMenu nativeMenu = player.inventoryMenu;
            if (player.containerMenu != nativeMenu
                    || nativeMenu.getClass() != InventoryMenu.class
                    || !nativeMenu.stillValid(player)
                    || nativeMenu.slots.size()
                            != MenuFamily.INVENTORY_2X2.slotCount()
                    || !nativeMenu.getCarried().isEmpty()) {
                return false;
            }
            if (!expected.nativeInventoryMenu().equals(
                    MinecraftActionSnapshot.inventoryMenu(player))) {
                return false;
            }
            Optional<List<ActiveEffectObservation>> effects =
                    activeEffectObservations(player);
            return effects.isPresent()
                    && expected.matchesActiveEffects(effects.orElseThrow());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Converts live effects only at the server-thread backend boundary. Any
     * unregistered, malformed, duplicated or over-limit effect makes the caller
     * reject the action rather than serializing an ambiguous mutable object.
     */
    private static Optional<List<ActiveEffectObservation>>
            activeEffectObservations(BotServerPlayer player) {
        List<ActiveEffectObservation> effects = new ArrayList<>();
        try {
            for (MobEffectInstance effect : player.getActiveEffects()) {
                if (effects.size()
                        >= UseItemPreconditions.MAXIMUM_ACTIVE_EFFECTS) {
                    return Optional.empty();
                }
                net.minecraft.resources.ResourceLocation key =
                        BuiltInRegistries.MOB_EFFECT.getKey(
                                effect.getEffect().value());
                if (key == null) {
                    return Optional.empty();
                }
                int duration = effect.getDuration();
                if (duration < 0) {
                    return Optional.empty();
                }
                effects.add(new ActiveEffectObservation(
                        new ResourceId(key.toString()),
                        effect.getAmplifier(),
                        duration,
                        effect.isAmbient(),
                        effect.isVisible()));
            }
            effects.sort(null);
            for (int index = 1; index < effects.size(); index++) {
                if (effects.get(index - 1).effectId().equals(
                        effects.get(index).effectId())) {
                    return Optional.empty();
                }
            }
            return Optional.of(List.copyOf(effects));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }


    private BackendResult validateRelease(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.ReleaseUse releaseUse) {
        BackendResult fingerprint = requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getItemInHand(
                                MinecraftInteractionView.hand(
                                        releaseUse.hand()))),
                releaseUse.expectedUseItem(),
                "Used item changed before release");
        if (fingerprint.step() == BackendStep.FAILED) {
            return fingerprint;
        }
        if (!player.isUsingItem()
                || player.getUsedItemHand()
                        != MinecraftInteractionView.hand(releaseUse.hand())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot is not using the declared hand");
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult validateBlockInteraction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            BlockHitTarget target,
            InteractionHand hand,
            ItemStackFingerprint expectedHeld) {
        if (!MinecraftInteractionView.dimension(player)
                .equals(target.target().dimension())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block target dimension changed");
        }
        BlockPos position =
                MinecraftInteractionView.position(
                        target.target().position());
        if (!player.serverLevel().isLoaded(position)) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Block target chunk is not loaded");
        }
        if (!MinecraftInteractionView.blockFingerprint(player, position)
                .equals(target.target())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block target fingerprint changed");
        }

        MinecraftInteractionView.BlockReachEvidence reach =
                MinecraftInteractionView.blockReachEvidence(player, target);
        if (!reach.withinReach() || !reach.rayHitTarget()) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    blockReachEvidence(reach),
                    !reach.withinReach()
                            ? "Block target is out of reach"
                            : "Block target line of sight is blocked");
        }

        BackendResult held = requireFingerprint(
                envelope,
                MinecraftInteractionView.itemFingerprint(
                        player, player.getItemInHand(hand)),
                expectedHeld,
                "Held item changed before block interaction");
        if (held.step() == BackendStep.FAILED) {
            return held;
        }
        return BackendResult.accepted(envelope);
    }

    /**
     * Validates the legacy source/hand fence and any optional face-adjacent
     * world-state fences declared by a strict block-break request.
     *
     * <p>Some vanilla blocks have structural neighbours whose change can widen a
     * single source break into a cascade.  Those callers must not rely on an
     * earlier skill-layer observation alone: the action boundary rechecks the
     * frozen neighbours immediately before {@code START_DESTROY_BLOCK}, on every
     * mining tick, and again before success is reported.
     */
    private BackendResult validateBreakBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.BreakBlock breakBlock) {
        BackendResult target = validateBlockInteraction(
                envelope,
                player,
                breakBlock.target(),
                InteractionHand.MAIN_HAND,
                breakBlock.expectedTool());
        if (target.step() != BackendStep.ACCEPTED) {
            return target;
        }
        return validateBreakNeighborPreconditions(envelope, player,
                breakBlock);
    }

    private BackendResult validateBreakNeighborPreconditions(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.BreakBlock breakBlock) {
        for (BlockTargetFingerprint expected : breakBlock
                .neighborPreconditions()) {
            BlockPos position = MinecraftInteractionView.position(
                    expected.position());
            if (!player.serverLevel().isLoaded(position)) {
                return failure(
                        envelope,
                        ActionFailureCode.TARGET_UNAVAILABLE,
                        "Break neighbor chunk is not loaded");
            }
            if (!MinecraftInteractionView.blockFingerprint(player, position)
                    .equals(expected)) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Break neighbor fingerprint changed");
            }
        }
        return BackendResult.accepted(envelope);
    }

    /**
     * 生产工作站放置不能借用泛用 {@code UseOnBlock} 的“任意可观察变化”语义。锚点、空目标、
     * 主手方块物品和预期原版 block id 都必须在 packet 前再次成立；完整 placement state 则在
     * {@link #verifyPlaceBlock(ActionEnvelope, BotServerPlayer, InteractionState,
     * WorldInteractionActionSpec.PlaceBlock)} 中核验。
     */
    private BackendResult validatePlaceBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.PlaceBlock placeBlock) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.stillValid(player)
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement requires the native inventory menu with an empty cursor");
        }
        BackendResult anchor = validateBlockInteraction(
                envelope,
                player,
                placeBlock.anchor(),
                InteractionHand.MAIN_HAND,
                placeBlock.expectedHeldItem());
        if (anchor.step() != BackendStep.ACCEPTED) {
            return anchor;
        }
        BlockPos destination = MinecraftInteractionView.position(
                placeBlock.expectedPlaced().position());
        if (!player.serverLevel().isLoaded(destination)) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Block placement destination chunk is not loaded");
        }
        if (!player.serverLevel().getBlockState(destination).isAir()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement destination is not strictly air");
        }
        if (!player.canInteractWithBlock(destination, 0.0D)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement destination is out of reach");
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            return failure(
                    envelope,
                    ActionFailureCode.INVALID_REQUEST,
                    "Block placement held item is not a block item");
        }
        ResourceId heldBlockId = new ResourceId(BuiltInRegistries.BLOCK
                .getKey(blockItem.getBlock()).toString());
        if (!heldBlockId.equals(placeBlock.expectedPlaced().state()
                .blockId())) {
            return failure(
                    envelope,
                    ActionFailureCode.INVALID_REQUEST,
                    "Block placement expected state does not match the held block item");
        }
        if (!matchesFrozenFurnacePlacementFacing(
                player, placeBlock.expectedPlaced())) {
            /*
             * A furnace's complete state is derived from the vanilla placement
             * context, including the player's horizontal facing.  P5A freezes
             * that state before enqueueing its old PlaceBlock action, so a
             * changed facing must be rejected before the native packet can
             * consume an item and leave a differently-oriented workstation.
             * This is deliberately limited to the reviewed unlit furnace
             * states; generic PlaceBlock keeps its existing contract.
             */
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(evidence(
                            WorldInteractionActionSpec.PlaceBlock
                                    .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_KEY,
                            WorldInteractionActionSpec.PlaceBlock
                                    .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_VALUE)),
                    "Block placement facing changed before native dispatch");
        }
        return BackendResult.accepted(envelope);
    }

    private static boolean matchesFrozenFurnacePlacementFacing(
            BotServerPlayer player, BlockTargetFingerprint expectedPlaced) {
        BlockStateFingerprint expectedState = expectedPlaced.state();
        if (!"false".equals(expectedState.properties().get("lit"))) {
            return true;
        }
        boolean reviewedFurnace = false;
        for (FurnaceKind furnaceKind : FurnaceKind.values()) {
            if (furnaceKind.matchesWorkstationState(expectedState)) {
                reviewedFurnace = true;
                break;
            }
        }
        return !reviewedFurnace
                || expectedState.properties().get("facing").equals(
                        player.getDirection().getOpposite()
                                .getSerializedName());
    }

    /**
     * Atomic aim-and-place never inherits {@link WorldInteractionActionSpec.PlaceBlock}'s broad
     * {@code isAir()} destination test. The whole frozen {@code targetBefore} fingerprint is
     * checked by validate, start revalidation, and the post-look packet fence.
     */
    private BackendResult validateAimAndPlaceBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.stillValid(player)
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Atomic placement requires the native inventory menu with an empty cursor");
        }
        BackendResult anchor = validateBlockInteraction(
                envelope,
                player,
                aimAndPlace.anchor(),
                InteractionHand.MAIN_HAND,
                aimAndPlace.expectedHeldItem());
        if (anchor.step() != BackendStep.ACCEPTED) {
            return anchor;
        }
        MinecraftInteractionView.BlockReachEvidence exactReach =
                MinecraftInteractionView.exactBlockReachEvidence(
                        player, aimAndPlace.anchor());
        if (!exactReach.withinReach() || !exactReach.rayHitTarget()) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    blockReachEvidence(exactReach),
                    !exactReach.withinReach()
                            ? "Atomic placement anchor is out of reach"
                            : "Atomic placement anchor does not exactly match the frozen face and hit");
        }
        BlockPos destination = MinecraftInteractionView.position(
                aimAndPlace.targetBefore().position());
        if (!player.serverLevel().isLoaded(destination)) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Atomic placement destination chunk is not loaded");
        }
        if (!MinecraftInteractionView.blockFingerprint(player, destination)
                .equals(aimAndPlace.targetBefore())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Atomic placement exact targetBefore fingerprint changed");
        }
        if (!player.canInteractWithBlock(destination, 0.0D)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Atomic placement destination is out of reach");
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            return failure(
                    envelope,
                    ActionFailureCode.INVALID_REQUEST,
                    "Atomic placement held item is not a block item");
        }
        ResourceId heldBlockId = new ResourceId(BuiltInRegistries.BLOCK
                .getKey(blockItem.getBlock()).toString());
        if (!heldBlockId.equals(aimAndPlace.expectedPlaced().state()
                .blockId())) {
            return failure(
                    envelope,
                    ActionFailureCode.INVALID_REQUEST,
                    "Atomic placement expected state does not match the held block item");
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult validateEntity(
            ActionEnvelope envelope,
            BotServerPlayer player,
            EntityTargetFingerprint target,
            boolean attack) {
        return validateEntity(
                envelope,
                player,
                target,
                attack,
                InteractionHand.MAIN_HAND,
                Optional.empty());
    }

    /**
     * Validate the declared hand before resolving or dispatching an entity
     * interaction.  The strict check is intentionally repeated by
     * {@link #start(ActionEnvelope, long)} for {@code INTERACT_ENTITY}, closing
     * the mailbox-to-packet gap for item-consuming interactions.
     */
    private BackendResult validateEntity(
            ActionEnvelope envelope,
            BotServerPlayer player,
            EntityTargetFingerprint target,
            boolean attack,
            InteractionHand hand,
            Optional<ItemStackFingerprint> expectedHeldItem) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
        if (expectedHeldItem.isPresent()) {
            BackendResult held = requireFingerprint(
                    envelope,
                    MinecraftInteractionView.itemFingerprint(
                            player, player.getItemInHand(hand)),
                    expectedHeldItem.orElseThrow(),
                    "Held item changed before entity interaction");
            if (held.step() != BackendStep.ACCEPTED) {
                return held;
            }
        }
        Optional<Entity> entity =
                MinecraftInteractionView.entity(player, target);
        if (entity.isEmpty()) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Entity target is unavailable");
        }
        Entity resolved = entity.orElseThrow();
        if (!player.canInteractWithEntity(resolved, 1.0D)
                || !player.hasLineOfSight(resolved)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Entity target is out of reach or sight");
        }
        if (attack && player.getAttackStrengthScale(0.5F) < 1.0F) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Attack cooldown has not recovered");
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult validatePickup(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.PickupWait pickupWait) {
        if (pickupWait.expectedItemEntityIds().isEmpty()) {
            return BackendResult.accepted(envelope);
        }
        for (UUID entityId : pickupWait.expectedItemEntityIds()) {
            Entity entity = player.serverLevel().getEntity(entityId);
            if (!(entity instanceof ItemEntity itemEntity)
                    || itemEntity.isRemoved()) {
                return failure(
                        envelope,
                        ActionFailureCode.TARGET_UNAVAILABLE,
                        "Expected item entity is unavailable");
            }
            if (!player.canInteractWithEntity(itemEntity, 1.0D)) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Expected item entity is out of pickup reach");
            }
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult dispatchStart(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state) {
        if (state.spec instanceof WorldInteractionActionSpec
                .BreakBlock breakBlock) {
            return dispatchBreakStartWithDropProvenance(envelope, player,
                    state, breakBlock);
        }
        if (state.spec instanceof WorldInteractionActionSpec
                .AimAndPlaceBlock aimAndPlace) {
            return dispatchAimAndPlaceBlock(
                    envelope, player, state, aimAndPlace);
        }
        // Mark the operation as possibly applied before calling a packet/menu
        // entry point: handlers may mutate state and then throw.
        state.sideEffectDispatched = true;
        switch (state.spec) {
            case WorldInteractionActionSpec.SelectHotbar selectHotbar ->
                    player.connection.handleSetCarriedItem(
                            new ServerboundSetCarriedItemPacket(
                                    selectHotbar.slot()));
            case WorldInteractionActionSpec.SwapInventoryHotbar swap -> {
                // 原版 InventoryMenu 的主背包 menu slot 9..35 与 Inventory 索引一致。
                player.inventoryMenu.clicked(
                        swap.sourceInventorySlot(),
                        swap.targetHotbarSlot(),
                        ClickType.SWAP,
                        player);
                player.inventoryMenu.broadcastChanges();
            }
            case WorldInteractionActionSpec.InventoryMenuSwap menuSwap ->
                    dispatchMenuSwap(player, state, menuSwap);
            case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                    dispatchWorldMenuTransaction(player, state, menu);
            case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                    dispatchWorldMenuTransfer(player, state, menu);
            case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                    dispatchWorldMenuRecipe(player, state, menu);
            case WorldInteractionActionSpec.WorldVillagerTrade trade ->
                    dispatchWorldVillagerTrade(player, state, trade);
            case WorldInteractionActionSpec.UseItem useItem ->
                    dispatchUseItem(player, useItem);
            case WorldInteractionActionSpec.ReleaseUse ignored ->
                    dispatchReleaseUse(player);
            case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                    player.connection.handleUseItemOn(
                            new ServerboundUseItemOnPacket(
                                    MinecraftInteractionView.hand(
                                            useOnBlock.hand()),
                                    MinecraftInteractionView.hit(
                                            useOnBlock.target()),
                                    nextSequence()));
            case WorldInteractionActionSpec.PlaceBlock placeBlock ->
                    player.connection.handleUseItemOn(
                            new ServerboundUseItemOnPacket(
                                    InteractionHand.MAIN_HAND,
                                    MinecraftInteractionView.hit(
                                            placeBlock.anchor()),
                                    nextSequence()));
            case WorldInteractionActionSpec.AimAndPlaceBlock ignored ->
                    throw new IllegalStateException(
                            "Atomic aim-and-place bypassed the final packet fence");
            case WorldInteractionActionSpec.BreakBlock ignored ->
                    throw new IllegalStateException(
                            "Break start escaped drop-provenance capture");
            case WorldInteractionActionSpec.AttackEntity attackEntity -> {
                Entity target = MinecraftInteractionView.entity(
                                player, attackEntity.target())
                        .orElseThrow();
                player.connection.handleInteract(
                        ServerboundInteractPacket.createAttackPacket(
                                target,
                                player.isSecondaryUseActive()));
            }
            case WorldInteractionActionSpec.InteractEntity interactEntity -> {
                Entity target = MinecraftInteractionView.entity(
                                player, interactEntity.target())
                        .orElseThrow();
                InteractionHand hand =
                        MinecraftInteractionView.hand(interactEntity.hand());
                ServerboundInteractPacket packet =
                        interactEntity.localHit()
                                .map(hit ->
                                        ServerboundInteractPacket
                                                .createInteractionPacket(
                                                        target,
                                                        player.isSecondaryUseActive(),
                                                        hand,
                                                        new Vec3(
                                                                hit.x(),
                                                                hit.y(),
                                                                hit.z())))
                                .orElseGet(() ->
                                        ServerboundInteractPacket
                                                .createInteractionPacket(
                                                        target,
                                                        player.isSecondaryUseActive(),
                                                        hand));
                player.connection.handleInteract(packet);
            }
            case WorldInteractionActionSpec.DropSelected dropSelected ->
                    player.connection.handlePlayerAction(
                            new ServerboundPlayerActionPacket(
                                    dropSelected.entireStack()
                                            ? ServerboundPlayerActionPacket
                                                    .Action.DROP_ALL_ITEMS
                                            : ServerboundPlayerActionPacket
                                                    .Action.DROP_ITEM,
                                    BlockPos.ZERO,
                                    Direction.DOWN,
                                    nextSequence()));
            case WorldInteractionActionSpec.PickupWait ignored -> {
                // Pickup remains a normal collision-driven player behavior.
            }
        }
        state.startedUsing =
                state.spec
                                instanceof WorldInteractionActionSpec
                                        .UseItem useItem
                        && player.isUsingItem()
                        && player.getUsedItemHand()
                                == MinecraftInteractionView.hand(
                                        useItem.hand())
                        && MinecraftInteractionView.itemFingerprint(
                                        player, player.getUseItem())
                                .equals(useItem.expectedHeldItem());
        return BackendResult.accepted(envelope);
    }

    /**
     * The action already owns LOOK, MAIN_HAND, and INTERACT. Revalidate once after capture and
     * once after {@code lookAt}; only then mark and send the native packet.
     */
    private BackendResult dispatchAimAndPlaceBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace) {
        BackendResult capturedFence = validateAimAndPlaceBlock(
                envelope, player, aimAndPlace);
        if (capturedFence.step() != BackendStep.ACCEPTED) {
            return capturedFence;
        }

        BlockHitTarget anchor = aimAndPlace.anchor();
        state.aimAndPlaceBeforeAimError = viewAngleDegrees(
                player,
                anchor.worldX(),
                anchor.worldY(),
                anchor.worldZ());
        AimAndPlaceFinalFenceResult finalFence =
                executeAimAndPlaceFinalFence(
                        () -> player.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                new Vec3(
                                        anchor.worldX(),
                                        anchor.worldY(),
                                        anchor.worldZ())),
                        () -> validateAimAndPlaceBlock(
                                envelope, player, aimAndPlace),
                        () -> viewAngleDegrees(
                                player,
                                anchor.worldX(),
                                anchor.worldY(),
                                anchor.worldZ()),
                        AIM_AND_PLACE_LOOK_TOLERANCE_DEGREES,
                        finalAimError -> {
                            state.aimAndPlaceFinalFencePassed = true;
                            state.aimAndPlaceDispatchAimError = finalAimError;
                            // Native packet handling may synchronously mutate the world.
                            state.sideEffectDispatched = true;
                            player.connection.handleUseItemOn(
                                    new ServerboundUseItemOnPacket(
                                            InteractionHand.MAIN_HAND,
                                            MinecraftInteractionView.hit(anchor),
                                            nextSequence()));
                        });
        return switch (finalFence.status()) {
            case REVALIDATION_REJECTED -> finalFence.revalidation();
            case AIM_REJECTED -> failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Atomic placement view does not precisely target the frozen hit");
            case PACKET_DISPATCHED -> BackendResult.accepted(envelope);
        };
    }

    /**
     * Vanilla {@code instabreak()} blocks can complete from START_DESTROY_BLOCK,
     * before {@link #tickBreak(ActionEnvelope, BotServerPlayer,
     * InteractionState, WorldInteractionActionSpec.BreakBlock, long)} reaches
     * STOP_DESTROY_BLOCK. Capture this one synchronous packet as well, so a
     * verified instant break cannot lose its exact native drop receipt.
     */
    private BackendResult dispatchBreakStartWithDropProvenance(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.BreakBlock breakBlock) {
        BreakDropProvenanceCapture.Scope capture;
        try {
            capture = BreakDropProvenanceCapture.arm(player,
                    breakBlock.target().target(), state.botGeneration);
        } catch (RuntimeException exception) {
            return failure(envelope, ActionFailureCode.INTERNAL_ERROR,
                    "Could not arm exact vanilla block-drop provenance");
        }
        try {
            // Mark immediately before the packet: it may break an instabreak
            // source synchronously and then throw from a listener.
            state.sideEffectDispatched = true;
            dispatchBreak(player, breakBlock,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        } finally {
            capture.close();
            state.breakDropProvenances = capture.provenances();
        }
        return BackendResult.accepted(envelope);
    }

    private void dispatchMenuSwap(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap) {
        state.menuTransaction =
                Objects.requireNonNull(
                                state.menuTransaction,
                                "menuTransaction")
                        .start();
        if (state.menuTransaction.state()
                != InventoryMenuTransactionState.RUNNING) {
            throw new IllegalStateException(
                    "Inventory menu transaction did not start");
        }
        applyNextMenuSwapStep(
                player,
                state,
                menuSwap,
                state.startedTick);
    }

    private void dispatchWorldMenuTransaction(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuTransaction menu) {
        menu.opener().ifPresent(opener ->
                player.connection.handleUseItemOn(
                        new ServerboundUseItemOnPacket(
                                MinecraftInteractionView.hand(menu.hand()),
                                MinecraftInteractionView.hit(opener),
                                nextSequence())));
        MenuSnapshot opened = snapshotMenu(
                player, menu.template().family()).orElseThrow(
                        MenuPreconditionChangedException::new);
        MenuTransactionPlan plan = menu.template().bind(opened)
                .orElseThrow(MenuPreconditionChangedException::new);
        installWorldMenuPlan(
                state,
                menu.template().family(),
                menu.limits(),
                opened,
                plan);
    }

    /**
     * 单次 strict transfer 只在原版容器打开以后读取完整布局，再构造守恒的完整堆叠
     * 或逐右键精确数量模板；这避免在未打开箱子、熔炉或工作台时窥探其内容。
     */
    private void dispatchWorldMenuTransfer(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuTransfer menu) {
        player.connection.handleUseItemOn(
                new ServerboundUseItemOnPacket(
                        MinecraftInteractionView.hand(menu.hand()),
                        MinecraftInteractionView.hit(menu.opener()),
                        nextSequence()));
        Optional<Container> expectedEnderChestInventory =
                expectedEnderChestInventory(player, menu);
        MenuSnapshot opened = snapshotMenu(
                player,
                menu.family(),
                Optional.empty(),
                expectedEnderChestInventory,
                Optional.empty())
                .orElseThrow(MenuPreconditionChangedException::new);
        if (expectedEnderChestInventory.isPresent()) {
            /*
             * The item ledger of an ender chest is private to the player, not the block entity.
             * Bind the exact native menu instance immediately after the original interaction so a
             * re-entrant menu replacement cannot continue through a same-shape ChestMenu.
             */
            state.worldMenuBoundNativeMenu = player.containerMenu;
        }
        MenuTransactionTemplate template = (menu.requestedAmount() == 0
                ? MenuTransactionTemplateBuilder.moveOrSwap(
                        opened, menu.sourceSlot(), menu.targetSlot())
                : MenuTransactionTemplateBuilder.moveExactAmount(
                        opened,
                        menu.sourceSlot(),
                        menu.targetSlot(),
                        menu.requestedAmount()))
                .orElseThrow(MenuPreconditionChangedException::new);
        MenuTransactionPlan plan = template.bind(opened)
                .orElseThrow(MenuPreconditionChangedException::new);
        if (plan.orderedSteps().size() > menu.limits().maxClicks()) {
            throw new MenuPreconditionChangedException();
        }
        installWorldMenuPlan(
                state, menu.family(), menu.limits(), opened, plan);
    }

    /**
     * 配方 source 与 result 指纹只能在真实原版菜单打开后绑定。这里没有 packet recipe
     * 快捷路径：每一格投入和领取都将在后续 Tick 走 {@code clicked()}。
     */
    private void dispatchWorldMenuRecipe(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuRecipe menu) {
        menu.opener().ifPresent(opener ->
                player.connection.handleUseItemOn(
                        new ServerboundUseItemOnPacket(
                                MinecraftInteractionView.hand(menu.hand()),
                                MinecraftInteractionView.hit(opener),
                                nextSequence())));
        MenuSnapshot opened = menu.recipe().isFurnace()
                ? snapshotFurnaceMenu(
                        player, menu.recipe().furnaceKind()).orElseThrow(
                                MenuPreconditionChangedException::new)
                : snapshotMenu(player, menu.recipe().family()).orElseThrow(
                        MenuPreconditionChangedException::new);
        Map<ResourceId, ItemStackFingerprint> prototypes =
                vanillaRecipePrototypes(player, menu.recipe());
        if (menu.recipe().isCrafting()) {
            MenuTransactionPlan plan = P5ACraftingMenuPlanBuilder.build(
                    opened,
                    menu.recipe(),
                    menu.batches(),
                    prototypes,
                    nativeCraftingPreviewResolver(player, prototypes))
                    .orElseThrow(
                            MenuPreconditionChangedException::new);
            if (plan.orderedSteps().size() > menu.limits().maxClicks()) {
                throw new MenuPreconditionChangedException();
            }
            installWorldMenuPlan(
                    state, menu.recipe().family(), menu.limits(), opened,
                    plan);
            state.recipeSession = RecipeSession.crafting(
                    menu.recipe(), menu.batches(), prototypes,
                    MinecraftInteractionView.inventoryContents(player));
            return;
        }

        if (!matchesExactFurnaceRecipe(
                player, menu.recipe(), prototypes)) {
            throw new MenuPreconditionChangedException();
        }
        P5AFurnaceMenuPlanBuilder.Deposit deposit =
                P5AFurnaceMenuPlanBuilder.deposit(
                        opened,
                        menu.recipe(),
                        prototypes,
                        menu.recipe().furnaceKind()).orElseThrow(
                                MenuPreconditionChangedException::new);
        if (deposit.plan().orderedSteps().size()
                > menu.limits().maxClicks()) {
            throw new MenuPreconditionChangedException();
        }
        installWorldMenuPlan(
                state, MenuFamily.FURNACE, menu.limits(), opened,
                deposit.plan());
        state.recipeSession = RecipeSession.furnace(
                menu.recipe(), menu.batches(), prototypes,
                MinecraftInteractionView.inventoryContents(player),
                deposit.expectation());
    }

    /**
     * 打开和选择 offer 都通过原版实体 interaction / MerchantMenu API；不调用 villager、offer 或
     * Inventory 的任何写入方法。真正的支付和领取也只在后续 tick 经 {@link
     * AbstractContainerMenu#clicked(int, int, ClickType, net.minecraft.world.entity.player.Player)} 完成。
     */
    private void dispatchWorldVillagerTrade(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldVillagerTrade trade) {
        if (!exactNativeInventoryMenuHasEmptyCursor(player)) {
            throw new MenuPreconditionChangedException();
        }
        Entity target = MinecraftInteractionView.entity(player,
                        trade.villager())
                .orElseThrow(MenuPreconditionChangedException::new);
        if (target.getClass() != Villager.class) {
            throw new MenuPreconditionChangedException();
        }
        Villager villager = (Villager) target;
        MerchantOffer offer = merchantOfferAt(villager, trade.offerIndex())
                .orElseThrow(MenuPreconditionChangedException::new);
        MerchantOfferState offerState = merchantOfferStaticState(
                player, offer).orElseThrow(MenuPreconditionChangedException::new);
        int villagerXpAfter = villagerXpAfterTrade(
                trade.expectedVillagerXp(), offerState).orElseThrow(
                        MenuPreconditionChangedException::new);
        if (!merchantOfferMatches(player, villager, offer, trade,
                trade.expectedOfferUses(), trade.expectedVillagerXp(), true)
                || villagerTradeWouldChangeLevel(
                        villager, offerState, trade.expectedVillagerXp())
                || !strictVillagerTradeInventoryLayout(player, trade)) {
            throw new MenuPreconditionChangedException();
        }
        InventoryContentsSnapshot inventoryBefore =
                MinecraftInteractionView.inventoryContents(player);
        InventoryMenuSnapshot inventoryMenuBefore =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (state.villagerTradeInventoryBefore == null
                || !inventoryMenuBefore.layoutEqualsIgnoringState(
                        state.villagerTradeInventoryBefore)) {
            throw new MenuPreconditionChangedException();
        }
        state.villagerTradeOfferStateBefore = offerState;
        state.villagerTradeVillagerDataBefore = villager.getVillagerData();
        player.connection.handleInteract(
                ServerboundInteractPacket.createInteractionPacket(
                        villager,
                        player.isSecondaryUseActive(),
                        InteractionHand.MAIN_HAND));
        if (player.containerMenu.getClass() != MerchantMenu.class
                || !(player.containerMenu instanceof MerchantMenu merchantMenu)) {
            throw merchantTradeStartRejected(
                    MerchantTradeStartRejection.OPEN_DID_NOT_BIND_MERCHANT);
        }
        /* MerchantMenu level is populated only by the client offers packet.
         * The authoritative VillagerData level remains checked below. */
        if (!merchantMenu.stillValid(player)
                || villager.getTradingPlayer() != player
                || merchantMenu.getTraderXp() != trade.expectedVillagerXp()
                || merchantMenu.getOffers().size() <= trade.offerIndex()
                || merchantMenu.getOffers().get(trade.offerIndex()) != offer
                || merchantOfferAt(villager, trade.offerIndex()).orElse(null)
                        != offer
                || !merchantOfferMatches(player, villager, offer, trade,
                        trade.expectedOfferUses(), trade.expectedVillagerXp(),
                        true)
                || !merchantOfferStaticState(player, offer)
                        .filter(offerState::equals)
                        .isPresent()) {
            throw merchantTradeStartRejected(
                    MerchantTradeStartRejection.OPENED_MERCHANT_BINDING_DRIFT);
        }
        merchantMenu.setSelectionHint(trade.offerIndex());
        MenuSnapshot opened = snapshotMenu(player, MenuFamily.MERCHANT)
                .orElseThrow(() -> merchantTradeStartRejected(
                        MerchantTradeStartRejection.MERCHANT_SNAPSHOT_INVALID));
        state.merchantTradeSession = new MerchantTradeSession(
                villager,
                merchantMenu,
                offer,
                trade.offerIndex(),
                trade.expectedOfferUses(),
                villager.getVillagerData(),
                offerState,
                trade.expectedVillagerXp(),
                villagerXpAfter,
                inventoryBefore,
                inventoryMenuBefore);
        MenuTransactionTemplate template = MerchantTradeMenuPlanBuilder.build(
                        opened,
                        trade.expectedCost(),
                        trade.expectedResult(),
                        merchantMenuSlotForInventorySlot(
                                trade.sourceInventorySlot()),
                        merchantMenuSlotForInventorySlot(
                                trade.outputInventorySlot()))
                .orElseThrow(() -> merchantTradeStartRejected(
                        MerchantTradeStartRejection.MERCHANT_PLAN_UNBINDABLE));
        MenuTransactionPlan plan = template.bind(opened)
                .orElseThrow(() -> merchantTradeStartRejected(
                        MerchantTradeStartRejection.MERCHANT_PLAN_UNBINDABLE));
        if (plan.orderedSteps().size() > trade.limits().maxClicks()) {
            throw merchantTradeStartRejected(
                    MerchantTradeStartRejection.MERCHANT_PLAN_UNBINDABLE);
        }
        try {
            installWorldMenuPlan(
                    state, MenuFamily.MERCHANT, trade.limits(), opened, plan);
        } catch (MenuPreconditionChangedException exception) {
            throw merchantTradeStartRejected(
                    MerchantTradeStartRejection.MERCHANT_PLAN_UNBINDABLE);
        }
    }

    /**
     * MerchantMenu 固定为两个支付/一个结果，随后 27 主背包和 9 快捷栏。这里不从
     * Slot.containerSlot 猜映射，避免一个 modded menu 通过相同数字伪装；调用点已先要求精确
     * {@link MerchantMenu} 类。
     */
    private static int merchantMenuSlotForInventorySlot(int inventorySlot) {
        if (inventorySlot >= 9 && inventorySlot <= 35) {
            return MerchantTradeMenuPlanBuilder.FIRST_PLAYER_SLOT
                    + inventorySlot - 9;
        }
        if (inventorySlot >= 0 && inventorySlot <= 8) {
            return 30 + inventorySlot;
        }
        throw new IllegalArgumentException(
                "merchant trade inventory slot is outside the player storage layout");
    }

    private static void installWorldMenuPlan(
            InteractionState state,
            MenuFamily family,
            MenuTransactionLimits limits,
            MenuSnapshot opened,
            MenuTransactionPlan plan) {
        MenuTransaction transaction = MenuTransaction.opening(
                family, limits, state.startedTick);
        if (!transaction.observeOpened(opened, state.startedTick)) {
            throw new MenuPreconditionChangedException();
        }
        transaction.beginPlanning(state.startedTick);
        if (!transaction.installPlan(plan, state.startedTick)) {
            throw new MenuPreconditionChangedException();
        }
        state.worldMenuTransaction = transaction;
        state.worldMenuLastSnapshot = opened;
    }

    private BackendResult tickWorldMenuTransaction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            MenuFamily family,
            long currentTick) {
        return tickWorldMenuTransaction(
                envelope,
                player,
                state,
                family,
                Optional.empty(),
                expectedEnderChestInventory(player, state.spec),
                currentTick);
    }

    private BackendResult tickWorldMenuTransaction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            MenuFamily family,
            Optional<FurnaceKind> expectedFurnaceKind,
            long currentTick) {
        return tickWorldMenuTransaction(
                envelope,
                player,
                state,
                family,
                expectedFurnaceKind,
                expectedEnderChestInventory(player, state.spec),
                currentTick);
    }

    private BackendResult tickWorldMenuTransaction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            MenuFamily family,
            Optional<FurnaceKind> expectedFurnaceKind,
            Optional<Container> expectedEnderChestInventory,
            long currentTick) {
        if (!lifecycleManager.mayActionMutateInventory(
                envelope.botId(), envelope.botGeneration())
                || !isCurrentActionTarget(state, player)) {
            return failure(
                    envelope,
                    ActionFailureCode.CHANNEL_BUSY,
                    "Bot inventory became write-locked during world menu transaction");
        }
        MenuTransaction transaction = state.worldMenuTransaction;
        if (transaction == null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World menu transaction state is missing");
        }
        if (expectedEnderChestInventory.isPresent()
                && state.worldMenuBoundNativeMenu == null) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Ender chest menu transaction has no private native-menu binding");
        }
        try {
            return switch (transaction.state()) {
                case APPLYING -> tickWorldMenuClick(
                        envelope,
                        player,
                        state,
                        family,
                        expectedFurnaceKind,
                        expectedEnderChestInventory,
                        currentTick);
                case VERIFYING -> closeVerifiedWorldMenu(
                        envelope,
                        player,
                        state,
                        expectedFurnaceKind,
                        expectedEnderChestInventory,
                        currentTick);
                case COMPLETED -> BackendResult.readyToVerify(envelope);
                case OPENING,
                        SNAPSHOT,
                        PLANNING,
                        ACK,
                        CLOSING -> failure(
                                envelope,
                                ActionFailureCode.INTERNAL_ERROR,
                                "World menu transaction entered an unreachable state");
                case FAILED,
                        CANCELLED -> menuTransactionFailure(
                                envelope, transaction.failure().orElse(
                                        MenuTransactionFailure.CANCELLED));
            };
        } catch (RuntimeException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "World menu adapter failed closed during click or close");
        }
    }

    /**
     * 交易的每个 tick 先重验实体、精确 MerchantMenu、offer 对象/价格/等级和已确认 click
     * 前缀对应的 uses。随后才允许通用 menu transaction 读取快照并调用一次原版 clicked。
     */
    private BackendResult tickWorldVillagerTrade(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldVillagerTrade trade,
            long currentTick) {
        MerchantTradeSession session = state.merchantTradeSession;
        MenuTransaction transaction = state.worldMenuTransaction;
        if (session == null || transaction == null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "Villager trade has no bound merchant session");
        }
        boolean paymentConsumed = transaction.plan()
                .map(plan -> transaction.confirmedClicks()
                        == plan.orderedSteps().size())
                .orElse(false);
        int expectedUses;
        int expectedVillagerXp;
        try {
            expectedUses = Math.addExact(
                    session.usesBefore, paymentConsumed ? 1 : 0);
            expectedVillagerXp = paymentConsumed
                    ? session.villagerXpAfter
                    : session.villagerXpBefore;
        } catch (ArithmeticException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Villager trade offer uses overflowed");
        }
        if (!merchantTradeSessionMatchesOpen(
                player, session, trade, expectedUses, expectedVillagerXp)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager, MerchantMenu, offer, price, stock, or level drifted during trade");
        }
        return tickWorldMenuTransaction(
                envelope, player, state, MenuFamily.MERCHANT, currentTick);
    }

    private static boolean merchantTradeSessionMatchesOpen(
            BotServerPlayer player,
            MerchantTradeSession session,
            WorldInteractionActionSpec.WorldVillagerTrade trade,
            int expectedUses,
            int expectedVillagerXp) {
        try {
            Entity current = MinecraftInteractionView.entity(
                            player, trade.villager())
                    .orElse(null);
            // MerchantMenu level is a client presentation field; the
            // authoritative VillagerData level is checked by merchantOfferMatches.
            if (current != session.villager
                    || current == null
                    || current.getClass() != Villager.class
                    || player.containerMenu != session.menu
                    || session.menu.getClass() != MerchantMenu.class
                    || !session.menu.stillValid(player)
                    || session.villager.getTradingPlayer() != player
                    || session.villager.getVillagerData()
                            != session.villagerDataBefore
                    || session.menu.getTraderXp() != expectedVillagerXp
                    || session.menu.getOffers().size() <= session.offerIndex
                    || session.menu.getOffers().get(session.offerIndex)
                            != session.offer
                    || merchantOfferAt(session.villager,
                                    session.offerIndex)
                            .orElse(null) != session.offer) {
                return false;
            }
            return merchantOfferMatches(
                    player,
                    session.villager,
                    session.offer,
                    trade,
                    expectedUses,
                    expectedVillagerXp,
                    expectedUses < trade.expectedOfferMaxUses())
                    && merchantOfferStaticState(player, session.offer)
                            .filter(session.offerState::equals)
                            .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private BackendResult tickWorldMenuRecipe(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuRecipe menu,
            long currentTick) {
        if (!lifecycleManager.mayActionMutateInventory(
                envelope.botId(), envelope.botGeneration())
                || !isCurrentActionTarget(state, player)) {
            return failure(
                    envelope,
                    ActionFailureCode.CHANNEL_BUSY,
                    "Bot inventory became write-locked during world recipe transaction");
        }
        RecipeSession session = state.recipeSession;
        if (session == null || session.recipe != menu.recipe()) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World recipe action has no matching recipe session");
        }
        if (currentTick - state.startedTick > menu.limits().maxTicks()) {
            return failure(
                    envelope,
                    ActionFailureCode.DEADLINE_EXCEEDED,
                    "World recipe exceeded its bounded transaction deadline");
        }
        if (menu.recipe().isCrafting()) {
            return tickWorldMenuTransaction(
                    envelope, player, state, menu.recipe().family(),
                    currentTick);
        }
        try {
            return switch (session.furnaceStage) {
                case DEPOSITING -> tickFurnaceDeposit(
                        envelope, player, state, session, currentTick);
                case WAITING -> pollFurnaceResult(
                        envelope, player, state, menu, session, currentTick);
                case COLLECTING -> tickFurnaceCollection(
                        envelope, player, state, session, currentTick);
                case COMPLETED -> BackendResult.readyToVerify(envelope);
            };
        } catch (RuntimeException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Furnace recipe adapter failed closed during menu transition");
        }
    }

    private BackendResult tickFurnaceDeposit(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            RecipeSession session,
            long currentTick) {
        MenuTransaction transaction = state.worldMenuTransaction;
        if (transaction == null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "Furnace deposit transaction is missing");
        }
        return switch (transaction.state()) {
            case APPLYING -> {
                BackendResult click = tickWorldMenuClick(
                        envelope,
                        player,
                        state,
                        MenuFamily.FURNACE,
                        Optional.of(session.furnaceExpectation.furnaceKind()),
                        Optional.empty(),
                        currentTick);
                // 最后一颗燃料一经原版接受，下一世界 tick 即可被炉子消耗。必须在同一
                // server tick 复核并关闭投入窗口，不能把一个已经确认的精确布局暴露给
                // 异步熔炼进度后再当作外部 snapshot drift。
                if (transaction.state() == MenuTransactionState.VERIFYING) {
                    yield closeVerifiedFurnaceDeposit(
                            envelope, player, state, session, currentTick);
                }
                yield click;
            }
            case VERIFYING -> closeVerifiedFurnaceDeposit(
                    envelope, player, state, session, currentTick);
            case FAILED,
                    CANCELLED -> menuTransactionFailure(
                            envelope, transaction.failure().orElse(
                                    MenuTransactionFailure.CANCELLED));
            case OPENING,
                    SNAPSHOT,
                    PLANNING,
                    ACK,
                    CLOSING,
                    COMPLETED -> failure(
                            envelope,
                            ActionFailureCode.INTERNAL_ERROR,
                            "Furnace deposit transaction entered an unreachable state");
        };
    }

    private BackendResult closeVerifiedFurnaceDeposit(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            RecipeSession session,
            long currentTick) {
        MenuTransaction transaction = Objects.requireNonNull(
                state.worldMenuTransaction, "worldMenuTransaction");
        MenuSnapshot finalSnapshot = snapshotFurnaceMenu(
                player, session.furnaceExpectation.furnaceKind()).orElse(null);
        if (!transaction.verify(finalSnapshot, currentTick)) {
            return menuTransactionFailure(
                    envelope, transaction.failure().orElseThrow());
        }
        state.worldMenuLastSnapshot = finalSnapshot;
        player.closeContainer();
        if (!nativeInventoryMenuHasEmptyCursor(player)
                || !transaction.closeConfirmed(currentTick)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Vanilla furnace did not close after verified deposit");
        }
        session.furnaceStage = FurnaceStage.WAITING;
        session.nextFurnacePollTick = Math.addExact(
                currentTick, FURNACE_POLL_INTERVAL_TICKS);
        return BackendResult.running(envelope);
    }

    private BackendResult pollFurnaceResult(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuRecipe menu,
            RecipeSession session,
            long currentTick) {
        if (currentTick < session.nextFurnacePollTick) {
            return BackendResult.running(envelope);
        }
        if (!recipeWorkstationFingerprintStillMatches(player, menu)) {
            closeWorldMenuDuringCleanup(player, state);
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Furnace workstation changed while smelting");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.CHANNEL_BUSY,
                    "Furnace recipe cannot poll while another menu is active");
        }
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(
                MinecraftInteractionView.hand(menu.hand()),
                MinecraftInteractionView.hit(menu.opener().orElseThrow()),
                nextSequence()));
        MenuSnapshot observed = snapshotFurnaceMenu(
                player, session.furnaceExpectation.furnaceKind()).orElse(null);
        if (observed == null
                || !session.furnaceExpectation.pollingSnapshotAllowed(
                        observed,
                        session.furnaceExpectation.furnaceKind())) {
            closeWorldMenuDuringCleanup(player, state);
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Furnace contents or player inventory drifted while smelting");
        }
        if (!session.furnaceExpectation.readyToCollect(
                observed, session.furnaceExpectation.furnaceKind())) {
            player.closeContainer();
            if (player.containerMenu != player.inventoryMenu) {
                return failure(
                        envelope,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "Furnace poll did not restore the native inventory menu");
            }
            session.nextFurnacePollTick = Math.addExact(
                    currentTick, FURNACE_POLL_INTERVAL_TICKS);
            return BackendResult.running(envelope);
        }
        MenuTransactionPlan collection = P5AFurnaceMenuPlanBuilder.collect(
                observed,
                session.furnaceExpectation,
                session.furnaceExpectation.furnaceKind()).orElse(null);
        if (collection == null
                || collection.orderedSteps().size()
                        > menu.limits().maxClicks()) {
            closeWorldMenuDuringCleanup(player, state);
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Furnace result could not bind a strict collection plan");
        }
        installWorldMenuPlan(
                state, MenuFamily.FURNACE, menu.limits(), observed,
                collection);
        session.furnaceStage = FurnaceStage.COLLECTING;
        return BackendResult.running(envelope);
    }

    private BackendResult tickFurnaceCollection(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            RecipeSession session,
            long currentTick) {
        BackendResult result = tickWorldMenuTransaction(
                envelope,
                player,
                state,
                MenuFamily.FURNACE,
                Optional.of(session.furnaceExpectation.furnaceKind()),
                currentTick);
        if (result.step() == BackendStep.READY_TO_VERIFY) {
            session.furnaceStage = FurnaceStage.COMPLETED;
        }
        return result;
    }

    private BackendResult tickWorldMenuClick(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            MenuFamily family,
            Optional<FurnaceKind> expectedFurnaceKind,
            Optional<Container> expectedEnderChestInventory,
            long currentTick) {
        MenuTransaction transaction = Objects.requireNonNull(
                state.worldMenuTransaction, "worldMenuTransaction");
        MenuSnapshot before = snapshotMenu(
                player,
                family,
                expectedFurnaceKind,
                expectedEnderChestInventory,
                Optional.ofNullable(state.worldMenuBoundNativeMenu)).orElse(null);
        Optional<MenuClick> click = transaction.issueNextClick(
                currentTick, before);
        if (transaction.state() == MenuTransactionState.FAILED) {
            MenuTransactionFailure failure = transaction.failure()
                    .orElseThrow();
            return failure == MenuTransactionFailure.SNAPSHOT_DRIFT
                    ? menuSnapshotDriftFailure(
                            envelope, transaction, before, false)
                    : menuTransactionFailure(envelope, failure);
        }
        if (click.isEmpty()) {
            return BackendResult.running(envelope);
        }
        MenuClick nextClick = click.orElseThrow();
        AbstractContainerMenu nativeMenu = player.containerMenu;
        if (!menuClickAllowed(nativeMenu, player, nextClick)) {
            return failure(
                    envelope,
                    ActionFailureCode.PERMISSION_DENIED,
                    "Vanilla menu slot permission changed before click");
        }
        if (expectedEnderChestInventory.isPresent()) {
            /*
             * menuClickAllowed() invokes vanilla slot hooks. Re-read the complete bound menu
             * after those potentially re-entrant hooks, then do one final field-only identity
             * check immediately before the first world-affecting click.
             */
            MenuSnapshot postPermission = snapshotMenu(
                    player,
                    family,
                    expectedFurnaceKind,
                    expectedEnderChestInventory,
                    Optional.ofNullable(state.worldMenuBoundNativeMenu)).orElse(null);
            if (!Objects.equals(before, postPermission)
                    || !matchesBoundEnderChestMenu(
                            player,
                            nativeMenu,
                            state.worldMenuBoundNativeMenu,
                            expectedEnderChestInventory.orElseThrow())) {
                return failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        "Ender chest private native-menu binding changed before click");
            }
        }
        MenuClickDispatchBoundary.Result dispatch =
                MenuClickDispatchBoundary.dispatch(
                        transaction,
                        currentTick,
                        () -> worldMenuClickDispatcher.dispatch(
                                nativeMenu, nextClick, player),
                        () -> snapshotMenu(
                                player,
                                family,
                                expectedFurnaceKind,
                                expectedEnderChestInventory,
                                Optional.of(nativeMenu)).orElse(null));
        if (!dispatch.mayAcknowledge()) {
            dispatch.observedAfter().ifPresent(
                    observed -> state.worldMenuLastSnapshot = observed);
            return switch (dispatch.disposition()) {
                case FAILED -> menuTransactionFailure(
                        envelope, transaction.failure().orElseThrow());
                case UNSAFE_REENTRANT -> failure(
                        envelope,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "World menu transaction changed during failed native click dispatch");
                case ACKNOWLEDGE -> throw new IllegalStateException(
                        "acknowledgable dispatch reached failure branch");
            };
        }
        MenuSnapshot after = dispatch.observedAfter().orElse(null);
        if (!transaction.acknowledge(after, currentTick)) {
            MenuTransactionFailure failure = transaction.failure()
                    .orElseThrow();
            return failure == MenuTransactionFailure.SNAPSHOT_DRIFT
                    ? menuSnapshotDriftFailure(
                            envelope, transaction, after, true)
                    : menuTransactionFailure(envelope, failure);
        }
        state.worldMenuLastSnapshot = after;
        return BackendResult.running(envelope);
    }

    private BackendResult closeVerifiedWorldMenu(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            Optional<FurnaceKind> expectedFurnaceKind,
            Optional<Container> expectedEnderChestInventory,
            long currentTick) {
        MenuTransaction transaction = Objects.requireNonNull(
                state.worldMenuTransaction, "worldMenuTransaction");
        MenuSnapshot finalSnapshot = snapshotMenu(
                player,
                transaction.expectedFamily(),
                expectedFurnaceKind,
                expectedEnderChestInventory,
                Optional.ofNullable(state.worldMenuBoundNativeMenu)).orElse(null);
        if (!transaction.verify(finalSnapshot, currentTick)) {
            return menuTransactionFailure(
                    envelope, transaction.failure().orElseThrow());
        }
        state.worldMenuLastSnapshot = finalSnapshot;
        player.closeContainer();
        if (!nativeInventoryMenuHasEmptyCursor(player)
                || !transaction.closeConfirmed(currentTick)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Vanilla menu did not close after verified transaction");
        }
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult verifyWorldMenuTransaction(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            MenuFamily family) {
        MenuTransaction transaction = state.worldMenuTransaction;
        MenuSnapshot finalSnapshot = state.worldMenuLastSnapshot;
        if (transaction == null
                || transaction.state() != MenuTransactionState.COMPLETED
                || finalSnapshot == null
                || !nativeInventoryMenuHasEmptyCursor(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "World menu transaction was not fully verified and closed");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "menu.family",
                                family.stableId()),
                        evidence(
                                "menu.container_id",
                                Integer.toString(
                                        finalSnapshot.containerId())),
                        evidence(
                                "menu.clicks",
                                Integer.toString(
                                        transaction.confirmedClicks())),
                        evidence("menu.closed", "true")),
                "Verified and closed vanilla menu transaction");
    }

    private BackendResult verifyWorldVillagerTrade(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldVillagerTrade trade) {
        MerchantTradeSession session = state.merchantTradeSession;
        MenuTransaction transaction = state.worldMenuTransaction;
        MenuSnapshot finalSnapshot = state.worldMenuLastSnapshot;
        if (session == null
                || transaction == null
                || transaction.state() != MenuTransactionState.COMPLETED
                || finalSnapshot == null
                || !exactNativeInventoryMenuHasEmptyCursor(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager trade was not fully verified and closed");
        }
        int expectedUses;
        try {
            expectedUses = Math.addExact(session.usesBefore, 1);
        } catch (ArithmeticException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Villager trade offer uses overflowed");
        }
        Entity resolved = MinecraftInteractionView.entity(player,
                        trade.villager())
                .orElse(null);
        if (resolved != session.villager
                || resolved == null
                || resolved.getClass() != Villager.class
                || session.villager.getTradingPlayer() != null
                || merchantOfferAt(session.villager, session.offerIndex)
                        .orElse(null) != session.offer
                || session.villager.getVillagerData()
                        != session.villagerDataBefore
                || !merchantOfferMatches(
                        player,
                        session.villager,
                        session.offer,
                        trade,
                        expectedUses,
                        session.villagerXpAfter,
                        false)
                || !merchantOfferStaticState(player, session.offer)
                        .filter(session.offerState::equals)
                        .isPresent()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Villager offer, price, stock, XP, or level changed during trade");
        }
        InventoryContentsSnapshot inventoryAfter =
                MinecraftInteractionView.inventoryContents(player);
        if (!VillagerTradeInventoryConservation.matchesCompletedTrade(
                session.inventoryBefore,
                inventoryAfter,
                trade.expectedCost(),
                trade.expectedResult())) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Villager trade inventory totals did not match exactly one offer");
        }
        if (!merchantTradeInventoryLayoutMatches(
                player, session, trade, true)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Villager trade source or unique result slot drifted after close");
        }
        return success(
                envelope,
                List.of(
                        evidence("merchant.entity_id",
                                session.villager.getUUID().toString()),
                        evidence("merchant.offer_index", Integer.toString(
                                session.offerIndex)),
                        evidence("merchant.villager_level", Integer.toString(
                                trade.expectedVillagerLevel())),
                        evidence("merchant.xp_before", Integer.toString(
                                session.villagerXpBefore)),
                        evidence("merchant.xp_after", Integer.toString(
                                session.villagerXpAfter)),
                        evidence("merchant.uses_before", Integer.toString(
                                session.usesBefore)),
                        evidence("merchant.uses_after", Integer.toString(
                                expectedUses)),
                        evidence("menu.family", MenuFamily.MERCHANT.stableId()),
                        evidence("menu.container_id", Integer.toString(
                                finalSnapshot.containerId())),
                        evidence("menu.clicks", Integer.toString(
                                transaction.confirmedClicks())),
                        evidence("menu.closed", "true")),
                "Verified exactly one vanilla villager trade");
    }

    /**
     * 总量守恒仍不足以证明外部代码没有在菜单外重排库存。因此对完成/取消两种落点均重放
     * 41 槽 native inventory 基线：完成时只有 source 和唯一 output 可以变化。
     */
    private static boolean merchantTradeInventoryLayoutMatches(
            BotServerPlayer player,
            MerchantTradeSession session,
            WorldInteractionActionSpec.WorldVillagerTrade trade,
            boolean completed) {
        try {
            InventoryMenuSnapshot after =
                    MinecraftActionSnapshot.inventoryMenu(player);
            InventoryMenuSnapshot before = session.inventoryMenuBefore;
            if (after.containerId() != before.containerId()
                    || after.selectedHotbar() != before.selectedHotbar()
                    || !after.cursor().isEmpty()) {
                return false;
            }
            for (int inventorySlot = 0;
                    inventorySlot < after.inventorySlots().size();
                    inventorySlot++) {
                ItemStackFingerprint expected = before.itemAt(inventorySlot);
                if (completed
                        && inventorySlot == trade.sourceInventorySlot()) {
                    expected = new ItemStackFingerprint(
                            trade.expectedSource().itemId(),
                            trade.expectedSource().count()
                                    - trade.expectedCost().count(),
                            trade.expectedSource().damage(),
                            trade.expectedSource().componentsDigest());
                } else if (completed
                        && inventorySlot == trade.outputInventorySlot()) {
                    expected = trade.expectedResult();
                }
                if (!after.itemAt(inventorySlot).equals(expected)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private BackendResult verifyWorldMenuRecipe(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldMenuRecipe menu) {
        RecipeSession session = state.recipeSession;
        MenuTransaction transaction = state.worldMenuTransaction;
        MenuSnapshot finalSnapshot = state.worldMenuLastSnapshot;
        if (session == null
                || session.recipe != menu.recipe()
                || transaction == null
                || transaction.state() != MenuTransactionState.COMPLETED
                || finalSnapshot == null
                || !nativeInventoryMenuHasEmptyCursor(player)
                || menu.recipe().isFurnace()
                        && session.furnaceStage != FurnaceStage.COMPLETED
                || !recipeWorkstationFingerprintStillMatches(player, menu)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "World recipe transaction was not fully verified and closed");
        }
        InventoryContentsSnapshot actual =
                MinecraftInteractionView.inventoryContents(player);
        boolean deltaMatches = menu.recipe().isCrafting()
                ? P5ACraftingMenuPlanBuilder.matchesPlayerDelta(
                        session.inventoryBefore,
                        actual,
                        menu.recipe(),
                        session.batches,
                        session.vanillaPrototypes)
                : P5AFurnaceMenuPlanBuilder.matchesPlayerDelta(
                        session.inventoryBefore,
                        actual,
                        session.furnaceExpectation);
        if (!deltaMatches) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "World recipe player inventory delta did not match its whitelist contract");
        }
        List<ActionEvidence> evidence = new ArrayList<>(8);
        evidence.add(evidence("recipe.id", menu.recipe().stableId()));
        evidence.add(evidence("recipe.batches", Integer.toString(
                session.batches)));
        evidence.add(evidence("menu.family", menu.recipe().family()
                .stableId()));
        evidence.add(evidence("menu.container_id", Integer.toString(
                finalSnapshot.containerId())));
        evidence.add(evidence("menu.clicks", Integer.toString(
                transaction.confirmedClicks())));
        evidence.add(evidence("menu.closed", "true"));
        if (menu.recipe().isFurnace()) {
            evidence.add(evidence("furnace.kind", menu.recipe()
                    .furnaceKind().stableId()));
            evidence.add(evidence("furnace.recipe_type", menu.recipe()
                    .furnaceKind().recipeTypeStableId()));
        }
        return success(
                envelope,
                List.copyOf(evidence),
                "Verified and closed vanilla recipe menu transaction");
    }

    private static Optional<MenuSnapshot> snapshotMenu(
            BotServerPlayer player, MenuFamily expectedFamily) {
        return snapshotMenu(
                player,
                expectedFamily,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * 炉型动作必须在每次打开、点击前后和关闭前都重新验证精确 Mojang menu class；39 槽形状
     * 只是第二道形状检查，不能代替这个类身份合同。
     */
    private static Optional<MenuSnapshot> snapshotFurnaceMenu(
            BotServerPlayer player, FurnaceKind expectedKind) {
        return snapshotMenu(
                player,
                MenuFamily.FURNACE,
                Optional.of(Objects.requireNonNull(
                        expectedKind, "expectedKind")),
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<MenuSnapshot> snapshotMenu(
            BotServerPlayer player,
            MenuFamily expectedFamily,
            Optional<FurnaceKind> expectedFurnaceKind,
            Optional<Container> expectedEnderChestInventory,
            Optional<AbstractContainerMenu> expectedNativeMenu) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(expectedFamily, "expectedFamily");
        Objects.requireNonNull(expectedFurnaceKind, "expectedFurnaceKind");
        Objects.requireNonNull(
                expectedEnderChestInventory, "expectedEnderChestInventory");
        Objects.requireNonNull(expectedNativeMenu, "expectedNativeMenu");
        AbstractContainerMenu menu = player.containerMenu;
        MenuFamily observed = menuFamily(player, menu).orElse(null);
        FurnaceKind observedFurnaceKind = expectedFamily
                == MenuFamily.FURNACE
                ? exactVanillaFurnaceKind(menu.getClass()).orElse(null)
                : null;
        boolean furnaceKindMatches = expectedFamily == MenuFamily.FURNACE
                ? expectedFurnaceKind.filter(
                        expected -> expected == observedFurnaceKind).isPresent()
                : expectedFurnaceKind.isEmpty();
        boolean enderChestInventoryMatches = expectedEnderChestInventory
                .map(expected -> matchesEnderChestMenu(
                        menu, expectedFamily, expected))
                .orElse(true);
        boolean nativeMenuMatches = expectedNativeMenu
                .map(expected -> menu == expected)
                .orElse(true);
        if (observed != expectedFamily
                || !furnaceKindMatches
                || !enderChestInventoryMatches
                || !nativeMenuMatches
                || !menu.stillValid(player)
                || menu.slots.size() != expectedFamily.slotCount()) {
            return Optional.empty();
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            slots.add(MinecraftInteractionView.itemFingerprint(
                    player, slot.getItem()));
        }
        return Optional.of(new MenuSnapshot(
                observed,
                menu.containerId,
                menu.getStateId(),
                MinecraftInteractionView.itemFingerprint(
                        player, menu.getCarried()),
                slots));
    }

    /**
     * Ender chest transfer is only safe when the exact vanilla 3x9 menu exposes this Bot's own
     * player-owned ender inventory. The block entity itself is never used as an item container.
     */
    private static boolean matchesEnderChestMenu(
            AbstractContainerMenu menu,
            MenuFamily expectedFamily,
            Container expectedEnderChestInventory) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(expectedFamily, "expectedFamily");
        Objects.requireNonNull(
                expectedEnderChestInventory, "expectedEnderChestInventory");
        return expectedFamily == MenuFamily.CHEST_3X9
                && menu.getClass() == ChestMenu.class
                && ((ChestMenu) menu).getRowCount() == 3
                && ((ChestMenu) menu).getContainer().getContainerSize() == 27
                && ((ChestMenu) menu).getContainer()
                        == expectedEnderChestInventory;
    }

    /**
     * This contains only field identity and exact vanilla menu-shape checks. It deliberately does
     * not call stillValid() or slot hooks: the caller invokes it after the final potentially
     * re-entrant permission check and immediately before {@code clicked()}.
     */
    private static boolean matchesBoundEnderChestMenu(
            BotServerPlayer player,
            AbstractContainerMenu nativeMenu,
            AbstractContainerMenu boundNativeMenu,
            Container expectedEnderChestInventory) {
        return boundNativeMenu != null
                && player.containerMenu == nativeMenu
                && nativeMenu == boundNativeMenu
                && matchesEnderChestMenu(
                        nativeMenu,
                        MenuFamily.CHEST_3X9,
                        expectedEnderChestInventory);
    }

    private static Optional<MenuFamily> menuFamily(
            BotServerPlayer player, AbstractContainerMenu menu) {
        if (menu == player.inventoryMenu && menu instanceof InventoryMenu) {
            return MenuFamily.resolveExact(
                    MenuFamily.INVENTORY_2X2.stableId(), menu.slots.size());
        }
        if (menu.getClass() == CraftingMenu.class) {
            return MenuFamily.resolveExact(
                    MenuFamily.CRAFTING_3X3.stableId(), menu.slots.size());
        }
        if (exactVanillaFurnaceKind(menu.getClass()).isPresent()) {
            return MenuFamily.resolveExact(
                    MenuFamily.FURNACE.stableId(), menu.slots.size());
        }
        if (menu.getClass() == MerchantMenu.class) {
            return MenuFamily.resolveExact(
                    MenuFamily.MERCHANT.stableId(), menu.slots.size());
        }
        /*
         * 只信任 Mojang 的精确 ChestMenu 类；模组即使复用相同槽位数也不能靠子类伪装成
         * 原版普通箱子和木桶本身都走这个原版类。潜影盒是另一种原版精确 menu，见下方。
         */
        if (menu.getClass() == ChestMenu.class) {
            ChestMenu chest = (ChestMenu) menu;
            if (chest.getRowCount() == 3
                    && chest.getContainer().getContainerSize() == 27) {
                return MenuFamily.resolveExact(
                        MenuFamily.CHEST_3X9.stableId(), menu.slots.size());
            }
            if (chest.getRowCount() == 6
                    && chest.getContainer().getContainerSize() == 54) {
                return MenuFamily.resolveExact(
                        MenuFamily.CHEST_6X9.stableId(), menu.slots.size());
            }
        }
        // 潜影盒在原版中使用独立的 ShulkerBoxMenu；不能因类不同而误当作模组 menu。
        if (menu.getClass() == ShulkerBoxMenu.class) {
            return MenuFamily.resolveExact(
                    MenuFamily.CHEST_3X9.stableId(), menu.slots.size());
        }
        return Optional.empty();
    }

    /**
     * 绝不以 {@code AbstractFurnaceMenu} 或 {@code instanceof} 接受炉子：模组子类和错误炉型
     * 即使具有同样的槽位布局，也只能返回空并由调用方安全关闭。
     */
    static Optional<FurnaceKind> exactVanillaFurnaceKind(
            Class<?> menuClass) {
        Objects.requireNonNull(menuClass, "menuClass");
        if (menuClass == FurnaceMenu.class) {
            return Optional.of(FurnaceKind.FURNACE);
        }
        if (menuClass == BlastFurnaceMenu.class) {
            return Optional.of(FurnaceKind.BLAST_FURNACE);
        }
        if (menuClass == SmokerMenu.class) {
            return Optional.of(FurnaceKind.SMOKER);
        }
        return Optional.empty();
    }

    /**
     * 将每种封闭炉型映射到它唯一允许查询的原版 {@link RecipeType}。调用方不能以三者共享
     * 39 槽布局为由回退到 {@code SMELTING}。
     */
    static RecipeType<?> exactVanillaFurnaceRecipeType(
            FurnaceKind furnaceKind) {
        Objects.requireNonNull(furnaceKind, "furnaceKind");
        return switch (furnaceKind) {
            case FURNACE -> RecipeType.SMELTING;
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
        };
    }

    /**
     * P5B 的 Action 边界也重验 opener 指纹，不能只依赖上层 skill handler：模组方块可以
     * 主动创建 Mojang 的 {@link ChestMenu}，而仅检查 menu 的精确 Java 类仍会误放行。
     * 指纹随后仍由 {@link #validateBlockInteraction} 与世界当前状态逐字段比较。
     */
    static boolean isAllowedVanillaContainerTarget(
            BlockTargetFingerprint target, MenuFamily family) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(family, "family");
        Map<String, String> properties = target.state().properties();
        String blockId = target.state().blockId().value();
        return switch (family) {
            case CHEST_3X9 -> isSingleChestTarget(blockId, properties)
                    || isBarrelTarget(blockId, properties)
                    || isEnderChestTarget(blockId, properties)
                    || isVanillaShulkerTarget(blockId, properties);
            case CHEST_6X9 -> isDoubleChestTarget(blockId, properties);
            case INVENTORY_2X2, CRAFTING_3X3, FURNACE, MERCHANT -> false;
        };
    }

    /**
     * 世界配方不能只根据菜单槽数判断类型：模组方块可以打开同形状菜单。三种原版炉型共享
     * 39 槽布局，却各自绑定不同方块、精确 menu class 与 RecipeType；任何一项不匹配都由
     * 后续打开/轮询快照继续拒绝，不能退回 {@code AbstractFurnaceMenu} 的宽松父类判断。
     */
    static boolean isAllowedVanillaWorkstationTarget(
            BlockTargetFingerprint target, P5ARecipe recipe) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recipe, "recipe");
        Map<String, String> properties = target.state().properties();
        String blockId = target.state().blockId().value();
        return switch (recipe.family()) {
            case CRAFTING_3X3 -> isCraftingTableTarget(blockId, properties);
            case FURNACE -> recipe.furnaceKind().matchesWorkstationState(
                    target.state());
            case INVENTORY_2X2, MERCHANT, CHEST_3X9, CHEST_6X9 -> false;
        };
    }

    private static boolean isCraftingTableTarget(
            String blockId, Map<String, String> properties) {
        return "minecraft:crafting_table".equals(blockId)
                && properties.isEmpty();
    }

    /**
     * 配方 action 成功前仍要复核它最初绑定的工作站。每种已绑定炉型的 {@code lit} 都是
     * 本 action 合法改变的原版运行态，其他坐标、维度、方块 id 和状态字段必须保持精确相同。
     */
    private static boolean recipeWorkstationFingerprintStillMatches(
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldMenuRecipe menu) {
        if (menu.recipe().family() == MenuFamily.INVENTORY_2X2) {
            return true;
        }
        try {
            BlockTargetFingerprint expected = menu.opener()
                    .orElseThrow()
                    .target();
            BlockPos position = MinecraftInteractionView.position(
                    expected.position());
            if (!expected.dimension().value().equals(player.serverLevel()
                    .dimension().location().toString())
                    || !player.serverLevel().isLoaded(position)) {
                return false;
            }
            BlockTargetFingerprint actual = MinecraftInteractionView
                    .blockFingerprint(player, position);
            if (!expected.dimension().equals(actual.dimension())
                    || !expected.position().equals(actual.position())
                    || !expected.state().blockId().equals(
                            actual.state().blockId())) {
                return false;
            }
            if (menu.recipe().family() != MenuFamily.FURNACE) {
                return expected.state().equals(actual.state());
            }
            Map<String, String> expectedProperties = new LinkedHashMap<>(
                    expected.state().properties());
            Map<String, String> actualProperties = new LinkedHashMap<>(
                    actual.state().properties());
            String expectedLit = expectedProperties.remove("lit");
            String actualLit = actualProperties.remove("lit");
            return expectedLit != null
                    && actualLit != null
                    && BOOLEAN_PROPERTY_VALUES.contains(expectedLit)
                    && BOOLEAN_PROPERTY_VALUES.contains(actualLit)
                    && expectedProperties.equals(actualProperties);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isSingleChestTarget(
            String blockId, Map<String, String> properties) {
        return isChestTarget(blockId, properties)
                && "single".equals(properties.get("type"));
    }

    private static boolean isDoubleChestTarget(
            String blockId, Map<String, String> properties) {
        if (!isChestTarget(blockId, properties)) {
            return false;
        }
        String type = properties.get("type");
        return "left".equals(type) || "right".equals(type);
    }

    private static boolean isChestTarget(
            String blockId, Map<String, String> properties) {
        return "minecraft:chest".equals(blockId)
                && properties.keySet().equals(CHEST_PROPERTY_NAMES)
                && HORIZONTAL_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(
                        properties.get("waterlogged"));
    }

    private static boolean isBarrelTarget(
            String blockId, Map<String, String> properties) {
        return "minecraft:barrel".equals(blockId)
                && properties.keySet().equals(BARREL_PROPERTY_NAMES)
                && VANILLA_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(properties.get("open"));
    }

    private static boolean isEnderChestTarget(
            String blockId, Map<String, String> properties) {
        return "minecraft:ender_chest".equals(blockId)
                && properties.keySet().equals(ENDER_CHEST_PROPERTY_NAMES)
                && HORIZONTAL_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(
                        properties.get("waterlogged"));
    }

    /** This only verifies the original vanilla opener type; it never inspects ender chest data. */
    private static boolean isExactEnderChestBlockEntity(
            BotServerPlayer player, BlockTargetFingerprint target) {
        try {
            if (!target.dimension().value().equals(player.serverLevel()
                    .dimension().location().toString())) {
                return false;
            }
            BlockPos position = MinecraftInteractionView.position(
                    target.position());
            return player.serverLevel().isLoaded(position)
                    && player.serverLevel().getBlockEntity(position)
                            instanceof EnderChestBlockEntity;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 末影箱的 27 格 menu 容器属于玩家而不属于 block entity。只有冻结的精确原版
     * ender_chest 指纹才要求其对象恒等；其他 3×9 原版容器继续走既有无绑定快照。
     */
    private static Optional<Container> expectedEnderChestInventory(
            BotServerPlayer player,
            WorldInteractionActionSpec spec) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(spec, "spec");
        if (!(spec instanceof WorldInteractionActionSpec.WorldMenuTransfer menu)) {
            return Optional.empty();
        }
        return expectedEnderChestInventory(player, menu);
    }

    private static Optional<Container> expectedEnderChestInventory(
            BotServerPlayer player,
            WorldInteractionActionSpec.WorldMenuTransfer menu) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        BlockTargetFingerprint target = menu.opener().target();
        return menu.family() == MenuFamily.CHEST_3X9
                        && isEnderChestTarget(
                                target.state().blockId().value(),
                                target.state().properties())
                ? Optional.of(player.getEnderChestInventory())
                : Optional.empty();
    }

    private static boolean isVanillaShulkerTarget(
            String blockId, Map<String, String> properties) {
        return VANILLA_SHULKER_IDS.contains(blockId)
                && properties.keySet().equals(SHULKER_PROPERTY_NAMES)
                && VANILLA_DIRECTION_NAMES.contains(properties.get("facing"));
    }

    private static boolean menuClickAllowed(
            AbstractContainerMenu menu,
            BotServerPlayer player,
            MenuClick click) {
        if (!menu.stillValid(player)) {
            return false;
        }
        Slot slot = menu.getSlot(click.slot());
        if (!slot.mayPickup(player)) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        return click.type() != MenuClickType.PICKUP
                || carried.isEmpty()
                || slot.mayPlace(carried);
    }

    private static ClickType nativeClickType(MenuClickType type) {
        return switch (type) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case SWAP -> ClickType.SWAP;
        };
    }

    /**
     * 把封闭 P5A 材料表绑定为当前 registry access 下的默认原版组件指纹。不能从 source
     * stack 复制组件，否则带自定义数据的物品可能借由配方进入白名单路径。
     */
    private static Map<ResourceId, ItemStackFingerprint>
            vanillaRecipePrototypes(
                    BotServerPlayer player, P5ARecipe recipe) {
        Map<ResourceId, ItemStackFingerprint> prototypes =
                new LinkedHashMap<>();
        for (ResourceId material : recipe.materialIds()) {
            Item item = vanillaRecipeItem(material);
            ItemStackFingerprint prototype =
                    MinecraftInteractionView.itemFingerprint(
                            player, new ItemStack(item));
            if (prototype.isEmpty()
                    || prototype.count() != 1
                    || prototype.damage() != 0
                    || !prototype.itemId().orElseThrow().equals(material)) {
                throw new IllegalStateException(
                        "P5A recipe material did not resolve to its default vanilla stack");
            }
            prototypes.put(material, prototype);
        }
        return Map.copyOf(prototypes);
    }

    private static Item vanillaRecipeItem(ResourceId material) {
        return switch (material.value()) {
            case "minecraft:oak_log" -> Items.OAK_LOG;
            case "minecraft:oak_planks" -> Items.OAK_PLANKS;
            case "minecraft:stick" -> Items.STICK;
            case "minecraft:crafting_table" -> Items.CRAFTING_TABLE;
            case "minecraft:wooden_pickaxe" -> Items.WOODEN_PICKAXE;
            case "minecraft:cobblestone" -> Items.COBBLESTONE;
            case "minecraft:furnace" -> Items.FURNACE;
            case "minecraft:stone_pickaxe" -> Items.STONE_PICKAXE;
            case "minecraft:raw_iron" -> Items.RAW_IRON;
            case "minecraft:chicken" -> Items.CHICKEN;
            case "minecraft:coal" -> Items.COAL;
            case "minecraft:iron_ingot" -> Items.IRON_INGOT;
            case "minecraft:cooked_chicken" -> Items.COOKED_CHICKEN;
            case "minecraft:iron_pickaxe" -> Items.IRON_PICKAXE;
            default -> throw new IllegalStateException(
                    "P5A recipe exposes an unbound vanilla material: "
                            + material.value());
        };
    }

    /**
     * 在把任何物品投入炉子前，用当前服务端的 {@link RecipeType} 查询精确炉型合同。即使某个
     * 数据包 recipe 恰好产生同名物品，只要它不属于配方声明的 smelting/blasting/smoking 类型，
     * 也会被拒绝；反过来，正确类型但输出组件或数量漂移同样失败关闭。
     */
    private static boolean matchesExactFurnaceRecipe(
            BotServerPlayer player,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(prototypes, "prototypes");
        if (!recipe.isFurnace()) {
            return false;
        }
        try {
            RecipeType<?> expectedRecipeType =
                    exactVanillaFurnaceRecipeType(recipe.furnaceKind());
            SingleRecipeInput input = new SingleRecipeInput(
                    new ItemStack(
                            vanillaRecipeItem(recipe.furnaceInput()),
                            recipe.furnaceInputCount()));
            Optional<ItemStack> output = switch (recipe.furnaceKind()) {
                case FURNACE -> {
                    if (expectedRecipeType != RecipeType.SMELTING) {
                        yield Optional.empty();
                    }
                    yield player.serverLevel().getRecipeManager()
                            .getRecipeFor(
                                    RecipeType.SMELTING,
                                    input,
                                    player.serverLevel())
                            .map(holder -> holder.value().assemble(
                                    input,
                                    player.serverLevel().registryAccess()));
                }
                case BLAST_FURNACE -> {
                    if (expectedRecipeType != RecipeType.BLASTING) {
                        yield Optional.empty();
                    }
                    yield player.serverLevel().getRecipeManager()
                            .getRecipeFor(
                                    RecipeType.BLASTING,
                                    input,
                                    player.serverLevel())
                            .map(holder -> holder.value().assemble(
                                    input,
                                    player.serverLevel().registryAccess()));
                }
                case SMOKER -> {
                    if (expectedRecipeType != RecipeType.SMOKING) {
                        yield Optional.empty();
                    }
                    yield player.serverLevel().getRecipeManager()
                            .getRecipeFor(
                                    RecipeType.SMOKING,
                                    input,
                                    player.serverLevel())
                            .map(holder -> holder.value().assemble(
                                    input,
                                    player.serverLevel().registryAccess()));
                }
            };
            ItemStack assembled = output.orElse(ItemStack.EMPTY);
            if (assembled.isEmpty()
                    || !assembled.isItemEnabled(
                            player.serverLevel().enabledFeatures())) {
                return false;
            }
            ItemStackFingerprint expectedPrototype = prototypes.get(
                    recipe.output());
            if (expectedPrototype == null
                    || expectedPrototype.isEmpty()) {
                return false;
            }
            ItemStackFingerprint expected = new ItemStackFingerprint(
                    expectedPrototype.itemId(),
                    recipe.furnaceOutputPerInput(),
                    expectedPrototype.damage(),
                    expectedPrototype.componentsDigest());
            return MinecraftInteractionView.itemFingerprint(
                    player, assembled).equals(expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 以与 {@link CraftingMenu} 相同的服务端 RecipeManager 路径解析每一个计划中间格形。
     * 不能只预测最终 P5A 配方：例如一个木板已经会在原版菜单显示木按钮 preview。
     */
    private static CraftingPreviewResolver nativeCraftingPreviewResolver(
            BotServerPlayer player,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prototypes, "prototypes");
        return postInput -> {
            try {
                CraftingInput input = craftingInputForPreview(
                        player, postInput, prototypes);
                Optional<RecipeHolder<CraftingRecipe>> matched = player
                        .serverLevel()
                        .getRecipeManager()
                        .getRecipeFor(
                                RecipeType.CRAFTING,
                                input,
                                player.serverLevel());
                ResultContainer previewResult = new ResultContainer();
                ItemStack result = matched.filter(recipe -> previewResult
                        .setRecipeUsed(player.serverLevel(), player, recipe))
                        .map(recipe -> recipe.value().assemble(input,
                                player.serverLevel().registryAccess()))
                        .orElse(ItemStack.EMPTY);
                if (!result.isEmpty()
                        && !result.isItemEnabled(player.serverLevel()
                                .enabledFeatures())) {
                    result = ItemStack.EMPTY;
                }
                return Optional.of(MinecraftInteractionView.itemFingerprint(
                        player, result));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        };
    }

    private static CraftingInput craftingInputForPreview(
            BotServerPlayer player,
            MenuSnapshot postInput,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        int width = switch (postInput.family()) {
            case INVENTORY_2X2 -> 2;
            case CRAFTING_3X3 -> 3;
            case FURNACE, MERCHANT,
                    CHEST_3X9,
                    CHEST_6X9 -> throw new IllegalArgumentException(
                            "only native crafting menus have a crafting preview");
        };
        List<ItemStack> inputs = new ArrayList<>(width * width);
        for (int slot = 1; slot <= width * width; slot++) {
            inputs.add(craftingPreviewStack(
                    player, postInput.itemAt(slot), prototypes));
        }
        return CraftingInput.of(width, width, inputs);
    }

    private static ItemStack craftingPreviewStack(
            BotServerPlayer player,
            ItemStackFingerprint fingerprint,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        if (fingerprint.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceId material = fingerprint.itemId().orElseThrow();
        ItemStackFingerprint prototype = prototypes.get(material);
        if (prototype == null
                || !fingerprint.sameItemAndComponents(prototype)
                || fingerprint.damage() != prototype.damage()) {
            throw new IllegalArgumentException(
                    "crafting preview contains a non-default P5A material");
        }
        ItemStack result = new ItemStack(vanillaRecipeItem(material),
                fingerprint.count());
        if (!MinecraftInteractionView.itemFingerprint(player, result)
                .equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "crafting preview material did not round-trip to its full fingerprint");
        }
        return result;
    }

    private static BackendResult menuTransactionFailure(
            ActionEnvelope envelope, MenuTransactionFailure failure) {
        ActionFailureCode code = switch (failure) {
            case TIMEOUT -> ActionFailureCode.DEADLINE_EXCEEDED;
            case CONSERVATION_BREACH ->
                    ActionFailureCode.UNSAFE_CONTROL_STATE;
            case CLICK_DISPATCH_FAILED ->
                    ActionFailureCode.UNSAFE_CONTROL_STATE;
            case INVALID_PLAN -> ActionFailureCode.INVALID_REQUEST;
            case UNEXPECTED_MENU,
                    STALE_STATE,
                    SNAPSHOT_DRIFT,
                    CANCELLED -> ActionFailureCode.PRECONDITION_FAILED;
        };
        return MinecraftWorldInteractionBackend.failure(
                envelope, code, "World menu transaction failed: " + failure);
    }

    private static void dispatchVanillaWorldMenuClick(
            AbstractContainerMenu nativeMenu,
            MenuClick click,
            BotServerPlayer player) {
        nativeMenu.clicked(
                click.slot(),
                click.button(),
                nativeClickType(click.type()),
                player);
        nativeMenu.broadcastChanges();
    }

    /**
     * Preserves the strict snapshot rejection while making a native menu mismatch
     * diagnosable without dumping the full inventory or component digests.
     */
    private static BackendResult menuSnapshotDriftFailure(
            ActionEnvelope envelope,
            MenuTransaction transaction,
            MenuSnapshot observed,
            boolean afterClick) {
        MenuSnapshot expected = expectedMenuSnapshot(
                transaction, afterClick);
        return MinecraftWorldInteractionBackend.failure(
                envelope,
                ActionFailureCode.PRECONDITION_FAILED,
                "World menu transaction failed: SNAPSHOT_DRIFT "
                        + describeMenuSnapshotDifference(expected, observed));
    }

    private static MenuSnapshot expectedMenuSnapshot(
            MenuTransaction transaction, boolean afterClick) {
        MenuTransactionPlan plan = transaction.plan().orElse(null);
        int index = transaction.confirmedClicks();
        if (plan == null || index < 0
                || index >= plan.orderedSteps().size()) {
            return null;
        }
        return afterClick
                ? plan.orderedSteps().get(index).expectedAfter()
                : plan.orderedSteps().get(index).expectedBefore();
    }

    private static String describeMenuSnapshotDifference(
            MenuSnapshot expected, MenuSnapshot observed) {
        if (expected == null || observed == null) {
            return "(menu snapshot unavailable)";
        }
        if (!expected.sameMenu(observed)) {
            return "(native menu identity changed)";
        }
        if (!expected.carried().equals(observed.carried())) {
            return "(cursor expected=" + describeMenuStack(
                    expected.carried()) + ", actual="
                    + describeMenuStack(observed.carried()) + ")";
        }
        for (int slot = 0; slot < expected.slots().size(); slot++) {
            if (!expected.itemAt(slot).equals(observed.itemAt(slot))) {
                return "(slot " + slot + " expected="
                        + describeMenuStack(expected.itemAt(slot))
                        + ", actual="
                        + describeMenuStack(observed.itemAt(slot)) + ")";
            }
        }
        return "(layout differed outside the bounded diagnostic)";
    }

    private static String describeMenuStack(ItemStackFingerprint stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String itemId = stack.itemId().orElseThrow().value();
        if (itemId.length() > 48) {
            itemId = itemId.substring(0, 45) + "...";
        }
        return itemId + "x"
                + stack.count() + " damage=" + stack.damage();
    }

    private void applyNextMenuSwapStep(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap,
            long currentTick) {
        InventoryMenuTransaction transaction =
                Objects.requireNonNull(
                        state.menuTransaction,
                        "menuTransaction");
        InventoryMenuClickStep step =
                transaction.nextClick().orElseThrow(() ->
                        new IllegalStateException(
                                "Inventory menu transaction has no next click"));
        InventoryMenuSnapshot last =
                Objects.requireNonNull(
                        state.menuLastSnapshot,
                        "menuLastSnapshot");
        InventoryMenuSnapshot before =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (!lifecycleManager.mayActionMutateInventory(
                        state.botId, state.botGeneration)
                || !isCurrentActionTarget(state, player)
                || !before.equals(last)
                || !before.layoutEqualsIgnoringState(
                        step.before())
                || !nativeCraftSlotsEmpty(player)
                || !menuStepAllowedNow(player, step)) {
            throw new MenuPreconditionChangedException();
        }
        state.menuForwardInFlight =
                transaction.confirmedClicks();
        state.menuForwardTargetSnapshot = null;
        state.menuCleanupSession.beginClickDispatch(
                currentTick);
        try {
            player.inventoryMenu.clicked(
                    step.menuSlot(),
                    step.hotbarButton(),
                    ClickType.SWAP,
                    player);
            player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException exception) {
            freezeMenuForwardTargetAfterThrow(
                    player, state, step);
            throw exception;
        } finally {
            state.menuCleanupSession.endClickDispatch(
                    currentTick);
        }
        InventoryMenuSnapshot after =
                MinecraftActionSnapshot.inventoryMenu(player);
        boolean exactTarget =
                after.layoutEqualsIgnoringState(
                                step.after())
                        && nativeCraftSlotsEmpty(player);
        if (exactTarget) {
            state.menuForwardTargetSnapshot = after;
        }
        if (!lifecycleManager.mayActionMutateInventory(
                        state.botId, state.botGeneration)
                || player.containerMenu
                        != player.inventoryMenu
                || !isCurrentActionTarget(state, player)) {
            throw new IllegalStateException(
                    "Bot action authority changed during inventory menu click");
        }
        if (!exactTarget) {
            throw new IllegalStateException(
                    "Inventory menu transaction step was not applied exactly");
        }
        state.menuTransaction =
                transaction.confirmNext(step);
        state.menuCleanupSession
                .recordConfirmedForwardProgress();
        state.menuLastSnapshot = after;
        state.menuForwardInFlight = -1;
        state.menuForwardTargetSnapshot = null;
    }

    private static void freezeMenuForwardTargetAfterThrow(
            BotServerPlayer player,
            InteractionState state,
            InventoryMenuClickStep step) {
        try {
            InventoryMenuSnapshot after =
                    MinecraftActionSnapshot
                            .inventoryMenu(player);
            if (after.layoutEqualsIgnoringState(
                    step.after())) {
                state.menuForwardTargetSnapshot =
                        after;
                return;
            }
        } catch (RuntimeException ignored) {
            // Cleanup will fail closed without a frozen in-flight target.
        }
    }

    private static boolean menuStepAllowedNow(
            BotServerPlayer player,
            InventoryMenuClickStep step) {
        Slot clicked =
                player.inventoryMenu.getSlot(step.menuSlot());
        Slot hotbar = player.inventoryMenu.getSlot(
                PlayerInventoryMenuLayout
                        .menuSlotForInventorySlot(
                                step.hotbarButton()));
        ItemStack clickedItem = clicked.getItem();
        ItemStack hotbarItem = hotbar.getItem();
        return (clickedItem.isEmpty()
                        || clicked.mayPickup(player))
                && (hotbarItem.isEmpty()
                        || hotbar.mayPickup(player))
                && mayPlaceCompleteStack(
                        clicked, hotbarItem)
                && mayPlaceCompleteStack(
                        hotbar, clickedItem);
    }

    private void dispatchUseItem(
            BotServerPlayer player,
            WorldInteractionActionSpec.UseItem useItem) {
        player.connection.handleUseItem(
                new ServerboundUseItemPacket(
                        MinecraftInteractionView.hand(useItem.hand()),
                        nextSequence(),
                        player.getYRot(),
                        player.getXRot()));
    }

    private void dispatchReleaseUse(BotServerPlayer player) {
        player.connection.handlePlayerAction(
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO,
                        Direction.DOWN,
                        nextSequence()));
    }

    private void dispatchBreak(
            BotServerPlayer player,
            WorldInteractionActionSpec.BreakBlock breakBlock,
            ServerboundPlayerActionPacket.Action action) {
        BlockPos position =
                MinecraftInteractionView.position(
                        breakBlock.target().target().position());
        player.connection.handlePlayerAction(
                new ServerboundPlayerActionPacket(
                        action,
                        position,
                        MinecraftInteractionView.direction(
                                breakBlock.target().face()),
                        nextSequence()));
    }

    private BackendResult tickBreak(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.BreakBlock breakBlock,
            long currentTick) {
        BlockPos position =
                MinecraftInteractionView.position(
                        breakBlock.target().target().position());
        BlockTargetFingerprint current =
                MinecraftInteractionView.blockFingerprint(player, position);
        if (!current.equals(breakBlock.target().target())) {
            return BackendResult.readyToVerify(envelope);
        }
        if (!MinecraftInteractionView.itemFingerprint(
                        player, player.getMainHandItem())
                .equals(breakBlock.expectedTool())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Break tool changed while mining");
        }
        BackendResult neighbors = validateBreakNeighborPreconditions(
                envelope, player, breakBlock);
        if (neighbors.step() != BackendStep.ACCEPTED) {
            return neighbors;
        }
        MinecraftInteractionView.BlockReachEvidence reach =
                MinecraftInteractionView.blockReachEvidence(
                        player, breakBlock.target());
        if (!reach.withinReach() || !reach.rayHitTarget()) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    blockReachEvidence(reach),
                    "Block reach or line of sight changed while mining");
        }

        BlockState blockState =
                player.serverLevel().getBlockState(position);
        float progressPerTick =
                blockState.getDestroyProgress(
                        player, player.serverLevel(), position);
        long elapsed = currentTick - state.startedTick + 1L;
        if (progressPerTick > 0.0F
                && progressPerTick * (double) elapsed >= 1.0D) {
            /* Re-read the complete source/hand/neighbour fence in the same call
             * that will emit STOP_DESTROY_BLOCK. This is intentionally stronger
             * than the per-tick check above: a vanilla structural block must not
             * cross the last pre-dispatch boundary on a stale observation. */
            BackendResult beforeStop = validateBreakBlock(envelope, player,
                    breakBlock);
            if (beforeStop.step() != BackendStep.ACCEPTED) {
                return beforeStop;
            }
            BreakDropProvenanceCapture.Scope capture;
            try {
                capture = BreakDropProvenanceCapture.arm(player,
                        breakBlock.target().target(), state.botGeneration);
            } catch (RuntimeException exception) {
                return failure(
                        envelope,
                        ActionFailureCode.INTERNAL_ERROR,
                        "Could not arm exact vanilla block-drop provenance");
            }
            try {
                dispatchBreak(
                        player,
                        breakBlock,
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
                state.breakStopSent = true;
            } finally {
                capture.close();
            }
            state.breakDropProvenances = capture.provenances();
            return BackendResult.readyToVerify(envelope);
        }
        return BackendResult.running(envelope);
    }

    private BackendResult tickUse(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.UseItem useItem,
            long currentTick) {
        if (state.strictUseCompletionPhase
                .hasEnteredNativeCompletion()) {
            /*
             * Completion entered before vanilla's call. Its eventual physical
             * result must be verified, rather than being overwritten by a
             * cancellation that arrived from a Finish/Post callback.
             */
            return BackendResult.readyToVerify(envelope);
        }
        if (state.strictUseStopReason
                == StrictUseStopReason.PRECONDITION_DRIFT) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Strict item-use preflight changed before native consumption");
        }
        if (state.strictUseStopReason
                == StrictUseStopReason.CANCELLATION_FENCED) {
            /*
             * The Mixin has stopped vanilla use, but the accepted exact
             * cancellation may still be behind earlier mailbox commands. Keep
             * this action live until that command drains; otherwise a low
             * command budget would turn cancellation into PRECONDITION_FAILED.
             */
            return BackendResult.running(envelope);
        }
        if (!state.startedUsing
                && useItem.mode()
                        != WorldInteractionActionSpec.ItemUseMode
                                .INSTANT) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Declared item use did not start");
        }
        if (player.isUsingItem()
                && (player.getUsedItemHand()
                                != MinecraftInteractionView.hand(
                                        useItem.hand())
                        || !MinecraftInteractionView.itemFingerprint(
                                        player, player.getUseItem())
                                .sameItemAndComponents(
                                        useItem.expectedHeldItem()))) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Active item use no longer matches the declared hand and stack");
        }
        if (player.isUsingItem() && useItem.strictPreconditions().isPresent()) {
            BackendResult strict = validateUseItemPreconditions(envelope,
                    player, useItem.strictPreconditions().orElseThrow());
            if (strict.step() != BackendStep.ACCEPTED) {
                interruptStrictUse(player, state);
                return strict;
            }
        }
        return switch (useItem.mode()) {
            case INSTANT -> BackendResult.readyToVerify(envelope);
            case FINISH_NATURALLY -> player.isUsingItem()
                    ? BackendResult.running(envelope)
                    : BackendResult.readyToVerify(envelope);
            case RELEASE_AFTER_HOLD -> {
                if (currentTick - state.startedTick
                        < useItem.holdTicks()) {
                    yield BackendResult.running(envelope);
                }
                state.releaseSent = true;
                dispatchReleaseUse(player);
                yield BackendResult.readyToVerify(envelope);
            }
        };
    }

    /**
     * A strict-use drift is discovered while vanilla is still holding the item.
     * Release through the normal packet path and stop the native use immediately;
     * neither inventory nor effects are edited here. The action runtime will
     * still perform its ordinary terminal cleanup, for which {@code releaseSent}
     * suppresses a duplicate packet.
     */
    private void interruptStrictUse(BotServerPlayer player,
            InteractionState state) {
        if (!player.isUsingItem() || state.releaseSent) {
            return;
        }
        /* Mark before packet dispatch: RELEASE_USE_ITEM callbacks can re-enter. */
        state.releaseSent = true;
        try {
            dispatchReleaseUse(player);
        } finally {
            player.stopUsingItem();
        }
    }

    private BackendResult tickPickup(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.PickupWait pickupWait,
            long currentTick) {
        if (!state.pickupEntityIds.isEmpty()
                && state.pickupEntityIds.stream().allMatch(entityId -> {
                    Entity entity = player.serverLevel().getEntity(entityId);
                    return entity == null || entity.isRemoved();
                })) {
            return BackendResult.readyToVerify(envelope);
        }
        return currentTick - state.startedTick >= pickupWait.ticks()
                ? BackendResult.readyToVerify(envelope)
                : BackendResult.running(envelope);
    }

    private BackendResult tickMenuSwap(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap,
            long currentTick) {
        if (!state.menuCleanupSession
                .mayDispatchClickAt(currentTick)) {
            return BackendResult.running(envelope);
        }
        if (!lifecycleManager.mayActionMutateInventory(
                envelope.botId(), envelope.botGeneration())) {
            return failure(
                    envelope,
                    ActionFailureCode.CHANNEL_BUSY,
                    "Bot inventory became write-locked during menu transaction");
        }
        InventoryMenuTransaction transaction =
                state.menuTransaction;
        if (transaction == null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "Inventory menu transaction state is missing");
        }
        if (transaction.state()
                == InventoryMenuTransactionState.VERIFYING) {
            return BackendResult.readyToVerify(envelope);
        }
        if (transaction.state()
                != InventoryMenuTransactionState.RUNNING) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "Inventory menu transaction entered an invalid state");
        }
        try {
            applyNextMenuSwapStep(
                    player,
                    state,
                    menuSwap,
                    currentTick);
        } catch (MenuPreconditionChangedException exception) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory menu transaction precondition changed before click");
        }
        return state.menuTransaction.state()
                        == InventoryMenuTransactionState
                                .VERIFYING
                ? BackendResult.readyToVerify(envelope)
                : BackendResult.running(envelope);
    }

    private BackendResult verifySelect(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.SelectHotbar selectHotbar) {
        ItemStackFingerprint actual =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory().getItem(selectHotbar.slot()));
        if (player.getInventory().selected != selectHotbar.slot()
                || !actual.equals(selectHotbar.expectedSlotItem())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Hotbar selection was not applied");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "inventory.selected_slot",
                                Integer.toString(selectHotbar.slot())),
                        evidence(
                                "inventory.item_count",
                                Integer.toString(actual.count()))),
                "Selected hotbar slot");
    }

    private BackendResult verifySwap(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.SwapInventoryHotbar swap) {
        if (player.containerMenu != player.inventoryMenu) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Bot inventory menu changed during swap");
        }
        ItemStackFingerprint sourceAfter =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.sourceInventorySlot()));
        ItemStackFingerprint targetAfter =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.targetHotbarSlot()));
        if (!sourceAfter.equals(swap.expectedTarget())
                || !targetAfter.equals(swap.expectedSource())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory and hotbar stacks were not exactly swapped");
        }
        String multisetAfter =
                MinecraftInteractionView.inventoryMultisetDigest(player);
        if (!multisetAfter.equals(state.inventoryMultisetBefore)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Inventory conservation check failed after swap");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "inventory.source_slot",
                                Integer.toString(
                                        swap.sourceInventorySlot())),
                        evidence(
                                "inventory.target_hotbar_slot",
                                Integer.toString(
                                        swap.targetHotbarSlot())),
                        evidence(
                                "inventory.multiset_preserved",
                                "true")),
                "Verified inventory hotbar swap");
    }

    private BackendResult verifyMenuSwap(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap) {
        if (player.containerMenu != player.inventoryMenu
                || !isCurrentActionTarget(state, player)
                || !nativeCraftSlotsEmpty(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Native inventory menu changed during transaction");
        }
        InventoryMenuTransaction transaction =
                state.menuTransaction;
        InventoryMenuSnapshot last =
                state.menuLastSnapshot;
        InventoryMenuSnapshot actual =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (transaction == null
                || transaction.state()
                        != InventoryMenuTransactionState
                                .VERIFYING
                || last == null
                || !actual.equals(last)
                || !actual.layoutEqualsIgnoringState(
                        menuSwap.plan().finalSnapshot())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory menu transaction final snapshot changed");
        }
        String multisetAfter =
                MinecraftInteractionView
                        .inventoryMultisetDigest(player);
        if (!multisetAfter.equals(
                state.inventoryMultisetBefore)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Inventory conservation check failed after menu transaction");
        }
        state.menuTransaction = transaction.commit();
        return success(
                envelope,
                List.of(
                        evidence(
                                "menu.container_id",
                                Integer.toString(
                                        actual.containerId())),
                        evidence(
                                "menu.state_id",
                                Integer.toString(
                                        actual.stateId())),
                        evidence(
                                "menu.click_count",
                                Integer.toString(
                                        transaction
                                                .confirmedClicks())),
                        evidence(
                                "inventory.multiset_preserved",
                                "true")),
                "Verified native inventory menu transaction");
    }

    private BackendResult verifyUse(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.UseItem useItem) {
        ItemStackFingerprint after =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getItemInHand(
                                MinecraftInteractionView.hand(
                                        useItem.hand())));
        String inventoryAfter =
                MinecraftInteractionView.inventoryDigest(player);
        int foodLevelAfter =
                player.getFoodData().getFoodLevel();
        boolean itemOrInventoryChanged =
                !after.equals(state.heldBefore)
                        || !inventoryAfter.equals(state.inventoryBefore)
                        || state.releaseSent;
        boolean observable =
                itemOrInventoryChanged
                        || (useItem.mode()
                                        != WorldInteractionActionSpec
                                                .ItemUseMode
                                                .FINISH_NATURALLY
                                && state.startedUsing);
        if (!observable
                || (useItem.mode()
                                != WorldInteractionActionSpec.ItemUseMode
                                        .FINISH_NATURALLY
                        && player.isUsingItem())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Item use produced no verified completion");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "item.before_id",
                                itemIdentityId(state.heldBefore)),
                        evidence(
                                "item.before_damage",
                                itemIdentityDamage(state.heldBefore)),
                        evidence(
                                "item.before_components",
                                itemIdentityComponents(
                                        state.heldBefore)),
                        evidence(
                                "item.before_count",
                                Integer.toString(state.heldBefore.count())),
                        evidence(
                                "item.after_id",
                                itemIdentityId(after)),
                        evidence(
                                "item.after_damage",
                                itemIdentityDamage(after)),
                        evidence(
                                "item.after_components",
                                itemIdentityComponents(after)),
                        evidence(
                                "item.after_count",
                                Integer.toString(after.count())),
                        evidence(
                                "item.use_started",
                                Boolean.toString(state.startedUsing)),
                        evidence(
                                "player.food_before",
                                Integer.toString(state.foodLevelBefore)),
                        evidence(
                                "player.food_after",
                                Integer.toString(foodLevelAfter))),
                "Verified item use");
    }

    private static String itemIdentityId(
            ItemStackFingerprint fingerprint) {
        return fingerprint.itemId()
                .map(ResourceIdEvidence::encode)
                .orElse("empty");
    }

    private static String itemIdentityDamage(
            ItemStackFingerprint fingerprint) {
        return fingerprint.isEmpty()
                ? "empty"
                : Integer.toString(fingerprint.damage());
    }

    private static String itemIdentityComponents(
            ItemStackFingerprint fingerprint) {
        return fingerprint.componentsDigest().orElse("empty");
    }

    private BackendResult verifyRelease(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.ReleaseUse releaseUse) {
        if (player.isUsingItem()) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Used item was not released");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "item.release_hand",
                                releaseUse.hand().name().toLowerCase())),
                "Released held item use");
    }

    private BackendResult verifyUseOn(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.UseOnBlock useOnBlock) {
        BlockPos position =
                MinecraftInteractionView.position(
                        useOnBlock.target().target().position());
        BlockTargetFingerprint blockAfter =
                MinecraftInteractionView.blockFingerprint(player, position);
        ItemStackFingerprint heldAfter =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getItemInHand(
                                MinecraftInteractionView.hand(
                                        useOnBlock.hand())));
        String inventoryAfter =
                MinecraftInteractionView.inventoryDigest(player);
        boolean changed =
                !blockAfter.equals(state.blockBefore)
                        || !heldAfter.equals(state.heldBefore)
                        || !inventoryAfter.equals(state.inventoryBefore)
                        || player.containerMenu.containerId
                                != state.containerIdBefore;
        if (!changed) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block use produced no observable result");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "block.position",
                                position.getX()
                                        + ","
                                        + position.getY()
                                        + ","
                                        + position.getZ()),
                        evidence(
                                "block.changed",
                                Boolean.toString(
                                        !blockAfter.equals(
                                                state.blockBefore))),
                        evidence(
                                "inventory.changed",
                                Boolean.toString(
                                        !inventoryAfter.equals(
                                                state.inventoryBefore)))),
                "Verified block use");
    }

    private BackendResult verifyPlaceBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.PlaceBlock placeBlock) {
        return verifyExactBlockPlacement(
                envelope,
                player,
                state,
                placeBlock.anchor(),
                placeBlock.expectedPlaced(),
                List.of(),
                "Verified exact block placement");
    }

    private BackendResult verifyAimAndPlaceBlock(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace) {
        if (!state.aimAndPlaceFinalFencePassed
                || !Double.isFinite(state.aimAndPlaceBeforeAimError)
                || !Double.isFinite(state.aimAndPlaceDispatchAimError)) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Atomic aim-and-place did not pass its final packet fence");
        }
        BlockHitTarget anchor = aimAndPlace.anchor();
        double verifyAimError = viewAngleDegrees(
                player, anchor.worldX(), anchor.worldY(), anchor.worldZ());
        List<ActionEvidence> aimEvidence = List.of(
                evidence(
                        "aim.before_error_deg",
                        Double.toString(
                                state.aimAndPlaceBeforeAimError)),
                evidence(
                        "aim.after_error_deg",
                        Double.toString(
                                state.aimAndPlaceDispatchAimError)),
                evidence("aim.verify_error_deg", Double.toString(verifyAimError)));
        if (!Double.isFinite(verifyAimError)
                || verifyAimError > AIM_AND_PLACE_LOOK_TOLERANCE_DEGREES) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    aimEvidence,
                    "Atomic placement view no longer targets the frozen hit");
        }
        return verifyExactBlockPlacement(
                envelope,
                player,
                state,
                anchor,
                aimAndPlace.expectedPlaced(),
                aimEvidence,
                "Verified atomic aim-and-place exact block state");
    }

    private BackendResult verifyExactBlockPlacement(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            BlockHitTarget anchor,
            BlockTargetFingerprint expectedPlaced,
            List<ActionEvidence> additionalEvidence,
            String successSummary) {
        BlockPos destination = MinecraftInteractionView.position(
                expectedPlaced.position());
        if (!player.serverLevel().isLoaded(destination)) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Block placement destination became unavailable before verification");
        }
        if (player.containerMenu != player.inventoryMenu
                || player.containerMenu.containerId != state.containerIdBefore
                || !player.inventoryMenu.stillValid(player)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement did not retain the native inventory menu");
        }
        BlockTargetFingerprint actual = MinecraftInteractionView.blockFingerprint(
                player, destination);
        if (!actual.equals(expectedPlaced)) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement did not produce the exact expected block state");
        }
        BlockPos anchorPosition = MinecraftInteractionView.position(
                anchor.target().position());
        if (!MinecraftInteractionView.blockFingerprint(player, anchorPosition)
                .equals(anchor.target())) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Block placement changed its anchor block");
        }
        ItemStackFingerprint heldAfter = MinecraftInteractionView.itemFingerprint(
                player, player.getMainHandItem());
        boolean consumedExactlyOne = state.heldBefore.count() == 1
                ? heldAfter.isEmpty()
                : heldAfter.sameItemAndComponents(state.heldBefore)
                        && heldAfter.count() == state.heldBefore.count() - 1;
        if (!consumedExactlyOne) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Block placement did not consume exactly one held block");
        }
        List<ActionEvidence> placementEvidence = new ArrayList<>(
                4 + additionalEvidence.size());
        placementEvidence.add(evidence(
                "block.position",
                destination.getX()
                        + ","
                        + destination.getY()
                        + ","
                        + destination.getZ()));
        placementEvidence.add(evidence("block.matches_expected", "true"));
        placementEvidence.add(evidence(
                "item.before_count",
                Integer.toString(state.heldBefore.count())));
        placementEvidence.add(evidence(
                "item.after_count",
                Integer.toString(heldAfter.count())));
        placementEvidence.addAll(additionalEvidence);
        return success(envelope, List.copyOf(placementEvidence), successSummary);
    }

    private BackendResult verifyBreak(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.BreakBlock breakBlock) {
        BlockPos position =
                MinecraftInteractionView.position(
                        breakBlock.target().target().position());
        BlockState current =
                player.serverLevel().getBlockState(position);
        if (!current.isAir()) {
            return failure(
                    envelope,
                    state.breakStopSent
                            ? ActionFailureCode.PERMISSION_DENIED
                            : ActionFailureCode.PRECONDITION_FAILED,
                    state.breakStopSent
                            ? "Block break was denied by world rules"
                            : "Block target changed before break completion");
        }
        BackendResult neighbors = validateBreakNeighborPreconditions(
                envelope, player, breakBlock);
        if (neighbors.step() != BackendStep.ACCEPTED) {
            return neighbors;
        }
        List<ActionEvidence> evidence = new ArrayList<>(
                VERIFY_BREAK_BASE_EVIDENCE_ITEMS + 3);
        evidence.add(evidence(
                "block.position",
                position.getX()
                        + ","
                        + position.getY()
                        + ","
                        + position.getZ()));
        evidence.add(evidence("block.after", "minecraft:air"));
        evidence.add(evidence(
                "inventory.changed",
                Boolean.toString(!MinecraftInteractionView.inventoryDigest(player)
                        .equals(state.inventoryBefore))));
        if (state.breakDropProvenances.isPresent()) {
            Optional<List<ActionEvidence>> receipts = breakDropEvidence(
                    state.breakDropProvenances.orElseThrow());
            if (receipts.isEmpty()) {
                return failure(
                        envelope,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "Captured block drops exceed the bounded evidence protocol");
            }
            evidence.addAll(receipts.orElseThrow());
        }
        return success(envelope, List.copyOf(evidence),
                "Verified block break");
    }

    private BackendResult verifyAttack(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.AttackEntity attackEntity) {
        Entity entity =
                player.serverLevel()
                        .getEntity(attackEntity.target().entityId());
        boolean removed = entity == null || entity.isRemoved();
        float healthAfter =
                entity instanceof LivingEntity living
                        ? living.getHealth()
                        : state.targetHealthBefore;
        boolean affected =
                removed || healthAfter < state.targetHealthBefore;
        if (!affected) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Attack produced no verified target effect");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "entity.id",
                                attackEntity.target()
                                        .entityId()
                                        .toString()),
                        evidence(
                                "entity.removed",
                                Boolean.toString(removed)),
                        evidence(
                                "entity.health_before",
                                Float.toString(
                                        state.targetHealthBefore)),
                        evidence(
                                "entity.health_after",
                                Float.toString(healthAfter))),
                "Verified entity attack");
    }

    private BackendResult verifyInteract(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InteractEntity interactEntity) {
        Entity entity =
                player.serverLevel()
                        .getEntity(interactEntity.target().entityId());
        boolean observable =
                entity == null
                        || entity.isRemoved()
                        || player.getVehicle() != state.vehicleBefore
                        || player.containerMenu.containerId
                                != state.containerIdBefore
                        || !MinecraftInteractionView.inventoryDigest(player)
                                .equals(state.inventoryBefore)
                        || !MinecraftInteractionView.itemFingerprint(
                                        player,
                                        player.getItemInHand(
                                                MinecraftInteractionView
                                                        .hand(
                                                                interactEntity
                                                                        .hand())))
                                .equals(state.heldBefore);
        if (!observable) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Entity interaction produced no observable result");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "entity.id",
                                interactEntity.target()
                                        .entityId()
                                        .toString()),
                        evidence(
                                "entity.interaction_specific",
                                Boolean.toString(
                                        interactEntity
                                                .usesSpecificInteraction()))),
                "Verified entity interaction");
    }

    private BackendResult verifyDrop(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.DropSelected dropSelected) {
        ItemStackFingerprint selectedAfter =
                MinecraftInteractionView.itemFingerprint(
                        player, player.getInventory().getSelected());
        int expectedMaximum =
                dropSelected.entireStack()
                        ? 0
                        : Math.max(0, state.heldBefore.count() - 1);
        if (selectedAfter.sameItemAndComponents(state.heldBefore)
                && selectedAfter.count() > expectedMaximum) {
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Selected item count did not decrease after drop");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "item.before_count",
                                Integer.toString(state.heldBefore.count())),
                        evidence(
                                "item.after_count",
                                Integer.toString(selectedAfter.count())),
                        evidence(
                                "item.drop_all",
                                Boolean.toString(
                                        dropSelected.entireStack()))),
                "Verified selected item drop");
    }

    private BackendResult verifyPickup(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.PickupWait pickupWait) {
        String inventoryAfter =
                MinecraftInteractionView.inventoryDigest(player);
        if (state.pickupEntityIds.isEmpty()) {
            if (inventoryAfter.equals(state.inventoryBefore)) {
                return failure(
                        envelope,
                        ActionFailureCode.TARGET_UNAVAILABLE,
                        "Pickup wait observed no inventory change");
            }
            return success(
                    envelope,
                    List.of(evidence("inventory.changed", "true")),
                    "Verified inventory pickup");
        }

        if (state.pickupReceipts.size() != state.pickupEntityIds.size()) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Expected item entity changed before pickup wait began");
        }
        for (UUID entityId : state.pickupEntityIds) {
            Entity remaining = player.serverLevel().getEntity(entityId);
            if (remaining != null && !remaining.isRemoved()) {
                return failure(
                        envelope,
                        ActionFailureCode.TARGET_UNAVAILABLE,
                        "Expected item entity was not collected");
            }
        }
        for (PickupItemExpectation expectation : pickupItemExpectations(
                state.pickupReceipts)) {
            int matchingAfter = MinecraftInteractionView.inventoryCount(
                    player, expectation.item());
            int gained = matchingAfter - expectation.matchingCountBefore();
            if (gained < expectation.expectedCount()) {
                return failure(
                        envelope,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "Pickup item conservation check failed");
            }
        }
        if (state.pickupEntityIds.size() == 1) {
            PickupReceipt receipt = state.pickupReceipts.get(0);
            int matchingAfter = MinecraftInteractionView.inventoryCount(
                    player, receipt.item());
            int gained = matchingAfter - receipt.matchingCountBefore();
            return success(
                    envelope,
                    List.of(
                            evidence("entity.id", receipt.entityId()
                                    .toString()),
                            evidence("item.expected_count", Integer.toString(
                                    receipt.item().count())),
                            evidence("item.gained_count", Integer.toString(
                                    gained))),
                    "Verified item entity pickup");
        }
        return success(
                envelope,
                List.of(
                        evidence("entity.ids", state.pickupEntityIds.stream()
                                .map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining(","))),
                        evidence("entity.count", Integer.toString(
                                state.pickupEntityIds.size()))),
                "Verified bounded item entity pickup");
    }

    private static List<PickupItemExpectation> pickupItemExpectations(
            List<PickupReceipt> receipts) {
        List<PickupItemExpectation> expectations = new ArrayList<>();
        for (PickupReceipt receipt : receipts) {
            int matching = -1;
            for (int index = 0; index < expectations.size(); index++) {
                if (expectations.get(index).item().sameItemAndComponents(
                        receipt.item())) {
                    matching = index;
                    break;
                }
            }
            if (matching < 0) {
                expectations.add(new PickupItemExpectation(receipt.item(),
                        receipt.matchingCountBefore(),
                        receipt.item().count()));
                continue;
            }
            PickupItemExpectation existing = expectations.get(matching);
            if (existing.matchingCountBefore()
                    != receipt.matchingCountBefore()) {
                throw new IllegalStateException(
                        "pickup receipt inventory baseline changed during capture");
            }
            expectations.set(matching, new PickupItemExpectation(
                    existing.item(), existing.matchingCountBefore(),
                    Math.addExact(existing.expectedCount(),
                            receipt.item().count())));
        }
        return List.copyOf(expectations);
    }

    private void cleanupPlayerState(
            BotServerPlayer player,
            InteractionState state,
            ActionCleanupReason reason,
            long currentTick,
            boolean replacementInheritor) {
        if (reason == ActionCleanupReason.SUCCEEDED
                && state.spec
                        instanceof WorldInteractionActionSpec
                                .InventoryMenuSwap
                && (state.menuTransaction == null
                        || state.menuTransaction.state()
                                != InventoryMenuTransactionState
                                        .COMMITTED)) {
            throw new IllegalStateException(
                    "Successful menu action lacks a committed transaction");
        }
        if (reason == ActionCleanupReason.SUCCEEDED
                && (state.spec
                                instanceof WorldInteractionActionSpec
                                        .WorldMenuTransaction
                        || state.spec
                                instanceof WorldInteractionActionSpec
                                        .WorldMenuTransfer
                        || state.spec
                                instanceof WorldInteractionActionSpec
                                        .WorldMenuRecipe
                        || state.spec
                                instanceof WorldInteractionActionSpec
                                        .WorldVillagerTrade)
                && (state.worldMenuTransaction == null
                        || state.worldMenuTransaction.state()
                                != MenuTransactionState.COMPLETED)) {
            throw new IllegalStateException(
                    "Successful world menu action lacks a completed transaction");
        }
        if (reason != ActionCleanupReason.SUCCEEDED
                && state.sideEffectDispatched) {
            if (state.spec
                    instanceof WorldInteractionActionSpec
                            .SwapInventoryHotbar swap) {
                rollbackSwap(player, state, swap);
            } else if (state.spec
                    instanceof WorldInteractionActionSpec
                            .InventoryMenuSwap menuSwap) {
                settleMenuSwap(
                        player,
                        state,
                        menuSwap,
                        currentTick,
                        replacementInheritor);
            } else if (state.spec
                    instanceof WorldInteractionActionSpec.WorldMenuTransaction
                    || state.spec
                            instanceof WorldInteractionActionSpec.WorldMenuTransfer
                    || state.spec
                            instanceof WorldInteractionActionSpec.WorldMenuRecipe) {
                closeWorldMenuDuringCleanup(player, state);
            } else if (state.spec
                    instanceof WorldInteractionActionSpec.WorldVillagerTrade
                            trade) {
                closeVillagerTradeDuringCleanup(player, state, trade);
            } else if (state.spec
                    instanceof WorldInteractionActionSpec.PlaceBlock
                    || state.spec
                            instanceof WorldInteractionActionSpec
                                    .AimAndPlaceBlock) {
                closeWorldMenuDuringCleanup(player, state);
            } else if (state.spec
                    instanceof WorldInteractionActionSpec
                            .SelectHotbar select) {
                rollbackSelection(player, state, select);
            }
        }
        if (!replacementInheritor
                && state.spec
                        instanceof WorldInteractionActionSpec
                                .BreakBlock breakBlock) {
            dispatchBreak(
                    player,
                    breakBlock,
                    ServerboundPlayerActionPacket.Action
                            .ABORT_DESTROY_BLOCK);
        }
        /*
         * A staged replacement is accepted only after the predecessor has
         * left every ServerLevel and the shared listener owns the new body.
         * Its gameMode therefore cannot carry the predecessor's destroy
         * progress. Sending an old-dimension ABORT packet through the new
         * body would be a cross-world mutation, so that bookkeeping is
         * discarded without dispatch.
         */
        if (state.spec instanceof WorldInteractionActionSpec.UseItem
                        || state.spec
                                instanceof WorldInteractionActionSpec
                                        .ReleaseUse) {
            if (player.isUsingItem()) {
                dispatchReleaseUse(player);
            }
            player.stopUsingItem();
        }
    }

    /**
     * generic menu 没有可逆的“直接回写库存”路径；取消时只允许原版关闭窗口处理 carried
     * stack，并且必须同步回到 native inventory menu，避免把半开的容器交给后续动作。
     */
    private static void closeWorldMenuDuringCleanup(
            BotServerPlayer player, InteractionState state) {
        if (state.worldMenuTransaction != null) {
            state.worldMenuTransaction.cancel();
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        if (!nativeInventoryMenuHasEmptyCursor(player)) {
            throw new IllegalStateException(
                    "World menu cleanup could not restore native menu with an empty cursor");
        }
    }

    /**
     * 取消不尝试直接把 payment 或 result 写回村民/库存。先让原版关闭 MerchantMenu，再只接受
     * 两种可证明结算：关闭返还后的完整原样，或最后一次 QUICK_MOVE 已经完成的一笔精确交易。
     */
    private static void closeVillagerTradeDuringCleanup(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.WorldVillagerTrade trade) {
        closeWorldMenuDuringCleanup(player, state);
        if (!exactNativeInventoryMenuHasEmptyCursor(player)) {
            throw new IllegalStateException(
                    "Villager trade cleanup did not restore the exact native inventory menu");
        }
        MerchantTradeSession session = state.merchantTradeSession;
        if (session == null) {
            // interaction packet may have opened an unknown menu and failed before binding one.
            // The dispatch baseline still has to prove that this path made no inventory/offer change.
            InventoryMenuSnapshot before = state.villagerTradeInventoryBefore;
            Entity resolved = MinecraftInteractionView.entity(player,
                            trade.villager())
                    .orElse(null);
            Villager villager = resolved != null
                    && resolved.getClass() == Villager.class
                            ? (Villager) resolved : null;
            MerchantOffer offer = villager == null ? null
                    : merchantOfferAt(villager, trade.offerIndex())
                            .orElse(null);
            boolean offerMatches = offer != null && merchantOfferMatches(
                    player,
                    villager,
                    offer,
                    trade,
                    trade.expectedOfferUses(),
                    trade.expectedVillagerXp(),
                    true);
            boolean villagerDataMatches = state.villagerTradeVillagerDataBefore
                    == null || villager != null && villager.getVillagerData()
                            == state.villagerTradeVillagerDataBefore;
            boolean offerStateMatches = state.villagerTradeOfferStateBefore
                    == null || offer != null && merchantOfferStaticState(
                            player, offer)
                    .filter(state.villagerTradeOfferStateBefore::equals)
                    .isPresent();
            if (before == null
                    || villager == null
                    || villager.getTradingPlayer() != null
                    || !MinecraftActionSnapshot.inventoryMenu(player)
                            .layoutEqualsIgnoringState(before)
                    || !offerMatches
                    || !villagerDataMatches
                    || !offerStateMatches) {
                throw new IllegalStateException(
                        "Unbound villager trade cleanup cannot prove unchanged vanilla state");
            }
            return;
        }
        Entity resolved = MinecraftInteractionView.entity(player,
                        trade.villager())
                .orElse(null);
        if (resolved != session.villager
                || resolved == null
                || resolved.getClass() != Villager.class
                || session.villager.getTradingPlayer() != null
                || merchantOfferAt(session.villager, session.offerIndex)
                        .orElse(null) != session.offer) {
            throw new IllegalStateException(
                    "Villager trade cleanup lost its exact entity or offer identity");
        }
        InventoryContentsSnapshot after =
                MinecraftInteractionView.inventoryContents(player);
        VillagerTradeInventoryConservation.CancellationSettlement settlement =
                VillagerTradeInventoryConservation.cancellationSettlement(
                        session.inventoryBefore,
                        after,
                        trade.expectedCost(),
                        trade.expectedResult());
        boolean completed = settlement
                == VillagerTradeInventoryConservation.CancellationSettlement
                        .COMPLETED;
        if (settlement
                == VillagerTradeInventoryConservation.CancellationSettlement
                        .UNSAFE) {
            throw new IllegalStateException(
                    "Villager trade cancellation did not settle to a proven vanilla boundary");
        }
        int expectedUses;
        int expectedVillagerXp;
        try {
            expectedUses = Math.addExact(
                    session.usesBefore, completed ? 1 : 0);
            expectedVillagerXp = completed
                    ? session.villagerXpAfter
                    : session.villagerXpBefore;
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Villager trade cleanup offer uses overflowed", exception);
        }
        if (!merchantOfferMatches(
                        player,
                        session.villager,
                        session.offer,
                        trade,
                        expectedUses,
                        expectedVillagerXp,
                        expectedUses < trade.expectedOfferMaxUses())
                || session.villager.getVillagerData()
                        != session.villagerDataBefore
                || !merchantOfferStaticState(player, session.offer)
                        .filter(session.offerState::equals)
                        .isPresent()
                || !merchantTradeInventoryLayoutMatches(
                        player, session, trade, completed)) {
            throw new IllegalStateException(
                    "Villager trade cancellation observed offer or inventory drift");
        }
    }

    private static boolean nativeInventoryMenuHasEmptyCursor(
            BotServerPlayer player) {
        return player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty();
    }

    /** P5B never accepts a custom inventory menu as the native inventory settlement surface. */
    private static boolean exactNativeInventoryMenuHasEmptyCursor(
            BotServerPlayer player) {
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

    private void rollbackSwap(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.SwapInventoryHotbar swap) {
        ItemStackFingerprint source =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.sourceInventorySlot()));
        ItemStackFingerprint target =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.targetHotbarSlot()));
        if (source.equals(swap.expectedSource())
                && target.equals(swap.expectedTarget())) {
            String restoredMultiset =
                    MinecraftInteractionView.inventoryMultisetDigest(
                            player);
            if (!restoredMultiset.equals(
                    state.inventoryMultisetBefore)) {
                throw new IllegalStateException(
                        "Inventory hotbar swap compensation did not restore the original inventory multiset");
            }
            return;
        }
        if (player.containerMenu != player.inventoryMenu
                || !source.equals(swap.expectedTarget())
                || !target.equals(swap.expectedSource())) {
            throw new IllegalStateException(
                    "Cannot safely compensate inventory hotbar swap");
        }
        player.inventoryMenu.clicked(
                swap.sourceInventorySlot(),
                swap.targetHotbarSlot(),
                ClickType.SWAP,
                player);
        player.inventoryMenu.broadcastChanges();
        ItemStackFingerprint restoredSource =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.sourceInventorySlot()));
        ItemStackFingerprint restoredTarget =
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.getInventory()
                                .getItem(swap.targetHotbarSlot()));
        String restoredMultiset =
                MinecraftInteractionView.inventoryMultisetDigest(player);
        if (!restoredSource.equals(swap.expectedSource())
                || !restoredTarget.equals(swap.expectedTarget())
                || !restoredMultiset.equals(
                        state.inventoryMultisetBefore)) {
            throw new IllegalStateException(
                    "Inventory hotbar swap compensation failed");
        }
    }

    private void settleMenuSwap(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap,
            long currentTick,
            boolean replacementInheritor) {
        if (!lifecycleManager.mayCleanupMutateInventory(
                        state.botId, state.botGeneration)
                || player.containerMenu != player.inventoryMenu
                || !isCurrentCleanupTarget(state, player)
                || !nativeCraftSlotsEmpty(player)) {
            throw new IllegalStateException(
                    "Cannot safely settle a closed, viewed or dirty inventory menu");
        }
        InventoryMenuSwapPlan plan = menuSwap.plan();
        InventoryMenuSnapshot actual =
                MinecraftActionSnapshot.inventoryMenu(player);
        String multiset =
                MinecraftInteractionView
                        .inventoryMultisetDigest(player);
        if (!multiset.equals(state.inventoryMultisetBefore)) {
            throw new IllegalStateException(
                    "Inventory menu transaction changed the inventory multiset");
        }
        if (!actual.inventoryMultisetEquals(
                plan.initialSnapshot())) {
            throw new IllegalStateException(
                    "Inventory menu transaction changed the structured inventory multiset");
        }
        InventoryMenuTransaction transaction =
                Objects.requireNonNull(
                        state.menuTransaction,
                        "menuTransaction");
        if (state.menuForwardInFlight >= 0
                || state.menuSettlementSourcePrefix >= 0) {
            throw new IllegalStateException(
                    "Synchronous menu cleanup cannot resolve an in-flight click");
        }
        InventoryMenuPrefixAuthority authority =
                menuPrefixAuthority(
                        state,
                        plan,
                        transaction,
                        actual,
                        replacementInheritor);
        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan, actual, authority);
        switch (decision.outcome()) {
            case UNSAFE -> throw new IllegalStateException(
                    "Inventory menu transaction cannot prove a safe settlement");
            case CLICK_TO_INITIAL, CLICK_TO_FINAL -> {
                if (!state.menuCleanupSession
                        .mayDispatchClickAt(currentTick)) {
                    throw new IllegalStateException(
                            "Synchronous menu cleanup cannot click twice in one Tick");
                }
                InventoryMenuSettlementCursor cursor =
                        decision.settlementCursor().orElseThrow();
                InventoryMenuSettlementCursor advanced =
                        cursor.advanceAfterProposedClick();
                if (!advanced.atEndpoint()) {
                    throw new IllegalStateException(
                            "Synchronous menu cleanup cannot span multiple Ticks");
                }
                InventoryMenuSnapshot settled =
                        applyMenuSettlementClick(
                                player,
                                state,
                                plan,
                                decision,
                                currentTick);
                finishMenuSettlement(
                        state,
                        plan,
                        advanced,
                        settled);
            }
            case ALREADY_INITIAL -> finishMenuSettlement(
                    state,
                    plan,
                    decision.settlementCursor()
                            .orElseThrow(),
                    actual);
            case ALREADY_FINAL -> finishMenuSettlement(
                    state,
                    plan,
                    decision.settlementCursor()
                            .orElseThrow(),
                    actual);
            case SAFE_PREFIX_COMMITTED -> {
                clearMenuSettlementState(state);
                state.menuForwardInFlight = -1;
                state.menuForwardTargetSnapshot = null;
                state.menuLastSnapshot = actual;
                if (!transaction.state().terminal()) {
                    state.menuTransaction =
                            transaction.cancel();
                }
            }
        }
    }

    private static InventoryMenuPrefixAuthority
            menuPrefixAuthority(
                    InteractionState state,
                    InventoryMenuSwapPlan plan,
                    InventoryMenuTransaction transaction,
                    InventoryMenuSnapshot actual,
                    boolean replacementInheritor) {
        if (state.menuSettlementSourcePrefix >= 0) {
            int source = state.menuSettlementSourcePrefix;
            int target = state.menuSettlementTargetPrefix;
            InventoryMenuSnapshot sourceSnapshot =
                    Objects.requireNonNull(
                            state.menuSettlementSourceSnapshot,
                            "menuSettlementSourceSnapshot");
            if (replacementInheritor
                    && plan.snapshotAtPrefix(source)
                            .layoutEqualsIgnoringState(actual)) {
                sourceSnapshot = actual;
                state.menuSettlementSourceSnapshot =
                        actual;
            }
            InventoryMenuSnapshot targetSnapshot =
                    state.menuSettlementTargetSnapshot;
            if (replacementInheritor
                    && plan.snapshotAtPrefix(target)
                            .layoutEqualsIgnoringState(actual)) {
                targetSnapshot = actual;
                state.menuSettlementTargetSnapshot =
                        actual;
            }
            if (targetSnapshot == null) {
                targetSnapshot =
                        plan.snapshotAtPrefix(target);
            }
            return InventoryMenuPrefixAuthority.inFlight(
                    plan,
                    source,
                    target,
                    sourceSnapshot,
                    targetSnapshot);
        }
        int confirmed = transaction.confirmedClicks();
        InventoryMenuSnapshot confirmedSnapshot =
                Objects.requireNonNull(
                        state.menuLastSnapshot,
                        "menuLastSnapshot");
        if (replacementInheritor
                && plan.snapshotAtPrefix(confirmed)
                        .layoutEqualsIgnoringState(actual)) {
            confirmedSnapshot = actual;
            state.menuLastSnapshot = actual;
        }
        if (state.menuForwardInFlight == confirmed
                && confirmed < plan.orderedSteps().size()) {
            int target = confirmed + 1;
            InventoryMenuSnapshot targetSnapshot =
                    state.menuForwardTargetSnapshot;
            if (replacementInheritor
                    && plan.snapshotAtPrefix(target)
                            .layoutEqualsIgnoringState(actual)) {
                targetSnapshot = actual;
                state.menuForwardTargetSnapshot =
                        actual;
            }
            if (targetSnapshot == null) {
                targetSnapshot =
                        plan.snapshotAtPrefix(target);
            }
            return InventoryMenuPrefixAuthority.inFlight(
                    plan,
                    confirmed,
                    target,
                    confirmedSnapshot,
                    targetSnapshot);
        }
        return InventoryMenuPrefixAuthority.stable(
                plan, confirmed, confirmedSnapshot);
    }

    private InventoryMenuSnapshot applyMenuSettlementClick(
            BotServerPlayer player,
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuSettlementDecision decision,
            long currentTick) {
        InventoryMenuClickStep step =
                decision.click().orElseThrow();
        InventoryMenuSnapshot before =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (!lifecycleManager.mayCleanupMutateInventory(
                        state.botId, state.botGeneration)
                || player.containerMenu != player.inventoryMenu
                || !isCurrentCleanupTarget(state, player)
                || !before.equals(
                        decision.observedSnapshot())
                || !before.layoutEqualsIgnoringState(
                        step.before())
                || !before.inventoryMultisetEquals(
                        plan.initialSnapshot())
                || !nativeCraftSlotsEmpty(player)
                || !menuStepAllowedNow(player, step)) {
            throw new IllegalStateException(
                    "Inventory menu settlement precondition changed");
        }
        int targetPrefix =
                decision.outcome()
                                == InventoryMenuSettlementDecision
                                        .Outcome.CLICK_TO_INITIAL
                        ? 0
                        : plan.orderedSteps().size();
        state.menuSettlementSourcePrefix =
                decision.observedPrefix();
        state.menuSettlementTargetPrefix = targetPrefix;
        state.menuSettlementSourceSnapshot = before;
        state.menuSettlementTargetSnapshot = null;
        state.menuForwardInFlight = -1;
        state.menuCleanupSession.beginClickDispatch(
                currentTick);
        try {
            player.inventoryMenu.clicked(
                    step.menuSlot(),
                    step.hotbarButton(),
                    ClickType.SWAP,
                    player);
            player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException exception) {
            freezeMenuSettlementTargetAfterThrow(
                    player, state, plan, step);
            throw exception;
        } finally {
            state.menuCleanupSession.endClickDispatch(
                    currentTick);
        }
        InventoryMenuSnapshot after =
                MinecraftActionSnapshot.inventoryMenu(player);
        String multisetAfter =
                MinecraftInteractionView
                        .inventoryMultisetDigest(player);
        boolean exactTarget =
                after.layoutEqualsIgnoringState(step.after())
                        && after.inventoryMultisetEquals(
                                plan.initialSnapshot())
                        && multisetAfter.equals(
                                state.inventoryMultisetBefore)
                        && nativeCraftSlotsEmpty(player);
        if (exactTarget) {
            state.menuSettlementTargetSnapshot = after;
        }
        if (!lifecycleManager.mayCleanupMutateInventory(
                        state.botId, state.botGeneration)
                || player.containerMenu
                        != player.inventoryMenu
                || !isCurrentCleanupTarget(state, player)) {
            throw new IllegalStateException(
                    "Bot cleanup authority changed during inventory menu settlement");
        }
        if (!exactTarget) {
            throw new IllegalStateException(
                    "Inventory menu settlement click failed");
        }
        state.menuLastSnapshot = after;
        return after;
    }

    private static void freezeMenuSettlementTargetAfterThrow(
            BotServerPlayer player,
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuClickStep step) {
        try {
            InventoryMenuSnapshot after =
                    MinecraftActionSnapshot
                            .inventoryMenu(player);
            if (after.layoutEqualsIgnoringState(
                            step.after())
                    && after.inventoryMultisetEquals(
                            plan.initialSnapshot())) {
                state.menuSettlementTargetSnapshot =
                        after;
            }
        } catch (RuntimeException ignored) {
            // forceSafeReset will fail closed without a frozen target.
        }
    }

    private static void finishMenuSettlement(
            InteractionState state,
            InventoryMenuSwapPlan plan,
            InventoryMenuSettlementCursor cursor,
            InventoryMenuSnapshot settled) {
        InventoryMenuTransaction transaction =
                Objects.requireNonNull(
                        state.menuTransaction,
                        "menuTransaction");
        if (!cursor.atEndpoint()) {
            throw new IllegalStateException(
                    "Menu settlement did not reach its endpoint");
        }
        if (cursor.endpoint()
                == InventoryMenuSettlementCursor.Endpoint.FINAL) {
            int finalPrefix = plan.orderedSteps().size();
            if (transaction.confirmedClicks()
                    == finalPrefix - 1) {
                transaction = transaction.confirmNext(
                        plan.orderedSteps().get(
                                finalPrefix - 1));
            }
            if (transaction.confirmedClicks()
                            != finalPrefix
                    || transaction.state()
                            != InventoryMenuTransactionState
                                    .VERIFYING) {
                throw new IllegalStateException(
                        "Final menu settlement did not reach a verifiable transaction cursor");
            }
            state.menuTransaction = transaction.commit();
        } else if (!transaction.state().terminal()) {
            state.menuTransaction = transaction.cancel();
        }
        state.menuLastSnapshot = settled;
        state.menuForwardInFlight = -1;
        state.menuForwardTargetSnapshot = null;
        clearMenuSettlementState(state);
    }

    private static void clearMenuSettlementState(
            InteractionState state) {
        state.menuCleanupSession.clearSettlementCursor();
        clearMenuSettlementInFlight(state);
    }

    private static void clearMenuSettlementInFlight(
            InteractionState state) {
        state.menuSettlementSourcePrefix = -1;
        state.menuSettlementTargetPrefix = -1;
        state.menuSettlementSourceSnapshot = null;
        state.menuSettlementTargetSnapshot = null;
    }

    private void rollbackSelection(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.SelectHotbar select) {
        int current = player.getInventory().selected;
        if (current == state.selectedBefore) {
            return;
        }
        if (current != select.slot()) {
            throw new IllegalStateException(
                    "Cannot safely compensate hotbar selection");
        }
        player.connection.handleSetCarriedItem(
                new ServerboundSetCarriedItemPacket(
                        state.selectedBefore));
        if (player.getInventory().selected
                != state.selectedBefore) {
            throw new IllegalStateException(
                    "Hotbar selection compensation failed");
        }
    }

    private static boolean requiresResolvedCleanupTarget(
            InteractionState state, ActionCleanupReason reason) {
        if (reason == ActionCleanupReason.SUCCEEDED
                || !state.sideEffectDispatched) {
            return false;
        }
        return state.spec
                        instanceof WorldInteractionActionSpec
                                .SwapInventoryHotbar
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .InventoryMenuSwap
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .WorldMenuTransaction
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .WorldMenuTransfer
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .WorldMenuRecipe
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .WorldVillagerTrade
                || state.spec
                        instanceof WorldInteractionActionSpec.PlaceBlock
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .AimAndPlaceBlock
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .SelectHotbar
                || state.spec
                        instanceof WorldInteractionActionSpec
                                .BreakBlock
                || state.spec
                        instanceof WorldInteractionActionSpec.UseItem
                || state.spec
                        instanceof WorldInteractionActionSpec.ReleaseUse;
    }

    private Optional<BotServerPlayer> resolveActive(
            ActionEnvelope envelope) {
        return lifecycleManager.resolveActionTarget(
                envelope.botId(), envelope.botGeneration());
    }

    private boolean isCurrentActionTarget(
            InteractionState state, BotServerPlayer player) {
        return lifecycleManager
                .resolveActionTarget(
                        state.botId, state.botGeneration)
                .filter(candidate -> candidate == player)
                .isPresent();
    }

    private boolean isCurrentCleanupTarget(
            InteractionState state, BotServerPlayer player) {
        return lifecycleManager
                .resolveCleanupTarget(
                        state.botId, state.botGeneration)
                .filter(candidate -> candidate == player)
                .isPresent();
    }

    private int nextSequence() {
        if (packetSequence == Integer.MAX_VALUE) {
            packetSequence = 0;
        }
        return packetSequence++;
    }

    /**
     * Operations whose source, hand, menu or structural fences may not cross the
     * validate-to-start boundary. BreakBlock is included because START_DESTROY_BLOCK
     * may synchronously destroy vanilla instabreak blocks.
     */
    static boolean requiresStartRevalidation(
            WorldInteractionActionSpec spec) {
        return spec instanceof WorldInteractionActionSpec
                        .SwapInventoryHotbar
                || spec instanceof WorldInteractionActionSpec
                        .InventoryMenuSwap
                || spec instanceof WorldInteractionActionSpec
                        .WorldMenuTransaction
                || spec instanceof WorldInteractionActionSpec
                        .WorldMenuTransfer
                || spec instanceof WorldInteractionActionSpec
                        .WorldMenuRecipe
                || spec instanceof WorldInteractionActionSpec
                        .WorldVillagerTrade
                || spec instanceof WorldInteractionActionSpec.PlaceBlock
                || spec instanceof WorldInteractionActionSpec.AimAndPlaceBlock
                || spec instanceof WorldInteractionActionSpec.BreakBlock
                || spec instanceof WorldInteractionActionSpec.PickupWait
                || spec instanceof WorldInteractionActionSpec.AttackEntity
                || spec instanceof WorldInteractionActionSpec
                        .InteractEntity
                || spec instanceof WorldInteractionActionSpec.SelectHotbar
                || spec instanceof WorldInteractionActionSpec.UseItem useItem
                        && useItem.strictPreconditions().isPresent();
    }

    private static boolean requiresTicks(
            WorldInteractionActionSpec spec) {
        return switch (spec) {
            case WorldInteractionActionSpec.BreakBlock ignored -> true;
            case WorldInteractionActionSpec.PickupWait ignored -> true;
            case WorldInteractionActionSpec.InventoryMenuSwap ignored ->
                    true;
            case WorldInteractionActionSpec.WorldMenuTransaction ignored ->
                    true;
            case WorldInteractionActionSpec.WorldMenuTransfer ignored ->
                    true;
            case WorldInteractionActionSpec.WorldMenuRecipe ignored -> true;
            case WorldInteractionActionSpec.WorldVillagerTrade ignored ->
                    true;
            case WorldInteractionActionSpec.UseItem useItem ->
                    useItem.mode()
                            != WorldInteractionActionSpec.ItemUseMode
                                    .INSTANT;
            default -> false;
        };
    }

    /**
     * Performs the non-bypassable post-look fence and invokes the packet callback only after the
     * complete action revalidation and view-angle check have both passed. This package-private
     * seam deliberately retains no live Minecraft object, so ordering failures are unit-testable.
     */
    static AimAndPlaceFinalFenceResult executeAimAndPlaceFinalFence(
            Runnable lookAt,
            Supplier<BackendResult> finalRevalidation,
            DoubleSupplier finalAimErrorDegrees,
            double toleranceDegrees,
            DoubleConsumer packetDispatch) {
        Objects.requireNonNull(lookAt, "lookAt");
        Objects.requireNonNull(finalRevalidation, "finalRevalidation");
        Objects.requireNonNull(finalAimErrorDegrees, "finalAimErrorDegrees");
        Objects.requireNonNull(packetDispatch, "packetDispatch");
        if (!Double.isFinite(toleranceDegrees) || toleranceDegrees < 0.0D) {
            throw new IllegalArgumentException(
                    "toleranceDegrees must be finite and non-negative");
        }

        lookAt.run();
        BackendResult revalidation = Objects.requireNonNull(
                finalRevalidation.get(), "finalRevalidation result");
        if (revalidation.step() != BackendStep.ACCEPTED) {
            return new AimAndPlaceFinalFenceResult(
                    AimAndPlaceFinalFenceStatus.REVALIDATION_REJECTED,
                    revalidation,
                    Double.NaN);
        }

        double finalAimError = finalAimErrorDegrees.getAsDouble();
        if (!Double.isFinite(finalAimError)
                || finalAimError > toleranceDegrees) {
            return new AimAndPlaceFinalFenceResult(
                    AimAndPlaceFinalFenceStatus.AIM_REJECTED,
                    revalidation,
                    finalAimError);
        }

        packetDispatch.accept(finalAimError);
        return new AimAndPlaceFinalFenceResult(
                AimAndPlaceFinalFenceStatus.PACKET_DISPATCHED,
                revalidation,
                finalAimError);
    }

    enum AimAndPlaceFinalFenceStatus {
        REVALIDATION_REJECTED,
        AIM_REJECTED,
        PACKET_DISPATCHED
    }

    record AimAndPlaceFinalFenceResult(
            AimAndPlaceFinalFenceStatus status,
            BackendResult revalidation,
            double finalAimErrorDegrees) {
        AimAndPlaceFinalFenceResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(revalidation, "revalidation");
            boolean revalidationAccepted =
                    revalidation.step() == BackendStep.ACCEPTED;
            if (status == AimAndPlaceFinalFenceStatus.REVALIDATION_REJECTED
                    && revalidationAccepted) {
                throw new IllegalArgumentException(
                        "revalidation rejection requires a rejected result");
            }
            if (status != AimAndPlaceFinalFenceStatus.REVALIDATION_REJECTED
                    && !revalidationAccepted) {
                throw new IllegalArgumentException(
                        "aim or packet result requires accepted revalidation");
            }
            if (status == AimAndPlaceFinalFenceStatus.PACKET_DISPATCHED
                    && !Double.isFinite(finalAimErrorDegrees)) {
                throw new IllegalArgumentException(
                        "packet dispatch requires finite aim error");
            }
        }
    }

    /** Returns the angle between the current eye view vector and a frozen hit point. */
    private static double viewAngleDegrees(
            BotServerPlayer player,
            double targetX,
            double targetY,
            double targetZ) {
        Vec3 eye = player.getEyePosition();
        double deltaX = targetX - eye.x;
        double deltaY = targetY - eye.y;
        double deltaZ = targetZ - eye.z;
        double targetLengthSquared = deltaX * deltaX
                + deltaY * deltaY
                + deltaZ * deltaZ;
        if (!Double.isFinite(targetLengthSquared)
                || targetLengthSquared <= MIN_AIM_VECTOR_LENGTH_SQUARED) {
            return Double.NaN;
        }
        Vec3 view = player.getViewVector(1.0F);
        double viewLengthSquared = view.lengthSqr();
        if (!Double.isFinite(viewLengthSquared)
                || viewLengthSquared <= MIN_AIM_VECTOR_LENGTH_SQUARED) {
            return Double.NaN;
        }
        double dot = (view.x * deltaX + view.y * deltaY + view.z * deltaZ)
                / Math.sqrt(viewLengthSquared * targetLengthSquared);
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0D, 1.0D)));
    }

    private static BackendResult requireFingerprint(
            ActionEnvelope envelope,
            ItemStackFingerprint actual,
            ItemStackFingerprint expected,
            String summary) {
        return actual.equals(expected)
                ? BackendResult.accepted(envelope)
                : failure(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        summary);
    }

    private static BackendResult failure(
            ActionEnvelope envelope,
            ActionFailureCode code,
            String summary) {
        return failure(envelope, code, List.of(), summary);
    }

    private static BackendResult failure(
            ActionEnvelope envelope,
            ActionFailureCode code,
            List<ActionEvidence> evidence,
            String summary) {
        return BackendResult.failed(envelope, code, evidence, summary);
    }

    private static BackendResult success(
            ActionEnvelope envelope,
            List<ActionEvidence> evidence,
            String summary) {
        return BackendResult.succeeded(envelope, evidence, summary);
    }

    private static ActionEvidence evidence(String key, String value) {
        return new ActionEvidence(key, value);
    }

    /**
     * The global action outcome allows only sixteen evidence items.  A singleton
     * remains in the old three-field protocol for the existing P5A resource
     * collector; a multi-drop break instead uses exactly one compact receipt per
     * entity.  Invalid or over-budget lists return empty as a whole, never a
     * prefix that could make a partial harvest look complete.
     */
    static Optional<List<ActionEvidence>> breakDropEvidence(
            List<BreakDropProvenanceCapture.Provenance> drops) {
        Objects.requireNonNull(drops, "drops");
        if (drops.isEmpty()
                || drops.size() > ActionOutcome.MAX_EVIDENCE_ITEMS
                        - VERIFY_BREAK_BASE_EVIDENCE_ITEMS) {
            return Optional.empty();
        }
        try {
            Set<UUID> entityIds = new HashSet<>(drops.size());
            for (BreakDropProvenanceCapture.Provenance drop : drops) {
                if (drop == null || !entityIds.add(drop.entityId())) {
                    return Optional.empty();
                }
            }
            if (drops.size() == 1) {
                BreakDropProvenanceCapture.Provenance drop = drops.getFirst();
                return Optional.of(List.of(
                        evidence("block.drop.entity.id",
                                drop.entityId().toString()),
                        evidence("block.drop.item", drop.itemId()),
                        evidence("block.drop.count",
                                Integer.toString(drop.count()))));
            }
            List<ActionEvidence> receipts = new ArrayList<>(drops.size());
            for (int index = 0; index < drops.size(); index++) {
                BreakDropProvenanceCapture.Provenance drop = drops.get(index);
                String value = BreakDropProvenanceCapture
                        .compactReceiptValue(drop).orElse(null);
                if (value == null) {
                    return Optional.empty();
                }
                receipts.add(evidence(
                        BreakDropProvenanceCapture
                                .compactReceiptEvidenceKey(index),
                        value));
            }
            return Optional.of(List.copyOf(receipts));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static List<ActionEvidence> blockReachEvidence(
            MinecraftInteractionView.BlockReachEvidence reach) {
        return List.of(
                evidence(
                        "block.within_reach",
                        Boolean.toString(reach.withinReach())),
                evidence(
                        "block.ray_hit_target",
                        Boolean.toString(reach.rayHitTarget())),
                evidence("block.ray_hit_type", reach.rayHitType()),
                evidence(
                        "block.ray_hit_position",
                        reach.rayHitPosition()),
                evidence("block.eye_position", reach.eyePosition()),
                evidence(
                        "block.expected_hit_position",
                        reach.expectedHitPosition()),
                evidence(
                        "block.ray_end_position",
                        reach.rayEndPosition()));
    }

    private record ActionKey(
            UUID botId, long botGeneration, UUID actionId) {
        private static ActionKey from(ActionEnvelope envelope) {
            return new ActionKey(
                    envelope.botId(),
                    envelope.botGeneration(),
                    envelope.actionId());
        }
    }

    private enum MerchantTradeStartRejection {
        OPEN_DID_NOT_BIND_MERCHANT,
        OPENED_MERCHANT_BINDING_DRIFT,
        MERCHANT_SNAPSHOT_INVALID,
        MERCHANT_PLAN_UNBINDABLE
    }

    private static MenuPreconditionChangedException merchantTradeStartRejected(
            MerchantTradeStartRejection rejection) {
        return new MenuPreconditionChangedException(
                "Villager trade start rejected: "
                        + Objects.requireNonNull(rejection, "rejection").name());
    }

    private static final class MenuPreconditionChangedException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final String DEFAULT_SAFE_SUMMARY =
                "Inventory menu transaction precondition changed before click";

        private final String safeSummary;

        private MenuPreconditionChangedException() {
            this(DEFAULT_SAFE_SUMMARY);
        }

        private MenuPreconditionChangedException(String safeSummary) {
            super(Objects.requireNonNull(safeSummary, "safeSummary"));
            this.safeSummary = safeSummary;
        }

        private String safeSummary() {
            return safeSummary;
        }
    }

    private enum FurnaceStage {
        DEPOSITING,
        WAITING,
        COLLECTING,
        COMPLETED;
    }

    /**
     * recipe action 的短生命周期权威证据。它不保存原版 menu、block entity 或玩家对象；每次
     * 重新打开炉子仍由当前完整快照和 generation 复核。
     */
    private static final class RecipeSession {
        private final P5ARecipe recipe;
        private final int batches;
        private final Map<ResourceId, ItemStackFingerprint>
                vanillaPrototypes;
        private final InventoryContentsSnapshot inventoryBefore;
        private final P5AFurnaceMenuPlanBuilder.FurnaceExpectation
                furnaceExpectation;
        private FurnaceStage furnaceStage;
        private long nextFurnacePollTick;

        private RecipeSession(
                P5ARecipe recipe,
                int batches,
                Map<ResourceId, ItemStackFingerprint> vanillaPrototypes,
                InventoryContentsSnapshot inventoryBefore,
                P5AFurnaceMenuPlanBuilder.FurnaceExpectation
                        furnaceExpectation,
                FurnaceStage furnaceStage) {
            this.recipe = Objects.requireNonNull(recipe, "recipe");
            if (batches < 1 || batches > recipe.maximumBatches()) {
                throw new IllegalArgumentException(
                        "recipe session batches exceed the reviewed action bound");
            }
            this.batches = batches;
            this.vanillaPrototypes = Map.copyOf(
                    Objects.requireNonNull(
                            vanillaPrototypes, "vanillaPrototypes"));
            this.inventoryBefore = Objects.requireNonNull(
                    inventoryBefore, "inventoryBefore");
            this.furnaceExpectation = furnaceExpectation;
            this.furnaceStage = furnaceStage;
            if (recipe.isFurnace()
                    != (furnaceExpectation != null)
                    || recipe.isFurnace()
                            != (furnaceStage != null)) {
                throw new IllegalArgumentException(
                        "recipe session furnace state does not match recipe family");
            }
        }

        private static RecipeSession crafting(
                P5ARecipe recipe,
                int batches,
                Map<ResourceId, ItemStackFingerprint> vanillaPrototypes,
                InventoryContentsSnapshot inventoryBefore) {
            if (!recipe.isCrafting()) {
                throw new IllegalArgumentException(
                        "crafting session requires a crafting recipe");
            }
            return new RecipeSession(
                    recipe, batches, vanillaPrototypes, inventoryBefore,
                    null, null);
        }

        private static RecipeSession furnace(
                P5ARecipe recipe,
                int batches,
                Map<ResourceId, ItemStackFingerprint> vanillaPrototypes,
                InventoryContentsSnapshot inventoryBefore,
                P5AFurnaceMenuPlanBuilder.FurnaceExpectation
                        furnaceExpectation) {
            if (!recipe.isFurnace()) {
                throw new IllegalArgumentException(
                        "furnace session requires a furnace recipe");
            }
            return new RecipeSession(
                    recipe,
                    batches,
                    vanillaPrototypes,
                    inventoryBefore,
                    Objects.requireNonNull(
                            furnaceExpectation, "furnaceExpectation"),
                    FurnaceStage.DEPOSITING);
        }
    }

    /**
     * 服务端线程内单次交易的短生命周期锚点。它保存的都是同一 action 内重新核验的原版对象
     * 身份和不可变快照；不会跨 action、generation 或清理边界复用。
     */
    private static final class MerchantTradeSession {
        private final Villager villager;
        private final MerchantMenu menu;
        private final MerchantOffer offer;
        private final int offerIndex;
        private final int usesBefore;
        private final VillagerData villagerDataBefore;
        private final MerchantOfferState offerState;
        private final int villagerXpBefore;
        private final int villagerXpAfter;
        private final InventoryContentsSnapshot inventoryBefore;
        private final InventoryMenuSnapshot inventoryMenuBefore;

        private MerchantTradeSession(
                Villager villager,
                MerchantMenu menu,
                MerchantOffer offer,
                int offerIndex,
                int usesBefore,
                VillagerData villagerDataBefore,
                MerchantOfferState offerState,
                int villagerXpBefore,
                int villagerXpAfter,
                InventoryContentsSnapshot inventoryBefore,
                InventoryMenuSnapshot inventoryMenuBefore) {
            this.villager = Objects.requireNonNull(villager, "villager");
            this.menu = Objects.requireNonNull(menu, "menu");
            this.offer = Objects.requireNonNull(offer, "offer");
            if (offerIndex < 0 || usesBefore < 0
                    || villagerXpBefore < 0 || villagerXpAfter < 0) {
                throw new IllegalArgumentException(
                        "merchant session offer fields are invalid");
            }
            this.offerIndex = offerIndex;
            this.usesBefore = usesBefore;
            this.villagerDataBefore = Objects.requireNonNull(
                    villagerDataBefore, "villagerDataBefore");
            this.offerState = Objects.requireNonNull(
                    offerState, "offerState");
            this.villagerXpBefore = villagerXpBefore;
            this.villagerXpAfter = villagerXpAfter;
            this.inventoryBefore = Objects.requireNonNull(
                    inventoryBefore, "inventoryBefore");
            this.inventoryMenuBefore = Objects.requireNonNull(
                    inventoryMenuBefore, "inventoryMenuBefore");
        }
    }

    /** One exact entity/item observation frozen before a UUID-bound pickup wait. */
    private record PickupReceipt(
        UUID entityId,
            ItemStackFingerprint item,
            int matchingCountBefore) {
        private PickupReceipt {
            entityId = Objects.requireNonNull(entityId, "pickupEntityId");
            if (entityId.getMostSignificantBits() == 0L
                    && entityId.getLeastSignificantBits() == 0L) {
                throw new IllegalArgumentException(
                        "pickupEntityId must not be zero UUID");
            }
            item = Objects.requireNonNull(item, "item");
            if (item.isEmpty() || matchingCountBefore < 0) {
                throw new IllegalArgumentException(
                        "pickup receipt requires a non-empty item and non-negative baseline");
            }
        }
    }

    /** Per item/component aggregate used to prove a multi-receipt inventory delta. */
    private record PickupItemExpectation(
            ItemStackFingerprint item,
            int matchingCountBefore,
            int expectedCount) {
        private PickupItemExpectation {
            item = Objects.requireNonNull(item, "item");
            if (item.isEmpty()
                    || matchingCountBefore < 0
                    || expectedCount < 1) {
                throw new IllegalArgumentException(
                        "pickup expectation is outside the bounded item contract");
            }
        }
    }

    /**
     * Exact native world-menu dispatch seam. Production binds it to
     * {@link #dispatchVanillaWorldMenuClick(AbstractContainerMenu, MenuClick, BotServerPlayer)};
     * package tests can deterministically throw before or after a simulated
     * mutation without exposing a production Action/Skill escape hatch.
     */
    @FunctionalInterface
    interface WorldMenuClickDispatcher {
        void dispatch(
                AbstractContainerMenu nativeMenu,
                MenuClick click,
                BotServerPlayer player);
    }

    /**
     * Why a strict item use was stopped before native completion.
     *
     * <p>Cancellation is deliberately distinct from observation drift: a
     * cancellation command can be accepted by the lifecycle layer yet remain
     * behind earlier mailbox work, while native use must be fenced immediately.
     */
    private enum StrictUseStopReason {
        NONE,
        PRECONDITION_DRIFT,
        CANCELLATION_FENCED
    }

    private static final class InteractionState {
        private final ActionEnvelope envelope;
        private final UUID botId;
        private final long botGeneration;
        private final WorldInteractionActionSpec spec;
        private final long startedTick;
        private final ItemStackFingerprint heldBefore;
        private final String inventoryBefore;
        private final String inventoryMultisetBefore;
        private final BlockTargetFingerprint blockBefore;
        private final int containerIdBefore;
        private final Entity vehicleBefore;
        private final float targetHealthBefore;
        private final List<UUID> pickupEntityIds;
        private final List<PickupReceipt> pickupReceipts;
        private final int selectedBefore;
        private final int foodLevelBefore;
        private boolean sideEffectDispatched;
        private boolean startedUsing;
        private boolean releaseSent;
        private StrictUseStopReason strictUseStopReason =
                StrictUseStopReason.NONE;
        private StrictUseCompletionPhase strictUseCompletionPhase =
                StrictUseCompletionPhase.OPEN;
        private boolean breakStopSent;
        private boolean aimAndPlaceFinalFencePassed;
        private double aimAndPlaceBeforeAimError = Double.NaN;
        private double aimAndPlaceDispatchAimError = Double.NaN;
        private Optional<List<BreakDropProvenanceCapture.Provenance>>
                breakDropProvenances = Optional.empty();
        private InventoryMenuTransaction menuTransaction;
        private MenuTransaction worldMenuTransaction;
        private MenuSnapshot worldMenuLastSnapshot;
        /** Only an ender-chest transfer binds the exact opened native ChestMenu instance. */
        private AbstractContainerMenu worldMenuBoundNativeMenu;
        private RecipeSession recipeSession;
        private MerchantTradeSession merchantTradeSession;
        private InventoryMenuSnapshot villagerTradeInventoryBefore;
        private VillagerData villagerTradeVillagerDataBefore;
        private MerchantOfferState villagerTradeOfferStateBefore;
        private InventoryMenuSnapshot menuLastSnapshot;
        private int menuForwardInFlight = -1;
        private final InventoryMenuCleanupSession
                menuCleanupSession =
                        new InventoryMenuCleanupSession();
        private boolean menuReplacementRebound;
        private int menuSettlementSourcePrefix = -1;
        private int menuSettlementTargetPrefix = -1;
        private InventoryMenuSnapshot
                menuSettlementSourceSnapshot;
        private InventoryMenuSnapshot
                menuSettlementTargetSnapshot;
        private InventoryMenuSnapshot
                menuForwardTargetSnapshot;

        private InteractionState(
                UUID botId,
                long botGeneration,
                WorldInteractionActionSpec spec,
                ActionEnvelope envelope,
                long startedTick,
                ItemStackFingerprint heldBefore,
                String inventoryBefore,
                String inventoryMultisetBefore,
                BlockTargetFingerprint blockBefore,
                int containerIdBefore,
                Entity vehicleBefore,
                float targetHealthBefore,
                List<UUID> pickupEntityIds,
                List<PickupReceipt> pickupReceipts,
                int selectedBefore,
                int foodLevelBefore) {
            this.botId = Objects.requireNonNull(
                    botId, "botId");
            if (botGeneration <= 0L) {
                throw new IllegalArgumentException(
                        "botGeneration must be positive");
            }
            this.botGeneration = botGeneration;
            this.spec = Objects.requireNonNull(spec, "spec");
            this.envelope = Objects.requireNonNull(envelope, "envelope");
            if (!botId.equals(envelope.botId())
                    || botGeneration != envelope.botGeneration()
                    || !(envelope.action()
                            instanceof WorldInteractionAction action)
                    || !spec.equals(action.spec())) {
                throw new IllegalArgumentException(
                        "interaction state does not match its action envelope");
            }
            this.startedTick = startedTick;
            this.heldBefore = heldBefore;
            this.inventoryBefore = inventoryBefore;
            this.inventoryMultisetBefore = inventoryMultisetBefore;
            this.blockBefore = blockBefore;
            this.containerIdBefore = containerIdBefore;
            this.vehicleBefore = vehicleBefore;
            this.targetHealthBefore = targetHealthBefore;
            this.pickupEntityIds = List.copyOf(Objects.requireNonNull(
                    pickupEntityIds, "pickupEntityIds"));
            this.pickupReceipts = List.copyOf(Objects.requireNonNull(
                    pickupReceipts, "pickupReceipts"));
            this.selectedBefore = selectedBefore;
            this.foodLevelBefore = foodLevelBefore;
            if (spec
                    instanceof WorldInteractionActionSpec
                            .InventoryMenuSwap menuSwap) {
                this.menuTransaction =
                        InventoryMenuTransaction.planned(
                                menuSwap.plan());
                this.menuLastSnapshot =
                        menuSwap.plan().initialSnapshot();
            }
        }

        private static InteractionState capture(
                BotServerPlayer player,
                WorldInteractionActionSpec spec,
                long startedTick,
                UUID botId,
                long botGeneration,
                ActionEnvelope envelope) {
            InteractionHand hand = switch (spec) {
                case WorldInteractionActionSpec.UseItem useItem ->
                        MinecraftInteractionView.hand(useItem.hand());
                case WorldInteractionActionSpec.ReleaseUse releaseUse ->
                        MinecraftInteractionView.hand(releaseUse.hand());
                case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                        MinecraftInteractionView.hand(useOnBlock.hand());
                case WorldInteractionActionSpec.PlaceBlock ignored ->
                        InteractionHand.MAIN_HAND;
                case WorldInteractionActionSpec.AimAndPlaceBlock ignored ->
                        InteractionHand.MAIN_HAND;
                case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                        MinecraftInteractionView.hand(menu.hand());
                case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                        MinecraftInteractionView.hand(menu.hand());
                case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                        MinecraftInteractionView.hand(menu.hand());
                case WorldInteractionActionSpec.WorldVillagerTrade ignored ->
                        InteractionHand.MAIN_HAND;
                case WorldInteractionActionSpec.InteractEntity
                        interactEntity ->
                        MinecraftInteractionView.hand(
                                interactEntity.hand());
                default -> InteractionHand.MAIN_HAND;
            };
            ItemStackFingerprint heldBefore =
                    MinecraftInteractionView.itemFingerprint(
                            player, player.getItemInHand(hand));
            BlockTargetFingerprint blockBefore = switch (spec) {
                case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        useOnBlock.target()
                                                .target()
                                                .position()));
                case WorldInteractionActionSpec.PlaceBlock placeBlock ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        placeBlock.expectedPlaced()
                                                .position()));
                case WorldInteractionActionSpec.AimAndPlaceBlock aimAndPlace ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        aimAndPlace.targetBefore()
                                                .position()));
                case WorldInteractionActionSpec.BreakBlock breakBlock ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        breakBlock.target()
                                                .target()
                                                .position()));
                case WorldInteractionActionSpec.WorldMenuTransaction menu ->
                        menu.opener().map(opener ->
                                MinecraftInteractionView.blockFingerprint(
                                        player,
                                        MinecraftInteractionView.position(
                                                opener.target()
                                                        .position())))
                                .orElse(null);
                case WorldInteractionActionSpec.WorldMenuTransfer menu ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        menu.opener().target().position()));
                case WorldInteractionActionSpec.WorldMenuRecipe menu ->
                        menu.opener().map(opener ->
                                MinecraftInteractionView.blockFingerprint(
                                        player,
                                        MinecraftInteractionView.position(
                                                opener.target()
                                                        .position())))
                                .orElse(null);
                case WorldInteractionActionSpec.WorldVillagerTrade ignored ->
                        null;
                default -> null;
            };
            float targetHealthBefore = Float.NaN;
            if (spec
                    instanceof WorldInteractionActionSpec.AttackEntity
                            attackEntity) {
                Entity target =
                        player.serverLevel()
                                .getEntity(
                                        attackEntity.target()
                                                .entityId());
                if (target instanceof LivingEntity living) {
                    targetHealthBefore = living.getHealth();
                }
            }

            List<UUID> pickupEntityIds = List.of();
            List<PickupReceipt> pickupReceipts = List.of();
            if (spec
                            instanceof WorldInteractionActionSpec
                                    .PickupWait pickupWait) {
                pickupEntityIds = pickupWait.expectedItemEntityIds();
                List<PickupReceipt> captured = new ArrayList<>(
                        pickupEntityIds.size());
                for (UUID entityId : pickupEntityIds) {
                    Entity target = player.serverLevel().getEntity(entityId);
                    if (!(target instanceof ItemEntity itemEntity)
                            || itemEntity.isRemoved()) {
                        continue;
                    }
                    ItemStackFingerprint item = MinecraftInteractionView
                            .itemFingerprint(player, itemEntity.getItem());
                    captured.add(new PickupReceipt(entityId, item,
                            MinecraftInteractionView.inventoryCount(
                                    player, item)));
                }
                pickupReceipts = List.copyOf(captured);
            }

            InteractionState state = new InteractionState(
                    botId,
                    botGeneration,
                    spec,
                    envelope,
                    startedTick,
                    heldBefore,
                    MinecraftInteractionView.inventoryDigest(player),
                    spec
                                    instanceof WorldInteractionActionSpec
                                            .SwapInventoryHotbar
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .InventoryMenuSwap
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .WorldMenuTransaction
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .WorldMenuTransfer
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .WorldMenuRecipe
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .WorldVillagerTrade
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .PlaceBlock
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .AimAndPlaceBlock
                            ? MinecraftInteractionView
                                    .inventoryMultisetDigest(player)
                            : null,
                    blockBefore,
                    player.containerMenu.containerId,
                    player.getVehicle(),
                    targetHealthBefore,
                    pickupEntityIds,
                    pickupReceipts,
                    player.getInventory().selected,
                    player.getFoodData().getFoodLevel());
            if (spec instanceof WorldInteractionActionSpec.WorldVillagerTrade) {
                state.villagerTradeInventoryBefore =
                        MinecraftActionSnapshot.inventoryMenu(player);
            }
            return state;
        }
    }
}
