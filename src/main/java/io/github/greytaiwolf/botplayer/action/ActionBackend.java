package io.github.greytaiwolf.botplayer.action;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ActionBackend {
   ActionBackend.BackendResult validate(ActionEnvelope var1, long var2);

   ActionBackend.BackendResult start(ActionEnvelope var1, long var2);

   ActionBackend.BackendResult tick(ActionEnvelope var1, long var2, long var4);

   ActionBackend.BackendResult verify(ActionEnvelope var1, long var2);

   void cleanup(ActionEnvelope var1, ActionCleanupReason var2, long var3);

   /**
    * 执行一次带精确身份的物理清理步骤。
    *
    * <p>旧后端继续使用同步 cleanup；需要跨 Tick 的后端覆写本方法并返回 PENDING。
    */
   default ActionCleanupReceipt cleanupStep(
      ActionEnvelope envelope,
      ActionCleanupRequest request
   ) {
      Objects.requireNonNull(envelope, "envelope");
      Objects.requireNonNull(request, "request");
      if (!request.matches(envelope)) {
         throw new IllegalArgumentException(
            "cleanup request does not match the action envelope"
         );
      }
      cleanup(envelope, request.reason(), request.currentTick());
      return ActionCleanupReceipt.complete(
         request, 0L, "Legacy cleanup completed"
      );
   }

   boolean forceSafeReset(UUID var1, long var2, long var4);

   public static record BackendResult(
      UUID actionId,
      UUID botId,
      long botGeneration,
      ActionBackend.BackendStep step,
      ActionFailureCode failureCode,
      List<ActionEvidence> evidence,
      String safeSummary
   ) {
      public BackendResult(
         UUID actionId,
         UUID botId,
         long botGeneration,
         ActionBackend.BackendStep step,
         ActionFailureCode failureCode,
         List<ActionEvidence> evidence,
         String safeSummary
      ) {
         ActionEnvelope.requireNonZero(actionId, "actionId");
         ActionEnvelope.requireNonZero(botId, "botId");
         if (botGeneration <= 0L) {
            throw new IllegalArgumentException("botGeneration must be positive");
         } else {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(evidence, "evidence");
            if (evidence.size() > 16) {
               throw new IllegalArgumentException("evidence exceeds 16 items");
            } else {
               evidence = List.copyOf(evidence);
               safeSummary = validateSummary(safeSummary);
               requireCoherentStep(step, failureCode, evidence);
               this.actionId = actionId;
               this.botId = botId;
               this.botGeneration = botGeneration;
               this.step = step;
               this.failureCode = failureCode;
               this.evidence = evidence;
               this.safeSummary = safeSummary;
            }
         }
      }

      public static ActionBackend.BackendResult accepted(ActionEnvelope var0) {
         return control(var0, ActionBackend.BackendStep.ACCEPTED);
      }

      public static ActionBackend.BackendResult running(ActionEnvelope var0) {
         return control(var0, ActionBackend.BackendStep.RUNNING);
      }

      public static ActionBackend.BackendResult readyToVerify(ActionEnvelope var0) {
         return control(var0, ActionBackend.BackendStep.READY_TO_VERIFY);
      }

      public static ActionBackend.BackendResult succeeded(ActionEnvelope var0, List<ActionEvidence> var1, String var2) {
         return terminal(var0, ActionBackend.BackendStep.SUCCEEDED, ActionFailureCode.NONE, var1, var2);
      }

      public static ActionBackend.BackendResult failed(ActionEnvelope var0, ActionFailureCode var1, List<ActionEvidence> var2, String var3) {
         return terminal(var0, ActionBackend.BackendStep.FAILED, var1, var2, var3);
      }

      public static ActionBackend.BackendResult stale(ActionEnvelope var0, String var1) {
         return terminal(var0, ActionBackend.BackendStep.STALE, ActionFailureCode.STALE_GENERATION, List.of(), var1);
      }

      private static ActionBackend.BackendResult control(ActionEnvelope var0, ActionBackend.BackendStep var1) {
         Objects.requireNonNull(var0, "envelope");
         return new ActionBackend.BackendResult(var0.actionId(), var0.botId(), var0.botGeneration(), var1, ActionFailureCode.NONE, List.of(), "");
      }

      private static ActionBackend.BackendResult terminal(
         ActionEnvelope var0, ActionBackend.BackendStep var1, ActionFailureCode var2, List<ActionEvidence> var3, String var4
      ) {
         Objects.requireNonNull(var0, "envelope");
         return new ActionBackend.BackendResult(var0.actionId(), var0.botId(), var0.botGeneration(), var1, var2, var3, var4);
      }

      private static void requireCoherentStep(ActionBackend.BackendStep var0, ActionFailureCode var1, List<ActionEvidence> var2) {
         switch (var0) {
            case ACCEPTED:
            case RUNNING:
            case READY_TO_VERIFY:
               if (var1 != ActionFailureCode.NONE || !var2.isEmpty()) {
                  throw new IllegalArgumentException(var0 + " cannot contain failure or terminal evidence");
               }
               break;
            case SUCCEEDED:
               if (var1 != ActionFailureCode.NONE) {
                  throw new IllegalArgumentException("SUCCEEDED must use failureCode NONE");
               }
               break;
            case FAILED:
               if (var1 == ActionFailureCode.NONE
                  || var1 == ActionFailureCode.CANCELLED
                  || var1 == ActionFailureCode.PREEMPTED
                  || var1 == ActionFailureCode.STALE_GENERATION) {
                  throw new IllegalArgumentException("FAILED requires a non-control failure code");
               }
               break;
            case STALE:
               if (var1 != ActionFailureCode.STALE_GENERATION) {
                  throw new IllegalArgumentException("STALE must use failureCode STALE_GENERATION");
               }
         }
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

   public static enum BackendStep {
      ACCEPTED,
      RUNNING,
      READY_TO_VERIFY,
      SUCCEEDED,
      FAILED,
      STALE;
   }
}
