package io.github.greytaiwolf.botplayer.technique.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TechniqueRuntimeTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID SKILL_RUN_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final TechniqueId ID = new TechniqueId("botplayer",
            "test_technique");
    private static final TechniqueVersion VERSION = new TechniqueVersion(1, 0, 0);

    @Test
    void exactChildIdentityMustSettleBeforeTheTechniqueCanComplete() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("move", List.of(
                        new TechniqueChildRequest("move.step", Set.of(
                                ActionChannel.MOVE))))),
                List.of(TechniqueDirective.complete("Movement technique succeeded")));
        TechniqueRuntime runtime = runtime(dispatcher, technique);

        UUID runId = start(runtime, 0L);
        TechniqueRunView waiting = runtime.inspectRun(runId).orElseThrow();
        assertEquals(TechniqueState.WAITING_CHILDREN, waiting.state());
        TechniqueChildTicket ticket = only(dispatcher.submitted);
        assertEquals(TechniqueSignalStatus.IDENTITY_MISMATCH,
                runtime.offerSignal(signal(ticket, BOT_ID, 2L,
                        TechniqueChildState.SUCCEEDED, TechniqueFailureCode.NONE), 1L));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.SUCCEEDED, TechniqueFailureCode.NONE), 1L));
        assertEquals(TechniqueState.RUNNING,
                runtime.inspectRun(runId).orElseThrow().state());

        runtime.tick(BOT_ID, 1L, 2L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.SUCCEEDED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.NONE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void conflictingChannelsFailClosedBeforeAnyChildIsDispatched() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("conflict", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.MOVE))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);

        UUID runId = start(runtime, 0L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertTrue(dispatcher.submitted.isEmpty());
        assertEquals(TechniqueFailureCode.CHANNEL_CONFLICT,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void deadlineWaitsForChildCleanupBeforeItRecordsTimeout() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(descriptor(2, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("holding", List.of(
                        new TechniqueChildRequest("hold", Set.of(ActionChannel.OFF_HAND))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);

        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);
        runtime.tick(BOT_ID, 1L, 2L);
        assertEquals(TechniqueState.CANCELLING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(List.of(ticket), dispatcher.cancelled);
        assertTrue(runtime.latestOutcome(BOT_ID).isEmpty());

        runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                TechniqueChildState.CANCELLED, TechniqueFailureCode.CANCELLED), 2L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.TIMEOUT,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void safetyPreemptionPreservesItsTerminalReasonAcrossChildAcknowledgement() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = waitingTechnique(ActionChannel.MAIN_HAND);
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(1, dispatcher.cancelled.size());
        runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                TechniqueChildState.PREEMPTED, TechniqueFailureCode.PREEMPTED), 1L);

        assertEquals(TechniqueState.PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.SAFETY_PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
        assertEquals(TechniqueSignalStatus.RUN_NOT_FOUND,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED), 2L));
    }

    @Test
    void safetyPreemptionUpgradesAnOutstandingCancellationAtTheChildPort() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.MAIN_HAND));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.CANCELLING,
                runtime.inspectRun(runId).orElseThrow().state());

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(List.of(ticket, ticket), dispatcher.cancelled);
        assertEquals(List.of(
                TechniqueCancelReason.REQUESTED,
                TechniqueCancelReason.SAFETY_PREEMPTION),
                dispatcher.cancellationReasons);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED), 1L));
        assertEquals(TechniqueState.PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.SAFETY_PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void cancellationCannotDowngradeAnOutstandingSafetyPreemption() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.MAIN_HAND));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.cancel(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(List.of(ticket), dispatcher.cancelled);
        assertEquals(List.of(TechniqueCancelReason.SAFETY_PREEMPTION),
                dispatcher.cancellationReasons);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), 1L));
        assertEquals(TechniqueState.PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.SAFETY_PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void childlessCancellationAndSafetyPreemptionUseLegalEndingTransitions() {
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.continueRunning("running")), List.of());

        RecordingDispatcher cancelledDispatcher = new RecordingDispatcher();
        TechniqueRuntime cancelledRuntime = runtime(cancelledDispatcher,
                technique);
        UUID cancelledRun = start(cancelledRuntime, 0L);
        assertEquals(TechniqueCancellationStatus.CANCELLING,
                cancelledRuntime.cancel(cancelledRun, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.CANCELLED,
                cancelledRuntime.latestOutcome(BOT_ID).orElseThrow().state());

        RecordingDispatcher preemptedDispatcher = new RecordingDispatcher();
        TechniqueRuntime preemptedRuntime = runtime(preemptedDispatcher,
                new ScriptedTechnique(descriptor(20, 8, 4, 2, 1),
                        List.of(TechniqueDirective.continueRunning("running")),
                        List.of()));
        UUID preemptedRun = start(preemptedRuntime, 0L);
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                preemptedRuntime.preemptForSafety(preemptedRun, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTED,
                preemptedRuntime.latestOutcome(BOT_ID).orElseThrow().state());
    }

    @Test
    void missingCleanupReceiptFailsBoundedlyAndQuarantinesOnlyThatGeneration() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.OFF_HAND));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, 1L));
        long cleanupDeadline = 1L + TechniqueRuntime.MAX_CLEANUP_TICKS;
        runtime.tick(BOT_ID, 1L, cleanupDeadline);
        assertEquals(TechniqueState.CANCELLING,
                runtime.inspectRun(runId).orElseThrow().state());

        runtime.tick(BOT_ID, 2L, cleanupDeadline + 1L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
        assertEquals(TechniqueChildState.ACTIVE, ticket.state());
        assertEquals(TechniqueSignalStatus.RUN_NOT_FOUND,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), cleanupDeadline + 2L));
        assertEquals(TechniqueSubmission.Status.GENERATION_QUARANTINED,
                startSubmission(runtime, 1L, cleanupDeadline + 1L).status());
        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                startSubmission(runtime, 2L, cleanupDeadline + 1L).status());
    }

    @Test
    void generationCloseCancelsActiveChildrenAndFailsWithGenerationCode() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.INTERACT));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        runtime.closeGeneration(BOT_ID, 1L, 1L);
        assertEquals(TechniqueState.CANCELLING,
                runtime.inspectRun(runId).orElseThrow().state());
        runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                TechniqueChildState.STALE,
                TechniqueFailureCode.GENERATION_CHANGED), 1L);
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void runtimeEnforcesDescriptorBudgetsAndRejectsInvalidChildRequests() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique childrenOverBudget = new ScriptedTechnique(
                descriptor(20, 8, 1, 2, 1),
                List.of(TechniqueDirective.awaitChildren("too-many", List.of(
                        new TechniqueChildRequest("one", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("two", Set.of(ActionChannel.LOOK))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, childrenOverBudget);
        UUID runId = start(runtime, 0L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueFailureCode.BUDGET_EXCEEDED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());

        assertThrows(IllegalArgumentException.class, () -> new TechniqueChildRequest(
                "bad", Set.of()));
        assertNotEquals(ID, new TechniqueId("botplayer", "other"));
        assertFalse(dispatcher.submitted.size() > 0);
    }

    @Test
    void synchronousChildAcknowledgementsAreRegisteredBeforeDispatchAndDoNotSplitABatch() {
        ReentrantSuccessDispatcher dispatcher = new ReentrantSuccessDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("two-fast-children", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK))))),
                List.of(TechniqueDirective.complete("Both synchronous children settled")));
        TechniqueRuntime runtime = new TechniqueRuntime(new TechniqueRuntimeLimits(
                4, 4, 4), dispatcher);
        dispatcher.runtime = runtime;
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(technique));

        UUID runId = start(runtime, 0L);
        assertEquals(List.of(TechniqueSignalStatus.ACCEPTED,
                        TechniqueSignalStatus.ACCEPTED),
                dispatcher.signalStatuses);
        assertEquals(2, dispatcher.submitted.size());
        assertEquals(TechniqueState.RUNNING,
                runtime.inspectRun(runId).orElseThrow().state());

        runtime.tick(BOT_ID, 1L, 1L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.SUCCEEDED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
    }

    @Test
    void staleChildSignalCannotMutateTheTicketBeforeTheTickIsRejected() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.MAIN_HAND));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 2L));
        assertEquals(TechniqueSignalStatus.STALE_TICK,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED), 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(TechniqueChildState.ACTIVE,
                runtime.inspectRun(runId).orElseThrow().childTickets().get(0).state());

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED), 2L));
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
    }

    @Test
    void repeatedInStateDirectivesAdvanceTicksWithoutIllegalSelfTransitions() {
        RecordingDispatcher continueDispatcher = new RecordingDispatcher();
        TechniqueRuntime continueRuntime = runtime(continueDispatcher,
                new ScriptedTechnique(descriptor(20, 8, 4, 2, 1),
                        List.of(TechniqueDirective.continueRunning("steady")),
                        List.of(TechniqueDirective.continueRunning("steady"),
                                TechniqueDirective.continueRunning("steady"))));
        UUID continueRun = start(continueRuntime, 0L);
        continueRuntime.tick(BOT_ID, 1L, 1L);
        continueRuntime.tick(BOT_ID, 1L, 2L);
        assertEquals(TechniqueState.RUNNING,
                continueRuntime.inspectRun(continueRun).orElseThrow().state());
        assertTrue(continueRuntime.latestOutcome(BOT_ID).isEmpty());
        assertEquals(TechniqueCancellationStatus.STALE_TICK,
                continueRuntime.cancel(continueRun, BOT_ID, 1L, 1L));

        RecordingDispatcher verifyDispatcher = new RecordingDispatcher();
        TechniqueRuntime verifyRuntime = runtime(verifyDispatcher,
                new RepeatingVerifyTechnique());
        UUID verifyRun = start(verifyRuntime, 0L);
        verifyRuntime.tick(BOT_ID, 1L, 1L);
        verifyRuntime.tick(BOT_ID, 1L, 2L);
        assertEquals(TechniqueState.VERIFYING,
                verifyRuntime.inspectRun(verifyRun).orElseThrow().state());
        assertTrue(verifyRuntime.latestOutcome(BOT_ID).isEmpty());

        RecordingDispatcher recoverDispatcher = new RecordingDispatcher();
        TechniqueRuntime recoverRuntime = runtime(recoverDispatcher,
                new ScriptedTechnique(descriptor(20, 8, 4, 2, 4),
                        List.of(TechniqueDirective.recover("recover"),
                                TechniqueDirective.recover("recover"),
                                TechniqueDirective.recover("recover")),
                        List.of()));
        UUID recoverRun = start(recoverRuntime, 0L);
        recoverRuntime.tick(BOT_ID, 1L, 1L);
        recoverRuntime.tick(BOT_ID, 1L, 2L);
        assertEquals(TechniqueState.RECOVERING,
                recoverRuntime.inspectRun(recoverRun).orElseThrow().state());
        assertTrue(recoverRuntime.latestOutcome(BOT_ID).isEmpty());
    }

    @Test
    void reentrantSafetyEscalationNeverSendsAWeakerReasonAfterSafety() {
        ReentrantSafetyDispatcher dispatcher = new ReentrantSafetyDispatcher(1L);
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("two", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK))))),
                List.of());
        TechniqueRuntime runtime = new TechniqueRuntime(new TechniqueRuntimeLimits(
                4, 4, 4), dispatcher);
        dispatcher.runtime = runtime;
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(technique));

        UUID runId = start(runtime, 0L);
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.cancel(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        for (TechniqueChildTicket ticket : dispatcher.submitted) {
            List<TechniqueCancelReason> reasons = dispatcher.reasonsFor(ticket);
            assertEquals(TechniqueCancelReason.SAFETY_PREEMPTION,
                    reasons.get(reasons.size() - 1));
            int safetyIndex = reasons.indexOf(
                    TechniqueCancelReason.SAFETY_PREEMPTION);
            assertTrue(safetyIndex >= 0);
            assertFalse(reasons.subList(safetyIndex + 1, reasons.size())
                    .contains(TechniqueCancelReason.REQUESTED));
        }
    }

    @Test
    void cleanupWatchdogFailsAtTheLastRepresentableServerTick() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                new ScriptedTechnique(descriptor(1, 8, 4, 2, 1),
                        List.of(TechniqueDirective.awaitChildren("holding", List.of(
                                new TechniqueChildRequest("hold", Set.of(
                                        ActionChannel.OFF_HAND))))),
                        List.of()));
        long startTick = Long.MAX_VALUE - TechniqueRuntime.MAX_CLEANUP_TICKS;
        UUID runId = start(runtime, startTick);
        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, startTick));

        runtime.tick(BOT_ID, 1L, Long.MAX_VALUE);
        runtime.finishTick(BOT_ID, 1L, Long.MAX_VALUE);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void cleanupStartedAtMaxTickFailsWithoutWaitingForAnImpossibleNextTick() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                new ScriptedTechnique(descriptor(1, 8, 4, 2, 1),
                        List.of(TechniqueDirective.awaitChildren("holding", List.of(
                                new TechniqueChildRequest("hold", Set.of(
                                        ActionChannel.OFF_HAND))))),
                        List.of()));
        UUID runId = start(runtime, Long.MAX_VALUE - 1L);

        runtime.tick(BOT_ID, 1L, Long.MAX_VALUE);
        runtime.finishTick(BOT_ID, 1L, Long.MAX_VALUE);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void saturatedCleanupAcceptsEveryExactSameTickAcknowledgementBeforeTickSettlement() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("two", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        long startTick = Long.MAX_VALUE - TechniqueRuntime.MAX_CLEANUP_TICKS;
        UUID runId = start(runtime, startTick);
        TechniqueChildTicket first = dispatcher.submitted.get(0);
        TechniqueChildTicket second = dispatcher.submitted.get(1);

        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, startTick));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(first, BOT_ID, 1L,
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), Long.MAX_VALUE));
        assertTrue(runtime.inspectRun(runId).isPresent());
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(second, BOT_ID, 1L,
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), Long.MAX_VALUE));
        runtime.finishTick(BOT_ID, 1L, Long.MAX_VALUE);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueState.CANCELLED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
    }

    @Test
    void saturatedCleanupFailsAtTheExplicitBoundaryAfterAnInvalidSignal() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.OFF_HAND));
        long startTick = Long.MAX_VALUE - TechniqueRuntime.MAX_CLEANUP_TICKS;
        UUID runId = start(runtime, startTick);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, startTick));
        TechniqueSignal unknown = new TechniqueSignal(runId,
                UUID.randomUUID(), BOT_ID, 1L, ticket.revision(),
                TechniqueChildState.CANCELLED, TechniqueFailureCode.CANCELLED,
                "Unknown child");
        assertEquals(TechniqueSignalStatus.TICKET_NOT_FOUND,
                runtime.offerSignal(unknown, Long.MAX_VALUE));
        runtime.finishTick(BOT_ID, 1L, Long.MAX_VALUE);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void finishTickGenerationMismatchCannotLeaveAnOldChildRunActive() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.OFF_HAND));
        UUID runId = start(runtime, 0L);

        runtime.finishTick(BOT_ID, 2L, 1L);
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void safetyPreemptionOutranksAnOrdinaryFailureButNotUnsafeCleanup() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(1, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("holding", List.of(
                        new TechniqueChildRequest("hold", Set.of(ActionChannel.MAIN_HAND))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        runtime.tick(BOT_ID, 1L, 1L);
        assertEquals(TechniqueState.CANCELLING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueState.PREEMPTING,
                runtime.inspectRun(runId).orElseThrow().state());
        assertEquals(1, technique.safetyPreemptionNotifications);

        runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                TechniqueChildState.PREEMPTED,
                TechniqueFailureCode.PREEMPTED), 1L);
        assertEquals(TechniqueState.PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.SAFETY_PREEMPTED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());

        RecordingDispatcher unsafeDispatcher = new RecordingDispatcher();
        TechniqueRuntime unsafeRuntime = runtime(unsafeDispatcher,
                waitingTechnique(ActionChannel.INTERACT));
        UUID unsafeRun = start(unsafeRuntime, 0L);
        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                unsafeRuntime.preemptForSafety(unsafeRun, BOT_ID, 1L, 1L));
        unsafeRuntime.tick(BOT_ID, 1L,
                1L + TechniqueRuntime.MAX_CLEANUP_TICKS + 1L);
        assertEquals(TechniqueState.FAILED,
                unsafeRuntime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                unsafeRuntime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void generationClosureOutranksSafetyAndReachesTheChildPort() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.INTERACT));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        runtime.closeGeneration(BOT_ID, 1L, 1L);
        assertEquals(List.of(
                TechniqueCancelReason.SAFETY_PREEMPTION,
                TechniqueCancelReason.GENERATION_CHANGED),
                dispatcher.cancellationReasons);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 1L));
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void generationClosureCannotBeDowngradedIntoASafetyCallback() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = waitingTechnique(ActionChannel.INTERACT);
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        runtime.closeGeneration(BOT_ID, 1L, 1L);
        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(0, technique.safetyPreemptionNotifications);
        assertEquals(List.of(TechniqueCancelReason.GENERATION_CHANGED),
                dispatcher.cancellationReasons);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 1L));
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void endpointUpgradeAdvancesTheMonotonicSignalBoundary() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.INTERACT));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        runtime.closeGeneration(BOT_ID, 1L, 5L);
        assertEquals(TechniqueSignalStatus.STALE_TICK,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 2L));
        assertEquals(TechniqueChildState.ACTIVE,
                runtime.inspectRun(runId).orElseThrow().childTickets()
                        .get(0).state());

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 5L));
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void authoritativeChildFailureSupersedesSafetyAndUpgradesLiveSiblingReason() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("two", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket first = dispatcher.submitted.get(0);
        TechniqueChildTicket second = dispatcher.submitted.get(1);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(first, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 1L));
        assertEquals(TechniqueCancelReason.GENERATION_CHANGED,
                dispatcher.cancellationReasons.get(
                        dispatcher.cancellationReasons.size() - 1));

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(second, BOT_ID, 1L,
                        TechniqueChildState.STALE,
                        TechniqueFailureCode.GENERATION_CHANGED), 1L));
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void unsafeChildCleanupProofSupersedesAnEarlierSafetyPreemption() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("two", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK))))),
                List.of());
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket first = dispatcher.submitted.get(0);
        TechniqueChildTicket second = dispatcher.submitted.get(1);

        assertEquals(TechniqueCancellationStatus.PREEMPTING,
                runtime.preemptForSafety(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(first, BOT_ID, 1L,
                        TechniqueChildState.FAILED,
                        TechniqueFailureCode.ACTION_CLEANUP_UNSAFE), 1L));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(second, BOT_ID, 1L,
                        TechniqueChildState.PREEMPTED,
                        TechniqueFailureCode.PREEMPTED), 1L));
        assertEquals(TechniqueState.FAILED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
    }

    @Test
    void generationSafetyIncludesLiveRunsAndUnsafeChildQuarantine() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = runtime(dispatcher,
                waitingTechnique(ActionChannel.MAIN_HAND));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        assertFalse(runtime.isGenerationSafe(BOT_ID, 1L));
        assertTrue(runtime.isGenerationSafe(BOT_ID, 2L));

        runtime.cancel(runId, BOT_ID, 1L, 1L);
        runtime.tick(BOT_ID, 1L,
                1L + TechniqueRuntime.MAX_CLEANUP_TICKS + 1L);

        assertFalse(runtime.isGenerationSafe(BOT_ID, 1L));
        assertEquals(TechniqueSubmission.Status.GENERATION_QUARANTINED,
                startSubmission(runtime, 1L,
                        1L + TechniqueRuntime.MAX_CLEANUP_TICKS + 2L)
                        .status());
        assertEquals(TechniqueChildState.ACTIVE, ticket.state());
    }

    @Test
    void shutdownClosesIngressAndUsesServerStopForLiveChildren() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ScriptedTechnique technique = waitingTechnique(ActionChannel.MAIN_HAND);
        TechniqueRuntime runtime = runtime(dispatcher, technique);
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket ticket = only(dispatcher.submitted);

        runtime.shutdown(1L);

        assertTrue(runtime.isClosed());
        assertEquals(List.of(TechniqueCancelReason.SERVER_STOP),
                dispatcher.cancellationReasons);
        assertEquals(TechniqueSubmission.Status.RUNTIME_CLOSED,
                startSubmission(runtime, 2L, 1L).status());
        assertEquals(TechniqueRegistrationStatus.RUNTIME_CLOSED,
                runtime.register(new ScriptedTechnique(
                        descriptor(20, 8, 4, 2, 1), List.of(), List.of())));
        assertFalse(runtime.isGenerationSafe(BOT_ID, 1L));

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                        TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), 1L));
        assertTrue(runtime.inspectRun(runId).isEmpty());
        assertEquals(TechniqueFailureCode.RUNTIME_CLOSED,
                runtime.latestOutcome(BOT_ID).orElseThrow().failureCode());
        assertTrue(runtime.isGenerationSafe(BOT_ID, 1L));
    }

    @Test
    void reentrantSiblingAcknowledgementIsNotCancelledAgainFromASnapshot() {
        ReentrantSiblingAcknowledgementDispatcher dispatcher =
                new ReentrantSiblingAcknowledgementDispatcher();
        ScriptedTechnique technique = new ScriptedTechnique(
                descriptor(20, 8, 4, 3, 1),
                List.of(TechniqueDirective.awaitChildren("three", List.of(
                        new TechniqueChildRequest("first", Set.of(ActionChannel.MOVE)),
                        new TechniqueChildRequest("second", Set.of(ActionChannel.LOOK)),
                        new TechniqueChildRequest("third", Set.of(ActionChannel.INTERACT))))),
                List.of());
        TechniqueRuntime runtime = new TechniqueRuntime(new TechniqueRuntimeLimits(
                4, 4, 4), dispatcher);
        dispatcher.runtime = runtime;
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(technique));
        UUID runId = start(runtime, 0L);
        TechniqueChildTicket first = dispatcher.submitted.get(0);
        TechniqueChildTicket second = dispatcher.submitted.get(1);
        TechniqueChildTicket third = dispatcher.submitted.get(2);

        assertEquals(TechniqueCancellationStatus.CANCELLING,
                runtime.cancel(runId, BOT_ID, 1L, 1L));
        assertEquals(TechniqueSignalStatus.ACCEPTED,
                dispatcher.siblingAcknowledgement);
        assertTrue(dispatcher.settledSiblingId != null);
        assertFalse(dispatcher.cancelledTicketIds.contains(
                dispatcher.settledSiblingId));
        assertTrue(dispatcher.cancelledTicketIds.contains(
                dispatcher.triggerTicketId));
        for (TechniqueChildTicket ticket : List.of(first, second, third)) {
            if (!ticket.ticketId().equals(dispatcher.settledSiblingId)) {
                assertEquals(TechniqueSignalStatus.ACCEPTED,
                        runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                                TechniqueChildState.CANCELLED,
                                TechniqueFailureCode.CANCELLED), 1L));
            }
        }
        assertEquals(TechniqueState.CANCELLED,
                runtime.latestOutcome(BOT_ID).orElseThrow().state());
    }

    private static TechniqueRuntime runtime(RecordingDispatcher dispatcher,
            PlayerTechnique technique) {
        TechniqueRuntime runtime = new TechniqueRuntime(new TechniqueRuntimeLimits(
                4, 4, 4), dispatcher);
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(technique));
        return runtime;
    }

    private static UUID start(TechniqueRuntime runtime, long tick) {
        TechniqueSubmission submission = startSubmission(runtime, 1L, tick);
        assertEquals(TechniqueSubmission.Status.ACCEPTED, submission.status());
        return submission.techniqueRunId().orElseThrow();
    }

    private static TechniqueSubmission startSubmission(TechniqueRuntime runtime,
            long generation, long tick) {
        return runtime.start(new TechniqueStartRequest(SKILL_RUN_ID, BOT_ID,
                generation, ID, VERSION, TechniqueParameters.empty(), tick));
    }

    private static TechniqueChildTicket only(List<TechniqueChildTicket> values) {
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static TechniqueSignal signal(TechniqueChildTicket ticket,
            UUID botId, long generation, TechniqueChildState state,
            TechniqueFailureCode code) {
        return new TechniqueSignal(ticket.techniqueRunId(), ticket.ticketId(),
                botId, generation, ticket.revision(), state, code,
                "Child acknowledgement");
    }

    private static ScriptedTechnique waitingTechnique(ActionChannel channel) {
        return new ScriptedTechnique(descriptor(20, 8, 4, 2, 1),
                List.of(TechniqueDirective.awaitChildren("awaiting", List.of(
                        new TechniqueChildRequest("child", Set.of(channel))))),
                List.of());
    }

    private static TechniqueDescriptor descriptor(int ticks, int phases,
            int submitted, int active, int recoveries) {
        return new TechniqueDescriptor(ID, VERSION, TechniqueRiskLevel.LOW,
                ticks, phases, submitted, active, recoveries);
    }

    private static final class RecordingDispatcher
            implements TechniqueChildDispatcher {
        private final List<TechniqueChildTicket> submitted = new ArrayList<>();
        private final List<TechniqueChildTicket> cancelled = new ArrayList<>();
        private final List<TechniqueCancelReason> cancellationReasons =
                new ArrayList<>();

        @Override
        public Submission submit(TechniqueChildTicket ticket) {
            submitted.add(ticket);
            return Submission.accepted("Child accepted");
        }

        @Override
        public void cancel(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            cancelled.add(ticket);
            cancellationReasons.add(reason);
        }
    }

    private static final class ReentrantSuccessDispatcher
            implements TechniqueChildDispatcher {
        private final List<TechniqueChildTicket> submitted = new ArrayList<>();
        private final List<TechniqueSignalStatus> signalStatuses = new ArrayList<>();
        private TechniqueRuntime runtime;

        @Override
        public Submission submit(TechniqueChildTicket ticket) {
            submitted.add(ticket);
            signalStatuses.add(runtime.offerSignal(signal(ticket, BOT_ID, 1L,
                    TechniqueChildState.SUCCEEDED, TechniqueFailureCode.NONE),
                    ticket.submittedTick()));
            return Submission.accepted("Child synchronously completed");
        }

        @Override
        public void cancel(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            // Each child settles synchronously before cancellation can be needed.
        }
    }

    private static final class ReentrantSafetyDispatcher
            implements TechniqueChildDispatcher {
        private final List<TechniqueChildTicket> submitted = new ArrayList<>();
        private final List<CancelCall> cancellationCalls = new ArrayList<>();
        private final long safetyTick;
        private TechniqueRuntime runtime;
        private boolean safetyRequested;

        private ReentrantSafetyDispatcher(long safetyTick) {
            this.safetyTick = safetyTick;
        }

        @Override
        public Submission submit(TechniqueChildTicket ticket) {
            submitted.add(ticket);
            return Submission.accepted("Child accepted");
        }

        @Override
        public void cancel(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            cancellationCalls.add(new CancelCall(ticket.ticketId(), reason));
            if (!safetyRequested && reason == TechniqueCancelReason.REQUESTED) {
                safetyRequested = true;
                runtime.preemptForSafety(ticket.techniqueRunId(), BOT_ID, 1L,
                        safetyTick);
            }
        }

        private List<TechniqueCancelReason> reasonsFor(
                TechniqueChildTicket ticket) {
            return cancellationCalls.stream()
                    .filter(call -> call.ticketId().equals(ticket.ticketId()))
                    .map(CancelCall::reason)
                    .toList();
        }
    }

    /**
     * Simulates a port in which cancelling the first child synchronously
     * delivers a terminal receipt for a different child. The runtime must not
     * subsequently pass that already-terminal sibling back to the port from a
     * stale active-ticket snapshot.
     */
    private static final class ReentrantSiblingAcknowledgementDispatcher
            implements TechniqueChildDispatcher {
        private final List<TechniqueChildTicket> submitted = new ArrayList<>();
        private final List<UUID> cancelledTicketIds = new ArrayList<>();
        private TechniqueRuntime runtime;
        private TechniqueSignalStatus siblingAcknowledgement;
        private UUID triggerTicketId;
        private UUID settledSiblingId;
        private boolean siblingSettled;

        @Override
        public Submission submit(TechniqueChildTicket ticket) {
            submitted.add(ticket);
            return Submission.accepted("Child accepted");
        }

        @Override
        public void cancel(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            cancelledTicketIds.add(ticket.ticketId());
            if (!siblingSettled
                    && reason == TechniqueCancelReason.REQUESTED
                    && submitted.size() >= 2) {
                siblingSettled = true;
                triggerTicketId = ticket.ticketId();
                TechniqueChildTicket sibling = submitted.stream()
                        .filter(candidate -> !candidate.ticketId().equals(
                                ticket.ticketId()))
                        .findFirst()
                        .orElseThrow();
                settledSiblingId = sibling.ticketId();
                siblingAcknowledgement = runtime.offerSignal(signal(sibling,
                        BOT_ID, 1L, TechniqueChildState.CANCELLED,
                        TechniqueFailureCode.CANCELLED), 1L);
            }
        }
    }

    private record CancelCall(UUID ticketId, TechniqueCancelReason reason) {
    }

    private static class ScriptedTechnique implements PlayerTechnique {
        private final TechniqueDescriptor descriptor;
        private final ArrayDeque<TechniqueDirective> start;
        private final ArrayDeque<TechniqueDirective> tick;
        private int safetyPreemptionNotifications;

        private ScriptedTechnique(TechniqueDescriptor descriptor,
                List<TechniqueDirective> start,
                List<TechniqueDirective> tick) {
            this.descriptor = descriptor;
            this.start = new ArrayDeque<>(start);
            this.tick = new ArrayDeque<>(tick);
        }

        @Override
        public TechniqueDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public TechniqueDirective start(TechniqueContext context,
                TechniqueParameters parameters) {
            return start.isEmpty()
                    ? TechniqueDirective.continueRunning("running")
                    : start.removeFirst();
        }

        @Override
        public TechniqueDirective tick(TechniqueContext context,
                TechniqueRunView run) {
            return tick.isEmpty()
                    ? TechniqueDirective.continueRunning("running")
                    : tick.removeFirst();
        }

        @Override
        public TechniqueDirective verify(TechniqueContext context,
                TechniqueRunView run) {
            return TechniqueDirective.complete("Technique verified");
        }

        @Override
        public void safetyPreempted(TechniqueContext context,
                TechniqueRunView run) {
            safetyPreemptionNotifications++;
        }
    }

    private static final class RepeatingVerifyTechnique implements PlayerTechnique {
        @Override
        public TechniqueDescriptor descriptor() {
            return TechniqueRuntimeTest.descriptor(20, 8, 4, 2, 1);
        }

        @Override
        public TechniqueDirective start(TechniqueContext context,
                TechniqueParameters parameters) {
            return TechniqueDirective.verify("verify");
        }

        @Override
        public TechniqueDirective tick(TechniqueContext context,
                TechniqueRunView run) {
            return TechniqueDirective.continueRunning("running");
        }

        @Override
        public TechniqueDirective verify(TechniqueContext context,
                TechniqueRunView run) {
            return TechniqueDirective.verify("verify");
        }
    }
}
