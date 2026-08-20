package io.github.greytaiwolf.botplayer.technique.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import io.github.greytaiwolf.botplayer.action.WaitAction;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TechniqueActionPermitTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID SKILL = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ACTION = UUID.fromString(
            "55555555-5555-5555-5555-555555555555");

    @Test
    void coordinatorIssuesAnOpaquePermitOnlyDuringExactChildDispatch() {
        PermitRoute route = attempt((run, ticket) -> envelope(ticket, 10L,
                10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);

        assertEquals(TechniqueSubmission.Status.ACCEPTED, route.submission.status());
        assertNull(route.prebindFailure);
        TechniqueActionPermit permit = route.permit;
        assertNotNull(permit);
        assertEquals(BOT, permit.botId());
        assertEquals(1L, permit.botGeneration());
        assertEquals(ActionKind.LOOK_AT, permit.kind());
        assertEquals(Set.of(ActionChannel.LOOK), permit.channels());
        assertEquals(10L, permit.deadlineTick());
        assertEquals(0, TechniqueActionPermit.class.getConstructors().length,
                "a public route cannot construct arbitrary permits");

        assertThrows(IllegalStateException.class,
                () -> route.coordinator.prebindAction(route, route.run, route.ticket,
                        route.envelope, ActionPriority.OWNER_TASK));

        PermitRoute duplicate = attempt((run, ticket) -> envelope(ticket,
                10L, 10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK, false, true);
        assertTrue(duplicate.duplicatePrebindFailure
                instanceof IllegalStateException,
                "one child must never receive multiple Action permits");
    }

    @Test
    void permitRejectsMismatchedTicketEnvelopeWindowAndL0Priority() {
        PermitRoute wrongOrigin = attempt((run, ticket) -> envelope(ticket,
                10L, 10, new LookAtAction(1.0, 2.0, 3.0),
                ActionOrigin.fromSkillRun(run.skillRunId())),
                ActionPriority.OWNER_TASK);
        assertTrue(wrongOrigin.prebindFailure instanceof IllegalArgumentException,
                "origin must contain the exact technique child identity");

        PermitRoute l0Priority = attempt((run, ticket) -> envelope(ticket,
                10L, 10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.SURVIVAL);
        assertTrue(l0Priority.prebindFailure instanceof IllegalArgumentException,
                "techniques must never claim an L0 lane");

        PermitRoute outsideDeadline = attempt((run, ticket) -> envelope(ticket,
                21L, 1, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);
        assertTrue(outsideDeadline.prebindFailure
                instanceof IllegalArgumentException);

        PermitRoute oversizedBudget = attempt((run, ticket) -> envelope(ticket,
                10L, 11, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);
        assertTrue(oversizedBudget.prebindFailure
                instanceof IllegalArgumentException);

        PermitRoute wrongChannels = attempt((run, ticket) -> envelope(ticket,
                10L, 1, new WaitAction(1),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK, Set.of(ActionKind.WAIT));
        assertTrue(wrongChannels.prebindFailure
                instanceof IllegalArgumentException);

        PermitRoute closedDuringDispatch = attempt((run, ticket) -> envelope(
                ticket, 10L, 10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK, true);
        assertTrue(closedDuringDispatch.prebindFailure
                instanceof IllegalStateException,
                "a generation close during child dispatch must fence permit issuance");
    }

    @Test
    void terminalAndCancellationCannotCrossPermitIdentity() {
        PermitRoute route = attempt((run, ticket) -> envelope(ticket, 10L,
                10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);
        TechniqueActionPermit permit = route.permit;
        assertNotNull(permit);
        ActionOutcome succeeded = new ActionOutcome(ACTION,
                ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 2L,
                List.of(), "placed by a contract test");

        TechniqueActionTerminal terminal = new TechniqueActionTerminal(permit,
                ActionKind.LOOK_AT, succeeded);
        assertEquals(succeeded, terminal.outcome());

        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueActionTerminal(permit,
                        ActionKind.MOVE_INPUT, succeeded));
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueActionCancellation(permit,
                        TechniqueActionCancellationReason.SAFETY_PREEMPTION,
                        ActionCancellationReceipt.fencedBeforeStart(BOT, 1L,
                                UUID.randomUUID())));

        TechniqueActionCancellation cancellation = new TechniqueActionCancellation(
                permit, TechniqueActionCancellationReason.SAFETY_PREEMPTION,
                ActionCancellationReceipt.fencedBeforeStart(BOT, 1L, ACTION));
        assertTrue(cancellation.safelyRetracted());
        assertEquals(TechniqueActionSubmission.Status.MAILBOX_FULL,
                TechniqueActionSubmission.rejected(
                        TechniqueActionSubmission.Status.MAILBOX_FULL)
                        .status());
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueActionSubmission.rejected(
                        TechniqueActionSubmission.Status.ENQUEUED));
    }

    @Test
    void portBindsToItsRouteAndConsumesEachPermitExactlyOnce() {
        PortAttempt accepted = attemptWithRecordingPort(true);
        assertEquals(TechniqueActionSubmission.Status.ENQUEUED,
                accepted.route.firstPortSubmission.status());
        assertEquals(TechniqueActionSubmission.Status.ALREADY_CONSUMED,
                accepted.route.secondPortSubmission.status());
        assertEquals(1, accepted.port.submissions);

        PortAttempt preempted = attemptWithRecordingPort(false, true);
        assertEquals(io.github.greytaiwolf.botplayer.technique.runtime
                        .TechniqueCancellationStatus.PREEMPTING,
                preempted.route.preemptionStatus);
        assertEquals(TechniqueActionSubmission.Status.REJECTED_PREBINDING,
                preempted.route.firstPortSubmission.status(),
                "an L0 preemption in the child callback must close Action ingress");
        assertEquals(0, preempted.port.submissions);

        PermitRoute delayed = attempt((run, ticket) -> envelope(ticket, 10L,
                10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);
        delayed.coordinator.closeGeneration(BOT, 1L, 0L);
        RecordingPort delayedPort = new RecordingPort(delayed.coordinator,
                delayed);
        assertEquals(TechniqueActionSubmission.Status.REJECTED_PREBINDING,
                delayedPort.submit(delayed.permit).status(),
                "a retained permit must not submit after its generation closes");
        assertEquals(0, delayedPort.submissions);

        PermitRoute retractedRoute = attempt((run, ticket) -> envelope(ticket,
                10L, 10, new LookAtAction(1.0, 2.0, 3.0),
                TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK);
        RecordingPort retractedPort = new RecordingPort(
                retractedRoute.coordinator, retractedRoute);
        TechniqueActionCancellation first = retractedPort.cancelOrContain(
                retractedRoute.permit,
                TechniqueActionCancellationReason.GENERATION_CHANGED, 0L);
        TechniqueActionCancellation second = retractedPort.cancelOrContain(
                retractedRoute.permit,
                TechniqueActionCancellationReason.GENERATION_CHANGED, 0L);
        assertTrue(first.safelyRetracted());
        assertTrue(second.safelyRetracted());
        assertEquals(0, retractedPort.cancellations);
        assertEquals(TechniqueActionSubmission.Status.ALREADY_CONSUMED,
                retractedPort.submit(retractedRoute.permit).status());
    }

    private static ActionEnvelope envelope(TechniqueChildTicket ticket,
            long deadline, int maxTicks,
            io.github.greytaiwolf.botplayer.action.ActionRequest action,
            ActionOrigin origin) {
        return new ActionEnvelope(ACTION, ticket.botId(), ticket.botGeneration(),
                TechniqueActionPermit.idempotencyKeyFor(ticket), deadline,
                maxTicks, action, origin);
    }

    private static PermitRoute attempt(PermitFactory factory,
            ActionPriority priority) {
        return attempt(factory, priority, false, false,
                Set.of(ActionKind.LOOK_AT));
    }

    private static PermitRoute attempt(PermitFactory factory,
            ActionPriority priority, boolean closeBeforePrebind) {
        return attempt(factory, priority, closeBeforePrebind, false,
                Set.of(ActionKind.LOOK_AT));
    }

    private static PermitRoute attempt(PermitFactory factory,
            ActionPriority priority, boolean closeBeforePrebind,
            boolean duplicatePrebind) {
        return attempt(factory, priority, closeBeforePrebind, duplicatePrebind,
                Set.of(ActionKind.LOOK_AT));
    }

    private static PermitRoute attempt(PermitFactory factory,
            ActionPriority priority, Set<ActionKind> allowedKinds) {
        return attempt(factory, priority, false, false, allowedKinds);
    }

    private static PermitRoute attempt(PermitFactory factory,
            ActionPriority priority, boolean closeBeforePrebind,
            boolean duplicatePrebind, Set<ActionKind> allowedKinds) {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        PermitRoute route = new PermitRoute(coordinator, factory, priority,
                closeBeforePrebind, duplicatePrebind, false, allowedKinds);
        coordinator.register(route);
        route.submission = coordinator.start(route,
                new TechniqueStartRequest(SKILL, BOT, 1L,
                        route.technique().descriptor().id(),
                        route.technique().descriptor().version(),
                        io.github.greytaiwolf.botplayer.technique.core
                                .TechniqueParameters.empty(),
                        0L));
        return route;
    }

    private static PortAttempt attemptWithRecordingPort(
            boolean duplicatePortSubmission) {
        return attemptWithRecordingPort(duplicatePortSubmission, false);
    }

    private static PortAttempt attemptWithRecordingPort(
            boolean duplicatePortSubmission, boolean preemptBeforePortSubmit) {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        PermitRoute route = new PermitRoute(coordinator,
                (run, ticket) -> envelope(ticket, 10L, 10,
                        new LookAtAction(1.0, 2.0, 3.0),
                        TechniqueActionPermit.originFor(run, ticket)),
                ActionPriority.OWNER_TASK, false, false,
                preemptBeforePortSubmit, Set.of(ActionKind.LOOK_AT));
        RecordingPort port = new RecordingPort(coordinator, route);
        route.bindPort(port, duplicatePortSubmission);
        coordinator.register(route);
        route.submission = coordinator.start(route,
                new TechniqueStartRequest(SKILL, BOT, 1L,
                        route.technique().descriptor().id(),
                        route.technique().descriptor().version(),
                        io.github.greytaiwolf.botplayer.technique.core
                                .TechniqueParameters.empty(),
                        0L));
        return new PortAttempt(route, port);
    }

    private static final class PermitRoute extends TechniqueRoute {
        private static final PlayerTechnique TECHNIQUE = new PlayerTechnique() {
            private final io.github.greytaiwolf.botplayer.technique.core
                    .TechniqueDescriptor descriptor =
                    new io.github.greytaiwolf.botplayer.technique.core
                            .TechniqueDescriptor(
                                    new TechniqueId("botplayer",
                                            "contract/permit-route"),
                                    new TechniqueVersion(1, 0, 0),
                                    io.github.greytaiwolf.botplayer.technique.core
                                            .TechniqueRiskLevel.LOW,
                                    20, 2, 1, 1, 0);

            @Override
            public io.github.greytaiwolf.botplayer.technique.core
                    .TechniqueDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public io.github.greytaiwolf.botplayer.technique.runtime
                    .TechniqueDirective start(
                            io.github.greytaiwolf.botplayer.technique.runtime
                                    .TechniqueContext context,
                            io.github.greytaiwolf.botplayer.technique.core
                                    .TechniqueParameters parameters) {
                return io.github.greytaiwolf.botplayer.technique.runtime
                        .TechniqueDirective.awaitChildren("await", List.of(
                                new io.github.greytaiwolf.botplayer.technique.runtime
                                        .TechniqueChildRequest("contract.look",
                                                Set.of(ActionChannel.LOOK))));
            }

            @Override
            public io.github.greytaiwolf.botplayer.technique.runtime
                    .TechniqueDirective tick(
                            io.github.greytaiwolf.botplayer.technique.runtime
                                    .TechniqueContext context,
                            TechniqueRunView run) {
                return io.github.greytaiwolf.botplayer.technique.runtime
                        .TechniqueDirective.continueRunning("await");
            }

            @Override
            public io.github.greytaiwolf.botplayer.technique.runtime
                    .TechniqueDirective verify(
                            io.github.greytaiwolf.botplayer.technique.runtime
                                    .TechniqueContext context,
                            TechniqueRunView run) {
                return io.github.greytaiwolf.botplayer.technique.runtime
                        .TechniqueDirective.fail(
                                io.github.greytaiwolf.botplayer.technique.core
                                        .TechniqueFailureCode.INTERNAL_ERROR,
                                "not used by this contract test");
            }
        };

        private final TechniqueLifecycleCoordinator coordinator;
        private final PermitFactory factory;
        private final ActionPriority priority;
        private final boolean closeBeforePrebind;
        private final boolean duplicatePrebind;
        private final boolean preemptBeforePortSubmit;
        private final Set<ActionKind> allowedKinds;
        private TechniqueRunView run;
        private TechniqueChildTicket ticket;
        private ActionEnvelope envelope;
        private TechniqueActionPermit permit;
        private RuntimeException prebindFailure;
        private RuntimeException duplicatePrebindFailure;
        private TechniqueSubmission submission;
        private TechniqueActionPort port;
        private boolean duplicatePortSubmission;
        private TechniqueActionSubmission firstPortSubmission;
        private TechniqueActionSubmission secondPortSubmission;
        private io.github.greytaiwolf.botplayer.technique.runtime
                .TechniqueCancellationStatus preemptionStatus;

        private PermitRoute(TechniqueLifecycleCoordinator coordinator,
                PermitFactory factory, ActionPriority priority,
                boolean closeBeforePrebind, boolean duplicatePrebind,
                boolean preemptBeforePortSubmit, Set<ActionKind> allowedKinds) {
            this.coordinator = coordinator;
            this.factory = factory;
            this.priority = priority;
            this.closeBeforePrebind = closeBeforePrebind;
            this.duplicatePrebind = duplicatePrebind;
            this.preemptBeforePortSubmit = preemptBeforePortSubmit;
            this.allowedKinds = Set.copyOf(allowedKinds);
        }

        private void bindPort(TechniqueActionPort port,
                boolean duplicatePortSubmission) {
            this.port = port;
            this.duplicatePortSubmission = duplicatePortSubmission;
        }

        @Override
        PlayerTechnique technique() {
            return TECHNIQUE;
        }

        @Override
        void observeTick(long currentTick) {
            // The coordinator owns tick sequencing for this pure contract test.
        }

        @Override
        TechniqueChildDispatcher.Submission submitChild(
                TechniqueChildTicket ticket, TechniqueRunView run) {
            this.run = run;
            this.ticket = ticket;
            envelope = factory.create(run, ticket);
            if (closeBeforePrebind) {
                coordinator.closeGeneration(ticket.botId(),
                        ticket.botGeneration(), ticket.submittedTick());
            }
            try {
                permit = coordinator.prebindAction(this, run, ticket, envelope,
                        priority);
            } catch (RuntimeException exception) {
                prebindFailure = exception;
                return TechniqueChildDispatcher.Submission.rejected(
                        TechniqueChildDispatcher.Status.REJECTED,
                        "permit was rejected by the contract");
            }
            if (duplicatePrebind) {
                try {
                    coordinator.prebindAction(this, run, ticket, envelope,
                            priority);
                } catch (RuntimeException exception) {
                    duplicatePrebindFailure = exception;
                }
            }
            if (port != null) {
                if (preemptBeforePortSubmit) {
                    preemptionStatus = coordinator.preemptForSafety(this,
                            run.techniqueRunId(), ticket.botId(),
                            ticket.botGeneration(), ticket.submittedTick());
                }
                firstPortSubmission = port.submit(permit);
                if (duplicatePortSubmission) {
                    secondPortSubmission = port.submit(permit);
                }
            }
            return TechniqueChildDispatcher.Submission.accepted(
                    "prebound but not submitted");
        }

        @Override
        boolean allowsActionKind(TechniqueChildTicket ticket, ActionKind kind) {
            return "contract.look".equals(ticket.operationKey())
                    && allowedKinds.contains(kind);
        }

        @Override
        void cancelChild(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            // This test never reaches cancellation after its successful prebind.
        }

        @Override
        void drainCompletedChildren(long currentTick,
                TechniqueLifecycleCoordinator.SignalSink signals) {
            // A permit alone is not an Action submission or a terminal signal.
        }

        @Override
        void onGenerationClosing(UUID botId, long botGeneration,
                long currentTick) {
            // No Action port has accepted this permit.
        }

        @Override
        void closeIngress() {
            // No independent ingress exists in this pure contract route.
        }

        @Override
        boolean isRouteGenerationSafe(UUID botId, long botGeneration) {
            return true;
        }

        @Override
        void verifyRun(TechniqueRunView run) {
            assertEquals(TECHNIQUE.descriptor().id(), run.techniqueId());
            assertEquals(TECHNIQUE.descriptor().version(),
                    run.techniqueVersion());
        }

        @Override
        void reapTerminalRun(UUID techniqueRunId) {
            // The test does not advance to a terminal technique state.
        }
    }

    @FunctionalInterface
    private interface PermitFactory {
        ActionEnvelope create(TechniqueRunView run,
                TechniqueChildTicket ticket);
    }

    private static final class RecordingPort extends TechniqueActionPort {
        private int submissions;
        private int cancellations;

        private RecordingPort(TechniqueLifecycleCoordinator coordinator,
                TechniqueRoute route) {
            super(coordinator, route);
        }

        @Override
        protected TechniqueActionSubmission submitClaimed(
                TechniqueActionPermit permit) {
            submissions++;
            return TechniqueActionSubmission.enqueued();
        }

        @Override
        protected java.util.Optional<TechniqueActionTerminal> completedClaimed(
                TechniqueActionPermit permit) {
            return java.util.Optional.empty();
        }

        @Override
        protected TechniqueActionCancellation cancelClaimed(
                TechniqueActionPermit permit,
                TechniqueActionCancellationReason reason,
                long currentTick) {
            cancellations++;
            return new TechniqueActionCancellation(permit, reason,
                    ActionCancellationReceipt.unsafe(permit.botId(),
                            permit.botGeneration(), permit.actionId(),
                            ActionCancellationReceipt.Disposition.UNKNOWN));
        }
    }

    private record PortAttempt(PermitRoute route, RecordingPort port) {
    }
}
