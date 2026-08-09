package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Objects;
import java.util.Optional;

/**
 * 由 Minecraft 适配层在同一个服务端时刻采集的生产前置证据。
 */
public record ProductionPreconditionSnapshot(
        long observationRevision,
        ProductionLedger playerLedger,
        Optional<MenuFamily> openedMenuFamily,
        boolean cursorEmpty,
        Optional<ProductionLedger> chestLedger) {
    public ProductionPreconditionSnapshot {
        if (observationRevision < 0L) {
            throw new IllegalArgumentException(
                    "production observation revision must not be negative");
        }
        Objects.requireNonNull(playerLedger, "playerLedger");
        openedMenuFamily = Objects.requireNonNull(
                openedMenuFamily, "openedMenuFamily");
        chestLedger = Objects.requireNonNull(chestLedger, "chestLedger");
    }
}
