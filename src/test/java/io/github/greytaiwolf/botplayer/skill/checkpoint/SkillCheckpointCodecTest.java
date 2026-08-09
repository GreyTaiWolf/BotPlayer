package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointCodecTest {
    private static final UUID SERVER_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);
    private static final UUID PLAYER_ID = new UUID(1L, 3L);
    private static final UUID RUN_ID = new UUID(1L, 4L);
    private static final UUID CHECKPOINT_ID = new UUID(1L, 5L);
    private static final UUID PLAN_ID = new UUID(1L, 6L);
    private static final UUID FIRST_NODE_ID = new UUID(1L, 7L);
    private static final UUID SECOND_NODE_ID = new UUID(1L, 8L);
    private static final UUID TARGET_ENTITY_ID = new UUID(1L, 9L);
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void roundTripsCanonicalPureDataAndIntegrity() {
        SkillCheckpoint checkpoint = checkpoint(4L, 9L);

        CompoundTag encoded = checkpoint.save();
        SkillCheckpoint restored = SkillCheckpoint.load(encoded);

        Assertions.assertEquals(checkpoint, restored);
        Assertions.assertEquals(encoded, restored.save());
        Assertions.assertEquals(
                List.of(FIRST_NODE_ID, SECOND_NODE_ID),
                restored.nodes().stream().map(SkillCheckpointNode::nodeId).toList());
        Assertions.assertTrue(restored.scope().isPresent());
    }

    @Test
    void rejectsTamperingAndUnknownFieldsFailClosed() {
        CompoundTag modified = checkpoint(4L, 9L).save();
        modified.putString("Phase", "tampered");

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> SkillCheckpoint.load(modified));

        CompoundTag unknownState = checkpoint(4L, 9L).save();
        unknownState.putString("ContinuationState", "UNKNOWN");
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> SkillCheckpoint.load(unknownState));

        CompoundTag unknown = checkpoint(4L, 9L).save();
        unknown.putString("Unexpected", "value");
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> SkillCheckpoint.load(unknown));
    }

    @Test
    void enforcesBoundsAndUniqueNodeIdsBeforeEncoding() {
        SkillCheckpointNode node = readyNode(FIRST_NODE_ID);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillCheckpoint(
                        CHECKPOINT_ID,
                        SERVER_ID,
                        BOT_ID,
                        PLAYER_ID,
                        4L,
                        RUN_ID,
                        new SkillCheckpointPlan(PLAN_ID, 3L, DIGEST),
                        "revalidate",
                        SkillCheckpointContinuationState.RESTARTABLE,
                        9L,
                        1,
                        0,
                        100L,
                        Optional.empty(),
                        List.of(node, node),
                        List.of(),
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CheckpointEvidence(
                        "observation",
                        "x".repeat(CheckpointEvidence.MAX_SUMMARY_LENGTH + 1),
                        1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillCheckpointPlan(
                        PLAN_ID,
                        3L,
                        "not-a-digest"));
    }

    static SkillCheckpoint checkpoint(long generation, long stateRevision) {
        SkillCheckpointNode failed = new SkillCheckpointNode(
                SECOND_NODE_ID,
                SkillCheckpointNodeState.FAILED,
                2,
                Optional.of("permission_denied"),
                List.of(new CheckpointEvidence(
                        "permission_denied", "保护规则拒绝了方块操作", 98L)));
        return new SkillCheckpoint(
                CHECKPOINT_ID,
                SERVER_ID,
                BOT_ID,
                PLAYER_ID,
                generation,
                RUN_ID,
                new SkillCheckpointPlan(PLAN_ID, 3L, DIGEST),
                "revalidate",
                SkillCheckpointContinuationState.RESTARTABLE,
                stateRevision,
                2,
                1,
                100L,
                Optional.of(new SkillCheckpointScope(
                        "minecraft:overworld",
                        12,
                        64,
                        -4,
                        Optional.of(TARGET_ENTITY_ID),
                        DIGEST,
                        "目标和库存摘要已重新核验")),
                List.of(failed, readyNode(FIRST_NODE_ID)),
                List.of(new CheckpointEvidence(
                        "inventory_observed", "仅保存计数和摘要，不保存物品 NBT", 99L)),
                Optional.empty());
    }

    private static SkillCheckpointNode readyNode(UUID nodeId) {
        return new SkillCheckpointNode(
                nodeId,
                SkillCheckpointNodeState.READY,
                1,
                Optional.empty(),
                List.of(new CheckpointEvidence(
                        "prerequisite_verified", "前置节点已完成", 97L)));
    }
}
