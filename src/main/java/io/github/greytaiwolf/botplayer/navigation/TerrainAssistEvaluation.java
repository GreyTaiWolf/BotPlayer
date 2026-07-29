package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import java.util.Objects;
import java.util.Optional;

public record TerrainAssistEvaluation(
        Status status,
        Optional<Decision> decision,
        String safeSummary) {
    public TerrainAssistEvaluation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if ((status == Status.ACTION) != decision.isPresent()) {
            throw new IllegalArgumentException(
                    "only ACTION may contain a decision");
        }
        if (safeSummary.isBlank()
                || safeSummary.length() > 256
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be 1-256 safe characters");
        }
    }

    public static TerrainAssistEvaluation withoutAction(
            Status status, String summary) {
        if (status == Status.ACTION) {
            throw new IllegalArgumentException(
                    "ACTION requires a decision");
        }
        return new TerrainAssistEvaluation(
                status, Optional.empty(), summary);
    }

    public static TerrainAssistEvaluation action(
            Decision decision) {
        Objects.requireNonNull(decision, "decision");
        return new TerrainAssistEvaluation(
                Status.ACTION,
                Optional.of(decision),
                decision.safeSummary());
    }

    public enum Status {
        ACTION,
        NOT_REQUESTED,
        SERVER_POLICY_BLOCKED,
        BUDGET_EXHAUSTED,
        NO_SAFE_EPISODE
    }

    public record Decision(
            WorldInteractionAction action,
            Mutation mutation,
            int maximumTicks,
            String safeSummary) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(mutation, "mutation");
            if (maximumTicks < 1 || maximumTicks > 600) {
                throw new IllegalArgumentException(
                        "maximumTicks must be between 1 and 600");
            }
            Objects.requireNonNull(safeSummary, "safeSummary");
            if (safeSummary.isBlank()
                    || safeSummary.length() > 256
                    || !safeSummary.equals(safeSummary.strip())) {
                throw new IllegalArgumentException(
                        "safeSummary must be 1-256 trimmed characters");
            }
        }
    }

    public record Mutation(Kind kind, GridPoint position) {
        public Mutation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(position, "position");
        }
    }

    public enum Kind {
        BREAK,
        PLACE
    }
}
