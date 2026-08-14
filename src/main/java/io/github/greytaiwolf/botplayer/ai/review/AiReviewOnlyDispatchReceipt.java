package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.Objects;
import java.util.Optional;

/** Server-side receipt for one manual review dispatch; it does not imply a Provider result. */
public record AiReviewOnlyDispatchReceipt(
        AiReviewOnlyDispatchStatus status,
        Optional<AiRequestDispatchReceipt> dispatch,
        Optional<AiReviewOnlySnapshotProjection> projection) {
    public AiReviewOnlyDispatchReceipt {
        status = Objects.requireNonNull(status, "status");
        dispatch = Objects.requireNonNull(dispatch, "dispatch");
        projection = Objects.requireNonNull(projection, "projection");
        if (status == AiReviewOnlyDispatchStatus.DISPATCHED
                != (dispatch.isPresent() && projection.isPresent())) {
            throw new IllegalArgumentException(
                    "only a dispatched review receipt may expose a correlation");
        }
    }

    public static AiReviewOnlyDispatchReceipt dispatched(
            AiRequestDispatchReceipt dispatch,
            AiReviewOnlySnapshotProjection projection) {
        return new AiReviewOnlyDispatchReceipt(
                AiReviewOnlyDispatchStatus.DISPATCHED,
                Optional.of(Objects.requireNonNull(dispatch, "dispatch")),
                Optional.of(Objects.requireNonNull(projection, "projection")));
    }

    public static AiReviewOnlyDispatchReceipt rejected(AiReviewOnlyDispatchStatus status) {
        if (status == AiReviewOnlyDispatchStatus.DISPATCHED) {
            throw new IllegalArgumentException("use dispatched receipt factory");
        }
        return new AiReviewOnlyDispatchReceipt(status, Optional.empty(), Optional.empty());
    }

    @Override
    public String toString() {
        return "AiReviewOnlyDispatchReceipt[status=" + status
                + ", dispatched=" + dispatch.isPresent() + "]";
    }
}
