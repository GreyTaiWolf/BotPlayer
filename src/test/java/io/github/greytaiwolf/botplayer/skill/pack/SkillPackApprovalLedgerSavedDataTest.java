package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillPackApprovalLedgerSavedDataTest {
    private static final UUID SERVER_ID = new UUID(2L, 1L);
    private static final UUID REVIEWER_ID = new UUID(2L, 2L);
    private static final SkillPackRevision REVISION = new SkillPackRevision(
            new SkillPackId("botplayer", "wood_to_stone"),
            new SkillVersion(1, 2, 3),
            new SkillPackHash("b".repeat(64)));

    @Test
    void savesOnlyExactRevisionAndReviewerAcrossRestart() {
        SkillPackApprovalLedgerSavedData data =
                SkillPackApprovalLedgerSavedData.create(SERVER_ID);
        data.approve(REVISION, REVIEWER_ID);

        CompoundTag encoded = data.save(new CompoundTag(), null);
        Assertions.assertEquals(
                Set.of("SchemaVersion", "ServerInstanceId", "Approvals"),
                encoded.getAllKeys());
        Assertions.assertEquals(
                Set.of("Id", "Major", "Minor", "Patch", "Hash", "Reviewer"),
                encoded.getList("Approvals", Tag.TAG_COMPOUND)
                        .getCompound(0)
                        .getAllKeys());
        SkillPackApprovalLedgerSavedData restored =
                SkillPackApprovalLedgerSavedData.load(encoded, null);

        Assertions.assertEquals(SERVER_ID, restored.serverInstanceId());
        Assertions.assertEquals(Optional.of(REVIEWER_ID),
                restored.reviewer(REVISION));
        Assertions.assertEquals(1, restored.approvals().size());
        Assertions.assertTrue(restored.revoke(REVISION));
        Assertions.assertTrue(restored.reviewer(REVISION).isEmpty());
    }

    @Test
    void rejectsMalformedOrAmbiguousSavedApprovalEntries() {
        SkillPackApprovalLedgerSavedData data =
                SkillPackApprovalLedgerSavedData.create(SERVER_ID);
        data.approve(REVISION, REVIEWER_ID);
        CompoundTag encoded = data.save(new CompoundTag(), null);
        ListTag approvals = encoded.getList("Approvals", Tag.TAG_COMPOUND);
        approvals.getCompound(0).putString("Hash", "A".repeat(64));

        Assertions.assertThrows(IllegalStateException.class,
                () -> SkillPackApprovalLedgerSavedData.load(encoded, null));
    }
}
