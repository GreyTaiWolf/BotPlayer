package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;
import java.util.Optional;

/**
 * 通过 catalog/schema 审核后的节点。后续生命周期只能从此对象取得菜单合同。
 */
public record ProductionResolvedNode(
        ProductionPlanNode node,
        ProductionDelta expectedPlayerDelta,
        Optional<ProductionMenuContract> menuContract,
        Optional<FurnaceBatchRequirement> furnaceRequirement) {
    public ProductionResolvedNode {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(expectedPlayerDelta, "expectedPlayerDelta");
        menuContract = Objects.requireNonNull(menuContract, "menuContract");
        furnaceRequirement = Objects.requireNonNull(
                furnaceRequirement, "furnaceRequirement");
        if (node.operation() instanceof ResourceAcquisition) {
            if (menuContract.isPresent() || furnaceRequirement.isPresent()) {
                throw new IllegalArgumentException(
                        "resource acquisition must not require a menu contract");
            }
        } else if (menuContract.isEmpty()) {
            throw new IllegalArgumentException(
                    "menu operation requires an exact menu contract");
        }
    }
}
