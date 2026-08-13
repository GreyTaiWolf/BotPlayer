package io.github.greytaiwolf.botplayer.ai.transport;

/** Shared, wire-visible bounds for the transient client-sponsored proposal session. */
public final class AiProposalSessionLimits {
    /** A client-sponsored request may remain correlated for at most sixty seconds at 20 TPS. */
    public static final int MAX_REQUEST_TTL_TICKS = 20 * 60;

    private AiProposalSessionLimits() {
        throw new AssertionError("No instances");
    }
}
