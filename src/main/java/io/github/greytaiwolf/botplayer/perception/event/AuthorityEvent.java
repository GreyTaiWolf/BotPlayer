package io.github.greytaiwolf.botplayer.perception.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务器已提交的事实事件。它不会因为存在于总线中而自动成为任何 bot 的知识。
 */
public record AuthorityEvent(
        UUID sessionId,
        long eventSeq,
        long worldRevision,
        SemanticEventDraft event) {
    public AuthorityEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        if (eventSeq <= 0) {
            throw new IllegalArgumentException("eventSeq must be positive");
        }
        if (worldRevision < 0) {
            throw new IllegalArgumentException("worldRevision must not be negative");
        }
        Objects.requireNonNull(event, "event");
    }
}
