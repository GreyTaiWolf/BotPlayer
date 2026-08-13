package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;

/**
 * Wire-level reply shape for the fixed {@link AiRequestPurpose#REVIEW_ONLY_V1} purpose.
 *
 * <p>This lives next to the server-owned proposal correlation rather than in the review feature
 * package so {@link AiProposalSessionGate} can reject an invalid response before it invokes the
 * generic tool codec or Firewall. It contains no prompt, context, credential, or execution
 * semantics: it only recognizes the one safe transport acknowledgement.
 */
public final class AiReviewOnlyProposalShape {
    /** The sole fixed tool name allowed by the R1 proposal wire contract. */
    public static final String TOOL_NAME = "botplayer_review_snapshot";

    private AiReviewOnlyProposalShape() {}

    /**
     * Returns whether an untrusted C2S payload has the exact R1 acknowledgement shape.
     *
     * <p>Whitespace is deliberately not normalized. In particular, only the literal JSON bytes
     * {@code {}} are accepted for the zero-argument tool, so a later parser cannot broaden this
     * review-only transport boundary.
     */
    public static boolean matches(AiProposalPayload payload) {
        try {
            if (payload == null
                    || !payload.outputText().isEmpty()
                    || payload.toolCalls().size() != 1) {
                return false;
            }
            var toolCall = payload.toolCalls().get(0);
            return TOOL_NAME.equals(toolCall.name())
                    && "{}".equals(toolCall.argumentsJson());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
