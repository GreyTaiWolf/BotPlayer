package io.github.greytaiwolf.botplayer.navigation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public record NavigationSubmission(
        Status status,
        Optional<CompletionStage<NavigationOutcome>> completion,
        String safeSummary) {
    public NavigationSubmission {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if ((status == Status.ENQUEUED) != completion.isPresent()) {
            throw new IllegalArgumentException(
                    "only ENQUEUED submissions contain a completion stage");
        }
    }

    public static NavigationSubmission enqueued(
            CompletionStage<NavigationOutcome> completion) {
        return new NavigationSubmission(
                Status.ENQUEUED,
                Optional.of(Objects.requireNonNull(completion, "completion")),
                "导航会话已创建");
    }

    public static NavigationSubmission rejected(
            Status status, String summary) {
        if (status == Status.ENQUEUED) {
            throw new IllegalArgumentException(
                    "rejected status must not be ENQUEUED");
        }
        return new NavigationSubmission(
                status, Optional.empty(), summary);
    }

    public enum Status {
        ENQUEUED,
        DUPLICATE,
        BOT_BUSY,
        BOT_NOT_ACTIVE,
        STALE_GENERATION,
        WRONG_DIMENSION,
        GOAL_OUT_OF_RANGE,
        SUPPLY_REQUIRED,
        RUNTIME_CLOSED
    }
}
