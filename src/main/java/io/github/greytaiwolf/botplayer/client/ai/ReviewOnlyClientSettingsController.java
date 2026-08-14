package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies the persisted local R1 opt-in to a physical-client-only runtime.
 *
 * <p>The controller has no packet, server, credential, or world dependency. Therefore an S2C
 * dispatch can never enable a Provider: only a local persisted {@code enabled=true} setting can
 * ask the supplied fixed factory to be constructed.
 */
public final class ReviewOnlyClientSettingsController {
    private static final AiModelCapabilities FIXED_REVIEW_MODEL = new AiModelCapabilities(
            AiReviewOnlyContract.MODEL,
            8_192L,
            AiReviewOnlyContract.MAXIMUM_OUTPUT_TOKENS,
            Set.of(AiCapability.CHAT, AiCapability.TOOL_CALLS));

    private final ReviewOnlyClientSettingsStore settingsStore;
    private final Supplier<? extends ReviewOnlyClientAiProviderFactory> factorySupplier;
    private final Runtime runtime;

    public ReviewOnlyClientSettingsController(
            ReviewOnlyClientSettingsStore settingsStore,
            Supplier<? extends ReviewOnlyClientAiProviderFactory> factorySupplier,
            Runtime runtime) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        this.factorySupplier = Objects.requireNonNull(factorySupplier, "factorySupplier");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Reads and reapplies local configuration; every reload retires prior local sessions first. */
    public synchronized ReviewOnlyClientSettings reload()
            throws ReviewOnlyClientSettingsException {
        try {
            ReviewOnlyClientSettings settings = settingsStore.load();
            return apply(settings);
        } catch (ReviewOnlyClientSettingsException exception) {
            /* A malformed/local-unknown file must retire an already-enabled Provider as well. */
            disableQuietly();
            throw exception;
        } catch (RuntimeException exception) {
            disableQuietly();
            throw exception;
        }
    }

    /** Persists and applies the one local opt-in bit. */
    public synchronized ReviewOnlyClientSettings setEnabled(boolean enabled)
            throws ReviewOnlyClientSettingsException {
        try {
            return apply(settingsStore.setEnabled(enabled));
        } catch (ReviewOnlyClientSettingsException | RuntimeException exception) {
            disableQuietly();
            throw exception;
        }
    }

    /**
     * Creates the sole production factory used by the physical client.
     *
     * <p>Its model, capabilities, output ceiling, Provider origin and one tool are all code
     * constants. The factory constructor creates no credential-bound Provider and performs no
     * HTTP; a later canonical dispatch still has to pass owner/binding/TTL checks.
     */
    public static ReviewOnlyDeepSeekClientAiProviderFactory createFixedFactory() {
        return new ReviewOnlyDeepSeekClientAiProviderFactory(FIXED_REVIEW_MODEL);
    }

    /** Returns the immutable, code-defined model capability policy used by {@link #createFixedFactory()}. */
    public static AiModelCapabilities fixedModelCapabilities() {
        return FIXED_REVIEW_MODEL;
    }

    private ReviewOnlyClientSettings apply(ReviewOnlyClientSettings settings) {
        ReviewOnlyClientSettings checked = Objects.requireNonNull(settings, "settings");
        if (!checked.enabled()) {
            runtime.disable();
            return checked;
        }
        try {
            ReviewOnlyClientAiProviderFactory factory = Objects.requireNonNull(
                    factorySupplier.get(), "review-only factory");
            runtime.install(factory);
            return checked;
        } catch (RuntimeException exception) {
            disableQuietly();
            throw exception;
        }
    }

    private void disableQuietly() {
        try {
            runtime.disable();
        } catch (RuntimeException ignored) {
            // The configuration path remains fail-closed even if cleanup diagnostics fail.
        }
    }

    /** Physical-client runtime callbacks; neither callback carries server-selected configuration. */
    public interface Runtime {
        void install(ReviewOnlyClientAiProviderFactory factory);

        void disable();
    }
}
