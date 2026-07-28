package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.Objects;
import java.util.UUID;

/**
 * 原版已经定向给某个 bot listener 的不可变声音候选。
 */
public record SoundObservationCandidate(
        UUID botId,
        long botGeneration,
        String dimension,
        long gameTick,
        SpatialPoint position,
        String soundId,
        String source,
        float volume,
        float pitch) {
    public SoundObservationCandidate {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0 || gameTick < 0) {
            throw new IllegalArgumentException("sound generation/tick is invalid");
        }
        dimension = requireText(dimension, "dimension", 128);
        Objects.requireNonNull(position, "position");
        soundId = requireText(soundId, "soundId", 128);
        source = requireText(source, "source", 64);
        if (!Float.isFinite(volume)
                || volume < 0.0F
                || !Float.isFinite(pitch)
                || pitch < 0.0F) {
            throw new IllegalArgumentException("sound volume/pitch is invalid");
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }
}
