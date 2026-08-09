package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Objects;

/**
 * 交给菜单事务适配层的无副作用合同。
 *
 * <p>适配层必须先见到完全匹配的 {@link MenuFamily} 与空 cursor，随后再把真实槽位布局
 * 编译成逐点击模板。这里的账本 delta 是验证目标，不是对库存的直接写入授权。
 */
public record ProductionMenuContract(
        MenuFamily family,
        int maximumClicks,
        boolean requiresEmptyCursor,
        ProductionDelta expectedPlayerDelta) {
    public static final int ABSOLUTE_MAX_CLICKS = 64;

    public ProductionMenuContract {
        Objects.requireNonNull(family, "family");
        if (maximumClicks < 1 || maximumClicks > ABSOLUTE_MAX_CLICKS) {
            throw new IllegalArgumentException(
                    "maximum menu clicks must be within 1.."
                            + ABSOLUTE_MAX_CLICKS);
        }
        Objects.requireNonNull(expectedPlayerDelta, "expectedPlayerDelta");
    }
}
