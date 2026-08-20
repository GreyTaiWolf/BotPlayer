package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.Objects;
import java.util.Optional;

/** Exact server-only correlation between a P6-R1 dispatch and its frozen review projection. */
public record AiReviewOnlyTicket(
        AiRequestDispatchReceipt dispatch,
        AiReviewOnlySnapshotProjection projection,
        Optional<AiPhysicalAttemptIdentity> physicalAttemptIdentity) {
    public AiReviewOnlyTicket {
        dispatch = Objects.requireNonNull(dispatch, "dispatch");
        projection = Objects.requireNonNull(projection, "projection");
        physicalAttemptIdentity = Objects.requireNonNull(
                physicalAttemptIdentity, "physicalAttemptIdentity");
        if (dispatch.purpose() != AiReviewOnlyContract.PURPOSE
                || !dispatch.botId().equals(projection.botId())
                || dispatch.generation() != projection.generation()
                || dispatch.revision() != projection.snapshotId()) {
            throw new IllegalArgumentException("review ticket correlation is invalid");
        }
        if (physicalAttemptIdentity.isPresent()
                && !physicalAttemptIdentity.orElseThrow()
                        .dispatchReceipt().equals(dispatch)) {
            throw new IllegalArgumentException(
                    "physical attempt identity must match review ticket dispatch");
        }
    }

    /** Compatibility ticket for pure review-gate tests before a B1 offer is created. */
    public AiReviewOnlyTicket(
            AiRequestDispatchReceipt dispatch,
            AiReviewOnlySnapshotProjection projection) {
        this(dispatch, projection, Optional.empty());
    }

    /** R1 production ticket carrying the one exact B1 physical-attempt identity. */
    public AiReviewOnlyTicket(
            AiRequestDispatchReceipt dispatch,
            AiReviewOnlySnapshotProjection projection,
            AiPhysicalAttemptIdentity physicalAttemptIdentity) {
        this(dispatch, projection, Optional.of(Objects.requireNonNull(
                physicalAttemptIdentity, "physicalAttemptIdentity")));
    }

    @Override
    public String toString() {
        return "AiReviewOnlyTicket[dispatch=" + dispatch
                + ", projection=" + projection
                + ", physicalAttemptBound=" + physicalAttemptIdentity.isPresent() + "]";
    }
}
