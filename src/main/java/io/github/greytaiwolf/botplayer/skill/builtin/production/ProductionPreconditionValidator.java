package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Objects;
import java.util.Optional;

/**
 * 将已解析的计划节点和权威观察相比较；不打开菜单、不点击、不修改账本。
 */
public final class ProductionPreconditionValidator {
    public ProductionPreconditionResult validate(
            ProductionResolvedNode node,
            ProductionPreconditionSnapshot snapshot) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(snapshot, "snapshot");
        Optional<ProductionMenuContract> contract = node.menuContract();
        if (contract.isEmpty() && snapshot.openedMenuFamily().isPresent()) {
            return ProductionPreconditionResult.rejected(
                    ProductionPreconditionRejection.UNEXPECTED_MENU_OPEN);
        }
        if (contract.isPresent()) {
            Optional<MenuFamily> opened = snapshot.openedMenuFamily();
            if (opened.isEmpty()) {
                return ProductionPreconditionResult.rejected(
                        ProductionPreconditionRejection.MENU_NOT_OPEN);
            }
            if (opened.orElseThrow() != contract.orElseThrow().family()) {
                return ProductionPreconditionResult.rejected(
                        ProductionPreconditionRejection.MENU_FAMILY_MISMATCH);
            }
            if (contract.orElseThrow().requiresEmptyCursor()
                    && !snapshot.cursorEmpty()) {
                return ProductionPreconditionResult.rejected(
                        ProductionPreconditionRejection.CURSOR_NOT_EMPTY);
            }
        }
        Optional<ProductionLedger> playerAfter = node.expectedPlayerDelta()
                .applyTo(snapshot.playerLedger());
        if (playerAfter.isEmpty()) {
            return ProductionPreconditionResult.rejected(
                    ProductionPreconditionRejection.PLAYER_LEDGER_INSUFFICIENT);
        }
        if (node.node().operation() instanceof SingleChestTransfer transfer) {
            Optional<ProductionLedger> chest = snapshot.chestLedger();
            if (chest.isEmpty()) {
                return ProductionPreconditionResult.rejected(
                        ProductionPreconditionRejection.CHEST_LEDGER_MISSING);
            }
            Optional<ProductionLedger> chestAfter = transfer.expectedChestAfter(
                    chest.orElseThrow());
            if (chestAfter.isEmpty()) {
                return ProductionPreconditionResult.rejected(
                        ProductionPreconditionRejection.CHEST_LEDGER_INSUFFICIENT);
            }
            return ProductionPreconditionResult.ready(
                    playerAfter.orElseThrow(), chestAfter);
        }
        return ProductionPreconditionResult.ready(
                playerAfter.orElseThrow(), Optional.empty());
    }
}
