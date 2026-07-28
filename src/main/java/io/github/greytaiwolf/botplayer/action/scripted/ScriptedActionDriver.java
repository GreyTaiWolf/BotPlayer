package io.github.greytaiwolf.botplayer.action.scripted;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ScriptedActionDriver implements ActionBackend {
   public static final int MAX_SCRIPT_STEPS = 4096;
   private final Thread ownerThread = Thread.currentThread();
   private final Deque<ScriptedActionDriver.IndexedStep> remaining;
   private final Map<ScriptedActionDriver.ActionKey, ScriptedActionDriver.ActiveStep> active = new LinkedHashMap<>();
   private long startedCount;
   private long cleanupCount;

   public ScriptedActionDriver(List<ScriptedActionDriver.Step> var1) {
      Objects.requireNonNull(var1, "steps");
      if (var1.size() > 4096) {
         throw new IllegalArgumentException("script exceeds 4096 steps");
      } else {
         this.remaining = new ArrayDeque<>(var1.size());

         for (int var2 = 0; var2 < var1.size(); var2++) {
            this.remaining
               .addLast(new ScriptedActionDriver.IndexedStep(
                  var2, Objects.requireNonNull(var1.get(var2), "script step")));
         }
      }
   }

   @Override
   public ActionBackend.BackendResult validate(ActionEnvelope var1, long var2) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "envelope");
      ScriptedActionDriver.IndexedStep var4 = this.remaining.peekFirst();
      if (var4 == null) {
         return ActionBackend.BackendResult.failed(var1, ActionFailureCode.UNSUPPORTED, List.of(), "Script has no remaining action step");
      } else {
         return var4.step().kind() != var1.action().kind()
            ? ActionBackend.BackendResult.failed(var1, ActionFailureCode.INVALID_REQUEST, List.of(), "Action kind does not match the next script step")
            : ActionBackend.BackendResult.accepted(var1);
      }
   }

   @Override
   public ActionBackend.BackendResult start(ActionEnvelope var1, long var2) {
      this.assertOwnerThread();
      ScriptedActionDriver.ActionKey var4 = ScriptedActionDriver.ActionKey.from(var1);
      if (this.active.containsKey(var4)) {
         return ActionBackend.BackendResult.failed(var1, ActionFailureCode.INTERNAL_ERROR, List.of(), "Script action was started more than once");
      } else {
         ScriptedActionDriver.IndexedStep var5 = this.remaining.pollFirst();
         if (var5 != null && var5.step().kind() == var1.action().kind()) {
            this.active.put(var4, new ScriptedActionDriver.ActiveStep(var5, var2));
            this.startedCount = incrementSaturated(this.startedCount);
            return var5.step().runTicks() == 0 ? ActionBackend.BackendResult.readyToVerify(var1) : ActionBackend.BackendResult.running(var1);
         } else {
            return ActionBackend.BackendResult.failed(var1, ActionFailureCode.INVALID_REQUEST, List.of(), "Script changed between validation and start");
         }
      }
   }

   @Override
   public ActionBackend.BackendResult tick(ActionEnvelope var1, long var2, long var4) {
      this.assertOwnerThread();
      ScriptedActionDriver.ActiveStep var6 = this.active.get(ScriptedActionDriver.ActionKey.from(var1));
      if (var6 != null && var6.startedTick() == var2) {
         return var4 - var2 >= (long)var6.indexed().step().runTicks()
            ? ActionBackend.BackendResult.readyToVerify(var1)
            : ActionBackend.BackendResult.running(var1);
      } else {
         return ActionBackend.BackendResult.failed(var1, ActionFailureCode.INTERNAL_ERROR, List.of(), "Script has no matching active action");
      }
   }

   @Override
   public ActionBackend.BackendResult verify(ActionEnvelope var1, long var2) {
      this.assertOwnerThread();
      ScriptedActionDriver.ActiveStep var4 = this.active.get(ScriptedActionDriver.ActionKey.from(var1));
      if (var4 == null) {
         return ActionBackend.BackendResult.failed(var1, ActionFailureCode.INTERNAL_ERROR, List.of(), "Script has no action to verify");
      } else {
         ScriptedActionDriver.IndexedStep var5 = var4.indexed();
         ArrayList<ActionEvidence> var6 = new ArrayList<>(var5.step().evidence());
         var6.add(new ActionEvidence("script.step", Integer.toString(var5.index())));
         var6.add(new ActionEvidence("script.kind", var5.step().kind().name()));

         return switch (var5.step().result()) {
            case SUCCEED -> ActionBackend.BackendResult.succeeded(var1, var6, var5.step().safeSummary());
            case FAIL -> ActionBackend.BackendResult.failed(var1, var5.step().failureCode(), var6, var5.step().safeSummary());
            case STALE -> ActionBackend.BackendResult.stale(var1, var5.step().safeSummary());
         };
      }
   }

   @Override
   public void cleanup(ActionEnvelope var1, ActionCleanupReason var2, long var3) {
      this.assertOwnerThread();
      Objects.requireNonNull(var2, "reason");
      if (this.active.remove(ScriptedActionDriver.ActionKey.from(var1)) != null) {
         this.cleanupCount = incrementSaturated(this.cleanupCount);
      }
   }

   @Override
   public boolean forceSafeReset(UUID var1, long var2, long var4) {
      this.assertOwnerThread();
      Objects.requireNonNull(var1, "botId");
      if (var1.equals(new UUID(0L, 0L))) {
         throw new IllegalArgumentException("botId must not be the zero UUID");
      } else if (var2 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         this.active.keySet().removeIf(var3 -> var3.botId().equals(var1) && var3.botGeneration() == var2);
         return true;
      }
   }

   public int remainingStepCount() {
      this.assertOwnerThread();
      return this.remaining.size();
   }

   public int activeStepCount() {
      this.assertOwnerThread();
      return this.active.size();
   }

   public long startedCount() {
      this.assertOwnerThread();
      return this.startedCount;
   }

   public long cleanupCount() {
      this.assertOwnerThread();
      return this.cleanupCount;
   }

   private void assertOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("ScriptedActionDriver must run on its owner thread");
      }
   }

   private static long incrementSaturated(long var0) {
      return var0 == Long.MAX_VALUE ? Long.MAX_VALUE : var0 + 1L;
   }

   private static record ActionKey(UUID botId, long botGeneration, UUID actionId) {
      static ScriptedActionDriver.ActionKey from(ActionEnvelope var0) {
         return new ScriptedActionDriver.ActionKey(var0.botId(), var0.botGeneration(), var0.actionId());
      }
   }

   private static record ActiveStep(ScriptedActionDriver.IndexedStep indexed, long startedTick) {
   }

   private static record IndexedStep(int index, ScriptedActionDriver.Step step) {
   }

   public static enum ScriptResult {
      SUCCEED,
      FAIL,
      STALE;
   }

   public static record Step(
      ActionKind kind, int runTicks, ScriptedActionDriver.ScriptResult result, ActionFailureCode failureCode, List<ActionEvidence> evidence, String safeSummary
   ) {
      public Step(
         ActionKind kind,
         int runTicks,
         ScriptedActionDriver.ScriptResult result,
         ActionFailureCode failureCode,
         List<ActionEvidence> evidence,
         String safeSummary
      ) {
         Objects.requireNonNull(kind, "kind");
         if (runTicks >= 0 && runTicks <= 6000) {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(evidence, "evidence");
            if (evidence.size() > 14) {
               throw new IllegalArgumentException("script evidence exceeds 14 items");
            } else {
               evidence = List.copyOf(evidence);
               safeSummary = validateSummary(safeSummary);
               switch (result) {
                  case SUCCEED:
                     if (failureCode != ActionFailureCode.NONE) {
                        throw new IllegalArgumentException("successful steps must use failureCode NONE");
                     }
                     break;
                  case FAIL:
                     if (failureCode == ActionFailureCode.NONE
                        || failureCode == ActionFailureCode.CANCELLED
                        || failureCode == ActionFailureCode.PREEMPTED
                        || failureCode == ActionFailureCode.STALE_GENERATION) {
                        throw new IllegalArgumentException("failed steps require a non-control failure code");
                     }
                     break;
                  case STALE:
                     if (failureCode != ActionFailureCode.STALE_GENERATION) {
                        throw new IllegalArgumentException("stale steps must use STALE_GENERATION");
                     }
               }

               this.kind = kind;
               this.runTicks = runTicks;
               this.result = result;
               this.failureCode = failureCode;
               this.evidence = evidence;
               this.safeSummary = safeSummary;
            }
         } else {
            throw new IllegalArgumentException("runTicks must be between 0 and 6000");
         }
      }

      public static ScriptedActionDriver.Step succeed(ActionKind var0, int var1) {
         return new ScriptedActionDriver.Step(
            var0, var1, ScriptedActionDriver.ScriptResult.SUCCEED, ActionFailureCode.NONE, List.of(), "Scripted action succeeded"
         );
      }

      public static ScriptedActionDriver.Step fail(ActionKind var0, int var1, ActionFailureCode var2, String var3) {
         return new ScriptedActionDriver.Step(var0, var1, ScriptedActionDriver.ScriptResult.FAIL, var2, List.of(), var3);
      }

      public static ScriptedActionDriver.Step stale(ActionKind var0, int var1) {
         return new ScriptedActionDriver.Step(
            var0, var1, ScriptedActionDriver.ScriptResult.STALE, ActionFailureCode.STALE_GENERATION, List.of(), "Scripted action became stale"
         );
      }

      private static String validateSummary(String var0) {
         Objects.requireNonNull(var0, "safeSummary");
         if (var0.length() > 256) {
            throw new IllegalArgumentException("safeSummary exceeds 256 characters");
         } else if (!var0.equals(var0.strip())) {
            throw new IllegalArgumentException("safeSummary must not have surrounding whitespace");
         } else if (var0.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("safeSummary must not contain control characters");
         } else {
            return var0;
         }
      }
   }
}
