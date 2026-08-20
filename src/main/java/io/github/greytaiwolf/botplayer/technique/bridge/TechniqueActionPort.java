package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import java.util.Objects;
import java.util.Optional;

/**
 * Lifecycle-owned port between a registered Technique route and P2 Action.
 *
 * <p>Only a route holding a server-issued {@link TechniqueActionPermit} may
 * submit.  The package-private constructor binds an implementation to one
 * coordinator and one registered route; the final ingress method may claim a
 * permit only during the original active child dispatch on that coordinator's
 * owner thread.  A failed ingress is deliberately not retried through the
 * same permit: the parent must issue a later child ticket after it observes a
 * safe terminal boundary.
 */
public abstract class TechniqueActionPort {
    private final TechniqueLifecycleCoordinator coordinator;
    private final TechniqueRoute route;

    TechniqueActionPort(TechniqueLifecycleCoordinator coordinator,
            TechniqueRoute route) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.route = Objects.requireNonNull(route, "route");
    }

    public final TechniqueActionSubmission submit(TechniqueActionPermit permit) {
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        if (!required.belongsTo(route)) {
            return TechniqueActionSubmission.rejected(
                    TechniqueActionSubmission.Status.REJECTED_PREBINDING);
        }
        if (!coordinator.claimActionPermitForIngress(route, required)) {
            return TechniqueActionSubmission.rejected(
                    required.isConsumed()
                            ? TechniqueActionSubmission.Status.ALREADY_CONSUMED
                            : TechniqueActionSubmission.Status.REJECTED_PREBINDING);
        }
        return Objects.requireNonNull(submitClaimed(required),
                "Technique Action port submission");
    }

    public final Optional<TechniqueActionTerminal> completed(
            TechniqueActionPermit permit) {
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        coordinator.requireActionPortOwnerThread();
        if (!required.belongsTo(route) || !required.ingressWasClaimed()) {
            return Optional.empty();
        }
        Optional<TechniqueActionTerminal> terminal = Optional.ofNullable(
                completedClaimed(required)).orElseThrow(() ->
                        new NullPointerException("Technique Action terminal optional"));
        terminal.ifPresent(value -> {
            if (value.permit() != required) {
                throw new IllegalStateException(
                        "Technique Action port returned a terminal for another permit");
            }
        });
        return terminal;
    }

    public final TechniqueActionCancellation cancelOrContain(
            TechniqueActionPermit permit,
            TechniqueActionCancellationReason reason,
            long currentTick) {
        TechniqueActionPermit required = Objects.requireNonNull(permit,
                "permit");
        coordinator.requireActionPortOwnerThread();
        TechniqueActionCancellationReason requiredReason =
                Objects.requireNonNull(reason, "reason");
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "Technique Action cancellation tick must not be negative");
        }
        if (!required.belongsTo(route)) {
            return new TechniqueActionCancellation(required, requiredReason,
                    ActionCancellationReceipt.unsafe(required.botId(),
                            required.botGeneration(), required.actionId(),
                            ActionCancellationReceipt.Disposition.UNKNOWN));
        }
        if (required.retractBeforeActionIngress()) {
            return new TechniqueActionCancellation(required, requiredReason,
                    ActionCancellationReceipt.fencedBeforeStart(
                            required.botId(), required.botGeneration(),
                            required.actionId()));
        }
        if (required.wasRetractedBeforeActionIngress()) {
            return new TechniqueActionCancellation(required, requiredReason,
                    ActionCancellationReceipt.fencedBeforeStart(
                            required.botId(), required.botGeneration(),
                            required.actionId()));
        }
        TechniqueActionCancellation cancellation = Objects.requireNonNull(
                cancelClaimed(required, requiredReason, currentTick),
                "Technique Action cancellation");
        if (cancellation.permit() != required) {
            throw new IllegalStateException(
                    "Technique Action port returned cancellation for another permit");
        }
        return cancellation;
    }

    /** Runs only after this exact permit has been irreversibly claimed once. */
    protected abstract TechniqueActionSubmission submitClaimed(
            TechniqueActionPermit permit);

    /** Must return only a terminal outcome for the same claimed permit. */
    protected abstract Optional<TechniqueActionTerminal> completedClaimed(
            TechniqueActionPermit permit);

    /** Must use exact P2 containment once ingress was claimed. */
    protected abstract TechniqueActionCancellation cancelClaimed(
            TechniqueActionPermit permit,
            TechniqueActionCancellationReason reason,
            long currentTick);
}
