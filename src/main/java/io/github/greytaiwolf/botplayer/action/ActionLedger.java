package io.github.greytaiwolf.botplayer.action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ActionLedger {
   public static final int MAX_CAPACITY = 65536;
   public static final int MAX_ACTION_IDS_PER_ENTRY = 64;
   private final int capacity;
   private final Thread ownerThread;
   private final LinkedHashMap<ActionLedger.LedgerKey, ActionLedger.Entry> entries = new LinkedHashMap<>();
   private final Map<ActionLedger.ActionKey, ActionLedger.LedgerKey> keyByAction = new HashMap<>();

   public ActionLedger(int var1) {
      if (var1 >= 1 && var1 <= 65536) {
         this.capacity = var1;
         this.ownerThread = Thread.currentThread();
      } else {
         throw new IllegalArgumentException("capacity must be between 1 and 65536");
      }
   }

   public ActionLedger.BeginResult begin(ActionEnvelope var1) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "envelope");
      ActionLedger.LedgerKey var2 = ActionLedger.LedgerKey.from(var1);
      ActionLedger.LedgerKey var3 = this.keyByAction.get(ActionLedger.ActionKey.from(var1));
      if (var3 != null && !var3.equals(var2)) {
         ActionLedger.Entry var5 = this.entries.get(var3);
         if (var5 == null) {
            throw new IllegalStateException("Action index points to a missing ledger entry");
         } else {
            return ActionLedger.BeginResult.idempotencyConflict(var5.envelope.actionId());
         }
      } else {
         ActionLedger.Entry var4 = this.entries.get(var2);
         if (var4 != null) {
            if (!var4.envelope.hasSameIdempotentOperation(var1)) {
               return ActionLedger.BeginResult.idempotencyConflict(var4.envelope.actionId());
            } else {
               return var4.outcome == null
                  ? ActionLedger.BeginResult.duplicateInProgress(var4.envelope.actionId())
                  : ActionLedger.BeginResult.replayed(var4.envelope.actionId(), var4.outcome);
            }
         } else if (this.entries.size() == this.capacity && !this.evictOldestCompleted()) {
            return ActionLedger.BeginResult.capacityExhausted();
         } else {
            this.entries.put(var2, new ActionLedger.Entry(var1));
            this.keyByAction.put(ActionLedger.ActionKey.from(var1), var2);
            return ActionLedger.BeginResult.started(var1.actionId());
         }
      }
   }

   public ActionOutcome complete(ActionEnvelope var1, ActionOutcome var2) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "envelope");
      Objects.requireNonNull(var2, "outcome");
      ActionLedger.LedgerKey var3 = ActionLedger.LedgerKey.from(var1);
      ActionLedger.Entry var4 = this.entries.get(var3);
      if (var4 == null) {
         throw new IllegalStateException("Cannot complete an action that was not begun");
      } else if (!var4.envelope.actionId().equals(var1.actionId())) {
         throw new IllegalArgumentException("Only the canonical in-progress action may be completed");
      } else if (!var4.envelope.equals(var1)) {
         throw new IllegalArgumentException("Only the canonical action envelope may be completed");
      } else if (!var2.actionId().equals(var4.envelope.actionId())) {
         throw new IllegalArgumentException("Outcome actionId does not match the action");
      } else if (var4.outcome != null) {
         if (!var4.outcome.equals(var2)) {
            throw new IllegalStateException("Action already has a different final outcome");
         } else {
            return var4.outcome;
         }
      } else {
         var4.outcome = var2;
         return var2;
      }
   }

   public boolean registerAlias(ActionEnvelope var1, UUID var2) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "alias");
      ActionEnvelope.requireNonZero(var2, "canonicalActionId");
      ActionLedger.LedgerKey var3 = ActionLedger.LedgerKey.from(var1);
      ActionLedger.Entry var4 = this.entries.get(var3);
      if (var4 != null && var4.envelope.actionId().equals(var2) && var4.envelope.hasSameIdempotentOperation(var1)) {
         ActionLedger.ActionKey var5 = ActionLedger.ActionKey.from(var1);
         ActionLedger.LedgerKey var6 = this.keyByAction.get(var5);
         if (var6 != null) {
            return var6.equals(var3);
         } else if (var4.actionKeys.size() >= 64) {
            return false;
         } else {
            this.keyByAction.put(var5, var3);
            var4.actionKeys.add(var5);
            return true;
         }
      } else {
         return false;
      }
   }

   public Optional<UUID> canonicalActionId(UUID var1, UUID var2) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      ActionEnvelope.requireNonZero(var2, "actionId");
      ActionLedger.LedgerKey var3 = this.keyByAction.get(new ActionLedger.ActionKey(var1, var2));
      if (var3 == null) {
         return Optional.empty();
      } else {
         ActionLedger.Entry var4 = this.entries.get(var3);
         if (var4 == null) {
            throw new IllegalStateException("Action index points to a missing ledger entry");
         } else {
            return Optional.of(var4.envelope.actionId());
         }
      }
   }

   public Optional<ActionOutcome> completedOutcome(UUID var1, String var2) {
      this.assertOwnerThread();
      ActionLedger.LedgerKey var3 = ActionLedger.LedgerKey.of(var1, var2);
      ActionLedger.Entry var4 = this.entries.get(var3);
      return var4 == null ? Optional.empty() : Optional.ofNullable(var4.outcome);
   }

   public Optional<ActionOutcome> completedOutcome(UUID var1, UUID var2) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      ActionEnvelope.requireNonZero(var2, "actionId");
      ActionLedger.LedgerKey var3 = this.keyByAction.get(new ActionLedger.ActionKey(var1, var2));
      if (var3 == null) {
         return Optional.empty();
      } else {
         ActionLedger.Entry var4 = this.entries.get(var3);
         if (var4 == null) {
            throw new IllegalStateException("Action index points to a missing ledger entry");
         } else {
            return Optional.ofNullable(var4.outcome);
         }
      }
   }

   public boolean isInProgress(UUID var1, String var2) {
      this.assertOwnerThread();
      ActionLedger.LedgerKey var3 = ActionLedger.LedgerKey.of(var1, var2);
      ActionLedger.Entry var4 = this.entries.get(var3);
      return var4 != null && var4.outcome == null;
   }

   public int size() {
      this.assertOwnerThread();
      return this.entries.size();
   }

   public int capacity() {
      return this.capacity;
   }

   private boolean evictOldestCompleted() {
      Iterator<Map.Entry<ActionLedger.LedgerKey, ActionLedger.Entry>> var1 =
         this.entries.entrySet().iterator();

      while (var1.hasNext()) {
         Map.Entry<ActionLedger.LedgerKey, ActionLedger.Entry> var2 = var1.next();
         if (var2.getValue().outcome != null) {
            var1.remove();

            for (ActionLedger.ActionKey var4 : var2.getValue().actionKeys) {
               this.keyByAction.remove(var4, var2.getKey());
            }

            return true;
         }
      }

      return false;
   }

   private void assertOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("ActionLedger accessed outside its owner thread");
      }
   }

   private static record ActionKey(UUID botId, UUID actionId) {
      private static ActionLedger.ActionKey from(ActionEnvelope var0) {
         return new ActionLedger.ActionKey(var0.botId(), var0.actionId());
      }
   }

   public static record BeginResult(ActionLedger.BeginStatus status, Optional<UUID> canonicalActionId, Optional<ActionOutcome> replayedOutcome) {
      public BeginResult(ActionLedger.BeginStatus status, Optional<UUID> canonicalActionId, Optional<ActionOutcome> replayedOutcome) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(canonicalActionId, "canonicalActionId");
         Objects.requireNonNull(replayedOutcome, "replayedOutcome");
         switch (status) {
            case STARTED:
            case DUPLICATE_IN_PROGRESS:
            case IDEMPOTENCY_CONFLICT:
               if (canonicalActionId.isEmpty() || replayedOutcome.isPresent()) {
                  throw new IllegalArgumentException(status + " requires an action id and no replay");
               }
               break;
            case REPLAYED:
               if (canonicalActionId.isEmpty() || replayedOutcome.isEmpty()) {
                  throw new IllegalArgumentException("REPLAYED requires an action id and outcome");
               }
               break;
            case CAPACITY_EXHAUSTED:
               if (canonicalActionId.isPresent() || replayedOutcome.isPresent()) {
                  throw new IllegalArgumentException("CAPACITY_EXHAUSTED cannot contain an action or replay");
               }
         }

         this.status = status;
         this.canonicalActionId = canonicalActionId;
         this.replayedOutcome = replayedOutcome;
      }

      public boolean accepted() {
         return this.status == ActionLedger.BeginStatus.STARTED;
      }

      private static ActionLedger.BeginResult started(UUID var0) {
         return new ActionLedger.BeginResult(ActionLedger.BeginStatus.STARTED, Optional.of(var0), Optional.empty());
      }

      private static ActionLedger.BeginResult duplicateInProgress(UUID var0) {
         return new ActionLedger.BeginResult(ActionLedger.BeginStatus.DUPLICATE_IN_PROGRESS, Optional.of(var0), Optional.empty());
      }

      private static ActionLedger.BeginResult replayed(UUID var0, ActionOutcome var1) {
         return new ActionLedger.BeginResult(ActionLedger.BeginStatus.REPLAYED, Optional.of(var0), Optional.of(var1));
      }

      private static ActionLedger.BeginResult idempotencyConflict(UUID var0) {
         return new ActionLedger.BeginResult(ActionLedger.BeginStatus.IDEMPOTENCY_CONFLICT, Optional.of(var0), Optional.empty());
      }

      private static ActionLedger.BeginResult capacityExhausted() {
         return new ActionLedger.BeginResult(ActionLedger.BeginStatus.CAPACITY_EXHAUSTED, Optional.empty(), Optional.empty());
      }
   }

   public static enum BeginStatus {
      STARTED,
      DUPLICATE_IN_PROGRESS,
      IDEMPOTENCY_CONFLICT,
      REPLAYED,
      CAPACITY_EXHAUSTED;
   }

   private static final class Entry {
      private final ActionEnvelope envelope;
      private final Set<ActionLedger.ActionKey> actionKeys = new HashSet<>();
      private ActionOutcome outcome;

      private Entry(ActionEnvelope var1) {
         this.envelope = var1;
         this.actionKeys.add(ActionLedger.ActionKey.from(var1));
      }
   }

   private static record LedgerKey(UUID botId, String idempotencyKey) {
      private static ActionLedger.LedgerKey from(ActionEnvelope var0) {
         return new ActionLedger.LedgerKey(var0.botId(), var0.idempotencyKey());
      }

      private static ActionLedger.LedgerKey of(UUID var0, String var1) {
         ActionEnvelope.requireNonZero(var0, "botId");
         return new ActionLedger.LedgerKey(var0, ActionEnvelope.validateIdempotencyKey(var1));
      }
   }
}
