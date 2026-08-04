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
      ActionEnvelope.requireNonZero(var1, "botId");
      ActionEnvelope.requireNonZero(var2, "actionId");
      Objects.requireNonNull(var3, "reason");
      if (this.closed.get()) {
         return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.RUNTIME_CLOSED);
      } else {
         CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> var4 =
            this.completionDispatcher.tryReserveCancellation();
         if (var4 == null) {
            return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.COMPLETION_BACKPRESSURE);
         } else {
            ActionMailbox.SubmitCommand var5 = this.submissions
               .stream()
               .filter(var2x -> var2x.envelope().botId().equals(var1) && var2x.envelope().actionId().equals(var2))
               .findFirst()
               .orElse(null);
            ActionMailbox.SubmitCommand var6 = var5 == null
               ? null
               : this.submissions
                  .stream()
                  .filter(
                     var1x -> var1x.envelope().idempotencyKey().equals(var5.envelope().idempotencyKey())
                           && var5.envelope().hasSameIdempotentOperation(var1x.envelope())
                  )
                  .findFirst()
                  .orElseThrow();
            ActionMailbox.CancelCommand var7 = new ActionMailbox.CancelCommand(var1, var2, var3, Optional.ofNullable(var6), var4);
            if (!this.cancellations.offer(var7)) {
               this.completionDispatcher.releaseUnusedReservation(var4);
               return ActionMailbox.Cancellation.rejected(ActionMailbox.CancellationStatus.MAILBOX_FULL);
            } else if (var6 != null && !this.submissions.remove(var6)) {
               throw new IllegalStateException("Queued action disappeared while its cancellation was reserved");
            } else {
               return ActionMailbox.Cancellation.enqueued(var4.future().minimalCompletionStage());
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
      CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> completion
   ) implements ActionMailbox.Command {
      CancelCommand(
         UUID botId,
         UUID actionId,
         ActionCancellationReason reason,
         Optional<ActionMailbox.SubmitCommand> queuedSubmission,
         CompletionDispatcher.Completion<ActionMailbox.CancellationStatus> completion
      ) {
         Objects.requireNonNull(queuedSubmission, "queuedSubmission");
         this.botId = botId;
         this.actionId = actionId;
         this.reason = reason;
         this.queuedSubmission = queuedSubmission;
         this.completion = completion;
      }
   }

   public static record Cancellation(ActionMailbox.CancellationStatus status, Optional<CompletionStage<ActionMailbox.CancellationStatus>> completion) {
      public Cancellation(ActionMailbox.CancellationStatus status, Optional<CompletionStage<ActionMailbox.CancellationStatus>> completion) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(completion, "completion");
         if (status == ActionMailbox.CancellationStatus.ENQUEUED != completion.isPresent()) {
            throw new IllegalArgumentException("Only an enqueued cancellation has a completion stage");
         } else {
            this.status = status;
            this.completion = completion;
         }
      }

      private static ActionMailbox.Cancellation enqueued(CompletionStage<ActionMailbox.CancellationStatus> var0) {
         return new ActionMailbox.Cancellation(ActionMailbox.CancellationStatus.ENQUEUED, Optional.of(var0));
      }

      private static ActionMailbox.Cancellation rejected(ActionMailbox.CancellationStatus var0) {
         return new ActionMailbox.Cancellation(var0, Optional.empty());
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
