package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Local-only DeepSeek factory for exactly the P6-R1 review acknowledgement.
 *
 * <p>The local installation chooses whether the fixed {@code deepseek-chat} model is allowed, but
 * it cannot add a server-selected endpoint, model, prompt, or tool catalog. The provider sees
 * exactly one zero-argument tool. Credentials remain in the supplied client store and are bound
 * only when a canonical dispatch has already passed local owner/binding checks.
 */
public final class ReviewOnlyDeepSeekClientAiProviderFactory
        implements ReviewOnlyClientAiProviderFactory {
    private static final String REVIEW_TOOL_DESCRIPTION =
            "Acknowledge the fixed read-only BotPlayer snapshot. "
                    + "Call with an empty object only; it never performs an action.";

    private final DeepSeekProviderConfig config;
    private final DeepSeekHttpExecutor executor;
    private final Clock clock;

    /**
     * Installs one locally allowlisted fixed model. The model must be capable of the one shared
     * review tool; arbitrary model names and extra tools are not retained.
     */
    public ReviewOnlyDeepSeekClientAiProviderFactory(
            AiModelCapabilities localModel,
            boolean streamResponses,
            DeepSeekHttpExecutor executor,
            Clock clock) {
        AiModelCapabilities checkedModel = requireReviewModel(localModel, streamResponses);
        this.config = new DeepSeekProviderConfig(
                List.of(checkedModel),
                List.of(new DeepSeekToolDefinition(
                        AiReviewOnlyContract.toolDefinition(), REVIEW_TOOL_DESCRIPTION)),
                streamResponses);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReviewOnlyDeepSeekClientAiProviderFactory(AiModelCapabilities localModel) {
        this(localModel, false, JavaDeepSeekHttpExecutor.defaults(), Clock.systemUTC());
    }

    /**
     * Reads a client-local model allowlist and retains only the fixed R1 model entry. This avoids
     * treating a server dispatch as a request to opt into another local paid model.
     */
    public static ReviewOnlyDeepSeekClientAiProviderFactory fromLocalAllowlist(
            List<AiModelCapabilities> localModels,
            boolean streamResponses,
            DeepSeekHttpExecutor executor,
            Clock clock) {
        Objects.requireNonNull(localModels, "localModels");
        AiModelCapabilities model = localModels.stream()
                .filter(candidate -> AiReviewOnlyContract.MODEL.equals(candidate.model()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "local review model is not allowlisted"));
        return new ReviewOnlyDeepSeekClientAiProviderFactory(
                model, streamResponses, executor, clock);
    }

    @Override
    public boolean accepts(AiClientRequestDispatch dispatch) {
        try {
            return AiReviewOnlyContract.isCanonicalDispatch(dispatch)
                    && DeepSeekProvider.PROVIDER_ID.equals(dispatch.providerId())
                    && config.findModel(AiReviewOnlyContract.MODEL).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public AiProvider create(
            AiClientRequestDispatch dispatch, ClientCredentialStore credentialStore) {
        AiClientRequestDispatch checkedDispatch = Objects.requireNonNull(dispatch, "dispatch");
        ClientCredentialStore checkedStore = Objects.requireNonNull(
                credentialStore, "credentialStore");
        if (!accepts(checkedDispatch)) {
            throw new IllegalArgumentException("local review-only policy rejected dispatch");
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

    private static AiModelCapabilities requireReviewModel(
            AiModelCapabilities model, boolean streamResponses) {
        AiModelCapabilities checked = Objects.requireNonNull(model, "localModel");
        if (!AiReviewOnlyContract.MODEL.equals(checked.model())
                || checked.maximumOutputTokens() < AiReviewOnlyContract.MAXIMUM_OUTPUT_TOKENS
                || !checked.supports(AiCapability.CHAT)
                || !checked.supports(AiCapability.TOOL_CALLS)
                || streamResponses && !checked.supports(AiCapability.STREAMING)) {
            throw new IllegalArgumentException(
                    "local model cannot satisfy the fixed review-only policy");
        }
        return checked;
    }
}
