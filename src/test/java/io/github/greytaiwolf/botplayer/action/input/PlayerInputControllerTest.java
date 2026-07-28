package io.github.greytaiwolf.botplayer.action.input;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlayerInputControllerTest {
   private static final UUID BOT = new UUID(0L, 100L);
   private static final UUID OTHER_BOT = new UUID(0L, 200L);
   private static final PlayerInputState FORWARD = new PlayerInputState(1.0F, 0.0F, false, false, false, false);
   private static final PlayerInputState STRAFE = new PlayerInputState(0.0F, 1.0F, false, false, false, false);

   @Test
   void exactOwnerDrivesOncePerServerTick() {
      PlayerInputController var1 = new PlayerInputController(2);
      PlayerInputOwner var2 = owner(1L, 1L);
      ArrayList<PlayerInputState> var3 = new ArrayList<>();
      Assertions.assertEquals(PlayerInputController.ClaimStatus.CLAIMED, var1.claim(var2, FORWARD, 10L, 20L));
      Assertions.assertEquals(PlayerInputController.ApplyStatus.APPLIED, var1.applyOnce(BOT, 1L, 11L, var3::add).status());
      Assertions.assertEquals(PlayerInputController.ApplyStatus.DUPLICATE_TICK_SKIPPED, var1.applyOnce(BOT, 1L, 11L, var3::add).status());
      Assertions.assertEquals(List.of(FORWARD), var3);
   }

   @Test
   void oneDistinctMutationPerTickIsEnforced() {
      PlayerInputController var1 = new PlayerInputController(1);
      PlayerInputOwner var2 = owner(1L, 1L);
      var1.claim(var2, FORWARD, 10L, 20L);
      Assertions.assertEquals(PlayerInputController.UpdateStatus.DUPLICATE_TICK, var1.update(var2, STRAFE, 10L, 20L));
      Assertions.assertEquals(PlayerInputController.UpdateStatus.UNCHANGED, var1.update(var2, FORWARD, 10L, 20L));
      Assertions.assertEquals(PlayerInputController.UpdateStatus.UPDATED, var1.update(var2, STRAFE, 11L, 21L));
      Assertions.assertEquals(PlayerInputController.UpdateStatus.OUT_OF_ORDER, var1.update(var2, FORWARD, 10L, 20L));
   }

   @Test
   void oldCleanupCannotEraseANewerOwner() {
      PlayerInputController var1 = new PlayerInputController(1);
      PlayerInputOwner var2 = owner(1L, 1L);
      PlayerInputOwner var3 = owner(1L, 2L);
      var1.claim(var2, FORWARD, 10L, 20L);
      Assertions.assertEquals(PlayerInputController.ReleaseStatus.RELEASED, var1.release(var2));
      Assertions.assertEquals(PlayerInputController.ClaimStatus.CLAIMED, var1.claim(var3, STRAFE, 10L, 20L));
      Assertions.assertEquals(PlayerInputController.ReleaseStatus.NOT_OWNER, var1.release(var2));
      Assertions.assertEquals(var3, var1.snapshot(BOT).orElseThrow().owner().orElseThrow());
      Assertions.assertEquals(STRAFE, var1.snapshot(BOT).orElseThrow().desiredInput());
   }

   @Test
   void newerGenerationInvalidatesOldInputAndRejectsLateWork() {
      PlayerInputController var1 = new PlayerInputController(1);
      PlayerInputOwner var2 = owner(1L, 1L);
      PlayerInputOwner var3 = owner(2L, 2L);
      ArrayList<PlayerInputState> var4 = new ArrayList<>();
      var1.claim(var2, FORWARD, 10L, 20L);
      Assertions.assertEquals(PlayerInputController.ClaimStatus.CLAIMED, var1.claim(var3, STRAFE, 11L, 21L));
      Assertions.assertEquals(PlayerInputController.ReleaseStatus.STALE_GENERATION, var1.release(var2));
      Assertions.assertEquals(PlayerInputController.ApplyStatus.STALE_GENERATION_SKIPPED, var1.applyOnce(BOT, 1L, 12L, var4::add).status());
      Assertions.assertTrue(var4.isEmpty());
      Assertions.assertEquals(PlayerInputController.ApplyStatus.APPLIED, var1.applyOnce(BOT, 2L, 12L, var4::add).status());
      Assertions.assertEquals(List.of(STRAFE), var4);
   }

   @Test
   void leaseExpiryActivelyZerosInput() {
      PlayerInputController var1 = new PlayerInputController(1);
      PlayerInputOwner var2 = owner(1L, 1L);
      ArrayList<PlayerInputState> var3 = new ArrayList<>();
      var1.claim(var2, FORWARD, 10L, 12L);
      var1.applyOnce(BOT, 1L, 11L, var3::add);
      Assertions.assertEquals(PlayerInputController.ApplyStatus.EXPIRED_ZEROED, var1.applyOnce(BOT, 1L, 12L, var3::add).status());
      Assertions.assertEquals(List.of(FORWARD, PlayerInputState.IDLE), var3);
      Assertions.assertTrue(var1.snapshot(BOT).orElseThrow().owner().isEmpty());
   }

   @Test
   void lifecycleClearPreservesANewerGeneration() {
      PlayerInputController var1 = new PlayerInputController(1);
      PlayerInputOwner var2 = owner(2L, 2L);
      var1.claim(var2, STRAFE, 10L, 20L);
      Assertions.assertEquals(PlayerInputController.ForceClearStatus.NEWER_GENERATION_PRESERVED, var1.forceClear(BOT, 1L));
      Assertions.assertEquals(PlayerInputController.ForceClearStatus.CLEARED, var1.forceClear(BOT, 2L));
      Assertions.assertEquals(PlayerInputState.IDLE, var1.snapshot(BOT).orElseThrow().desiredInput());
   }

   @Test
   void capacityAndLeaseDurationAreBounded() {
      PlayerInputController var1 = new PlayerInputController(1);
      var1.claim(owner(1L, 1L), FORWARD, 10L, 20L);
      PlayerInputOwner var2 = new PlayerInputOwner(OTHER_BOT, 1L, new UUID(0L, 3L));
      Assertions.assertEquals(PlayerInputController.ClaimStatus.CAPACITY_EXHAUSTED, var1.claim(var2, STRAFE, 10L, 20L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.update(owner(1L, 1L), FORWARD, 10L, 6011L));
   }

   @Test
   void accessOutsideTheOwnerThreadIsRejected() throws InterruptedException {
      PlayerInputController var1 = new PlayerInputController(1);
      AtomicReference<Throwable> var2 = new AtomicReference<>();
      Thread var3 = new Thread(() -> {
         try {
            var1.trackedBotCount();
         } catch (Throwable var3x) {
            var2.set(var3x);
         }
      });
      var3.start();
      var3.join();
      Assertions.assertTrue(var2.get() instanceof IllegalStateException);
   }

   @Test
   void exactOwnerCheckRejectsOtherActionsAndGenerations() {
      PlayerInputController controller = new PlayerInputController(1);
      PlayerInputOwner owner = owner(1L, 1L);
      PlayerInputOwner otherAction = owner(1L, 2L);
      PlayerInputOwner newerGeneration = owner(2L, 3L);

      Assertions.assertFalse(controller.isExactOwner(owner));
      Assertions.assertEquals(
         PlayerInputController.ClaimStatus.CLAIMED,
         controller.claim(owner, FORWARD, 10L, 20L));
      Assertions.assertTrue(controller.isExactOwner(owner));
      Assertions.assertFalse(controller.isExactOwner(otherAction));
      Assertions.assertFalse(controller.isExactOwner(newerGeneration));
      Assertions.assertEquals(
         PlayerInputController.ReleaseStatus.RELEASED,
         controller.release(owner));
      Assertions.assertFalse(controller.isExactOwner(owner));
   }

   @Test
   void failedSinkCanRetryTheSameAbsoluteTick() {
      PlayerInputController controller = new PlayerInputController(1);
      PlayerInputOwner owner = owner(1L, 1L);
      List<PlayerInputState> applied = new ArrayList<>();
      controller.claim(owner, FORWARD, 10L, 20L);

      Assertions.assertThrows(
         IllegalStateException.class,
         () -> controller.applyOnce(
            BOT,
            1L,
            11L,
            ignored -> {
               throw new IllegalStateException("adapter failed");
            }));
      PlayerInputController.ApplyReport retry = controller.applyOnce(
         BOT, 1L, 11L, applied::add);

      Assertions.assertEquals(
         PlayerInputController.ApplyStatus.APPLIED,
         retry.status());
      Assertions.assertEquals(List.of(FORWARD), applied);
      Assertions.assertEquals(
         11L,
         controller.snapshot(BOT).orElseThrow().lastAppliedTick());
   }

   private static PlayerInputOwner owner(long var0, long var2) {
      return new PlayerInputOwner(BOT, var0, new UUID(0L, var2));
   }
}
