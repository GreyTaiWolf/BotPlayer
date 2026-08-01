package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BotActionRuntime {
   public static final int MAX_COMMANDS_PER_TICK = 4096;
   public static final int MAX_ACTIVE_ACTIONS = 16384;
   public static final int MAX_WAITERS_PER_ACTION = 64;
   public static final int TRANSITION_HISTORY_CAPACITY = 512;
   /** 动作业务终止后，物理清理最多保留的独立 Tick 预算。 */
   public static final int MAX_CLEANUP_TICKS = 256;
   /** Exact unsafe-generation keys retained before the runtime fails closed. */
   public static final int MAX_QUARANTINED_GENERATIONS = 4096;
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
   private final Set<BotActionRuntime.GenerationKey> unsafeGenerations = new LinkedHashSet<>();
   /**
    * Exact consumed intents retained only while their active ticket drains;
    * size is therefore bounded by {@link #activeCapacity}.
    */
   private final Set<BotActionRuntime.GenerationKey> vanillaDeathConsumedGenerations = new LinkedHashSet<>();
   private final Map<BotActionRuntime.GenerationKey, BotActionRuntime.PendingGenerationQuarantine> pendingGenerationQuarantines = new LinkedHashMap<>();
   private final Set<BotActionRuntime.GenerationKey> quarantinesInProgress = new LinkedHashSet<>();
   private final Map<BotActionRuntime.GenerationKey, BotActionRuntime.PendingVanillaDeathConsumption> pendingVanillaDeathConsumptions = new LinkedHashMap<>();
   private final Set<BotActionRuntime.GenerationKey> vanillaDeathConsumptionsInProgress = new LinkedHashSet<>();
   private final List<ActionMailbox.Command> pendingRuntimeFailClosedCommands = new ArrayList<>();
   private final Map<UUID, BotActionRuntime.PendingLifecycleClose> pendingLifecycleCloses = new LinkedHashMap<>();
   private final Deque<ActionTransition> transitionHistory = new ArrayDeque<>(512);
   private long lastTick = -1L;
   private long lastMutationTick = -1L;
   private long cleanupFailureCount;
   private long outcomeSinkFailureCount;
   private boolean mutating;
   private boolean quarantineCapacityExhausted;
   private boolean pendingRuntimeFailClosed;
   private boolean runtimeFailClosedInProgress;
   private boolean drainingGenerationQuarantines;
   private boolean drainingVanillaDeathConsumptions;
   private boolean drainingLifecycleCloses;
   private boolean shutdown;
   private boolean shutdownComplete;

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
         List<ActionMailbox.Command> var10 = this.shutdown
            ? List.of()
            : this.mailbox.drain(this.commandBudget);

         for (ActionMailbox.Command var5 : var10) {
            this.process(var5, var1);
            this.drainPendingSafeBoundaryWork(var1);
         }

         this.drainPendingSafeBoundaryWork(var1);
         int var12 = 0;

         for (BotActionRuntime.Ticket var6 : this.orderedActiveTickets()) {
            if (!var6.state.isTerminal()) {
               this.advance(var6, var1);
               var12++;
               this.drainPendingSafeBoundaryWork(var1);
            }
         }

         this.drainPendingSafeBoundaryWork(var1);
         this.completeShutdownIfDrained();
         var3 = new BotActionRuntime.TickReport(
            false,
            var10.size(),
            var12,
            this.active.size(),
            this.cleanupFailureCount
         );
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

   private void drainPendingSafeBoundaryWork(long var1) {
      while (true) {
         if (!this.runtimeFailClosedInProgress
            && this.pendingRuntimeFailClosed) {
            this.drainPendingRuntimeFailClosed(var1);
         } else if (!this.drainingGenerationQuarantines
            && !this.pendingGenerationQuarantines.isEmpty()) {
            this.drainPendingGenerationQuarantines(var1);
         } else if (!this.drainingVanillaDeathConsumptions
            && !this.pendingVanillaDeathConsumptions.isEmpty()) {
            this.drainPendingVanillaDeathConsumptions(var1);
         } else {
            if (this.drainingLifecycleCloses
               || this.pendingLifecycleCloses.isEmpty()) {
               return;
            }

            this.drainPendingLifecycleCloses(var1);
         }
      }
   }

   private void requestVanillaDeathConsumption(
      BotActionRuntime.GenerationKey key
   ) {
      List<ActionMailbox.SubmitCommand> queued =
         this.mailbox.closeBotThrough(
            key.botId(), key.generation()
         );
      boolean exactGenerationActive = this.hasActiveGeneration(key);
      if (exactGenerationActive) {
         this.vanillaDeathConsumedGenerations.add(key);
      }
      List<BotActionRuntime.GenerationKey> olderActiveGenerations =
         this.active.values().stream()
            .filter(ticket -> !ticket.state.isTerminal()
               && ticket.envelope.botId().equals(key.botId())
               && ticket.envelope.botGeneration() < key.generation())
            .map(ticket -> new BotActionRuntime.GenerationKey(
               ticket.envelope.botId(),
               ticket.envelope.botGeneration()
            ))
            .distinct()
            .toList();
      for (BotActionRuntime.GenerationKey older
            : olderActiveGenerations) {
         this.requestGenerationQuarantine(older);
      }
      if (!olderActiveGenerations.isEmpty()) {
         this.requestGenerationQuarantine(key);
      }
      if (this.vanillaDeathConsumptionsInProgress.contains(key)) {
         return;
      }
      if (!queued.isEmpty() || exactGenerationActive) {
         this.pendingVanillaDeathConsumptions
            .computeIfAbsent(
               key,
               ignored -> new BotActionRuntime.PendingVanillaDeathConsumption()
            )
            .queued.addAll(queued);
      }
   }

   private void drainPendingVanillaDeathConsumptions(long currentTick) {
      if (this.drainingVanillaDeathConsumptions
         || this.pendingVanillaDeathConsumptions.isEmpty()) {
         return;
      }
      this.drainingVanillaDeathConsumptions = true;
      try {
         while (!this.pendingVanillaDeathConsumptions.isEmpty()) {
            List<Map.Entry<BotActionRuntime.GenerationKey,
               BotActionRuntime.PendingVanillaDeathConsumption>> pending =
               List.copyOf(this.pendingVanillaDeathConsumptions.entrySet());
            this.pendingVanillaDeathConsumptions.clear();
            for (Map.Entry<BotActionRuntime.GenerationKey,
                    BotActionRuntime.PendingVanillaDeathConsumption> entry
                    : pending) {
               BotActionRuntime.GenerationKey key = entry.getKey();
               this.vanillaDeathConsumptionsInProgress.add(key);
               try {
                  this.consumeGenerationAfterVanillaDeathAtSafeBoundary(
                     key, entry.getValue().queued, currentTick
                  );
               } finally {
                  this.vanillaDeathConsumptionsInProgress.remove(key);
                  this.releaseVanillaDeathConsumedIntent(key);
               }
            }
         }
      } finally {
         this.drainingVanillaDeathConsumptions = false;
      }
   }

   private void consumeGenerationAfterVanillaDeathAtSafeBoundary(
      BotActionRuntime.GenerationKey key,
      List<ActionMailbox.SubmitCommand> queued,
      long currentTick
   ) {
      for (BotActionRuntime.Ticket ticket
            : List.copyOf(this.active.values())) {
         if (!ticket.state.isTerminal()
            && ticket.envelope.botId().equals(key.botId())
            && ticket.envelope.botGeneration() == key.generation()) {
            if (this.isTerminationGenerationUnsafe(ticket)) {
               this.replaceUnsafeTerminationForVanillaDeath(
                  ticket,
                  "Bot controls were unsafe when vanilla death consumed the player body state",
                  currentTick
               );
            } else {
               this.replaceTerminationForVanillaDeath(
                  ticket, currentTick
               );
            }
            this.advanceTermination(ticket, currentTick);
         }
      }
      for (ActionMailbox.SubmitCommand submission : queued) {
         this.completeQueuedCancellation(
            submission,
            currentTick,
            ActionCancellationReason.LIFECYCLE
         );
      }
   }

   private void replaceTerminationForVanillaDeath(
      BotActionRuntime.Ticket ticket, long currentTick
   ) {
      this.replaceTerminationWithVanillaDeathCleanup(
         ticket,
         ActionState.CANCELLED,
         ActionFailureCode.CANCELLED,
         List.of(),
         "Action cancelled after vanilla death consumed the player body state",
         currentTick
      );
   }

   private void replaceUnsafeTerminationForVanillaDeath(
      BotActionRuntime.Ticket ticket,
      String summary,
      long currentTick
   ) {
      this.replaceTerminationWithVanillaDeathCleanup(
         ticket,
         ActionState.FAILED,
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         List.of(),
         summary,
         currentTick
      );
   }

   private void replaceTerminationWithVanillaDeathCleanup(
      BotActionRuntime.Ticket ticket,
      ActionState state,
      ActionFailureCode failureCode,
      List<ActionEvidence> evidence,
      String summary,
      long currentTick
   ) {
      if (ticket.termination != null
         && ticket.termination.cleanupReason
            == ActionCleanupReason.VANILLA_DEATH_CONSUMED
         && (ticket.termination.state == ActionState.FAILED
            || state != ActionState.FAILED)) {
         return;
      }
      ticket.termination = new BotActionRuntime.PendingTermination(
         state,
         failureCode,
         evidence,
         summary,
         ActionCleanupReason.VANILLA_DEATH_CONSUMED,
         currentTick,
         currentTick
      );
      ticket.cleanupRequest = null;
      ticket.cleanupReceipt = null;
      ticket.lastCleanupAttemptTick = -1L;
      ticket.cleanupProgressRevision = -1L;
      ticket.cleanupFailureRecorded = false;
      ticket.preemptionClaimed = false;
   }

   private boolean wasVanillaDeathConsumed(
      BotActionRuntime.GenerationKey key
   ) {
      return this.vanillaDeathConsumedGenerations.contains(key);
   }

   private boolean hasActiveOlderGeneration(
      BotActionRuntime.GenerationKey key
   ) {
      return this.active.values().stream()
         .anyMatch(ticket -> !ticket.state.isTerminal()
            && ticket.envelope.botId().equals(key.botId())
            && ticket.envelope.botGeneration() < key.generation());
   }

   private void releaseVanillaDeathConsumedIntent(
      BotActionRuntime.GenerationKey key
   ) {
      if (this.vanillaDeathConsumedGenerations.contains(key)
         && !this.hasActiveGeneration(key)
         && !this.pendingVanillaDeathConsumptions.containsKey(key)
         && !this.vanillaDeathConsumptionsInProgress.contains(key)) {
         this.vanillaDeathConsumedGenerations.remove(key);
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
                  if (var14 != null
                     && var14.state() != ActionState.CANCELLED) {
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

            this.drainPendingSafeBoundaryWork(var5);
            BotActionRuntime.GenerationKey var18 =
               new BotActionRuntime.GenerationKey(var1, var2);
            boolean var15 = this.quarantineCapacityExhausted
               || this.unsafeGenerations.contains(var18);
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

   /**
    * Consumes one exact authoritative generation after vanilla death has
    * consumed that body's mutable inventory/menu state. Ingress is permanently
    * closed through that generation by the mailbox's per-bot high-water mark;
    * active cleanup still matches only the exact generation.
    *
    * <p>This lifecycle-only boundary never asks an action backend to restore
    * the dead body's physical layout. Queued work is cancelled directly;
    * started work receives a new, one-attempt
    * {@link ActionCleanupReason#VANILLA_DEATH_CONSUMED} cleanup transaction.
    */
   public GenerationDrainStatus consumeBotGenerationForVanillaDeathNow(
      UUID botId,
      long botGeneration,
      long currentTick
   ) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(botId, "botId");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException(
            "botGeneration must be positive"
         );
      }
      BotActionRuntime.GenerationKey key =
         new BotActionRuntime.GenerationKey(botId, botGeneration);
      if (this.mutating) {
         if (currentTick != this.lastMutationTick) {
            throw new IllegalArgumentException(
               "A reentrant vanilla death consumption must use the current runtime tick"
            );
         }
         this.requestVanillaDeathConsumption(key);
         return this.inspectGenerationStatus(botId, botGeneration, true);
      }

      this.beginMutation(currentTick);
      try {
         this.requestVanillaDeathConsumption(key);
         this.drainPendingSafeBoundaryWork(currentTick);
         return this.inspectGenerationStatus(botId, botGeneration, true);
      } finally {
         this.endMutation();
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
            if (this.quarantineCapacityExhausted) {
               return false;
            }

            BotActionRuntime.GenerationKey var6 =
               new BotActionRuntime.GenerationKey(var1, var2);
            if (!this.unsafeGenerations.contains(var6)) {
               return true;
            }
            boolean var7 = this.active
               .values()
               .stream()
               .anyMatch(var3 -> var3.envelope.botId().equals(var1) && var3.envelope.botGeneration() == var2 && !var3.state.isTerminal());
            if (var7
               || this.pendingGenerationQuarantines.containsKey(var6)
               || this.quarantinesInProgress.contains(var6)) {
               return false;
            }

            boolean var8;
            try {
               var8 = this.backend.forceSafeReset(var1, var2, var4);
            } catch (RuntimeException var13) {
               var8 = false;
            }

            if (var8
               && this.unsafeGenerations.contains(var6)
               && !this.pendingRuntimeFailClosed
               && !this.runtimeFailClosedInProgress
               && !this.pendingGenerationQuarantines.containsKey(var6)
               && !this.quarantinesInProgress.contains(var6)) {
               this.arbiter.releaseGenerationAfterSafeReset(var1, var2);
               this.unsafeGenerations.remove(var6);
               this.mailbox.recoverBotGeneration(var1, var2);
            } else {
               var8 = false;
            }

            var9 = var8;
         } finally {
            this.endMutation();
         }

         return var9;
      }
   }

   /**
    * 隔离一个已被上层事务判定为无法安全补偿的 generation。
    *
    * <p>这条入口与 backend cleanup 失败使用同一隔离路径：终结同 generation
    * 的活动/排队动作，并拒绝后续动作，直到显式安全恢复。
    * 若 exact 隔离集合耗尽，runtime 会全局 fail-closed，而不会退化为 generation
    * 高水位语义。
    */
   public BotActionRuntime.GenerationQuarantineResult quarantineBotGenerationNow(
      UUID var1,
      long var2,
      long var4
   ) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         BotActionRuntime.GenerationKey var6 =
            new BotActionRuntime.GenerationKey(var1, var2);
         if (this.mutating) {
            if (var4 != this.lastMutationTick) {
               throw new IllegalArgumentException(
                  "A reentrant generation quarantine must use the current runtime tick"
               );
            }

            int var7 = this.requestGenerationQuarantine(var6);
            return this.generationQuarantineResult(var6, var7);
         }

         this.beginMutation(var4);

         BotActionRuntime.GenerationQuarantineResult var8;
         try {
            int var7 = this.requestGenerationQuarantine(var6);
            this.drainPendingSafeBoundaryWork(var4);
            var8 = this.generationQuarantineResult(var6, var7);
         } finally {
            this.endMutation();
         }

         return var8;
      }
   }

   public boolean isGenerationSafe(UUID var1, long var2) {
      return this.inspectGenerationStatus(var1, var2, false)
         == GenerationDrainStatus.COMPLETE;
   }

   /**
    * 区分“仍在有界清理”与“该代已经不可安全提交”。生命周期层只能把
    * COMPLETE 当作可保存/可接管的授权；PENDING 需要在后续 Tick 继续观察。
    */
   public GenerationDrainStatus generationDrainStatus(
      UUID var1,
      long var2
   ) {
      return this.inspectGenerationStatus(var1, var2, true);
   }

   private GenerationDrainStatus inspectGenerationStatus(
      UUID var1,
      long var2,
      boolean requireClosedIngress
   ) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         BotActionRuntime.GenerationKey var4 =
            new BotActionRuntime.GenerationKey(var1, var2);
         if (this.quarantineCapacityExhausted
            || this.unsafeGenerations.contains(var4)
            || (this.wasVanillaDeathConsumed(var4)
               && this.hasActiveOlderGeneration(var4))
            || this.pendingRuntimeFailClosed
            || this.runtimeFailClosedInProgress
            || this.pendingGenerationQuarantines.containsKey(var4)
            || this.quarantinesInProgress.contains(var4)) {
            return GenerationDrainStatus.UNSAFE;
         }
         BotActionRuntime.PendingLifecycleClose var5 =
            this.pendingLifecycleCloses.get(var1);
         return GenerationDrainStatus.classify(
            false,
            (requireClosedIngress
               && !this.mailbox.isGenerationIngressClosed(var1, var2))
               || this.hasActiveGeneration(var4)
               || this.pendingVanillaDeathConsumptions.containsKey(var4)
               || this.vanillaDeathConsumptionsInProgress.contains(var4)
               || (var5 != null && var2 <= var5.throughGeneration),
            this.arbiter.hasLease(var1, var2)
         );
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

            /*
             * 停服事件没有下一服务器 Tick 合同。首次清理仍未到安全端点时，
             * 立即转入精确 generation 隔离，不能留下永久占用的 lease。
             */
            for (BotActionRuntime.Ticket var9
               : List.copyOf(this.active.values())) {
               if (var9.termination != null
                  && var9.outcome == null) {
                  this.failCleanupUnsafe(var9, var1);
               }
            }

            this.shutdown = true;
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

   Optional<UUID> activeLeaseOwner(
      UUID var1, ActionChannel var2
   ) {
      this.assertOwnerThread();
      return this.arbiter.currentLease(var1, var2)
         .map(ControlArbiter.Lease::actionId);
   }

   int pendingCancellationWaiterCount(UUID var1, UUID var2) {
      this.assertOwnerThread();
      BotActionRuntime.Ticket var3 = this.active.get(
         new BotActionRuntime.ActionKey(var1, var2)
      );
      return var3 == null ? 0 : var3.cancellationWaiters.size();
   }

   int retainedVanillaDeathConsumedIntentCount() {
      this.assertOwnerThread();
      return this.vanillaDeathConsumedGenerations.size();
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
      BotActionRuntime.GenerationKey var5 =
         new BotActionRuntime.GenerationKey(
            var4.botId(), var4.botGeneration()
         );
      if (this.quarantineCapacityExhausted
         || this.unsafeGenerations.contains(var5)) {
         ActionOutcome var9 = this.rejectedOutcome(
            var4, var2, ActionFailureCode.UNSAFE_CONTROL_STATE, "Bot controls are quarantined after an unsafe cleanup"
         );
         this.ledger.complete(var4, var9);
         this.publishOutcome(var4, var9);
         this.publish(var1.completion(), var9);
      } else if (this.active.size() >= this.activeCapacity) {
         ActionOutcome var8 = this.rejectedOutcome(var4, var2, ActionFailureCode.RUNTIME_CAPACITY_EXCEEDED, "Active action capacity is exhausted");
         this.ledger.complete(var4, var8);
         this.publishOutcome(var4, var8);
         this.publish(var1.completion(), var8);
      } else {
         BotActionRuntime.Ticket var6 = new BotActionRuntime.Ticket(var4, var1.priority(), var2, var1.completion());
         BotActionRuntime.Ticket var7 = this.active.put(new BotActionRuntime.ActionKey(var4.botId(), var4.actionId()), var6);
         if (var7 != null) {
            throw new IllegalStateException("Duplicate active action id");
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
         if (var8 == null) {
            if (!this.queueCancellationWaiter(
               var5, var1, false
            )) {
               var1.queuedSubmission().ifPresent(
                  var4x -> this.completeQueuedCancellation(
                     var4x, var2, var1.reason()
                  )
               );
               this.publish(
                  var1.completion(),
                  ActionMailbox.CancellationStatus.COMPLETION_BACKPRESSURE
               );
            }
         } else {
            this.publish(
               var1.completion(),
               var8.state() == ActionState.CANCELLED ? ActionMailbox.CancellationStatus.CANCELLED : ActionMailbox.CancellationStatus.CLEANUP_FAILED
            );
            var1.queuedSubmission().ifPresent(var4x -> this.completeQueuedCancellation(var4x, var2, var1.reason()));
         }
      } else if (var1.queuedSubmission().isPresent()) {
         ActionMailbox.CancellationStatus var7 =
            this.completeQueuedCancellation(
               var1.queuedSubmission().orElseThrow(),
               var2,
               var1.reason(),
               var1
            );
         if (var7 != null) {
            this.publish(var1.completion(), var7);
         }
      } else {
         ActionMailbox.CancellationStatus var6 = this.ledger.completedOutcome(var1.botId(), var1.actionId()).isPresent()
            ? ActionMailbox.CancellationStatus.ALREADY_TERMINAL
            : ActionMailbox.CancellationStatus.NOT_FOUND;
         this.publish(var1.completion(), var6);
      }
   }

   private void advance(BotActionRuntime.Ticket var1, long var2) {
      if (var1.termination != null) {
         this.advanceTermination(var1, var2);
      } else if (var1.backendStarted
         && !this.ensureSafeControlAuthority(var1, var2)) {
         return;
      } else if (var1.envelope.isExpiredAt(var2)) {
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
            if (var1.controlValidated) {
               this.acquireAndStart(var1, var2);
               return;
            }

            ActionBackend.BackendResult var5 = this.invoke(var1, BotActionRuntime.BackendCall.VALIDATE, var2);
            if (this.acceptIdentity(var1, var5, var2)) {
               switch (var5.step()) {
                  case ACCEPTED:
                     var1.controlValidated = true;
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
      if (!this.isLiveActiveTicket(var1)) {
         return;
      }
      if (var1.lastAcquireAttemptTick == var2) {
         return;
      }
      var1.lastAcquireAttemptTick = var2;
      if (this.hasSuperiorPreemptionClaim(var1)) {
         return;
      }
      if (this.hasUnsafePreemptionBarrier(var1)) {
         this.finish(
            var1,
            ActionState.FAILED,
            ActionFailureCode.UNSAFE_CONTROL_STATE,
            List.of(),
            "A displaced control generation could not be contained safely",
            ActionCleanupReason.FAILED,
            var2
         );
         return;
      }

      ControlArbiter.AcquireResult var4 = this.arbiter.acquire(var1.envelope, var1.priority);
      if (var4.status()
         == ControlArbiter.AcquireStatus.PREEMPTION_REQUIRED) {
         var1.preemptionClaimed = true;
         boolean var5 = false;
         for (ControlArbiter.Lease var6 : var4.preemptionCandidates()) {
            BotActionRuntime.GenerationKey var7 =
               new BotActionRuntime.GenerationKey(
                  var6.botId(), var6.botGeneration()
               );
            var1.preemptionBarriers.add(var7);
            BotActionRuntime.Ticket var8 = this.active.get(
               new BotActionRuntime.ActionKey(
                  var6.botId(), var6.actionId()
               )
            );
            if (this.arbiter.isHeld(var6)
               && (var8 == null
               || var8.state.isTerminal()
               || var8.lease != var6)) {
               this.requestGenerationQuarantine(var7);
               var5 = true;
            }
         }
         this.drainPendingSafeBoundaryWork(var2);
         if (!this.isLiveActiveTicket(var1)) {
            return;
         }
         if (var5 || this.hasUnsafePreemptionBarrier(var1)) {
            this.finish(
               var1,
               ActionState.FAILED,
               ActionFailureCode.UNSAFE_CONTROL_STATE,
               List.of(),
               "A displaced control lease lost its exact owner",
               ActionCleanupReason.FAILED,
               var2
            );
            return;
         }

         /*
          * 先验证整组旧 owner，再逐个请求收口。任一旧 owner 已经失败到不安全
          * 端点时，后续互不相关的 owner 必须保持运行，不能被一个注定失败的
          * claimant 继续终止。重入回调若已安全释放某张精确 lease，则直接跳过；
          * 只有 lease 仍在而 owner 消失时才按 orphan 隔离。
          */
         for (ControlArbiter.Lease var6 : var4.preemptionCandidates()) {
            if (!this.isLiveActiveTicket(var1)) {
               return;
            }
            if (!this.arbiter.isHeld(var6)) {
               continue;
            }
            BotActionRuntime.GenerationKey var7 =
               new BotActionRuntime.GenerationKey(
                  var6.botId(), var6.botGeneration()
               );
            BotActionRuntime.Ticket var8 = this.active.get(
               new BotActionRuntime.ActionKey(
                  var6.botId(), var6.actionId()
               )
            );
            if (var8 == null
               || var8.state.isTerminal()
               || var8.lease != var6) {
               this.requestGenerationQuarantine(var7);
               this.drainPendingSafeBoundaryWork(var2);
               if (this.isLiveActiveTicket(var1)) {
                  this.finish(
                     var1,
                     ActionState.FAILED,
                     ActionFailureCode.UNSAFE_CONTROL_STATE,
                     List.of(),
                     "A displaced control lease lost its exact owner",
                     ActionCleanupReason.FAILED,
                     var2
                  );
               }
               return;
            }
            this.finish(
               var8,
               ActionState.PREEMPTED,
               ActionFailureCode.PREEMPTED,
               List.of(),
               "Action was preempted by a higher-priority controller",
               ActionCleanupReason.PREEMPTED,
               var2
            );
            this.drainPendingSafeBoundaryWork(var2);
            if (!this.isLiveActiveTicket(var1)) {
               return;
            }
            if (this.hasUnsafePreemptionBarrier(var1)) {
               this.finish(
                  var1,
                  ActionState.FAILED,
                  ActionFailureCode.UNSAFE_CONTROL_STATE,
                  List.of(),
                  "A displaced control generation could not be contained safely",
                  ActionCleanupReason.FAILED,
                  var2
               );
               return;
            }
         }

         /*
          * 同步清理可能已经释放全部旧 lease。当前最高 claim 必须在本 Tick
          * 立即重试一次，不能让随后遍历到的较低优先级 waiter 抢走通道。
          */
         if (!this.hasSuperiorPreemptionClaim(var1)) {
            ControlArbiter.AcquireResult var9 =
               this.arbiter.acquire(var1.envelope, var1.priority);
            if (var9.acquired()) {
               if (!this.isLiveActiveTicket(var1)) {
                  this.arbiter.release(var9.lease().orElseThrow());
                  return;
               }
               this.grantAndStart(
                  var1, var9.lease().orElseThrow(), var2
               );
            }
         }
         return;
      }
      if (!var4.acquired()) {
         this.finish(var1, ActionState.FAILED, ActionFailureCode.CHANNEL_BUSY, List.of(), "Required control channel is busy", ActionCleanupReason.FAILED, var2);
         return;
      }

      this.grantAndStart(var1, var4.lease().orElseThrow(), var2);
   }

   private boolean hasUnsafePreemptionBarrier(
      BotActionRuntime.Ticket var1
   ) {
      if (this.quarantineCapacityExhausted
         || this.pendingRuntimeFailClosed
         || this.runtimeFailClosedInProgress) {
         return true;
      }
      return var1.preemptionBarriers.stream()
         .anyMatch(this.unsafeGenerations::contains);
   }

   private boolean isLiveActiveTicket(
      BotActionRuntime.Ticket var1
   ) {
      return var1.outcome == null
         && var1.termination == null
         && !var1.state.isTerminal()
         && this.active.get(
            new BotActionRuntime.ActionKey(
               var1.envelope.botId(),
               var1.envelope.actionId()
            )
         ) == var1;
   }

   private boolean hasSuperiorPreemptionClaim(
      BotActionRuntime.Ticket var1
   ) {
      boolean var2 = false;
      for (BotActionRuntime.Ticket var4 : this.active.values()) {
         if (var4 == var1) {
            var2 = true;
            continue;
         }
         if (!var4.preemptionClaimed
            || var4.outcome != null
            || var4.termination != null
            || !var4.envelope.botId().equals(var1.envelope.botId())
            || java.util.Collections.disjoint(
               var4.envelope.action().channels(),
               var1.envelope.action().channels()
            )) {
            continue;
         }
         if (var4.priority.outranks(var1.priority)
            || (var4.priority == var1.priority && !var2)) {
            return true;
         }
      }
      return false;
   }

   private boolean ensureSafeControlAuthority(
      BotActionRuntime.Ticket var1, long var2
   ) {
      BotActionRuntime.GenerationKey var4 =
         new BotActionRuntime.GenerationKey(
            var1.envelope.botId(),
            var1.envelope.botGeneration()
         );
      if (!this.quarantineCapacityExhausted
         && !this.pendingRuntimeFailClosed
         && !this.runtimeFailClosedInProgress
         && !this.unsafeGenerations.contains(var4)
         && !this.hasUnsafePreemptionBarrier(var1)) {
         return true;
      }
      this.finish(
         var1,
         ActionState.FAILED,
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         List.of(),
         "Control authority became unsafe while the action was running",
         ActionCleanupReason.FAILED,
         var2
      );
      return false;
   }

   private void grantAndStart(
      BotActionRuntime.Ticket var1,
      ControlArbiter.Lease var2,
      long var3
   ) {
      if (!this.isLiveActiveTicket(var1)) {
         this.arbiter.release(var2);
         return;
      }
      var1.lease = var2;
      BotActionRuntime.GenerationKey var4 =
         new BotActionRuntime.GenerationKey(
            var1.envelope.botId(),
            var1.envelope.botGeneration()
         );
      if (this.quarantineCapacityExhausted
         || this.unsafeGenerations.contains(var4)
         || this.hasUnsafePreemptionBarrier(var1)) {
         this.finish(
            var1,
            ActionState.FAILED,
            ActionFailureCode.UNSAFE_CONTROL_STATE,
            List.of(),
            "Preempted control could not be reset safely",
            ActionCleanupReason.FAILED,
            var3
         );
         return;
      }

      var1.startedTick = var3;
      this.transition(var1, ActionState.RUNNING);
      var1.preemptionClaimed = false;
      var1.backendStarted = true;
      ActionBackend.BackendResult var5 = this.invoke(
         var1, BotActionRuntime.BackendCall.START, var3
      );
      this.handleExecutionResult(var1, var5, var3);
   }

   private void handleExecutionResult(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      if (this.ensureSafeControlAuthority(var1, var3)
         && this.acceptIdentity(var1, var2, var3)) {
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
      if (this.ensureSafeControlAuthority(var1, var2)
         && this.acceptIdentity(var1, var4, var2)) {
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
         this.drainPendingSafeBoundaryWork(var3);
         if (!var1.state.isTerminal()) {
            this.finish(var1, ActionState.FAILED, ActionFailureCode.INTERNAL_ERROR, List.of(), "Action backend failed", ActionCleanupReason.FAILED, var3);
         }
         return ActionBackend.BackendResult.failed(var1.envelope, ActionFailureCode.INTERNAL_ERROR, List.of(), "Action backend failed");
      }

      this.drainPendingSafeBoundaryWork(var3);
      return var5;
   }

   private boolean acceptIdentity(BotActionRuntime.Ticket var1, ActionBackend.BackendResult var2, long var3) {
      if (var1.state.isTerminal()
         || var1.termination != null) {
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
      }

      if (var1.termination == null) {
         var1.termination = new BotActionRuntime.PendingTermination(
            var2,
            var3,
            var4,
            var5,
            var6,
            var7,
            cleanupDeadline(var7)
         );
      }
      this.advanceTermination(var1, var7);
      return var1.outcome;
   }

   private void advanceTermination(
      BotActionRuntime.Ticket var1, long var2
   ) {
      if (var1.outcome != null) {
         return;
      }
      BotActionRuntime.PendingTermination var3 =
         Objects.requireNonNull(var1.termination, "termination");
      if (var1.lease == null || !var1.backendStarted) {
         this.finalizeTermination(
            var1,
            var3.state,
            var3.failureCode,
            var3.evidence,
            var3.safeSummary,
            var2,
            false
         );
         return;
      }
      if (var1.lastCleanupAttemptTick == var2) {
         return;
      }
      if (var2 > var3.cleanupDeadlineTick) {
         this.failCleanupUnsafe(var1, var2);
         return;
      }

      ActionCleanupRequest var4;
      if (var1.cleanupRequest == null) {
         var4 = ActionCleanupRequest.first(
            UUID.randomUUID(),
            var1.envelope,
            var3.cleanupReason,
            var2
         );
      } else {
         ActionCleanupReceipt var5 = var1.cleanupReceipt;
         if (var5 == null
            || var5.status() != ActionCleanupStatus.PENDING) {
            this.failCleanupUnsafe(var1, var2);
            return;
         }
         if (var2 < var5.nextRetryTick()) {
            return;
         }
         try {
            var4 = var1.cleanupRequest.next(var5, var2);
         } catch (RuntimeException var13) {
            this.failCleanupUnsafe(var1, var2);
            return;
         }
      }

      var1.cleanupRequest = var4;
      var1.lastCleanupAttemptTick = var2;
      ActionCleanupReceipt var5;
      try {
         var5 = Objects.requireNonNull(
            this.backend.cleanupStep(var1.envelope, var4),
            "cleanup receipt"
         );
      } catch (RuntimeException var12) {
         this.failCleanupUnsafe(var1, var2);
         return;
      }

      this.drainPendingSafeBoundaryWork(var2);
      if (var1.outcome != null) {
         return;
      }
      if (!var5.matches(var4)
         || var5.progressRevision()
            < var1.cleanupProgressRevision) {
         this.failCleanupUnsafe(var1, var2);
         return;
      }
      var1.cleanupProgressRevision = var5.progressRevision();
      var1.cleanupReceipt = var5;
      switch (var5.status()) {
         case PENDING:
            if (var5.nextRetryTick()
               > var3.cleanupDeadlineTick) {
               this.failCleanupUnsafe(var1, var2);
            }
            break;
         case UNSAFE:
            this.failCleanupUnsafe(var1, var2);
            break;
         case COMPLETE:
            if (this.isTerminationGenerationUnsafe(var1)) {
               this.failCleanupUnsafe(var1, var2);
            } else {
               this.finalizeTermination(
                  var1,
                  var3.state,
                  var3.failureCode,
                  var3.evidence,
                  var3.safeSummary,
                  var2,
                  false
               );
            }
      }
   }

   private boolean isTerminationGenerationUnsafe(
      BotActionRuntime.Ticket var1
   ) {
      BotActionRuntime.GenerationKey var2 =
         new BotActionRuntime.GenerationKey(
            var1.envelope.botId(),
            var1.envelope.botGeneration()
         );
      return this.quarantineCapacityExhausted
         || this.pendingRuntimeFailClosed
         || this.runtimeFailClosedInProgress
         || this.unsafeGenerations.contains(var2);
   }

   private void failCleanupUnsafe(
      BotActionRuntime.Ticket var1, long var2
   ) {
      if (!var1.cleanupFailureRecorded) {
         var1.cleanupFailureRecorded = true;
         this.cleanupFailureCount++;
         this.quarantineGeneration(var1);
      }
      this.finalizeTermination(
         var1,
         ActionState.FAILED,
         ActionFailureCode.UNSAFE_CONTROL_STATE,
         List.of(new ActionEvidence("runtime.cleanup", "unsafe")),
         "Action cleanup could not prove a safe endpoint",
         var2,
         true
      );
   }

   private void finalizeTermination(
      BotActionRuntime.Ticket var1,
      ActionState var2,
      ActionFailureCode var3,
      List<ActionEvidence> var4,
      String var5,
      long var6,
      boolean var8
   ) {
      if (var1.outcome != null) {
         return;
      }
      if (var1.lease != null) {
         ControlArbiter.Lease var9 = var1.lease;
         if (!var8 && !this.arbiter.release(var9)) {
            var1.lease = null;
            this.failCleanupUnsafe(var1, var6);
            return;
         }
         if (var8) {
            this.arbiter.release(var9);
         }
         var1.lease = null;
      }

      if (var1.state == ActionState.QUEUED
         && var2 == ActionState.FAILED) {
         this.transition(var1, ActionState.VALIDATING);
      }
      this.transition(var1, var2);
      long var10 = var1.startedTick >= 0L
         ? var1.startedTick
         : var1.acceptedTick;
      ActionOutcome var11 = new ActionOutcome(
         var1.envelope.actionId(),
         var2,
         var3,
         var10,
         var6,
         var4,
         var5
      );
      this.ledger.complete(var1.envelope, var11);
      var1.outcome = var11;
      this.active.remove(
         new BotActionRuntime.ActionKey(
            var1.envelope.botId(),
            var1.envelope.actionId()
         ),
         var1
      );
      this.releaseVanillaDeathConsumedIntent(
         new BotActionRuntime.GenerationKey(
            var1.envelope.botId(),
            var1.envelope.botGeneration()
         )
      );
      this.publishOutcome(var1.envelope, var11);

      for (CompletionDispatcher.Completion<ActionOutcome> var13
         : var1.waiters) {
         this.publish(var13, var11);
      }
      this.completeCancellationWaiters(var1, var11, var8, var6);
   }

   private void completeCancellationWaiters(
      BotActionRuntime.Ticket var1,
      ActionOutcome var2,
      boolean var3,
      long var4
   ) {
      ActionMailbox.CancellationStatus var6;
      if (var3) {
         var6 = ActionMailbox.CancellationStatus.CLEANUP_FAILED;
      } else if (var1.termination.state == ActionState.CANCELLED
         && var2.state() == ActionState.CANCELLED) {
         var6 = ActionMailbox.CancellationStatus.CANCELLED;
      } else {
         var6 = ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
      }

      for (BotActionRuntime.PendingCancellation var8
         : var1.cancellationWaiters) {
         ActionMailbox.CancelCommand var9 = var8.command;
         if (!var8.queuedSubmissionHandled) {
            var9.queuedSubmission().ifPresent(
            var3x -> this.completeQueuedCancellation(
                  var3x, var4, var9.reason()
               )
            );
         }
         this.publish(var9.completion(), var6);
      }
   }

   private boolean queueCancellationWaiter(
      BotActionRuntime.Ticket var1,
      ActionMailbox.CancelCommand var2,
      boolean var3
   ) {
      if (var1.cancellationWaiters.size()
         >= MAX_WAITERS_PER_ACTION) {
         return false;
      }
      var1.cancellationWaiters.add(
         new BotActionRuntime.PendingCancellation(var2, var3)
      );
      return true;
   }

   private ActionMailbox.CancellationStatus completeQueuedCancellation(
      ActionMailbox.SubmitCommand var1,
      long var2,
      ActionCancellationReason var4
   ) {
      return this.completeQueuedCancellation(
         var1, var2, var4, null
      );
   }

   private ActionMailbox.CancellationStatus completeQueuedCancellation(
      ActionMailbox.SubmitCommand var1,
      long var2,
      ActionCancellationReason var4,
      ActionMailbox.CancelCommand var5
   ) {
      ActionEnvelope var6 = var1.envelope();
      ActionLedger.BeginResult var7 = this.ledger.begin(var6);

      return switch (var7.status()) {
         case STARTED -> {
            BotActionRuntime.Ticket var13 = new BotActionRuntime.Ticket(var6, var1.priority(), var2, var1.completion());
            this.finishDetached(var13, ActionState.CANCELLED, ActionFailureCode.CANCELLED, "Action cancelled before leaving the lifecycle mailbox", var2, true);
            yield ActionMailbox.CancellationStatus.CANCELLED;
         }
         case DUPLICATE_IN_PROGRESS -> {
            BotActionRuntime.Ticket var12 = this.active.get(new BotActionRuntime.ActionKey(var6.botId(), var7.canonicalActionId().orElseThrow()));
            if (var12 == null) {
               throw new IllegalStateException("Queued cancellation found a missing canonical action");
            }

            if (var12.waiters.size() >= MAX_WAITERS_PER_ACTION
               || var5 != null
                  && var12.cancellationWaiters.size()
                     >= MAX_WAITERS_PER_ACTION
               || !this.ledger.registerAlias(
                  var6, var7.canonicalActionId().orElseThrow()
               )) {
               this.publish(
                  var1.completion(),
                  this.rejectedOutcome(
                     var6,
                     var2,
                     ActionFailureCode.DUPLICATE_IN_PROGRESS,
                     "Too many callers are waiting for the canonical action"
                  )
               );
               yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
            }

            /*
             * 必须先登记两个 completion，再触发 canonical cleanup。cleanupStep
             * 可以同步重入；预留完成后，无论本次收口同步还是跨 Tick，别名提交和
             * 取消回执都只由 canonical 的唯一终态发布一次。
             */
            var12.waiters.add(var1.completion());
            if (var5 != null
               && !this.queueCancellationWaiter(var12, var5, true)) {
               throw new IllegalStateException(
                  "Queued alias cancellation lost its reserved waiter"
               );
            }
            ActionOutcome var9 = this.finish(
               var12,
               ActionState.CANCELLED,
               ActionFailureCode.CANCELLED,
               List.of(),
               cancellationSummary(var4),
               var4 == ActionCancellationReason.RUNTIME_SHUTDOWN ? ActionCleanupReason.RUNTIME_SHUTDOWN : ActionCleanupReason.CANCELLED,
               var2
            );
            if (var5 != null) {
               yield null;
            }
            yield var9 == null
               || var9.state() == ActionState.CANCELLED
               ? ActionMailbox.CancellationStatus.CANCELLED
               : ActionMailbox.CancellationStatus.CLEANUP_FAILED;
         }
         case REPLAYED -> {
            ActionOutcome var11 = this.replayOrAliasFailure(var1, var7, var2);
            this.publish(var1.completion(), var11);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
         case IDEMPOTENCY_CONFLICT -> {
            ActionOutcome var10 = this.rejectedOutcome(var6, var2, ActionFailureCode.IDEMPOTENCY_CONFLICT, "Queued action conflicts with a retained action");
            this.publish(var1.completion(), var10);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
         case CAPACITY_EXHAUSTED -> {
            ActionOutcome var8 = this.rejectedOutcome(var6, var2, ActionFailureCode.LEDGER_CAPACITY_EXCEEDED, "Action ledger capacity is exhausted");
            this.publish(var1.completion(), var8);
            yield ActionMailbox.CancellationStatus.ALREADY_TERMINAL;
         }
      };
   }

   private static long cleanupDeadline(long var0) {
      return var0 > Long.MAX_VALUE - MAX_CLEANUP_TICKS
         ? Long.MAX_VALUE
         : var0 + MAX_CLEANUP_TICKS;
   }

   private void quarantineGeneration(BotActionRuntime.Ticket var1) {
      this.requestGenerationQuarantine(
         new BotActionRuntime.GenerationKey(
            var1.envelope.botId(),
            var1.envelope.botGeneration()
         )
      );
   }

   private int requestGenerationQuarantine(
      BotActionRuntime.GenerationKey var1
   ) {
      if (this.quarantineCapacityExhausted
         || (!this.unsafeGenerations.contains(var1)
            && this.unsafeGenerations.size()
               >= MAX_QUARANTINED_GENERATIONS)) {
         return this.requestRuntimeFailClosed(var1);
      }

      List<ActionMailbox.SubmitCommand> var2 =
         this.mailbox.quarantineBotGeneration(var1.botId(), var1.generation());
      this.unsafeGenerations.add(var1);
      int var3 = this.countActiveGeneration(var1) + var2.size();
      if (!this.quarantinesInProgress.contains(var1)
         || !var2.isEmpty()) {
         this.pendingGenerationQuarantines
            .computeIfAbsent(
               var1,
               var0 -> new BotActionRuntime.PendingGenerationQuarantine()
            )
            .queued
            .addAll(var2);
      }

      return var3;
   }

   private int requestRuntimeFailClosed(
      BotActionRuntime.GenerationKey var1
   ) {
      this.mailbox.close();
      this.quarantineCapacityExhausted = true;
      List<ActionMailbox.Command> var2 = this.mailbox.drainAll();
      int var3 = this.countActiveGeneration(var1);

      for (ActionMailbox.Command var5 : var2) {
         if (var5 instanceof ActionMailbox.SubmitCommand var6
            && targetsGeneration(var6, var1)) {
            var3++;
         } else if (var5 instanceof ActionMailbox.CancelCommand var7
            && var7.queuedSubmission().filter(
               var2x -> targetsGeneration(var2x, var1)
            ).isPresent()) {
            var3++;
         }
      }

      this.pendingRuntimeFailClosedCommands.addAll(var2);
      if (!this.runtimeFailClosedInProgress) {
         this.pendingRuntimeFailClosed = true;
      }

      return var3;
   }

   private void drainPendingRuntimeFailClosed(long var1) {
      if (!this.runtimeFailClosedInProgress
         && this.pendingRuntimeFailClosed) {
         this.pendingRuntimeFailClosed = false;
         this.runtimeFailClosedInProgress = true;

         try {
            List<ActionMailbox.Command> var3 =
               List.copyOf(this.pendingRuntimeFailClosedCommands);
            this.pendingRuntimeFailClosedCommands.clear();

            for (ActionMailbox.Command var5 : var3) {
               switch (var5) {
                  case ActionMailbox.SubmitCommand var6 ->
                     this.completeQueuedFailure(
                        var6,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        "Action runtime failed closed after quarantine capacity was exhausted",
                        var1
                     );
                  case ActionMailbox.CancelCommand var7 ->
                     this.processCancellation(var7, var1);
               }
            }

            for (BotActionRuntime.Ticket var4 : List.copyOf(this.active.values())) {
               if (!var4.state.isTerminal()) {
                  BotActionRuntime.GenerationKey key =
                     new BotActionRuntime.GenerationKey(
                        var4.envelope.botId(),
                        var4.envelope.botGeneration()
                     );
                  if (this.wasVanillaDeathConsumed(key)) {
                     this.replaceUnsafeTerminationForVanillaDeath(
                        var4,
                        "Action runtime failed closed after vanilla death consumed the player body state",
                        var1
                     );
                     this.advanceTermination(var4, var1);
                  } else {
                     this.finish(
                        var4,
                        ActionState.FAILED,
                        ActionFailureCode.UNSAFE_CONTROL_STATE,
                        List.of(),
                        "Action runtime failed closed after quarantine capacity was exhausted",
                        ActionCleanupReason.FAILED,
                        var1
                     );
                  }
               }
            }
         } finally {
            this.runtimeFailClosedInProgress = false;
         }
      }
   }

   private static boolean targetsGeneration(
      ActionMailbox.SubmitCommand var0,
      BotActionRuntime.GenerationKey var1
   ) {
      return var0.envelope().botId().equals(var1.botId())
         && var0.envelope().botGeneration() == var1.generation();
   }

   private int countActiveGeneration(
      BotActionRuntime.GenerationKey var1
   ) {
      return Math.toIntExact(
         this.active.values().stream()
            .filter(var2 -> !var2.state.isTerminal()
               && var2.envelope.botId().equals(var1.botId())
               && var2.envelope.botGeneration() == var1.generation())
            .count()
      );
   }

   private boolean hasActiveGeneration(
      BotActionRuntime.GenerationKey var1
   ) {
      return this.active.values().stream()
         .anyMatch(var2 -> !var2.state.isTerminal()
            && var2.envelope.botId().equals(var1.botId())
            && var2.envelope.botGeneration() == var1.generation());
   }

   private void drainPendingGenerationQuarantines(long var1) {
      if (!this.drainingGenerationQuarantines
         && !this.pendingGenerationQuarantines.isEmpty()) {
         this.drainingGenerationQuarantines = true;

         try {
            while (!this.pendingGenerationQuarantines.isEmpty()) {
               List<Map.Entry<BotActionRuntime.GenerationKey, BotActionRuntime.PendingGenerationQuarantine>> var3 =
                  List.copyOf(this.pendingGenerationQuarantines.entrySet());
               this.pendingGenerationQuarantines.clear();

               for (Map.Entry<BotActionRuntime.GenerationKey, BotActionRuntime.PendingGenerationQuarantine> var5 : var3) {
                  BotActionRuntime.GenerationKey var6 = var5.getKey();
                  this.quarantinesInProgress.add(var6);

                  try {
                     this.quarantineGenerationAtSafeBoundary(
                        var6, var5.getValue().queued, var1
                     );
                  } finally {
                     this.quarantinesInProgress.remove(var6);
                  }
               }
            }
         } finally {
            this.drainingGenerationQuarantines = false;
         }
      }
   }

   private void quarantineGenerationAtSafeBoundary(
      BotActionRuntime.GenerationKey var1,
      List<ActionMailbox.SubmitCommand> var2,
      long var3
   ) {
      for (BotActionRuntime.Ticket var5 : List.copyOf(this.active.values())) {
         if (!var5.state.isTerminal()
            && var5.envelope.botId().equals(var1.botId())
            && var5.envelope.botGeneration() == var1.generation()) {
            if (this.wasVanillaDeathConsumed(var1)) {
               this.replaceUnsafeTerminationForVanillaDeath(
                  var5,
                  "Bot controls were quarantined after vanilla death consumed the player body state",
                  var3
               );
               this.advanceTermination(var5, var3);
            } else {
               this.finish(
                  var5,
                  ActionState.FAILED,
                  ActionFailureCode.UNSAFE_CONTROL_STATE,
                  List.of(),
                  "Bot controls were quarantined after unsafe transaction recovery",
                  ActionCleanupReason.FAILED,
                  var3
               );
            }
         }
      }

      for (ActionMailbox.SubmitCommand var4 : var2) {
         this.completeQueuedFailure(
            var4,
            ActionFailureCode.UNSAFE_CONTROL_STATE,
            "Bot controls were quarantined before the action started",
            var3
         );
      }
   }

   private BotActionRuntime.GenerationQuarantineResult generationQuarantineResult(
      BotActionRuntime.GenerationKey var1,
      int var2
   ) {
      return new BotActionRuntime.GenerationQuarantineResult(
         var2,
         this.mailbox.isGenerationIngressClosed(
            var1.botId(), var1.generation()
         ),
         this.quarantineCapacityExhausted,
         this.pendingRuntimeFailClosed
            || this.runtimeFailClosedInProgress
            || this.pendingGenerationQuarantines.containsKey(var1)
            || this.quarantinesInProgress.contains(var1),
         this.hasActiveGeneration(var1),
         this.arbiter.hasLease(var1.botId(), var1.generation())
      );
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
            this.drainPendingSafeBoundaryWork(this.lastMutationTick);
            this.completeShutdownIfDrained();
         } finally {
            this.mutating = false;
         }
      }
   }

   private List<BotActionRuntime.Ticket> orderedActiveTickets() {
      List<BotActionRuntime.Ticket> var1 =
         new ArrayList<>(this.active.values());
      var1.sort((var0, var2) -> {
         int var3 = Boolean.compare(
            var0.termination == null,
            var2.termination == null
         );
         if (var3 != 0) {
            return var3;
         }
         /*
          * List.sort 是稳定排序；除清理 ticket 前置外，业务动作严格保留
          * LinkedHashMap/mailbox FIFO。这样每个旧 owner 本 Tick 至多再执行
          * 一个后端步骤，随后 emergency 才能取得其精确 prefix 并发起收口。
          */
         return 0;
      });
      return var1;
   }

   private void completeShutdownIfDrained() {
      if (this.shutdown
         && !this.shutdownComplete
         && this.active.isEmpty()
         && this.arbiter.activeLeaseCount() == 0
         && this.pendingGenerationQuarantines.isEmpty()
         && this.pendingVanillaDeathConsumptions.isEmpty()
         && this.pendingLifecycleCloses.isEmpty()
         && this.pendingRuntimeFailClosedCommands.isEmpty()
         && !this.pendingRuntimeFailClosed
         && !this.runtimeFailClosedInProgress) {
         this.shutdownComplete = true;
         this.completionDispatcher.shutdownAfterQueuedWork();
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

   private static record GenerationKey(UUID botId, long generation) {
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

   public static record GenerationQuarantineResult(
      int targetedActions,
      boolean ingressClosed,
      boolean runtimeFailClosed,
      boolean pending,
      boolean ticketRemaining,
      boolean leaseRemaining
   ) {
      public GenerationQuarantineResult(
         int targetedActions,
         boolean ingressClosed,
         boolean runtimeFailClosed,
         boolean pending,
         boolean ticketRemaining,
         boolean leaseRemaining
      ) {
         if (targetedActions < 0) {
            throw new IllegalArgumentException(
               "targetedActions must not be negative"
            );
         }

         this.targetedActions = targetedActions;
         this.ingressClosed = ingressClosed;
         this.runtimeFailClosed = runtimeFailClosed;
         this.pending = pending;
         this.ticketRemaining = ticketRemaining;
         this.leaseRemaining = leaseRemaining;
      }

      public boolean containmentConfirmed() {
         return this.ingressClosed
            && !this.pending
            && !this.ticketRemaining
            && !this.leaseRemaining;
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
         return this.drainStatus()
            == GenerationDrainStatus.COMPLETE;
      }

      public GenerationDrainStatus drainStatus() {
         return GenerationDrainStatus.classify(
            this.cleanupFailures != 0 || this.quarantined,
            this.ticketRemaining,
            this.leaseRemaining
         );
      }
   }

   private static final class PendingGenerationQuarantine {
      private final List<ActionMailbox.SubmitCommand> queued =
         new ArrayList<>();
   }

   private static final class PendingVanillaDeathConsumption {
      private final List<ActionMailbox.SubmitCommand> queued =
         new ArrayList<>();
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

   private static final class PendingTermination {
      private final ActionState state;
      private final ActionFailureCode failureCode;
      private final List<ActionEvidence> evidence;
      private final String safeSummary;
      private final ActionCleanupReason cleanupReason;
      private final long requestedTick;
      private final long cleanupDeadlineTick;

      private PendingTermination(
         ActionState var1,
         ActionFailureCode var2,
         List<ActionEvidence> var3,
         String var4,
         ActionCleanupReason var5,
         long var6,
         long var8
      ) {
         this.state = Objects.requireNonNull(var1, "state");
         this.failureCode = Objects.requireNonNull(
            var2, "failureCode"
         );
         this.evidence = List.copyOf(
            Objects.requireNonNull(var3, "evidence")
         );
         this.safeSummary = Objects.requireNonNull(
            var4, "safeSummary"
         );
         this.cleanupReason = Objects.requireNonNull(
            var5, "cleanupReason"
         );
         this.requestedTick = var6;
         this.cleanupDeadlineTick = var8;
      }
   }

   private static record PendingCancellation(
      ActionMailbox.CancelCommand command,
      boolean queuedSubmissionHandled
   ) {
      private PendingCancellation {
         Objects.requireNonNull(command, "command");
      }
   }

   private static final class Ticket {
      private final ActionEnvelope envelope;
      private final ActionPriority priority;
      private final long acceptedTick;
      private final List<CompletionDispatcher.Completion<ActionOutcome>> waiters = new ArrayList<>();
      private final List<BotActionRuntime.PendingCancellation> cancellationWaiters =
         new ArrayList<>();
      private final Set<BotActionRuntime.GenerationKey> preemptionBarriers =
         new LinkedHashSet<>();
      private ActionState state = ActionState.QUEUED;
      private long startedTick = -1L;
      private long lastAcquireAttemptTick = -1L;
      private long lastCleanupAttemptTick = -1L;
      private long cleanupProgressRevision = -1L;
      private ControlArbiter.Lease lease;
      private ActionOutcome outcome;
      private BotActionRuntime.PendingTermination termination;
      private ActionCleanupRequest cleanupRequest;
      private ActionCleanupReceipt cleanupReceipt;
      private boolean cleanupFailureRecorded;
      private boolean controlValidated;
      private boolean preemptionClaimed;
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
