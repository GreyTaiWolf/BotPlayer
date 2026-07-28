package io.github.greytaiwolf.botplayer.perception.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PerceptionProjectionTest {
    private static final UUID SESSION_ID = new UUID(0L, 1L);
    private static final UUID BOT_ID = new UUID(0L, 2L);
    private static final UUID HIDDEN_ACTOR_ID = new UUID(0L, 3L);

    @Test
    void fullProjectionRemovesAuthorityRevisionScopeAndAuditMetadata() {
        AuthorityEvent authority = authority(Map.ofEntries(
                Map.entry("block", "minecraft:stone"),
                Map.entry("block.revision.target", "31"),
                Map.entry("scope.kind", "block"),
                Map.entry(
                        "audit.corrector_id",
                        HIDDEN_ACTOR_ID.toString()),
                Map.entry("action.generation", "9"),
                Map.entry("routing.bot_generation", "9"),
                Map.entry("target.bot_id", BOT_ID.toString())));

        PerceptionProjection projection = PerceptionProjection.full(
                authority,
                10L,
                PerceptionChannel.SELF,
                1.0F);

        Assertions.assertEquals(
                Map.of("block", "minecraft:stone"),
                projection.delta());
    }

    @Test
    void selfProjectionKeepsOnlyTheBotActorAndRemovesIdentityFields() {
        AuthorityEvent authority = authority(Map.ofEntries(
                Map.entry("action", "hurt"),
                Map.entry("target.id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("actor.id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("attacker.id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("victim.id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("entity.id", HIDDEN_ACTOR_ID.toString()),
                Map.entry(
                        "evidence.entity.id",
                        HIDDEN_ACTOR_ID.toString()),
                Map.entry("context.target_id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("context.actor_id", HIDDEN_ACTOR_ID.toString()),
                Map.entry("context.uuid", HIDDEN_ACTOR_ID.toString())));

        PerceptionProjection projection =
                PerceptionProjection.self(authority, BOT_ID, 10L, 1.0F);

        Assertions.assertEquals(
                List.of(new ActorRef(
                        BOT_ID, "minecraft:player", "Bot")),
                projection.actors());
        Assertions.assertEquals(Map.of("action", "hurt"), projection.delta());
    }

    private static AuthorityEvent authority(Map<String, String> delta) {
        SemanticEventDraft event = new SemanticEventDraft(
                new UUID(0L, 4L),
                "minecraft:overworld",
                10L,
                Instant.EPOCH,
                SemanticEventType.ENTITY_DAMAGED,
                SemanticEventOutcome.COMMITTED,
                Optional.empty(),
                List.of(
                        new ActorRef(BOT_ID, "minecraft:player", "Bot"),
                        new ActorRef(
                                HIDDEN_ACTOR_ID,
                                "minecraft:player",
                                "Hidden")),
                List.of(),
                delta,
                SemanticEventSource.NEOFORGE_EVENT,
                Set.of(PerceptionChannel.SELF),
                1.0F,
                Set.of("test"),
                false);
        return new AuthorityEvent(SESSION_ID, 1L, 99L, event);
    }
}
