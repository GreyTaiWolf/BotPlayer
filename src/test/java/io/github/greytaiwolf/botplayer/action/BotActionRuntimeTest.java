package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotActionRuntimeTest {
   private static final UUID FIRST_BOT = new UUID(0L, 100L);
   private static final UUID SECOND_BOT = new UUID(0L, 200L);

   @Test
   void completesAndReplaysWithoutRepeatingTheSideEffect() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "same", new LookAtAction(1.0, 2.0, 3.0), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      ActionOutcome var5 = outcome(var4);
      Assertions.assertEquals(ActionState.SUCCEEDED, var5.state());
      Assertions.assertEquals(1, var1.startCount(var3.actionId()));
      Assertions.assertEquals(1, var1.cleanupCount(var3.actionId()));
      ActionEnvelope var6 = envelope(FIRST_BOT, 2L, "same", var3.action(), 200L, 10);
      ActionMailbox.Submission var7 = var2.submit(var6, ActionPriority.OWNER_TASK);
      var2.tick(2L);
      Assertions.assertSame(var5, outcome(var7));
      Assertions.assertEquals(var3.actionId(), outcome(var7).actionId());
      Assertions.assertEquals(1, var1.totalStarts());
      Assertions.assertEquals(1, var2.ledgerSize());
      Assertions.assertSame(var5, var2.completedOutcome(FIRST_BOT, var6.actionId()).orElseThrow());
   }

   @Test
   void joinsAnInProgressIdempotentRetryToTheCanonicalOutcome() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "wait", new WaitAction(3), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      Assertions.assertFalse(future(var4).toCompletableFuture().isDone());
      ActionEnvelope var5 = envelope(FIRST_BOT, 2L, "wait", new WaitAction(3), 200L, 10);
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.OWNER_TASK);
      var2.tick(2L);
      Assertions.assertFalse(future(var6).toCompletableFuture().isDone());
      var2.tick(3L);
      var2.tick(4L);
      ActionOutcome var7 = outcome(var4);
      Assertions.assertSame(var7, outcome(var6));
      Assertions.assertEquals(var3.actionId(), var7.actionId());
      Assertions.assertEquals(1, var1.totalStarts());
   }

   @Test
   void retryActionIdCancelsAndQueriesTheCanonicalInProgressAction() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "alias-cancel", new WaitAction(20), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      ActionEnvelope var5 = envelope(FIRST_BOT, 2L, "alias-cancel", var3.action(), 100L, 20);
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.OWNER_TASK);
      var2.tick(2L);
      ActionMailbox.Cancellation var7 = var2.cancel(FIRST_BOT, var5.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(3L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var7));
      Assertions.assertSame(outcome(var4), outcome(var6));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var6).state());
      Assertions.assertSame(outcome(var4), var2.completedOutcome(FIRST_BOT, var5.actionId()).orElseThrow());
      ActionMailbox.Cancellation var8 = var2.cancel(FIRST_BOT, var5.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(4L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.ALREADY_TERMINAL, cancellationStatus(var8));
      Assertions.assertEquals(1, var1.cleanupCount(var3.actionId()));
   }

   @Test
   void higherPriorityActionPreemptsAndCleansTheDisplacedOwner() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "look", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.AUTONOMOUS);
      var2.tick(1L);
      ActionEnvelope var5 = envelope(FIRST_BOT, 2L, "stop", new StopAction(), 100L, 2);
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.OWNER_CONTROL);
      var2.tick(2L);
      Assertions.assertEquals(ActionState.PREEMPTED, outcome(var4).state());
      Assertions.assertEquals(ActionFailureCode.PREEMPTED, outcome(var4).failureCode());
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var6).state());
      Assertions.assertEquals(1, var1.cleanupCount(var3.actionId()));
      Assertions.assertEquals(1, var1.cleanupCount(var5.actionId()));
      Assertions.assertEquals(0, var2.activeLeaseCount());
   }

   @Test
   void tiedAndLowerPriorityActionsFailWithoutTouchingTheBackend() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "owner", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      var1.runForever(var3.actionId());
      var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      ActionEnvelope var4 = envelope(FIRST_BOT, 2L, "lower", new LookAtAction(2.0, 3.0, 4.0), 100L, 20);
      ActionMailbox.Submission var5 = var2.submit(var4, ActionPriority.AUTONOMOUS);
      var2.tick(2L);
      Assertions.assertEquals(ActionState.FAILED, outcome(var5).state());
      Assertions.assertEquals(ActionFailureCode.CHANNEL_BUSY, outcome(var5).failureCode());
      Assertions.assertEquals(0, var1.startCount(var4.actionId()));
      Assertions.assertEquals(0, var1.cleanupCount(var4.actionId()));
   }

   @Test
   void cancellationIsIdempotentAndCleanupRunsOnce() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "cancel", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      ActionMailbox.Cancellation var5 = var2.cancel(FIRST_BOT, var3.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(2L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var5));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var4).state());
      ActionMailbox.Cancellation var6 = var2.cancel(FIRST_BOT, var3.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(3L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.ALREADY_TERMINAL, cancellationStatus(var6));
      Assertions.assertEquals(1, var1.cleanupCount(var3.actionId()));
      Assertions.assertEquals(0, var2.activeActionCount());
   }

   @Test
   void cancellationAtomicallyRemovesAnActionThatIsStillQueued() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "queued-cancel", new StopAction(), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Cancellation var5 = var2.cancel(FIRST_BOT, var3.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(1L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var5));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var4).state());
      Assertions.assertEquals(0, var1.startCount(var3.actionId()));
      Assertions.assertEquals(0, var1.cleanupCount(var3.actionId()));
   }

   @Test
   void cancellingAQueuedAliasPreservesAndCancelsTheFirstCanonicalEnvelope() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "queued-alias", new WaitAction(10), 100L, 20);
      ActionEnvelope var4 = envelope(FIRST_BOT, 2L, "queued-alias", var3.action(), 100L, 20);
      ActionMailbox.Submission var5 = var2.submit(var3, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission var6 = var2.submit(var4, ActionPriority.OWNER_TASK);
      ActionMailbox.Cancellation var7 = var2.cancel(FIRST_BOT, var4.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(1L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var7));
      Assertions.assertEquals(var3.actionId(), outcome(var5).actionId());
      Assertions.assertSame(outcome(var5), outcome(var6));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var6).state());
      Assertions.assertEquals(0, var1.totalStarts());
      ActionMailbox.Cancellation var8 = var2.cancel(FIRST_BOT, var4.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(2L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.ALREADY_TERMINAL, cancellationStatus(var8));
      Assertions.assertSame(outcome(var5), var2.completedOutcome(FIRST_BOT, var4.actionId()).orElseThrow());
   }

   @Test
   void queuedAliasCancellationRespectsThePerTickCommandBudget() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 16, 16, 1, 16, 16);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "budgeted-cancel", new StopAction(), 100L, 5);
      ArrayList<ActionMailbox.Submission> var4 = new ArrayList<>();
      var4.add(var2.submit(var3, ActionPriority.OWNER_CONTROL));

      for (long var5 = 2L; var5 <= 6L; var5++) {
         var4.add(var2.submit(envelope(FIRST_BOT, var5, "budgeted-cancel", var3.action(), 100L, 5), ActionPriority.OWNER_CONTROL));
      }

      ActionMailbox.Cancellation var10 = var2.cancel(FIRST_BOT, actionId(6L), ActionCancellationReason.REQUESTED);
      BotActionRuntime.TickReport var6 = var2.tick(1L);
      Assertions.assertEquals(1, var6.drainedCommands());
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var10));
      ActionOutcome var7 = outcome(var4.getFirst());
      Assertions.assertEquals(ActionState.CANCELLED, var7.state());

      for (int var8 = 1; var8 < var4.size(); var8++) {
         Assertions.assertFalse(future(var4.get(var8)).toCompletableFuture().isDone());
      }

      Assertions.assertEquals(0, var1.totalStarts());

      for (long var11 = 2L; var11 <= 6L; var11++) {
         Assertions.assertEquals(1, var2.tick(var11).drainedCommands());
      }

      for (ActionMailbox.Submission var9 : var4) {
         Assertions.assertSame(var7, outcome(var9));
      }

      Assertions.assertSame(var7, var2.completedOutcome(FIRST_BOT, actionId(6L)).orElseThrow());
      Assertions.assertEquals(0, var1.totalStarts());
   }

   @Test
   void enforcesDeadlineAndExecutionTickBudget() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "expired", new LookAtAction(1.0, 2.0, 3.0), 5L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(5L);
      Assertions.assertEquals(ActionFailureCode.DEADLINE_EXCEEDED, outcome(var4).failureCode());
      Assertions.assertEquals(0, var1.startCount(var3.actionId()));
      ActionEnvelope var5 = envelope(FIRST_BOT, 2L, "bounded", new LookAtAction(1.0, 2.0, 3.0), 100L, 1);
      var1.runForever(var5.actionId());
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.OWNER_TASK);
      var2.tick(6L);
      Assertions.assertFalse(future(var6).toCompletableFuture().isDone());
      var2.tick(7L);
      Assertions.assertEquals(ActionFailureCode.MAX_TICKS_EXCEEDED, outcome(var6).failureCode());
      Assertions.assertEquals(1, var1.cleanupCount(var5.actionId()));
   }

   @Test
   void rejectsAResultFromAnotherGenerationAsStale() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.validationIdentityMode = BotActionRuntimeTest.IdentityMode.WRONG_GENERATION;
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "stale", new LookAtAction(1.0, 2.0, 3.0), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      Assertions.assertEquals(ActionState.STALE, outcome(var4).state());
      Assertions.assertEquals(ActionFailureCode.STALE_GENERATION, outcome(var4).failureCode());
      Assertions.assertEquals(0, var1.totalStarts());
   }

   @Test
   void rejectsAMisroutedActionResultAsAnInternalProtocolFailure() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.validationIdentityMode = BotActionRuntimeTest.IdentityMode.WRONG_ACTION;
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "mismatch", new LookAtAction(1.0, 2.0, 3.0), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      Assertions.assertEquals(ActionState.FAILED, outcome(var4).state());
      Assertions.assertEquals(ActionFailureCode.BACKEND_RESULT_MISMATCH, outcome(var4).failureCode());
   }

   @Test
   void mailboxAndPerTickDrainAreHardBoundedAndDuplicateTicksDoNothing() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 2, 8, 1, 8);
      ActionMailbox.Submission var3 = var2.submit(envelope(FIRST_BOT, 1L, "first", new LookAtAction(1.0, 2.0, 3.0), 100L, 10), ActionPriority.OWNER_TASK);
      ActionMailbox.Submission var4 = var2.submit(envelope(SECOND_BOT, 2L, "second", new LookAtAction(1.0, 2.0, 3.0), 100L, 10), ActionPriority.OWNER_TASK);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED, var3.status());
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.MAILBOX_FULL, var4.status());
      BotActionRuntime.TickReport var5 = var2.tick(1L);
      BotActionRuntime.TickReport var6 = var2.tick(1L);
      Assertions.assertEquals(1, var5.drainedCommands());
      Assertions.assertTrue(var6.duplicateTick());
      Assertions.assertEquals(0, var6.drainedCommands());
      Assertions.assertEquals(1, var1.totalStarts());
   }

   @Test
   void lifecycleCancellationClearsEveryActionForOnlyThatBot() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "first", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      ActionEnvelope var4 = envelope(SECOND_BOT, 2L, "second", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      var1.runForever(var3.actionId());
      var1.runForever(var4.actionId());
      ActionMailbox.Submission var5 = var2.submit(var3, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission var6 = var2.submit(var4, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      Assertions.assertEquals(1, var2.cancelBotNow(FIRST_BOT, var3.botGeneration(), ActionCancellationReason.LIFECYCLE, 2L));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var5).state());
      Assertions.assertFalse(future(var6).toCompletableFuture().isDone());
      Assertions.assertEquals(1, var2.activeActionCount());
   }

   @Test
   void exactGenerationDrainCancelsActiveAndQueuedWorkWithoutClosingIngress() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope active = envelope(
         FIRST_BOT, 1L, 1L, "exclusive-active", new WaitAction(20), 100L, 20
      );
      backend.runForever(active.actionId());
      ActionMailbox.Submission activeSubmission =
         runtime.submit(active, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      ActionEnvelope queued = envelope(
         FIRST_BOT, 1L, 2L, "exclusive-queued", new StopAction(), 100L, 5
      );
      ActionEnvelope newerGeneration = envelope(
         FIRST_BOT, 2L, 3L, "exclusive-newer", new StopAction(), 100L, 5
      );
      ActionMailbox.Submission queuedSubmission =
         runtime.submit(queued, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Submission newerSubmission =
         runtime.submit(newerGeneration, ActionPriority.OWNER_CONTROL);

      BotActionRuntime.GenerationCancellationResult result =
         runtime.cancelBotGenerationNow(
            FIRST_BOT,
            1L,
            ActionCancellationReason.LIFECYCLE,
            2L
         );

      Assertions.assertEquals(2, result.cancelledActions());
      Assertions.assertEquals(0, result.cleanupFailures());
      Assertions.assertFalse(result.quarantined());
      Assertions.assertTrue(result.safeForExclusiveMutation());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(activeSubmission).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(queuedSubmission).state());
      Assertions.assertFalse(future(newerSubmission).toCompletableFuture().isDone());
      Assertions.assertEquals(1, backend.cleanupCount(active.actionId()));
      Assertions.assertEquals(0, backend.startCount(queued.actionId()));

      ActionEnvelope resumed = envelope(
         FIRST_BOT, 1L, 4L, "exclusive-resumed", new StopAction(), 100L, 5
      );
      ActionMailbox.Submission resumedSubmission =
         runtime.submit(resumed, ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.ENQUEUED,
         resumedSubmission.status()
      );
      runtime.tick(2L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(newerSubmission).state());
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(resumedSubmission).state());
   }

   @Test
   void exactGenerationDrainReportsCleanupFailureAndQuarantineAsUnsafe() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      backend.forceResetSucceeds = false;
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope active = envelope(
         FIRST_BOT, 1L, 1L, "exclusive-unsafe", new WaitAction(20), 100L, 20
      );
      backend.runForever(active.actionId());
      backend.failCleanupFor(active.actionId());
      ActionMailbox.Submission submission =
         runtime.submit(active, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      BotActionRuntime.GenerationCancellationResult result =
         runtime.cancelBotGenerationNow(
            FIRST_BOT,
            1L,
            ActionCancellationReason.LIFECYCLE,
            2L
         );

      Assertions.assertEquals(1, result.cancelledActions());
      Assertions.assertEquals(1, result.cleanupFailures());
      Assertions.assertTrue(result.quarantined());
      Assertions.assertFalse(result.safeForExclusiveMutation());
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(submission).failureCode());
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         runtime.submit(
            envelope(
               FIRST_BOT,
               1L,
               2L,
               "exclusive-quarantined",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );
   }

   @Test
   void exactGenerationDrainTreatsRecoveredCleanupFailureAsUnsafeButReusable() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope active = envelope(
         FIRST_BOT, 1L, 1L, "exclusive-reset", new WaitAction(20), 100L, 20
      );
      backend.runForever(active.actionId());
      backend.failCleanupFor(active.actionId());
      runtime.submit(active, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      BotActionRuntime.GenerationCancellationResult result =
         runtime.cancelBotGenerationNow(
            FIRST_BOT,
            1L,
            ActionCancellationReason.LIFECYCLE,
            2L
         );

      Assertions.assertEquals(1, result.cleanupFailures());
      Assertions.assertFalse(result.quarantined());
      Assertions.assertFalse(result.safeForExclusiveMutation());
      ActionMailbox.Submission resumed = runtime.submit(
         envelope(
            FIRST_BOT,
            1L,
            2L,
            "exclusive-reset-resumed",
            new StopAction(),
            100L,
            5
         ),
         ActionPriority.OWNER_CONTROL
      );
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED, resumed.status());
      runtime.tick(2L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(resumed).state());
   }

   @Test
   void lifecycleCloseReenteredFromBackendClosesIngressAndDrainsTheGeneration() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = new BotActionRuntime(backend, 16, 32, 1, 8, 16);
      ActionEnvelope sibling = envelope(
         FIRST_BOT, 1L, 1L, "reentrant-sibling", new WaitAction(20), 100L, 20
      );
      backend.runForever(sibling.actionId());
      ActionMailbox.Submission siblingSubmission =
         runtime.submit(sibling, ActionPriority.OWNER_TASK);
      runtime.tick(1L);
      Assertions.assertEquals(1, runtime.activeLeaseCount());

      ActionEnvelope dying = envelope(
         FIRST_BOT, 1L, 2L, "reentrant-dying", new LookAtAction(1.0, 2.0, 3.0), 100L, 20
      );
      ActionEnvelope queued = envelope(
         FIRST_BOT, 1L, 3L, "reentrant-queued", new StopAction(), 100L, 5
      );
      ActionMailbox.Submission dyingSubmission =
         runtime.submit(dying, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission queuedSubmission =
         runtime.submit(queued, ActionPriority.OWNER_CONTROL);
      AtomicReference<Integer> targeted = new AtomicReference<>();
      AtomicReference<ActionMailbox.SubmissionStatus> reentrantSubmission =
         new AtomicReference<>();
      AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
      backend.onStart(dying.actionId(), () -> {
         try {
            targeted.set(runtime.cancelBotNow(
               FIRST_BOT,
               1L,
               ActionCancellationReason.LIFECYCLE,
               2L
            ));
            reentrantSubmission.set(runtime.submit(
               envelope(
                  FIRST_BOT,
                  1L,
                  4L,
                  "reentrant-rejected",
                  new StopAction(),
                  100L,
                  5
               ),
               ActionPriority.OWNER_CONTROL
            ).status());
         } catch (Throwable failure) {
            callbackFailure.set(failure);
         }
      });

      BotActionRuntime.TickReport report = runtime.tick(2L);

      Assertions.assertEquals(null, callbackFailure.get());
      Assertions.assertEquals(3, targeted.get());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         reentrantSubmission.get()
      );
      Assertions.assertEquals(ActionState.CANCELLED, outcome(siblingSubmission).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(dyingSubmission).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(queuedSubmission).state());
      Assertions.assertEquals(1, backend.cleanupCount(sibling.actionId()));
      Assertions.assertEquals(1, backend.cleanupCount(dying.actionId()));
      Assertions.assertEquals(0, backend.verifyCount(dying.actionId()));
      Assertions.assertEquals(0, backend.startCount(queued.actionId()));
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertEquals(0, report.activeActions());

      ActionEnvelope respawned = envelope(
         FIRST_BOT, 2L, 5L, "reentrant-respawned", new StopAction(), 100L, 5
      );
      ActionMailbox.Submission respawnedSubmission =
         runtime.submit(respawned, ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.ENQUEUED,
         respawnedSubmission.status()
      );
      runtime.tick(3L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(respawnedSubmission).state());
   }

   @Test
   void lifecycleCloseRaisedDuringCleanupIsDrainedBeforeTheTickReturns() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = new BotActionRuntime(backend, 16, 32, 16, 8, 16);
      ActionEnvelope firstBotMove = envelope(
         FIRST_BOT, 1L, 1L, "nested-first-move", new WaitAction(20), 100L, 20
      );
      ActionEnvelope secondBotLook = envelope(
         SECOND_BOT, 1L, 2L, "nested-second-look", new LookAtAction(4.0, 5.0, 6.0), 100L, 20
      );
      backend.runForever(firstBotMove.actionId());
      backend.runForever(secondBotLook.actionId());
      ActionMailbox.Submission firstBotMoveSubmission =
         runtime.submit(firstBotMove, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission secondBotLookSubmission =
         runtime.submit(secondBotLook, ActionPriority.OWNER_TASK);
      runtime.tick(1L);
      Assertions.assertEquals(2, runtime.activeLeaseCount());

      ActionEnvelope dying = envelope(
         FIRST_BOT, 1L, 3L, "nested-dying", new LookAtAction(1.0, 2.0, 3.0), 100L, 20
      );
      backend.runForever(dying.actionId());
      AtomicReference<Integer> nestedTargeted = new AtomicReference<>();
      backend.onCleanup(firstBotMove.actionId(), () -> nestedTargeted.set(
         runtime.cancelBotNow(
            SECOND_BOT,
            1L,
            ActionCancellationReason.LIFECYCLE,
            2L
         )
      ));
      backend.onStart(dying.actionId(), () -> runtime.cancelBotNow(
         FIRST_BOT,
         1L,
         ActionCancellationReason.LIFECYCLE,
         2L
      ));
      ActionMailbox.Submission dyingSubmission =
         runtime.submit(dying, ActionPriority.OWNER_TASK);

      runtime.tick(2L);

      Assertions.assertEquals(1, nestedTargeted.get());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(firstBotMoveSubmission).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(secondBotLookSubmission).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(dyingSubmission).state());
      Assertions.assertEquals(1, backend.cleanupCount(firstBotMove.actionId()));
      Assertions.assertEquals(1, backend.cleanupCount(secondBotLook.actionId()));
      Assertions.assertEquals(1, backend.cleanupCount(dying.actionId()));
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         runtime.submit(
            envelope(
               SECOND_BOT,
               1L,
               4L,
               "nested-second-rejected",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );
   }

   @Test
   void lifecycleCloseDoesNotEraseQuarantineRaisedByItsCleanup() {
      BotActionRuntimeTest.ScriptedBackend backend =
         new BotActionRuntimeTest.ScriptedBackend();
      backend.forceResetSucceeds = false;
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope active = envelope(
         FIRST_BOT,
         1L,
         91L,
         "lifecycle-cleanup-quarantine",
         new WaitAction(20),
         100L,
         20
      );
      backend.runForever(active.actionId());
      backend.failCleanupFor(active.actionId());
      ActionMailbox.Submission submission =
         runtime.submit(active, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      Assertions.assertEquals(
         1,
         runtime.cancelBotNow(
            FIRST_BOT,
            1L,
            ActionCancellationReason.LIFECYCLE,
            2L
         )
      );

      Assertions.assertEquals(
         ActionFailureCode.INTERNAL_ERROR, outcome(submission).failureCode()
      );
      Assertions.assertFalse(runtime.isGenerationSafe(FIRST_BOT, 1L));
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         runtime.submit(
            envelope(
               FIRST_BOT,
               1L,
               92L,
               "lifecycle-cleanup-stays-closed",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );
   }

   @Test
   void shutdownClosesIngressAndCompletesQueuedOrActiveActions() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "active", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      ActionEnvelope var5 = envelope(SECOND_BOT, 2L, "queued", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.OWNER_TASK);
      var2.shutdown(2L);
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var4).state());
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var6).state());
      Assertions.assertEquals(0, var2.activeActionCount());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.RUNTIME_CLOSED,
         var2.submit(envelope(FIRST_BOT, 3L, "late", new StopAction(), 100L, 1), ActionPriority.LIFECYCLE_CLEANUP).status()
      );
   }

   @Test
   void cleanupFailureCannotTurnAnUnsafeSuccessIntoSuccess() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.failCleanupFor(actionId(1L));
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "cleanup", new LookAtAction(1.0, 2.0, 3.0), 100L, 10);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(1L);
      Assertions.assertEquals(ActionState.FAILED, outcome(var4).state());
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var4).failureCode());
      Assertions.assertEquals(1L, var2.cleanupFailureCount());
      Assertions.assertEquals(0, var2.activeLeaseCount());
   }

   @Test
   void cleanupQuarantineCanFailASiblingThatHasNotAdvancedYet() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.forceResetSucceeds = false;
      var1.failCleanupFor(actionId(1L));
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "queued-quarantine-first", new StopAction(), 100L, 5);
      ActionEnvelope var4 = envelope(FIRST_BOT, 2L, "queued-quarantine-sibling", new LookAtAction(1.0, 2.0, 3.0), 100L, 5);
      ActionMailbox.Submission var5 = var2.submit(var3, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Submission var6 = var2.submit(var4, ActionPriority.BACKGROUND);
      var2.tick(1L);
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var5).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var6).failureCode());
      Assertions.assertEquals(0, var1.startCount(var4.actionId()));
      Assertions.assertEquals(0, var2.activeActionCount());
      Assertions.assertEquals(0, var2.activeLeaseCount());
   }

   @Test
   void safetyRecoveryCannotReopenAPermanentlyClosedLifecycleGeneration() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.forceResetSucceeds = false;
      var1.failCleanupFor(actionId(1L));
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "permanent-close", new StopAction(), 100L, 5);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_CONTROL);
      var2.tick(1L);
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var4).failureCode());
      var2.cancelBotNow(FIRST_BOT, var3.botGeneration(), ActionCancellationReason.LIFECYCLE, 2L);
      var1.forceResetSucceeds = true;
      Assertions.assertTrue(var2.recoverBotSafety(FIRST_BOT, var3.botGeneration(), 3L));
      ActionMailbox.Submission var5 = var2.submit(envelope(FIRST_BOT, 2L, "must-stay-closed", new StopAction(), 100L, 5), ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED, var5.status());
   }

   @Test
   void failedCleanupAndResetQuarantineTheWholeGeneration() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.forceResetSucceeds = false;
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "unsafe-look", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "unsafe-move", new WaitAction(20), 100L, 20);
      var1.runForever(var2.actionId());
      var1.runForever(var3.actionId());
      var1.failCleanupFor(var2.actionId());
      BotActionRuntime var4 = runtime(var1);
      ActionMailbox.Submission var5 = var4.submit(var2, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission var6 = var4.submit(var3, ActionPriority.OWNER_TASK);
      var4.tick(1L);
      ActionEnvelope var7 = envelope(FIRST_BOT, 3L, "unsafe-queued", new StopAction(), 100L, 5);
      ActionMailbox.Submission var8 = var4.submit(var7, ActionPriority.BACKGROUND);
      ActionMailbox.Cancellation var9 = var4.cancel(FIRST_BOT, var2.actionId(), ActionCancellationReason.REQUESTED);
      var4.tick(2L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CLEANUP_FAILED, cancellationStatus(var9));
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var5).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var6).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var8).failureCode());
      Assertions.assertEquals(0, var1.startCount(var7.actionId()));
      Assertions.assertEquals(0, var4.activeActionCount());
      Assertions.assertEquals(0, var4.activeLeaseCount());
      ActionEnvelope var10 = envelope(FIRST_BOT, 2L, 4L, "untrusted-newer-generation", new LookAtAction(2.0, 3.0, 4.0), 100L, 5);
      ActionMailbox.Submission var11 = var4.submit(var10, ActionPriority.OWNER_TASK);
      var4.tick(3L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var11).state());
      ActionEnvelope var12 = envelope(FIRST_BOT, 1L, 5L, "still-quarantined", new LookAtAction(3.0, 4.0, 5.0), 100L, 5);
      ActionMailbox.Submission var13 = var4.submit(var12, ActionPriority.OWNER_TASK);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED, var13.status());
      Assertions.assertEquals(0, var1.startCount(var12.actionId()));
      var1.forceResetSucceeds = true;
      Assertions.assertTrue(var4.recoverBotSafety(FIRST_BOT, 1L, 4L));
      ActionEnvelope var14 = envelope(FIRST_BOT, 1L, 6L, "recovered-generation", new LookAtAction(4.0, 5.0, 6.0), 100L, 5);
      ActionMailbox.Submission var15 = var4.submit(var14, ActionPriority.OWNER_TASK);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED, var15.status());
      var4.tick(5L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var15).state());
   }

   @Test
   void upperLayerCanQuarantineAnUnrecoverableTransaction() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "unsafe-transaction-active", new WaitAction(20), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.SURVIVAL);
      var2.tick(1L);
      ActionEnvelope var5 = envelope(FIRST_BOT, 2L, "unsafe-transaction-queued", new StopAction(), 100L, 5);
      ActionMailbox.Submission var6 = var2.submit(var5, ActionPriority.BACKGROUND);

      BotActionRuntime.GenerationQuarantineResult quarantine =
         var2.quarantineBotGenerationNow(FIRST_BOT, 1L, 2L);

      Assertions.assertEquals(2, quarantine.targetedActions());
      Assertions.assertTrue(quarantine.ingressClosed());
      Assertions.assertTrue(quarantine.containmentConfirmed());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var4).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var6).failureCode());
      Assertions.assertEquals(0, var2.activeActionCount());
      Assertions.assertEquals(0, var2.activeLeaseCount());
      Assertions.assertFalse(var2.isGenerationSafe(FIRST_BOT, 1L));
      ActionEnvelope var7 = envelope(FIRST_BOT, 3L, "unsafe-transaction-rejected", new StopAction(), 100L, 5);
      ActionMailbox.Submission var8 = var2.submit(var7, ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED, var8.status());
      Assertions.assertEquals(0, var1.startCount(var7.actionId()));
   }

   @Test
   void quarantinesExactGenerationsIndependentlyInReverseOrder() {
      BotActionRuntimeTest.ScriptedBackend backend =
         new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope generationOneActive = envelope(
         FIRST_BOT,
         1L,
         101L,
         "exact-generation-one-active",
         new WaitAction(20),
         100L,
         20
      );
      ActionEnvelope generationTwoActive = envelope(
         FIRST_BOT,
         2L,
         102L,
         "exact-generation-two-active",
         new LookAtAction(1.0, 2.0, 3.0),
         100L,
         20
      );
      backend.runForever(generationOneActive.actionId());
      backend.runForever(generationTwoActive.actionId());
      ActionMailbox.Submission generationOneActiveSubmission =
         runtime.submit(generationOneActive, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission generationTwoActiveSubmission =
         runtime.submit(generationTwoActive, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      ActionEnvelope generationOneQueued = envelope(
         FIRST_BOT,
         1L,
         103L,
         "exact-generation-one-queued",
         new StopAction(),
         100L,
         5
      );
      ActionEnvelope generationTwoQueued = envelope(
         FIRST_BOT,
         2L,
         104L,
         "exact-generation-two-queued",
         new StopAction(),
         100L,
         5
      );
      ActionEnvelope generationThree = envelope(
         FIRST_BOT,
         3L,
         105L,
         "exact-generation-three",
         new StopAction(),
         100L,
         5
      );
      ActionEnvelope otherBot = envelope(
         SECOND_BOT,
         1L,
         106L,
         "exact-generation-other-bot",
         new StopAction(),
         100L,
         5
      );
      ActionMailbox.Submission generationOneQueuedSubmission =
         runtime.submit(generationOneQueued, ActionPriority.BACKGROUND);
      ActionMailbox.Submission generationTwoQueuedSubmission =
         runtime.submit(generationTwoQueued, ActionPriority.BACKGROUND);
      ActionMailbox.Submission generationThreeSubmission =
         runtime.submit(generationThree, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Submission otherBotSubmission =
         runtime.submit(otherBot, ActionPriority.OWNER_CONTROL);

      BotActionRuntime.GenerationQuarantineResult generationTwoResult =
         runtime.quarantineBotGenerationNow(FIRST_BOT, 2L, 2L);

      Assertions.assertEquals(2, generationTwoResult.targetedActions());
      Assertions.assertTrue(generationTwoResult.containmentConfirmed());
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(generationTwoActiveSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(generationTwoQueuedSubmission).failureCode()
      );
      Assertions.assertFalse(
         future(generationOneActiveSubmission).toCompletableFuture().isDone()
      );
      Assertions.assertFalse(
         future(generationOneQueuedSubmission).toCompletableFuture().isDone()
      );
      Assertions.assertFalse(runtime.isGenerationSafe(FIRST_BOT, 1L));

      BotActionRuntime.GenerationQuarantineResult generationOneResult =
         runtime.quarantineBotGenerationNow(FIRST_BOT, 1L, 2L);

      Assertions.assertEquals(2, generationOneResult.targetedActions());
      Assertions.assertTrue(generationOneResult.containmentConfirmed());
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(generationOneActiveSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(generationOneQueuedSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         runtime.submit(
            envelope(
               FIRST_BOT,
               1L,
               107L,
               "exact-generation-one-rejected",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         runtime.submit(
            envelope(
               FIRST_BOT,
               2L,
               108L,
               "exact-generation-two-rejected",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );

      runtime.tick(3L);

      Assertions.assertEquals(
         ActionState.SUCCEEDED, outcome(generationThreeSubmission).state()
      );
      Assertions.assertEquals(
         ActionState.SUCCEEDED, outcome(otherBotSubmission).state()
      );
      Assertions.assertFalse(runtime.isGenerationSafe(FIRST_BOT, 1L));
      Assertions.assertFalse(runtime.isGenerationSafe(FIRST_BOT, 2L));
      Assertions.assertTrue(runtime.isGenerationSafe(FIRST_BOT, 3L));
      Assertions.assertTrue(runtime.isGenerationSafe(SECOND_BOT, 1L));
   }

   @Test
   void quarantineCapacityOverflowFailsTheWholeRuntimeClosed() {
      BotActionRuntimeTest.ScriptedBackend backend =
         new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope unrelatedActive = envelope(
         SECOND_BOT,
         1L,
         151L,
         "quarantine-capacity-active",
         new WaitAction(20),
         100L,
         20
      );
      backend.runForever(unrelatedActive.actionId());
      ActionMailbox.Submission unrelatedSubmission =
         runtime.submit(unrelatedActive, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      BotActionRuntime.GenerationQuarantineResult lastBounded = null;
      for (long generation = 1L;
           generation <= BotActionRuntime.MAX_QUARANTINED_GENERATIONS;
           generation++) {
         lastBounded = runtime.quarantineBotGenerationNow(
            FIRST_BOT, generation, 2L
         );
      }

      Assertions.assertFalse(lastBounded.runtimeFailClosed());
      BotActionRuntime.GenerationQuarantineResult overflow =
         runtime.quarantineBotGenerationNow(
            FIRST_BOT,
            BotActionRuntime.MAX_QUARANTINED_GENERATIONS + 1L,
            2L
         );

      Assertions.assertTrue(overflow.runtimeFailClosed());
      Assertions.assertTrue(overflow.ingressClosed());
      Assertions.assertTrue(overflow.containmentConfirmed());
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(unrelatedSubmission).failureCode()
      );
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertFalse(
         runtime.isGenerationSafe(FIRST_BOT, 5000L)
      );
      Assertions.assertFalse(
         runtime.isGenerationSafe(SECOND_BOT, 1L)
      );
      Assertions.assertFalse(
         runtime.recoverBotSafety(FIRST_BOT, 1L, 3L)
      );
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.RUNTIME_CLOSED,
         runtime.submit(
            envelope(
               SECOND_BOT,
               2L,
               152L,
               "quarantine-capacity-rejected",
               new StopAction(),
               100L,
               5
            ),
            ActionPriority.OWNER_CONTROL
         ).status()
      );
   }

   @Test
   void reentrantQuarantineClosesIngressBeforeSafeBoundaryContainment() {
      BotActionRuntimeTest.ScriptedBackend backend =
         new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = runtime(backend);
      ActionEnvelope sibling = envelope(
         FIRST_BOT,
         1L,
         201L,
         "reentrant-quarantine-sibling",
         new WaitAction(20),
         100L,
         20
      );
      backend.runForever(sibling.actionId());
      ActionMailbox.Submission siblingSubmission =
         runtime.submit(sibling, ActionPriority.OWNER_TASK);
      runtime.tick(1L);

      ActionEnvelope trigger = envelope(
         FIRST_BOT,
         1L,
         202L,
         "reentrant-quarantine-trigger",
         new LookAtAction(1.0, 2.0, 3.0),
         100L,
         20
      );
      ActionEnvelope exactQueued = envelope(
         FIRST_BOT,
         1L,
         203L,
         "reentrant-quarantine-queued",
         new StopAction(),
         100L,
         5
      );
      ActionEnvelope newerGeneration = envelope(
         FIRST_BOT,
         2L,
         204L,
         "reentrant-quarantine-newer",
         new StopAction(),
         100L,
         5
      );
      ActionMailbox.Submission triggerSubmission =
         runtime.submit(trigger, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission exactQueuedSubmission =
         runtime.submit(exactQueued, ActionPriority.BACKGROUND);
      ActionMailbox.Submission newerGenerationSubmission =
         runtime.submit(newerGeneration, ActionPriority.OWNER_CONTROL);
      AtomicReference<BotActionRuntime.GenerationQuarantineResult> callbackReceipt =
         new AtomicReference<>();
      AtomicReference<ActionMailbox.SubmissionStatus> callbackSubmission =
         new AtomicReference<>();
      AtomicReference<Boolean> callbackSafety = new AtomicReference<>();
      AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
      backend.onStart(trigger.actionId(), () -> {
         try {
            callbackReceipt.set(
               runtime.quarantineBotGenerationNow(FIRST_BOT, 1L, 2L)
            );
            callbackSubmission.set(
               runtime.submit(
                  envelope(
                     FIRST_BOT,
                     1L,
                     205L,
                     "reentrant-quarantine-rejected",
                     new StopAction(),
                     100L,
                     5
                  ),
                  ActionPriority.OWNER_CONTROL
               ).status()
            );
            callbackSafety.set(runtime.isGenerationSafe(FIRST_BOT, 1L));
         } catch (Throwable failure) {
            callbackFailure.set(failure);
         }
      });

      runtime.tick(2L);

      Assertions.assertEquals(null, callbackFailure.get());
      BotActionRuntime.GenerationQuarantineResult reentrantResult =
         callbackReceipt.get();
      Assertions.assertEquals(3, reentrantResult.targetedActions());
      Assertions.assertTrue(reentrantResult.ingressClosed());
      Assertions.assertTrue(reentrantResult.pending());
      Assertions.assertTrue(reentrantResult.ticketRemaining());
      Assertions.assertTrue(reentrantResult.leaseRemaining());
      Assertions.assertFalse(reentrantResult.containmentConfirmed());
      Assertions.assertEquals(
         ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
         callbackSubmission.get()
      );
      Assertions.assertFalse(callbackSafety.get());
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(siblingSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(triggerSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         outcome(exactQueuedSubmission).failureCode()
      );
      Assertions.assertEquals(
         ActionState.SUCCEEDED, outcome(newerGenerationSubmission).state()
      );
      Assertions.assertEquals(0, runtime.activeActionCount());
      Assertions.assertEquals(0, runtime.activeLeaseCount());
      Assertions.assertEquals(1, backend.cleanupCount(sibling.actionId()));
      Assertions.assertEquals(1, backend.cleanupCount(trigger.actionId()));
      Assertions.assertEquals(0, backend.startCount(exactQueued.actionId()));

      BotActionRuntime.GenerationQuarantineResult contained =
         runtime.quarantineBotGenerationNow(FIRST_BOT, 1L, 2L);
      Assertions.assertEquals(0, contained.targetedActions());
      Assertions.assertTrue(contained.containmentConfirmed());
      Assertions.assertFalse(runtime.isGenerationSafe(FIRST_BOT, 1L));
      Assertions.assertTrue(runtime.recoverBotSafety(FIRST_BOT, 1L, 3L));
      Assertions.assertTrue(runtime.isGenerationSafe(FIRST_BOT, 1L));
   }

   @Test
   void quarantineDuringMultiLeasePreemptionFinishesEveryTicketWithoutThrowing() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.forceResetSucceeds = false;
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "preempt-look", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "preempt-move", new WaitAction(20), 100L, 20);
      var1.runForever(var2.actionId());
      var1.runForever(var3.actionId());
      var1.failCleanupFor(var2.actionId());
      BotActionRuntime var4 = runtime(var1);
      ActionMailbox.Submission var5 = var4.submit(var2, ActionPriority.AUTONOMOUS);
      ActionMailbox.Submission var6 = var4.submit(var3, ActionPriority.AUTONOMOUS);
      var4.tick(1L);
      ActionEnvelope var7 = envelope(FIRST_BOT, 3L, "preempt-stop", new StopAction(), 100L, 5);
      ActionMailbox.Submission var8 = var4.submit(var7, ActionPriority.OWNER_CONTROL);
      var4.tick(2L);
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var5).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var6).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var8).failureCode());
      Assertions.assertEquals(0, var4.activeActionCount());
      Assertions.assertEquals(0, var4.activeLeaseCount());
   }

   @Test
   void quarantinePreemptionDoesNotDependOnRetainingDisplacedLedgerEntries() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      var1.forceResetSucceeds = false;
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "evict-look", new LookAtAction(1.0, 2.0, 3.0), 100L, 20);
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "evict-move", new WaitAction(20), 100L, 20);
      var1.runForever(var2.actionId());
      var1.runForever(var3.actionId());
      var1.failCleanupFor(var2.actionId());
      BotActionRuntime var4 = new BotActionRuntime(var1, 32, 3, 1, 16, 32);
      ActionMailbox.Submission var5 = var4.submit(var2, ActionPriority.AUTONOMOUS);
      ActionMailbox.Submission var6 = var4.submit(var3, ActionPriority.AUTONOMOUS);
      var4.tick(1L);
      var4.tick(2L);
      ActionEnvelope var7 = envelope(FIRST_BOT, 3L, "evict-stop", new StopAction(), 100L, 5);
      ActionEnvelope var8 = envelope(FIRST_BOT, 4L, "evict-queued", new LookAtAction(4.0, 5.0, 6.0), 100L, 5);
      ActionMailbox.Submission var9 = var4.submit(var7, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Submission var10 = var4.submit(var8, ActionPriority.BACKGROUND);
      var4.tick(3L);
      Assertions.assertEquals(ActionFailureCode.INTERNAL_ERROR, outcome(var5).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var6).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var9).failureCode());
      Assertions.assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE, outcome(var10).failureCode());
      Assertions.assertEquals(0, var4.activeActionCount());
      Assertions.assertEquals(0, var4.activeLeaseCount());
   }

   @Test
   void terminalCallbacksCannotRunOnOrMutateTheOwnerThread() throws InterruptedException {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 32, 64, 32, 16, 32);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "safe-completion", new StopAction(), 100L, 5);
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_CONTROL);
      CompletionStage<ActionOutcome> var5 = future(var4);
      CompletableFuture<ActionOutcome> var6 = var5.toCompletableFuture();
      ActionOutcome var7 = new ActionOutcome(var3.actionId(), ActionState.FAILED, ActionFailureCode.INTERNAL_ERROR, 0L, 0L, List.of(), "Forged caller result");
      Assertions.assertTrue(var6.complete(var7));
      Thread var8 = Thread.currentThread();
      AtomicReference<Thread> var9 = new AtomicReference<>();
      AtomicReference<Throwable> var10 = new AtomicReference<>();
      CountDownLatch var11 = new CountDownLatch(1);
      var5.whenComplete((var4x, var5x) -> {
         var9.set(Thread.currentThread());

         try {
            var2.tick(2L);
         } catch (Throwable var10x) {
            var10.set(var10x);
         } finally {
            var11.countDown();
         }
      });
      var2.tick(1L);
      Assertions.assertTrue(var11.await(2L, TimeUnit.SECONDS));
      Assertions.assertFalse(var8 == var9.get());
      Assertions.assertTrue(var10.get() instanceof IllegalStateException);
      Assertions.assertEquals(ActionState.SUCCEEDED, var5.toCompletableFuture().join().state());
      Assertions.assertSame(var7, var6.join());
   }

   @Test
   void blockedCompletionCallbacksApplyBoundedIngressBackpressure() throws InterruptedException {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 8, 8, 8, 8, 3);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "blocked-first", new StopAction(), 100L, 5);
      ActionEnvelope var4 = envelope(SECOND_BOT, 2L, "blocked-second", new StopAction(), 100L, 5);
      ActionMailbox.Submission var5 = var2.submit(var3, ActionPriority.OWNER_CONTROL);
      ActionMailbox.Submission var6 = var2.submit(var4, ActionPriority.OWNER_CONTROL);
      CountDownLatch var7 = new CountDownLatch(1);
      CountDownLatch var8 = new CountDownLatch(1);
      future(var5).whenComplete((var2x, var3x) -> {
         var7.countDown();

         try {
            var8.await();
         } catch (InterruptedException var5x) {
            Thread.currentThread().interrupt();
         }
      });
      var2.tick(1L);
      Assertions.assertTrue(var7.await(2L, TimeUnit.SECONDS));
      Assertions.assertEquals(2, var2.pendingCompletionCount());
      ActionMailbox.Submission var9 = var2.submit(envelope(FIRST_BOT, 3L, "blocked-third", new StopAction(), 100L, 5), ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.COMPLETION_BACKPRESSURE, var9.status());
      var8.countDown();
      outcome(var5);
      outcome(var6);
      awaitPendingCompletionCount(var2, 0);
      ActionMailbox.Submission var10 = var2.submit(envelope(FIRST_BOT, 4L, "after-backpressure", new StopAction(), 100L, 5), ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED, var10.status());
      var2.tick(2L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var10).state());
   }

   @Test
   void cancellationHasReservedCompletionCapacityDuringSubmissionBackpressure() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 8, 8, 8, 8, 2);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "reserved-cancel", new WaitAction(20), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      ActionMailbox.Submission var5 = var2.submit(envelope(SECOND_BOT, 2L, "submission-backpressure", new StopAction(), 100L, 5), ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.COMPLETION_BACKPRESSURE, var5.status());
      var2.tick(1L);
      ActionMailbox.Cancellation var6 = var2.cancel(FIRST_BOT, var3.actionId(), ActionCancellationReason.REQUESTED);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.ENQUEUED, var6.status());
      var2.tick(2L);
      Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, cancellationStatus(var6));
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var4).state());
      Assertions.assertEquals(1, var1.cleanupCount(var3.actionId()));
   }

   @Test
   void lifecycleMutationsRejectTicksOlderThanTheLastMutation() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "monotonic", new WaitAction(10), 100L, 20);
      var1.runForever(var3.actionId());
      ActionMailbox.Submission var4 = var2.submit(var3, ActionPriority.OWNER_TASK);
      var2.tick(5L);
      Assertions.assertThrows(IllegalArgumentException.class, () -> var2.cancelBotNow(FIRST_BOT, var3.botGeneration(), ActionCancellationReason.LIFECYCLE, 4L));
      Assertions.assertFalse(future(var4).toCompletableFuture().isDone());
      Assertions.assertEquals(1, var2.activeActionCount());
      var2.cancelBotNow(FIRST_BOT, var3.botGeneration(), ActionCancellationReason.LIFECYCLE, 5L);
      Assertions.assertEquals(ActionState.CANCELLED, outcome(var4).state());
   }

   @Test
   void duplicateWaitersHaveAHardPerActionLimit() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 128, 128, 128, 16);
      ActionEnvelope var3 = envelope(FIRST_BOT, 1L, "bounded-waiters", new WaitAction(20), 100L, 20);
      var1.runForever(var3.actionId());
      ArrayList<ActionMailbox.Submission> var4 = new ArrayList<>();
      var4.add(var2.submit(var3, ActionPriority.OWNER_TASK));
      var2.tick(1L);

      for (long var5 = 2L; var5 <= 66L; var5++) {
         var4.add(var2.submit(envelope(FIRST_BOT, var5, "bounded-waiters", var3.action(), 100L, 20), ActionPriority.OWNER_TASK));
      }

      var2.tick(2L);
      var2.cancel(FIRST_BOT, var3.actionId(), ActionCancellationReason.REQUESTED);
      var2.tick(3L);
      long var7 = var4.stream().map(BotActionRuntimeTest::outcome).filter(var0 -> var0.failureCode() == ActionFailureCode.DUPLICATE_IN_PROGRESS).count();
      Assertions.assertEquals(2L, var7);
      Assertions.assertEquals(1, var1.totalStarts());
   }

   @Test
   void runtimeMutationIsThreadConfinedButMailboxOfferIsSafe() throws InterruptedException {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = runtime(var1);
      AtomicReference<Throwable> var3 = new AtomicReference<>();
      AtomicReference<ActionMailbox.SubmissionStatus> var4 = new AtomicReference<>();
      AtomicReference<Throwable> safetyFailure = new AtomicReference<>();
      Thread var5 = new Thread(() -> {
         var4.set(var2.submit(envelope(FIRST_BOT, 1L, "threaded", new LookAtAction(1.0, 2.0, 3.0), 100L, 10), ActionPriority.OWNER_TASK).status());

         try {
            var2.isGenerationSafe(FIRST_BOT, 1L);
         } catch (Throwable failure) {
            safetyFailure.set(failure);
         }

         try {
            var2.tick(1L);
         } catch (Throwable var4x) {
            var3.set(var4x);
         }
      });
      var5.start();
      var5.join();
      Assertions.assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED, var4.get());
      Assertions.assertTrue(var3.get() instanceof IllegalStateException);
      Assertions.assertTrue(safetyFailure.get() instanceof IllegalStateException);
      var2.tick(1L);
      Assertions.assertEquals(1, var1.totalStarts());
   }

   @Test
   void validatesWaitBoundsAndRuntimeConfiguration() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WaitAction(0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WaitAction(6001));
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BotActionRuntime(var1, 1, 1, 1, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BotActionRuntime(var1, 1, 1, 0, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BotActionRuntime(var1, 1, 1, 1, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BotActionRuntime(var1, 2, 1, 1, 1, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BotActionRuntime(var1, 2, 1, 1, 1, 1));
   }

   @Test
   void publishesEachCanonicalOutcomeOnceAndDoesNotRepublishAReplay() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      List<UUID> published = new ArrayList<>();
      BotActionRuntime runtime = new BotActionRuntime(
         backend, 16, 16, 16, 8, 16, (envelope, outcome) -> published.add(outcome.actionId())
      );
      ActionEnvelope first = envelope(FIRST_BOT, 1L, "sink-once", new StopAction(), 100L, 5);
      ActionMailbox.Submission firstSubmission = runtime.submit(first, ActionPriority.OWNER_CONTROL);
      runtime.tick(1L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(firstSubmission).state());

      ActionEnvelope replay = envelope(FIRST_BOT, 2L, "sink-once", first.action(), 200L, 5);
      ActionMailbox.Submission replaySubmission = runtime.submit(replay, ActionPriority.OWNER_CONTROL);
      runtime.tick(2L);
      Assertions.assertSame(outcome(firstSubmission), outcome(replaySubmission));
      Assertions.assertEquals(List.of(first.actionId()), published);
   }

   @Test
   void isolatesOutcomeSinkFailureFromTheCanonicalActionResult() {
      BotActionRuntimeTest.ScriptedBackend backend = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime runtime = new BotActionRuntime(
         backend,
         16,
         16,
         16,
         8,
         16,
         (envelope, outcome) -> {
            throw new IllegalStateException("synthetic sink failure");
         }
      );
      ActionEnvelope action = envelope(FIRST_BOT, 1L, "sink-failure", new StopAction(), 100L, 5);
      ActionMailbox.Submission submission = runtime.submit(action, ActionPriority.OWNER_CONTROL);
      runtime.tick(1L);

      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(submission).state());
      Assertions.assertEquals(1L, runtime.outcomeSinkFailureCount());
      Assertions.assertEquals(1, runtime.ledgerSize());
   }

   @Test
   void publishesCanonicalRuntimeCapacityRejectionToTheOutcomeSink() {
      BotActionRuntimeTest.ScriptedBackend backend =
         new BotActionRuntimeTest.ScriptedBackend();
      List<UUID> published = new ArrayList<>();
      BotActionRuntime runtime = new BotActionRuntime(
         backend,
         16,
         16,
         16,
         1,
         16,
         (envelope, outcome) -> published.add(outcome.actionId())
      );
      ActionEnvelope active = envelope(
         FIRST_BOT, 1L, "capacity-active", new WaitAction(20), 100L, 20
      );
      ActionMailbox.Submission activeSubmission =
         runtime.submit(active, ActionPriority.OWNER_TASK);
      runtime.tick(1L);
      Assertions.assertFalse(future(activeSubmission).toCompletableFuture().isDone());

      ActionEnvelope rejected = envelope(
         SECOND_BOT, 2L, "capacity-rejected", new StopAction(), 100L, 5
      );
      ActionMailbox.Submission rejectedSubmission =
         runtime.submit(rejected, ActionPriority.OWNER_CONTROL);
      runtime.tick(2L);

      Assertions.assertEquals(
         ActionFailureCode.RUNTIME_CAPACITY_EXCEEDED,
         outcome(rejectedSubmission).failureCode()
      );
      Assertions.assertEquals(List.of(rejected.actionId()), published);
      Assertions.assertSame(
         outcome(rejectedSubmission),
         runtime.completedOutcome(SECOND_BOT, rejected.actionId()).orElseThrow()
      );
   }

   @Test
   void transitionDiagnosticsAreStructuredChronologicalAndHardBounded() {
      BotActionRuntimeTest.ScriptedBackend var1 = new BotActionRuntimeTest.ScriptedBackend();
      BotActionRuntime var2 = new BotActionRuntime(var1, 32, 256, 32, 16, 32);

      for (long var3 = 1L; var3 <= 130L; var3++) {
         ActionMailbox.Submission var5 = var2.submit(
            envelope(FIRST_BOT, var3, "transition-" + var3, new StopAction(), var3 + 100L, 5), ActionPriority.OWNER_CONTROL
         );
         var2.tick(var3);
         Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var5).state());
      }

      List<ActionTransition> var6 = var2.transitionHistory(512);
      Assertions.assertEquals(512, var6.size());
      ActionTransition var4 = var6.getLast();
      Assertions.assertEquals(FIRST_BOT, var4.botId());
      Assertions.assertEquals(actionId(130L), var4.actionId());
      Assertions.assertEquals(ActionKind.STOP, var4.kind());
      Assertions.assertEquals(ActionState.VERIFYING, var4.from());
      Assertions.assertEquals(ActionState.SUCCEEDED, var4.to());
      Assertions.assertEquals(130L, var4.serverTick());
      Assertions.assertTrue(var6.getFirst().serverTick() > 1L);
      Assertions.assertEquals(List.of(var4), var2.transitionHistory(1));
      Assertions.assertEquals(List.of(), var2.transitionHistory(0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> var2.transitionHistory(-1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> var2.transitionHistory(513));
   }

   private static BotActionRuntime runtime(BotActionRuntimeTest.ScriptedBackend var0) {
      return new BotActionRuntime(var0, 32, 64, 32, 16);
   }

   private static ActionEnvelope envelope(UUID var0, long var1, String var3, ActionRequest var4, long var5, int var7) {
      return new ActionEnvelope(actionId(var1), var0, 1L, var3, var5, var7, var4, ActionOrigin.none());
   }

   private static ActionEnvelope envelope(UUID var0, long var1, long var3, String var5, ActionRequest var6, long var7, int var9) {
      return new ActionEnvelope(actionId(var3), var0, var1, var5, var7, var9, var6, ActionOrigin.none());
   }

   private static UUID actionId(long var0) {
      return new UUID(0L, var0);
   }

   private static CompletionStage<ActionOutcome> future(ActionMailbox.Submission var0) {
      return var0.completion().orElseThrow();
   }

   private static ActionOutcome outcome(ActionMailbox.Submission var0) {
      return future(var0).toCompletableFuture().join();
   }

   private static ActionMailbox.CancellationStatus cancellationStatus(ActionMailbox.Cancellation var0) {
      return var0.completion().orElseThrow().toCompletableFuture().join();
   }

   private static void awaitPendingCompletionCount(BotActionRuntime var0, int var1) throws InterruptedException {
      long var2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);

      while (var0.pendingCompletionCount() != var1 && System.nanoTime() < var2) {
         Thread.sleep(1L);
      }

      Assertions.assertEquals(var1, var0.pendingCompletionCount());
   }

   private static enum IdentityMode {
      CORRECT,
      WRONG_GENERATION,
      WRONG_ACTION;
   }

   private static final class ScriptedBackend implements ActionBackend {
      private final Set<UUID> forever = new HashSet<>();
      private final Map<UUID, Integer> starts = new HashMap<>();
      private final Map<UUID, Integer> verifies = new HashMap<>();
      private final Map<UUID, Integer> cleanups = new HashMap<>();
      private final Map<UUID, Long> startTicks = new HashMap<>();
      private final Set<UUID> cleanupFailures = new HashSet<>();
      private final Map<UUID, Runnable> startHooks = new HashMap<>();
      private final Map<UUID, Runnable> cleanupHooks = new HashMap<>();
      private BotActionRuntimeTest.IdentityMode validationIdentityMode = BotActionRuntimeTest.IdentityMode.CORRECT;
      private boolean forceResetSucceeds = true;
      private int forceResetCount;

      @Override
      public ActionBackend.BackendResult validate(ActionEnvelope var1, long var2) {
         return switch (this.validationIdentityMode) {
            case CORRECT -> ActionBackend.BackendResult.accepted(var1);
            case WRONG_GENERATION -> new ActionBackend.BackendResult(
            var1.actionId(), var1.botId(), var1.botGeneration() + 1L, ActionBackend.BackendStep.ACCEPTED, ActionFailureCode.NONE, List.of(), ""
         );
            case WRONG_ACTION -> new ActionBackend.BackendResult(
            new UUID(0L, 9999L), var1.botId(), var1.botGeneration(), ActionBackend.BackendStep.ACCEPTED, ActionFailureCode.NONE, List.of(), ""
         );
         };
      }

      @Override
      public ActionBackend.BackendResult start(ActionEnvelope var1, long var2) {
         this.starts.merge(var1.actionId(), 1, Integer::sum);
         this.startTicks.put(var1.actionId(), var2);
         Runnable startHook = this.startHooks.remove(var1.actionId());
         if (startHook != null) {
            startHook.run();
         }
         return !this.forever.contains(var1.actionId()) && !(var1.action() instanceof WaitAction)
            ? ActionBackend.BackendResult.readyToVerify(var1)
            : ActionBackend.BackendResult.running(var1);
      }

      @Override
      public ActionBackend.BackendResult tick(ActionEnvelope var1, long var2, long var4) {
         if (this.forever.contains(var1.actionId())) {
            return ActionBackend.BackendResult.running(var1);
         } else {
            if (var1.action() instanceof WaitAction var6 && var4 - this.startTicks.get(var1.actionId()) < (long)var6.ticks()) {
               return ActionBackend.BackendResult.running(var1);
            }

            return ActionBackend.BackendResult.readyToVerify(var1);
         }
      }

      @Override
      public ActionBackend.BackendResult verify(ActionEnvelope var1, long var2) {
         this.verifies.merge(var1.actionId(), 1, Integer::sum);
         return ActionBackend.BackendResult.succeeded(
            var1, List.of(new ActionEvidence("backend.kind", var1.action().kind().name().toLowerCase())), "Action verified"
         );
      }

      @Override
      public void cleanup(ActionEnvelope var1, ActionCleanupReason var2, long var3) {
         this.cleanups.merge(var1.actionId(), 1, Integer::sum);
         Runnable cleanupHook = this.cleanupHooks.remove(var1.actionId());
         if (cleanupHook != null) {
            cleanupHook.run();
         }
         if (this.cleanupFailures.contains(var1.actionId())) {
            throw new IllegalStateException("synthetic cleanup failure");
         }
      }

      @Override
      public boolean forceSafeReset(UUID var1, long var2, long var4) {
         this.forceResetCount++;
         return this.forceResetSucceeds;
      }

      private void runForever(UUID var1) {
         this.forever.add(var1);
      }

      private void onStart(UUID var1, Runnable var2) {
         this.startHooks.put(var1, var2);
      }

      private void onCleanup(UUID var1, Runnable var2) {
         this.cleanupHooks.put(var1, var2);
      }

      private int startCount(UUID var1) {
         return this.starts.getOrDefault(var1, 0);
      }

      private int cleanupCount(UUID var1) {
         return this.cleanups.getOrDefault(var1, 0);
      }

      private int verifyCount(UUID var1) {
         return this.verifies.getOrDefault(var1, 0);
      }

      private int totalStarts() {
         return this.starts.values().stream().mapToInt(Integer::intValue).sum();
      }

      private void failCleanupFor(UUID var1) {
         this.cleanupFailures.add(var1);
      }
   }
}
