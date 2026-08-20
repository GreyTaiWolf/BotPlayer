package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientSponsoredTerminalObservation;

/**
 * Non-blocking client-local observation of one terminal AI request session.
 *
 * <p>The value contains only a safe receipt and terminal classification. It never carries a
 * nonce, owner, prompt, schema, model response, throwable, credential, or cancellation handle.
 * It must remain non-blocking and is intended for a future bounded handoff, not for session
 * ownership or C2S transport.
 */
@FunctionalInterface
public interface ClientAiRequestTerminalObserver {
    void observe(AiClientSponsoredTerminalObservation observation);
}
