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
import io.github.greytaiwolf.botplayer.action.ResourceIdEvidence;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
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
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
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
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 通过真人玩家使用的服务端入口执行 P2-C 与 P5A 世界交互。
 *
 * <p>全部状态均为短生命周期、generation 隔离并限制在服务器主线程。第一次副作用前先登记
 * 状态，使 packet handler 重入生命周期逻辑时，运行时清理仍能撤销持续使用或方块破坏。
 */
final class MinecraftWorldInteractionBackend implements ActionBackend {
    private static final int MAX_COMPLETED_MENU_CLEANUPS = 256;
    private final BotLifecycleManager lifecycleManager;
    private final Map<ActionKey, InteractionState> active = new LinkedHashMap<>();
    private final Map<ActionKey, ActionCleanupReceipt>
            completedMenuCleanups = new LinkedHashMap<>();
    private final InventoryLayoutCleanupFence
            inventoryLayoutCleanupFence =
                    new InventoryLayoutCleanupFence();
    private int packetSequence;

    MinecraftWorldInteractionBackend(BotLifecycleManager lifecycleManager) {
        this.lifecycleManager =
                Objects.requireNonNull(lifecycleManager, "lifecycleManager");
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
                                .InventoryMenuSwap) {
            if (!lifecycleManager.mayActionMutateInventory(
                    envelope.botId(), envelope.botGeneration())) {
                return failure(
                        envelope,
                        ActionFailureCode.CHANNEL_BUSY,
                        "Bot inventory is write-locked by an open viewer");
            }
        }
        if (action.spec()
                        instanceof WorldInteractionActionSpec
                                .SwapInventoryHotbar
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .InventoryMenuSwap
                || action.spec()
                        instanceof WorldInteractionActionSpec
                                .SelectHotbar) {
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
                        envelope.botGeneration());
        if (active.putIfAbsent(key, state) != null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World action was started more than once");
        }

        try {
            dispatchStart(player, state);
        } catch (MenuPreconditionChangedException exception) {
            state.sideEffectDispatched = false;
            return failure(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    "Inventory menu transaction precondition changed before click");
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
                            menuSwap);
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
            case WorldInteractionActionSpec.UseItem useItem ->
                    verifyUse(envelope, player, state, useItem);
            case WorldInteractionActionSpec.ReleaseUse releaseUse ->
                    verifyRelease(envelope, player, releaseUse);
            case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                    verifyUseOn(envelope, player, state, useOnBlock);
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
                    applyMenuCleanupClick(
                            key,
                            player,
                            state,
                            plan,
                            decision,
                            request);
        };
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
                .recordObservedForwardProgress();
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
            case WorldInteractionActionSpec.BreakBlock breakBlock ->
                    validateBlockInteraction(
                            envelope,
                            player,
                            breakBlock.target(),
                            InteractionHand.MAIN_HAND,
                            breakBlock.expectedTool());
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
                            false);
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
        return BackendResult.accepted(envelope);
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

    private BackendResult validateEntity(
            ActionEnvelope envelope,
            BotServerPlayer player,
            EntityTargetFingerprint target,
            boolean attack) {
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
        if (pickupWait.expectedItemEntityId().isEmpty()) {
            return BackendResult.accepted(envelope);
        }
        Entity entity =
                player.serverLevel()
                        .getEntity(
                                pickupWait.expectedItemEntityId()
                                        .orElseThrow());
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
        return BackendResult.accepted(envelope);
    }

    private void dispatchStart(
            BotServerPlayer player, InteractionState state) {
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
            case WorldInteractionActionSpec.BreakBlock breakBlock ->
                    dispatchBreak(
                            player,
                            breakBlock,
                            ServerboundPlayerActionPacket.Action
                                    .START_DESTROY_BLOCK);
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
        applyNextMenuSwapStep(player, state, menuSwap);
    }

    private void applyNextMenuSwapStep(
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap) {
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
            dispatchBreak(
                    player,
                    breakBlock,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            state.breakStopSent = true;
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
                dispatchReleaseUse(player);
                state.releaseSent = true;
                yield BackendResult.readyToVerify(envelope);
            }
        };
    }

    private BackendResult tickPickup(
            ActionEnvelope envelope,
            BotServerPlayer player,
            InteractionState state,
            WorldInteractionActionSpec.PickupWait pickupWait,
            long currentTick) {
        if (state.pickupEntityId != null
                && player.serverLevel()
                        .getEntity(state.pickupEntityId) == null) {
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
            WorldInteractionActionSpec.InventoryMenuSwap menuSwap) {
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
                    player, state, menuSwap);
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
                        evidence("block.after", "minecraft:air"),
                        evidence(
                                "inventory.changed",
                                Boolean.toString(
                                        !MinecraftInteractionView
                                                .inventoryDigest(player)
                                                .equals(
                                                        state.inventoryBefore)))),
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
        if (state.pickupEntityId == null) {
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

        Entity remaining =
                player.serverLevel().getEntity(state.pickupEntityId);
        int matchingAfter =
                MinecraftInteractionView.inventoryCount(
                        player,
                        Objects.requireNonNull(
                                state.pickupItem,
                                "pickupItem"));
        int gained = matchingAfter - state.matchingCountBefore;
        if (remaining != null && !remaining.isRemoved()) {
            return failure(
                    envelope,
                    ActionFailureCode.TARGET_UNAVAILABLE,
                    "Expected item entity was not collected");
        }
        if (gained < state.pickupItem.count()) {
            return failure(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Pickup item conservation check failed");
        }
        return success(
                envelope,
                List.of(
                        evidence(
                                "entity.id",
                                state.pickupEntityId.toString()),
                        evidence(
                                "item.expected_count",
                                Integer.toString(
                                        state.pickupItem.count())),
                        evidence(
                                "item.gained_count",
                                Integer.toString(gained))),
                "Verified item entity pickup");
    }

    private void cleanupPlayerState(
            BotServerPlayer player,
            InteractionState state,
            ActionCleanupReason reason,
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
                        replacementInheritor);
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
                                decision);
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
            InventoryMenuSettlementDecision decision) {
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

    private static boolean requiresTicks(
            WorldInteractionActionSpec spec) {
        return switch (spec) {
            case WorldInteractionActionSpec.BreakBlock ignored -> true;
            case WorldInteractionActionSpec.PickupWait ignored -> true;
            case WorldInteractionActionSpec.InventoryMenuSwap ignored ->
                    true;
            case WorldInteractionActionSpec.UseItem useItem ->
                    useItem.mode()
                            != WorldInteractionActionSpec.ItemUseMode
                                    .INSTANT;
            default -> false;
        };
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
        return BackendResult.failed(envelope, code, List.of(), summary);
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

    private static final class MenuPreconditionChangedException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class InteractionState {
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
        private final UUID pickupEntityId;
        private final ItemStackFingerprint pickupItem;
        private final int matchingCountBefore;
        private final int selectedBefore;
        private final int foodLevelBefore;
        private boolean sideEffectDispatched;
        private boolean startedUsing;
        private boolean releaseSent;
        private boolean breakStopSent;
        private InventoryMenuTransaction menuTransaction;
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
                long startedTick,
                ItemStackFingerprint heldBefore,
                String inventoryBefore,
                String inventoryMultisetBefore,
                BlockTargetFingerprint blockBefore,
                int containerIdBefore,
                Entity vehicleBefore,
                float targetHealthBefore,
                UUID pickupEntityId,
                ItemStackFingerprint pickupItem,
                int matchingCountBefore,
                int selectedBefore,
                int foodLevelBefore) {
            this.botId = Objects.requireNonNull(
                    botId, "botId");
            if (botGeneration <= 0L) {
                throw new IllegalArgumentException(
                        "botGeneration must be positive");
            }
            this.botGeneration = botGeneration;
            this.spec = spec;
            this.startedTick = startedTick;
            this.heldBefore = heldBefore;
            this.inventoryBefore = inventoryBefore;
            this.inventoryMultisetBefore = inventoryMultisetBefore;
            this.blockBefore = blockBefore;
            this.containerIdBefore = containerIdBefore;
            this.vehicleBefore = vehicleBefore;
            this.targetHealthBefore = targetHealthBefore;
            this.pickupEntityId = pickupEntityId;
            this.pickupItem = pickupItem;
            this.matchingCountBefore = matchingCountBefore;
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
                long botGeneration) {
            InteractionHand hand = switch (spec) {
                case WorldInteractionActionSpec.UseItem useItem ->
                        MinecraftInteractionView.hand(useItem.hand());
                case WorldInteractionActionSpec.ReleaseUse releaseUse ->
                        MinecraftInteractionView.hand(releaseUse.hand());
                case WorldInteractionActionSpec.UseOnBlock useOnBlock ->
                        MinecraftInteractionView.hand(useOnBlock.hand());
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
                case WorldInteractionActionSpec.BreakBlock breakBlock ->
                        MinecraftInteractionView.blockFingerprint(
                                player,
                                MinecraftInteractionView.position(
                                        breakBlock.target()
                                                .target()
                                                .position()));
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

            UUID pickupEntityId = null;
            ItemStackFingerprint pickupItem = null;
            int matchingCountBefore = 0;
            if (spec
                            instanceof WorldInteractionActionSpec
                                    .PickupWait pickupWait
                    && pickupWait.expectedItemEntityId().isPresent()) {
                pickupEntityId =
                        pickupWait.expectedItemEntityId().orElseThrow();
                Entity target =
                        player.serverLevel().getEntity(pickupEntityId);
                if (target instanceof ItemEntity itemEntity) {
                    pickupItem =
                            MinecraftInteractionView.itemFingerprint(
                                    player, itemEntity.getItem());
                    matchingCountBefore =
                            MinecraftInteractionView.inventoryCount(
                                    player, pickupItem);
                }
            }

            return new InteractionState(
                    botId,
                    botGeneration,
                    spec,
                    startedTick,
                    heldBefore,
                    MinecraftInteractionView.inventoryDigest(player),
                    spec
                                    instanceof WorldInteractionActionSpec
                                            .SwapInventoryHotbar
                                    || spec
                                            instanceof WorldInteractionActionSpec
                                                    .InventoryMenuSwap
                            ? MinecraftInteractionView
                                    .inventoryMultisetDigest(player)
                            : null,
                    blockBefore,
                    player.containerMenu.containerId,
                    player.getVehicle(),
                    targetHealthBefore,
                    pickupEntityId,
                    pickupItem,
                    matchingCountBefore,
                    player.getInventory().selected,
                    player.getFoodData().getFoodLevel());
        }
    }
}
