package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueOutcome;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancellationStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRegistrationStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRuntime;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignal;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignalStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The single owner-thread lifecycle boundary for all registered player Techniques.
 *
 * <p>It owns exactly one {@link TechniqueRuntime}, keeps its child dispatcher private, and routes
 * every opaque child ticket back to the route which registered the matching technique descriptor.
 * A route cannot be chosen by a caller, an Action, an AI plan, or a world DTO. The coordinator does
 * not itself create Actions, Navigation requests, Skills, packets, or Minecraft interactions.
 *
 * <p>One route currently hosts the already-authorized self-defense melee bridge. This does not
 * create P5D building gameplay: future routes require their own reviewed admission, prebinding and
 * lifecycle integration before they can be registered.
 */
public final class TechniqueLifecycleCoordinator {
    /** An unforgeable package capability required to create an Action permit. */
    static final class PermitIssuer {
        private PermitIssuer() {
        }
    }

    private final Thread ownerThread;
    private final TechniqueRuntime runtime;
    private final PermitIssuer permitIssuer = new PermitIssuer();
    private final Map<RouteKey, TechniqueRoute> routesByKey = new LinkedHashMap<>();
    private final Map<UUID, TechniqueRoute> routesByRunId = new LinkedHashMap<>();
    private final Map<UUID, TicketRoute> routesByTicketId = new LinkedHashMap<>();
    private TechniqueRoute pendingStartRoute;
    /** A permit may be issued only during this exact coordinator child dispatch. */
    private TechniqueRoute pendingChildSubmissionRoute;
    private TechniqueChildTicket pendingChildSubmissionTicket;
    /** One child may freeze exactly one Action identity before external ingress. */
    private TechniqueActionPermit pendingChildPermit;
    private long lastObservedTick = -1L;
    private long advancedThroughTick = -1L;

    public TechniqueLifecycleCoordinator() {
        ownerThread = Thread.currentThread();
        runtime = new TechniqueRuntime(new TechniqueChildDispatcher() {
            @Override
            public Submission submit(TechniqueChildTicket ticket) {
                return dispatchChild(ticket);
            }

            @Override
            public void cancel(TechniqueChildTicket ticket,
                    TechniqueCancelReason reason) {
                cancelChild(ticket, reason);
            }
        });
    }

    /**
     * Advances each live Technique at most once for one server tick.
     *
     * <p>The coordinator, not an individual route, is the only production caller of
     * {@link TechniqueRuntime#tick(UUID, long, long)}. This prevents two routes from advancing
     * the same single-runtime body slot in one unsealed tick.
     */
    public void tick(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        observeRoutes(currentTick);
        if (currentTick == advancedThroughTick) {
            return;
        }
        for (Map.Entry<UUID, TechniqueRoute> entry : snapshotRuns()) {
            TechniqueRunView run = runtime.inspectRun(entry.getKey()).orElse(null);
            if (run == null) {
                reapTerminalRun(entry.getKey(), entry.getValue());
                continue;
            }
            entry.getValue().verifyRun(run);
            runtime.tick(run.botId(), run.botGeneration(), currentTick);
        }
        advancedThroughTick = currentTick;
        reapTerminalRuns();
    }

    /** Drains each approved route exactly once after the Action runtime has advanced. */
    public void drainCompletedChildren(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        observeRoutes(currentTick);
        for (TechniqueRoute route : List.copyOf(routesByKey.values())) {
            route.drainCompletedChildren(currentTick, this::offerSignal);
        }
        reapTerminalRuns();
    }

    /** Seals the Technique boundary after all routes drained their external terminal signals. */
    public void finishTick(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        observeRoutes(currentTick);
        for (Map.Entry<UUID, TechniqueRoute> entry : snapshotRuns()) {
            TechniqueRunView run = runtime.inspectRun(entry.getKey()).orElse(null);
            if (run == null) {
                reapTerminalRun(entry.getKey(), entry.getValue());
                continue;
            }
            entry.getValue().verifyRun(run);
            runtime.finishTick(run.botId(), run.botGeneration(), currentTick);
        }
        reapTerminalRuns();
    }

    /**
     * Closes one body generation before the dependent Skill/action lifecycle releases it.
     *
     * <p>The runtime requests exact child cancellation first; routes then clean any still-pending
     * narrow bindings which can no longer have a live Technique owner. This ordering preserves the
     * child identity during synchronous Action ingress reentrancy.
     */
    public void closeGeneration(UUID botId, long botGeneration, long currentTick) {
        requireOwnerThread();
        UUID checkedBotId = requireNonZero(botId, "botId");
        if (botGeneration < 1L) {
            throw new IllegalArgumentException("technique generation must be positive");
        }
        observeTick(currentTick);
        observeRoutes(currentTick);
        runtime.closeGeneration(checkedBotId, botGeneration, currentTick);
        for (TechniqueRoute route : List.copyOf(routesByKey.values())) {
            route.onGenerationClosing(checkedBotId, botGeneration, currentTick);
        }
        reapTerminalRuns();
    }

    /** Closes new Technique ingress but leaves later exact child terminal drains available. */
    public void shutdown(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        observeRoutes(currentTick);
        for (TechniqueRoute route : List.copyOf(routesByKey.values())) {
            route.closeIngress();
        }
        runtime.shutdown(currentTick);
        reapTerminalRuns();
    }

    /** A generation is safe only when the runtime and every registered route agree. */
    public boolean isGenerationSafe(UUID botId, long botGeneration) {
        requireOwnerThread();
        UUID checkedBotId = requireNonZero(botId, "botId");
        if (botGeneration < 1L) {
            throw new IllegalArgumentException("technique generation must be positive");
        }
        if (!runtime.isGenerationSafe(checkedBotId, botGeneration)) {
            return false;
        }
        return routesByKey.values().stream().allMatch(route ->
                route.isRouteGenerationSafe(checkedBotId, botGeneration));
    }

    /** Returns the current single-runtime view for diagnostics owned by the lifecycle. */
    public Optional<TechniqueRunView> inspect(UUID botId) {
        requireOwnerThread();
        return runtime.inspect(requireNonZero(botId, "botId"));
    }

    /** Returns the latest single-runtime outcome for diagnostics owned by the lifecycle. */
    public Optional<TechniqueOutcome> latestOutcome(UUID botId) {
        requireOwnerThread();
        return runtime.latestOutcome(requireNonZero(botId, "botId"));
    }

    /** The coordinator's count includes every registered route, not only self-defense. */
    public int activeRunCount() {
        requireOwnerThread();
        reapTerminalRuns();
        return routesByRunId.size();
    }

    /** Package-private route registration; production code cannot register arbitrary callers. */
    void register(TechniqueRoute route) {
        requireOwnerThread();
        TechniqueRoute checked = Objects.requireNonNull(route, "route");
        PlayerTechnique technique = Objects.requireNonNull(checked.technique(), "route technique");
        RouteKey key = RouteKey.from(technique);
        if (routesByKey.containsKey(key)) {
            throw new IllegalStateException("a route already owns this technique descriptor");
        }
        TechniqueRegistrationStatus registration = runtime.register(technique);
        if (registration != TechniqueRegistrationStatus.REGISTERED) {
            throw new IllegalStateException(
                    "could not register route technique: " + registration);
        }
        routesByKey.put(key, checked);
    }

    /** Package-private narrow start used by a route's already-authorized ingress. */
    TechniqueSubmission start(TechniqueRoute route, TechniqueStartRequest request) {
        requireOwnerThread();
        TechniqueRoute checkedRoute = requireRegisteredRoute(route);
        TechniqueStartRequest checkedRequest = Objects.requireNonNull(request, "request");
        RouteKey expected = RouteKey.from(checkedRoute.technique());
        if (!expected.matches(checkedRequest)) {
            return TechniqueSubmission.rejected(TechniqueSubmission.Status.INVALID_REQUEST,
                    "Technique start did not match its registered route");
        }
        observeTick(checkedRequest.currentTick());
        observeRoutes(checkedRequest.currentTick());
        if (pendingStartRoute != null) {
            throw new IllegalStateException("technique starts must not re-enter a route dispatch");
        }
        pendingStartRoute = checkedRoute;
        try {
            TechniqueSubmission submission = runtime.start(checkedRequest);
            if (submission.status() == TechniqueSubmission.Status.ACCEPTED) {
                UUID runId = submission.techniqueRunId().orElseThrow();
                TechniqueRunView run = runtime.inspectRun(runId).orElse(null);
                if (run != null) {
                    bindRun(checkedRoute, run);
                }
            }
            return submission;
        } finally {
            pendingStartRoute = null;
            /*
             * The external child port has now returned, so it is safe to
             * release a run which synchronously rejected its only child or
             * otherwise reached a terminal state during start. Reaping while
             * pendingStartRoute was set would race a route still unwinding
             * that same ingress.
             */
            reapTerminalRuns();
        }
    }

    /** Package-private exact child-signal route; a wrong route/ticket cannot reach the runtime. */
    TechniqueSignalStatus offerSignal(TechniqueRoute route, TechniqueSignal signal,
            long currentTick) {
        requireOwnerThread();
        TechniqueRoute checkedRoute = requireRegisteredRoute(route);
        TechniqueSignal checkedSignal = Objects.requireNonNull(signal, "signal");
        observeTick(currentTick);
        TicketRoute ticketRoute = routesByTicketId.get(checkedSignal.ticketId());
        if (ticketRoute == null
                || ticketRoute.route() != checkedRoute
                || !ticketRoute.techniqueRunId().equals(checkedSignal.techniqueRunId())
                || routesByRunId.get(checkedSignal.techniqueRunId()) != checkedRoute) {
            return TechniqueSignalStatus.IDENTITY_MISMATCH;
        }
        TechniqueSignalStatus status = runtime.offerSignal(checkedSignal, currentTick);
        if (status == TechniqueSignalStatus.ACCEPTED) {
            routesByTicketId.remove(checkedSignal.ticketId(), ticketRoute);
        }
        reapTerminalRuns();
        return status;
    }

    /** Package-private route-specific L0 preemption, never a broad botId cancellation. */
    TechniqueCancellationStatus preemptForSafety(TechniqueRoute route,
            UUID techniqueRunId, UUID botId, long botGeneration, long currentTick) {
        requireOwnerThread();
        TechniqueRoute checkedRoute = requireRegisteredRoute(route);
        UUID checkedRunId = requireNonZero(techniqueRunId, "techniqueRunId");
        UUID checkedBotId = requireNonZero(botId, "botId");
        observeTick(currentTick);
        observeRoutes(currentTick);
        if (routesByRunId.get(checkedRunId) != checkedRoute) {
            return TechniqueCancellationStatus.IDENTITY_MISMATCH;
        }
        TechniqueRunView run = runtime.inspectRun(checkedRunId).orElse(null);
        if (run == null) {
            reapTerminalRun(checkedRunId, checkedRoute);
            return TechniqueCancellationStatus.RUN_NOT_FOUND;
        }
        checkedRoute.verifyRun(run);
        TechniqueCancellationStatus status = runtime.preemptForSafety(
                checkedRunId, checkedBotId, botGeneration, currentTick);
        reapTerminalRuns();
        return status;
    }

    /**
     * Issues one opaque Action capability for the child currently dispatched to
     * a registered route.
     *
     * <p>This is intentionally package-private: routes have no generic
     * {@code submit(ActionEnvelope)} entry point.  The coordinator rechecks
     * route, run and ticket identity while the child dispatcher is still
     * unwinding, before the permit can reach an Action port.
     */
    TechniqueActionPermit prebindAction(TechniqueRoute route,
            TechniqueRunView run, TechniqueChildTicket ticket,
            ActionEnvelope envelope, ActionPriority priority) {
        requireOwnerThread();
        TechniqueRoute checkedRoute = requireRegisteredRoute(route);
        TechniqueRunView checkedRun = Objects.requireNonNull(run, "run");
        TechniqueChildTicket checkedTicket = Objects.requireNonNull(ticket,
                "ticket");
        if (pendingChildSubmissionRoute != checkedRoute
                || !checkedTicket.equals(pendingChildSubmissionTicket)) {
            throw new IllegalStateException(
                    "Technique Action permits may only be issued during exact child dispatch");
        }
        if (pendingChildPermit != null) {
            throw new IllegalStateException(
                    "Technique child already owns a prebound Action permit");
        }
        TicketRoute ticketRoute = routesByTicketId.get(checkedTicket.ticketId());
        if (ticketRoute == null || ticketRoute.route() != checkedRoute
                || !ticketRoute.techniqueRunId().equals(
                        checkedTicket.techniqueRunId())) {
            throw new IllegalStateException(
                    "Technique Action ticket was no longer routed to its owner");
        }
        TechniqueRunView current = runtime.inspectRun(
                checkedTicket.techniqueRunId()).orElse(null);
        if (current == null || !current.equals(checkedRun)) {
            throw new IllegalStateException(
                    "Technique Action run changed before permit issuance");
        }
        checkedRoute.verifyRun(current);
        ActionEnvelope checkedEnvelope = Objects.requireNonNull(envelope,
                "envelope");
        if (!checkedRoute.allowsActionKind(checkedTicket,
                checkedEnvelope.action().kind())) {
            throw new IllegalArgumentException(
                    "Technique route did not allow this Action kind for its child");
        }
        TechniqueActionPermit permit = TechniqueActionPermit.issue(permitIssuer,
                checkedRoute, current, checkedTicket, checkedEnvelope,
                Objects.requireNonNull(priority, "priority"));
        pendingChildPermit = permit;
        return permit;
    }

    /**
     * Atomically claims a permit only while its original child dispatch is
     * still live on this coordinator's owner thread.
     *
     * <p>A route therefore cannot retain a prebound permit and submit it after
     * a generation close, L0 preemption, terminal child signal, or tick-boundary
     * transition.  A future adapter must still exact-cancel an Action if its
     * own external ingress synchronously re-enters lifecycle code.
     */
    boolean claimActionPermitForIngress(TechniqueRoute route,
            TechniqueActionPermit permit) {
        requireOwnerThread();
        TechniqueRoute checkedRoute = requireRegisteredRoute(route);
        TechniqueActionPermit checkedPermit = Objects.requireNonNull(permit,
                "permit");
        if (!checkedPermit.belongsTo(checkedRoute)
                || pendingChildSubmissionRoute != checkedRoute
                || pendingChildPermit != checkedPermit
                || pendingChildSubmissionTicket == null
                || !pendingChildSubmissionTicket.ticketId().equals(
                        checkedPermit.techniqueChildTicketId())
                || !pendingChildSubmissionTicket.techniqueRunId().equals(
                        checkedPermit.techniqueRunId())
                || pendingChildSubmissionTicket.revision()
                        != checkedPermit.techniqueChildRevision()) {
            return false;
        }
        TechniqueRunView current = runtime.inspectRun(
                checkedPermit.techniqueRunId()).orElse(null);
        if (current == null || current.state()
                != io.github.greytaiwolf.botplayer.technique.runtime
                        .TechniqueState.WAITING_CHILDREN
                || !current.botId().equals(checkedPermit.botId())
                || current.botGeneration() != checkedPermit.botGeneration()
                || current.childTickets().stream().noneMatch(ticket ->
                        ticket.ticketId().equals(
                                checkedPermit.techniqueChildTicketId())
                                && ticket.revision()
                                        == checkedPermit.techniqueChildRevision()
                                && ticket.state()
                                        == io.github.greytaiwolf.botplayer.technique.runtime
                                                .TechniqueChildState.ACTIVE)) {
            return false;
        }
        return checkedPermit.claimForActionIngress();
    }

    /** Owner-thread fence shared by every permit port operation. */
    void requireActionPortOwnerThread() {
        requireOwnerThread();
    }

    /** Package-private exact runtime view for route-side identity checks only. */
    Optional<TechniqueRunView> inspectRun(UUID techniqueRunId) {
        requireOwnerThread();
        return runtime.inspectRun(requireNonZero(techniqueRunId, "techniqueRunId"));
    }

    @FunctionalInterface
    interface SignalSink {
        TechniqueSignalStatus offer(TechniqueRoute route, TechniqueSignal signal,
                long currentTick);
    }

    private TechniqueChildDispatcher.Submission dispatchChild(TechniqueChildTicket ticket) {
        requireOwnerThread();
        TechniqueChildTicket checked = Objects.requireNonNull(ticket, "ticket");
        TechniqueRoute route = routesByRunId.get(checked.techniqueRunId());
        if (route == null) {
            route = pendingStartRoute;
            if (route == null) {
                return TechniqueChildDispatcher.Submission.rejected(
                        TechniqueChildDispatcher.Status.REJECTED,
                        "Technique child did not belong to a registered route");
            }
        }
        TechniqueRunView run = runtime.inspectRun(checked.techniqueRunId()).orElse(null);
        if (run == null || !run.techniqueRunId().equals(checked.techniqueRunId())) {
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Technique child runtime view was unavailable");
        }
        if (!RouteKey.from(route.technique()).matches(run)) {
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Technique child did not match its registered route");
        }
        TechniqueRoute existingRunRoute = routesByRunId.putIfAbsent(
                checked.techniqueRunId(), route);
        if (existingRunRoute != null && existingRunRoute != route) {
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Technique run was routed to a different route");
        }
        TicketRoute existing = routesByTicketId.putIfAbsent(checked.ticketId(),
                new TicketRoute(checked.techniqueRunId(), route));
        if (existing != null) {
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.CHANNEL_BUSY,
                    "Technique child ticket was already routed");
        }
        if (pendingChildSubmissionRoute != null) {
            routesByTicketId.remove(checked.ticketId());
            return TechniqueChildDispatcher.Submission.rejected(
                    TechniqueChildDispatcher.Status.REJECTED,
                    "Technique child dispatch must not re-enter another route");
        }
        pendingChildSubmissionRoute = route;
        pendingChildSubmissionTicket = checked;
        pendingChildPermit = null;
        try {
            try {
                TechniqueChildDispatcher.Submission submission = Objects.requireNonNull(
                        route.submitChild(checked, run), "route child submission");
                if (submission.status()
                        != TechniqueChildDispatcher.Status.ACCEPTED) {
                    routesByTicketId.remove(checked.ticketId());
                } else {
                    route.verifyRun(run);
                }
                return submission;
            } catch (RuntimeException exception) {
                routesByTicketId.remove(checked.ticketId());
                try {
                    route.cancelChild(checked,
                            TechniqueCancelReason.GENERATION_CHANGED);
                } catch (RuntimeException ignored) {
                    /* A route owns any external fail-closed containment proof. */
                }
                return TechniqueChildDispatcher.Submission.rejected(
                        TechniqueChildDispatcher.Status.REJECTED,
                        "Technique route threw while accepting a child");
            }
        } finally {
            pendingChildSubmissionRoute = null;
            pendingChildSubmissionTicket = null;
            pendingChildPermit = null;
        }
    }

    private void cancelChild(TechniqueChildTicket ticket, TechniqueCancelReason reason) {
        requireOwnerThread();
        TechniqueChildTicket checkedTicket = Objects.requireNonNull(ticket, "ticket");
        TechniqueCancelReason checkedReason = Objects.requireNonNull(reason, "reason");
        TicketRoute ticketRoute = routesByTicketId.get(checkedTicket.ticketId());
        if (ticketRoute == null
                || !ticketRoute.techniqueRunId().equals(checkedTicket.techniqueRunId())
                || routesByRunId.get(checkedTicket.techniqueRunId()) != ticketRoute.route()) {
            return;
        }
        ticketRoute.route().cancelChild(checkedTicket, checkedReason);
    }

    private TechniqueRoute requireRegisteredRoute(TechniqueRoute route) {
        TechniqueRoute checked = Objects.requireNonNull(route, "route");
        RouteKey key = RouteKey.from(checked.technique());
        if (routesByKey.get(key) != checked) {
            throw new IllegalArgumentException(
                    "Technique route is not registered with this coordinator");
        }
        return checked;
    }

    private void bindRun(TechniqueRoute route, TechniqueRunView run) {
        TechniqueRoute existing = routesByRunId.putIfAbsent(run.techniqueRunId(), route);
        if (existing != null && existing != route) {
            throw new IllegalStateException("Technique run was routed to a different route");
        }
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "technique coordinator tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void observeRoutes(long currentTick) {
        for (TechniqueRoute route : routesByKey.values()) {
            route.observeTick(currentTick);
        }
    }

    private List<Map.Entry<UUID, TechniqueRoute>> snapshotRuns() {
        return new ArrayList<>(routesByRunId.entrySet());
    }

    private void reapTerminalRuns() {
        if (pendingStartRoute != null) {
            return;
        }
        for (Map.Entry<UUID, TechniqueRoute> entry : snapshotRuns()) {
            if (runtime.inspectRun(entry.getKey()).isEmpty()) {
                reapTerminalRun(entry.getKey(), entry.getValue());
            }
        }
    }

    private void reapTerminalRun(UUID techniqueRunId, TechniqueRoute route) {
        if (!routesByRunId.remove(techniqueRunId, route)) {
            return;
        }
        routesByTicketId.entrySet().removeIf(entry ->
                entry.getValue().techniqueRunId().equals(techniqueRunId));
        route.reapTerminalRun(techniqueRunId);
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID checked = Objects.requireNonNull(value, name);
        if (checked.getMostSignificantBits() == 0L
                && checked.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
        return checked;
    }

    private record RouteKey(TechniqueId techniqueId, TechniqueVersion techniqueVersion) {
        private RouteKey {
            techniqueId = Objects.requireNonNull(techniqueId, "techniqueId");
            techniqueVersion = Objects.requireNonNull(techniqueVersion, "techniqueVersion");
        }

        private static RouteKey from(PlayerTechnique technique) {
            PlayerTechnique checked = Objects.requireNonNull(technique, "technique");
            return new RouteKey(checked.descriptor().id(), checked.descriptor().version());
        }

        private boolean matches(TechniqueStartRequest request) {
            return techniqueId.equals(request.techniqueId())
                    && techniqueVersion.equals(request.techniqueVersion());
        }

        private boolean matches(TechniqueRunView run) {
            return techniqueId.equals(run.techniqueId())
                    && techniqueVersion.equals(run.techniqueVersion());
        }
    }

    private record TicketRoute(UUID techniqueRunId, TechniqueRoute route) {
        private TicketRoute {
            techniqueRunId = requireNonZero(techniqueRunId, "techniqueRunId");
            route = Objects.requireNonNull(route, "route");
        }
    }
}
