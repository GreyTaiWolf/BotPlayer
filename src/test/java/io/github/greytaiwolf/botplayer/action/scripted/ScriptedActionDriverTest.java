package io.github.greytaiwolf.botplayer.action.scripted;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.WaitAction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScriptedActionDriverTest {
   private static final UUID BOT_ID = new UUID(0L, 7L);

   @Test
   void executesSuccessFailureAndStaleStepsWithoutNetwork() {
      ScriptedActionDriver var1 = new ScriptedActionDriver(
         List.of(
            ScriptedActionDriver.Step.succeed(ActionKind.WAIT, 1),
            ScriptedActionDriver.Step.fail(ActionKind.WAIT, 0, ActionFailureCode.PERMISSION_DENIED, "Script denied the action"),
            ScriptedActionDriver.Step.stale(ActionKind.WAIT, 0)
         )
      );
      BotActionRuntime var2 = new BotActionRuntime(var1, 16, 16, 16, 4, 16);
      ActionMailbox.Submission var3 = var2.submit(envelope(1L, "success", 20L, 5), ActionPriority.OWNER_TASK);
      var2.tick(1L);
      var2.tick(2L);
      Assertions.assertEquals(ActionState.SUCCEEDED, outcome(var3).state());
      ActionMailbox.Submission var4 = var2.submit(envelope(2L, "failure", 20L, 5), ActionPriority.OWNER_TASK);
      var2.tick(3L);
      Assertions.assertEquals(ActionFailureCode.PERMISSION_DENIED, outcome(var4).failureCode());
      ActionMailbox.Submission var5 = var2.submit(envelope(3L, "stale", 20L, 5), ActionPriority.OWNER_TASK);
      var2.tick(4L);
      Assertions.assertEquals(ActionState.STALE, outcome(var5).state());
      Assertions.assertEquals(3L, var1.startedCount());
      Assertions.assertEquals(3L, var1.cleanupCount());
      Assertions.assertEquals(0, var1.remainingStepCount());
      Assertions.assertEquals(0, var1.activeStepCount());
   }

   @Test
   void survivesMoreThanOneHundredStartCancelCleanupCycles() {
      byte var1 = 125;
      ArrayList<ScriptedActionDriver.Step> var2 = new ArrayList<>(var1);

      for (int var3 = 0; var3 < var1; var3++) {
         var2.add(ScriptedActionDriver.Step.succeed(ActionKind.WAIT, 100));
      }

      ScriptedActionDriver var11 = new ScriptedActionDriver(var2);
      BotActionRuntime var4 = new BotActionRuntime(var11, 32, 256, 32, 8, 32);
      long var5 = 1L;

      for (int var7 = 0; var7 < var1; var7++) {
         ActionEnvelope var8 = envelope((long)(var7 + 1), "cancel-" + var7, var5 + 500L, 200);
         ActionMailbox.Submission var9 = var4.submit(var8, ActionPriority.OWNER_TASK);
         var4.tick(var5++);
         ActionMailbox.Cancellation var10 = var4.cancel(BOT_ID, var8.actionId(), ActionCancellationReason.REQUESTED);
         var4.tick(var5++);
         Assertions.assertEquals(ActionMailbox.CancellationStatus.CANCELLED, var10.completion().orElseThrow().toCompletableFuture().join());
         Assertions.assertEquals(ActionState.CANCELLED, outcome(var9).state());
         Assertions.assertEquals(0, var4.activeActionCount());
         Assertions.assertEquals(0, var4.activeLeaseCount());
         Assertions.assertEquals(0, var11.activeStepCount());
      }

      Assertions.assertEquals((long)var1, var11.startedCount());
      Assertions.assertEquals((long)var1, var11.cleanupCount());
      Assertions.assertEquals(0, var11.remainingStepCount());
      Assertions.assertEquals(0L, var4.cleanupFailureCount());
   }

   @Test
   void scriptAndStepInputsAreHardBounded() {
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new ScriptedActionDriver(List.of(ScriptedActionDriver.Step.fail(ActionKind.WAIT, 0, ActionFailureCode.NONE, "invalid")))
      );
      ArrayList<ScriptedActionDriver.Step> var1 = new ArrayList<>(4097);

      for (int var2 = 0; var2 <= 4096; var2++) {
         var1.add(ScriptedActionDriver.Step.succeed(ActionKind.WAIT, 0));
      }

      Assertions.assertThrows(IllegalArgumentException.class, () -> new ScriptedActionDriver(var1));
   }

   @Test
   void rejectsScriptUseFromAnotherThread() throws InterruptedException {
      ScriptedActionDriver var1 = new ScriptedActionDriver(List.of());
      ArrayList<Throwable> var2 = new ArrayList<>(1);
      Thread var3 = new Thread(() -> {
         try {
            var1.remainingStepCount();
         } catch (Throwable var3x) {
            var2.add(var3x);
         }
      });
      var3.start();
      var3.join();
      Assertions.assertEquals(1, var2.size());
      Assertions.assertTrue(var2.getFirst() instanceof IllegalStateException);
   }

   private static ActionEnvelope envelope(long var0, String var2, long var3, int var5) {
      return new ActionEnvelope(new UUID(0L, var0), BOT_ID, 1L, var2, var3, var5, new WaitAction(Math.min(100, var5)), ActionOrigin.none());
   }

   private static ActionOutcome outcome(ActionMailbox.Submission var0) {
      return var0.completion().orElseThrow().toCompletableFuture().join();
   }
}
