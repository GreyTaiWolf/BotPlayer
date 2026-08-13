package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import java.time.Clock;
import java.util.Objects;

/**
 * Local-only factory that binds a configured DeepSeek transport to one verified credential tuple.
 *
 * <p>The endpoint remains fixed inside {@link DeepSeekProvider}; neither an S2C dispatch nor a
 * server configuration can redirect Authorization to another origin. The caller must install this
 * factory only from trusted physical-client configuration. It deliberately has no default model
 * list: a server request cannot opt a client into an arbitrary model merely by naming one.
 */
public final class DeepSeekClientAiProviderFactory implements ClientAiProviderFactory {
    private final DeepSeekProviderConfig config;
    private final DeepSeekHttpExecutor executor;
    private final Clock clock;

    public DeepSeekClientAiProviderFactory(
            DeepSeekProviderConfig config,
            DeepSeekHttpExecutor executor,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeepSeekClientAiProviderFactory(DeepSeekProviderConfig config) {
        this(config, JavaDeepSeekHttpExecutor.defaults(), Clock.systemUTC());
    }

    @Override
    public AiProvider create(
            AiClientRequestDispatch dispatch, ClientCredentialStore credentialStore) {
        AiClientRequestDispatch checkedDispatch = Objects.requireNonNull(dispatch, "dispatch");
        ClientCredentialStore checkedStore = Objects.requireNonNull(
                credentialStore, "credentialStore");
        if (!DeepSeekProvider.PROVIDER_ID.equals(checkedDispatch.providerId())
                || config.findModel(checkedDispatch.model()).isEmpty()) {
            throw new IllegalArgumentException("local DeepSeek provider policy rejected dispatch");
        }
        return new DeepSeekProvider(
                config,
                new BoundDeepSeekCredentialSupplier(
                        checkedStore,
                        checkedDispatch.serverInstanceId(),
                        checkedDispatch.ownerId(),
                        checkedDispatch.botId(),
                        checkedDispatch.agentId()),
                executor,
                clock);
    }
}
