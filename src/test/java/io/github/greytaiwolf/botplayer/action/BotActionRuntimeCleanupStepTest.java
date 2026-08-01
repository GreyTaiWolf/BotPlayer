package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotActionRuntimeCleanupStepTest {
    private static final UUID BOT = new UUID(0L, 700L);

    @Test
    void pendingCleanupRetainsAuthorityAndCompletesOnAdvertisedTick() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(1L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request,
                                1L,
                                4L,
                                "等待授权 Tick"),
                        request -> ActionCleanupReceipt.complete(
                                request,
                                1L,
                                "安全端点")));
        ActionMailbox.Submission submission =
                runtime.submit(action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);

        ActionMailbox.Cancellation cancellation = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(2L);
        BotActionRuntime.GenerationCancellationResult repeated =
                runtime.cancelBotGenerationNow(
                        BOT,
                        1L,
                        ActionCancellationReason.LIFECYCLE,
                        2L);

        assertPending(runtime, action, submission);
        Assertions.assertFalse(cancelFuture(cancellation).isDone());
        Assertions.assertFalse(
                repeated.safeForExclusiveMutation());
        Assertions.assertTrue(repeated.ticketRemaining());
        Assertions.assertTrue(repeated.leaseRemaining());
        Assertions.assertEquals(1, backend.cleanupStepCount(
                action.actionId()));
        Assertions.assertEquals(
                action.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());

        runtime.tick(2L);
        runtime.tick(3L);
        Assertions.assertEquals(1, backend.cleanupStepCount(
                action.actionId()));
        assertPending(runtime, action, submission);

        runtime.tick(4L);

        ActionOutcome outcome = outcome(submission);
        Assertions.assertEquals(ActionState.CANCELLED, outcome.state());
        Assertions.assertEquals(4L, outcome.finishedTick());
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.CANCELLED,
                cancelStatus(cancellation));
        Assertions.assertEquals(0, runtime.activeActionCount());
        Assertions.assertEquals(0, runtime.activeLeaseCount());
        List<ActionCleanupRequest> requests =
                backend.cleanupRequests(action.actionId());
        Assertions.assertEquals(2, requests.size());
        Assertions.assertEquals(
                requests.get(0).cleanupId(),
                requests.get(1).cleanupId());
        Assertions.assertEquals(1, requests.get(0).attempt());
        Assertions.assertEquals(2, requests.get(1).attempt());
        Assertions.assertEquals(2L, requests.get(0).requestedTick());
        Assertions.assertEquals(4L, requests.get(1).currentTick());
        Assertions.assertEquals(
                ActionCleanupReason.CANCELLED,
                requests.get(0).reason());
        Assertions.assertEquals(
                ActionCleanupReason.CANCELLED,
                requests.get(1).reason());
        Assertions.assertEquals(0, backend.businessTickCount(
                action.actionId()));
        Assertions.assertEquals(0, backend.legacyCleanupCalls);
    }

    @Test
    void preemptorStartsOnlyAfterDisplacedCleanupCompletes() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope low = envelope(2L, 1L, 100L);
        ActionEnvelope high = stopEnvelope(3L, 1L, 100L);
        backend.script(
                low.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 3L, "等待旧 owner"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "旧 owner 已收口")));
        ActionMailbox.Submission lowSubmission =
                runtime.submit(low, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        ActionMailbox.Submission highSubmission =
                runtime.submit(high, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(0, backend.startCount(
                high.actionId()));
        Assertions.assertFalse(future(lowSubmission).isDone());
        Assertions.assertFalse(future(highSubmission).isDone());
        Assertions.assertEquals(
                low.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());

        runtime.tick(3L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(lowSubmission).state());
        Assertions.assertEquals(1, backend.startCount(
                high.actionId()));
        Assertions.assertEquals(
                high.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());
        int completeIndex = backend.events.indexOf(
                "cleanup:" + low.actionId() + ":COMPLETE");
        int startIndex = backend.events.indexOf(
                "start:" + high.actionId());
        Assertions.assertTrue(completeIndex >= 0);
        Assertions.assertTrue(startIndex > completeIndex);
    }

    @Test
    void unsafeDisplacedCleanupQuarantinesAndNeverStartsPreemptor() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope low = envelope(4L, 1L, 100L);
        ActionEnvelope high = stopEnvelope(5L, 1L, 100L);
        backend.script(
                low.actionId(),
                List.of(request -> ActionCleanupReceipt.unsafe(
                        request, 1L, "无法证明旧 owner 安全")));
        ActionMailbox.Submission lowSubmission =
                runtime.submit(low, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        ActionMailbox.Submission highSubmission =
                runtime.submit(high, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(lowSubmission).failureCode());
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(highSubmission).failureCode());
        Assertions.assertEquals(0, backend.startCount(
                high.actionId()));
        Assertions.assertEquals(0, backend.forceResetCalls);
        Assertions.assertEquals(0, runtime.activeLeaseCount());
        Assertions.assertEquals(
                ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
                runtime.submit(
                        envelope(6L, 1L, 100L),
                        ActionPriority.OWNER_TASK).status());
        ActionMailbox.Submission newer = runtime.submit(
                envelope(7L, 2L, 100L),
                ActionPriority.OWNER_TASK);
        Assertions.assertEquals(
                ActionMailbox.SubmissionStatus.ENQUEUED,
                newer.status());
        runtime.tick(3L);
        Assertions.assertEquals(1, backend.startCount(
                new UUID(0L, 7L)));
    }

    @Test
    void cancelWaitersCompleteOnlyAfterCleanupTerminal() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(8L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 4L, "等待取消收口"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "取消收口完成")));
        ActionMailbox.Submission submission =
                runtime.submit(action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        ActionMailbox.Cancellation first = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(2L);
        ActionMailbox.Cancellation second = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.LIFECYCLE);
        runtime.tick(3L);

        Assertions.assertFalse(cancelFuture(first).isDone());
        Assertions.assertFalse(cancelFuture(second).isDone());
        assertPending(runtime, action, submission);
        Assertions.assertEquals(1, backend.cleanupStepCount(
                action.actionId()));
        Assertions.assertEquals(2,
                runtime.pendingCancellationWaiterCount(
                        BOT, action.actionId()));

        runtime.tick(4L);

        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.CANCELLED,
                cancelStatus(first));
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.CANCELLED,
                cancelStatus(second));
        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(submission).state());
        ActionMailbox.Cancellation terminal = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(5L);
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.ALREADY_TERMINAL,
                cancelStatus(terminal));
        Assertions.assertEquals(2, backend.cleanupStepCount(
                action.actionId()));
    }

    @Test
    void deadlineOutcomeWaitsForCleanupAndIsNotOverwritten() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(9L, 1L, 2L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 4L, "等待 deadline 收口"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "deadline 收口完成")));
        ActionMailbox.Submission submission =
                runtime.submit(action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        runtime.tick(2L);
        ActionMailbox.Cancellation cancellation = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(3L);

        assertPending(runtime, action, submission);
        Assertions.assertFalse(cancelFuture(cancellation).isDone());
        runtime.tick(4L);

        ActionOutcome outcome = outcome(submission);
        Assertions.assertEquals(ActionState.FAILED, outcome.state());
        Assertions.assertEquals(
                ActionFailureCode.DEADLINE_EXCEEDED,
                outcome.failureCode());
        Assertions.assertEquals(4L, outcome.finishedTick());
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.ALREADY_TERMINAL,
                cancelStatus(cancellation));
        Assertions.assertEquals(0, backend.businessTickCount(
                action.actionId()));
        List<ActionCleanupRequest> requests =
                backend.cleanupRequests(action.actionId());
        Assertions.assertEquals(2, requests.size());
        Assertions.assertEquals(
                ActionCleanupReason.DEADLINE_EXCEEDED,
                requests.get(0).reason());
        Assertions.assertEquals(
                ActionCleanupReason.DEADLINE_EXCEEDED,
                requests.get(1).reason());
        Assertions.assertEquals(2L,
                requests.get(0).requestedTick());
        Assertions.assertEquals(2L,
                requests.get(1).requestedTick());
    }

    @Test
    void shutdownFailsClosedWhenCleanupNeedsAFutureTick() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(10L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 3L, "等待 shutdown 收口")));
        ActionMailbox.Submission submission =
                runtime.submit(action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);

        runtime.shutdown(2L);

        ActionOutcome shutdownOutcome = outcome(submission);
        Assertions.assertEquals(ActionState.FAILED,
                shutdownOutcome.state());
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                shutdownOutcome.failureCode());
        Assertions.assertEquals(2L, shutdownOutcome.finishedTick());
        Assertions.assertEquals(
                ActionMailbox.SubmissionStatus.RUNTIME_CLOSED,
                runtime.submit(
                        envelope(11L, 2L, 100L),
                        ActionPriority.OWNER_TASK).status());
        Assertions.assertEquals(0, runtime.activeActionCount());
        Assertions.assertEquals(0, runtime.activeLeaseCount());
        Assertions.assertEquals(1, backend.cleanupStepCount(
                action.actionId()));
        Assertions.assertEquals(0, backend.forceResetCalls);
    }

    @Test
    void reentrantLifecycleCloseRejectsTheOldStartResult() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(14L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 2L, "等待重入收口"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "重入收口完成")));
        backend.readyOnStart(action.actionId());
        backend.onStart(action.actionId(), () ->
                runtime.cancelBotNow(
                        BOT,
                        1L,
                        ActionCancellationReason.LIFECYCLE,
                        1L));
        ActionMailbox.Submission submission = runtime.submit(
                action, ActionPriority.OWNER_TASK);

        runtime.tick(1L);

        assertPending(runtime, action, submission);
        Assertions.assertEquals(0, backend.verifyCount(
                action.actionId()));
        Assertions.assertEquals(1, backend.cleanupStepCount(
                action.actionId()));

        runtime.tick(2L);

        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(submission).state());
        Assertions.assertEquals(0, backend.verifyCount(
                action.actionId()));
        Assertions.assertEquals(0, runtime.activeLeaseCount());
    }

    @Test
    void queuedAliasCancellationWaitsForCanonicalCleanup() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope canonical = envelopeWithKey(
                15L, 1L, "shared-cancel-key", 100L);
        backend.script(
                canonical.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 4L, "等待别名取消收口"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "别名取消收口完成")));
        ActionMailbox.Submission canonicalSubmission = runtime.submit(
                canonical, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        ActionEnvelope alias = envelopeWithKey(
                16L, 1L, "shared-cancel-key", 100L);
        ActionMailbox.Submission aliasSubmission = runtime.submit(
                alias, ActionPriority.OWNER_TASK);
        ActionMailbox.Cancellation cancellation = runtime.cancel(
                BOT,
                alias.actionId(),
                ActionCancellationReason.REQUESTED);

        runtime.tick(2L);

        assertPending(runtime, canonical, canonicalSubmission);
        Assertions.assertFalse(future(aliasSubmission).isDone());
        Assertions.assertFalse(cancelFuture(cancellation).isDone());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, alias.actionId()).isEmpty());
        Assertions.assertEquals(1, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(1,
                runtime.pendingCancellationWaiterCount(
                        BOT, canonical.actionId()));

        runtime.tick(3L);
        Assertions.assertFalse(future(aliasSubmission).isDone());
        Assertions.assertFalse(cancelFuture(cancellation).isDone());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, canonical.actionId()).isEmpty());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, alias.actionId()).isEmpty());
        Assertions.assertEquals(1, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(1,
                runtime.pendingCancellationWaiterCount(
                        BOT, canonical.actionId()));
        runtime.tick(4L);

        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(canonicalSubmission).state());
        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(aliasSubmission).state());
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.CANCELLED,
                cancelStatus(cancellation));
        Assertions.assertEquals(0, runtime.activeLeaseCount());
    }

    @Test
    void highestPreemptionClaimWinsAfterSynchronousCleanup() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope old = envelope(17L, 1L, 100L);
        ActionEnvelope emergency = stopEnvelope(18L, 1L, 100L);
        ActionEnvelope owner = envelope(19L, 1L, 100L);
        ActionMailbox.Submission oldSubmission = runtime.submit(
                old, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        runtime.submit(emergency, ActionPriority.EMERGENCY);
        ActionMailbox.Submission ownerSubmission = runtime.submit(
                owner, ActionPriority.OWNER_TASK);

        runtime.tick(2L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(oldSubmission).state());
        Assertions.assertEquals(1, backend.startCount(
                emergency.actionId()));
        Assertions.assertEquals(0, backend.startCount(
                owner.actionId()));
        Assertions.assertEquals(
                ActionFailureCode.CHANNEL_BUSY,
                outcome(ownerSubmission).failureCode());
        Assertions.assertEquals(
                emergency.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());
    }

    @Test
    void claimReservesFreedChannelsUntilEveryOldOwnerIsSafe() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope oldLook = envelope(27L, 1L, 100L);
        ActionEnvelope oldMove = waitEnvelope(28L, 1L, 100L);
        ActionEnvelope emergency = stopEnvelope(29L, 1L, 100L);
        ActionEnvelope ownerLook = envelope(30L, 1L, 100L);
        backend.script(
                oldMove.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 1L, 4L, "等待第二个旧 owner"),
                        request -> ActionCleanupReceipt.complete(
                                request, 1L, "第二个旧 owner 已收口")));
        ActionMailbox.Submission lookSubmission = runtime.submit(
                oldLook, ActionPriority.AUTONOMOUS);
        ActionMailbox.Submission moveSubmission = runtime.submit(
                oldMove, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        ActionMailbox.Submission emergencySubmission = runtime.submit(
                emergency, ActionPriority.EMERGENCY);
        ActionMailbox.Submission ownerSubmission = runtime.submit(
                ownerLook, ActionPriority.OWNER_TASK);

        runtime.tick(2L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(lookSubmission).state());
        Assertions.assertFalse(future(moveSubmission).isDone());
        Assertions.assertFalse(future(emergencySubmission).isDone());
        Assertions.assertFalse(future(ownerSubmission).isDone());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, emergency.actionId()).isEmpty());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, ownerLook.actionId()).isEmpty());
        Assertions.assertEquals(3, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(
                oldMove.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.MOVE)
                        .orElseThrow());
        Assertions.assertEquals(0, backend.startCount(
                emergency.actionId()));
        Assertions.assertEquals(0, backend.startCount(
                ownerLook.actionId()));
        Assertions.assertTrue(runtime.activeLeaseOwner(
                BOT, ActionChannel.LOOK).isEmpty());

        runtime.tick(3L);
        Assertions.assertFalse(future(emergencySubmission).isDone());
        Assertions.assertFalse(future(ownerSubmission).isDone());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, emergency.actionId()).isEmpty());
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, ownerLook.actionId()).isEmpty());
        Assertions.assertEquals(3, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(
                oldMove.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.MOVE)
                        .orElseThrow());
        runtime.tick(4L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(moveSubmission).state());
        Assertions.assertEquals(1, backend.startCount(
                emergency.actionId()));
        Assertions.assertEquals(0, backend.startCount(
                ownerLook.actionId()));
        Assertions.assertEquals(
                ActionFailureCode.CHANNEL_BUSY,
                outcome(ownerSubmission).failureCode());
        Assertions.assertEquals(
                emergency.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());
    }

    @Test
    void unsafeEarlyCandidateDoesNotPreemptLaterIndependentOwner() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope oldLook = envelope(35L, 1L, 100L);
        ActionEnvelope oldMove = waitEnvelope(36L, 2L, 100L);
        ActionEnvelope claimant = stopEnvelope(37L, 3L, 100L);
        backend.script(
                oldLook.actionId(),
                List.of(request -> ActionCleanupReceipt.unsafe(
                        request, 1L, "首个旧 owner 无法安全收口")));
        ActionMailbox.Submission lookSubmission = runtime.submit(
                oldLook, ActionPriority.AUTONOMOUS);
        ActionMailbox.Submission moveSubmission = runtime.submit(
                oldMove, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        ActionMailbox.Submission claimantSubmission = runtime.submit(
                claimant, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(lookSubmission).failureCode());
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(claimantSubmission).failureCode());
        Assertions.assertFalse(future(moveSubmission).isDone());
        Assertions.assertEquals(0, backend.startCount(
                claimant.actionId()));
        Assertions.assertEquals(0, backend.cleanupStepCount(
                oldMove.actionId()));
        Assertions.assertEquals(1, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(
                oldMove.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.MOVE)
                        .orElseThrow());
        Assertions.assertTrue(runtime.activeLeaseOwner(
                BOT, ActionChannel.LOOK).isEmpty());
    }

    @Test
    void safelyReleasedCandidateSnapshotIsNotQuarantined() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope oldLook = envelope(38L, 1L, 100L);
        ActionEnvelope oldMove = waitEnvelope(39L, 2L, 100L);
        ActionEnvelope claimant = stopEnvelope(40L, 3L, 100L);
        backend.script(
                oldLook.actionId(),
                List.of(request -> {
                    runtime.cancelBotNow(
                            BOT,
                            2L,
                            ActionCancellationReason.LIFECYCLE,
                            2L);
                    return ActionCleanupReceipt.complete(
                            request, 1L,
                            "重入关闭已安全释放后续 owner");
                }));
        ActionMailbox.Submission lookSubmission = runtime.submit(
                oldLook, ActionPriority.AUTONOMOUS);
        ActionMailbox.Submission moveSubmission = runtime.submit(
                oldMove, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        runtime.submit(claimant, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(lookSubmission).state());
        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(moveSubmission).state());
        Assertions.assertEquals(1, backend.startCount(
                claimant.actionId()));
        Assertions.assertEquals(0L, runtime.cleanupFailureCount());
        Assertions.assertTrue(runtime.isGenerationSafe(BOT, 2L));
        Assertions.assertEquals(
                claimant.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());
        Assertions.assertEquals(
                claimant.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.MOVE)
                        .orElseThrow());
    }

    @Test
    void overflowQueuedAliasCannotCancelCanonicalAction() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = new BotActionRuntime(
                backend, 256, 128, 256, 16, 256);
        ActionEnvelope canonical = envelopeWithKey(
                1000L, 1L, "full-alias-set", 100L);
        ActionMailbox.Submission canonicalSubmission = runtime.submit(
                canonical, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        List<ActionMailbox.Submission> aliases = new ArrayList<>();
        for (long actionId = 1001L; actionId <= 1063L;
                actionId++) {
            ActionMailbox.Submission alias = runtime.submit(
                    envelopeWithKey(
                            actionId,
                            1L,
                            "full-alias-set",
                            100L),
                    ActionPriority.OWNER_TASK);
            Assertions.assertEquals(
                    ActionMailbox.SubmissionStatus.ENQUEUED,
                    alias.status());
            aliases.add(alias);
        }
        runtime.tick(2L);
        ActionEnvelope overflow = envelopeWithKey(
                1064L, 1L, "full-alias-set", 100L);
        ActionMailbox.Submission overflowSubmission = runtime.submit(
                overflow, ActionPriority.OWNER_TASK);
        ActionMailbox.Cancellation overflowCancellation = runtime.cancel(
                BOT,
                overflow.actionId(),
                ActionCancellationReason.REQUESTED);

        runtime.tick(3L);

        ActionOutcome overflowOutcome = outcome(overflowSubmission);
        Assertions.assertEquals(ActionState.FAILED,
                overflowOutcome.state());
        Assertions.assertEquals(
                ActionFailureCode.DUPLICATE_IN_PROGRESS,
                overflowOutcome.failureCode());
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.ALREADY_TERMINAL,
                cancelStatus(overflowCancellation));
        Assertions.assertFalse(future(canonicalSubmission).isDone());
        aliases.forEach(alias -> Assertions.assertFalse(
                future(alias).isDone()));
        Assertions.assertTrue(runtime.completedOutcome(
                BOT, overflow.actionId()).isEmpty());
        Assertions.assertEquals(0, backend.cleanupStepCount(
                canonical.actionId()));
        Assertions.assertEquals(1, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(
                canonical.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());
    }

    @Test
    void sameTickFifoAllowsFirstStepBeforeEmergencyPreemption() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope ordinary = envelope(20L, 1L, 100L);
        ActionEnvelope emergency = stopEnvelope(21L, 1L, 100L);
        ActionMailbox.Submission ordinarySubmission = runtime.submit(
                ordinary, ActionPriority.OWNER_TASK);
        runtime.submit(emergency, ActionPriority.EMERGENCY);

        runtime.tick(1L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(ordinarySubmission).state());
        Assertions.assertEquals(1, backend.startCount(
                ordinary.actionId()));
        Assertions.assertEquals(1, backend.startCount(
                emergency.actionId()));
        Assertions.assertTrue(
                backend.events.indexOf("start:" + ordinary.actionId())
                        < backend.events.indexOf(
                                "start:" + emergency.actionId()));
    }

    @Test
    void startCallbackCannotCrossAnUnsafeGenerationBarrier() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope old = envelope(22L, 1L, 100L);
        ActionEnvelope replacement = stopEnvelope(23L, 2L, 100L);
        ActionMailbox.Submission oldSubmission = runtime.submit(
                old, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        backend.onStart(replacement.actionId(), () ->
                runtime.quarantineBotGenerationNow(BOT, 1L, 2L));
        ActionMailbox.Submission replacementSubmission = runtime.submit(
                replacement, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(oldSubmission).state());
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(replacementSubmission).failureCode());
        Assertions.assertEquals(1, backend.startCount(
                replacement.actionId()));
        Assertions.assertEquals(0, backend.businessTickCount(
                replacement.actionId()));
        Assertions.assertEquals(0, runtime.activeLeaseCount());
    }

    @Test
    void displacedCleanupCannotResurrectACancelledClaimant() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope old = envelope(31L, 1L, 100L);
        ActionEnvelope claimant = stopEnvelope(32L, 2L, 100L);
        backend.script(
                old.actionId(),
                List.of(request -> {
                    runtime.cancelBotNow(
                            BOT,
                            2L,
                            ActionCancellationReason.LIFECYCLE,
                            2L);
                    return ActionCleanupReceipt.complete(
                            request, 1L, "清理回调关闭 claimant");
                }));
        ActionMailbox.Submission oldSubmission = runtime.submit(
                old, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        ActionMailbox.Submission claimantSubmission = runtime.submit(
                claimant, ActionPriority.EMERGENCY);

        Assertions.assertDoesNotThrow(() -> runtime.tick(2L));

        Assertions.assertEquals(ActionState.PREEMPTED,
                outcome(oldSubmission).state());
        Assertions.assertEquals(ActionState.CANCELLED,
                outcome(claimantSubmission).state());
        Assertions.assertEquals(0, backend.startCount(
                claimant.actionId()));
        Assertions.assertEquals(0, runtime.activeActionCount());
        Assertions.assertEquals(0, runtime.activeLeaseCount());
    }

    @Test
    void reentrantQuarantineCannotBeOverwrittenBySafetyRecovery() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(33L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(request -> ActionCleanupReceipt.unsafe(
                        request, 1L, "先进入隔离")));
        ActionMailbox.Submission submission = runtime.submit(
                action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(2L);
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(submission).failureCode());
        backend.onForceReset(() ->
                runtime.quarantineBotGenerationNow(BOT, 1L, 3L));

        Assertions.assertFalse(runtime.recoverBotSafety(
                BOT, 1L, 3L));

        Assertions.assertFalse(runtime.isGenerationSafe(BOT, 1L));
        Assertions.assertEquals(
                ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
                runtime.submit(
                        envelope(34L, 1L, 100L),
                        ActionPriority.OWNER_TASK).status());
        Assertions.assertEquals(1, backend.forceResetCalls);
    }

    @Test
    void orphanedLeaseStaysClosedUntilExplicitSafeReset()
            throws ReflectiveOperationException {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope old = envelope(24L, 1L, 100L);
        ActionMailbox.Submission oldSubmission = runtime.submit(
                old, ActionPriority.AUTONOMOUS);
        runtime.tick(1L);
        clearTicketLeasePointer(runtime);
        ActionEnvelope waiter = stopEnvelope(25L, 2L, 100L);
        ActionMailbox.Submission waiterSubmission = runtime.submit(
                waiter, ActionPriority.EMERGENCY);

        runtime.tick(2L);

        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(oldSubmission).failureCode());
        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(waiterSubmission).failureCode());
        Assertions.assertEquals(0, backend.startCount(
                waiter.actionId()));
        Assertions.assertEquals(1, runtime.activeLeaseCount());
        Assertions.assertEquals(
                old.actionId(),
                runtime.activeLeaseOwner(BOT, ActionChannel.LOOK)
                        .orElseThrow());

        Assertions.assertTrue(runtime.recoverBotSafety(
                BOT, 1L, 3L));
        Assertions.assertEquals(1, backend.forceResetCalls);
        Assertions.assertEquals(0, runtime.activeLeaseCount());
        ActionEnvelope afterRecovery = envelope(26L, 2L, 100L);
        runtime.submit(afterRecovery, ActionPriority.OWNER_TASK);
        runtime.tick(4L);
        Assertions.assertEquals(1, backend.startCount(
                afterRecovery.actionId()));
    }

    @Test
    void revisionRegressionFailsClosedWithoutForceReset() {
        CleanupScriptBackend backend = new CleanupScriptBackend();
        BotActionRuntime runtime = runtime(backend);
        ActionEnvelope action = envelope(12L, 1L, 100L);
        backend.script(
                action.actionId(),
                List.of(
                        request -> ActionCleanupReceipt.pending(
                                request, 3L, 3L, "已有物理进度"),
                        request -> ActionCleanupReceipt.complete(
                                request, 2L, "伪造回退")));
        ActionMailbox.Submission submission =
                runtime.submit(action, ActionPriority.OWNER_TASK);
        runtime.tick(1L);
        ActionMailbox.Cancellation cancellation = runtime.cancel(
                BOT,
                action.actionId(),
                ActionCancellationReason.REQUESTED);
        runtime.tick(2L);
        runtime.tick(3L);

        Assertions.assertEquals(
                ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(submission).failureCode());
        Assertions.assertEquals(1L, runtime.cleanupFailureCount());
        Assertions.assertEquals(0, backend.forceResetCalls);
        List<ActionCleanupRequest> requests =
                backend.cleanupRequests(action.actionId());
        Assertions.assertEquals(2, requests.size());
        Assertions.assertEquals(1, requests.get(0).attempt());
        Assertions.assertEquals(2, requests.get(1).attempt());
        Assertions.assertEquals(
                ActionCleanupReason.CANCELLED,
                requests.get(0).reason());
        Assertions.assertEquals(
                ActionCleanupReason.CANCELLED,
                requests.get(1).reason());
        Assertions.assertEquals(
                ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
                runtime.submit(
                        envelope(13L, 1L, 100L),
                        ActionPriority.OWNER_TASK).status());
        Assertions.assertEquals(
                ActionMailbox.CancellationStatus.CLEANUP_FAILED,
                cancelStatus(cancellation));
    }

    private static void assertPending(
            BotActionRuntime runtime,
            ActionEnvelope action,
            ActionMailbox.Submission submission) {
        Assertions.assertFalse(future(submission).isDone());
        Assertions.assertTrue(runtime.completedOutcome(
                action.botId(), action.actionId()).isEmpty());
        Assertions.assertEquals(1, runtime.activeActionCount());
        Assertions.assertEquals(1, runtime.activeLeaseCount());
    }

    private static BotActionRuntime runtime(
            CleanupScriptBackend backend) {
        return new BotActionRuntime(
                backend, 32, 64, 32, 16, 32);
    }

    private static ActionEnvelope envelope(
            long actionId, long generation, long deadline) {
        return envelopeWithKey(
                actionId,
                generation,
                "cleanup-runtime-" + actionId,
                deadline);
    }

    private static ActionEnvelope envelopeWithKey(
            long actionId,
            long generation,
            String idempotencyKey,
            long deadline) {
        return new ActionEnvelope(
                new UUID(0L, actionId),
                BOT,
                generation,
                idempotencyKey,
                deadline,
                20,
                new LookAtAction(1.0D, 2.0D, 3.0D),
                ActionOrigin.none());
    }

    @SuppressWarnings("unchecked")
    private static void clearTicketLeasePointer(
            BotActionRuntime runtime)
            throws ReflectiveOperationException {
        java.lang.reflect.Field activeField =
                BotActionRuntime.class.getDeclaredField("active");
        activeField.setAccessible(true);
        Map<Object, Object> active =
                (Map<Object, Object>) activeField.get(runtime);
        Object ticket = active.values().iterator().next();
        java.lang.reflect.Field leaseField =
                ticket.getClass().getDeclaredField("lease");
        leaseField.setAccessible(true);
        leaseField.set(ticket, null);
    }

    private static ActionEnvelope stopEnvelope(
            long actionId, long generation, long deadline) {
        return new ActionEnvelope(
                new UUID(0L, actionId),
                BOT,
                generation,
                "cleanup-stop-" + actionId,
                deadline,
                20,
                new StopAction(),
                ActionOrigin.none());
    }

    private static ActionEnvelope waitEnvelope(
            long actionId, long generation, long deadline) {
        return new ActionEnvelope(
                new UUID(0L, actionId),
                BOT,
                generation,
                "cleanup-wait-" + actionId,
                deadline,
                20,
                new WaitAction(20),
                ActionOrigin.none());
    }

    private static java.util.concurrent.CompletableFuture<ActionOutcome>
            future(ActionMailbox.Submission submission) {
        return submission.completion().orElseThrow()
                .toCompletableFuture();
    }

    private static java.util.concurrent.CompletableFuture<
            ActionMailbox.CancellationStatus> cancelFuture(
                    ActionMailbox.Cancellation cancellation) {
        return cancellation.completion().orElseThrow()
                .toCompletableFuture();
    }

    private static ActionOutcome outcome(
            ActionMailbox.Submission submission) {
        return future(submission).join();
    }

    private static ActionMailbox.CancellationStatus cancelStatus(
            ActionMailbox.Cancellation cancellation) {
        return cancelFuture(cancellation).join();
    }

    private static final class CleanupScriptBackend
            implements ActionBackend {
        private final Map<UUID, ArrayDeque<Function<
                ActionCleanupRequest, ActionCleanupReceipt>>> scripts =
                new HashMap<>();
        private final Map<UUID, List<ActionCleanupRequest>> requests =
                new HashMap<>();
        private final Map<UUID, Integer> starts = new HashMap<>();
        private final Map<UUID, Integer> businessTicks =
                new HashMap<>();
        private final Map<UUID, Integer> verifies = new HashMap<>();
        private final Map<UUID, Runnable> startHooks = new HashMap<>();
        private final Set<UUID> readyOnStart = new HashSet<>();
        private Runnable forceResetHook;
        private final List<String> events = new ArrayList<>();
        private int legacyCleanupCalls;
        private int forceResetCalls;

        private void script(
                UUID actionId,
                List<Function<ActionCleanupRequest,
                        ActionCleanupReceipt>> responders) {
            scripts.put(actionId, new ArrayDeque<>(responders));
        }

        private void onStart(UUID actionId, Runnable hook) {
            startHooks.put(actionId, hook);
        }

        private void readyOnStart(UUID actionId) {
            readyOnStart.add(actionId);
        }

        private void onForceReset(Runnable hook) {
            forceResetHook = hook;
        }

        @Override
        public BackendResult validate(
                ActionEnvelope envelope, long currentTick) {
            return BackendResult.accepted(envelope);
        }

        @Override
        public BackendResult start(
                ActionEnvelope envelope, long currentTick) {
            starts.merge(envelope.actionId(), 1, Integer::sum);
            events.add("start:" + envelope.actionId());
            Runnable hook = startHooks.remove(envelope.actionId());
            if (hook != null) {
                hook.run();
            }
            return readyOnStart.contains(envelope.actionId())
                    ? BackendResult.readyToVerify(envelope)
                    : BackendResult.running(envelope);
        }

        @Override
        public BackendResult tick(
                ActionEnvelope envelope,
                long startedTick,
                long currentTick) {
            businessTicks.merge(
                    envelope.actionId(), 1, Integer::sum);
            return BackendResult.running(envelope);
        }

        @Override
        public BackendResult verify(
                ActionEnvelope envelope, long currentTick) {
            verifies.merge(envelope.actionId(), 1, Integer::sum);
            return BackendResult.succeeded(
                    envelope, List.of(), "完成");
        }

        @Override
        public ActionCleanupReceipt cleanupStep(
                ActionEnvelope envelope,
                ActionCleanupRequest request) {
            requests.computeIfAbsent(
                    envelope.actionId(),
                    ignored -> new ArrayList<>()).add(request);
            ArrayDeque<Function<ActionCleanupRequest,
                    ActionCleanupReceipt>> queue =
                    scripts.get(envelope.actionId());
            ActionCleanupReceipt receipt = queue == null
                    || queue.isEmpty()
                    ? ActionCleanupReceipt.complete(
                            request, 0L, "默认安全收口")
                    : queue.removeFirst().apply(request);
            events.add("cleanup:" + envelope.actionId()
                    + ":" + receipt.status());
            return receipt;
        }

        @Override
        public void cleanup(
                ActionEnvelope envelope,
                ActionCleanupReason reason,
                long currentTick) {
            legacyCleanupCalls++;
        }

        @Override
        public boolean forceSafeReset(
                UUID botId,
                long botGeneration,
                long currentTick) {
            forceResetCalls++;
            if (forceResetHook != null) {
                Runnable hook = forceResetHook;
                forceResetHook = null;
                hook.run();
            }
            return true;
        }

        private int cleanupStepCount(UUID actionId) {
            return requests.getOrDefault(
                    actionId, List.of()).size();
        }

        private List<ActionCleanupRequest> cleanupRequests(
                UUID actionId) {
            return List.copyOf(requests.getOrDefault(
                    actionId, List.of()));
        }

        private int startCount(UUID actionId) {
            return starts.getOrDefault(actionId, 0);
        }

        private int businessTickCount(UUID actionId) {
            return businessTicks.getOrDefault(actionId, 0);
        }

        private int verifyCount(UUID actionId) {
            return verifies.getOrDefault(actionId, 0);
        }
    }
}
