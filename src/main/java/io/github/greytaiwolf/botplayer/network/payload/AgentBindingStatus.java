package io.github.greytaiwolf.botplayer.network.payload;

/**
 * Server-authoritative outcome of a client-agent binding request.
 */
public enum AgentBindingStatus {
    BOUND,
    REPLACED,
    UNBOUND,
    ALREADY_UNBOUND,
    STALE_AGENT_ID,
    AGENT_ID_IN_USE,
    BOT_NOT_ACTIVE,
    NOT_OWNER,
    INTERNAL_ERROR
}
