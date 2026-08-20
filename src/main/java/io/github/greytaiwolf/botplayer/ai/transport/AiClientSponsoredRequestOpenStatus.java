package io.github.greytaiwolf.botplayer.ai.transport;

/** Stable, non-secret admission outcomes for the server-thread request coordinator. */
public enum AiClientSponsoredRequestOpenStatus {
    OPENED,
    BOT_BUSY,
    CAPACITY_EXHAUSTED
}
