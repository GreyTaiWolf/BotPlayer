package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.UUID;

/**
 * Safe server-side receipt that one bounded S2C dispatch was handed to the exact online owner.
 *
 * <p>The nonce, prompt, schema, model output, credential and Firewall policy are intentionally not
 * exposed by this receipt. It does not mean that a Provider completed, that a proposal was accepted,
 * or that any Skill/world action was executed.
 */
public record AiRequestDispatchReceipt(
        UUID botId,
        UUID agentId,
        long generation,
        UUID requestId,
        long revision,
        long expiresAtTick,
        AiRequestPurpose purpose) {
    public AiRequestDispatchReceipt {
        requireNonZero(botId, "botId");
        requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        requireNonZero(requestId, "requestId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (expiresAtTick < 0L) {
            throw new IllegalArgumentException("expiresAtTick must not be negative");
        }
        purpose = Objects.requireNonNull(purpose, "purpose");
    }

    /** Compatibility constructor for pre-purpose pure DTO tests; never a client admission. */
    public AiRequestDispatchReceipt(
            UUID botId,
            UUID agentId,
            long generation,
            UUID requestId,
            long revision,
            long expiresAtTick) {
        this(
                botId,
                agentId,
                generation,
                requestId,
                revision,
                expiresAtTick,
                AiRequestPurpose.UNSPECIFIED_V1);
    }

    public static AiRequestDispatchReceipt fromEnvelope(
            AiProposalRequestEnvelope envelope) {
        AiProposalRequestEnvelope checked = Objects.requireNonNull(envelope, "envelope");
        return new AiRequestDispatchReceipt(
                checked.botId(),
                checked.agentId(),
                checked.generation(),
                checked.requestId(),
                checked.revision(),
                checked.expiresAtTick(),
                checked.purpose());
    }

    /** No prompt, nonce, owner id, tool arguments, or credential-derived data is rendered. */
    @Override
    public String toString() {
        return "AiRequestDispatchReceipt[botId=" + botId
                + ", agentId=" + agentId
                + ", generation=" + generation
                + ", requestId=" + requestId
                + ", revision=" + revision
                + ", expiresAtTick=" + expiresAtTick
                + ", purpose=" + purpose + "]";
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
