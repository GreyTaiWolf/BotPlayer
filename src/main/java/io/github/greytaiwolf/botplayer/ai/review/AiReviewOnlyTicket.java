package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.Objects;

/** Exact server-only correlation between a P6-R1 dispatch and its frozen review projection. */
public record AiReviewOnlyTicket(
        AiRequestDispatchReceipt dispatch,
        AiReviewOnlySnapshotProjection projection) {
    public AiReviewOnlyTicket {
        dispatch = Objects.requireNonNull(dispatch, "dispatch");
        projection = Objects.requireNonNull(projection, "projection");
        if (dispatch.purpose() != AiReviewOnlyContract.PURPOSE
                || !dispatch.botId().equals(projection.botId())
                || dispatch.generation() != projection.generation()
                || dispatch.revision() != projection.snapshotId()) {
            throw new IllegalArgumentException("review ticket correlation is invalid");
        }
    }

    @Override
    public String toString() {
        return "AiReviewOnlyTicket[dispatch=" + dispatch
                + ", projection=" + projection + "]";
    }
}
