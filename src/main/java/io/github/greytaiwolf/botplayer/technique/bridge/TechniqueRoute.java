package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import java.util.UUID;

/**
 * Package-private route from the single lifecycle coordinator to one approved Technique port.
 *
 * <p>It is deliberately an abstract class rather than a public interface: child submission and
 * cancellation are coordinator-only internals, not a generic Technique-to-world API. Concrete
 * routes retain their own narrow Action/Navigation bindings and must never accept raw world input
 * through this type.
 */
abstract class TechniqueRoute {
    abstract PlayerTechnique technique();

    abstract void observeTick(long currentTick);

    abstract TechniqueChildDispatcher.Submission submitChild(
            TechniqueChildTicket ticket, TechniqueRunView run);

    /**
     * Safe-by-default Action-kind allowlist for a child which requests a
     * {@link TechniqueActionPermit}.  Existing routes that do not opt in
     * cannot issue a permit merely because they hold a child ticket.
     */
    boolean allowsActionKind(TechniqueChildTicket ticket, ActionKind kind) {
        return false;
    }

    abstract void cancelChild(TechniqueChildTicket ticket,
            TechniqueCancelReason reason);

    abstract void drainCompletedChildren(long currentTick,
            TechniqueLifecycleCoordinator.SignalSink signals);

    abstract void onGenerationClosing(UUID botId, long botGeneration,
            long currentTick);

    abstract void closeIngress();

    abstract boolean isRouteGenerationSafe(UUID botId, long botGeneration);

    abstract void verifyRun(TechniqueRunView run);

    abstract void reapTerminalRun(UUID techniqueRunId);
}
