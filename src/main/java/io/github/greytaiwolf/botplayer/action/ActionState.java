package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;

public enum ActionState {
   QUEUED,
   VALIDATING,
   RUNNING,
   VERIFYING,
   SUCCEEDED,
   FAILED,
   CANCELLED,
   PREEMPTED,
   STALE;

   public boolean isTerminal() {
      return switch (this) {
         case QUEUED, VALIDATING, RUNNING, VERIFYING -> false;
         case SUCCEEDED, FAILED, CANCELLED, PREEMPTED, STALE -> true;
      };
   }

   public boolean canTransitionTo(ActionState var1) {
      Objects.requireNonNull(var1, "next");

      return switch (this) {
         case QUEUED -> var1 == VALIDATING || var1 == CANCELLED || var1 == PREEMPTED;
         case VALIDATING -> var1 == RUNNING || var1 == FAILED || var1 == CANCELLED || var1 == PREEMPTED || var1 == STALE;
         case RUNNING -> var1 == VERIFYING || var1 == FAILED || var1 == CANCELLED || var1 == PREEMPTED || var1 == STALE;
         case VERIFYING -> var1 == SUCCEEDED || var1 == FAILED || var1 == CANCELLED || var1 == PREEMPTED || var1 == STALE;
         case SUCCEEDED, FAILED, CANCELLED, PREEMPTED, STALE -> false;
      };
   }

   public void requireTransitionTo(ActionState var1) {
      if (!this.canTransitionTo(var1)) {
         throw new IllegalStateException("Illegal action transition: " + this + " -> " + var1);
      }
   }
}
