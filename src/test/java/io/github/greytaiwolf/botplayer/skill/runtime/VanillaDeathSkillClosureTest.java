package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VanillaDeathSkillClosureTest {
    private static final UUID BOT = new UUID(7L, 1L);

    @Test
    void retriesTheExactLeaseAfterTheFirstConsumeThrows() {
        FakeOperations operations = new FakeOperations(11L, true);
        operations.consumeFailuresRemaining = 1;

        Assertions.assertFalse(close(11L, operations));
        Assertions.assertTrue(operations.active != null);

        Assertions.assertTrue(close(11L, operations));
        Assertions.assertEquals(2, operations.consumeCalls);
        Assertions.assertEquals(List.of(11L), operations.closed);
        Assertions.assertTrue(operations.active == null);
    }

    @Test
    void retryAfterCloseGenerationThrowsDoesNotConsumeTwice() {
        FakeOperations operations = new FakeOperations(12L, true);
        operations.closeFailuresRemaining = 1;

        Assertions.assertThrows(
                ExpectedCloseFailure.class,
                () -> close(12L, operations));
        Assertions.assertEquals(1, operations.consumeCalls);
        Assertions.assertTrue(operations.active == null);

        Assertions.assertTrue(close(12L, operations));
        Assertions.assertEquals(1, operations.consumeCalls);
        Assertions.assertEquals(List.of(12L, 12L), operations.closed);
    }

    @Test
    void absentRunIsAlreadySafe() {
        FakeOperations operations = new FakeOperations(null, false);

        Assertions.assertTrue(close(13L, operations));
        Assertions.assertEquals(List.of(13L), operations.closed);
        Assertions.assertEquals(0, operations.consumeCalls);
    }

    @Test
    void closingAnOldAbsentGenerationDoesNotTouchANewerRun() {
        FakeOperations operations = new FakeOperations(15L, false);
        FakeRun newer = operations.active;

        Assertions.assertTrue(close(14L, operations));
        Assertions.assertSame(newer, operations.active);
        Assertions.assertFalse(newer.finished);
        Assertions.assertEquals(List.of(14L), operations.closed);
    }

    @Test
    void unconfirmedActionCleanupHasNoSideEffects() {
        FakeOperations operations = new FakeOperations(16L, true);

        Assertions.assertFalse(
                VanillaDeathSkillClosure.close(
                        BOT, 16L, false, operations));

        Assertions.assertTrue(operations.active != null);
        Assertions.assertEquals(0, operations.consumeCalls);
        Assertions.assertTrue(operations.closed.isEmpty());
    }

    private static boolean close(
            long generation, FakeOperations operations) {
        return VanillaDeathSkillClosure.close(
                BOT, generation, true, operations);
    }

    private static final class FakeOperations
            implements VanillaDeathSkillClosure
                    .Operations<FakeRun> {
        private final List<Long> closed = new ArrayList<>();
        private FakeRun active;
        private int consumeCalls;
        private int consumeFailuresRemaining;
        private int closeFailuresRemaining;

        private FakeOperations(
                Long generation, boolean layoutLeaseOpen) {
            active = generation == null
                    ? null
                    : new FakeRun(
                            generation, layoutLeaseOpen);
        }

        @Override
        public Optional<FakeRun> activeRun(UUID botId) {
            Assertions.assertEquals(BOT, botId);
            return Optional.ofNullable(active);
        }

        @Override
        public long generation(FakeRun run) {
            return run.generation;
        }

        @Override
        public boolean hasOpenLayoutLease(FakeRun run) {
            return run.layoutLeaseOpen;
        }

        @Override
        public boolean consumeLayout(
                UUID botId, long generation, FakeRun run) {
            Assertions.assertEquals(BOT, botId);
            Assertions.assertSame(active, run);
            Assertions.assertEquals(run.generation, generation);
            consumeCalls++;
            if (consumeFailuresRemaining > 0) {
                consumeFailuresRemaining--;
                throw new ExpectedConsumeFailure();
            }
            run.layoutLeaseOpen = false;
            return true;
        }

        @Override
        public void finishActiveRun(FakeRun run) {
            Assertions.assertSame(active, run);
            run.finished = true;
            active = null;
        }

        @Override
        public void closeGeneration(
                UUID botId, long generation) {
            Assertions.assertEquals(BOT, botId);
            closed.add(generation);
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new ExpectedCloseFailure();
            }
        }
    }

    private static final class FakeRun {
        private final long generation;
        private boolean layoutLeaseOpen;
        private boolean finished;

        private FakeRun(
                long generation, boolean layoutLeaseOpen) {
            this.generation = generation;
            this.layoutLeaseOpen = layoutLeaseOpen;
        }
    }

    private static final class ExpectedConsumeFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ExpectedCloseFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
