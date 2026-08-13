package io.github.greytaiwolf.botplayer.technique.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Stable per-phase variation seed without retaining random state in a run. */
public final class TechniqueDeterministicSeed {
    private TechniqueDeterministicSeed() {
    }

    public static long derive(UUID botId, UUID skillRunId, UUID techniqueRunId,
            String phase, long profileSalt) {
        UUID bot = requireNonZero(botId, "botId");
        UUID skill = requireNonZero(skillRunId, "skillRunId");
        UUID technique = requireNonZero(techniqueRunId, "techniqueRunId");
        String requiredPhase = Objects.requireNonNull(phase, "phase");
        if (requiredPhase.isEmpty() || requiredPhase.length() > 96
                || requiredPhase.codePoints().anyMatch(Character::isISOControl)
                || requiredPhase.codePoints().anyMatch(codePoint -> codePoint >= 0xD800
                        && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException(
                    "technique seed phase must be bounded plain text");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer ids = ByteBuffer.allocate(Long.BYTES * 7);
            ids.putLong(bot.getMostSignificantBits()).putLong(bot.getLeastSignificantBits());
            ids.putLong(skill.getMostSignificantBits()).putLong(skill.getLeastSignificantBits());
            ids.putLong(technique.getMostSignificantBits()).putLong(technique.getLeastSignificantBits());
            ids.putLong(profileSalt);
            digest.update(ids.array());
            digest.update((byte) 0);
            digest.update(requiredPhase.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (new UUID(0L, 0L).equals(required)) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
        return required;
    }
}
