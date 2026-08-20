package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.TechniqueChildOrigin;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Opaque lifecycle capability for one exact prebound Technique child Action.
 *
 * <p>Only {@link TechniqueLifecycleCoordinator} can issue this capability to
 * a registered route while that route is dispatching the matching active child
 * ticket.  It exposes scalar audit identity only; the raw {@link ActionEnvelope}
 * and its priority remain package-private so a Technique cannot turn this into
 * a generic Action or world-write entry point.
 */
public final class TechniqueActionPermit {
    private static final String IDEMPOTENCY_PREFIX = "technique/";

    private final UUID techniqueRunId;
    private final UUID techniqueChildTicketId;
    private final UUID skillRunId;
    private final UUID botId;
    private final long botGeneration;
    private final long techniqueChildRevision;
    private final UUID actionId;
    private final ActionKind kind;
    private final Set<ActionChannel> channels;
    private final long deadlineTick;
    private final ActionEnvelope envelope;
    private final ActionPriority priority;
    private final TechniqueRoute route;
    private IngressState ingressState = IngressState.PENDING;

    private TechniqueActionPermit(TechniqueRoute route,
            TechniqueRunView run,
            TechniqueChildTicket ticket, ActionEnvelope envelope,
            ActionPriority priority) {
        this.route = Objects.requireNonNull(route, "route");
        this.techniqueRunId = run.techniqueRunId();
        this.techniqueChildTicketId = ticket.ticketId();
        this.skillRunId = run.skillRunId();
        this.botId = run.botId();
        this.botGeneration = run.botGeneration();
        this.techniqueChildRevision = ticket.revision();
        this.actionId = envelope.actionId();
        this.kind = envelope.action().kind();
        this.channels = Set.copyOf(envelope.action().channels());
        this.deadlineTick = envelope.deadlineTick();
        this.envelope = envelope;
        this.priority = priority;
    }

    static TechniqueActionPermit issue(
            TechniqueLifecycleCoordinator.PermitIssuer issuer,
            TechniqueRoute route, TechniqueRunView run,
            TechniqueChildTicket ticket, ActionEnvelope envelope,
            ActionPriority priority) {
        Objects.requireNonNull(issuer, "issuer");
        TechniqueRoute requiredRoute = Objects.requireNonNull(route, "route");
        TechniqueRunView requiredRun = Objects.requireNonNull(run, "run");
        TechniqueChildTicket requiredTicket = Objects.requireNonNull(ticket,
                "ticket");
        ActionEnvelope requiredEnvelope = Objects.requireNonNull(envelope,
                "envelope");
        ActionPriority requiredPriority = Objects.requireNonNull(priority,
                "priority");
        requireTicketBelongsToRun(requiredRun, requiredTicket);
        requireEnvelopeMatches(requiredRun, requiredTicket, requiredEnvelope);
        requireTechniquePriority(requiredPriority);
        return new TechniqueActionPermit(requiredRoute, requiredRun, requiredTicket,
                requiredEnvelope, requiredPriority);
    }

    /** Stable Action-ledger key for exactly one server-issued child ticket. */
    public static String idempotencyKeyFor(TechniqueChildTicket ticket) {
        TechniqueChildTicket required = Objects.requireNonNull(ticket,
                "ticket");
        return IDEMPOTENCY_PREFIX + required.techniqueRunId() + "/"
                + required.ticketId() + "/" + required.revision();
    }

    /** Builds the sole Action-origin value accepted for this exact child. */
    public static ActionOrigin originFor(TechniqueRunView run,
            TechniqueChildTicket ticket) {
        TechniqueRunView requiredRun = Objects.requireNonNull(run, "run");
        TechniqueChildTicket requiredTicket = Objects.requireNonNull(ticket,
                "ticket");
        return ActionOrigin.fromTechniqueChild(requiredRun.skillRunId(),
                new TechniqueChildOrigin(requiredTicket.techniqueRunId(),
                        requiredTicket.ticketId(), requiredTicket.revision()));
    }

    public UUID techniqueRunId() {
        return techniqueRunId;
    }

    public UUID techniqueChildTicketId() {
        return techniqueChildTicketId;
    }

    public UUID skillRunId() {
        return skillRunId;
    }

    public UUID botId() {
        return botId;
    }

    public long botGeneration() {
        return botGeneration;
    }

    public long techniqueChildRevision() {
        return techniqueChildRevision;
    }

    public UUID actionId() {
        return actionId;
    }

    public ActionKind kind() {
        return kind;
    }

    public Set<ActionChannel> channels() {
        return channels;
    }

    public long deadlineTick() {
        return deadlineTick;
    }

    ActionEnvelope envelope() {
        return envelope;
    }

    ActionPriority priority() {
        return priority;
    }

    boolean belongsTo(TechniqueRoute candidate) {
        return route == Objects.requireNonNull(candidate, "route");
    }

    boolean claimForActionIngress() {
        if (ingressState != IngressState.PENDING) {
            return false;
        }
        ingressState = IngressState.CLAIMED;
        return true;
    }

    boolean retractBeforeActionIngress() {
        if (ingressState != IngressState.PENDING) {
            return false;
        }
        ingressState = IngressState.RETRACTED_BEFORE_INGRESS;
        return true;
    }

    boolean ingressWasClaimed() {
        return ingressState == IngressState.CLAIMED;
    }

    boolean wasRetractedBeforeActionIngress() {
        return ingressState == IngressState.RETRACTED_BEFORE_INGRESS;
    }

    boolean isConsumed() {
        return ingressState != IngressState.PENDING;
    }

    private static void requireTicketBelongsToRun(TechniqueRunView run,
            TechniqueChildTicket ticket) {
        if (!run.techniqueRunId().equals(ticket.techniqueRunId())
                || !run.botId().equals(ticket.botId())
                || run.botGeneration() != ticket.botGeneration()) {
            throw new IllegalArgumentException(
                    "Technique Action ticket did not match its run identity");
        }
        if (ticket.state() != TechniqueChildState.ACTIVE
                || !run.childTickets().contains(ticket)) {
            throw new IllegalArgumentException(
                    "Technique Action ticket was not an active child of the run");
        }
    }

    private static void requireEnvelopeMatches(TechniqueRunView run,
            TechniqueChildTicket ticket, ActionEnvelope envelope) {
        if (!run.botId().equals(envelope.botId())
                || run.botGeneration() != envelope.botGeneration()) {
            throw new IllegalArgumentException(
                    "Technique Action envelope did not match the child body generation");
        }
        if (!ticket.channels().equals(envelope.action().channels())) {
            throw new IllegalArgumentException(
                    "Technique Action channels did not exactly match the child ticket");
        }
        if (envelope.deadlineTick() <= ticket.submittedTick()
                || envelope.deadlineTick() > run.deadlineTick()) {
            throw new IllegalArgumentException(
                    "Technique Action deadline must remain within the child run window");
        }
        long availableTicks = envelope.deadlineTick() - ticket.submittedTick();
        if (envelope.maxTicks() > availableTicks) {
            throw new IllegalArgumentException(
                    "Technique Action tick budget exceeded its prebound deadline window");
        }
        if (!idempotencyKeyFor(ticket).equals(envelope.idempotencyKey())) {
            throw new IllegalArgumentException(
                    "Technique Action idempotency key did not match the child ticket");
        }
        if (!originFor(run, ticket).equals(envelope.origin())) {
            throw new IllegalArgumentException(
                    "Technique Action origin did not match the exact child ticket");
        }
    }

    private static void requireTechniquePriority(ActionPriority priority) {
        switch (priority) {
            case BACKGROUND, AUTONOMOUS, OWNER_TASK -> {
                // These priorities remain below L0 safety intervention.
            }
            case OWNER_CONTROL, SURVIVAL, LIFECYCLE_CLEANUP, EMERGENCY ->
                    throw new IllegalArgumentException(
                            "Technique Action priority must not claim owner-control or L0 lanes");
        }
    }

    private enum IngressState {
        PENDING,
        CLAIMED,
        RETRACTED_BEFORE_INGRESS
    }
}
