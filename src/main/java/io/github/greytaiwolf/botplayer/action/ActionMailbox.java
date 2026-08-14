package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ActionMailbox {
   public static final int MAX_CAPACITY = 65536;
   private final ArrayBlockingQueue<ActionMailbox.SubmitCommand> submissions;
   private final ArrayBlockingQueue<ActionMailbox.CancelCommand> cancellations;
   private final CompletionDispatcher completionDispatcher;
   private final Map<UUID, Long> closedGenerationByBot = new HashMap<>();
   private final Set<ActionMailbox.GenerationKey> quarantinedGenerations = new HashSet<>();
   private final AtomicBoolean closed = new AtomicBoolean();

   ActionMailbox(int var1, CompletionDispatcher var2) {
      if (var1 >= 2 && var1 <= 65536) {
         int var3 = Math.max(1, var1 / 8);
         int var4 = var1 - var3;
         this.submissions = new ArrayBlockingQueue<>(var4);
         this.cancellations = new ArrayBlockingQueue<>(var3);
         this.completionDispatcher = Objects.requireNonNull(var2, "completionDispatcher");
      } else {
         throw new IllegalArgumentException("capacity must be between 2 and 65536");
      }
   }

   public synchronized ActionMailbox.Submission submit(ActionEnvelope var1, ActionPriority var2) {
      Objects.requireNonNull(var1, "envelope");
      Objects.requireNonNull(var2, "priority");
      if (this.closed.get()) {
         return ActionMailbox.Submission.rejected(ActionMailbox.SubmissionStatus.RUNTIME_CLOSED);
      } else {
         long var3 = this.closedGenerationByBot.getOrDefault(var1.botId(), 0L);
         boolean var5 = this.quarantinedGenerations.contains(
            new ActionMailbox.GenerationKey(var1.botId(), var1.botGeneration())
         );
         if (var1.botGeneration() > var3 && !var5) {
            CompletionDispatcher.Completion<ActionOutcome> var7 =
               this.completionDispatcher.tryReserveSubmission();
            if (var7 == null) {
               return ActionMailbox.Submission.rejected(ActionMailbox.SubmissionStatus.COMPLETION_BACKPRESSURE);
            } else if (!this.submissions.offer(new ActionMailbox.SubmitCommand(var1, var2, var7))) {
               this.completionDispatcher.releaseUnusedReservation(var7);
               return ActionMailbox.Submission.rejected(ActionMailbox.SubmissionStatus.MAILBOX_FULL);
            } else {
               return ActionMailbox.Submission.enqueued(var7.future().minimalCompletionStage());
            }
         } else {
            return ActionMailbox.Submission.rejected(ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED);
         }
      }
   }

   public synchronized ActionMailbox.Cancellation cancel(UUID var1, UUID var2, ActionCancellationReason var3) {
      return this.cancel(var1, 0L, var2, var3);
   }

   /**
    * Atomically removes one exact queued envelope for P5C containment.
    *
    * <p>This deliberately bypasses the ordinary cancellation lane. A full
    * cancellation receipt queue must not force a still-unstarted exact action
    * into the ambiguous generation-containment path. The caller owns the
    * returned submission's terminal completion and must not use this method
    * for aliases or arbitrary cancellation.
    */
   synchronized Optional<ActionMailbox.SubmitCommand> removeExactQueuedForContainment(
      UUID botId, long botGeneration, UUID actionId
   ) {
      ActionEnvelope.requireNonZero(botId, "botId");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      }
      ActionEnvelope.requireNonZero(actionId, "actionId");

      ActionMailbox.SubmitCommand exactSubmission = this.submissions
         .stream()
         .filter(submission -> submission.envelope().botId().equals(botId)
            && submission.envelope().botGeneration() == botGeneration
            && submission.envelope().actionId().equals(actionId))
         .findFirst()
         .orElse(null);
      if (exactSubmission == null) {
         return Optional.empty();
      }
      if (!this.submissions.remove(exactSubmission)) {
         throw new IllegalStateException(
            "Exact queued action disappeared during containment"
         );
      }
      return Optional.of(exactSubmission);
   }

   private synchronized ActionMailbox.Cancellation cancel(
      UUID botId, long containmentGeneration, UUID actionId,
      ActionCancellationReason reason
   ) {
      ActionEnvelope.requireNonZero(botId, "botId");
      ActionEnvelope.requireNonZero(actionId, "actionId");
      Objects.requireNonNull(reason, "reason");
      if (this.closed.get()) {
         return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.RUNTIME_CLOSED);
      } else {
         CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> completion =
            this.completionDispatcher.tryReserveCancellation();
         if (completion == null) {
            return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.COMPLETION_BACKPRESSURE);
         } else {
            ActionMailbox.SubmitCommand exactSubmission = this.submissions
               .stream()
               .filter(
                  submission -> submission.envelope().botId().equals(botId)
                     && submission.envelope().actionId().equals(actionId)
                     && (containmentGeneration == 0L
                        || submission.envelope().botGeneration()
                           == containmentGeneration)
               )
               .findFirst()
               .orElse(null);
            /*
             * P5C's stronger path must remove the requested submission
             * itself. The legacy cancellation path retains canonical-alias
             * coalescing, but treating a sibling alias as proof would leave
             * this exact envelope eligible to start later.
             */
            ActionMailbox.SubmitCommand queuedSubmission = containmentGeneration > 0L
               ? exactSubmission
               : exactSubmission == null
                  ? null
                  : this.submissions
                     .stream()
                     .filter(
                        submission -> submission.envelope().idempotencyKey()
                              .equals(exactSubmission.envelope().idempotencyKey())
                              && exactSubmission.envelope()
                                 .hasSameIdempotentOperation(
                                    submission.envelope())
                     )
                     .findFirst()
                     .orElseThrow();
            ActionMailbox.CancelCommand cancellation = new ActionMailbox.CancelCommand(
               botId, actionId, reason, Optional.ofNullable(queuedSubmission),
               containmentGeneration, completion
            );
            if (!this.cancellations.offer(cancellation)) {
               this.completionDispatcher.releaseUnusedReservation(completion);
               return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.MAILBOX_FULL);
            } else if (queuedSubmission != null
               && !this.submissions.remove(queuedSubmission)) {
               throw new IllegalStateException("Queued action disappeared while its cancellation was reserved");
            } else {
               return ActionMailbox.Cancellation.enqueued(
                  completion.future().minimalCompletionStage(),
                  containmentGeneration > 0L && queuedSubmission != null
               );
            }
         }
      }
   }

   public int size() {
      return this.submissions.size() + this.cancellations.size();
   }

   public boolean isClosed() {
      return this.closed.get();
   }

   synchronized boolean isGenerationIngressClosed(UUID var1, long var2) {
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         return this.closed.get()
            || var2 <= this.closedGenerationByBot.getOrDefault(var1, 0L)
            || this.quarantinedGenerations.contains(
               new ActionMailbox.GenerationKey(var1, var2)
            );
      }
   }

   synchronized void close() {
      this.closed.set(true);
   }

   synchronized List<ActionMailbox.Command> drain(int var1) {
      if (var1 < 1) {
         throw new IllegalArgumentException("maximum must be positive");
      } else {
         ArrayList<ActionMailbox.Command> var2 =
            new ArrayList<>(Math.min(var1, this.size()));
         this.cancellations.drainTo(var2, var1);
         if (var2.size() < var1) {
            this.submissions.drainTo(var2, var1 - var2.size());
         }

         return var2;
      }
   }

   synchronized List<ActionMailbox.Command> drainAll() {
      ArrayList<ActionMailbox.Command> var1 = new ArrayList<>(this.size());
      this.cancellations.drainTo(var1);
      this.submissions.drainTo(var1);
      return var1;
   }

   synchronized List<ActionMailbox.SubmitCommand> closeBotThrough(UUID var1, long var2) {
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         this.closedGenerationByBot.merge(var1, var2, Math::max);
         this.quarantinedGenerations.removeIf(
            var3 -> var3.botId.equals(var1) && var3.generation <= var2
         );

         ArrayList<ActionMailbox.SubmitCommand> var5 = new ArrayList<>();
         this.submissions.removeIf(var4x -> {
            ActionEnvelope var5x = var4x.envelope();
            if (var5x.botId().equals(var1) && var5x.botGeneration() <= var2) {
               var5.add(var4x);
               return true;
            } else {
               return false;
            }
         });
         return var5;
      }
   }

   synchronized List<ActionMailbox.SubmitCommand> drainBotGeneration(UUID var1, long var2) {
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         ArrayList<ActionMailbox.SubmitCommand> var4 = new ArrayList<>();
         this.submissions.removeIf(var4x -> {
            ActionEnvelope var5 = var4x.envelope();
            if (var5.botId().equals(var1) && var5.botGeneration() == var2) {
               var4.add(var4x);
               return true;
            } else {
               return false;
            }
         });
         return var4;
      }
   }

   synchronized List<ActionMailbox.SubmitCommand> quarantineBotGeneration(UUID var1, long var2) {
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         this.quarantinedGenerations.add(
            new ActionMailbox.GenerationKey(var1, var2)
         );
         ArrayList<ActionMailbox.SubmitCommand> var4 = new ArrayList<>();
         this.submissions.removeIf(var4x -> {
            ActionEnvelope var5 = var4x.envelope();
            if (var5.botId().equals(var1) && var5.botGeneration() == var2) {
               var4.add(var4x);
               return true;
            } else {
               return false;
            }
         });
         return var4;
      }
   }

   synchronized boolean recoverBotGeneration(UUID var1, long var2) {
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         return this.quarantinedGenerations.remove(
            new ActionMailbox.GenerationKey(var1, var2)
         );
      }
   }

   private static record GenerationKey(UUID botId, long generation) {
   }

   static record CancelCommand(
      UUID botId,
      UUID actionId,
      ActionCancellationReason reason,
      Optional<ActionMailbox.SubmitCommand> queuedSubmission,
      long containmentGeneration,
      CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> completion
   ) implements ActionMailbox.Command {
      CancelCommand(
         UUID botId,
         UUID actionId,
         ActionCancellationReason reason,
         Optional<ActionMailbox.SubmitCommand> queuedSubmission,
         long containmentGeneration,
         CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> completion
      ) {
         Objects.requireNonNull(queuedSubmission, "queuedSubmission");
         if (containmentGeneration < 0L) {
            throw new IllegalArgumentException("containmentGeneration must not be negative");
         }
         this.botId = botId;
         this.actionId = actionId;
         this.reason = reason;
         this.queuedSubmission = queuedSubmission;
         this.containmentGeneration = containmentGeneration;
         this.completion = completion;
      }

      boolean requiresGenerationContainment() {
         return this.containmentGeneration > 0L;
      }
   }

   public static record Cancellation(
      ActionMailbox.CancellationStatus status,
      Optional<CompletionStage<ActionMailbox.CancellationStatus>> completion,
      boolean exactQueuedActionRemoved
   ) {
      public Cancellation(
         ActionMailbox.CancellationStatus status,
         Optional<CompletionStage<ActionMailbox.CancellationStatus>> completion
      ) {
         this(status, completion, false);
      }

      public Cancellation(
         ActionMailbox.CancellationStatus status,
         Optional<CompletionStage<ActionMailbox.CancellationStatus>> completion,
         boolean exactQueuedActionRemoved
      ) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(completion, "completion");
         if (status == ActionMailbox.CancellationStatus.ENQUEUED != completion.isPresent()) {
            throw new IllegalArgumentException("Only an enqueued cancellation has a completion stage");
         } else if (exactQueuedActionRemoved
            && status != ActionMailbox.CancellationStatus.ENQUEUED) {
            throw new IllegalArgumentException(
               "Only an enqueued cancellation can remove a queued action"
            );
         } else {
            this.status = status;
            this.completion = completion;
            this.exactQueuedActionRemoved = exactQueuedActionRemoved;
         }
      }

      private static ActionMailbox.Cancellation enqueued(
         CompletionStage<ActionMailbox.CancellationStatus> var0,
         boolean var1
      ) {
         return new ActionMailbox.Cancellation(
            ActionMailbox.CancellationStatus.ENQUEUED, Optional.of(var0), var1
         );
      }

      private static ActionMailbox.Cancellation rejected(ActionMailbox.CancellationStatus var0) {
         return new ActionMailbox.Cancellation(var0, Optional.empty(), false);
      }
   }

   public static enum CancellationStatus {
      ENQUEUED,
      CANCELLED,
      ALREADY_TERMINAL,
      CLEANUP_FAILED,
      NOT_FOUND,
      MAILBOX_FULL,
      COMPLETION_BACKPRESSURE,
      RUNTIME_CLOSED;
   }

   sealed interface Command permits ActionMailbox.SubmitCommand, ActionMailbox.CancelCommand {
   }

   public static record Submission(ActionMailbox.SubmissionStatus status, Optional<CompletionStage<ActionOutcome>> completion) {
      public Submission(ActionMailbox.SubmissionStatus status, Optional<CompletionStage<ActionOutcome>> completion) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(completion, "completion");
         if (status == ActionMailbox.SubmissionStatus.ENQUEUED != completion.isPresent()) {
            throw new IllegalArgumentException("Only an enqueued submission has a completion stage");
         } else {
            this.status = status;
            this.completion = completion;
         }
      }

      private static ActionMailbox.Submission enqueued(CompletionStage<ActionOutcome> var0) {
         return new ActionMailbox.Submission(ActionMailbox.SubmissionStatus.ENQUEUED, Optional.of(var0));
      }

      private static ActionMailbox.Submission rejected(ActionMailbox.SubmissionStatus var0) {
         return new ActionMailbox.Submission(var0, Optional.empty());
      }
   }

   public static enum SubmissionStatus {
      ENQUEUED,
      MAILBOX_FULL,
      COMPLETION_BACKPRESSURE,
      BOT_GENERATION_CLOSED,
      RUNTIME_CLOSED;
   }

   static record SubmitCommand(ActionEnvelope envelope, ActionPriority priority, CompletionDispatcher.Completion<ActionOutcome> completion)
      implements ActionMailbox.Command {
   }
}
