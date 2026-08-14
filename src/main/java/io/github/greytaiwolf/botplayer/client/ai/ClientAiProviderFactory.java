package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;

/**
 * Physical-client-only factory for a locally configured AI Provider.
 *
 * <p>The session controller calls this only after verifying the local credential binding and agent
 * id. Factories may construct a {@link BoundDeepSeekCredentialSupplier} from the supplied store,
 * binding it to {@link AiClientRequestDispatch#agentId()} so a local rebind cannot authorize a
 * later transport request. Factories must never expose a secret through a return value, exception
 * message, log, or payload.
 */
@FunctionalInterface
public interface ClientAiProviderFactory {
    AiProvider create(
            AiClientRequestDispatch dispatch, ClientCredentialStore credentialStore);
}
