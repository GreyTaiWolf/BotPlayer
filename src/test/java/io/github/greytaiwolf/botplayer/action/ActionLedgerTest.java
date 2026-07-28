package io.github.greytaiwolf.botplayer.action;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionLedgerTest {
   private static final UUID FIRST_BOT = new UUID(0L, 100L);
   private static final UUID SECOND_BOT = new UUID(0L, 200L);

   @Test
   void rejectsInProgressDuplicateAndReplaysCanonicalFinalResult() {
      ActionLedger var1 = new ActionLedger(4);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "same-key");
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "same-key");
      ActionLedger.BeginResult var4 = var1.begin(var2);
      Assertions.assertEquals(ActionLedger.BeginStatus.STARTED, var4.status());
      Assertions.assertTrue(var4.accepted());
      ActionLedger.BeginResult var5 = var1.begin(var3);
      Assertions.assertEquals(ActionLedger.BeginStatus.DUPLICATE_IN_PROGRESS, var5.status());
      Assertions.assertEquals(var2.actionId(), var5.canonicalActionId().orElseThrow());
      ActionOutcome var6 = success(var2, 10L, 11L);
      Assertions.assertSame(var6, var1.complete(var2, var6));
      Assertions.assertSame(var6, var1.complete(var2, var6));
      ActionLedger.BeginResult var7 = var1.begin(var3);
      Assertions.assertEquals(ActionLedger.BeginStatus.REPLAYED, var7.status());
      Assertions.assertEquals(var2.actionId(), var7.canonicalActionId().orElseThrow());
      Assertions.assertSame(var6, var7.replayedOutcome().orElseThrow());
      Assertions.assertEquals(1, var1.size());
   }

   @Test
   void idempotencyKeysAreScopedByBot() {
      ActionLedger var1 = new ActionLedger(2);
      Assertions.assertTrue(var1.begin(envelope(FIRST_BOT, 1L, "shared")).accepted());
      Assertions.assertTrue(var1.begin(envelope(SECOND_BOT, 2L, "shared")).accepted());
      Assertions.assertEquals(2, var1.size());
   }

   @Test
   void rejectsCapacityWhenAllEntriesAreInProgress() {
      ActionLedger var1 = new ActionLedger(2);
      var1.begin(envelope(FIRST_BOT, 1L, "one"));
      var1.begin(envelope(FIRST_BOT, 2L, "two"));
      ActionLedger.BeginResult var2 = var1.begin(envelope(FIRST_BOT, 3L, "three"));
      Assertions.assertEquals(ActionLedger.BeginStatus.CAPACITY_EXHAUSTED, var2.status());
      Assertions.assertFalse(var2.accepted());
      Assertions.assertEquals(2, var1.size());
   }

   @Test
   void evictsOldestCompletedEntryButNeverAnInProgressEntry() {
      ActionLedger var1 = new ActionLedger(2);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "in-progress");
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "completed");
      var1.begin(var2);
      var1.begin(var3);
      var1.complete(var3, success(var3, 1L, 2L));
      ActionEnvelope var4 = envelope(FIRST_BOT, 3L, "replacement");
      Assertions.assertTrue(var1.begin(var4).accepted());
      Assertions.assertTrue(var1.isInProgress(FIRST_BOT, "in-progress"));
      Assertions.assertTrue(var1.isInProgress(FIRST_BOT, "replacement"));
      Assertions.assertTrue(var1.completedOutcome(FIRST_BOT, "completed").isEmpty());
      Assertions.assertEquals(2, var1.size());
   }

   @Test
   void conflictingCompletionCannotReplaceTheCanonicalResult() {
      ActionLedger var1 = new ActionLedger(1);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "one");
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "one");
      var1.begin(var2);
      ActionOutcome var4 = success(var2, 1L, 2L);
      var1.complete(var2, var4);
      ActionOutcome var5 = new ActionOutcome(var2.actionId(), ActionState.FAILED, ActionFailureCode.INTERNAL_ERROR, 1L, 2L, List.of(), "failed");
      Assertions.assertThrows(IllegalStateException.class, () -> var1.complete(var2, var5));
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.complete(var3, var4));
      ActionEnvelope var6 = new ActionEnvelope(
         var2.actionId(), var2.botId(), 2L, var2.idempotencyKey(), var2.deadlineTick(), var2.maxTicks(), var2.action(), var2.origin()
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.complete(var6, var4));
   }

   @Test
   void validatesCapacityBoundsAndEnforcesThreadConfinement() throws InterruptedException {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ActionLedger(0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ActionLedger(65537));
      ActionLedger var1 = new ActionLedger(1);
      AtomicReference<Throwable> var2 = new AtomicReference<>();
      Thread var3 = new Thread(() -> {
         try {
            var1.size();
         } catch (Throwable var3x) {
            var2.set(var3x);
         }
      });
      var3.start();
      var3.join();
      Assertions.assertTrue(var2.get() instanceof IllegalStateException);
   }

   @Test
   void rejectsChangedOperationBehindTheSameIdempotencyKey() {
      ActionLedger var1 = new ActionLedger(3);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "same");
      var1.begin(var2);
      ActionEnvelope var3 = new ActionEnvelope(new UUID(0L, 2L), FIRST_BOT, 2L, "same", 120L, 20, var2.action(), var2.origin());
      ActionEnvelope var4 = new ActionEnvelope(new UUID(0L, 3L), FIRST_BOT, 1L, "same", 120L, 20, new LookAtAction(9.0, 9.0, 9.0), var2.origin());
      Assertions.assertEquals(ActionLedger.BeginStatus.IDEMPOTENCY_CONFLICT, var1.begin(var3).status());
      Assertions.assertEquals(ActionLedger.BeginStatus.IDEMPOTENCY_CONFLICT, var1.begin(var4).status());
      Assertions.assertEquals(1, var1.size());
   }

   @Test
   void rejectsReusingAnActionIdWithAnotherIdempotencyKey() {
      ActionLedger var1 = new ActionLedger(2);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "first-key");
      var1.begin(var2);
      ActionEnvelope var3 = new ActionEnvelope(
         var2.actionId(), var2.botId(), var2.botGeneration(), "second-key", var2.deadlineTick(), var2.maxTicks(), var2.action(), var2.origin()
      );
      ActionLedger.BeginResult var4 = var1.begin(var3);
      Assertions.assertEquals(ActionLedger.BeginStatus.IDEMPOTENCY_CONFLICT, var4.status());
      Assertions.assertEquals(var2.actionId(), var4.canonicalActionId().orElseThrow());
      Assertions.assertEquals(1, var1.size());
   }

   @Test
   void evictionAlsoReleasesTheActionIdIndex() {
      ActionLedger var1 = new ActionLedger(1);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "first");
      var1.begin(var2);
      var1.complete(var2, success(var2, 1L, 1L));
      ActionEnvelope var3 = envelope(FIRST_BOT, 2L, "second");
      Assertions.assertTrue(var1.begin(var3).accepted());
      var1.complete(var3, success(var3, 2L, 2L));
      ActionEnvelope var4 = new ActionEnvelope(
         var2.actionId(), var2.botId(), var2.botGeneration(), "reused-after-eviction", var2.deadlineTick(), var2.maxTicks(), var2.action(), var2.origin()
      );
      Assertions.assertTrue(var1.begin(var4).accepted());
   }

   @Test
   void retainsOnlyBoundedInProgressAliasesAndEvictsTheirIndexesTogether() {
      ActionLedger var1 = new ActionLedger(1);
      ActionEnvelope var2 = envelope(FIRST_BOT, 1L, "aliases");
      var1.begin(var2);

      for (long var3 = 2L; var3 <= 64L; var3++) {
         ActionEnvelope var5 = envelope(FIRST_BOT, var3, "aliases");
         Assertions.assertEquals(ActionLedger.BeginStatus.DUPLICATE_IN_PROGRESS, var1.begin(var5).status());
         Assertions.assertTrue(var1.registerAlias(var5, var2.actionId()));
         Assertions.assertEquals(var2.actionId(), var1.canonicalActionId(FIRST_BOT, var5.actionId()).orElseThrow());
      }

      ActionEnvelope var6 = envelope(FIRST_BOT, 65L, "aliases");
      Assertions.assertFalse(var1.registerAlias(var6, var2.actionId()));
      ActionOutcome var4 = success(var2, 1L, 2L);
      var1.complete(var2, var4);
      Assertions.assertSame(var4, var1.completedOutcome(FIRST_BOT, new UUID(0L, 2L)).orElseThrow());
      ActionEnvelope var7 = envelope(FIRST_BOT, 100L, "replacement");
      Assertions.assertTrue(var1.begin(var7).accepted());
      Assertions.assertTrue(var1.canonicalActionId(FIRST_BOT, new UUID(0L, 2L)).isEmpty());
   }

   private static ActionEnvelope envelope(UUID var0, long var1, String var3) {
      return new ActionEnvelope(new UUID(0L, var1), var0, 1L, var3, 100L, 20, new LookAtAction(1.0, 2.0, 3.0), ActionOrigin.none());
   }

   private static ActionOutcome success(ActionEnvelope var0, long var1, long var3) {
      return new ActionOutcome(var0.actionId(), ActionState.SUCCEEDED, ActionFailureCode.NONE, var1, var3, List.of(), "done");
   }
}
