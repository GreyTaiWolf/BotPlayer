package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizedActionDispatch;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizationRevocation;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.ClaimedActionDispatch;
import io.github.greytaiwolf.botplayer.technique.combat.SingleMeleeStrikeTechnique;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancellationStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignal;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignalStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/**
 * Lifecycle-owned, one-way adapter from limited self-defense to the technique
 * runtime.
 *
 * <p>It accepts exactly one already-authorized {@code MELEE_ATTACK} dispatch,
 * binds it immutably to one {@link WorldInteractionActionSpec.AttackEntity},
 * and is the only approved child route for {@link SingleMeleeStrikeTechnique}
 * inside the lifecycle-owned coordinator. There is deliberately no generic
 * technique-to-world route: no caller can
 * provide a target, move, equip, retry, or schedule any action through this
 * class.
 *
 * <p>All mutation and polling is owner-thread only. The Action runtime may
 * complete its own caller futures from another thread, but this bridge ignores
 * those callbacks and polls retained terminal outcomes at a server-tick
 * boundary before it releases the original self-defense completion.
 */
public final class SelfDefenseTechniqueBridge extends TechniqueRoute {
    private static final int MAX_RETAINED_CANCELLATION_RECEIPTS = 256;
    private final TechniqueLifecycleCoordinator coordinator;
    private final SingleMeleeStrikeTechnique technique;
    private final ActionGateway actions;
    private final LongSupplier currentTickSupplier;
    private final Thread ownerThread;
    private final Map<UUID, ActionBinding> actionBindingsByActionId =
            new LinkedHashMap<>();
    private final Map<UUID, ActionBinding> actionBindingsByTicketId =
            new LinkedHashMap<>();
    /* Mutable lifecycle state stays outside the immutable action binding. */
    private final Map<UUID, Retraction> retractionsByActionId =
            new LinkedHashMap<>();
    /*
     * The Action mailbox has a bounded cancellation lane. Do not consume it
     * repeatedly for the same or a weaker bridge cancellation, but let a
     * lifecycle escalation reach the exact child once.
     */
    private final Map<UUID, ActionCancellationReason>
            dispatchedCancellationsByActionId = new LinkedHashMap<>();
    /** Duplicate lifecycle order must not convert an exact receipt into a no-op. */
    private final Map<ActionCancellationReceipt.Identity,
            ActionCancellationReceipt> cancellationReceipts =
            new LinkedHashMap<>();
    private final Map<UUID, RunBinding> runsByTechniqueId =
            new LinkedHashMap<>();
    private final Map<UUID, RunBinding> runsByBotId = new LinkedHashMap<>();
    private PendingStart pendingStart;
    private long lastObservedTick = -1L;
    /* A missing physical cancellation proof permanently fails this narrow port closed. */
    private boolean cancellationContainmentUnsafe;
    private boolean closed;

    public SelfDefenseTechniqueBridge(TechniqueLifecycleCoordinator coordinator,
            ActionGateway actions, LongSupplier currentTickSupplier) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        technique = new SingleMeleeStrikeTechnique();
        this.actions = Objects.requireNonNull(actions, "actions");
        this.currentTickSupplier = Objects.requireNonNull(currentTickSupplier,
                "currentTickSupplier");
        ownerThread = Thread.currentThread();
        this.coordinator.register(this);
    }

    /**
     * Starts the one permitted technique for a service-created self-defense
     * capability. A raw ActionDispatch has no public ingress here.
     */
    public CompletionStage<ActionOutcome> submit(
            AuthorizedActionDispatch authorization) {
        requireOwnerThread();
        AuthorizedActionDispatch required = Objects.requireNonNull(
                authorization, "authorization");
        long currentTick = observeSuppliedTick();
        if (closed) {
            throw new SubmissionRejectedException(
                    "Self-defense technique bridge is closed");
        }
        if (cancellationContainmentUnsafe) {
            throw new SubmissionRejectedException(
                    "Self-defense action cancellation containment is unsafe");
        }
        ClaimedActionDispatch claimed = required.claim().orElse(null);
        if (claimed == null) {
            throw new SubmissionRejectedException(
                    "Self-defense melee authorization was not current");
        }
        if (!isEligibleDispatch(claimed)) {
            ActionEnvelope rejectedEnvelope = envelopeFor(claimed);
            rememberCancellationReceipt(rejectedEnvelope,
                    ActionCancellationReceipt.fencedBeforeStart(
                            rejectedEnvelope.botId(),
                            rejectedEnvelope.botGeneration(),
                            rejectedEnvelope.actionId()));
            throw new SubmissionRejectedException(
                    "Self-defense melee binding was rejected");
        }
        if (actionBindingsByActionId.containsKey(claimed.actionId())
                || runsByBotId.containsKey(claimed.botId())) {
            throw new SubmissionRejectedException(
                    "self-defense technique is already active");
        }
        if (pendingStart != null) {
            throw new IllegalStateException(
                    "self-defense technique starts must not re-enter");
        }

        Reply reply = new Reply();
        PendingStart pending = new PendingStart(required, claimed, reply,
                currentTick);
        pendingStart = pending;
        try {
            TechniqueSubmission submission = coordinator.start(this,
                    new TechniqueStartRequest(claimed.selfDefenseRunId(),
                            claimed.botId(), claimed.botGeneration(),
                            SingleMeleeStrikeTechnique.ID,
                            SingleMeleeStrikeTechnique.VERSION,
                            TechniqueParameters.empty(), currentTick));
            if (submission.status() != TechniqueSubmission.Status.ACCEPTED) {
                throw new SubmissionRejectedException(
                        "Self-defense technique start was rejected: "
                                + submission.status());
            } else if (pending.failure != null) {
                if (!claimed.isAuthorityCurrent()) {
                    reply.complete(pending.failure);
                } else {
                    throw new SubmissionRejectedException(
                            "Self-defense technique child submission was rejected");
                }
            } else if (pending.binding == null
                    || !submission.techniqueRunId().orElseThrow()
                            .equals(pending.binding.run().techniqueRunId())) {
                cancelBindingIfPresent(pending.binding,
                        ActionCancellationReason.LIFECYCLE);
                throw new SubmissionRejectedException(
                        "self-defense technique child was not bound");
            }
        } catch (SubmissionRejectedException exception) {
            cancelBindingIfPresent(pending.binding,
                    ActionCancellationReason.LIFECYCLE);
            throw exception;
        } catch (RuntimeException exception) {
            cancelBindingIfPresent(pending.binding,
                    ActionCancellationReason.LIFECYCLE);
            throw new SubmissionRejectedException(
                    "Self-defense technique start failed", exception);
        } finally {
            pendingStart = null;
        }
        return reply.view();
    }

    @Override
    PlayerTechnique technique() {
        return technique;
    }

    /**
     * Offers completed Action outcomes after the Action runtime has advanced.
     * The exact signal is delivered before the original self-defense stage is
     * released, so self-defense cannot issue a second attack while the first
     * technique has not observed its child terminal state.
     */
    @Override
    void drainCompletedChildren(long currentTick,
            TechniqueLifecycleCoordinator.SignalSink signals) {
        requireOwnerThread();
        TechniqueLifecycleCoordinator.SignalSink requiredSignals =
                Objects.requireNonNull(signals, "signals");
        for (ActionBinding binding : List.copyOf(
                actionBindingsByActionId.values())) {
            ActionOutcome outcome;
            try {
                outcome = actions.completedOutcome(binding.claim().botId(),
                        binding.claim().actionId()).orElse(null);
            } catch (RuntimeException exception) {
                settleInvalidCompletion(binding, currentTick,
                        "Self-defense action outcome lookup failed",
                        requiredSignals);
                continue;
            }
            if (outcome == null) {
                continue;
            }
            if (!binding.claim().actionId().equals(outcome.actionId())
                    || !outcome.state().isTerminal()) {
                settleInvalidCompletion(binding, currentTick,
                        "Self-defense action outcome identity was invalid",
                        requiredSignals);
                continue;
            }
            TechniqueSignalStatus signalStatus;
            try {
                signalStatus = requiredSignals.offer(this,
                        signalFor(binding, outcome), currentTick);
            } catch (RuntimeException exception) {
                signalStatus = TechniqueSignalStatus.RUN_NOT_FOUND;
            }
            Retraction retraction = retractionsByActionId.get(
                    binding.claim().actionId());
            if (cachedCancellationReceipt(binding.envelope()).isEmpty()) {
                rememberCancellationReceipt(binding.envelope(),
                        ActionCancellationReceipt.unsafe(
                                binding.envelope().botId(),
                                binding.envelope().botGeneration(),
                                binding.envelope().actionId(),
                                ActionCancellationReceipt.Disposition.TERMINAL));
            }
            removeActionBinding(binding);
            if (signalStatus != TechniqueSignalStatus.ACCEPTED) {
                coordinator.closeGeneration(binding.run().botId(),
                        binding.run().botGeneration(), currentTick);
            }
            if (cancellationContainmentUnsafe) {
                binding.reply().complete(rejectedOutcome(binding.claim(),
                        currentTick, ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "Self-defense cancellation could not prove physical containment"));
            } else if (signalStatus == TechniqueSignalStatus.ACCEPTED
                    || retraction != null) {
                binding.reply().complete(retraction == null
                        ? outcome
                        : retractedOutcome(binding.claim(), currentTick,
                                retraction));
            } else {
                binding.reply().complete(rejectedOutcome(binding.claim(),
                        currentTick, ActionFailureCode.INTERNAL_ERROR,
                        "Self-defense action outcome could not be acknowledged"));
            }
        }
    }

    /**
     * Called only after the limited self-defense session truly accepted an L0
     * preemption. It never infers a preemption from an ordinary safety tick.
     */
    public boolean preemptForSafety(UUID botId, long botGeneration,
            long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        RunBinding binding = runsByBotId.get(Objects.requireNonNull(botId,
                "botId"));
        if (binding == null || binding.botGeneration() != botGeneration) {
            return false;
        }
        TechniqueRunView view = coordinator.inspectRun(
                binding.techniqueRunId()).orElse(null);
        if (view == null) {
            removeRun(binding);
            return false;
        }
        requireExactRunView(binding, view);
        /*
         * SelfDefense closes its local session (and first requests an ordinary
         * action cancellation) before this bridge observes the accepted L0
         * preemption. Upgrade authority now, so a late physical SUCCESS can
         * never become a successful self-defense receipt.
         */
        markRetractionForRun(binding, Retraction.SAFETY);
        TechniqueCancellationStatus status = coordinator.preemptForSafety(this,
                binding.techniqueRunId(), binding.botId(),
                binding.botGeneration(), currentTick);
        return status == TechniqueCancellationStatus.PREEMPTING;
    }

    /**
     * Stops a still-bound self-defense child without accepting arbitrary action
     * IDs. Normal session shutdowns use this path; L0 and generation closures
     * additionally call the stronger runtime transition above.
     */
    public ActionCancellationReceipt cancelDispatch(
            AuthorizedActionDispatch authorization) {
        requireOwnerThread();
        AuthorizedActionDispatch required = Objects.requireNonNull(
                authorization, "authorization");
        ActionCancellationReason reason = required.revocation()
                .map(SelfDefenseTechniqueBridge::cancellationFor)
                .orElse(ActionCancellationReason.REQUESTED);
        ActionBinding binding = actionBindingsByActionId.get(
                required.actionId());
        if (binding != null && binding.authorization() == required) {
            return cancelBinding(binding, reason);
        }
        PendingStart pending = pendingStart;
        if (pending != null && pending.authorization == required
                && pending.binding != null) {
            return cancelBinding(pending.binding, reason);
        }
        return cachedCancellationReceipt(required.botId(),
                required.botGeneration(), required.actionId()).orElseGet(
                        () -> ActionCancellationReceipt.unsafe(
                                required.botId(), required.botGeneration(),
                                required.actionId(),
                                ActionCancellationReceipt.Disposition.UNKNOWN));
    }

    @Override
    void onGenerationClosing(UUID botId, long botGeneration,
            long currentTick) {
        requireOwnerThread();
        UUID requiredBotId = Objects.requireNonNull(botId, "botId");
        for (ActionBinding binding : List.copyOf(
                actionBindingsByActionId.values())) {
            if (binding.run().botId().equals(requiredBotId)
                    && binding.run().botGeneration() == botGeneration
                    && coordinator.inspectRun(binding.run().techniqueRunId())
                            .isEmpty()) {
                cancelBinding(binding, ActionCancellationReason.LIFECYCLE);
            }
        }
        PendingStart pending = pendingStart;
        if (pending != null && pending.binding != null
                && pending.binding.run().botId().equals(requiredBotId)
                && pending.binding.run().botGeneration() == botGeneration
                && coordinator.inspectRun(pending.binding.run().techniqueRunId())
                        .isEmpty()) {
            cancelBinding(pending.binding, ActionCancellationReason.LIFECYCLE);
        }
    }

    @Override
    void closeIngress() {
        requireOwnerThread();
        closed = true;
    }

    @Override
    boolean isRouteGenerationSafe(UUID botId, long botGeneration) {
        requireOwnerThread();
        UUID requiredBotId = Objects.requireNonNull(botId, "botId");
        if (cancellationContainmentUnsafe) {
            return false;
        }
        boolean hasPendingBinding = pendingStart != null
                && pendingStart.binding != null
                && pendingStart.binding.run().botId().equals(requiredBotId)
                && pendingStart.binding.run().botGeneration() == botGeneration;
        return !hasPendingBinding
                && actionBindingsByActionId.values().stream().noneMatch(binding ->
                        binding.run().botId().equals(requiredBotId)
                                && binding.run().botGeneration() == botGeneration)
                && runsByTechniqueId.values().stream().noneMatch(binding ->
                        binding.botId().equals(requiredBotId)
                                && binding.botGeneration() == botGeneration);
    }

    @Override
    void verifyRun(TechniqueRunView view) {
        requireOwnerThread();
        TechniqueRunView required = Objects.requireNonNull(view, "run");
        RunBinding binding = runsByTechniqueId.get(required.techniqueRunId());
        if (binding == null) {
            throw new IllegalStateException(
                    "self-defense technique route lost its run binding");
        }
        requireExactRunView(binding, required);
    }

    @Override
    TechniqueChildDispatcher.Submission submitChild(TechniqueChildTicket ticket,
            TechniqueRunView view) {
        requireOwnerThread();
        TechniqueChildTicket required = Objects.requireNonNull(ticket,
                "ticket");
        TechniqueRunView requiredView = Objects.requireNonNull(view, "run");
        PendingStart pending = pendingStart;
        if (pending == null || !isExactChildTicket(pending, required)) {
            if (pending != null) {
                pending.failure = rejectedOutcome(pending.claim,
                        pending.currentTick, ActionFailureCode.INTERNAL_ERROR,
                        "Self-defense technique child identity was invalid");
            }
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Self-defense technique child identity was rejected");
        }
        if (!isExactStartingRunView(pending, required, requiredView)) {
            pending.failure = rejectedOutcome(pending.claim,
                    pending.currentTick, ActionFailureCode.INTERNAL_ERROR,
                    "Self-defense technique runtime binding was invalid");
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Self-defense technique runtime binding was rejected");
        }
        if (actionBindingsByActionId.containsKey(pending.claim.actionId())
                || actionBindingsByTicketId.containsKey(required.ticketId())
                || runsByTechniqueId.containsKey(required.techniqueRunId())
                || runsByBotId.containsKey(required.botId())) {
            pending.failure = rejectedOutcome(pending.claim,
                    pending.currentTick, ActionFailureCode.CHANNEL_BUSY,
                    "Self-defense technique binding was already occupied");
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.CHANNEL_BUSY,
                    "Self-defense technique binding is occupied");
        }

        /* Freeze every child identity before external Action ingress. */
        RunBinding run;
        ActionBinding binding;
        try {
            run = new RunBinding(required.techniqueRunId(),
                    required.botId(), required.botGeneration(),
                    pending.claim.selfDefenseRunId(),
                    attackTarget(pending.claim), required.ticketId(),
                    required.revision(), required.submittedTick());
            binding = new ActionBinding(pending.authorization, pending.claim,
                    required, run, pending.reply, envelopeFor(pending.claim));
        } catch (RuntimeException exception) {
            pending.failure = rejectedOutcome(pending.claim,
                    pending.currentTick, ActionFailureCode.INTERNAL_ERROR,
                    "Self-defense melee binding could not be frozen");
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Self-defense melee binding could not be frozen");
        }

        /* Make the frozen exact child visible to a re-entrant cancellation. */
        pending.binding = binding;
        ActionMailbox.Submission actionSubmission;
        try {
            actionSubmission = Objects.requireNonNull(
                    actions.submit(binding.envelope(), ActionPriority.EMERGENCY),
                    "self-defense action submission");
        } catch (RuntimeException exception) {
            pending.failure = rejectedOutcome(pending.claim,
                    pending.currentTick, ActionFailureCode.INTERNAL_ERROR,
                    "Self-defense action submission failed");
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Self-defense action submission failed");
        }
        if (actionSubmission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            rememberCancellationReceipt(binding.envelope(),
                    ActionCancellationReceipt.fencedBeforeStart(
                            binding.envelope().botId(),
                            binding.envelope().botGeneration(),
                            binding.envelope().actionId()));
            pending.failure = rejectedOutcome(pending.claim,
                    pending.currentTick,
                    failureForActionSubmission(actionSubmission.status()),
                    "Self-defense action submission was rejected");
            return TechniqueChildDispatcher.Submission.rejected(
                    statusForActionSubmission(
                    actionSubmission.status()),
                    "Self-defense action submission was rejected");
        }

        /*
         * The gateway can synchronously invoke safety/lifecycle code. It may
         * revoke this exact authorization after accepting the envelope but
         * before control returns here. Cancel the exact newly-enqueued action
         * before it can become a valid child or a successful caller receipt.
         */
        if (!pending.claim.isAuthorityCurrent()) {
            AuthorizationRevocation revocation = pending.claim.revocation()
                    .orElse(AuthorizationRevocation.TERMINAL);
            ActionCancellationReceipt cancellation = cancelOrContain(
                    binding.envelope(), cancellationFor(revocation),
                    pending.currentTick);
            pending.failure = cancellation.safelyRetracted()
                    ? retractedOutcome(pending.claim, pending.currentTick,
                            retractionFor(revocation))
                    : rejectedOutcome(pending.claim, pending.currentTick,
                            ActionFailureCode.UNSAFE_CONTROL_STATE,
                            "Self-defense cancellation could not prove physical containment");
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Self-defense action authorization was revoked during submission");
        }

        actionBindingsByActionId.put(pending.claim.actionId(), binding);
        actionBindingsByTicketId.put(required.ticketId(), binding);
        runsByTechniqueId.put(run.techniqueRunId(), run);
        runsByBotId.put(run.botId(), run);
        return TechniqueChildDispatcher.Submission.accepted(
                "Self-defense melee child accepted");
    }

    @Override
    void cancelChild(TechniqueChildTicket ticket,
            TechniqueCancelReason reason) {
        requireOwnerThread();
        TechniqueChildTicket required = Objects.requireNonNull(ticket,
                "ticket");
        ActionBinding binding = actionBindingsByTicketId.get(
                required.ticketId());
        if (binding != null && binding.ticket().equals(required)) {
            cancelBinding(binding, actionCancellationReason(
                    Objects.requireNonNull(reason, "reason")));
            return;
        }
        PendingStart pending = pendingStart;
        if (pending != null && pending.binding != null
                && pending.binding.ticket().equals(required)) {
            cancelBinding(pending.binding, actionCancellationReason(
                    Objects.requireNonNull(reason, "reason")));
        }
    }

    private void settleInvalidCompletion(ActionBinding binding,
            long currentTick, String summary,
            TechniqueLifecycleCoordinator.SignalSink signals) {
        TechniqueSignalStatus status;
        try {
            status = signals.offer(this, new TechniqueSignal(
                    binding.run().techniqueRunId(), binding.ticket().ticketId(),
                    binding.run().botId(), binding.run().botGeneration(),
                    binding.ticket().revision(), TechniqueChildState.FAILED,
                    TechniqueFailureCode.INTERNAL_ERROR, summary), currentTick);
        } catch (RuntimeException exception) {
            status = TechniqueSignalStatus.RUN_NOT_FOUND;
        }
        cancelBinding(binding, ActionCancellationReason.LIFECYCLE);
        removeActionBinding(binding);
        if (status != TechniqueSignalStatus.ACCEPTED) {
            coordinator.closeGeneration(binding.run().botId(),
                    binding.run().botGeneration(), currentTick);
        }
        binding.reply().complete(rejectedOutcome(binding.claim(), currentTick,
                ActionFailureCode.INTERNAL_ERROR, summary));
    }

    private void cancelBindingIfPresent(ActionBinding binding,
            ActionCancellationReason reason) {
        if (binding != null) {
            cancelBinding(binding, reason);
        }
    }

    private ActionCancellationReceipt cancelBinding(ActionBinding binding,
            ActionCancellationReason reason) {
        markRetraction(binding, reason);
        if (!shouldDispatchCancellation(binding, reason)) {
            return cachedCancellationReceipt(binding.envelope()).orElseGet(
                    () -> unsafeUnknownReceipt(binding.envelope()));
        }
        ActionCancellationReceipt cancellation = cancelOrContain(binding.envelope(),
                reason, lastObservedTick);
        if (cancellation.safelyRetracted()) {
            dispatchedCancellationsByActionId.merge(
                    binding.claim().actionId(), reason,
                    SelfDefenseTechniqueBridge::strongerCancellationReason);
        } else {
            cancellationContainmentUnsafe = true;
            binding.reply().complete(rejectedOutcome(binding.claim(),
                    lastObservedTick, ActionFailureCode.UNSAFE_CONTROL_STATE,
                    "Self-defense cancellation could not prove physical containment"));
        }
        return cancellation;
    }

    private ActionCancellationReceipt cancelOrContain(ActionEnvelope envelope,
            ActionCancellationReason reason, long currentTick) {
        ActionEnvelope required = Objects.requireNonNull(envelope, "envelope");
        Optional<ActionCancellationReceipt> cached = cachedCancellationReceipt(
                required);
        if (cached.isPresent()) {
            return cached.orElseThrow();
        }
        ActionCancellationReceipt receipt;
        try {
            receipt = Objects.requireNonNull(
                    actions.cancelOrContain(envelope,
                            Objects.requireNonNull(reason, "reason"),
                            currentTick),
                    "self-defense cancellation result");
        } catch (RuntimeException exception) {
            receipt = unsafeUnknownReceipt(required);
        }
        if (!receipt.matches(required.botId(), required.botGeneration(),
                required.actionId())) {
            receipt = unsafeUnknownReceipt(required);
        }
        rememberCancellationReceipt(required, receipt);
        if (!receipt.safelyRetracted()) {
            cancellationContainmentUnsafe = true;
        }
        return receipt;
    }

    private void removeActionBinding(ActionBinding binding) {
        actionBindingsByActionId.remove(binding.claim().actionId(), binding);
        actionBindingsByTicketId.remove(binding.ticket().ticketId(), binding);
        retractionsByActionId.remove(binding.claim().actionId());
        dispatchedCancellationsByActionId.remove(binding.claim().actionId());
    }

    private Optional<ActionCancellationReceipt> cachedCancellationReceipt(
            ActionEnvelope envelope) {
        return cachedCancellationReceipt(envelope.botId(),
                envelope.botGeneration(), envelope.actionId());
    }

    private Optional<ActionCancellationReceipt> cachedCancellationReceipt(
            UUID botId, long botGeneration, UUID actionId) {
        return Optional.ofNullable(cancellationReceipts.get(
                new ActionCancellationReceipt.Identity(botId, botGeneration,
                        actionId)));
    }

    private void rememberCancellationReceipt(ActionEnvelope envelope,
            ActionCancellationReceipt receipt) {
        ActionCancellationReceipt.Identity identity =
                new ActionCancellationReceipt.Identity(envelope.botId(),
                        envelope.botGeneration(), envelope.actionId());
        cancellationReceipts.put(identity, Objects.requireNonNull(receipt,
                "receipt"));
        while (cancellationReceipts.size()
                > MAX_RETAINED_CANCELLATION_RECEIPTS) {
            ActionCancellationReceipt.Identity oldest = cancellationReceipts
                    .keySet().iterator().next();
            cancellationReceipts.remove(oldest);
        }
    }

    private static ActionCancellationReceipt unsafeUnknownReceipt(
            ActionEnvelope envelope) {
        return ActionCancellationReceipt.unsafe(envelope.botId(),
                envelope.botGeneration(), envelope.actionId(),
                ActionCancellationReceipt.Disposition.UNKNOWN);
    }

    private void removeRun(RunBinding binding) {
        runsByTechniqueId.remove(binding.techniqueRunId(), binding);
        runsByBotId.remove(binding.botId(), binding);
    }

    @Override
    void reapTerminalRun(UUID techniqueRunId) {
        RunBinding binding = runsByTechniqueId.get(
                Objects.requireNonNull(techniqueRunId, "techniqueRunId"));
        if (binding != null) {
            removeRun(binding);
        }
    }

    private static ActionEnvelope envelopeFor(
            ClaimedActionDispatch dispatch) {
        return new ActionEnvelope(dispatch.actionId(), dispatch.botId(),
                dispatch.botGeneration(), dispatch.idempotencyKey(),
                dispatch.deadlineTick(), dispatch.maximumTicks(),
                dispatch.action(), ActionOrigin.fromController(
                        ControllerKind.SAFETY, dispatch.selfDefenseRunId()));
    }

    private static boolean isEligibleDispatch(
            ClaimedActionDispatch dispatch) {
        if (dispatch.defenseAction().kind()
                != DefenseActionKind.MELEE_ATTACK
                || dispatch.defenseAction().safeRetreat().isPresent()
                || !(dispatch.action() instanceof WorldInteractionAction world)
                || !(world.spec() instanceof WorldInteractionActionSpec.AttackEntity
                        attack)) {
            return false;
        }
        return attack.target().entityId().equals(
                dispatch.defenseAction().targetId())
                && SingleMeleeStrikeTechnique.CHANNELS.equals(
                        dispatch.action().channels())
                && SingleMeleeStrikeTechnique.CHANNELS.equals(
                        world.channels());
    }

    private static EntityTargetFingerprint attackTarget(
            ClaimedActionDispatch dispatch) {
        if (!isEligibleDispatch(dispatch)) {
            throw new IllegalArgumentException(
                    "self-defense dispatch is not an exact melee attack");
        }
        WorldInteractionAction action =
                (WorldInteractionAction) dispatch.action();
        WorldInteractionActionSpec.AttackEntity attack =
                (WorldInteractionActionSpec.AttackEntity) action.spec();
        return attack.target();
    }

    private static boolean isExactChildTicket(PendingStart pending,
            TechniqueChildTicket ticket) {
        return ticket.botId().equals(pending.claim.botId())
                && ticket.botGeneration() == pending.claim.botGeneration()
                && ticket.submittedTick() == pending.currentTick
                && ticket.state() == TechniqueChildState.ACTIVE
                && SingleMeleeStrikeTechnique.OPERATION_KEY.equals(
                        ticket.operationKey())
                && SingleMeleeStrikeTechnique.CHANNELS.equals(
                        ticket.channels());
    }

    private static boolean isExactStartingRunView(PendingStart pending,
            TechniqueChildTicket ticket, TechniqueRunView view) {
        return view.techniqueRunId().equals(ticket.techniqueRunId())
                && view.skillRunId().equals(pending.claim.selfDefenseRunId())
                && view.botId().equals(pending.claim.botId())
                && view.botGeneration() == pending.claim.botGeneration()
                && view.techniqueId().equals(SingleMeleeStrikeTechnique.ID)
                && view.techniqueVersion().equals(
                        SingleMeleeStrikeTechnique.VERSION)
                && view.revision() == ticket.revision()
                && view.childTickets().size() == 1
                && view.childTickets().get(0).equals(ticket);
    }

    private static TechniqueSignal signalFor(ActionBinding binding,
            ActionOutcome outcome) {
        TechniqueChildState state = switch (outcome.state()) {
            case SUCCEEDED -> TechniqueChildState.SUCCEEDED;
            case FAILED -> TechniqueChildState.FAILED;
            case CANCELLED -> TechniqueChildState.CANCELLED;
            case PREEMPTED -> TechniqueChildState.PREEMPTED;
            case STALE -> TechniqueChildState.STALE;
            case QUEUED, VALIDATING, RUNNING, VERIFYING -> throw new IllegalArgumentException(
                    "Self-defense child requires a terminal Action outcome");
        };
        return new TechniqueSignal(binding.run().techniqueRunId(),
                binding.ticket().ticketId(), binding.run().botId(),
                binding.run().botGeneration(), binding.ticket().revision(),
                state, failureForActionOutcome(outcome),
                nonEmptySummary(outcome.safeSummary(),
                        "Self-defense action completed"));
    }

    private static TechniqueFailureCode failureForActionOutcome(
            ActionOutcome outcome) {
        return switch (outcome.state()) {
            case SUCCEEDED -> TechniqueFailureCode.NONE;
            case CANCELLED -> TechniqueFailureCode.CANCELLED;
            case PREEMPTED -> TechniqueFailureCode.PREEMPTED;
            case STALE -> TechniqueFailureCode.GENERATION_CHANGED;
            case FAILED -> switch (outcome.failureCode()) {
                case TARGET_UNAVAILABLE -> TechniqueFailureCode.TARGET_GONE;
                case STALE_GENERATION -> TechniqueFailureCode.GENERATION_CHANGED;
                case DEADLINE_EXCEEDED, MAX_TICKS_EXCEEDED ->
                        TechniqueFailureCode.TIMEOUT;
                case CHANNEL_BUSY -> TechniqueFailureCode.CHANNEL_CONFLICT;
                case PERMISSION_DENIED -> TechniqueFailureCode.PERMISSION_DENIED;
                case UNSUPPORTED -> TechniqueFailureCode.UNSUPPORTED;
                case PRECONDITION_FAILED -> TechniqueFailureCode.TARGET_CHANGED;
                case INVALID_REQUEST -> TechniqueFailureCode.INVALID_REQUEST;
                case BOT_NOT_ACTIVE, DUPLICATE_IN_PROGRESS,
                        IDEMPOTENCY_CONFLICT, LEDGER_CAPACITY_EXCEEDED,
                        ACTION_ALIAS_CAPACITY_EXCEEDED,
                        RUNTIME_CAPACITY_EXCEEDED, BACKEND_RESULT_MISMATCH,
                        UNSAFE_CONTROL_STATE, INTERNAL_ERROR ->
                        TechniqueFailureCode.ACTION_FAILED;
                case NONE, CANCELLED, PREEMPTED -> throw new IllegalArgumentException(
                        "incoherent failed Action outcome");
            };
            case QUEUED, VALIDATING, RUNNING, VERIFYING -> throw new IllegalArgumentException(
                    "Self-defense child requires a terminal Action outcome");
        };
    }

    private static ActionFailureCode failureForActionSubmission(
            ActionMailbox.SubmissionStatus status) {
        return switch (status) {
            case MAILBOX_FULL, COMPLETION_BACKPRESSURE ->
                    ActionFailureCode.RUNTIME_CAPACITY_EXCEEDED;
            case BOT_GENERATION_CLOSED -> ActionFailureCode.STALE_GENERATION;
            case RUNTIME_CLOSED -> ActionFailureCode.INTERNAL_ERROR;
            case ENQUEUED -> throw new IllegalArgumentException(
                    "enqueued Action submission cannot be rejected");
        };
    }

    private static TechniqueChildDispatcher.Status statusForActionSubmission(
            ActionMailbox.SubmissionStatus status) {
        return switch (status) {
            case MAILBOX_FULL, COMPLETION_BACKPRESSURE ->
                    TechniqueChildDispatcher.Status.CAPACITY_EXCEEDED;
            case BOT_GENERATION_CLOSED, RUNTIME_CLOSED ->
                    TechniqueChildDispatcher.Status.REJECTED;
            case ENQUEUED -> throw new IllegalArgumentException(
                    "enqueued Action submission cannot be rejected");
        };
    }

    private static ActionCancellationReason actionCancellationReason(
            TechniqueCancelReason reason) {
        return switch (reason) {
            case REQUESTED, SAFETY_PREEMPTION ->
                    ActionCancellationReason.REQUESTED;
            case GENERATION_CHANGED -> ActionCancellationReason.LIFECYCLE;
            case SERVER_STOP -> ActionCancellationReason.RUNTIME_SHUTDOWN;
        };
    }

    private static ActionCancellationReason cancellationFor(
            AuthorizationRevocation revocation) {
        return switch (Objects.requireNonNull(revocation, "revocation")) {
            case SERVER_STOP -> ActionCancellationReason.RUNTIME_SHUTDOWN;
            case GENERATION_CLOSED, TERMINAL ->
                    ActionCancellationReason.LIFECYCLE;
            case COMPLETED, CANCELLED, SUBMISSION_REJECTED,
                    SAFETY_PREEMPTION -> ActionCancellationReason.REQUESTED;
        };
    }

    private static Retraction retractionFor(
            AuthorizationRevocation revocation) {
        return switch (Objects.requireNonNull(revocation, "revocation")) {
            case SAFETY_PREEMPTION -> Retraction.SAFETY;
            case GENERATION_CLOSED, TERMINAL -> Retraction.LIFECYCLE;
            case SERVER_STOP -> Retraction.SERVER_STOP;
            case COMPLETED, CANCELLED, SUBMISSION_REJECTED ->
                    Retraction.REQUESTED;
        };
    }

    /**
     * A completed physical action must never revive a bridge caller after the
     * lifecycle has retracted its authority. The highest authority wins; this
     * state is intentionally separate from the immutable ticket/action
     * binding so no target or generation data can be rewritten mid-flight.
     */
    private void markRetraction(ActionBinding binding,
            ActionCancellationReason reason) {
        Retraction candidate = Retraction.from(
                Objects.requireNonNull(reason, "reason"));
        retractionsByActionId.merge(binding.claim().actionId(), candidate,
                Retraction::stronger);
    }

    private void markRetractionForRun(RunBinding run,
            Retraction retraction) {
        for (ActionBinding binding : actionBindingsByActionId.values()) {
            if (binding.run().equals(run)) {
                retractionsByActionId.merge(binding.claim().actionId(),
                        Objects.requireNonNull(retraction, "retraction"),
                        Retraction::stronger);
            }
        }
    }

    private boolean shouldDispatchCancellation(ActionBinding binding,
            ActionCancellationReason candidate) {
        ActionCancellationReason existing =
                dispatchedCancellationsByActionId.get(
                        binding.claim().actionId());
        return existing == null || cancellationPriority(candidate)
                > cancellationPriority(existing);
    }

    private static ActionCancellationReason strongerCancellationReason(
            ActionCancellationReason first, ActionCancellationReason second) {
        return cancellationPriority(first) >= cancellationPriority(second)
                ? first : second;
    }

    private static int cancellationPriority(ActionCancellationReason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case REQUESTED -> 1;
            case LIFECYCLE -> 2;
            case RUNTIME_SHUTDOWN -> 3;
        };
    }

    private static ActionOutcome rejectedOutcome(
            ClaimedActionDispatch dispatch, long currentTick,
            ActionFailureCode failureCode, String summary) {
        return rejectedOutcome(dispatch.actionId(), currentTick, failureCode,
                summary);
    }

    private static ActionOutcome rejectedOutcome(
            UUID actionId, long currentTick, ActionFailureCode failureCode,
            String summary) {
        return new ActionOutcome(actionId, ActionState.FAILED,
                Objects.requireNonNull(failureCode, "failureCode"),
                currentTick, currentTick, List.of(), nonEmptySummary(summary,
                        "Self-defense technique rejected the action"));
    }

    private static ActionOutcome retractedOutcome(
            ClaimedActionDispatch dispatch, long currentTick,
            Retraction retraction) {
        return new ActionOutcome(dispatch.actionId(), retraction.actionState(),
                retraction.failureCode(), currentTick, currentTick, List.of(),
                retraction.safeSummary());
    }

    private static String nonEmptySummary(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private void requireExactRunView(RunBinding binding,
            TechniqueRunView view) {
        if (!view.techniqueRunId().equals(binding.techniqueRunId())
                || !view.botId().equals(binding.botId())
                || view.botGeneration() != binding.botGeneration()
                || !view.skillRunId().equals(binding.selfDefenseRunId())
                || !view.techniqueId().equals(SingleMeleeStrikeTechnique.ID)
                || !view.techniqueVersion().equals(
                        SingleMeleeStrikeTechnique.VERSION)
                || view.childTickets().size() != 1
                || !isExactBoundChild(binding, view.childTickets().get(0))) {
            throw new IllegalStateException(
                    "self-defense technique runtime identity changed");
        }
    }

    private static boolean isExactBoundChild(RunBinding binding,
            TechniqueChildTicket ticket) {
        return ticket.ticketId().equals(binding.childTicketId())
                && ticket.techniqueRunId().equals(binding.techniqueRunId())
                && ticket.botId().equals(binding.botId())
                && ticket.botGeneration() == binding.botGeneration()
                && ticket.revision() == binding.childTicketRevision()
                && ticket.submittedTick() == binding.childSubmittedTick()
                && SingleMeleeStrikeTechnique.OPERATION_KEY.equals(
                        ticket.operationKey())
                && SingleMeleeStrikeTechnique.CHANNELS.equals(
                        ticket.channels());
    }

    private long observeSuppliedTick() {
        final long supplied;
        try {
            supplied = currentTickSupplier.getAsLong();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "self-defense bridge tick source failed", exception);
        }
        observeTick(supplied);
        return supplied;
    }

    @Override
    void observeTick(long currentTick) {
        if (currentTick < 0L || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "self-defense bridge tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "self-defense technique bridge requires the owner server thread");
        }
    }

    /** Minimal lifecycle port; all operations are still invoked on its owner thread. */
    public interface ActionGateway {
        ActionMailbox.Submission submit(ActionEnvelope envelope,
                ActionPriority priority);

        /** Returns an exact target receipt; generation quarantine is never proof. */
        ActionCancellationReceipt cancelOrContain(ActionEnvelope envelope,
                ActionCancellationReason reason, long currentTick);

        Optional<ActionOutcome> completedOutcome(UUID botId, UUID actionId);
    }

    private static final class Reply {
        private final CompletableFuture<ActionOutcome> source =
                new CompletableFuture<>();

        private CompletionStage<ActionOutcome> view() {
            return source.minimalCompletionStage();
        }

        private void complete(ActionOutcome outcome) {
            source.complete(Objects.requireNonNull(outcome, "outcome"));
        }
    }

    /** Signals a synchronous bridge/gateway rejection back to the service. */
    private static final class SubmissionRejectedException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private SubmissionRejectedException(String message) {
            super(message);
        }

        private SubmissionRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class PendingStart {
        private final AuthorizedActionDispatch authorization;
        private final ClaimedActionDispatch claim;
        private final Reply reply;
        private final long currentTick;
        private ActionBinding binding;
        private ActionOutcome failure;

        private PendingStart(AuthorizedActionDispatch authorization,
                ClaimedActionDispatch claim, Reply reply, long currentTick) {
            this.authorization = Objects.requireNonNull(authorization,
                    "authorization");
            this.claim = Objects.requireNonNull(claim, "claim");
            if (!this.claim.isClaimOf(this.authorization)) {
                throw new IllegalArgumentException(
                        "self-defense claim did not belong to authorization");
            }
            this.reply = Objects.requireNonNull(reply, "reply");
            this.currentTick = currentTick;
        }
    }

    private record RunBinding(UUID techniqueRunId, UUID botId,
            long botGeneration, UUID selfDefenseRunId,
            EntityTargetFingerprint target, UUID childTicketId,
            long childTicketRevision, long childSubmittedTick) {
        private RunBinding {
            Objects.requireNonNull(techniqueRunId, "techniqueRunId");
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(selfDefenseRunId, "selfDefenseRunId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(childTicketId, "childTicketId");
            if (botGeneration < 1L) {
                throw new IllegalArgumentException(
                        "self-defense technique generation must be positive");
            }
            if (childTicketRevision < 1L || childSubmittedTick < 0L) {
                throw new IllegalArgumentException(
                        "self-defense child ticket identity is invalid");
            }
        }
    }

    private record ActionBinding(
            AuthorizedActionDispatch authorization,
            ClaimedActionDispatch claim,
            TechniqueChildTicket ticket,
            RunBinding run,
            Reply reply,
            ActionEnvelope envelope) {
        private ActionBinding {
            Objects.requireNonNull(authorization, "authorization");
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(reply, "reply");
            Objects.requireNonNull(envelope, "envelope");
            if (!claim.isClaimOf(authorization)
                    || !isEligibleDispatch(claim)
                    || !attackTarget(claim).equals(run.target())
                    || !claim.defenseAction().targetId().equals(
                            run.target().entityId())
                    || !ticket.botId().equals(run.botId())
                    || ticket.botGeneration() != run.botGeneration()
                    || !ticket.techniqueRunId().equals(
                            run.techniqueRunId())
                    || !ticket.ticketId().equals(run.childTicketId())
                    || ticket.revision() != run.childTicketRevision()
                    || ticket.submittedTick() != run.childSubmittedTick()
                    || !envelope.actionId().equals(claim.actionId())
                    || !envelope.botId().equals(claim.botId())
                    || envelope.botGeneration() != claim.botGeneration()
                    || !envelope.idempotencyKey().equals(
                            claim.idempotencyKey())
                    || envelope.deadlineTick() != claim.deadlineTick()
                    || envelope.maxTicks() != claim.maximumTicks()
                    || envelope.action() != claim.action()
                    || !envelope.origin().equals(ActionOrigin.fromController(
                            ControllerKind.SAFETY,
                            claim.selfDefenseRunId()))) {
                throw new IllegalArgumentException(
                        "self-defense action binding identity is invalid");
            }
        }
    }

    private enum Retraction {
        REQUESTED(1, ActionState.CANCELLED, ActionFailureCode.CANCELLED,
                "Self-defense action was cancelled"),
        SAFETY(2, ActionState.PREEMPTED, ActionFailureCode.PREEMPTED,
                "Self-defense action was preempted by safety"),
        LIFECYCLE(3, ActionState.CANCELLED, ActionFailureCode.CANCELLED,
                "Self-defense action was cancelled by lifecycle"),
        SERVER_STOP(4, ActionState.CANCELLED, ActionFailureCode.CANCELLED,
                "Self-defense action was cancelled during server stop");

        private final int priority;
        private final ActionState actionState;
        private final ActionFailureCode failureCode;
        private final String safeSummary;

        Retraction(int priority, ActionState actionState,
                ActionFailureCode failureCode, String safeSummary) {
            this.priority = priority;
            this.actionState = actionState;
            this.failureCode = failureCode;
            this.safeSummary = safeSummary;
        }

        private static Retraction from(ActionCancellationReason reason) {
            return switch (reason) {
                case REQUESTED -> REQUESTED;
                case LIFECYCLE -> LIFECYCLE;
                case RUNTIME_SHUTDOWN -> SERVER_STOP;
            };
        }

        private static Retraction stronger(Retraction first,
                Retraction second) {
            return first.priority >= second.priority ? first : second;
        }

        private ActionState actionState() {
            return actionState;
        }

        private ActionFailureCode failureCode() {
            return failureCode;
        }

        private String safeSummary() {
            return safeSummary;
        }
    }
}
