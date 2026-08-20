package io.github.greytaiwolf.botplayer.ai.transport;

/**
 * Safe terminal classifications that a future client or Scheduler lane may report to the server
 * thread. No status means a proposal was accepted, a model response was trusted, or a world action
 * was performed.
 */
public enum AiClientSponsoredTerminalStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED
}
