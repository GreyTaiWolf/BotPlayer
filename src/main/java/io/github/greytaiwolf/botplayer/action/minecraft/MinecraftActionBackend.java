package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReceipt;
import io.github.greytaiwolf.botplayer.action.ActionCleanupRequest;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ClimbInputAction;
import io.github.greytaiwolf.botplayer.action.JumpAction;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.action.WaitAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.input.PlayerInputController;
import io.github.greytaiwolf.botplayer.action.input.PlayerInputOwner;
import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotActionTarget;
import io.github.greytaiwolf.botplayer.lifecycle.BotActionTargetStatus;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Server-thread Minecraft bridge for deterministic P2 actions.
 *
 * <p>The lifecycle manager is resolved again for every backend call. State contains only immutable
 * identities and primitive snapshots; it never retains a player, level, entity, item, or other
 * live Minecraft object between calls.
 */
public final class MinecraftActionBackend implements ActionBackend {
    private static final double MIN_LOOK_VECTOR_LENGTH_SQUARED =
            1.0E-12D;
    private static final double LOOK_TOLERANCE_DEGREES = 0.5D;
    private static final double MIN_MOVEMENT_PROGRESS_SQUARED =
            1.0E-4D;
    private static final double MOVEMENT_DISTANCE_TOLERANCE = 1.0D;

    private final BotLifecycleManager lifecycleManager;
    private final PlayerInputController inputController;
    private final MinecraftWorldInteractionBackend
            worldInteractionBackend;
    private final Map<StateKey, BackendState> states = new HashMap<>();

    /** Outcome of one exact strict-natural-use cancellation preflight. */
    public enum StrictUseCancellationFenceStatus {
        /** The exact active natural use was marked for the native Mixin. */
        MARKED,
        /**
         * Vanilla {@code completeUsingItem()} has already been entered for
         * the exact action. A cancellation can no longer safely claim that it
         * retracted the physical use.
         */
        NATIVE_COMPLETION_ENTERED,
        /** No strict natural item use is active for the requested generation. */
        NO_ACTIVE_STRICT_USE,
        /** A different strict natural use is active in the requested generation. */
        ACTIVE_STRICT_USE_MISMATCH
    }

    public MinecraftActionBackend(
            BotLifecycleManager lifecycleManager,
            PlayerInputController inputController) {
        this.lifecycleManager =
                Objects.requireNonNull(
                        lifecycleManager, "lifecycleManager");
        this.inputController =
                Objects.requireNonNull(
                        inputController, "inputController");
        this.worldInteractionBackend =
                new MinecraftWorldInteractionBackend(lifecycleManager);
    }

    /**
     * Arms the one-shot generation fence before a skill can own inventory
     * layout state.
     */
    public boolean openSkillInventoryLayout(
            UUID botId,
            long botGeneration,
            InventoryLayoutCleanupLease layoutLease) {
        return worldInteractionBackend.openSkillInventoryLayout(
                botId, botGeneration, layoutLease);
    }

    /**
     * Reconciles a completed skill's temporary inventory layout after the
     * action runtime has synchronously closed that generation.
     */
    public InventoryLayoutCleanupResult cleanupSkillInventoryLayout(
            UUID botId,
            long botGeneration,
            InventoryLayoutCleanupRequest request) {
        return worldInteractionBackend.cleanupSkillInventoryLayout(
                botId, botGeneration, request);
    }

    /**
     * Consumes an exact skill layout lease after vanilla death has already
     * emptied the body. This bookkeeping-only path never resolves or clicks
     * the dead player's menu.
     */
    public InventoryLayoutCleanupResult
            consumeVanillaDeathSkillInventoryLayout(
                    UUID botId,
                    long botGeneration,
                    InventoryLayoutCleanupLease layoutLease) {
        return worldInteractionBackend
                .consumeVanillaDeathSkillInventoryLayout(
                        botId,
                        botGeneration,
                        layoutLease);
    }

    public void releaseSkillInventoryLayout(
            UUID botId, long botGeneration, UUID runId) {
        worldInteractionBackend.releaseSkillInventoryLayout(
                botId, botGeneration, runId);
    }

    public void closeSkillInventoryGeneration(
            UUID botId, long botGeneration) {
        worldInteractionBackend.closeSkillInventoryGeneration(
                botId, botGeneration);
    }

    public void closeSkillInventoryFences() {
        worldInteractionBackend.closeSkillInventoryFences();
    }

    /**
     * Runs the strict natural-use fence from the native LivingEntity item-use
     * hook. The lifecycle manager has already verified server-thread listener
     * authority; this backend only examines its short-lived action state.
     *
     * @return whether vanilla must skip the pending native item-use update
     */
    public boolean beforeNativeItemUseUpdate(BotServerPlayer player) {
        return worldInteractionBackend.beforeNativeItemUseUpdate(player);
    }

    /**
     * Rechecks a strict natural use and enters its one-way native completion
     * boundary immediately before vanilla calls {@code completeUsingItem()}.
     *
     * @return whether vanilla must skip the pending completion call
     */
    public boolean beforeNativeItemUseCompletion(BotServerPlayer player) {
        return worldInteractionBackend.beforeNativeItemUseCompletion(player);
    }

    /**
     * Arms the exact strict-use fence before a queued lifecycle cancellation
     * can be drained. The marker itself performs no inventory or world write;
     * {@code LivingEntityUseItemMixin} consumes it immediately before native
     * item use advances.
     */
    public StrictUseCancellationFenceStatus
            fenceStrictNativeItemUseCancellation(
                    ActionEnvelope expected) {
        return worldInteractionBackend.fenceStrictNativeItemUseCancellation(
                expected);
    }

    @Override
    public BackendResult validate(
            ActionEnvelope envelope, long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        BotActionTarget target = resolve(envelope);
        BackendResult unavailable = unavailable(envelope, target);
        if (unavailable != null) {
            return unavailable;
        }
        if (envelope.action() instanceof WorldInteractionAction) {
            return worldInteractionBackend.validate(
                    envelope, currentTick);
        }

        BotServerPlayer player = target.player().orElseThrow();
        if (envelope.action() instanceof LookAtAction lookAt) {
            double angle = viewAngleDegrees(
                    player, lookAt.x(), lookAt.y(), lookAt.z());
            if (!Double.isFinite(angle)) {
                return BackendResult.failed(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        List.of(),
                        "Look target must differ from the bot eye position");
            }
            return BackendResult.accepted(envelope);
        }
        if (envelope.action() instanceof MoveInputAction move) {
            return validateMovementDuration(
                    envelope, currentTick, move.ticks());
        }
        if (envelope.action() instanceof JumpAction jump) {
            BackendResult duration = validateMovementDuration(
                    envelope, currentTick, jump.holdTicks());
            if (duration.step() != BackendStep.ACCEPTED) {
                return duration;
            }
            JumpAction.StartMode startMode =
                    JumpAction.classifyStart(
                            player.onGround(), player.isInWater());
            if (startMode == JumpAction.StartMode.REJECTED) {
                return BackendResult.failed(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        List.of(),
                        "Jump requires ground contact or water");
            }
            return BackendResult.accepted(envelope);
        }
        if (envelope.action() instanceof ClimbInputAction climb) {
            BackendResult duration = validateMovementDuration(
                    envelope, currentTick, climb.ticks());
            if (duration.step() != BackendStep.ACCEPTED) {
                return duration;
            }
            if (!player.onClimbable()) {
                return BackendResult.failed(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        List.of(),
                        "Climb input requires a vanilla climbable position");
            }
            return BackendResult.accepted(envelope);
        }
        if (envelope.action() instanceof WaitAction
                || envelope.action() instanceof StopAction) {
            return BackendResult.accepted(envelope);
        }
        return unsupported(envelope);
    }

    @Override
    public BackendResult start(
            ActionEnvelope envelope, long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        BotActionTarget target = resolve(envelope);
        BackendResult unavailable = unavailable(envelope, target);
        if (unavailable != null) {
            return unavailable;
        }
        if (envelope.action() instanceof WorldInteractionAction) {
            return worldInteractionBackend.start(
                    envelope, currentTick);
        }

        StateKey key = stateKey(envelope);
        if (states.containsKey(key)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Action backend state already exists");
        }

        BotServerPlayer player = target.player().orElseThrow();
        if (envelope.action() instanceof WaitAction) {
            states.put(key, new WaitState(currentTick));
            return BackendResult.running(envelope);
        }
        if (envelope.action() instanceof LookAtAction lookAt) {
            return startLookAt(envelope, player, lookAt);
        }
        if (envelope.action() instanceof MoveInputAction move) {
            return startMove(
                    envelope, player, move, currentTick);
        }
        if (envelope.action() instanceof JumpAction jump) {
            return startJump(
                    envelope, player, jump, currentTick);
        }
        if (envelope.action() instanceof ClimbInputAction climb) {
            return startClimb(
                    envelope, player, climb, currentTick);
        }
        if (envelope.action() instanceof StopAction) {
            return startStop(envelope, player, currentTick);
        }
        return unsupported(envelope);
    }

    @Override
    public BackendResult tick(
            ActionEnvelope envelope,
            long startedTick,
            long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        BotActionTarget target = resolve(envelope);
        BackendResult unavailable = unavailable(envelope, target);
        if (unavailable != null) {
            return unavailable;
        }
        if (envelope.action() instanceof WorldInteractionAction) {
            return worldInteractionBackend.tick(
                    envelope, startedTick, currentTick);
        }

        BackendState state = states.get(stateKey(envelope));
        if (envelope.action() instanceof WaitAction wait) {
            if (!(state instanceof WaitState waitState)
                    || waitState.startedTick() != startedTick
                    || currentTick < startedTick) {
                return missingState(envelope);
            }
            return currentTick - startedTick >= wait.ticks()
                    ? BackendResult.readyToVerify(envelope)
                    : BackendResult.running(envelope);
        }
        if (envelope.action() instanceof LookAtAction
                && state instanceof LookState) {
            return BackendResult.readyToVerify(envelope);
        }
        if (envelope.action() instanceof MoveInputAction move
                && state instanceof MoveState moveState) {
            return tickMove(
                    envelope,
                    target.player().orElseThrow(),
                    move,
                    moveState,
                    currentTick);
        }
        if (envelope.action() instanceof JumpAction jump
                && state instanceof JumpState jumpState) {
            return tickJump(
                    envelope,
                    target.player().orElseThrow(),
                    jump,
                    jumpState,
                    currentTick);
        }
        if (envelope.action() instanceof ClimbInputAction climb
                && state instanceof ClimbState climbState) {
            return tickClimb(
                    envelope,
                    target.player().orElseThrow(),
                    climb,
                    climbState,
                    currentTick);
        }
        if (envelope.action() instanceof StopAction
                && state instanceof StopState) {
            return BackendResult.readyToVerify(envelope);
        }
        if (envelope.action() instanceof LookAtAction
                || envelope.action() instanceof MoveInputAction
                || envelope.action() instanceof JumpAction
                || envelope.action() instanceof ClimbInputAction
                || envelope.action() instanceof StopAction) {
            return missingState(envelope);
        }
        return unsupported(envelope);
    }

    @Override
    public BackendResult verify(
            ActionEnvelope envelope, long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        BotActionTarget target = resolve(envelope);
        BackendResult unavailable = unavailable(envelope, target);
        if (unavailable != null) {
            return unavailable;
        }
        if (envelope.action() instanceof WorldInteractionAction) {
            return worldInteractionBackend.verify(
                    envelope, currentTick);
        }

        BackendState state = states.get(stateKey(envelope));
        BotServerPlayer player = target.player().orElseThrow();
        if (envelope.action() instanceof WaitAction wait) {
            if (!(state instanceof WaitState waitState)
                    || currentTick < waitState.startedTick()) {
                return missingState(envelope);
            }
            long elapsed =
                    currentTick - waitState.startedTick();
            List<ActionEvidence> evidence = List.of(
                    new ActionEvidence(
                            "wait.requested_ticks",
                            Integer.toString(wait.ticks())),
                    new ActionEvidence(
                            "wait.elapsed_ticks",
                            Long.toString(elapsed)));
            if (elapsed < wait.ticks()) {
                return BackendResult.failed(
                        envelope,
                        ActionFailureCode.PRECONDITION_FAILED,
                        evidence,
                        "Wait duration has not elapsed");
            }
            return BackendResult.succeeded(
                    envelope, evidence, "Wait completed");
        }
        if (envelope.action() instanceof LookAtAction) {
            if (!(state instanceof LookState lookState)) {
                return missingState(envelope);
            }
            return verifyLookAt(envelope, player, lookState);
        }
        if (envelope.action() instanceof MoveInputAction) {
            if (!(state instanceof MoveState moveState)) {
                return missingState(envelope);
            }
            return verifyMove(envelope, player, moveState);
        }
        if (envelope.action() instanceof JumpAction) {
            if (!(state instanceof JumpState jumpState)) {
                return missingState(envelope);
            }
            return verifyJump(envelope, player, jumpState);
        }
        if (envelope.action() instanceof ClimbInputAction climb) {
            if (!(state instanceof ClimbState climbState)) {
                return missingState(envelope);
            }
            return verifyClimb(
                    envelope, player, climb, climbState);
        }
        if (envelope.action() instanceof StopAction) {
            if (!(state instanceof StopState stopState)) {
                return missingState(envelope);
            }
            return verifyStop(envelope, player, stopState);
        }
        return unsupported(envelope);
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
        if (envelope.action() instanceof WorldInteractionAction) {
            return worldInteractionBackend.cleanupStep(
                    envelope, request);
        }
        if (request.vanillaDeathConsumed()) {
            states.remove(stateKey(envelope));
            inputController.forgetBot(
                    envelope.botId(), envelope.botGeneration());
            return ActionCleanupReceipt.complete(
                    request,
                    0L,
                    "Vanilla death consumed action body state");
        }
        return ActionBackend.super.cleanupStep(
                envelope, request);
    }

    @Override
    public void cleanup(
            ActionEnvelope envelope,
            ActionCleanupReason reason,
            long currentTick) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(reason, "reason");
        if (envelope.action() instanceof WorldInteractionAction) {
            worldInteractionBackend.cleanup(
                    envelope, reason, currentTick);
            return;
        }

        StateKey key = stateKey(envelope);
        if (reason == ActionCleanupReason.VANILLA_DEATH_CONSUMED) {
            states.remove(key);
            inputController.forgetBot(
                    envelope.botId(), envelope.botGeneration());
            return;
        }
        BackendState state = states.get(key);
        if (state instanceof MovementState movementState) {
            cleanupInputState(envelope, movementState);
        }
        BotServerPlayer cleanupPlayer =
                lifecycleManager
                        .resolveCleanupTarget(
                                envelope.botId(),
                                envelope.botGeneration())
                        .orElse(null);
        if (cleanupPlayer != null
                && envelope.action() instanceof StopAction) {
            stopPlayer(cleanupPlayer);
        }
        /*
         * Remove only after physical cleanup succeeds. On a partial adapter failure the retained
         * primitive state lets the runtime retry the same exact-owner cleanup.
         */
        states.remove(key, state);
    }

    @Override
    public boolean forceSafeReset(
            UUID botId,
            long botGeneration,
            long currentTick) {
        RuntimeException failure = null;
        boolean worldReset = false;
        try {
            worldReset = worldInteractionBackend.forceSafeReset(
                    botId, botGeneration, currentTick);
        } catch (RuntimeException exception) {
            failure = exception;
        }

        boolean inputReset = false;
        try {
            BotServerPlayer player =
                    lifecycleManager
                            .resolveCleanupTarget(
                                    botId, botGeneration)
                            .orElse(null);
            if (player != null) {
                PlayerInputController.ForceClearStatus status =
                        inputController.forceClear(
                                botId, botGeneration);
                if (status
                                != PlayerInputController
                                        .ForceClearStatus
                                        .NEWER_GENERATION_PRESERVED
                        && lifecycleManager
                                        .resolveCleanupTarget(
                                                botId,
                                                botGeneration)
                                        .orElse(null)
                                == player) {
                    stopPlayer(player);
                    states.entrySet().removeIf(entry ->
                            entry.getKey().botId().equals(botId)
                                    && entry.getKey()
                                                    .botGeneration()
                                            == botGeneration
                                    && entry.getValue()
                                            instanceof MovementState);
                    inputReset = true;
                }
            }
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return worldReset && inputReset;
    }

    private BackendResult startLookAt(
            ActionEnvelope envelope,
            BotServerPlayer player,
            LookAtAction lookAt) {
        double beforeError = viewAngleDegrees(
                player, lookAt.x(), lookAt.y(), lookAt.z());
        if (!Double.isFinite(beforeError)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Look target must differ from the bot eye position");
        }

        float beforeYaw = player.getYRot();
        float beforePitch = player.getXRot();
        player.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                new Vec3(lookAt.x(), lookAt.y(), lookAt.z()));
        float afterYaw = player.getYRot();
        float afterPitch = player.getXRot();
        double afterError = viewAngleDegrees(
                player, lookAt.x(), lookAt.y(), lookAt.z());
        states.put(
                stateKey(envelope),
                new LookState(
                        lookAt.x(),
                        lookAt.y(),
                        lookAt.z(),
                        beforeYaw,
                        beforePitch,
                        afterYaw,
                        afterPitch,
                        beforeError,
                        afterError));
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult startStop(
            ActionEnvelope envelope,
            BotServerPlayer player,
            long currentTick) {
        StopState state = new StopState(
                currentTick,
                player.xxa,
                player.yya,
                player.zza,
                player.isShiftKeyDown(),
                player.isSprinting(),
                player.isUsingItem());
        stopPlayer(player);
        states.put(stateKey(envelope), state);
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult validateMovementDuration(
            ActionEnvelope envelope,
            long currentTick,
            int requestedTicks) {
        if (envelope.maxTicks() <= requestedTicks) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.INVALID_REQUEST,
                    List.of(),
                    "Movement maxTicks must exceed its input duration");
        }
        if (currentTick
                        > Long.MAX_VALUE - requestedTicks - 1L
                || envelope.deadlineTick()
                        <= currentTick + requestedTicks) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.DEADLINE_EXCEEDED,
                    List.of(),
                    "Movement deadline is too short for its input duration");
        }
        return BackendResult.accepted(envelope);
    }

    private BackendResult startMove(
            ActionEnvelope envelope,
            BotServerPlayer player,
            MoveInputAction move,
            long currentTick) {
        PlayerInputOwner owner =
                PlayerInputOwner.from(envelope);
        long expiresAtTick =
                leaseExpiry(envelope, currentTick);
        MoveState state = new MoveState(
                owner,
                currentTick,
                expiresAtTick,
                player.getX(),
                player.getY(),
                player.getZ(),
                move.forward() != 0.0F
                        || move.strafe() != 0.0F,
                move.sneak(),
                move.swim());
        BackendResult claimFailure = claimInput(
                envelope,
                owner,
                move.inputState(),
                currentTick,
                expiresAtTick);
        if (claimFailure != null) {
            return claimFailure;
        }
        states.put(stateKey(envelope), state);
        return applyInitialInput(
                envelope,
                player,
                move.inputState(),
                state,
                "Movement");
    }

    private BackendResult startJump(
            ActionEnvelope envelope,
            BotServerPlayer player,
            JumpAction jump,
            long currentTick) {
        JumpAction.StartMode startMode =
                JumpAction.classifyStart(
                        player.onGround(), player.isInWater());
        if (startMode == JumpAction.StartMode.REJECTED) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Jump requires ground contact or water");
        }
        PlayerInputOwner owner =
                PlayerInputOwner.from(envelope);
        long expiresAtTick =
                leaseExpiry(envelope, currentTick);
        JumpState state = new JumpState(
                owner,
                currentTick,
                expiresAtTick,
                player.getX(),
                player.getY(),
                player.getZ(),
                startMode);
        BackendResult claimFailure = claimInput(
                envelope,
                owner,
                jump.inputState(),
                currentTick,
                expiresAtTick);
        if (claimFailure != null) {
            return claimFailure;
        }
        states.put(stateKey(envelope), state);
        return applyInitialInput(
                envelope,
                player,
                jump.inputState(),
                state,
                "Jump");
    }

    private BackendResult startClimb(
            ActionEnvelope envelope,
            BotServerPlayer player,
            ClimbInputAction climb,
            long currentTick) {
        if (!player.onClimbable()) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Climb input requires a vanilla climbable position");
        }
        PlayerInputOwner owner = PlayerInputOwner.from(envelope);
        long expiresAtTick = leaseExpiry(envelope, currentTick);
        ClimbState state = new ClimbState(
                owner,
                currentTick,
                expiresAtTick,
                player.getX(),
                player.getY(),
                player.getZ());
        BackendResult claimFailure = claimInput(
                envelope,
                owner,
                climb.inputState(),
                currentTick,
                expiresAtTick);
        if (claimFailure != null) {
            return claimFailure;
        }
        states.put(stateKey(envelope), state);
        return applyInitialInput(
                envelope,
                player,
                climb.inputState(),
                state,
                "Climb");
    }

    private BackendResult applyInitialInput(
            ActionEnvelope envelope,
            BotServerPlayer initiallyResolvedPlayer,
            PlayerInputState input,
            MovementState state,
            String actionName) {
        BotServerPlayer guardedPlayer = resolveExactOwnedPlayer(
                envelope, state.owner, initiallyResolvedPlayer);
        if (guardedPlayer == null) {
            rollbackFailedStart(envelope, state);
            return unavailableOrUnsafe(
                    envelope,
                    actionName
                            + " lost its generation or input owner before apply");
        }

        state.inputApplyAttempted = true;
        try {
            MinecraftPlayerInputAdapter.apply(
                    guardedPlayer, input);
        } catch (RuntimeException applyFailure) {
            rollbackFailedStart(envelope, state);
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.INTERNAL_ERROR,
                    List.of(),
                    actionName + " input adapter failed");
        }

        if (resolveExactOwnedPlayer(
                        envelope, state.owner, guardedPlayer)
                == null) {
            rollbackFailedStart(envelope, state);
            return unavailableOrUnsafe(
                    envelope,
                    actionName
                            + " lost its generation or input owner during apply");
        }
        return BackendResult.running(envelope);
    }

    private void rollbackFailedStart(
            ActionEnvelope envelope, MovementState state) {
        try {
            cleanupInputState(envelope, state);
            states.remove(stateKey(envelope), state);
        } catch (RuntimeException cleanupFailure) {
            // Retain provisional state for the runtime's mandatory cleanup retry.
        }
    }

    private BackendResult tickMove(
            ActionEnvelope envelope,
            BotServerPlayer player,
            MoveInputAction move,
            MoveState state,
            long currentTick) {
        if (currentTick < state.startedTick) {
            return missingState(envelope);
        }
        if (!captureMovement(player, state, currentTick)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Movement produced a non-finite position");
        }

        double horizontalDistanceSquared =
                horizontalDistanceSquared(
                        state.startX,
                        state.startZ,
                        player.getX(),
                        player.getZ());
        double maximumDistance =
                move.expectedMaximumHorizontalDistance()
                        + MOVEMENT_DISTANCE_TOLERANCE;
        if (horizontalDistanceSquared
                > maximumDistance * maximumDistance) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Movement exceeded its physical safety envelope");
        }

        long elapsed = currentTick - state.startedTick;
        if (state.requiresProgress
                && elapsed >= move.stuckWindowTicks()
                && currentTick - state.lastProgressTick
                        >= move.stuckWindowTicks()) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    movementEvidence(player, state),
                    "Movement is stuck");
        }
        if (elapsed < move.ticks()) {
            return BackendResult.running(envelope);
        }

        BackendResult clearFailure = clearOwnedInput(
                envelope, player, state, currentTick);
        if (clearFailure != null) {
            return clearFailure;
        }
        state.completed = true;
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult tickJump(
            ActionEnvelope envelope,
            BotServerPlayer player,
            JumpAction jump,
            JumpState state,
            long currentTick) {
        if (currentTick < state.startedTick) {
            return missingState(envelope);
        }
        if (!captureMovement(player, state, currentTick)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Jump produced a non-finite position");
        }
        state.leftGround |= !player.onGround();
        state.enteredWater |= player.isInWater();
        state.maxY = Math.max(state.maxY, player.getY());
        state.maximumDisplacementSquared = Math.max(
                state.maximumDisplacementSquared,
                distanceSquared(
                        state.startX,
                        state.startY,
                        state.startZ,
                        player.getX(),
                        player.getY(),
                        player.getZ()));

        long elapsed = currentTick - state.startedTick;
        if (elapsed < jump.holdTicks()) {
            return BackendResult.running(envelope);
        }
        BackendResult clearFailure = clearOwnedInput(
                envelope, player, state, currentTick);
        if (clearFailure != null) {
            return clearFailure;
        }
        state.completed = true;
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult tickClimb(
            ActionEnvelope envelope,
            BotServerPlayer player,
            ClimbInputAction climb,
            ClimbState state,
            long currentTick) {
        if (currentTick < state.startedTick) {
            return missingState(envelope);
        }
        if (!captureMovement(player, state, currentTick)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Climb produced a non-finite position");
        }
        state.observedClimbable |= player.onClimbable();
        state.minimumY = Math.min(state.minimumY, player.getY());
        state.maximumY = Math.max(state.maximumY, player.getY());
        if (currentTick - state.startedTick < climb.ticks()) {
            return BackendResult.running(envelope);
        }
        BackendResult clearFailure = clearOwnedInput(
                envelope, player, state, currentTick);
        if (clearFailure != null) {
            return clearFailure;
        }
        state.completed = true;
        return BackendResult.readyToVerify(envelope);
    }

    private BackendResult verifyMove(
            ActionEnvelope envelope,
            BotServerPlayer player,
            MoveState state) {
        if (!state.completed) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Movement input duration has not completed");
        }
        List<ActionEvidence> evidence =
                movementEvidence(player, state);
        double horizontalDistanceSquared =
                horizontalDistanceSquared(
                        state.startX,
                        state.startZ,
                        player.getX(),
                        player.getZ());
        if (state.requiresProgress
                && horizontalDistanceSquared
                        < MIN_MOVEMENT_PROGRESS_SQUARED) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Movement produced no physical progress");
        }
        if (state.requiresSneak && !state.observedSneaking) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Sneak input never produced a crouching player pose");
        }
        if (state.requiresSwim
                && !state.observedWaterSprint) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Swim input never produced in-water sprint movement");
        }
        if (!inputIsZero(player)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    evidence,
                    "Movement input did not clear after completion");
        }
        return BackendResult.succeeded(
                envelope, evidence, "Movement verified");
    }

    private BackendResult verifyJump(
            ActionEnvelope envelope,
            BotServerPlayer player,
            JumpState state) {
        if (!state.completed) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Jump input duration has not completed");
        }
        double rise = state.maxY - state.startY;
        double horizontalDistance = Math.sqrt(
                horizontalDistanceSquared(
                        state.startX,
                        state.startZ,
                        player.getX(),
                        player.getZ()));
        double maximumDisplacement =
                Math.sqrt(state.maximumDisplacementSquared);
        List<ActionEvidence> evidence = List.of(
                new ActionEvidence(
                        "jump.rise_blocks", number(rise)),
                new ActionEvidence(
                        "jump.horizontal_blocks",
                        number(horizontalDistance)),
                new ActionEvidence(
                        "jump.maximum_displacement_blocks",
                        number(maximumDisplacement)),
                new ActionEvidence(
                        "jump.start_mode",
                        state.startMode.name()
                                .toLowerCase(Locale.ROOT)),
                new ActionEvidence(
                        "jump.left_ground",
                        Boolean.toString(state.leftGround)),
                new ActionEvidence(
                        "jump.entered_water",
                        Boolean.toString(state.enteredWater)),
                new ActionEvidence(
                        "jump.on_ground_after",
                        Boolean.toString(player.onGround())));
        boolean physicallyObserved =
                JumpAction.hasPhysicalSuccess(
                        state.startMode,
                        state.leftGround,
                        Math.max(0.0D, rise),
                        maximumDisplacement);
        if (!physicallyObserved) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    state.startMode == JumpAction.StartMode.GROUND
                            ? "Jump did not produce a ground-to-air rise"
                            : "Water jump produced no physical displacement");
        }
        if (!inputIsZero(player)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    evidence,
                    "Jump input did not clear after completion");
        }
        return BackendResult.succeeded(
                envelope, evidence, "Jump verified");
    }

    private BackendResult verifyClimb(
            ActionEnvelope envelope,
            BotServerPlayer player,
            ClimbInputAction climb,
            ClimbState state) {
        if (!state.completed) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    List.of(),
                    "Climb input duration has not completed");
        }
        double verticalDelta = player.getY() - state.startY;
        List<ActionEvidence> evidence = List.of(
                new ActionEvidence(
                        "climb.vertical_blocks",
                        number(verticalDelta)),
                new ActionEvidence(
                        "climb.minimum_y",
                        number(state.minimumY)),
                new ActionEvidence(
                        "climb.maximum_y",
                        number(state.maximumY)),
                new ActionEvidence(
                        "climb.observed_climbable",
                        Boolean.toString(state.observedClimbable)));
        if (!state.observedClimbable
                || !climb.hasVerticalProgress(
                        state.startY, player.getY())) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Climb did not produce the requested vertical progress");
        }
        if (!inputIsZero(player)) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    evidence,
                    "Climb input did not clear after completion");
        }
        return BackendResult.succeeded(
                envelope, evidence, "Climb verified");
    }

    private BackendResult claimInput(
            ActionEnvelope envelope,
            PlayerInputOwner owner,
            PlayerInputState input,
            long currentTick,
            long expiresAtTick) {
        PlayerInputController.ClaimStatus status =
                inputController.claim(
                        owner,
                        input,
                        currentTick,
                        expiresAtTick);
        return switch (status) {
            case CLAIMED -> null;
            case STALE_GENERATION -> BackendResult.stale(
                    envelope,
                    "Movement input generation is stale");
            case CAPACITY_EXHAUSTED -> BackendResult.failed(
                    envelope,
                    ActionFailureCode.RUNTIME_CAPACITY_EXCEEDED,
                    List.of(),
                    "Movement input capacity is exhausted");
            case ALREADY_OWNED, OWNED_BY_OTHER ->
                    BackendResult.failed(
                            envelope,
                            ActionFailureCode.UNSAFE_CONTROL_STATE,
                            List.of(),
                            "Movement input is already owned");
        };
    }

    private BackendResult clearOwnedInput(
            ActionEnvelope envelope,
            BotServerPlayer player,
            MovementState state,
            long currentTick) {
        if (resolveExactOwnedPlayer(
                        envelope, state.owner, player)
                == null) {
            return unavailableOrUnsafe(
                    envelope,
                    "Movement input generation or owner changed before cleanup");
        }
        long expiresAtTick = Math.min(
                state.expiresAtTick, currentTick + 1L);
        if (expiresAtTick <= currentTick) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.DEADLINE_EXCEEDED,
                    List.of(),
                    "Movement input lease expired before cleanup");
        }
        PlayerInputController.UpdateStatus update =
                inputController.update(
                        state.owner,
                        PlayerInputState.IDLE,
                        currentTick,
                        expiresAtTick);
        if (update
                        != PlayerInputController.UpdateStatus.UPDATED
                && update
                        != PlayerInputController.UpdateStatus.UNCHANGED) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.UNSAFE_CONTROL_STATE,
                    List.of(),
                    "Movement input ownership changed before cleanup");
        }
        if (resolveExactOwnedPlayer(
                        envelope, state.owner, player)
                == null) {
            return unavailableOrUnsafe(
                    envelope,
                    "Movement input generation or owner changed during cleanup");
        }
        MinecraftPlayerInputAdapter.clear(player);
        state.inputClearCompleted = true;
        return null;
    }

    private void cleanupInputState(
            ActionEnvelope envelope, MovementState state) {
        PlayerInputController.ReleaseStatus release =
                inputController.release(state.owner);
        boolean exactLeaseReleased =
                release
                                == PlayerInputController.ReleaseStatus.RELEASED
                        || release
                                == PlayerInputController
                                        .ReleaseStatus
                                        .ALREADY_CLEAR;
        if (exactLeaseReleased
                && state.inputApplyAttempted
                && !state.inputClearCompleted) {
            BotServerPlayer cleanupPlayer =
                    lifecycleManager
                            .resolveCleanupTarget(
                                    envelope.botId(),
                                    envelope.botGeneration())
                            .orElse(null);
            if (cleanupPlayer != null) {
                MinecraftPlayerInputAdapter.clear(
                        cleanupPlayer);
                state.inputClearCompleted = true;
            }
        }
    }

    private static long leaseExpiry(
            ActionEnvelope envelope, long currentTick) {
        long tickBudgetExpiry =
                currentTick
                                > Long.MAX_VALUE
                                        - envelope.maxTicks()
                        ? Long.MAX_VALUE
                        : currentTick + envelope.maxTicks();
        return Math.min(
                envelope.deadlineTick(), tickBudgetExpiry);
    }

    private static boolean captureMovement(
            BotServerPlayer player,
            MovementState state,
            long currentTick) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            return false;
        }
        if (state instanceof MoveState moveState) {
            if (horizontalDistanceSquared(
                            moveState.lastProgressX,
                            moveState.lastProgressZ,
                            x,
                            z)
                    >= MIN_MOVEMENT_PROGRESS_SQUARED) {
                moveState.lastProgressX = x;
                moveState.lastProgressZ = z;
                moveState.lastProgressTick = currentTick;
            }
            moveState.observedSneaking |=
                    player.isShiftKeyDown()
                            && player.getPose()
                                    == net.minecraft.world.entity.Pose
                                            .CROUCHING;
            moveState.observedWaterSprint |=
                    player.isInWater() && player.isSprinting();
            moveState.observedHorizontalCollision |=
                    player.horizontalCollision;
        }
        return true;
    }

    private static List<ActionEvidence> movementEvidence(
            BotServerPlayer player, MoveState state) {
        double horizontalDistance = Math.sqrt(
                horizontalDistanceSquared(
                        state.startX,
                        state.startZ,
                        player.getX(),
                        player.getZ()));
        return List.of(
                new ActionEvidence(
                        "move.horizontal_blocks",
                        number(horizontalDistance)),
                new ActionEvidence(
                        "move.vertical_blocks",
                        number(player.getY() - state.startY)),
                new ActionEvidence(
                        "move.on_ground_after",
                        Boolean.toString(player.onGround())),
                new ActionEvidence(
                        "move.horizontal_collision",
                        Boolean.toString(
                                state.observedHorizontalCollision
                                        || player.horizontalCollision)),
                new ActionEvidence(
                        "move.observed_sneaking",
                        Boolean.toString(
                                state.observedSneaking)),
                new ActionEvidence(
                        "move.observed_water_sprint",
                        Boolean.toString(
                                state.observedWaterSprint)),
                new ActionEvidence(
                        "move.in_water_after",
                        Boolean.toString(player.isInWater())));
    }

    private BackendResult verifyLookAt(
            ActionEnvelope envelope,
            BotServerPlayer player,
            LookState state) {
        double verifyError = viewAngleDegrees(
                player,
                state.targetX(),
                state.targetY(),
                state.targetZ());
        List<ActionEvidence> evidence = List.of(
                new ActionEvidence(
                        "look.before_yaw_deg",
                        number(Mth.wrapDegrees(
                                state.beforeYaw()))),
                new ActionEvidence(
                        "look.before_pitch_deg",
                        number(Mth.clamp(
                                state.beforePitch(),
                                -90.0F,
                                90.0F))),
                new ActionEvidence(
                        "look.after_yaw_deg",
                        number(Mth.wrapDegrees(
                                state.afterYaw()))),
                new ActionEvidence(
                        "look.after_pitch_deg",
                        number(Mth.clamp(
                                state.afterPitch(),
                                -90.0F,
                                90.0F))),
                new ActionEvidence(
                        "look.before_error_deg",
                        number(state.beforeError())),
                new ActionEvidence(
                        "look.after_error_deg",
                        number(state.afterError())),
                new ActionEvidence(
                        "look.verify_error_deg",
                        number(verifyError)));
        if (!Double.isFinite(state.afterError())
                || !Double.isFinite(verifyError)
                || state.afterError()
                        > LOOK_TOLERANCE_DEGREES
                || verifyError > LOOK_TOLERANCE_DEGREES) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Bot view did not reach the look target");
        }
        return BackendResult.succeeded(
                envelope, evidence, "Look target verified");
    }

    private BackendResult verifyStop(
            ActionEnvelope envelope,
            BotServerPlayer player,
            StopState state) {
        boolean inputZero = player.xxa == 0.0F
                && player.yya == 0.0F
                && player.zza == 0.0F;
        boolean flagsClear = !player.isShiftKeyDown()
                && !player.isSprinting()
                && !player.isUsingItem();
        List<ActionEvidence> evidence = List.of(
                new ActionEvidence(
                        "stop.before_input",
                        input(
                                state.beforeXxa(),
                                state.beforeYya(),
                                state.beforeZza())),
                new ActionEvidence(
                        "stop.after_input",
                        input(
                                player.xxa,
                                player.yya,
                                player.zza)),
                new ActionEvidence(
                        "stop.before_flags",
                        flags(
                                state.beforeShift(),
                                state.beforeSprint(),
                                state.beforeUsingItem())),
                new ActionEvidence(
                        "stop.after_flags",
                        flags(
                                player.isShiftKeyDown(),
                                player.isSprinting(),
                                player.isUsingItem())),
                new ActionEvidence(
                        "stop.jump_commanded", "false"),
                new ActionEvidence(
                        "stop.started_tick",
                        Long.toString(state.startedTick())));
        if (!inputZero || !flagsClear) {
            return BackendResult.failed(
                    envelope,
                    ActionFailureCode.PRECONDITION_FAILED,
                    evidence,
                    "Bot input did not reach the stopped state");
        }
        return BackendResult.succeeded(
                envelope, evidence, "Bot stop verified");
    }

    private BotActionTarget resolve(ActionEnvelope envelope) {
        return lifecycleManager.inspectActionTarget(
                envelope.botId(),
                envelope.botGeneration());
    }

    private BotServerPlayer resolveExactOwnedPlayer(
            ActionEnvelope envelope,
            PlayerInputOwner owner,
            BotServerPlayer expectedPlayer) {
        if (!inputController.isExactOwner(owner)) {
            return null;
        }
        BotActionTarget target = resolve(envelope);
        if (target.status() != BotActionTargetStatus.ACTIVE) {
            return null;
        }
        BotServerPlayer resolved =
                target.player().orElseThrow();
        return resolved == expectedPlayer ? resolved : null;
    }

    private BackendResult unavailableOrUnsafe(
            ActionEnvelope envelope, String summary) {
        BackendResult result =
                unavailable(envelope, resolve(envelope));
        return result != null
                ? result
                : BackendResult.failed(
                        envelope,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        List.of(),
                        summary);
    }

    private static StateKey stateKey(ActionEnvelope envelope) {
        return new StateKey(
                envelope.botId(),
                envelope.actionId(),
                envelope.botGeneration());
    }

    private static BackendResult unavailable(
            ActionEnvelope envelope, BotActionTarget target) {
        if (target.status() == BotActionTargetStatus.ACTIVE) {
            return null;
        }
        if (target.status()
                == BotActionTargetStatus.STALE_GENERATION) {
            return BackendResult.stale(
                    envelope, "Bot generation is stale");
        }
        return BackendResult.failed(
                envelope,
                ActionFailureCode.BOT_NOT_ACTIVE,
                List.of(),
                "Bot is not active");
    }

    private static BackendResult missingState(
            ActionEnvelope envelope) {
        return BackendResult.failed(
                envelope,
                ActionFailureCode.PRECONDITION_FAILED,
                List.of(),
                "Action backend state is unavailable");
    }

    private static BackendResult unsupported(
            ActionEnvelope envelope) {
        return BackendResult.failed(
                envelope,
                ActionFailureCode.UNSUPPORTED,
                List.of(),
                "Action type is not supported by the Minecraft backend");
    }

    private static void stopPlayer(BotServerPlayer player) {
        player.xxa = 0.0F;
        player.yya = 0.0F;
        player.zza = 0.0F;
        player.setJumping(false);
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.stopUsingItem();
    }

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
                || targetLengthSquared
                        <= MIN_LOOK_VECTOR_LENGTH_SQUARED) {
            return Double.NaN;
        }
        Vec3 view = player.getViewVector(1.0F);
        double viewLengthSquared = view.lengthSqr();
        if (!Double.isFinite(viewLengthSquared)
                || viewLengthSquared
                        <= MIN_LOOK_VECTOR_LENGTH_SQUARED) {
            return Double.NaN;
        }
        double dot = (view.x * deltaX
                        + view.y * deltaY
                        + view.z * deltaZ)
                / Math.sqrt(
                        viewLengthSquared
                                * targetLengthSquared);
        return Math.toDegrees(
                Math.acos(Mth.clamp(dot, -1.0D, 1.0D)));
    }

    private static boolean inputIsZero(
            BotServerPlayer player) {
        return player.xxa == 0.0F
                && player.yya == 0.0F
                && player.zza == 0.0F
                && !player.isShiftKeyDown()
                && !player.isSprinting();
    }

    private static double horizontalDistanceSquared(
            double startX,
            double startZ,
            double endX,
            double endZ) {
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private static double distanceSquared(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ) {
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        return deltaX * deltaX
                + deltaY * deltaY
                + deltaZ * deltaZ;
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    private static String input(
            float xxa, float yya, float zza) {
        return xxa + "," + yya + "," + zza;
    }

    private static String flags(
            boolean shift,
            boolean sprint,
            boolean usingItem) {
        return "shift="
                + shift
                + ",sprint="
                + sprint
                + ",using="
                + usingItem;
    }

    private interface BackendState {}

    private record StateKey(
            UUID botId, UUID actionId, long botGeneration) {}

    private record WaitState(long startedTick)
            implements BackendState {}

    private record LookState(
            double targetX,
            double targetY,
            double targetZ,
            float beforeYaw,
            float beforePitch,
            float afterYaw,
            float afterPitch,
            double beforeError,
            double afterError)
            implements BackendState {}

    private record StopState(
            long startedTick,
            float beforeXxa,
            float beforeYya,
            float beforeZza,
            boolean beforeShift,
            boolean beforeSprint,
            boolean beforeUsingItem)
            implements BackendState {}

    private abstract static class MovementState
            implements BackendState {
        final PlayerInputOwner owner;
        final long startedTick;
        final long expiresAtTick;
        final double startX;
        final double startY;
        final double startZ;
        boolean completed;
        boolean inputApplyAttempted;
        boolean inputClearCompleted;

        private MovementState(
                PlayerInputOwner owner,
                long startedTick,
                long expiresAtTick,
                double startX,
                double startY,
                double startZ) {
            this.owner = owner;
            this.startedTick = startedTick;
            this.expiresAtTick = expiresAtTick;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
        }
    }

    private static final class MoveState
            extends MovementState {
        private final boolean requiresProgress;
        private final boolean requiresSneak;
        private final boolean requiresSwim;
        private double lastProgressX;
        private double lastProgressZ;
        private long lastProgressTick;
        private boolean observedSneaking;
        private boolean observedWaterSprint;
        private boolean observedHorizontalCollision;

        private MoveState(
                PlayerInputOwner owner,
                long startedTick,
                long expiresAtTick,
                double startX,
                double startY,
                double startZ,
                boolean requiresProgress,
                boolean requiresSneak,
                boolean requiresSwim) {
            super(
                    owner,
                    startedTick,
                    expiresAtTick,
                    startX,
                    startY,
                    startZ);
            this.requiresProgress = requiresProgress;
            this.requiresSneak = requiresSneak;
            this.requiresSwim = requiresSwim;
            this.lastProgressX = startX;
            this.lastProgressZ = startZ;
            this.lastProgressTick = startedTick;
        }
    }

    private static final class JumpState
            extends MovementState {
        private final JumpAction.StartMode startMode;
        private double maxY;
        private double maximumDisplacementSquared;
        private boolean leftGround;
        private boolean enteredWater;

        private JumpState(
                PlayerInputOwner owner,
                long startedTick,
                long expiresAtTick,
                double startX,
                double startY,
                double startZ,
                JumpAction.StartMode startMode) {
            super(
                    owner,
                    startedTick,
                    expiresAtTick,
                    startX,
                    startY,
                    startZ);
            this.startMode = startMode;
            this.maxY = startY;
        }
    }

    private static final class ClimbState
            extends MovementState {
        private double minimumY;
        private double maximumY;
        private boolean observedClimbable;

        private ClimbState(
                PlayerInputOwner owner,
                long startedTick,
                long expiresAtTick,
                double startX,
                double startY,
                double startZ) {
            super(
                    owner,
                    startedTick,
                    expiresAtTick,
                    startX,
                    startY,
                    startZ);
            this.minimumY = startY;
            this.maximumY = startY;
        }
    }
}
