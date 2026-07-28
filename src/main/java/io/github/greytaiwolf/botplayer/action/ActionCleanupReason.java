package io.github.greytaiwolf.botplayer.action;

public enum ActionCleanupReason {
   SUCCEEDED,
   FAILED,
   CANCELLED,
   PREEMPTED,
   STALE,
   DEADLINE_EXCEEDED,
   MAX_TICKS_EXCEEDED,
   RUNTIME_SHUTDOWN;
}
