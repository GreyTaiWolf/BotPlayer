package io.github.greytaiwolf.botplayer.action.input;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerInputController {
   public static final int MAX_CAPACITY = 65536;
   private final Thread ownerThread;
   private final int capacity;
   private final Map<UUID, PlayerInputController.BotInputSlot> slots = new LinkedHashMap<>();

   public PlayerInputController(int var1) {
      if (var1 >= 1 && var1 <= 65536) {
         this.ownerThread = Thread.currentThread();
         this.capacity = var1;
      } else {
         throw new IllegalArgumentException("capacity must be between 1 and 65536");
      }
   }

   public PlayerInputController.ClaimStatus claim(PlayerInputOwner var1, PlayerInputState var2, long var3, long var5) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "owner");
      Objects.requireNonNull(var2, "input");
      validateLeaseTicks(var3, var5);
      PlayerInputController.BotInputSlot var7 = this.slots.get(var1.botId());
      if (var7 == null) {
         if (this.slots.size() >= this.capacity) {
            return PlayerInputController.ClaimStatus.CAPACITY_EXHAUSTED;
         }

         var7 = new PlayerInputController.BotInputSlot(var1.botGeneration());
         this.slots.put(var1.botId(), var7);
      } else {
         if (var7.botGeneration > var1.botGeneration()) {
            return PlayerInputController.ClaimStatus.STALE_GENERATION;
         }

         if (var7.botGeneration < var1.botGeneration()) {
            var7.advanceGeneration(var1.botGeneration());
         }
      }

      if (var7.owner != null) {
         return var7.owner.equals(var1) ? PlayerInputController.ClaimStatus.ALREADY_OWNED : PlayerInputController.ClaimStatus.OWNED_BY_OTHER;
      } else {
         var7.owner = var1;
         var7.input = var2;
         var7.expiresAtTick = var5;
         var7.lastMutationTick = var3;
         return PlayerInputController.ClaimStatus.CLAIMED;
      }
   }

   public PlayerInputController.UpdateStatus update(PlayerInputOwner var1, PlayerInputState var2, long var3, long var5) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "owner");
      Objects.requireNonNull(var2, "input");
      validateLeaseTicks(var3, var5);
      PlayerInputController.BotInputSlot var7 = this.slots.get(var1.botId());
      if (var7 == null) {
         return PlayerInputController.UpdateStatus.NOT_TRACKED;
      } else if (var7.botGeneration != var1.botGeneration()) {
         return PlayerInputController.UpdateStatus.STALE_GENERATION;
      } else if (!var1.equals(var7.owner)) {
         return PlayerInputController.UpdateStatus.NOT_OWNER;
      } else if (var3 < var7.lastMutationTick) {
         return PlayerInputController.UpdateStatus.OUT_OF_ORDER;
      } else if (var3 != var7.lastMutationTick) {
         var7.input = var2;
         var7.expiresAtTick = var5;
         var7.lastMutationTick = var3;
         return PlayerInputController.UpdateStatus.UPDATED;
      } else {
         return var7.input.equals(var2) && var7.expiresAtTick == var5
            ? PlayerInputController.UpdateStatus.UNCHANGED
            : PlayerInputController.UpdateStatus.DUPLICATE_TICK;
      }
   }

   public PlayerInputController.ReleaseStatus release(PlayerInputOwner var1) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "owner");
      PlayerInputController.BotInputSlot var2 = this.slots.get(var1.botId());
      if (var2 == null) {
         return PlayerInputController.ReleaseStatus.NOT_TRACKED;
      } else if (var2.botGeneration != var1.botGeneration()) {
         return PlayerInputController.ReleaseStatus.STALE_GENERATION;
      } else if (var2.owner == null) {
         return PlayerInputController.ReleaseStatus.ALREADY_CLEAR;
      } else if (!var1.equals(var2.owner)) {
         return PlayerInputController.ReleaseStatus.NOT_OWNER;
      } else {
         var2.clearInput();
         return PlayerInputController.ReleaseStatus.RELEASED;
      }
   }

   /**
    * Returns whether the exact action still owns this generation's input writer.
    */
   public boolean isExactOwner(PlayerInputOwner owner) {
      this.assertOwnerThread();
      Objects.requireNonNull(owner, "owner");
      PlayerInputController.BotInputSlot slot = this.slots.get(owner.botId());
      return slot != null
         && slot.botGeneration == owner.botGeneration()
         && owner.equals(slot.owner);
   }

   public PlayerInputController.ForceClearStatus forceClear(UUID var1, long var2) {
      this.assertOwnerThread();
      requireNonZero(var1, "botId");
      requirePositiveGeneration(var2);
      PlayerInputController.BotInputSlot var4 = this.slots.get(var1);
      if (var4 == null) {
         return PlayerInputController.ForceClearStatus.NOT_TRACKED;
      } else if (var4.botGeneration > var2) {
         return PlayerInputController.ForceClearStatus.NEWER_GENERATION_PRESERVED;
      } else if (var4.owner == null && var4.input.isIdle()) {
         return PlayerInputController.ForceClearStatus.ALREADY_CLEAR;
      } else {
         var4.clearInput();
         return PlayerInputController.ForceClearStatus.CLEARED;
      }
   }

   public PlayerInputController.ApplyReport applyOnce(UUID var1, long var2, long var4, PlayerInputController.InputSink var6) {
      this.assertOwnerThread();
      requireNonZero(var1, "botId");
      requirePositiveGeneration(var2);
      requireNonNegativeTick(var4, "currentTick");
      Objects.requireNonNull(var6, "sink");
      PlayerInputController.BotInputSlot var7 = this.slots.get(var1);
      if (var7 == null) {
         if (this.slots.size() >= this.capacity) {
            var6.apply(PlayerInputState.IDLE);
            return new PlayerInputController.ApplyReport(PlayerInputController.ApplyStatus.CAPACITY_EXHAUSTED_ZEROED, PlayerInputState.IDLE, Optional.empty());
         }

         var7 = new PlayerInputController.BotInputSlot(var2);
         this.slots.put(var1, var7);
      } else {
         if (var7.botGeneration > var2) {
            return new PlayerInputController.ApplyReport(PlayerInputController.ApplyStatus.STALE_GENERATION_SKIPPED, PlayerInputState.IDLE, Optional.empty());
         }

         if (var7.botGeneration < var2) {
            var7.advanceGeneration(var2);
         }
      }

      if (var4 < var7.lastAppliedTick) {
         return new PlayerInputController.ApplyReport(
            PlayerInputController.ApplyStatus.OUT_OF_ORDER_SKIPPED, PlayerInputState.IDLE, Optional.ofNullable(var7.owner)
         );
      } else if (var4 == var7.lastAppliedTick) {
         return new PlayerInputController.ApplyReport(
            PlayerInputController.ApplyStatus.DUPLICATE_TICK_SKIPPED, var7.lastAppliedInput, Optional.ofNullable(var7.owner)
         );
      } else {
         PlayerInputController.ApplyStatus var8;
         if (var7.owner != null && var4 >= var7.expiresAtTick) {
            var7.clearInput();
            var8 = PlayerInputController.ApplyStatus.EXPIRED_ZEROED;
         } else {
            var8 = var7.owner == null ? PlayerInputController.ApplyStatus.IDLE_ZEROED : PlayerInputController.ApplyStatus.APPLIED;
         }

         PlayerInputState var9 = var7.input;
         Optional<PlayerInputOwner> appliedOwner = Optional.ofNullable(var7.owner);
         /*
          * Commit once-per-tick bookkeeping only after the external adapter succeeds. A partial
          * adapter failure may then retry the exact desired input in the same absolute tick.
          */
         var6.apply(var9);
         var7.lastAppliedTick = var4;
         var7.lastAppliedInput = var9;
         return new PlayerInputController.ApplyReport(var8, var9, appliedOwner);
      }
   }

   public PlayerInputController.ForgetStatus forgetBot(UUID var1, long var2) {
      this.assertOwnerThread();
      requireNonZero(var1, "botId");
      requirePositiveGeneration(var2);
      PlayerInputController.BotInputSlot var4 = this.slots.get(var1);
      if (var4 == null) {
         return PlayerInputController.ForgetStatus.NOT_TRACKED;
      } else if (var4.botGeneration > var2) {
         return PlayerInputController.ForgetStatus.NEWER_GENERATION_PRESERVED;
      } else {
         this.slots.remove(var1);
         return PlayerInputController.ForgetStatus.FORGOTTEN;
      }
   }

   public Optional<PlayerInputController.InputSnapshot> snapshot(UUID var1) {
      this.assertOwnerThread();
      requireNonZero(var1, "botId");
      PlayerInputController.BotInputSlot var2 = this.slots.get(var1);
      return var2 == null
         ? Optional.empty()
         : Optional.of(
            new PlayerInputController.InputSnapshot(
               var2.botGeneration,
               Optional.ofNullable(var2.owner),
               var2.input,
               var2.expiresAtTick,
               var2.lastMutationTick,
               var2.lastAppliedTick,
               var2.lastAppliedInput
            )
         );
   }

   public int trackedBotCount() {
      this.assertOwnerThread();
      return this.slots.size();
   }

   private void assertOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("PlayerInputController accessed outside its owner thread");
      }
   }

   private static void validateLeaseTicks(long var0, long var2) {
      requireNonNegativeTick(var0, "currentTick");
      requireNonNegativeTick(var2, "expiresAtTick");
      if (var2 <= var0) {
         throw new IllegalArgumentException("expiresAtTick must be after currentTick");
      } else if (var2 - var0 > 6000L) {
         throw new IllegalArgumentException("input lease exceeds the maximum action tick budget");
      }
   }

   private static void requireNonZero(UUID var0, String var1) {
      Objects.requireNonNull(var0, var1);
      if (var0.getMostSignificantBits() == 0L && var0.getLeastSignificantBits() == 0L) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      }
   }

   private static void requirePositiveGeneration(long var0) {
      if (var0 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      }
   }

   private static void requireNonNegativeTick(long var0, String var2) {
      if (var0 < 0L) {
         throw new IllegalArgumentException(var2 + " must not be negative");
      }
   }

   public static record ApplyReport(PlayerInputController.ApplyStatus status, PlayerInputState appliedInput, Optional<PlayerInputOwner> owner) {
      public ApplyReport(PlayerInputController.ApplyStatus status, PlayerInputState appliedInput, Optional<PlayerInputOwner> owner) {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(appliedInput, "appliedInput");
         Objects.requireNonNull(owner, "owner");
         this.status = status;
         this.appliedInput = appliedInput;
         this.owner = owner;
      }
   }

   public static enum ApplyStatus {
      APPLIED,
      IDLE_ZEROED,
      EXPIRED_ZEROED,
      DUPLICATE_TICK_SKIPPED,
      OUT_OF_ORDER_SKIPPED,
      STALE_GENERATION_SKIPPED,
      CAPACITY_EXHAUSTED_ZEROED;
   }

   private static final class BotInputSlot {
      private long botGeneration;
      private PlayerInputOwner owner;
      private PlayerInputState input = PlayerInputState.IDLE;
      private long expiresAtTick;
      private long lastMutationTick = -1L;
      private long lastAppliedTick = -1L;
      private PlayerInputState lastAppliedInput = PlayerInputState.IDLE;

      private BotInputSlot(long var1) {
         this.botGeneration = var1;
      }

      private void advanceGeneration(long var1) {
         this.botGeneration = var1;
         this.owner = null;
         this.input = PlayerInputState.IDLE;
         this.expiresAtTick = 0L;
         this.lastMutationTick = -1L;
         this.lastAppliedTick = -1L;
         this.lastAppliedInput = PlayerInputState.IDLE;
      }

      private void clearInput() {
         this.owner = null;
         this.input = PlayerInputState.IDLE;
         this.expiresAtTick = 0L;
      }
   }

   public static enum ClaimStatus {
      CLAIMED,
      ALREADY_OWNED,
      OWNED_BY_OTHER,
      STALE_GENERATION,
      CAPACITY_EXHAUSTED;
   }

   public static enum ForceClearStatus {
      CLEARED,
      ALREADY_CLEAR,
      NOT_TRACKED,
      NEWER_GENERATION_PRESERVED;
   }

   public static enum ForgetStatus {
      FORGOTTEN,
      NOT_TRACKED,
      NEWER_GENERATION_PRESERVED;
   }

   @FunctionalInterface
   public interface InputSink {
      void apply(PlayerInputState var1);
   }

   public static record InputSnapshot(
      long botGeneration,
      Optional<PlayerInputOwner> owner,
      PlayerInputState desiredInput,
      long expiresAtTick,
      long lastMutationTick,
      long lastAppliedTick,
      PlayerInputState lastAppliedInput
   ) {
      public InputSnapshot(
         long botGeneration,
         Optional<PlayerInputOwner> owner,
         PlayerInputState desiredInput,
         long expiresAtTick,
         long lastMutationTick,
         long lastAppliedTick,
         PlayerInputState lastAppliedInput
      ) {
         PlayerInputController.requirePositiveGeneration(botGeneration);
         Objects.requireNonNull(owner, "owner");
         Objects.requireNonNull(desiredInput, "desiredInput");
         Objects.requireNonNull(lastAppliedInput, "lastAppliedInput");
         this.botGeneration = botGeneration;
         this.owner = owner;
         this.desiredInput = desiredInput;
         this.expiresAtTick = expiresAtTick;
         this.lastMutationTick = lastMutationTick;
         this.lastAppliedTick = lastAppliedTick;
         this.lastAppliedInput = lastAppliedInput;
      }
   }

   public static enum ReleaseStatus {
      RELEASED,
      ALREADY_CLEAR,
      NOT_TRACKED,
      NOT_OWNER,
      STALE_GENERATION;
   }

   public static enum UpdateStatus {
      UPDATED,
      UNCHANGED,
      DUPLICATE_TICK,
      OUT_OF_ORDER,
      NOT_TRACKED,
      NOT_OWNER,
      STALE_GENERATION;
   }
}
