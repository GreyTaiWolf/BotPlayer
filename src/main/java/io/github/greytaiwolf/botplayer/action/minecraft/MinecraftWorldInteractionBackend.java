package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityLocalHit;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Executes P2-C through the same serverbound packet listener entry points as a physical player.
 *
 * <p>All state is short-lived, generation-scoped and confined to the server thread. A state entry
 * is installed before its first side effect so runtime cleanup can always undo held use or block
 * breaking when a packet handler re-enters lifecycle code.
 */
final class MinecraftWorldInteractionBackend implements ActionBackend {
    private final BotLifecycleManager lifecycleManager;
    private final Map<ActionKey, InteractionState> active = new LinkedHashMap<>();
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
        ActionKey key = ActionKey.from(envelope);
        InteractionState state =
                InteractionState.capture(player, action.spec(), currentTick);
        if (active.putIfAbsent(key, state) != null) {
            return failure(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    "World action was started more than once");
        }

        dispatchStart(player, state);
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
            cleanupPlayerState(player, state);
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
        try {
            player.stopUsingItem();
            for (Map.Entry<ActionKey, InteractionState> entry :
                    List.copyOf(active.entrySet())) {
                ActionKey key = entry.getKey();
                if (key.botId.equals(botId)
                        && key.botGeneration == botGeneration) {
                    cleanupPlayerState(player, entry.getValue());
                    active.remove(key, entry.getValue());
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
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
                    requireFingerprint(
                            envelope,
                            MinecraftInteractionView.itemFingerprint(
                                    player,
                                    player.getInventory()
                                            .getItem(selectHotbar.slot())),
                            selectHotbar.expectedSlotItem(),
                            "Selected hotbar precondition changed");
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

    private BackendResult validateHeldUse(
            ActionEnvelope envelope,
            BotServerPlayer player,
            WorldInteractionActionSpec.UseItem useItem) {
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
        switch (state.spec) {
            case WorldInteractionActionSpec.SelectHotbar selectHotbar ->
                    player.connection.handleSetCarriedItem(
                            new ServerboundSetCarriedItemPacket(
                                    selectHotbar.slot()));
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
        state.sideEffectDispatched = true;
        state.startedUsing = player.isUsingItem();
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
        boolean observable =
                state.startedUsing
                        || !after.equals(state.heldBefore)
                        || !inventoryAfter.equals(state.inventoryBefore)
                        || state.releaseSent;
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
                                "item.before_count",
                                Integer.toString(state.heldBefore.count())),
                        evidence(
                                "item.after_count",
                                Integer.toString(after.count())),
                        evidence(
                                "item.use_started",
                                Boolean.toString(state.startedUsing))),
                "Verified item use");
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
                removed
                        || healthAfter < state.targetHealthBefore
                        || (entity instanceof LivingEntity living
                                && living.hurtTime > 0);
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
            BotServerPlayer player, InteractionState state) {
        if (state.spec instanceof WorldInteractionActionSpec.BreakBlock
                breakBlock) {
            dispatchBreak(
                    player,
                    breakBlock,
                    ServerboundPlayerActionPacket.Action
                            .ABORT_DESTROY_BLOCK);
        }
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

    private Optional<BotServerPlayer> resolveActive(
            ActionEnvelope envelope) {
        return lifecycleManager.resolveActionTarget(
                envelope.botId(), envelope.botGeneration());
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

    private static final class InteractionState {
        private final WorldInteractionActionSpec spec;
        private final long startedTick;
        private final ItemStackFingerprint heldBefore;
        private final String inventoryBefore;
        private final BlockTargetFingerprint blockBefore;
        private final int containerIdBefore;
        private final Entity vehicleBefore;
        private final float targetHealthBefore;
        private final UUID pickupEntityId;
        private final ItemStackFingerprint pickupItem;
        private final int matchingCountBefore;
        private boolean sideEffectDispatched;
        private boolean startedUsing;
        private boolean releaseSent;
        private boolean breakStopSent;

        private InteractionState(
                WorldInteractionActionSpec spec,
                long startedTick,
                ItemStackFingerprint heldBefore,
                String inventoryBefore,
                BlockTargetFingerprint blockBefore,
                int containerIdBefore,
                Entity vehicleBefore,
                float targetHealthBefore,
                UUID pickupEntityId,
                ItemStackFingerprint pickupItem,
                int matchingCountBefore) {
            this.spec = spec;
            this.startedTick = startedTick;
            this.heldBefore = heldBefore;
            this.inventoryBefore = inventoryBefore;
            this.blockBefore = blockBefore;
            this.containerIdBefore = containerIdBefore;
            this.vehicleBefore = vehicleBefore;
            this.targetHealthBefore = targetHealthBefore;
            this.pickupEntityId = pickupEntityId;
            this.pickupItem = pickupItem;
            this.matchingCountBefore = matchingCountBefore;
        }

        private static InteractionState capture(
                BotServerPlayer player,
                WorldInteractionActionSpec spec,
                long startedTick) {
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
                    spec,
                    startedTick,
                    heldBefore,
                    MinecraftInteractionView.inventoryDigest(player),
                    blockBefore,
                    player.containerMenu.containerId,
                    player.getVehicle(),
                    targetHealthBefore,
                    pickupEntityId,
                    pickupItem,
                    matchingCountBefore);
        }
    }
}
