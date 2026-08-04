package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.util.Objects;
import java.util.UUID;

/**
 * 在任何原版背包掉落实体生成前冻结的不可变死亡票据。
 */
public record VanillaDeathTicket(
        UUID transactionId,
        UUID botId,
        long generation,
        long createdTick,
        boolean preserveExperience,
        int baseExperienceReward,
        DeathExperienceSnapshot respawnExperience) {
    public VanillaDeathTicket {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(
                respawnExperience, "respawnExperience");
        if (transactionId.getMostSignificantBits() == 0L
                && transactionId.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(
                    "transactionId must be non-zero");
        }
        if (botId.getMostSignificantBits() == 0L
                && botId.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(
                    "botId must be non-zero");
        }
        if (generation <= 0L || createdTick < 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive and tick non-negative");
        }
        if (baseExperienceReward < 0) {
            throw new IllegalArgumentException(
                    "baseExperienceReward must be non-negative");
        }
        if (preserveExperience && baseExperienceReward != 0) {
            throw new IllegalArgumentException(
                    "preserved experience cannot also create an XP reward");
        }
        if (!preserveExperience
                && !DeathExperienceSnapshot.ZERO.equals(
                        respawnExperience)) {
            throw new IllegalArgumentException(
                    "consumed experience must respawn as zero");
        }
    }

    public static VanillaDeathTicket create(
            UUID transactionId,
            UUID botId,
            long generation,
            long createdTick,
            DeathExperienceSnapshot currentExperience,
            boolean preserveExperience,
            int baseExperienceReward) {
        Objects.requireNonNull(
                currentExperience, "currentExperience");
        return new VanillaDeathTicket(
                transactionId,
                botId,
                generation,
                createdTick,
                preserveExperience,
                preserveExperience ? 0 : baseExperienceReward,
                preserveExperience
                        ? currentExperience
                        : DeathExperienceSnapshot.ZERO);
    }
}
