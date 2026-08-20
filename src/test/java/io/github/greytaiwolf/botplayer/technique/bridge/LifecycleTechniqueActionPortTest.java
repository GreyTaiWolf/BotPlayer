package io.github.greytaiwolf.botplayer.technique.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueContext;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueDirective;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignal;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignalStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LifecycleTechniqueActionPortTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID SKILL = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ACTION = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    @Test
    void productionRuntimeDrainsTheExactTerminalOnlyAtTheLifecycleBoundary() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        BotActionRuntime runtime = new BotActionRuntime(new SucceedingBackend(),
                8, 16, 8, 8, 8);
        AdapterRoute route = AdapterRoute.withRuntime(coordinator, runtime);
        coordinator.register(route);

        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                coordinator.start(route, request(route.technique(), 0L)).status());
        assertEquals(TechniqueActionSubmission.Status.ENQUEUED,
                route.portSubmission.status());
        assertEquals(0, route.acceptedSignals);

        for (long tick = 1L; tick <= 5L; tick++) {
            coordinator.tick(tick);
            runtime.tick(tick);
            coordinator.drainCompletedChildren(tick);
            coordinator.finishTick(tick);
        }

        assertEquals(1, route.acceptedSignals,
                "the route must publish one child terminal only from drain");
        assertTrue(route.port.isGenerationSafe(BOT, 1L),
                "the terminal route release must clear its exact binding");
        ActionEnvelope mismatched = new ActionEnvelope(ACTION, BOT, 2L,
                "other/terminal", 10L, 10,
                new LookAtAction(1.0, 2.0, 3.0),
                io.github.greytaiwolf.botplayer.action.ActionOrigin.none());
        assertTrue(runtime.completedOutcomeExact(mismatched).isEmpty(),
                "the Action runtime must not return a retained terminal for a different envelope");
    }

    @Test
    void synchronousGenerationCloseAfterIngressContainsTheExactActionOnce() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        gateway.onSubmit = () -> coordinator.closeGeneration(BOT, 1L, 0L);

        coordinator.start(route, request(route.technique(), 0L));

        assertNotNull(route.issuedPermit);
        assertEquals(1, gateway.cancelCount,
                "cancelChild and onGenerationClosing must share one receipt");
        assertTrue(route.cancellations.stream().allMatch(
                TechniqueActionCancellation::safelyRetracted));
        assertEquals(0, route.acceptedSignals);
        assertFalse(route.port.isGenerationSafe(BOT, 1L),
                "safe containment alone must retain the live child for terminal drain");

        gateway.complete(route.envelope, ActionState.CANCELLED,
                ActionFailureCode.CANCELLED);
        coordinator.drainCompletedChildren(1L);
        coordinator.finishTick(1L);
        assertEquals(1, route.acceptedSignals);
        assertEquals(TechniqueChildState.CANCELLED,
                route.acceptedSignalValues.getFirst().state());
        assertTrue(route.port.isGenerationSafe(BOT, 1L));

        gateway.complete(route.envelope, ActionState.SUCCEEDED,
                ActionFailureCode.NONE);
        coordinator.drainCompletedChildren(2L);
        assertEquals(1, route.acceptedSignals,
                "a late success after generation close cannot revive the child");
    }

    @Test
    void rejectedIngressUsesOnlyALocalFencedReceipt() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        gateway.submissionStatus = ActionMailbox.SubmissionStatus.MAILBOX_FULL;
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);

        coordinator.start(route, request(route.technique(), 0L));

        assertEquals(TechniqueActionSubmission.Status.MAILBOX_FULL,
                route.portSubmission.status());
        assertTrue(route.port.completed(route.issuedPermit).isEmpty());
        TechniqueActionCancellation first = route.port.cancelOrContain(
                route.issuedPermit,
                TechniqueActionCancellationReason.REQUESTED, 0L);
        TechniqueActionCancellation second = route.port.cancelOrContain(
                route.issuedPermit,
                TechniqueActionCancellationReason.GENERATION_CHANGED, 0L);
        assertTrue(first.safelyRetracted());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(0, gateway.cancelCount,
                "a rejected Action never reaches runtime containment");
        assertEquals(1, route.reapedRunCount,
                "the coordinator must reap the rejected child after start unwinds");
        assertEquals(0, coordinator.activeRunCount(),
                "a rejected ingress must not survive until a later lifecycle pass");
        assertTrue(route.port.isGenerationSafe(BOT, 1L),
                "reaping a rejected child must release its retained permit binding");
    }

    @Test
    void productionPortCancelsItsOwnEnvelopeWhenAnotherQueuedEnvelopeSharesItsTriple() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        BotActionRuntime runtime = new BotActionRuntime(new SucceedingBackend(),
                8, 16, 8, 8, 8);
        ActionEnvelope competing = new ActionEnvelope(ACTION, BOT, 1L,
                "other/queued-collision", 10L, 10,
                new LookAtAction(4.0, 5.0, 6.0),
                io.github.greytaiwolf.botplayer.action.ActionOrigin.none());
        assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED,
                runtime.submit(competing, ActionPriority.OWNER_TASK).status());
        AdapterRoute route = AdapterRoute.withRuntime(coordinator, runtime);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));

        TechniqueActionCancellation cancellation = route.port.cancelOrContain(
                route.issuedPermit, TechniqueActionCancellationReason.REQUESTED,
                0L);
        assertTrue(cancellation.safelyRetracted(),
                "the retained permit must remove its own immutable envelope");

        runtime.tick(1L);
        assertEquals(ActionState.CANCELLED,
                runtime.completedOutcomeExact(route.envelope).orElseThrow().state(),
                "a preceding same-triple envelope must not receive this permit's receipt");
        coordinator.drainCompletedChildren(1L);
        assertEquals(1, route.acceptedSignals);
        assertEquals(TechniqueChildState.CANCELLED,
                route.acceptedSignalValues.getFirst().state());
    }

    @Test
    void synchronousSafetyPreemptAfterIngressContainsTheExactActionOnce() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        gateway.onSubmit = route::preemptForSafety;

        coordinator.start(route, request(route.technique(), 0L));

        assertEquals(1, gateway.cancelCount);
        assertTrue(route.cancellations.stream().allMatch(
                TechniqueActionCancellation::safelyRetracted));
        assertFalse(route.port.isGenerationSafe(BOT, 1L));

        gateway.complete(route.envelope, ActionState.PREEMPTED,
                ActionFailureCode.PREEMPTED);
        coordinator.drainCompletedChildren(1L);
        coordinator.finishTick(1L);
        assertEquals(1, route.acceptedSignals);
        assertEquals(TechniqueChildState.PREEMPTED,
                route.acceptedSignalValues.getFirst().state());
        assertTrue(route.port.isGenerationSafe(BOT, 1L));

        gateway.complete(route.envelope, ActionState.SUCCEEDED,
                ActionFailureCode.NONE);
        coordinator.drainCompletedChildren(2L);
        assertEquals(1, route.acceptedSignals,
                "a late success after L0 preemption cannot revive the child");
    }

    @Test
    void safeCancellationKeepsTheBindingUntilItsRealTerminalDrains() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));

        TechniqueActionCancellation first = route.port.cancelOrContain(
                route.issuedPermit,
                TechniqueActionCancellationReason.REQUESTED, 0L);
        TechniqueActionCancellation second = route.port.cancelOrContain(
                route.issuedPermit,
                TechniqueActionCancellationReason.SAFETY_PREEMPTION, 0L);
        assertTrue(first.safelyRetracted());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(1, gateway.cancelCount);
        assertFalse(route.port.isGenerationSafe(BOT, 1L),
                "safe containment alone is not a Technique child terminal");

        gateway.complete(route.envelope, ActionState.CANCELLED,
                ActionFailureCode.CANCELLED);
        coordinator.drainCompletedChildren(1L);

        assertEquals(1, route.acceptedSignals);
        assertEquals(TechniqueChildState.CANCELLED,
                route.acceptedSignalValues.getFirst().state());
        assertTrue(route.port.isGenerationSafe(BOT, 1L));
    }

    @Test
    void cancellationReceiptCacheIsBoundToTheOpaquePermitNotOnlyActionId() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));
        TechniqueActionPermit firstPermit = route.issuedPermit;
        route.port.cancelOrContain(firstPermit,
                TechniqueActionCancellationReason.REQUESTED, 0L);
        gateway.complete(route.envelope, ActionState.CANCELLED,
                ActionFailureCode.CANCELLED);
        coordinator.drainCompletedChildren(1L);

        coordinator.start(route, request(route.technique(), 2L));
        TechniqueActionPermit secondPermit = route.issuedPermit;
        assertNotSame(firstPermit, secondPermit);
        assertEquals(ACTION, secondPermit.actionId(),
                "the regression deliberately reuses the raw Action UUID");
        route.port.cancelOrContain(secondPermit,
                TechniqueActionCancellationReason.REQUESTED, 2L);

        assertEquals(2, gateway.cancelCount,
                "another permit with the same Action UUID needs its own containment proof");
    }

    @Test
    void anOutcomeForTheSameActionIdButAnotherEnvelopeCannotCompleteThePermit() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));

        ActionEnvelope oldEnvelope = new ActionEnvelope(ACTION, BOT, 2L,
                "other/action", 10L, 10,
                new LookAtAction(1.0, 2.0, 3.0),
                io.github.greytaiwolf.botplayer.action.ActionOrigin.none());
        gateway.complete(oldEnvelope, ActionState.SUCCEEDED,
                ActionFailureCode.NONE);
        coordinator.drainCompletedChildren(1L);
        assertEquals(0, route.acceptedSignals,
                "an action-id collision is not terminal proof for the permit");

        gateway.complete(route.envelope, ActionState.SUCCEEDED,
                ActionFailureCode.NONE);
        coordinator.drainCompletedChildren(2L);
        assertEquals(1, route.acceptedSignals);
    }

    @Test
    void unsafeContainmentNeverPretendsTheChildWasCancelled() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        gateway.cancellationReceipt = ActionCancellationReceipt.unsafe(BOT, 1L,
                ACTION, ActionCancellationReceipt.Disposition.STARTED);
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));

        TechniqueActionCancellation cancellation = route.port.cancelOrContain(
                route.issuedPermit,
                TechniqueActionCancellationReason.SAFETY_PREEMPTION, 0L);
        assertFalse(cancellation.safelyRetracted());
        assertFalse(route.port.isGenerationSafe(BOT, 1L));
        assertEquals(1, gateway.cancelCount);
        assertEquals(0, route.acceptedSignals);
    }

    @Test
    void controlActionOutcomesRetainTheirTechniqueChildTerminalMeaning() {
        assertTerminalMapping(ActionState.SUCCEEDED, ActionFailureCode.NONE,
                TechniqueChildState.SUCCEEDED, TechniqueFailureCode.NONE);
        assertTerminalMapping(ActionState.CANCELLED,
                ActionFailureCode.CANCELLED, TechniqueChildState.CANCELLED,
                TechniqueFailureCode.CANCELLED);
        assertTerminalMapping(ActionState.PREEMPTED,
                ActionFailureCode.PREEMPTED, TechniqueChildState.PREEMPTED,
                TechniqueFailureCode.PREEMPTED);
        assertTerminalMapping(ActionState.STALE,
                ActionFailureCode.STALE_GENERATION, TechniqueChildState.STALE,
                TechniqueFailureCode.GENERATION_CHANGED);
        assertTerminalMapping(ActionState.FAILED,
                ActionFailureCode.INTERNAL_ERROR, TechniqueChildState.FAILED,
                TechniqueFailureCode.ACTION_FAILED);
    }

    private static void assertTerminalMapping(ActionState actionState,
            ActionFailureCode actionFailure, TechniqueChildState expectedState,
            TechniqueFailureCode expectedFailure) {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingGateway gateway = new RecordingGateway();
        AdapterRoute route = AdapterRoute.withGateway(coordinator, gateway);
        coordinator.register(route);
        coordinator.start(route, request(route.technique(), 0L));
        gateway.complete(route.envelope, actionState, actionFailure);

        coordinator.drainCompletedChildren(1L);

        assertEquals(1, route.acceptedSignals);
        TechniqueSignal signal = route.acceptedSignalValues.getFirst();
        assertEquals(expectedState, signal.state());
        assertEquals(expectedFailure, signal.failureCode());
    }

    private static TechniqueStartRequest request(PlayerTechnique technique,
            long currentTick) {
        TechniqueDescriptor descriptor = technique.descriptor();
        return new TechniqueStartRequest(SKILL, BOT, 1L, descriptor.id(),
                descriptor.version(), TechniqueParameters.empty(), currentTick);
    }

    private static final class AdapterRoute extends TechniqueRoute {
        private static final PlayerTechnique TECHNIQUE = new PlayerTechnique() {
            private final TechniqueDescriptor descriptor = new TechniqueDescriptor(
                    new TechniqueId("botplayer", "contract/lifecycle-action"),
                    new TechniqueVersion(1, 0, 0), TechniqueRiskLevel.LOW,
                    20, 2, 1, 1, 0);

            @Override
            public TechniqueDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TechniqueDirective start(TechniqueContext context,
                    TechniqueParameters parameters) {
                return TechniqueDirective.awaitChildren("look", List.of(
                        new TechniqueChildRequest("adapter.look",
                                java.util.Set.of(ActionChannel.LOOK))));
            }

            @Override
            public TechniqueDirective tick(TechniqueContext context,
                    TechniqueRunView run) {
                return TechniqueDirective.continueRunning("look");
            }

            @Override
            public TechniqueDirective verify(TechniqueContext context,
                    TechniqueRunView run) {
                return TechniqueDirective.complete("look verified");
            }
        };

        private final TechniqueLifecycleCoordinator coordinator;
        private final LifecycleTechniqueActionPort port;
        private TechniqueActionPermit issuedPermit;
        private TechniqueActionPermit activePermit;
        private TechniqueChildTicket ticket;
        private TechniqueRunView run;
        private ActionEnvelope envelope;
        private TechniqueActionSubmission portSubmission;
        private final List<TechniqueActionCancellation> cancellations =
                new ArrayList<>();
        private final List<TechniqueSignal> acceptedSignalValues =
                new ArrayList<>();
        private int acceptedSignals;
        private int reapedRunCount;
        private long lastObservedTick = -1L;

        private static AdapterRoute withGateway(
                TechniqueLifecycleCoordinator coordinator,
                TechniqueActionRuntimeGateway gateway) {
            return new AdapterRoute(coordinator, gateway, null);
        }

        private static AdapterRoute withRuntime(
                TechniqueLifecycleCoordinator coordinator,
                BotActionRuntime runtime) {
            return new AdapterRoute(coordinator, null, runtime);
        }

        private AdapterRoute(TechniqueLifecycleCoordinator coordinator,
                TechniqueActionRuntimeGateway gateway, BotActionRuntime runtime) {
            this.coordinator = coordinator;
            port = gateway != null
                    ? new LifecycleTechniqueActionPort(coordinator, this,
                            gateway)
                    : new LifecycleTechniqueActionPort(coordinator, this,
                            runtime);
        }

        @Override
        PlayerTechnique technique() {
            return TECHNIQUE;
        }

        @Override
        void observeTick(long currentTick) {
            lastObservedTick = currentTick;
        }

        @Override
        TechniqueChildDispatcher.Submission submitChild(
                TechniqueChildTicket ticket, TechniqueRunView run) {
            this.ticket = ticket;
            this.run = run;
            envelope = new ActionEnvelope(ACTION, ticket.botId(),
                    ticket.botGeneration(),
                    TechniqueActionPermit.idempotencyKeyFor(ticket), 10L, 10,
                    new LookAtAction(1.0, 2.0, 3.0),
                    TechniqueActionPermit.originFor(run, ticket));
            issuedPermit = coordinator.prebindAction(this, run, ticket,
                    envelope, ActionPriority.OWNER_TASK);
            /* Install the route binding before the adapter may re-enter lifecycle. */
            activePermit = issuedPermit;
            portSubmission = port.submit(issuedPermit);
            return portSubmission.status()
                    == TechniqueActionSubmission.Status.ENQUEUED
                            ? TechniqueChildDispatcher.Submission.accepted(
                                    "Action ingress accepted")
                            : TechniqueChildDispatcher.Submission.rejected(
                                    TechniqueChildDispatcher.Status.REJECTED,
                                    "Action ingress rejected");
        }

        @Override
        boolean allowsActionKind(TechniqueChildTicket ticket, ActionKind kind) {
            return "adapter.look".equals(ticket.operationKey())
                    && kind == ActionKind.LOOK_AT;
        }

        @Override
        void cancelChild(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            if (activePermit != null && this.ticket != null
                    && this.ticket.equals(ticket)) {
                cancellations.add(port.cancelOrContain(activePermit,
                        cancellationReason(reason), lastObservedTick));
            }
        }

        @Override
        void drainCompletedChildren(long currentTick,
                TechniqueLifecycleCoordinator.SignalSink signals) {
            if (activePermit == null || ticket == null || run == null) {
                return;
            }
            TechniqueActionPermit drainingPermit = activePermit;
            port.completed(drainingPermit).ifPresent(terminal -> {
                ActionOutcome outcome = terminal.outcome();
                TerminalMapping mapping = terminalMapping(outcome);
                TechniqueSignal signal = new TechniqueSignal(
                        run.techniqueRunId(), ticket.ticketId(),
                        ticket.botId(), ticket.botGeneration(),
                        ticket.revision(), mapping.state(),
                        mapping.failureCode(),
                        "adapter terminal");
                if (signals.offer(this, signal, currentTick)
                        == TechniqueSignalStatus.ACCEPTED) {
                    acceptedSignals++;
                    acceptedSignalValues.add(signal);
                    if (activePermit == drainingPermit) {
                        port.release(drainingPermit);
                        activePermit = null;
                    }
                }
            });
        }

        @Override
        void onGenerationClosing(UUID botId, long botGeneration,
                long currentTick) {
            if (activePermit != null && activePermit.botId().equals(botId)
                    && activePermit.botGeneration() == botGeneration) {
                cancellations.add(port.cancelOrContain(activePermit,
                        TechniqueActionCancellationReason.GENERATION_CHANGED,
                        currentTick));
            }
        }

        @Override
        void closeIngress() {
            // This pure contract route has no independent ingress.
        }

        @Override
        boolean isRouteGenerationSafe(UUID botId, long botGeneration) {
            return port.isGenerationSafe(botId, botGeneration);
        }

        @Override
        void verifyRun(TechniqueRunView run) {
            assertEquals(TECHNIQUE.descriptor().id(), run.techniqueId());
            assertEquals(TECHNIQUE.descriptor().version(),
                    run.techniqueVersion());
        }

        @Override
        void reapTerminalRun(UUID techniqueRunId) {
            reapedRunCount++;
            if (activePermit != null && activePermit.techniqueRunId().equals(
                    techniqueRunId)) {
                port.release(activePermit);
                activePermit = null;
            }
        }

        private void preemptForSafety() {
            if (run == null) {
                throw new IllegalStateException(
                        "adapter test route had no run for safety preemption");
            }
            coordinator.preemptForSafety(this, run.techniqueRunId(), BOT, 1L,
                    lastObservedTick);
        }

        private static TechniqueActionCancellationReason cancellationReason(
                TechniqueCancelReason reason) {
            return switch (reason) {
                case REQUESTED -> TechniqueActionCancellationReason.REQUESTED;
                case SAFETY_PREEMPTION ->
                        TechniqueActionCancellationReason.SAFETY_PREEMPTION;
                case GENERATION_CHANGED ->
                        TechniqueActionCancellationReason.GENERATION_CHANGED;
                case SERVER_STOP ->
                        TechniqueActionCancellationReason.RUNTIME_SHUTDOWN;
            };
        }

        private static TerminalMapping terminalMapping(ActionOutcome outcome) {
            return switch (outcome.state()) {
                case SUCCEEDED -> new TerminalMapping(
                        TechniqueChildState.SUCCEEDED,
                        TechniqueFailureCode.NONE);
                case CANCELLED -> new TerminalMapping(
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED);
                case PREEMPTED -> new TerminalMapping(
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED);
                case STALE -> new TerminalMapping(TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED);
                case FAILED -> new TerminalMapping(TechniqueChildState.FAILED,
                        TechniqueFailureCode.ACTION_FAILED);
                case QUEUED, VALIDATING, RUNNING, VERIFYING ->
                        throw new IllegalArgumentException(
                                "Technique Action terminal was not terminal");
            };
        }

        private record TerminalMapping(TechniqueChildState state,
                TechniqueFailureCode failureCode) {
        }
    }

    private static final class RecordingGateway
            implements TechniqueActionRuntimeGateway {
        private ActionMailbox.SubmissionStatus submissionStatus =
                ActionMailbox.SubmissionStatus.ENQUEUED;
        private final Map<ActionEnvelope, ActionOutcome> outcomes =
                new java.util.LinkedHashMap<>();
        private ActionCancellationReceipt cancellationReceipt;
        private Runnable onSubmit;
        private int cancelCount;

        @Override
        public ActionMailbox.Submission submit(ActionEnvelope envelope,
                ActionPriority priority) {
            Runnable callback = onSubmit;
            if (callback != null) {
                callback.run();
            }
            return new ActionMailbox.Submission(submissionStatus,
                    submissionStatus == ActionMailbox.SubmissionStatus.ENQUEUED
                            ? Optional.of(new CompletableFuture<ActionOutcome>())
                            : Optional.empty());
        }

        @Override
        public ActionCancellationReceipt cancelOrContain(
                ActionEnvelope envelope, ActionCancellationReason reason,
                long currentTick) {
            cancelCount++;
            return cancellationReceipt == null
                    ? ActionCancellationReceipt.fencedBeforeStart(
                            envelope.botId(), envelope.botGeneration(),
                            envelope.actionId())
                    : cancellationReceipt;
        }

        @Override
        public Optional<ActionOutcome> completedOutcomeExact(
                ActionEnvelope expected) {
            return Optional.ofNullable(outcomes.get(expected));
        }

        private void complete(ActionEnvelope envelope, ActionState state,
                ActionFailureCode failureCode) {
            outcomes.put(envelope, new ActionOutcome(envelope.actionId(), state,
                    failureCode, 0L, 1L, List.of(), "synthetic terminal"));
        }
    }

    private static final class SucceedingBackend implements ActionBackend {
        @Override
        public ActionBackend.BackendResult validate(ActionEnvelope envelope,
                long currentTick) {
            return ActionBackend.BackendResult.accepted(envelope);
        }

        @Override
        public ActionBackend.BackendResult start(ActionEnvelope envelope,
                long currentTick) {
            return ActionBackend.BackendResult.readyToVerify(envelope);
        }

        @Override
        public ActionBackend.BackendResult tick(ActionEnvelope envelope,
                long startedTick, long currentTick) {
            return ActionBackend.BackendResult.readyToVerify(envelope);
        }

        @Override
        public ActionBackend.BackendResult verify(ActionEnvelope envelope,
                long currentTick) {
            return ActionBackend.BackendResult.succeeded(envelope,
                    List.of(new ActionEvidence("adapter", "verified")),
                    "verified");
        }

        @Override
        public void cleanup(ActionEnvelope envelope, ActionCleanupReason reason,
                long currentTick) {
            // The adapter test needs no physical backend cleanup side effect.
        }

        @Override
        public boolean forceSafeReset(UUID botId, long botGeneration,
                long currentTick) {
            return true;
        }
    }
}
