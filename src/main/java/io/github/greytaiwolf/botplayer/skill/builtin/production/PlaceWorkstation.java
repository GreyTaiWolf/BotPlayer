package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 用一次真实原版 {@code PlaceBlock} 消耗并放置一个审核工作站。
 *
 * <p>坐标、锚点、命中面、目标空气状态和完整放置 state 都不属于 production 模板；它们必须在
 * 当前服务器 tick 由 Minecraft 端口观察、冻结并在动作后重新回读。这里仅声明不可伪造的
 * 账本扣款和封闭工作站种类。
 */
public record PlaceWorkstation(WorkstationKind workstation)
        implements ProductionOperation {
    public PlaceWorkstation {
        Objects.requireNonNull(workstation, "workstation");
    }

    @Override
    public ProductionOperationKind kind() {
        return ProductionOperationKind.PLACE_WORKSTATION;
    }

    /**
     * 放置不直接给物品；它必须精确消费一件对应的原版 block item。
     */
    public ProductionDelta expectedDelta() {
        return new ProductionDelta(
                ProductionLedger.of(workstation.material(), 1),
                ProductionLedger.empty());
    }
}
