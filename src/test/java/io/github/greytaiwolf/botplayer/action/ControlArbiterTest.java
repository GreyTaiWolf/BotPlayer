package io.github.greytaiwolf.botplayer.action;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ControlArbiterTest {
   private static final UUID FIRST_BOT = new UUID(0L, 100L);
   private static final UUID SECOND_BOT = new UUID(0L, 200L);

   @Test
   void oneBotAndChannelHasOneOwnerAndExistingLeaseWinsTies() {
      ControlArbiter var1 = new ControlArbiter();
      ActionEnvelope var2 = lookEnvelope(FIRST_BOT, 10L);
      ActionEnvelope var3 = lookEnvelope(FIRST_BOT, 1L);
      ControlArbiter.AcquireResult var4 = var1.acquire(var2, ActionPriority.OWNER_TASK);
      ControlArbiter.Lease var5 = var4.lease().orElseThrow();
      Assertions.assertEquals(ControlArbiter.AcquireStatus.GRANTED, var4.status());
      ControlArbiter.AcquireResult var6 = var1.acquire(var3, ActionPriority.OWNER_TASK);
      Assertions.assertEquals(ControlArbiter.AcquireStatus.REJECTED_TIED_PRIORITY, var6.status());
      Assertions.assertSame(var5, var6.blocker().orElseThrow());
      Assertions.assertSame(var5, var1.currentLease(FIRST_BOT, ActionChannel.LOOK).orElseThrow());
   }

   @Test
   void higherPriorityRequestsPreemptionWithoutStealingTheLease() {
      ControlArbiter var1 = new ControlArbiter();
      ActionEnvelope var2 = lookEnvelope(FIRST_BOT, 1L);
      ControlArbiter.Lease var3 = var1.acquire(var2, ActionPriority.OWNER_TASK).lease().orElseThrow();
      ControlArbiter.AcquireResult var4 = var1.acquire(lookEnvelope(FIRST_BOT, 2L), ActionPriority.AUTONOMOUS);
      Assertions.assertEquals(ControlArbiter.AcquireStatus.REJECTED_LOWER_PRIORITY, var4.status());
      Assertions.assertSame(var3, var4.blocker().orElseThrow());
      ActionEnvelope var5 = lookEnvelope(FIRST_BOT, 3L);
      ControlArbiter.AcquireResult var6 = var1.acquire(var5, ActionPriority.EMERGENCY);
      Assertions.assertEquals(
         ControlArbiter.AcquireStatus.PREEMPTION_REQUIRED,
         var6.status()
      );
      Assertions.assertFalse(var6.acquired());
      Assertions.assertTrue(var6.lease().isEmpty());
      Assertions.assertEquals(1, var6.preemptionCandidates().size());
      Assertions.assertSame(
         var3, var6.preemptionCandidates().getFirst()
      );
      Assertions.assertSame(var3, var1.currentLease(FIRST_BOT, ActionChannel.LOOK).orElseThrow());
      Assertions.assertTrue(var1.release(var3));
      ControlArbiter.AcquireResult var7 =
         var1.acquire(var5, ActionPriority.EMERGENCY);
      ControlArbiter.Lease var8 = var7.lease().orElseThrow();
      Assertions.assertEquals(
         ControlArbiter.AcquireStatus.GRANTED, var7.status()
      );
      Assertions.assertTrue(var7.preemptionCandidates().isEmpty());
      Assertions.assertSame(var8, var1.currentLease(FIRST_BOT, ActionChannel.LOOK).orElseThrow());
      Assertions.assertFalse(var1.release(var3));
      Assertions.assertSame(var8, var1.currentLease(FIRST_BOT, ActionChannel.LOOK).orElseThrow());
   }

   @Test
   void acquireAndReleaseAreIdempotent() {
      ControlArbiter var1 = new ControlArbiter();
      ActionEnvelope var2 = lookEnvelope(FIRST_BOT, 1L);
      ControlArbiter.AcquireResult var3 = var1.acquire(var2, ActionPriority.OWNER_TASK);
      ControlArbiter.AcquireResult var4 = var1.acquire(var2, ActionPriority.OWNER_TASK);
      Assertions.assertEquals(ControlArbiter.AcquireStatus.ALREADY_HELD, var4.status());
      Assertions.assertSame(var3.lease().orElseThrow(), var4.lease().orElseThrow());
      Assertions.assertEquals(1, var1.activeLeaseCount());
      Assertions.assertEquals(1, var1.occupiedChannelCount());
      Assertions.assertTrue(var1.release(var3.lease().orElseThrow()));
      Assertions.assertFalse(var1.release(var3.lease().orElseThrow()));
      Assertions.assertEquals(0, var1.activeLeaseCount());
      Assertions.assertEquals(0, var1.occupiedChannelCount());
   }

   @Test
   void sameChannelIsIsolatedBetweenBots() {
      ControlArbiter var1 = new ControlArbiter();
      Assertions.assertTrue(var1.acquire(lookEnvelope(FIRST_BOT, 1L), ActionPriority.AUTONOMOUS).acquired());
      Assertions.assertTrue(var1.acquire(lookEnvelope(SECOND_BOT, 2L), ActionPriority.AUTONOMOUS).acquired());
      Assertions.assertEquals(2, var1.activeLeaseCount());
      Assertions.assertEquals(2, var1.occupiedChannelCount());
   }

   @Test
   void stopWaitsForEveryOldOwnerBeforeAtomicGrant() {
      ControlArbiter var1 = new ControlArbiter();
      ControlArbiter.Lease var2 = var1.acquire(lookEnvelope(FIRST_BOT, 1L), ActionPriority.AUTONOMOUS).lease().orElseThrow();
      ControlArbiter.Lease var3 = var1.acquire(waitEnvelope(FIRST_BOT, 2L), ActionPriority.AUTONOMOUS).lease().orElseThrow();
      ActionEnvelope var4 = new ActionEnvelope(new UUID(0L, 3L), FIRST_BOT, 1L, "stop-3", 100L, 1, new StopAction(), ActionOrigin.none());
      ControlArbiter.AcquireResult var5 = var1.acquire(var4, ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(
         ControlArbiter.AcquireStatus.PREEMPTION_REQUIRED,
         var5.status()
      );
      Assertions.assertEquals(
         java.util.List.of(var2, var3),
         var5.preemptionCandidates()
      );
      Assertions.assertSame(
         var2,
         var1.currentLease(FIRST_BOT, ActionChannel.LOOK)
            .orElseThrow()
      );
      Assertions.assertSame(
         var3,
         var1.currentLease(FIRST_BOT, ActionChannel.MOVE)
            .orElseThrow()
      );
      Assertions.assertTrue(var1.release(var2));
      ControlArbiter.AcquireResult var6 =
         var1.acquire(var4, ActionPriority.OWNER_CONTROL);
      Assertions.assertEquals(
         ControlArbiter.AcquireStatus.PREEMPTION_REQUIRED,
         var6.status()
      );
      Assertions.assertEquals(
         java.util.List.of(var3),
         var6.preemptionCandidates()
      );
      Assertions.assertTrue(var6.lease().isEmpty());
      Assertions.assertTrue(var1.release(var3));
      ControlArbiter.AcquireResult var7 =
         var1.acquire(var4, ActionPriority.OWNER_CONTROL);
      ControlArbiter.Lease var8 = var7.lease().orElseThrow();
      Assertions.assertEquals(
         ControlArbiter.AcquireStatus.GRANTED, var7.status()
      );
      Assertions.assertEquals(ActionChannel.all(), var8.channels());
      Assertions.assertEquals(ActionChannel.values().length, var1.occupiedChannelCount());

      for (ActionChannel var9 : ActionChannel.values()) {
         Assertions.assertSame(var8, var1.currentLease(FIRST_BOT, var9).orElseThrow());
      }
   }

   @Test
   void actionIdCannotChangeLeaseAuthorityWhileHeld() {
      ControlArbiter var1 = new ControlArbiter();
      ActionEnvelope var2 = lookEnvelope(FIRST_BOT, 1L);
      var1.acquire(var2, ActionPriority.AUTONOMOUS);
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.acquire(var2, ActionPriority.EMERGENCY));
   }

   @Test
   void rejectsAccessOutsideTheOwnerThread() throws InterruptedException {
      ControlArbiter var1 = new ControlArbiter();
      AtomicReference<Throwable> var2 = new AtomicReference<>();
      Thread var3 = new Thread(() -> {
         try {
            var1.activeLeaseCount();
         } catch (Throwable var3x) {
            var2.set(var3x);
         }
      });
      var3.start();
      var3.join();
      Assertions.assertTrue(var2.get() instanceof IllegalStateException);
   }

   @Test
   void trustedPriorityOrderKeepsSafetyAndLifecycleAboveOwnerControl() {
      Assertions.assertTrue(ActionPriority.SURVIVAL.outranks(ActionPriority.OWNER_CONTROL));
      Assertions.assertTrue(ActionPriority.LIFECYCLE_CLEANUP.outranks(ActionPriority.SURVIVAL));
      Assertions.assertTrue(ActionPriority.EMERGENCY.outranks(ActionPriority.LIFECYCLE_CLEANUP));
   }

   private static ActionEnvelope lookEnvelope(UUID var0, long var1) {
      return new ActionEnvelope(new UUID(0L, var1), var0, 1L, "look-" + var1, 100L, 20, new LookAtAction(1.0, 2.0, 3.0), ActionOrigin.none());
   }

   private static ActionEnvelope waitEnvelope(UUID var0, long var1) {
      return new ActionEnvelope(
         new UUID(0L, var1),
         var0,
         1L,
         "wait-" + var1,
         100L,
         20,
         new WaitAction(20),
         ActionOrigin.none()
      );
   }
}
