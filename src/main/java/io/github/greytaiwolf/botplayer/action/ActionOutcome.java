package io.github.greytaiwolf.botplayer.action;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ActionOutcome(
   UUID actionId, ActionState state, ActionFailureCode failureCode, long startedTick, long finishedTick, List<ActionEvidence> evidence, String safeSummary
) {
   public static final int MAX_SAFE_SUMMARY_LENGTH = 256;
   public static final int MAX_EVIDENCE_ITEMS = 16;

   public ActionOutcome(
      UUID actionId, ActionState state, ActionFailureCode failureCode, long startedTick, long finishedTick, List<ActionEvidence> evidence, String safeSummary
   ) {
      ActionEnvelope.requireNonZero(actionId, "actionId");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(failureCode, "failureCode");
      if (!state.isTerminal()) {
         throw new IllegalArgumentException("ActionOutcome must contain a terminal state");
      } else {
         requireCoherentFailure(state, failureCode);
         if (startedTick < 0L) {
            throw new IllegalArgumentException("startedTick must not be negative");
         } else if (finishedTick < startedTick) {
            throw new IllegalArgumentException("finishedTick must not precede startedTick");
         } else {
            Objects.requireNonNull(evidence, "evidence");
            if (evidence.size() > 16) {
               throw new IllegalArgumentException("evidence exceeds 16 items");
            } else {
               evidence = List.copyOf(evidence);
               safeSummary = validateSummary(safeSummary);
               this.actionId = actionId;
               this.state = state;
               this.failureCode = failureCode;
               this.startedTick = startedTick;
               this.finishedTick = finishedTick;
               this.evidence = evidence;
               this.safeSummary = safeSummary;
            }
         }
      }
   }

   private static void requireCoherentFailure(ActionState var0, ActionFailureCode var1) {
      switch (var0) {
         case SUCCEEDED:
            if (var1 != ActionFailureCode.NONE) {
               throw new IllegalArgumentException("SUCCEEDED outcomes must use failureCode NONE");
            }
            break;
         case CANCELLED:
            requireCode(var1, ActionFailureCode.CANCELLED, var0);
            break;
         case PREEMPTED:
            requireCode(var1, ActionFailureCode.PREEMPTED, var0);
            break;
         case STALE:
            requireCode(var1, ActionFailureCode.STALE_GENERATION, var0);
            break;
         case FAILED:
            if (var1 == ActionFailureCode.NONE
               || var1 == ActionFailureCode.CANCELLED
               || var1 == ActionFailureCode.PREEMPTED
               || var1 == ActionFailureCode.STALE_GENERATION) {
               throw new IllegalArgumentException("FAILED outcomes require a non-control failure code");
            }
            break;
         case QUEUED:
         case VALIDATING:
         case RUNNING:
         case VERIFYING:
            throw new IllegalArgumentException("ActionOutcome must contain a terminal state");
      }
   }

   private static void requireCode(ActionFailureCode var0, ActionFailureCode var1, ActionState var2) {
      if (var0 != var1) {
         throw new IllegalArgumentException(var2 + " outcomes must use failureCode " + var1);
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
