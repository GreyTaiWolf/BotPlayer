package io.github.greytaiwolf.botplayer.inventory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotInventorySessionManagerTest {
   private static final UUID FIRST_BOT = new UUID(0L, 101L);
   private static final UUID SECOND_BOT = new UUID(0L, 102L);
   private static final UUID FIRST_VIEWER = new UUID(0L, 201L);
   private static final UUID SECOND_VIEWER = new UUID(0L, 202L);
   private static final UUID WRONG_NONCE = new UUID(0L, 999L);

   @Test
   void openCloseLifecycleHoldsLockUntilRealCloseConfirmation() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySessionManager.OpenResult var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER);
      BotInventorySession var4 = var3.session().orElseThrow();
      InventorySessionToken var5 = var4.token();
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.OPENING, var3.status());
      Assertions.assertEquals(InventorySessionState.OPENING, var4.state());
      Assertions.assertTrue(var4.writeLockHeld());
      Assertions.assertEquals(InventoryMutationGate.MutationStatus.VIEWER_WRITE_LOCKED, var2.mutationGate().check(FIRST_BOT, 1L));
      Assertions.assertEquals(BotInventorySessionManager.OpenConfirmationStatus.OPENED, var2.markOpened(var5));
      Assertions.assertEquals(BotInventorySessionManager.OpenConfirmationStatus.ALREADY_OPEN, var2.markOpened(var5));
      Assertions.assertEquals(InventorySessionState.OPEN, var4.state());
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.CLOSE_REQUESTED, var2.forceClose(var5, InventoryCloseReason.DANGER));
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSING, var2.forceClose(var5, InventoryCloseReason.VIEWER_REQUEST));
      Assertions.assertEquals(InventorySessionState.CLOSING, var4.state());
      Assertions.assertEquals(InventoryCloseReason.DANGER, var4.closeReason().orElseThrow());
      Assertions.assertTrue(var4.writeLockHeld());
      Assertions.assertEquals(InventoryMutationGate.MutationStatus.VIEWER_WRITE_LOCKED, var2.mutationGate().check(FIRST_BOT, 1L));
      Assertions.assertEquals(BotInventorySessionManager.CloseConfirmationStatus.CLOSED, var2.confirmClosed(var5));
      Assertions.assertEquals(BotInventorySessionManager.CloseConfirmationStatus.ALREADY_CLOSED, var2.confirmClosed(var5));
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSED, var2.forceClose(var5, InventoryCloseReason.DANGER));
      Assertions.assertEquals(InventorySessionState.CLOSED, var4.state());
      Assertions.assertFalse(var4.writeLockHeld());
      Assertions.assertEquals(0, var2.activeSessionCount());
      Assertions.assertEquals(InventoryMutationGate.MutationStatus.ALLOWED, var2.mutationGate().check(FIRST_BOT, 1L));
   }

   @Test
   void duplicateOpenReusesSessionAndSingleViewerLocksBothDirections() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      BotInventorySessionManager.OpenResult var4 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER);
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.EXISTING_SESSION, var4.status());
      Assertions.assertSame(var3, var4.session().orElseThrow());
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.BOT_LOCKED, var2.open(FIRST_BOT, 1L, SECOND_VIEWER).status());
      BotInventorySessionManager.OpenResult var5 = var2.open(SECOND_BOT, 1L, FIRST_VIEWER);
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.VIEWER_BUSY, var5.status());
      Assertions.assertSame(var3, var5.session().orElseThrow());
      Assertions.assertEquals(1, var2.activeSessionCount());
      var2.forceClose(var3.token(), InventoryCloseReason.VIEWER_REQUEST);
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.SESSION_CLOSING, var2.open(FIRST_BOT, 1L, FIRST_VIEWER).status());
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.BOT_LOCKED, var2.open(FIRST_BOT, 2L, FIRST_VIEWER).status());
   }

   @Test
   void permissionRunsFirstAndDeniedOpenAllocatesNothing() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      var1.permissionAllowed = false;
      AtomicInteger var2 = new AtomicInteger();
      BotInventorySessionManager var3 = new BotInventorySessionManager(var1::canWrite, var1::distance, var1::lifecycle, () -> {
         var2.incrementAndGet();
         return UUID.randomUUID();
      }, 8);
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.PERMISSION_DENIED, var3.open(FIRST_BOT, 1L, FIRST_VIEWER).status());
      Assertions.assertEquals(1, var1.permissionCalls);
      Assertions.assertEquals(0, var1.lifecycleCalls);
      Assertions.assertEquals(0, var1.distanceCalls);
      Assertions.assertEquals(0, var2.get());
      Assertions.assertEquals(0, var3.activeSessionCount());
   }

   @Test
   void lifecycleAndSpatialOpenFailuresAreTyped() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);

      for (InventoryLifecycleValidator.LifecycleStatus var6 : InventoryLifecycleValidator.LifecycleStatus.values()) {
         var1.lifecycleStatus = var6;

         BotInventorySessionManager.OpenStatus var7 = switch (var6) {
            case ACTIVE -> BotInventorySessionManager.OpenStatus.OPENING;
            case UNKNOWN_BOT -> BotInventorySessionManager.OpenStatus.UNKNOWN_BOT;
            case NOT_ACTIVE -> BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
            case STALE_GENERATION -> BotInventorySessionManager.OpenStatus.STALE_GENERATION;
            case INVALID_INSTANCE -> BotInventorySessionManager.OpenStatus.INVALID_INSTANCE;
            case SERVER_STOPPING -> BotInventorySessionManager.OpenStatus.SERVER_STOPPING;
         };
         BotInventorySessionManager.OpenResult var8 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER);
         Assertions.assertEquals(var7, var8.status());
         if (var8.status() == BotInventorySessionManager.OpenStatus.OPENING) {
            close(var2, var8.session().orElseThrow());
         }
      }

      var1.lifecycleStatus = InventoryLifecycleValidator.LifecycleStatus.ACTIVE;

      for (InventoryDistanceValidator.SpatialStatus var12 : InventoryDistanceValidator.SpatialStatus.values()) {
         var1.spatialStatus = var12;

         BotInventorySessionManager.OpenStatus var13 = switch (var12) {
            case IN_RANGE -> BotInventorySessionManager.OpenStatus.OPENING;
            case OUT_OF_RANGE -> BotInventorySessionManager.OpenStatus.OUT_OF_RANGE;
            case DIFFERENT_DIMENSION -> BotInventorySessionManager.OpenStatus.DIFFERENT_DIMENSION;
            case VIEWER_NOT_ALIVE -> BotInventorySessionManager.OpenStatus.VIEWER_NOT_ALIVE;
            case BOT_NOT_ALIVE -> BotInventorySessionManager.OpenStatus.BOT_NOT_ALIVE;
         };
         BotInventorySessionManager.OpenResult var14 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER);
         Assertions.assertEquals(var13, var14.status());
         if (var14.status() == BotInventorySessionManager.OpenStatus.OPENING) {
            close(var2, var14.session().orElseThrow());
         }
      }
   }

   @Test
   void revalidationClosesOnPermissionLifecycleAndDistanceChanges() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      var1.permissionAllowed = false;
      Assertions.assertEquals(BotInventorySessionManager.ValidationStatus.PERMISSION_DENIED, var2.revalidate(var3.token()));
      Assertions.assertEquals(InventoryCloseReason.PERMISSION_REVOKED, var3.closeReason().orElseThrow());
      var2.confirmClosed(var3.token());
      var1.permissionAllowed = true;
      BotInventorySession var4 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      var1.lifecycleStatus = InventoryLifecycleValidator.LifecycleStatus.STALE_GENERATION;
      Assertions.assertEquals(BotInventorySessionManager.ValidationStatus.STALE_GENERATION, var2.revalidate(var4.token()));
      Assertions.assertEquals(InventoryCloseReason.STALE_GENERATION, var4.closeReason().orElseThrow());
      var2.confirmClosed(var4.token());
      var1.lifecycleStatus = InventoryLifecycleValidator.LifecycleStatus.ACTIVE;
      BotInventorySession var5 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      var1.spatialStatus = InventoryDistanceValidator.SpatialStatus.DIFFERENT_DIMENSION;
      Assertions.assertEquals(BotInventorySessionManager.ValidationStatus.DIFFERENT_DIMENSION, var2.revalidate(var5.token()));
      Assertions.assertEquals(InventoryCloseReason.DIMENSION_CHANGED, var5.closeReason().orElseThrow());
      Assertions.assertEquals(BotInventorySessionManager.ValidationStatus.CLOSING, var2.revalidate(var5.token()));
   }

   @Test
   void wrongNonceAndOldGenerationCannotCloseCurrentSession() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 2L, FIRST_VIEWER).session().orElseThrow();
      InventorySessionToken var4 = new InventorySessionToken(FIRST_BOT, 2L, FIRST_VIEWER, WRONG_NONCE);
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.NOT_FOUND, var2.forceClose(var4, InventoryCloseReason.DANGER));
      Assertions.assertEquals(
         BotInventorySessionManager.ForceCloseStatus.GENERATION_MISMATCH, var2.forceCloseBot(FIRST_BOT, 1L, InventoryCloseReason.BOT_DEATH)
      );
      Assertions.assertEquals(InventorySessionState.OPENING, var3.state());
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.CLOSE_REQUESTED, var2.forceCloseBot(FIRST_BOT, 2L, InventoryCloseReason.BOT_DEATH));
      Assertions.assertEquals(InventoryCloseReason.BOT_DEATH, var3.closeReason().orElseThrow());
   }

   @Test
   void failOpenReleasesLeaseWithoutMenuCallback() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      Assertions.assertEquals(BotInventorySessionManager.CloseConfirmationStatus.CLOSED, var2.failOpen(var3.token()));
      Assertions.assertEquals(InventoryCloseReason.OPEN_FAILED, var3.closeReason().orElseThrow());
      Assertions.assertFalse(var3.writeLockHeld());
      Assertions.assertEquals(0, var2.activeSessionCount());
   }

   @Test
   void closeAllAndViewerCloseAreIdempotent() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      BotInventorySession var4 = var2.open(SECOND_BOT, 1L, SECOND_VIEWER).session().orElseThrow();
      Assertions.assertEquals(
         BotInventorySessionManager.ForceCloseStatus.CLOSE_REQUESTED, var2.forceCloseViewer(FIRST_VIEWER, InventoryCloseReason.VIEWER_DISCONNECTED)
      );
      List<InventorySessionToken> var5 =
            var2.forceCloseAll(InventoryCloseReason.SERVER_STOPPING);
      Assertions.assertEquals(List.of(var4.token()), var5);
      Assertions.assertTrue(var2.forceCloseAll(InventoryCloseReason.SERVER_STOPPING).isEmpty());
      Assertions.assertEquals(InventoryCloseReason.VIEWER_DISCONNECTED, var3.closeReason().orElseThrow());
      Assertions.assertEquals(InventoryCloseReason.SERVER_STOPPING, var4.closeReason().orElseThrow());
   }

   @Test
   void closedTokenTombstonesAreBoundedAndNonceReuseIsRejected() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      UUID var2 = new UUID(1L, 1L);
      UUID var3 = new UUID(1L, 2L);
      UUID var4 = new UUID(1L, 3L);
      ArrayDeque<UUID> var5 = new ArrayDeque<>(List.of(var2, var3, var4));
      BotInventorySessionManager var6 = new BotInventorySessionManager(var1::canWrite, var1::distance, var1::lifecycle, var5::removeFirst, 2);
      BotInventorySession var7 = var6.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      close(var6, var7);
      BotInventorySession var8 = var6.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      close(var6, var8);
      BotInventorySession var9 = var6.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      close(var6, var9);
      Assertions.assertEquals(2, var6.closedTokenCount());
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.NOT_FOUND, var6.forceClose(var7.token(), InventoryCloseReason.DANGER));
      Assertions.assertEquals(BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSED, var6.forceClose(var8.token(), InventoryCloseReason.DANGER));
      BotInventorySessionManager var10 = new BotInventorySessionManager(var1::canWrite, var1::distance, var1::lifecycle, () -> var4, 2);
      BotInventorySession var11 = var10.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      Assertions.assertEquals(var4, var11.token().nonce());
      Assertions.assertEquals(BotInventorySessionManager.OpenStatus.NONCE_UNAVAILABLE, var10.open(SECOND_BOT, 1L, SECOND_VIEWER).status());
   }

   @Test
   void nullValidatorResultsFailClosed() {
      BotInventorySessionManager var1 = new BotInventorySessionManager(
         (var0, var1x) -> true, (var0, var1x) -> InventoryDistanceValidator.SpatialStatus.IN_RANGE, (var0, var1x) -> null
      );
      Assertions.assertThrows(NullPointerException.class, () -> var1.open(FIRST_BOT, 1L, FIRST_VIEWER));
      BotInventorySessionManager var2 = new BotInventorySessionManager(
         (var0, var1x) -> true, (var0, var1x) -> null, (var0, var1x) -> InventoryLifecycleValidator.LifecycleStatus.ACTIVE
      );
      Assertions.assertThrows(NullPointerException.class, () -> var2.open(FIRST_BOT, 1L, FIRST_VIEWER));
   }

   @Test
   void mutationGateMapsLifecycleAndDetectsStaleViewerLease() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);

      for (InventoryLifecycleValidator.LifecycleStatus var6 : InventoryLifecycleValidator.LifecycleStatus.values()) {
         var1.lifecycleStatus = var6;

         InventoryMutationGate.MutationStatus var7 = switch (var6) {
            case ACTIVE -> InventoryMutationGate.MutationStatus.ALLOWED;
            case UNKNOWN_BOT -> InventoryMutationGate.MutationStatus.UNKNOWN_BOT;
            case NOT_ACTIVE -> InventoryMutationGate.MutationStatus.BOT_NOT_ACTIVE;
            case STALE_GENERATION -> InventoryMutationGate.MutationStatus.STALE_GENERATION;
            case INVALID_INSTANCE -> InventoryMutationGate.MutationStatus.INVALID_INSTANCE;
            case SERVER_STOPPING -> InventoryMutationGate.MutationStatus.SERVER_STOPPING;
         };
         Assertions.assertEquals(var7, var2.mutationGate().check(FIRST_BOT, 1L));
      }

      var1.lifecycleStatus = InventoryLifecycleValidator.LifecycleStatus.ACTIVE;
      var2.open(FIRST_BOT, 2L, FIRST_VIEWER);
      Assertions.assertEquals(InventoryMutationGate.MutationStatus.STALE_VIEWER_LOCK, var2.mutationGate().check(FIRST_BOT, 1L));
      Assertions.assertEquals(InventoryMutationGate.MutationStatus.VIEWER_WRITE_LOCKED, var2.mutationGate().check(FIRST_BOT, 2L));
   }

   @Test
   void managerRejectsAccessOutsideOwnerThread() throws InterruptedException {
      BotInventorySessionManager var1 = manager(new BotInventorySessionManagerTest.MutableGuards());
      AtomicReference<Throwable> var2 = new AtomicReference<>();
      Thread var3 = new Thread(() -> {
         try {
            var1.activeSessionCount();
         } catch (Throwable var3x) {
            var2.set(var3x);
         }
      });
      var3.start();
      var3.join();
      Assertions.assertTrue(var2.get() instanceof IllegalStateException);
   }

   @Test
   void differentSessionsReceiveDifferentNonces() {
      BotInventorySessionManagerTest.MutableGuards var1 = new BotInventorySessionManagerTest.MutableGuards();
      BotInventorySessionManager var2 = manager(var1);
      BotInventorySession var3 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      close(var2, var3);
      BotInventorySession var4 = var2.open(FIRST_BOT, 1L, FIRST_VIEWER).session().orElseThrow();
      Assertions.assertNotEquals(var3.token().nonce(), var4.token().nonce());
   }

   private static BotInventorySessionManager manager(BotInventorySessionManagerTest.MutableGuards var0) {
      return new BotInventorySessionManager(var0::canWrite, var0::distance, var0::lifecycle);
   }

   private static void close(BotInventorySessionManager var0, BotInventorySession var1) {
      var0.forceClose(var1.token(), InventoryCloseReason.VIEWER_REQUEST);
      var0.confirmClosed(var1.token());
   }

   private static final class MutableGuards {
      private boolean permissionAllowed = true;
      private InventoryLifecycleValidator.LifecycleStatus lifecycleStatus = InventoryLifecycleValidator.LifecycleStatus.ACTIVE;
      private InventoryDistanceValidator.SpatialStatus spatialStatus = InventoryDistanceValidator.SpatialStatus.IN_RANGE;
      private int permissionCalls;
      private int lifecycleCalls;
      private int distanceCalls;

      private boolean canWrite(UUID var1, UUID var2) {
         this.permissionCalls++;
         return this.permissionAllowed;
      }

      private InventoryLifecycleValidator.LifecycleStatus lifecycle(UUID var1, long var2) {
         this.lifecycleCalls++;
         return this.lifecycleStatus;
      }

      private InventoryDistanceValidator.SpatialStatus distance(UUID var1, UUID var2) {
         this.distanceCalls++;
         return this.spatialStatus;
      }
   }
}
