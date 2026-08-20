package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle-owned adapter from an opaque Technique child permit to P2 Action.
 *
 * <p>This class is deliberately package-private and is not a route: only a
 * reviewed route in this package can bind it to the coordinator which issued
 * its permit. It never exposes an Action future. Terminal evidence is read
 * only by exact immutable envelope at the coordinator's owner-thread drain
 * boundary; the route remains responsible for converting that evidence to its
 * precise child signal and then calling {@link #release}.
 *
 * <p>The gateway may synchronously re-enter lifecycle code only after it has
 * accepted the exact envelope. The binding is installed before ingress, so an
 * exact route cancellation can contain it during that callback. A gateway
 * which re-enters before it can prove ingress has not occurred receives an
 * unsafe receipt and the generation stays fail-closed rather than being
 * falsely reported as retracted.
 */
final class LifecycleTechniqueActionPort extends TechniqueActionPort {
    private static final int MAX_RETAINED_REJECTED_INGRESS_RECEIPTS = 256;

    private final TechniqueActionRuntimeGateway actionRuntime;
    private final Map<TechniqueActionPermit, Binding> bindings =
            new IdentityHashMap<>();
    /*
     * Opaque permit identity, not actionId, is the exact cancellation cache
     * key. A P2 receipt lives only with its live binding: historical receipts
     * must never evict a still-live safe containment proof. Rejected ingress
     * has a separate bounded local fence because it never reached P2.
     */
    private final Map<TechniqueActionPermit, ActionCancellationReceipt>
            cancellationReceipts = new IdentityHashMap<>();
    /* Rejected ingress never reached P2, so this bounded local fallback is safe. */
    private final Map<TechniqueActionPermit, ActionCancellationReceipt>
            rejectedIngressReceipts = new IdentityHashMap<>();
    private final Set<GenerationKey> unsafeGenerations = new HashSet<>();

    LifecycleTechniqueActionPort(TechniqueLifecycleCoordinator coordinator,
            TechniqueRoute route, BotActionRuntime runtime) {
        this(coordinator, route, TechniqueActionRuntimeGateway.from(runtime));
    }

    /** Package-private test seam; it is never handed to a Technique route. */
    LifecycleTechniqueActionPort(TechniqueLifecycleCoordinator coordinator,
            TechniqueRoute route, TechniqueActionRuntimeGateway actionRuntime) {
        super(coordinator, route);
        this.actionRuntime = Objects.requireNonNull(actionRuntime,
                "actionRuntime");
    }

    @Override
    protected TechniqueActionSubmission submitClaimed(
            TechniqueActionPermit permit) {
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        Binding existing = bindings.putIfAbsent(required,
                new Binding(required));
        if (existing != null) {
            throw new IllegalStateException(
                    "Technique Action port retained a duplicate claimed permit");
        }
        Binding binding = bindings.get(required);
        ActionMailbox.Submission submission;
        try {
            submission = Objects.requireNonNull(actionRuntime.submit(
                    required.envelope(), required.priority()),
                    "Technique Action runtime submission");
        } catch (RuntimeException | Error failure) {
            binding.markIngressUnknown();
            cancelExact(binding, TechniqueActionCancellationReason
                    .GENERATION_CHANGED, currentActionPortTick());
            throw failure;
        }

        TechniqueActionSubmission mapped = mapSubmission(submission.status());
        if (mapped.status() != TechniqueActionSubmission.Status.ENQUEUED) {
            binding.markRejectedBeforeIngress();
            cacheCancellation(required, ActionCancellationReceipt
                    .fencedBeforeStart(required.botId(),
                            required.botGeneration(), required.actionId()));
            return mapped;
        }

        binding.markEnqueued();
        if (!isClaimedPermitStillActive(required)
                && cachedCancellation(required).isEmpty()) {
            /*
             * The gateway returned after a synchronous close/preempt without
             * the route having reached this port. Contain the exact action;
             * retain ENQUEUED so the route can still drain its real terminal.
             */
            cancelExact(binding, TechniqueActionCancellationReason
                    .GENERATION_CHANGED, currentActionPortTick());
        }
        return mapped;
    }

    @Override
    protected Optional<TechniqueActionTerminal> completedClaimed(
            TechniqueActionPermit permit) {
        Binding binding = bindings.get(Objects.requireNonNull(permit,
                "permit"));
        if (binding == null || binding.state != BindingState.ENQUEUED) {
            return Optional.empty();
        }
        if (binding.terminal != null) {
            return Optional.of(binding.terminal);
        }
        Optional<ActionOutcome> outcome = actionRuntime.completedOutcomeExact(
                permit.envelope());
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        binding.terminal = new TechniqueActionTerminal(permit, permit.kind(),
                outcome.orElseThrow());
        return Optional.of(binding.terminal);
    }

    @Override
    protected TechniqueActionCancellation cancelClaimed(
            TechniqueActionPermit permit,
            TechniqueActionCancellationReason reason,
            long currentTick) {
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        TechniqueActionCancellationReason requiredReason =
                Objects.requireNonNull(reason, "reason");
        long ownerTick = currentActionPortTick();
        Optional<ActionCancellationReceipt> cached = cachedCancellation(required);
        if (cached.isPresent()) {
            return new TechniqueActionCancellation(required, requiredReason,
                    cached.orElseThrow());
        }
        Optional<ActionCancellationReceipt> rejectedIngress =
                cachedRejectedIngress(required);
        if (rejectedIngress.isPresent()) {
            return new TechniqueActionCancellation(required, requiredReason,
                    rejectedIngress.orElseThrow());
        }
        Binding binding = bindings.get(required);
        if (binding == null || binding.state == BindingState.INGRESS_UNKNOWN) {
            ActionCancellationReceipt receipt = unsafeUnknownReceipt(required);
            cacheCancellation(required, receipt);
            unsafeGenerations.add(GenerationKey.from(required));
            return new TechniqueActionCancellation(required, requiredReason,
                    receipt);
        }
        if (binding.state == BindingState.REJECTED_BEFORE_INGRESS) {
            ActionCancellationReceipt receipt = ActionCancellationReceipt
                    .fencedBeforeStart(required.botId(),
                            required.botGeneration(), required.actionId());
            cacheCancellation(required, receipt);
            return new TechniqueActionCancellation(required, requiredReason,
                    receipt);
        }
        return cancelExact(binding, requiredReason, ownerTick);
    }

    /**
     * Releases one retained binding and its exact P2 receipt only after its
     * route has either accepted its exact terminal signal or reaped the
     * corresponding run. A locally fenced rejected-ingress receipt remains
     * available by opaque permit because no Action ever reached P2.
     */
    void release(TechniqueActionPermit permit) {
        requireActionPortOwnerThread();
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        Binding binding = bindings.remove(required);
        ActionCancellationReceipt receipt = cancellationReceipts.remove(
                required);
        if (binding != null && binding.state
                == BindingState.REJECTED_BEFORE_INGRESS && receipt != null) {
            cacheRejectedIngress(required, receipt);
        }
    }

    /** A route must include this in its exact generation-safety proof. */
    boolean isGenerationSafe(UUID botId, long botGeneration) {
        requireActionPortOwnerThread();
        GenerationKey required = new GenerationKey(
                Objects.requireNonNull(botId, "botId"), botGeneration);
        return !unsafeGenerations.contains(required)
                && bindings.values().stream().noneMatch(binding ->
                        required.equals(GenerationKey.from(binding.permit)));
    }

    private TechniqueActionCancellation cancelExact(Binding binding,
            TechniqueActionCancellationReason reason, long currentTick) {
        TechniqueActionPermit permit = binding.permit;
        Optional<ActionCancellationReceipt> cached = cachedCancellation(permit);
        if (cached.isPresent()) {
            return new TechniqueActionCancellation(permit, reason,
                    cached.orElseThrow());
        }
        ActionCancellationReceipt receipt;
        try {
            receipt = Objects.requireNonNull(actionRuntime.cancelOrContain(
                    permit.envelope(), reason.actionReason(), currentTick),
                    "Technique Action runtime cancellation");
        } catch (RuntimeException | Error failure) {
            receipt = unsafeUnknownReceipt(permit);
        }
        if (!receipt.matches(permit.botId(), permit.botGeneration(),
                permit.actionId())) {
            receipt = unsafeUnknownReceipt(permit);
        }
        cacheCancellation(permit, receipt);
        if (!receipt.safelyRetracted()) {
            unsafeGenerations.add(GenerationKey.from(permit));
        }
        return new TechniqueActionCancellation(permit, reason, receipt);
    }

    private Optional<ActionCancellationReceipt> cachedCancellation(
            TechniqueActionPermit permit) {
        return Optional.ofNullable(cancellationReceipts.get(permit));
    }

    private Optional<ActionCancellationReceipt> cachedRejectedIngress(
            TechniqueActionPermit permit) {
        return Optional.ofNullable(rejectedIngressReceipts.get(permit));
    }

    private void cacheCancellation(TechniqueActionPermit permit,
            ActionCancellationReceipt receipt) {
        if (!bindings.containsKey(permit)) {
            return;
        }
        cancellationReceipts.putIfAbsent(permit, Objects.requireNonNull(
                receipt, "receipt"));
    }

    private void cacheRejectedIngress(TechniqueActionPermit permit,
            ActionCancellationReceipt receipt) {
        rejectedIngressReceipts.putIfAbsent(Objects.requireNonNull(permit,
                "permit"), Objects.requireNonNull(receipt, "receipt"));
        while (rejectedIngressReceipts.size()
                > MAX_RETAINED_REJECTED_INGRESS_RECEIPTS) {
            TechniqueActionPermit oldest = rejectedIngressReceipts.keySet()
                    .iterator().next();
            rejectedIngressReceipts.remove(oldest);
        }
    }

    private static TechniqueActionSubmission mapSubmission(
            ActionMailbox.SubmissionStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case ENQUEUED -> TechniqueActionSubmission.enqueued();
            case MAILBOX_FULL -> TechniqueActionSubmission.rejected(
                    TechniqueActionSubmission.Status.MAILBOX_FULL);
            case COMPLETION_BACKPRESSURE -> TechniqueActionSubmission.rejected(
                    TechniqueActionSubmission.Status.COMPLETION_BACKPRESSURE);
            case BOT_GENERATION_CLOSED -> TechniqueActionSubmission.rejected(
                    TechniqueActionSubmission.Status.BOT_GENERATION_CLOSED);
            case RUNTIME_CLOSED -> TechniqueActionSubmission.rejected(
                    TechniqueActionSubmission.Status.RUNTIME_CLOSED);
        };
    }

    private static ActionCancellationReceipt unsafeUnknownReceipt(
            TechniqueActionPermit permit) {
        return ActionCancellationReceipt.unsafe(permit.botId(),
                permit.botGeneration(), permit.actionId(),
                ActionCancellationReceipt.Disposition.UNKNOWN);
    }

    private enum BindingState {
        SUBMITTING,
        ENQUEUED,
        REJECTED_BEFORE_INGRESS,
        INGRESS_UNKNOWN
    }

    private static final class Binding {
        private final TechniqueActionPermit permit;
        private BindingState state = BindingState.SUBMITTING;
        private TechniqueActionTerminal terminal;

        private Binding(TechniqueActionPermit permit) {
            this.permit = Objects.requireNonNull(permit, "permit");
        }

        private void markEnqueued() {
            state = BindingState.ENQUEUED;
        }

        private void markRejectedBeforeIngress() {
            state = BindingState.REJECTED_BEFORE_INGRESS;
        }

        private void markIngressUnknown() {
            state = BindingState.INGRESS_UNKNOWN;
        }
    }

    private record GenerationKey(UUID botId, long botGeneration) {
        private GenerationKey {
            Objects.requireNonNull(botId, "botId");
            if (botGeneration < 1L) {
                throw new IllegalArgumentException(
                        "Technique Action generation must be positive");
            }
        }

        private static GenerationKey from(TechniqueActionPermit permit) {
            return new GenerationKey(permit.botId(), permit.botGeneration());
        }
    }
}
