package io.github.greytaiwolf.botplayer.action;

public enum ActionCleanupReason {
   SUCCEEDED,
   FAILED,
   CANCELLED,
   PREEMPTED,
   STALE,
   DEADLINE_EXCEEDED,
   MAX_TICKS_EXCEEDED,
   /**
    * Vanilla death already consumed the body's mutable inventory/menu state.
    * Backends must forget in-memory action state without dispatching any
    * compensating player interaction.
    */
   VANILLA_DEATH_CONSUMED,
   RUNTIME_SHUTDOWN;
}
