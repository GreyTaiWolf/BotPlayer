package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure orchestration boundary for closing one skill generation after vanilla
 * death consumed the player inventory.
 *
 * <p>The boundary deliberately owns only ordering and retry semantics. Game
 * objects stay behind {@link Operations}, which keeps the contract executable
 * on the ordinary unit-test classpath.
 */
final class VanillaDeathSkillClosure {
    private VanillaDeathSkillClosure() {}

    static <R> boolean close(
            UUID botId,
            long generation,
            boolean actionCleanupConfirmed,
            Operations<R> operations) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(operations, "operations");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        if (!actionCleanupConfirmed) {
            return false;
        }

        Optional<R> active = Objects.requireNonNull(
                operations.activeRun(botId),
                "activeRun");
        if (active.isEmpty()
                || operations.generation(active.orElseThrow())
                        != generation) {
            operations.closeGeneration(botId, generation);
            return true;
        }

        R run = active.orElseThrow();
        if (operations.hasOpenLayoutLease(run)) {
            try {
                if (!operations.consumeLayout(
                        botId, generation, run)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
        }
        operations.finishActiveRun(run);
        operations.closeGeneration(botId, generation);
        return true;
    }

    interface Operations<R> {
        Optional<R> activeRun(UUID botId);

        long generation(R run);

        boolean hasOpenLayoutLease(R run);

        boolean consumeLayout(
                UUID botId, long generation, R run);

        void finishActiveRun(R run);

        void closeGeneration(UUID botId, long generation);
    }
}
