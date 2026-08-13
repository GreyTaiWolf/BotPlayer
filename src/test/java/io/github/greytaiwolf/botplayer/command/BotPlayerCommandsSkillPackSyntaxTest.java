package io.github.greytaiwolf.botplayer.command;

import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackId;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackRevision;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotPlayerCommandsSkillPackSyntaxTest {
    private static final String HASH = "a".repeat(64);
    private static final String BOT_ID =
            "00000000-0000-0000-0000-000000000001";

    @Test
    void parsesOnlyAnExactBoundedPackRevision() {
        SkillPackRevision revision = BotPlayerCommands
                .parseSkillPackRevision(
                        "botplayer:wood_to_stone", "1.2.3", HASH);

        Assertions.assertEquals(
                new SkillPackId("botplayer", "wood_to_stone"),
                revision.id());
        Assertions.assertEquals(new SkillVersion(1, 2, 3),
                revision.version());
        Assertions.assertEquals(HASH, revision.contentHash().value());
    }

    @Test
    void rejectsAmbiguousRevisionTokensBeforeTheyReachApproval() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseSkillPackRevision(
                        "botplayer:wood_to_stone", "01.2.3", HASH));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseSkillPackRevision(
                        "botplayer:wood_to_stone", "1.2", HASH));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseSkillPackRevision(
                        "botplayer:wood_to_stone", "65536.0.0", HASH));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseSkillPackRevision(
                        "botplayer:wood_to_stone", "1.2.3", "A".repeat(64)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseSkillPackRevision(
                        "BotPlayer:wood_to_stone", "1.2.3", HASH));
    }

    @Test
    void requiresAConcreteCanonicalNonZeroBotUuidForRun() {
        Assertions.assertEquals(UUID.fromString(BOT_ID),
                BotPlayerCommands.parseCanonicalNonZeroUuid(BOT_ID));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseCanonicalNonZeroUuid(
                        "00000000-0000-0000-0000-000000000000"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseCanonicalNonZeroUuid(
                        "00000000-0000-0000-0000-00000000000A"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BotPlayerCommands.parseCanonicalNonZeroUuid(
                        "1-1-1-1-1"));
    }
}
