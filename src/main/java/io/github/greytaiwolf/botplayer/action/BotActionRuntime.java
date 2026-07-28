package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BotActionRuntime {
   public static final int MAX_COMMANDS_PER_TICK = 4096;
   public static final int MAX_ACTIVE_ACTIONS = 16384;
   public static final int MAX_WAITERS_PER_ACTION = 64;
   public static final int TRANSITION_HISTORY_CAPACITY = 512;
   private final Thread ownerThread;
   private final ActionMailbox mailbox;
   private final ActionLedger ledger;
   private final ControlArbiter arbiter;
   private final ActionBackend backend;
   private final ActionOutcomeSink outcomeSink;
   private final CompletionDispatcher completionDispatcher;
   private final int commandBudget;
   private final int activeCapacity;
   private final Map<BotActionRuntime.ActionKey, BotActionRuntime.Ticket> active = new LinkedHashMap<>();
   private final Map<UUID, Long> unsafeGenerationByBot = new LinkedHashMap<>();
   private final Map<UUID, BotActionRuntime.PendingLifecycleClose> pendingLifecycleCloses = new LinkedHashMap<>();
   private final Deque<ActionTransition> transitionHistory = new ArrayDeque<>(512);
   private long lastTick = -1L;
   private long lastMutationTick = -1L;
   private long cleanupFailureCount;
   private long outcomeSinkFailureCount;
   private boolean mutating;
   private boolean drainingLifecycleCloses;
   private boolean shutdown;

   public BotActionRuntime(ActionBackend var1, int var2, int var3, int var4, int var5) {
      this(var1, var2, var3, var4, var5, Math.max(var2, var5), ActionOutcomeSink.noop());
   }

   public BotActionRuntime(ActionBackend var1, int var2, int var3, int var4, int var5, int var6) {
      this(var1, var2, var3, var4, var5, var6, ActionOutcomeSink.noop());
   }

   public BotActionRuntime(
      ActionBackend var1,
      int var2,
      int var3,
      int var4,
      int var5,
      int var6,
      ActionOutcomeSink var7
   ) {
      this.backend = Objects.requireNonNull(var1, "backend");
      this.outcomeSink = Objects.requireNonNull(var7, "outcomeSink");
      this.completionDispatcher = new CompletionDispatcher(var6);
      if (var4 < 1 || var4 > 4096) {
         throw new IllegalArgumentException("commandBudget must be between 1 and 4096");
      } else if (var5 >= 1 && var5 <= 16384) {
         this.ownerThread = Thread.currentThread();
         this.mailbox = new ActionMailbox(var2, this.completionDispatcher);
         this.ledger = new ActionLedger(var3);
         this.arbiter = new ControlArbiter();
         this.commandBudget = var4;
         this.activeCapacity = var5;
      } else {
         throw new IllegalArgumentException("activeCapacity must be between 1 and 16384");
      }
   }

   public ActionMailbox.Submission submit(ActionEnvelope var1, ActionPriority var2) {
      return this.mailbox.submit(var1, var2);
   }

   public ActionMailbox.Cancellation cancel(UUID var1, UUID var2, ActionCancellationReason var3) {
      return this.mailbox.cancel(var1, var2, var3);
   }

   public BotActionRuntime.TickReport tick(long var1) {
      this.beginMutation(var1);

      BotActionRuntime.TickReport var3;
      try {
         if (var1 == this.lastTick) {
            return new BotActionRuntime.TickReport(true, 0, 0, this.active.size(), this.cleanupFailureCount);
         }

         this.lastTick = var1;
         if (!this.shutdown) {
            List<ActionMailbox.Command> var10 = this.mailbox.drain(this.commandBudget);

            for (ActionMailbox.Command var5 : var10) {
               this.process(var5, var1);
            }

            this.drainPendingLifecycleCloses(var1);
            int var12 = 0;

            for (BotActionRuntime.Ticket var6 : List.copyOf(this.active.values())) {
               if (!var6.state.isTerminal()) {
                  this.advance(var6, var1);
                  var12++;
                  this.drainPendingLifecycleCloses(var1);
               }
            }

            this.drainPendingLifecycleCloses(var1);
            return new BotActionRuntime.TickReport(false, var10.size(), var12, this.active.size(), this.cleanupFailureCount);
         }

         var3 = new BotActionRuntime.TickReport(false, 0, 0, 0, this.cleanupFailureCount);
      } finally {
         this.endMutation();
      }

      return var3;
   }

   public int cancelBotNow(UUID var1, long var2, ActionCancellationReason var4, long var5) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("throughGeneration must be positive");
      } else {
         Objects.requireNonNull(var4, "reason");
         if (this.mutating) {
            if (var5 != this.lastMutationTick) {
               throw new IllegalArgumentException("A reentrant lifecycle close must use the current runtime tick");
            }

            List<ActionMailbox.SubmitCommand> var7 = this.mailbox.closeBotThrough(var1, var2);
            int var8 = this.countActiveThrough(var1, var2) + var7.size();
            this.deferLifecycleClose(var1, var2, var4, var7);
            return var8;
         }

         this.beginMutation(var5);

         int var7;
         try {
            List<ActionMailbox.SubmitCommand> var8 = this.mailbox.closeBotThrough(var1, var2);
            var7 = this.closeBotAtSafeBoundary(var1, var2, var4, var8, var5);
         } finally {
            this.endMutation();
         }

         return var7;
      }
   }

   private int countActiveThrough(UUID var1, long var2) {
      return Math.toIntExact(
         this.active.values().stream()
            .filter(var3 -> var3.envelope.botId().equals(var1)
               && var3.envelope.botGeneration() <= var2
               && !var3.state.isTerminal())
            .count()
      );
   }

   private int closeBotAtSafeBoundary(
      UUID var1,
      long var2,
      ActionCancellationReason var4,
      List<ActionMailbox.SubmitCommand> var5,
      long var6
   ) {
      int var8 = 0;

      for (BotActionRuntime.Ticket var10 : List.copyOf(this.active.values())) {
         if (var10.envelope.botId().equals(var1)
            && var10.envelope.botGeneration() <= var2
            && !var10.state.isTerminal()) {
            this.finish(
               var10,
               ActionState.CANCELLED,
               ActionFailureCode.CANCELLED,
               List.of(),
               cancellationSummary(var4),
               var4 == ActionCancellationReason.RUNTIME_SHUTDOWN
                  ? ActionCleanupReason.RUNTIME_SHUTDOWN
                  : ActionCleanupReason.CANCELLED,
               var6
            );
            var8++;
         }
      }

      for (ActionMailbox.SubmitCommand var9 : var5) {
         this.completeQueuedCancellation(var9, var6, var4);
         var8++;
      }

      return var8;
   }

   private void deferLifecycleClose(
      UUID var1,
      long var2,
      ActionCancellationReason var4,
      List<ActionMailbox.SubmitCommand> var5
   ) {
      BotActionRuntime.PendingLifecycleClose var6 = this.pendingLifecycleCloses.get(var1);
      if (var6 == null) {
         var6 = new BotActionRuntime.PendingLifecycleClose(var2, var4);
         this.pendingLifecycleCloses.put(var1, var6);
      } else {
         var6.merge(var2, var4);
      }

      var6.queued.addAll(var5);
   }

   private void drainPendingLifecycleCloses(long var1) {
      if (!this.drainingLifecycleCloses && !this.pendingLifecycleCloses.isEmpty()) {
         this.drainingLifecycleCloses = true;

         try {
            while (!this.pendingLifecycleCloses.isEmpty()) {
               List<Map.Entry<UUID, BotActionRuntime.PendingLifecycleClose>> var3 =
                  List.copyOf(this.pendingLifecycleCloses.entrySet());
               this.pendingLifecycleCloses.clear();

               for (Map.Entry<UUID, BotActionRuntime.PendingLifecycleClose> var5 : var3) {
                  BotActionRuntime.PendingLifecycleClose var6 = var5.getValue();
                  this.closeBotAtSafeBoundary(
                     var5.getKey(),
                     var6.throughGeneration,
                     var6.reason,
                     var6.queued,
                     var1
                  );
               }
            }
         } finally {
            this.drainingLifecycleCloses = false;
         }
      }
   }

   public BotActionRuntime.GenerationCancellationResult cancelBotGenerationNow(
      UUID var1,
      long var2,
      ActionCancellationReason var4,
      long var5
   ) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         Objects.requireNonNull(var4, "reason");
         this.beginMutation(var5);

         BotActionRuntime.GenerationCancellationResult var7;
         try {
            List<ActionMailbox.SubmitCommand> var8 =
               this.mailbox.drainBotGeneration(var1, var2);
            List<BotActionRuntime.Ticket> var9 = this.active.values().stream()
               .filter(var3 -> var3.envelope.botId().equals(var1)
                  && var3.envelope.botGeneration() == var2
                  && !var3.state.isTerminal())
               .toList();
            int var10 = var9.size() + var8.size();
            int var11 = 0;

            for (BotActionRuntime.Ticket var13 : var9) {
               if (!var13.state.isTerminal()) {
                  ActionOutcome var14 = this.finish(
                     var13,
                     ActionState.CANCELLED,
                     ActionFailureCode.CANCELLED,
                     List.of(),
                     cancellationSummary(var4),
                     var4 == ActionCancellationReason.RUNTIME_SHUTDOWN
                        ? ActionCleanupReason.RUNTIME_SHUTDOWN
                        : ActionCleanupReason.CANCELLED,
                     var5
                  );
                  if (var14.state() != ActionState.CANCELLED) {
                     var11++;
                  }
               }
            }

            for (ActionMailbox.SubmitCommand var12 : var8) {
               if (this.completeQueuedCancellation(var12, var5, var4)
                  == ActionMailbox.CancellationStatus.CLEANUP_FAILED) {
                  var11++;
               }
            }

            boolean var15 = Objects.equals(this.unsafeGenerationByBot.get(var1), var2);
            boolean var16 = this.active.values().stream()
               .anyMatch(var3 -> var3.envelope.botId().equals(var1)
                  && var3.envelope.botGeneration() == var2
                  && !var3.state.isTerminal());
            boolean var17 = this.arbiter.hasLease(var1, var2);
            var7 = new BotActionRuntime.GenerationCancellationResult(
               var10,
               var11,
               var15,
               var16,
               var17
            );
         } finally {
            this.endMutation();
         }

         return var7;
      }
   }

   public boolean recoverBotSafety(UUID var1, long var2, long var4) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         this.beginMutation(var4);

         boolean var9;
         try {
            Long var6 = this.unsafeGenerationByBot.get(var1);
            if (var6 == null || var6 != var2) {
               return true;
            }

            boolean var7 = this.active
               .values()
               .stream()
               .anyMatch(var3 -> var3.envelope.botId().equals(var1) && var3.envelope.botGeneration() == var2 && !var3.state.isTerminal());
            if (var7 || this.arbiter.hasLease(var1, var2)) {
               return false;
            }

            boolean var8;
            try {
               var8 = this.backend.forceSafeReset(var1, var2, var4);
            } catch (RuntimeException var13) {
               var8 = false;
            }

            if (var8) {
               this.unsafeGenerationByBot.remove(var1, var2);
               this.mailbox.recoverBotGeneration(var1, var2);
            }

            var9 = var8;
         } finally {
            this.endMutation();
         }

         return var9;
      }
   }

   public void shutdown(long var1) {
      this.beginMutation(var1);

      try {
         if (!this.shutdown) {
            this.mailbox.close();

            for (ActionMailbox.Command var4 : this.mailbox.drainAll()) {
               this.process(var4, var1);
            }

            for (BotActionRuntime.Ticket var9 : List.copyOf(this.active.values())) {
               this.finish(
                  var9,
                  ActionState.CANCELLED,
                  ActionFailureCode.CANCELLED,
                  List.of(),
                  "Action cancelled because the runtime is shutting down",
                  ActionCleanupReason.RUNTIME_SHUTDOWN,
                  var1
               );
            }

            this.shutdown = true;
            this.completionDispatcher.shutdownAfterQueuedWork();
            return;
         }
      } finally {
         this.endMutation();
      }
   }

   public int activeActionCount() {
      this.assertOwnerThread();
      return this.active.size();
   }

   public int ledgerSize() {
      this.assertOwnerThread();
      return this.ledger.size();
   }

   public int activeLeaseCount() {
      this.assertOwnerThread();
      return this.arbiter.activeLeaseCount();
   }

   public long cleanupFailureCount() {
      this.assertOwnerThread();
      return this.cleanupFailureCount;
   }

   public int pendingCompletionCount() {
      return this.completionDispatcher.pendingCount();
   }

   public Optional<ActionOutcome> completedOutcome(UUID var1, UUID var2) {
      this.assertOwnerThread();
      return this.ledger.completedOutcome(var1, var2);
   }

   public List<ActionTransition> transitionHistory(int var1) {
      this.assertOwnerThread();
      if (var1 < 0 || var1 > 512) {
         throw new IllegalArgumentException("limit must be between 0 and 512");
      } else if (var1 != 0 && !this.transitionHistory.isEmpty()) {
         int var2 = Math.max(0, this.transitionHistory.size() - var1);
         return this.transitionHistory.stream().skip((long)var2).toList();
      } else {
         return List.of();
      }
   }

   private void process(ActionMailbox.Command var1, long var2) {
      Objects.requireNonNull(var1);
      switch (var1) {
         case ActionMailbox.SubmitCommand var6:
            this.processSubmission(var6, var2);
            break;
         case ActionMailbox.CancelCommand var7:
            this.processCancellation(var7, var2);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void processSubmission(ActionMailbox.SubmitCommand var1, long var2) {
      ActionEnvelope var4 = var1.envelope();
      ActionLedger.BeginResult var5 = this.ledger.begin(var4);
      switch (var5.status()) {
         case STARTED:
            this.startTicket(var1, var2);
            break;
         case DUPLICATE_IN_PROGRESS:
            UUID var6 = var5.canonicalActionId().orElseThrow();
            BotActionRuntime.Ticket var7 = this.active.get(new BotActionRuntime.ActionKey(var4.botId(), var6));
            if (var7 == null) {
               throw new IllegalStateException("Ledger has an in-progress action without an active ticket");
            }

            if (var7.waiters.size() < 64 && this.ledger.registerAlias(var4, var6)) {
               var7.waiters.add(var1.completion());
            } else {
               this.publish(
                  var1.completion(),
                  this.rejectedOutcome(var4, var2, ActionFailureCode.DUPLICATE_IN_PROGRESS, "Too many callers are waiting for the canonical action")
               );
            }
            break;
         case REPLAYED:
            this.publish(var1.completion(), this.replayOrAliasFailure(var1, var5, var2));
            break;
         case IDEMPOTENCY_CONFLICT:
            this.publish(
               var1.completion(),
               this.rejectedOutcome(var4, var2, ActionFailureCode.IDEMPOTENCY_CONFLICT, "Idempotency key or action id conflicts with a retained action")
            );
            break;
         case CAPACITY_EXHAUSTED:
            this.publish(var1.completion(), this.rejectedOutcome(var4, var2, ActionFailureCode.LEDGER_CAPACITY_EXCEEDED, "Action ledger capacity is exhausted"));
      }
   }

   private void startTicket(ActionMailbox.SubmitCommand var1, long var2) {
      ActionEnvelope var4 = var1.envelope();
      if (this.active.size() >= this.activeCapacity) {
         ActionOutcome var8 = this.rejectedOutcome(var4, var2, ActionFailureCode.RUNTIME_CAPACITY_EXCEEDED, "Active action capacity is exhausted");
         this.ledger.complete(var4, var8);
         this.publishOutcome(var4, var8);
         this.publish(var1.completion(), var8);
      } else {
         Long var5 = this.unsafeGenerationByBot.get(var4.botId());
         if (var5 != null && var5 == var4.botGeneration()) {
            ActionOutcome var9 = this.rejectedOutcome(
               var4, var2, ActionFailureCode.UNSAFE_CONTROL_STATE, "Bot controls are quarantined after an unsafe cleanup"
            );
            this.ledger.complete(var4, var9);
            this.publishOutcome(var4, var9);
            this.publish(var1.completion(), var9);
         } else {
            BotActionRuntime.Ticket var6 = new BotActionRuntime.Ticket(var4, var1.priority(), var2, var1.completion());
            BotActionRuntime.Ticket var7 = this.active.put(new BotActionRuntime.ActionKey(var4.botId(), var4.actionId()), var6);
            if (var7 != null) {
               throw new IllegalStateException("Duplicate active action id");
            }
         }
      }
   }

   private void processCancellation(ActionMailbox.CancelCommand var1, long var2) {
      BotActionRuntime.ActionKey var4 = new BotActionRuntime.ActionKey(var1.botId(), var1.actionId());
      BotActionRuntime.Ticket var5 = this.active.get(var4);
      if (var5 == null) {
         var5 = this.ledger
            .canonicalActionId(var1.botId(), var1.actionId())
            .map(var2x -> this.active.get(new BotActionRuntime.ActionKey(var1.botId(), var2x)))
            .orElse(null);
      }

      if (var5 != null && !var5.state.isTerminal()) {
         ActionOutcome var8 = this.finish(
            var5,
            ActionState.CANCELLED,
            ActionFailureCode.CANCELLED,
            List.of(),
            cancellationSummary(var1.reason()),
            var1.reason() == ActionCancellationReason.RUNTIME_SHUTDOWN ? ActionCleanupReason.RUNTIME_SHUTDOWN : ActionCleanupReason.CANCELLED,
            var2
         );
         this.publish(
            var1.completion(),
            var8.state() == ActionState.CANCELLED ? ActionMailbox.CancellationStatus.CANCELLED : ActionMailbox.CancellationStatus.CLEANUP_FAILED
         );
         var1.queuedSubmission().ifPresent(var4x -> this.completeQueuedCancellation(var4x, var2, var1.reason()));
      } else if (var1.queuedSubmission().isPresent()) {
         ActionMailbox.CancellationStatus var7 = this.completeQueuedCancellation(var1.queuedSubmission().orElseThrow(), var2, var1.reason());
         this.publish(var1.completion(), var7);
      } else {
         ActionMailbox.CancellationStatus var6 = this.ledger.completedOutcome(var1.botId(), var1.actionId()).isPresent()
            ? ActionMailbox.CancellationStatus.ALREADY_TERMINAL
            : ActionMailbox.CancellationStatus.NOT_FOUND;
         this.publish(var1.completion(), var6);
      }
   }

   private void advance(BotActionRuntime.Ticket var1, long var2) {
      if (var1.envelope.isExpiredAt(var2)) {
         if (var1.state == ActionState.QUEUED) {
            this.transition(var1, ActionState.VALIDATING);
         }

         this.finish(
            var1, ActionState.FAILED, ActionFailureCode.DEADLINE_EXCEEDED, List.of(), "Action deadline exceeded", ActionCleanupReason.DEADLINE_EXCEEDED, var2
         );
      } else if (var1.startedTick >= 0L && var1.envelope.hasExhaustedTickBudget(var1.startedTick, var2)) {
         this.finish(
            var1,
            ActionState.FAILED,
            ActionFailureCode.MAX_TICKS_EXCEEDED,
            List.of(),
            "Action tick budget exceeded",
            ActionCleanupReason.MAX_TICKS_EXCEEDED,
            var2
         );
      } else {
         if (var1.state == ActionState.QUEUED) {
            this.transition(var1, ActionState.VALIDATING);
         }

         if (var1.state == ActionState.VALIDATING) {
            ActionBackend.BackendResult var5 = this.invoke(var1, BotActionRuntime.BackendCall.VALIDATE, var2);
            if (this.acceptIdentity(var1, var5, var2)) {
               switch (var5.step()) {
                  case ACCEPTED:
                     this.acquireAndStart(var1, var2);
                     break;
                  case FAILED:
                     this.finishBackendFailure(var1, var5, var2);
                     break;
                  case STALE:
                     this.finishBackendStale(var1, var5, var2);
                     break;
                  case RUNNING:
                  case READY_TO_VERIFY:
                  case SUCCEEDED:
                     this.finishBackendMismatch(var1, "Backend returned an invalid validation step", var2);
               }
            }
         } else if (var1.state == ActionState.RUNNING) {
            ActionBackend.BackendResult var4 = this.invoke(var1, BotActionRuntime.BackendCall.TICK, var2);
            this.handleExecutionResult(var1, var4, var2);
         } else {
            if (var1.state == ActionState.VERIFYING) {
               this.verify(var1, var2);
            }
         }
      }
   }

   private void acquireAndStart(BotActionRuntime.Ticket var1, long var2) {
      ControlArbiter.AcquireResult var4 = this.arbiter.acquire(var1.envelope, var1.priority);
      if (!var4.acquired()) {
         this.finish(var1, ActionState.FAILED, ActionFailureCode.CHANNEL_BUSY, List.of(), "Required control channel is busy", ActionCleanupReason.FAILED, var2);
      } else {
         var1.lease = var4.lease().orElseThrow();

         for (ControlArbiter.Lease var6 : var4.preempted()) {
            if (var1.state.isTerminal()) {
               break;
            }

            BotActionRuntime.Ticket var7 = this.active.get(new BotActionRuntime.ActionKey(var6.botId(), var6.actionId()));
            if (var7 == null) {
               if (!this.ledger.completedOutcome(var6.botId(), var6.actionId()).isPresent()) {
                  throw new IllegalStateException("Preempted control lease has no active action");
               }
            } else if (!var7.state.isTerminal()) {
               this.finish(
                  var7,
                  ActionState.PREEMPTED,
                  ActionFailureCode.PREEMPTED,
                  List.of(),
                  "Action was preempted by a higher-priority controller",
                  ActionCleanupReason.PREEMPTED,
                  var2
               );
            }
         }

         if (!var1.state.isTerminal()) {
            Long var8 = this.unsafeGenerationByBot.get(var1.envelope.botId());
            if (var8 != null && var8 == var1.envelope.botGeneration()) {
               this.finish(
                  var1,
                  ActionState.FAILED,
                  ActionFailureCode.UNSAFE_CONTROL_STATE,
                  List.of(),
                  "Preempted control could not be reset safely",
                  ActionCleanupReason.FAILED,
                  var2
               );
            } else {
               var1.startedTick = var2;
               this.transition(var1, ActionState.RUNNING);
               var1.backendStarted = true;
               ActionBackend.BackendResult var9 = this.invoke(var1, BotActionRuntime.BackendCall.START, var2);
               this.handleExecutionResult(var1, var9, var2);
            }
         }
      }
   }

   private void handleExecutionResult(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      if (this.acceptIdentity(var1, var2, var3)) {
         switch (var2.step()) {
            case ACCEPTED:
            case SUCCEEDED:
               this.finishBackendMismatch(var1, "Backend returned an invalid execution step", var3);
               break;
            case FAILED:
               this.finishBackendFailure(var1, var2, var3);
               break;
            case STALE:
               this.finishBackendStale(var1, var2, var3);
               break;
            case RUNNING:
            default:
               break;
            case READY_TO_VERIFY:
               this.transition(var1, ActionState.VERIFYING);
               this.verify(var1, var3);
         }
      }
   }

   private void verify(BotActionRuntime.Ticket var1, long var2) {
      ActionBackend.BackendResult var4 = this.invoke(var1, BotActionRuntime.BackendCall.VERIFY, var2);
      if (this.acceptIdentity(var1, var4, var2)) {
         switch (var4.step()) {
            case ACCEPTED:
            case READY_TO_VERIFY:
               this.finishBackendMismatch(var1, "Backend returned an invalid verification step", var2);
               break;
            case FAILED:
               this.finishBackendFailure(var1, var4, var2);
               break;
            case STALE:
               this.finishBackendStale(var1, var4, var2);
               break;
            case RUNNING:
            default:
               break;
            case SUCCEEDED:
               this.finish(var1, ActionState.SUCCEEDED, ActionFailureCode.NONE, var4.evidence(), var4.safeSummary(), ActionCleanupReason.SUCCEEDED, var2);
         }
      }
   }

   private ActionBackend.BackendResult invoke(BotActionRuntime.Ticket var1, BotActionRuntime.BackendCall var2, long var3) {
      ActionBackend.BackendResult var5;
      try {
         var5 = Objects.requireNonNull(switch (var2) {
            case VALIDATE -> this.backend.validate(var1.envelope, var3);
            case START -> this.backend.start(var1.envelope, var3);
            case TICK -> this.backend.tick(var1.envelope, var1.startedTick, var3);
            case VERIFY -> this.backend.verify(var1.envelope, var3);
         }, "backend result");
      } catch (RuntimeException var6) {
         this.drainPendingLifecycleCloses(var3);
         if (!var1.state.isTerminal()) {
            this.finish(var1, ActionState.FAILED, ActionFailureCode.INTERNAL_ERROR, List.of(), "Action backend failed", ActionCleanupReason.FAILED, var3);
         }
         return ActionBackend.BackendResult.failed(var1.envelope, ActionFailureCode.INTERNAL_ERROR, List.of(), "Action backend failed");
      }

      this.drainPendingLifecycleCloses(var3);
      return var5;
   }

   private boolean acceptIdentity(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      if (var1.state.isTerminal()) {
         return false;
      } else if (!var2.botId().equals(var1.envelope.botId()) || var2.botGeneration() != var1.envelope.botGeneration()) {
         this.finish(
            var1,
            ActionState.STALE,
            ActionFailureCode.STALE_GENERATION,
            List.of(),
            "Backend result belongs to a stale bot generation",
            ActionCleanupReason.STALE,
            var3
         );
         return false;
      } else if (!var2.actionId().equals(var1.envelope.actionId())) {
         this.finishBackendMismatch(var1, "Backend result action id does not match", var3);
         return false;
      } else {
         return true;
      }
   }

   private void finishBackendFailure(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      this.finish(var1, ActionState.FAILED, var2.failureCode(), var2.evidence(), var2.safeSummary(), ActionCleanupReason.FAILED, var3);
   }

   private void finishBackendStale(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      this.finish(var1, ActionState.STALE, ActionFailureCode.STALE_GENERATION, var2.evidence(), var2.safeSummary(), ActionCleanupReason.STALE, var3);
   }

   private void finishBackendMismatch(BotActionRuntime.Ticket var1, String var2, long var3) {
      this.finish(var1, ActionState.FAILED, ActionFailureCode.BACKEND_RESULT_MISMATCH, List.of(), var2, ActionCleanupReason.FAILED, var3);
   }

   private ActionOutcome finish(
      BotActionRuntime.Ticket var1, ActionState var2, ActionFailureCode var3, List<ActionEvidence> var4, String var5, ActionCleanupReason var6, long var7
   ) {
      if (var1.outcome != null) {
         return var1.outcome;
      } else if (!var2.isTerminal()) {
         throw new IllegalArgumentException("finish requires a terminal state");
      } else {
         ActionState var9 = var2;
         ActionFailureCode var10 = var3;
         List<ActionEvidence> var11 = var4;
         String var12 = var5;
         if (var1.lease != null && var1.backendStarted && !var1.cleaned) {
            var1.cleaned = true;

            try {
               this.backend.cleanup(var1.envelope, var6, var7);
            } catch (RuntimeException var22) {
               this.cleanupFailureCount++;
               var9 = ActionState.FAILED;
               var10 = ActionFailureCode.INTERNAL_ERROR;
               var11 = List.of(new ActionEvidence("runtime.cleanup", "failed"));
               var12 = "Action cleanup failed";

               boolean var14;
               try {
                  var14 = this.backend.forceSafeReset(var1.envelope.botId(), var1.envelope.botGeneration(), var7);
               } catch (RuntimeException var21) {
                  var14 = false;
               }

               if (!var14) {
                  this.quarantineGeneration(var1, var7);
               }
            } finally {
               this.arbiter.release(var1.lease);
               var1.lease = null;
            }
         } else if (var1.lease != null) {
            this.arbiter.release(var1.lease);
            var1.lease = null;
         }

         if (var1.state == ActionState.QUEUED && var9 == ActionState.FAILED) {
            this.transition(var1, ActionState.VALIDATING);
         }

         this.transition(var1, var9);
         long var13 = var1.startedTick >= 0L ? var1.startedTick : var1.acceptedTick;
         ActionOutcome var15 = new ActionOutcome(var1.envelope.actionId(), var9, var10, var13, var7, var11, var12);
         this.ledger.complete(var1.envelope, var15);
         var1.outcome = var15;
         this.active.remove(new BotActionRuntime.ActionKey(var1.envelope.botId(), var1.envelope.actionId()), var1);
         this.publishOutcome(var1.envelope, var15);

         for (CompletionDispatcher.Completion<ActionOutcome> var17 : var1.waiters) {
            this.publish(var17, var15);
         }

         return var15;
      }
   }

   private void quarantineGeneration(BotActionRuntime.Ticket var1, long var2) {
      UUID var4 = var1.envelope.botId();
      long var5 = var1.envelope.botGeneration();
      Long var7 = this.unsafeGenerationByBot.get(var4);
      if (var7 == null || var7 < var5) {
         this.unsafeGenerationByBot.put(var4, var5);
         List<ActionMailbox.SubmitCommand> var8 =
            this.mailbox.quarantineBotGeneration(var4, var5);

         for (BotActionRuntime.Ticket var10 : List.copyOf(this.active.values())) {
            if (var10 != var1 && var10.envelope.botId().equals(var4) && var10.envelope.botGeneration() <= var5 && !var10.state.isTerminal()) {
               this.finish(
                  var10,
                  ActionState.FAILED,
                  ActionFailureCode.UNSAFE_CONTROL_STATE,
                  List.of(),
                  "Bot controls were quarantined after cleanup failed",
                  ActionCleanupReason.FAILED,
                  var2
               );
            }
         }

         for (ActionMailbox.SubmitCommand var12 : var8) {
            this.completeQueuedFailure(var12, ActionFailureCode.UNSAFE_CONTROL_STATE, "Bot controls were quarantined before the action started", var2);
         }
      }
   }

   private void completeQueuedFailure(ActionMailbox.SubmitCommand var1, ActionFailureCode var2, String var3, long var4) {
      ActionEnvelope var6 = var1.envelope();
      ActionLedger.BeginResult var7 = this.ledger.begin(var6);
      switch (var7.status()) {
         case STARTED:
            BotActionRuntime.Ticket var9 = new BotActionRuntime.Ticket(var6, var1.priority(), var4, var1.completion());
            this.finishDetached(var9, ActionState.FAILED, var2, var3, var4, true);
            break;
         case DUPLICATE_IN_PROGRESS:
            BotActionRuntime.Ticket var8 = this.active.get(new BotActionRuntime.ActionKey(var6.botId(), var7.canonicalActionId().orElseThrow()));
            if (var8 == null) {
               throw new IllegalStateException("Queued failure found a missing canonical action");
            }

            if (var8.waiters.size() < 64 && this.ledger.registerAlias(var6, var7.canonicalActionId().orElseThrow())) {
               var8.waiters.add(var1.completion());
            } else {
               this.publish(
                  var1.completion(),
                  this.rejectedOutcome(var6, var4, ActionFailureCode.DUPLICATE_IN_PROGRESS, "Too many callers are waiting for the canonical action")
               );
            }
            break;
         case REPLAYED:
            this.publish(var1.completion(), this.replayOrAliasFailure(var1, var7, var4));
            break;
         case IDEMPOTENCY_CONFLICT:
            this.publish(
               var1.completion(), this.rejectedOutcome(var6, var4, ActionFailureCode.IDEMPOTENCY_CONFLICT, "Queued action conflicts with a retained action")
            );
            break;
         case CAPACITY_EXHAUSTED:
            this.publish(var1.completion(), this.rejectedOutcome(var6, var4, ActionFailureCode.LEDGER_CAPACITY_EXCEEDED, "Action ledger capacity is exhausted"));
      }
   }

   private ActionMailbox.CancellationStatus completeQueuedCancellation(ActionMailbox.SubmitCommand var1, long var2, ActionCancellationReason var4) {
      ActionEnvelope var5 = var1.envelope();
      ActionLedger.BeginResult var6 = this.ledger.begin(var5);

      return switch (var6.status()) {
         case STARTED -> {
            BotActionRuntime.Ticket var12 = new BotActionRuntime.Ticket(var5, var1.priority(), var2, var1.completion());
            this.finishDetached(var12, ActionState.CANCELLED, ActionFailureCode.CANCELLED, "Action cancelled before leaving the lifecycle mailbox", var2, true);
            yield ActionMailbox.CancellationStatus.CANCELLED;
         }
         case DUPLICATE_IN_PROGRESS -> {
            BotActionRuntime.Ticket var11 = this.active.get(new BotActionRuntime.ActionKey(var5.botId(), var6.canonicalActionId().orElseThrow()));
            if (var11 == null) {
               throw new IllegalStateException("Queued cancellation found a missing canonical action");
            }

            this.ledger.registerAlias(var5, var6.canonicalActionId().orElseThrow());
            ActionOutcome var8 = this.finish(
               var11,
               ActionState.CANCELLED,
               ActionFailureCode.CANCELLED,
               List.of(),
               cancellationSummary(var4),
               var4 == ActionCancellationReason.RUNTIME_SHUTDOWN ? ActionCleanupReason.RUNTIME_SHUTDOWN : ActionCleanupReason.CANCELLED,
               var2
            );
            this.publish(var1.completion(), var8);
            yield var8.state() == ActionState.CANCELLED ? ActionMailbox.CancellationStatus.CANCELLED : ActionMailbox.CancellationStatus.CLEANUP_FAILED;
         }
         case REPLAYED -> {
            ActionOutcome var10 = this.replayOrAliasFailure(var1, var6, var2);
            this.publish(var1.completion(), var10);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
         case IDEMPOTENCY_CONFLICT -> {
            ActionOutcome var9 = this.rejectedOutcome(var5, var2, ActionFailureCode.IDEMPOTENCY_CONFLICT, "Queued action conflicts with a retained action");
            this.publish(var1.completion(), var9);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
         case CAPACITY_EXHAUSTED -> {
            ActionOutcome var7 = this.rejectedOutcome(var5, var2, ActionFailureCode.LEDGER_CAPACITY_EXCEEDED, "Action ledger capacity is exhausted");
            this.publish(var1.completion(), var7);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
      };
   }

   private ActionOutcome replayOrAliasFailure(ActionMailbox.SubmitCommand var1, ActionLedger.BeginResult var2, long var3) {
      ActionEnvelope var5 = var1.envelope();
      UUID var6 = var2.canonicalActionId().orElseThrow();
      return this.ledger.registerAlias(var5, var6)
         ? var2.replayedOutcome().orElseThrow()
         : this.rejectedOutcome(var5, var3, ActionFailureCode.ACTION_ALIAS_CAPACITY_EXCEEDED, "Retained action alias capacity is exhausted");
   }

   private ActionOutcome finishDetached(BotActionRuntime.Ticket var1, ActionState var2, ActionFailureCode var3, String var4, long var5, boolean var7) {
      if (var2 == ActionState.FAILED) {
         this.transition(var1, ActionState.VALIDATING);
      }

      this.transition(var1, var2);
      ActionOutcome var8 = new ActionOutcome(var1.envelope.actionId(), var2, var3, var5, var5, List.of(), var4);
      if (var7) {
         this.ledger.complete(var1.envelope, var8);
         this.publishOutcome(var1.envelope, var8);
      }

      var1.outcome = var8;

      for (CompletionDispatcher.Completion<ActionOutcome> var10 : var1.waiters) {
         this.publish(var10, var8);
      }

      return var8;
   }

   private ActionOutcome rejectedOutcome(ActionEnvelope var1, long var2, ActionFailureCode var4, String var5) {
      BotActionRuntime.Ticket var6 = new BotActionRuntime.Ticket(var1, ActionPriority.BACKGROUND, var2);
      return this.finishDetached(var6, ActionState.FAILED, var4, var5, var2, false);
   }

   private void transition(BotActionRuntime.Ticket var1, ActionState var2) {
      ActionState var3 = var1.state;
      var3.requireTransitionTo(var2);
      if (this.transitionHistory.size() == 512) {
         this.transitionHistory.removeFirst();
      }

      this.transitionHistory
         .addLast(
            new ActionTransition(
               var1.envelope.botId(), var1.envelope.botGeneration(), var1.envelope.actionId(), var1.envelope.action().kind(), var3, var2, this.lastMutationTick
            )
         );
      var1.state = var2;
   }

   private static String cancellationSummary(ActionCancellationReason var0) {
      return switch (var0) {
         case REQUESTED -> "Action cancellation was requested";
         case LIFECYCLE -> "Action cancelled by a bot lifecycle transition";
         case RUNTIME_SHUTDOWN -> "Action cancelled because the runtime is shutting down";
      };
   }

   private <T> void publish(CompletionDispatcher.Completion<T> var1, T var2) {
      this.completionDispatcher.dispatch(var1, var2);
   }

   private void publishOutcome(ActionEnvelope var1, ActionOutcome var2) {
      try {
         this.outcomeSink.accept(var1, var2);
      } catch (RuntimeException var4) {
         this.outcomeSinkFailureCount++;
      }
   }

   public long outcomeSinkFailureCount() {
      this.assertOwnerThread();
      return this.outcomeSinkFailureCount;
   }

   private void beginMutation(long var1) {
      this.assertOwnerThread();
      requireTick(var1);
      if (this.mutating) {
         throw new IllegalStateException("BotActionRuntime mutation is already in progress");
      } else if (var1 < this.lastMutationTick) {
         throw new IllegalArgumentException("currentTick must not precede the last runtime mutation");
      } else {
         this.mutating = true;
         this.lastMutationTick = var1;
      }
   }

   private void endMutation() {
      if (!this.mutating) {
         throw new IllegalStateException("BotActionRuntime mutation is not in progress");
      } else {
         try {
            this.drainPendingLifecycleCloses(this.lastMutationTick);
         } finally {
            this.mutating = false;
         }
      }
   }

   private void assertOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("BotActionRuntime accessed outside its owner thread");
      }
   }

   private static void requireTick(long var0) {
      if (var0 < 0L) {
         throw new IllegalArgumentException("currentTick must not be negative");
      }
   }

   private static record ActionKey(UUID botId, UUID actionId) {
   }

   private static enum BackendCall {
      VALIDATE,
      START,
      TICK,
      VERIFY;
   }

   public static record TickReport(boolean duplicateTick, int drainedCommands, int advancedActions, int activeActions, long cleanupFailures) {
      public TickReport(boolean duplicateTick, int drainedCommands, int advancedActions, int activeActions, long cleanupFailures) {
         if (drainedCommands >= 0 && advancedActions >= 0 && activeActions >= 0 && cleanupFailures >= 0L) {
            this.duplicateTick = duplicateTick;
            this.drainedCommands = drainedCommands;
            this.advancedActions = advancedActions;
            this.activeActions = activeActions;
            this.cleanupFailures = cleanupFailures;
         } else {
            throw new IllegalArgumentException("Tick report counters must not be negative");
         }
      }
   }

   public static record GenerationCancellationResult(
      int cancelledActions,
      int cleanupFailures,
      boolean quarantined,
      boolean ticketRemaining,
      boolean leaseRemaining
   ) {
      public GenerationCancellationResult(
         int cancelledActions,
         int cleanupFailures,
         boolean quarantined,
         boolean ticketRemaining,
         boolean leaseRemaining
      ) {
         if (cancelledActions < 0 || cleanupFailures < 0) {
            throw new IllegalArgumentException("generation cancellation counters must not be negative");
         } else if (cleanupFailures > cancelledActions) {
            throw new IllegalArgumentException("cleanupFailures cannot exceed cancelledActions");
         } else {
            this.cancelledActions = cancelledActions;
            this.cleanupFailures = cleanupFailures;
            this.quarantined = quarantined;
            this.ticketRemaining = ticketRemaining;
            this.leaseRemaining = leaseRemaining;
         }
      }

      public boolean safeForExclusiveMutation() {
         return this.cleanupFailures == 0
            && !this.quarantined
            && !this.ticketRemaining
            && !this.leaseRemaining;
      }
   }

   private static final class PendingLifecycleClose {
      private long throughGeneration;
      private ActionCancellationReason reason;
      private final List<ActionMailbox.SubmitCommand> queued = new ArrayList<>();

      private PendingLifecycleClose(long var1, ActionCancellationReason var3) {
         this.throughGeneration = var1;
         this.reason = var3;
      }

      private void merge(long var1, ActionCancellationReason var3) {
         this.throughGeneration = Math.max(this.throughGeneration, var1);
         this.reason = strongerReason(this.reason, var3);
      }

      private static ActionCancellationReason strongerReason(
         ActionCancellationReason var0,
         ActionCancellationReason var1
      ) {
         if (var0 == ActionCancellationReason.RUNTIME_SHUTDOWN
            || var1 == ActionCancellationReason.RUNTIME_SHUTDOWN) {
            return ActionCancellationReason.RUNTIME_SHUTDOWN;
         } else if (var0 == ActionCancellationReason.LIFECYCLE
            || var1 == ActionCancellationReason.LIFECYCLE) {
            return ActionCancellationReason.LIFECYCLE;
         } else {
            return ActionCancellationReason.REQUESTED;
         }
      }
   }

   private static final class Ticket {
      private final ActionEnvelope envelope;
      private final ActionPriority priority;
      private final long acceptedTick;
      private final List<CompletionDispatcher.Completion<ActionOutcome>> waiters = new ArrayList<>();
      private ActionState state = ActionState.QUEUED;
      private long startedTick = -1L;
      private ControlArbiter.Lease lease;
      private ActionOutcome outcome;
      private boolean cleaned;
      private boolean backendStarted;

      private Ticket(ActionEnvelope var1, ActionPriority var2, long var3) {
         this.envelope = var1;
         this.priority = var2;
         this.acceptedTick = var3;
      }

      private Ticket(ActionEnvelope var1, ActionPriority var2, long var3, CompletionDispatcher.Completion<ActionOutcome> var5) {
         this(var1, var2, var3);
         this.waiters.add(Objects.requireNonNull(var5, "completion"));
      }
   }
}
