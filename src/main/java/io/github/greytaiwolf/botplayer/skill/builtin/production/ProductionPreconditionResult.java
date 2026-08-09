package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;
import java.util.Optional;

/**
 * 前置检查结论。成功时的 after ledger 是待验证目标，不是写库存操作。
 */
public record ProductionPreconditionResult(
        boolean ready,
        Optional<ProductionPreconditionRejection> rejection,
        Optional<ProductionLedger> expectedPlayerAfter,
        Optional<ProductionLedger> expectedChestAfter) {
    public ProductionPreconditionResult {
        rejection = Objects.requireNonNull(rejection, "rejection");
        expectedPlayerAfter = Objects.requireNonNull(
                expectedPlayerAfter, "expectedPlayerAfter");
        expectedChestAfter = Objects.requireNonNull(
                expectedChestAfter, "expectedChestAfter");
        if (ready) {
            if (rejection.isPresent() || expectedPlayerAfter.isEmpty()) {
                throw new IllegalArgumentException(
                        "ready precondition requires a player target and no rejection");
            }
        } else if (rejection.isEmpty()
                || expectedPlayerAfter.isPresent()
                || expectedChestAfter.isPresent()) {
            throw new IllegalArgumentException(
                    "rejected precondition must expose only its rejection");
        }
    }

    public static ProductionPreconditionResult ready(
            ProductionLedger playerAfter,
            Optional<ProductionLedger> chestAfter) {
        return new ProductionPreconditionResult(true, Optional.empty(),
                Optional.of(Objects.requireNonNull(playerAfter,
                        "playerAfter")),
                Objects.requireNonNull(chestAfter, "chestAfter"));
    }

    public static ProductionPreconditionResult rejected(
            ProductionPreconditionRejection rejection) {
        return new ProductionPreconditionResult(false,
                Optional.of(Objects.requireNonNull(rejection, "rejection")),
                Optional.empty(), Optional.empty());
    }
}
