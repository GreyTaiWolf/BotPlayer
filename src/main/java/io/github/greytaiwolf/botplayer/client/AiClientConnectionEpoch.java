package io.github.greytaiwolf.botplayer.client;

/**
 * Immutable physical-client connection generation used to reject queued
 * network work from an old connection.
 *
 * <p>Payload callbacks capture {@link #ingressEpoch()} before they hand work
 * to the Minecraft thread. The client bootstrap accepts that work only through
 * {@link #acceptsIngress(long)}. No server id, owner, credential, request or
 * packet content is stored here.
 */
final class AiClientConnectionEpoch {
    static final long NO_ACTIVE_INGRESS_EPOCH = -1L;

    private final long epoch;
    private final boolean active;

    private AiClientConnectionEpoch(long epoch, boolean active) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("connection epoch must not be negative");
        }
        this.epoch = epoch;
        this.active = active;
    }

    static AiClientConnectionEpoch initial() {
        return new AiClientConnectionEpoch(0L, false);
    }

    /** Begins a fresh physical connection and invalidates every prior ingress epoch. */
    AiClientConnectionEpoch nextConnected() {
        return new AiClientConnectionEpoch(nextEpoch(epoch), true);
    }

    /** Ends the current connection and invalidates every queued ingress callback. */
    AiClientConnectionEpoch nextDisconnected() {
        return new AiClientConnectionEpoch(nextEpoch(epoch), false);
    }

    /** Reconfiguration keeps connection liveness but invalidates old provider callbacks. */
    AiClientConnectionEpoch nextForLocalFactoryChange() {
        return new AiClientConnectionEpoch(nextEpoch(epoch), active);
    }

    long ingressEpoch() {
        return active ? epoch : NO_ACTIVE_INGRESS_EPOCH;
    }

    boolean acceptsIngress(long expectedEpoch) {
        return active && epoch == expectedEpoch;
    }

    boolean active() {
        return active;
    }

    long epoch() {
        return epoch;
    }

    private static long nextEpoch(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }
}
