package io.github.greytaiwolf.botplayer.worldmodel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldRevisionTrackerTest {
    private static final RevisionScope BLOCK_A = new RevisionScope(
            "minecraft:overworld", RevisionKind.BLOCK, "1,64,1");
    private static final RevisionScope BLOCK_B = new RevisionScope(
            "minecraft:overworld", RevisionKind.BLOCK, "2,64,2");
    private static final RevisionScope BLOCK_C = new RevisionScope(
            "minecraft:overworld", RevisionKind.BLOCK, "3,64,3");

    @Test
    void targetRevisionDoesNotMoveBackwardsAfterLruEvictionAndReentry() {
        WorldRevisionTracker tracker = new WorldRevisionTracker(2);
        RevisionStamp first = tracker.advance(BLOCK_A);
        tracker.advance(BLOCK_B);
        tracker.advance(BLOCK_C);

        RevisionStamp whileEvicted = tracker.current(BLOCK_A);
        RevisionStamp reentered = tracker.advance(BLOCK_A);

        Assertions.assertTrue(whileEvicted.target() > first.target());
        Assertions.assertTrue(reentered.target() > first.target());
        Assertions.assertEquals(
                tracker.globalRevision(),
                reentered.global());
    }

    @Test
    void relatedScopesShareOneGlobalAndDimensionEpoch() {
        WorldRevisionTracker tracker = new WorldRevisionTracker(4);
        RevisionScope container = new RevisionScope(
                "minecraft:overworld",
                RevisionKind.CONTAINER,
                BLOCK_A.targetId());

        RevisionStamp block = tracker.advance(BLOCK_A);
        RevisionStamp related =
                tracker.advanceRelated(container, block);

        Assertions.assertEquals(block.global(), related.global());
        Assertions.assertEquals(block.dimension(), related.dimension());
        Assertions.assertEquals(1L, tracker.globalRevision());
        Assertions.assertEquals(block, tracker.current(BLOCK_A));
        Assertions.assertEquals(related, tracker.current(container));
    }

    @Test
    void relatedScopeRejectsAnObsoleteEpoch() {
        WorldRevisionTracker tracker = new WorldRevisionTracker(4);
        RevisionStamp obsolete = tracker.advance(BLOCK_A);
        tracker.advance(BLOCK_B);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tracker.advanceRelated(BLOCK_C, obsolete));
    }
}
