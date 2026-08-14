package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import java.util.Objects;

/**
 * A short-lived, server registered player technique.  Implementations receive
 * only immutable scalar context and return directives; they never retain live
 * Minecraft objects or submit commands directly.
 */
public interface PlayerTechnique {
    TechniqueDescriptor descriptor();

    TechniqueDirective start(TechniqueContext context,
            TechniqueParameters parameters);

    TechniqueDirective tick(TechniqueContext context,
            TechniqueRunView run);

    TechniqueDirective verify(TechniqueContext context,
            TechniqueRunView run);

    default void cancelled(TechniqueContext context, TechniqueRunView run,
            TechniqueCancelReason reason) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Notifies an already-cleaning technique that L0 safety has escalated the
     * handoff. This is deliberately separate from {@link #cancelled} because a
     * cancellation callback may have released one-shot local state and must not
     * be replayed. Implementations may use the notification only to stop any
     * remaining non-child local work; child cancellation remains runtime-owned.
     */
    default void safetyPreempted(TechniqueContext context,
            TechniqueRunView run) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(run, "run");
    }
}
