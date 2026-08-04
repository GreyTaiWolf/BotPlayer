package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ControlArbiter {
   private static final Comparator<ControlArbiter.Lease> BLOCKER_ORDER = Comparator.<ControlArbiter.Lease>comparingInt(var0 -> var0.priority().rank())
      .reversed()
      .thenComparingLong(ControlArbiter.Lease::sequence);
   private static final Comparator<ControlArbiter.Lease> PREEMPTION_ORDER = Comparator.comparingLong(ControlArbiter.Lease::sequence);
   private final Thread ownerThread;
   private final Map<ControlArbiter.ChannelKey, ControlArbiter.Lease> leasesByChannel = new HashMap<>();
   private final Map<ControlArbiter.ActionKey, ControlArbiter.Lease> leasesByAction = new HashMap<>();
   private long nextSequence;

   public ControlArbiter() {
      this.ownerThread = Thread.currentThread();
   }

   public ControlArbiter.AcquireResult acquire(ActionEnvelope var1, ActionPriority var2) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "envelope");
      Objects.requireNonNull(var2, "priority");
      Set<ActionChannel> var3 = immutableChannels(var1.action().channels());
      ControlArbiter.ActionKey var4 = new ControlArbiter.ActionKey(var1.botId(), var1.actionId());
      ControlArbiter.Lease var5 = this.leasesByAction.get(var4);
      if (var5 != null) {
         if (var5.botGeneration() == var1.botGeneration() && var5.priority() == var2 && var5.channels().equals(var3)) {
            return ControlArbiter.AcquireResult.alreadyHeld(var5);
         } else {
            throw new IllegalArgumentException("actionId already owns a different control lease");
         }
      } else {
         List<ControlArbiter.Lease> var6 = this.findConflicts(var1.botId(), var3);
         List<ControlArbiter.Lease> var7 =
            var6.stream()
               .filter(var1x -> !var2.outranks(var1x.priority()))
               .sorted(BLOCKER_ORDER)
               .toList();
         if (!var7.isEmpty()) {
            ControlArbiter.Lease var10 = var7.getFirst();
            ControlArbiter.AcquireStatus var11 = var10.priority() == var2
               ? ControlArbiter.AcquireStatus.REJECTED_TIED_PRIORITY
               : ControlArbiter.AcquireStatus.REJECTED_LOWER_PRIORITY;
            return ControlArbiter.AcquireResult.rejected(var11, var10);
         }

         List<ControlArbiter.Lease> var8 =
            var6.stream().sorted(PREEMPTION_ORDER).toList();
         if (!var8.isEmpty()) {
            return ControlArbiter.AcquireResult.preemptionRequired(var8);
         } else if (this.nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Control lease sequence exhausted");
         } else {
            ControlArbiter.Lease var9 = this.grant(var1, var2, var3);
            return ControlArbiter.AcquireResult.granted(var9);
         }
      }
   }

   public boolean release(ControlArbiter.Lease var1) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "lease");
      ControlArbiter.Lease var2 = this.leasesByAction.get(new ControlArbiter.ActionKey(var1.botId(), var1.actionId()));
      if (var2 != var1) {
         return false;
      } else {
         this.removeLease(var1);
         return true;
      }
   }

   /**
    * 只读核对一张候选票据是否仍是当前精确 lease。抢占清理中的重入回调
    * 可能已经安全释放旧 lease；调用方必须把这种情况与 owner 丢失但 lease
    * 仍悬空区分开。
    */
   boolean isHeld(ControlArbiter.Lease var1) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "lease");
      return this.leasesByAction.get(
         new ControlArbiter.ActionKey(var1.botId(), var1.actionId())
      ) == var1;
   }

   public Optional<ControlArbiter.Lease> currentLease(UUID var1, ActionChannel var2) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      Objects.requireNonNull(var2, "channel");
      return Optional.ofNullable(this.leasesByChannel.get(new ControlArbiter.ChannelKey(var1, var2)));
   }

   public int activeLeaseCount() {
      this.assertOwnerThread();
      return this.leasesByAction.size();
   }

   public int occupiedChannelCount() {
      this.assertOwnerThread();
      return this.leasesByChannel.size();
   }

   public boolean hasLease(UUID var1, long var2) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         return this.leasesByAction.values().stream().anyMatch(var3 -> var3.botId().equals(var1) && var3.botGeneration() == var2);
      }
   }

   /**
    * 仅在调用方已经从动作后端取得整代安全重置证明后，释放该 generation
    * 遗留的全部 lease。普通终止路径必须继续使用精确 {@link #release(Lease)}。
    */
   int releaseGenerationAfterSafeReset(UUID var1, long var2) {
      this.assertOwnerThread();
      ActionEnvelope.requireNonZero(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      }
      List<ControlArbiter.Lease> var4 = this.leasesByAction.values()
         .stream()
         .filter(var3 -> var3.botId().equals(var1)
            && var3.botGeneration() == var2)
         .toList();
      var4.forEach(this::removeLease);
      return var4.size();
   }

   private List<ControlArbiter.Lease> findConflicts(UUID var1, Set<ActionChannel> var2) {
      HashMap<Long, ControlArbiter.Lease> var3 = new HashMap<>();

      for (ActionChannel var5 : var2) {
         ControlArbiter.Lease var6 = this.leasesByChannel.get(new ControlArbiter.ChannelKey(var1, var5));
         if (var6 != null) {
            var3.put(var6.sequence(), var6);
         }
      }

      return new ArrayList<>(var3.values());
   }

   private ControlArbiter.Lease grant(ActionEnvelope var1, ActionPriority var2, Set<ActionChannel> var3) {
      ControlArbiter.Lease var4 = new ControlArbiter.Lease(var1.botId(), var1.actionId(), var1.botGeneration(), var2, var3, ++this.nextSequence);
      this.leasesByAction.put(new ControlArbiter.ActionKey(var4.botId(), var4.actionId()), var4);

      for (ActionChannel var6 : var3) {
         ControlArbiter.Lease var7 = this.leasesByChannel.put(new ControlArbiter.ChannelKey(var4.botId(), var6), var4);
         if (var7 != null) {
            throw new IllegalStateException("Control channel was not released atomically");
         }
      }

      return var4;
   }

   private void removeLease(ControlArbiter.Lease var1) {
      this.leasesByAction.remove(new ControlArbiter.ActionKey(var1.botId(), var1.actionId()), var1);

      for (ActionChannel var3 : var1.channels()) {
         this.leasesByChannel.remove(new ControlArbiter.ChannelKey(var1.botId(), var3), var1);
      }
   }

   private void assertOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("ControlArbiter accessed outside its owner thread");
      }
   }

   private static Set<ActionChannel> immutableChannels(Set<ActionChannel> var0) {
      Objects.requireNonNull(var0, "channels");
      if (var0.isEmpty()) {
         throw new IllegalArgumentException("An action must require at least one channel");
      } else {
         for (ActionChannel var2 : var0) {
            Objects.requireNonNull(var2, "action channel");
         }

         return Collections.unmodifiableSet(EnumSet.copyOf(var0));
      }
   }

   public static record AcquireResult(
      ControlArbiter.AcquireStatus status, Optional<ControlArbiter.Lease> lease, Optional<ControlArbiter.Lease> blocker, List<ControlArbiter.Lease> preempted
   ) {
      public AcquireResult(
         ControlArbiter.AcquireStatus status,
         Optional<ControlArbiter.Lease> lease,
         Optional<ControlArbiter.Lease> blocker,
         List<ControlArbiter.Lease> preempted
      ) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(lease, "lease");
         Objects.requireNonNull(blocker, "blocker");
         preempted = List.copyOf(Objects.requireNonNull(preempted, "preempted"));
         switch (status) {
            case GRANTED:
               if (lease.isEmpty()
                  || blocker.isPresent()
                  || !preempted.isEmpty()) {
                  throw new IllegalArgumentException(
                     "GRANTED requires only the new lease"
                  );
               }
               break;
            case ALREADY_HELD:
               if (lease.isEmpty() || blocker.isPresent() || !preempted.isEmpty()) {
                  throw new IllegalArgumentException("ALREADY_HELD requires only the existing lease");
               }
               break;
            case REJECTED_LOWER_PRIORITY:
            case REJECTED_TIED_PRIORITY:
               if (lease.isPresent() || blocker.isEmpty() || !preempted.isEmpty()) {
                  throw new IllegalArgumentException("Rejected acquisition requires only a blocker");
               }
               break;
            case PREEMPTION_REQUIRED:
               if (lease.isPresent()
                  || blocker.isPresent()
                  || preempted.isEmpty()) {
                  throw new IllegalArgumentException(
                     "PREEMPTION_REQUIRED requires only retained lease candidates"
                  );
               }
         }

         this.status = status;
         this.lease = lease;
         this.blocker = blocker;
         this.preempted = preempted;
      }

      public boolean acquired() {
         return this.status == ControlArbiter.AcquireStatus.GRANTED || this.status == ControlArbiter.AcquireStatus.ALREADY_HELD;
      }

      /**
       * 仍由旧动作持有、必须先安全收口的 lease 候选。
       */
      public List<ControlArbiter.Lease> preemptionCandidates() {
         return this.preempted;
      }

      private static ControlArbiter.AcquireResult granted(ControlArbiter.Lease var0) {
         return new ControlArbiter.AcquireResult(ControlArbiter.AcquireStatus.GRANTED, Optional.of(var0), Optional.empty(), List.of());
      }

      private static ControlArbiter.AcquireResult alreadyHeld(ControlArbiter.Lease var0) {
         return new ControlArbiter.AcquireResult(ControlArbiter.AcquireStatus.ALREADY_HELD, Optional.of(var0), Optional.empty(), List.of());
      }

      private static ControlArbiter.AcquireResult rejected(ControlArbiter.AcquireStatus var0, ControlArbiter.Lease var1) {
         return new ControlArbiter.AcquireResult(var0, Optional.empty(), Optional.of(var1), List.of());
      }

      private static ControlArbiter.AcquireResult preemptionRequired(
         List<ControlArbiter.Lease> var0
      ) {
         return new ControlArbiter.AcquireResult(
            ControlArbiter.AcquireStatus.PREEMPTION_REQUIRED,
            Optional.empty(),
            Optional.empty(),
            var0
         );
      }
   }

   public static enum AcquireStatus {
      GRANTED,
      ALREADY_HELD,
      PREEMPTION_REQUIRED,
      REJECTED_LOWER_PRIORITY,
      REJECTED_TIED_PRIORITY;
   }

   private static record ActionKey(UUID botId, UUID actionId) {
   }

   private static record ChannelKey(UUID botId, ActionChannel channel) {
   }

   public static final class Lease {
      private final UUID botId;
      private final UUID actionId;
      private final long botGeneration;
      private final ActionPriority priority;
      private final Set<ActionChannel> channels;
      private final long sequence;

      private Lease(UUID var1, UUID var2, long var3, ActionPriority var5, Set<ActionChannel> var6, long var7) {
         this.botId = var1;
         this.actionId = var2;
         this.botGeneration = var3;
         this.priority = var5;
         this.channels = var6;
         this.sequence = var7;
      }

      public UUID botId() {
         return this.botId;
      }

      public UUID actionId() {
         return this.actionId;
      }

      public long botGeneration() {
         return this.botGeneration;
      }

      public ActionPriority priority() {
         return this.priority;
      }

      public Set<ActionChannel> channels() {
         return this.channels;
      }

      public long sequence() {
         return this.sequence;
      }
   }
}
