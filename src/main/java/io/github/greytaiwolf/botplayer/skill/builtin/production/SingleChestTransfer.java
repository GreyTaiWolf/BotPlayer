package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 3x9 单箱中玩家账本与箱子账本之间的精确转移合同。
 *
 * <p>这里不保存容器 id 或具体槽位。菜单适配层只能在原版打开一个精确的
 * {@link MenuFamily#CHEST_3X9} 后，基于权威快照编译实际点击模板。
 */
public record SingleChestTransfer(
        ProductionLedger depositToChest,
        ProductionLedger withdrawFromChest,
        int maximumClicks) implements ProductionOperation {
    public SingleChestTransfer {
        Objects.requireNonNull(depositToChest, "depositToChest");
        Objects.requireNonNull(withdrawFromChest, "withdrawFromChest");
        if (depositToChest.isEmpty() && withdrawFromChest.isEmpty()) {
            throw new IllegalArgumentException(
                    "single chest transfer must not be empty");
        }
        Set<ProductionMaterial> overlap = new HashSet<>(
                depositToChest.quantities().keySet());
        overlap.retainAll(withdrawFromChest.quantities().keySet());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "one transfer cannot deposit and withdraw the same material");
        }
        if (maximumClicks < 1
                || maximumClicks > ProductionMenuContract.ABSOLUTE_MAX_CLICKS) {
            throw new IllegalArgumentException(
                    "single chest click limit is outside the bounded range");
        }
    }

    @Override
    public ProductionOperationKind kind() {
        return ProductionOperationKind.SINGLE_CHEST_TRANSFER;
    }

    public ProductionMenuContract menuContract() {
        return new ProductionMenuContract(
                MenuFamily.CHEST_3X9,
                maximumClicks,
                true,
                new ProductionDelta(depositToChest, withdrawFromChest));
    }

    public Optional<ProductionLedger> expectedPlayerAfter(
            ProductionLedger playerBefore) {
        return menuContract().expectedPlayerDelta().applyTo(
                Objects.requireNonNull(playerBefore, "playerBefore"));
    }

    public Optional<ProductionLedger> expectedChestAfter(
            ProductionLedger chestBefore) {
        Objects.requireNonNull(chestBefore, "chestBefore");
        return chestBefore.trySubtract(withdrawFromChest).map(remaining ->
                remaining.plus(depositToChest));
    }

    public boolean isReady(
            ProductionLedger playerBefore, ProductionLedger chestBefore) {
        return expectedPlayerAfter(playerBefore).isPresent()
                && expectedChestAfter(chestBefore).isPresent();
    }
}
