package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;

/**
 * Explicit physical-client opt-in for the P6-R1 review-only request shape.
 *
 * <p>This is intentionally separate from the older general provider factory. A caller must
 * reject every non-review dispatch before a credential-bound provider can be constructed.
 */
public interface ReviewOnlyClientAiProviderFactory extends ClientAiProviderFactory {
    /** Returns whether this local policy admits the complete fixed review dispatch. */
    boolean accepts(AiClientRequestDispatch dispatch);
}
