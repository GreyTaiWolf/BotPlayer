package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldModelServiceTest {
    private static final UUID SESSION_ID = new UUID(0L, 100L);
    private static final FactKey BLOCK_KEY =
            new FactKey("block", "minecraft:overworld", "1,64,1");
    private static final RevisionScope BLOCK_SCOPE = new RevisionScope(
            "minecraft:overworld", RevisionKind.BLOCK, "1,64,1");

    @Test
    void refreshesEqualFactsAndSupersedesConflictingValues() {
        WorldModelService model = new WorldModelService(8);
        WorldFact first = model.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                1L,
                1L,
                0.60F,
                FactStatus.ACTIVE));
        WorldFact refreshed = model.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                2L,
                2L,
                0.90F,
                FactStatus.ACTIVE));

        Assertions.assertEquals(first.factId(), refreshed.factId());
        Assertions.assertEquals(1L, refreshed.firstObservedTick());
        Assertions.assertEquals(2L, refreshed.lastConfirmedTick());
        Assertions.assertEquals(0.90F, refreshed.confidence());
        Assertions.assertEquals(2, refreshed.evidence().size());
        Assertions.assertEquals(refreshed, model.current(BLOCK_KEY).orElseThrow());

        WorldFact replacement = model.observe(draft(
                FactValue.of("block", "minecraft:dirt"),
                3L,
                3L,
                1.0F,
                FactStatus.ACTIVE));
        WorldFact superseded = model.recent(8).stream()
                .filter(fact -> fact.factId().equals(first.factId()))
                .findFirst()
                .orElseThrow();

        Assertions.assertEquals(FactStatus.SUPERSEDED, superseded.status());
        Assertions.assertEquals(replacement, model.current(BLOCK_KEY).orElseThrow());
        Assertions.assertNotEquals(first.factId(), replacement.factId());
    }

    @Test
    void scopeChangesDistinguishVisibleAndHiddenStaleness() {
        WorldModelService visibleModel = new WorldModelService(4);
        visibleModel.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                1L,
                1L,
                1.0F,
                FactStatus.ACTIVE));

        Assertions.assertEquals(
                0,
                visibleModel.invalidateScope(
                        BLOCK_SCOPE, new RevisionStamp(2L, 2L, 1L), 2L, false));
        Assertions.assertEquals(
                1,
                visibleModel.invalidateScope(
                        BLOCK_SCOPE, new RevisionStamp(3L, 3L, 2L), 3L, false));
        Assertions.assertTrue(visibleModel.current(BLOCK_KEY).isEmpty());
        Assertions.assertEquals(
                FactStatus.STALE, visibleModel.recent(1).getFirst().status());

        WorldModelService hiddenModel = new WorldModelService(4);
        hiddenModel.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                1L,
                1L,
                1.0F,
                FactStatus.ACTIVE));
        Assertions.assertEquals(
                1,
                hiddenModel.invalidateScope(
                        BLOCK_SCOPE, new RevisionStamp(2L, 2L, 2L), 2L, true));
        Assertions.assertEquals(
                FactStatus.STALE_UNKNOWN,
                hiddenModel.recent(1).getFirst().status());
    }

    @Test
    void ttlExpiresOnlyAfterItsFullValidityWindow() {
        WorldModelService model = new WorldModelService(4);
        WorldFactDraft draft = new WorldFactDraft(
                BLOCK_KEY,
                FactValue.of("block", "minecraft:stone"),
                BLOCK_SCOPE,
                new RevisionStamp(1L, 1L, 1L),
                10L,
                1.0F,
                FactSource.VISUAL,
                List.of(evidence(10L)),
                FactStatus.ACTIVE,
                new InvalidationRule(5L, false));
        model.observe(draft);

        Assertions.assertEquals(0, model.expire(15L));
        Assertions.assertTrue(model.current(BLOCK_KEY).isPresent());
        Assertions.assertEquals(1, model.expire(16L));
        Assertions.assertTrue(model.current(BLOCK_KEY).isEmpty());
        Assertions.assertEquals(
                FactStatus.STALE_UNKNOWN, model.recent(1).getFirst().status());
    }

    @Test
    void anUnverifiedRepeatCannotDowngradeAnActiveFact() {
        WorldModelService model = new WorldModelService(4);
        WorldFact active = model.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                1L,
                1L,
                0.80F,
                FactStatus.ACTIVE));

        WorldFact repeated = model.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                2L,
                2L,
                0.30F,
                FactStatus.UNVERIFIED));

        Assertions.assertEquals(active.factId(), repeated.factId());
        Assertions.assertEquals(FactStatus.ACTIVE, repeated.status());
        Assertions.assertEquals(repeated, model.current(BLOCK_KEY).orElseThrow());
        Assertions.assertEquals(0.80F, repeated.confidence());
    }

    @Test
    void capacityEvictionRemovesTheMatchingCurrentIndex() {
        WorldModelService model = new WorldModelService(1);
        model.observe(draft(
                FactValue.of("block", "minecraft:stone"),
                1L,
                1L,
                1.0F,
                FactStatus.ACTIVE));
        FactKey secondKey =
                new FactKey("block", "minecraft:overworld", "2,64,2");
        RevisionScope secondScope = new RevisionScope(
                "minecraft:overworld", RevisionKind.BLOCK, "2,64,2");
        WorldFact second = model.observe(new WorldFactDraft(
                secondKey,
                new FactValue(Map.of("block", "minecraft:dirt")),
                secondScope,
                new RevisionStamp(2L, 2L, 1L),
                2L,
                1.0F,
                FactSource.VISUAL,
                List.of(evidence(2L)),
                FactStatus.ACTIVE,
                new InvalidationRule(0L, true)));

        Assertions.assertTrue(model.current(BLOCK_KEY).isEmpty());
        Assertions.assertEquals(second, model.current(secondKey).orElseThrow());
        Assertions.assertEquals(1L, model.droppedFacts());
        Assertions.assertEquals(List.of(second), model.recent(1));
    }

    @Test
    void equalValueRefreshMovesTheActiveScopeIndex() {
        WorldModelService model = new WorldModelService(4);
        FactKey key =
                new FactKey("entity", "minecraft:overworld", "same-subject");
        RevisionScope oldScope = new RevisionScope(
                "minecraft:overworld", RevisionKind.ENTITY, "old-scope");
        RevisionScope newScope = new RevisionScope(
                "minecraft:overworld", RevisionKind.ENTITY, "new-scope");
        FactValue value = FactValue.of("state", "visible");
        model.observe(new WorldFactDraft(
                key,
                value,
                oldScope,
                new RevisionStamp(1L, 1L, 1L),
                1L,
                1.0F,
                FactSource.VISUAL,
                List.of(evidence(1L)),
                FactStatus.ACTIVE,
                new InvalidationRule(20L, true)));
        model.observe(new WorldFactDraft(
                key,
                value,
                newScope,
                new RevisionStamp(2L, 2L, 1L),
                2L,
                1.0F,
                FactSource.VISUAL,
                List.of(evidence(2L)),
                FactStatus.ACTIVE,
                new InvalidationRule(20L, true)));

        Assertions.assertEquals(
                0,
                model.invalidateScope(
                        oldScope,
                        new RevisionStamp(3L, 3L, 2L),
                        3L,
                        false));
        Assertions.assertTrue(model.current(key).isPresent());
        Assertions.assertEquals(
                1,
                model.invalidateScope(
                        newScope,
                        new RevisionStamp(3L, 3L, 2L),
                        3L,
                        false));
        Assertions.assertTrue(model.current(key).isEmpty());
    }

    @Test
    void expirationWorkIsBoundedAndContinuesOnTheNextCall() {
        int factCount = WorldModelService.MAX_EXPIRATIONS_PER_CALL + 44;
        WorldModelService model = new WorldModelService(factCount);
        for (int index = 0; index < factCount; index++) {
            String target = index + ",64,0";
            model.observe(new WorldFactDraft(
                    new FactKey("block", "minecraft:overworld", target),
                    FactValue.of("block", "minecraft:stone"),
                    new RevisionScope(
                            "minecraft:overworld",
                            RevisionKind.BLOCK,
                            target),
                    new RevisionStamp(1L, 1L, 1L),
                    0L,
                    1.0F,
                    FactSource.VISUAL,
                    List.of(evidence(index + 1L)),
                    FactStatus.ACTIVE,
                    new InvalidationRule(1L, false)));
        }

        Assertions.assertEquals(
                WorldModelService.MAX_EXPIRATIONS_PER_CALL,
                model.expire(2L));
        Assertions.assertEquals(
                44,
                model.recent(factCount).stream()
                        .filter(fact -> fact.status() == FactStatus.ACTIVE)
                        .count());
        Assertions.assertEquals(44, model.expire(2L));
        Assertions.assertTrue(model.recent(factCount).stream()
                .noneMatch(fact -> fact.status() == FactStatus.ACTIVE));
    }

    private static WorldFactDraft draft(
            FactValue value,
            long tick,
            long targetRevision,
            float confidence,
            FactStatus status) {
        return new WorldFactDraft(
                BLOCK_KEY,
                value,
                BLOCK_SCOPE,
                new RevisionStamp(targetRevision, targetRevision, targetRevision),
                tick,
                confidence,
                FactSource.VISUAL,
                List.of(evidence(tick)),
                status,
                new InvalidationRule(20L, true));
    }

    private static EvidenceRef evidence(long sequence) {
        return new EvidenceRef(
                "perceived_event", SESSION_ID, sequence, "block_changed");
    }
}
