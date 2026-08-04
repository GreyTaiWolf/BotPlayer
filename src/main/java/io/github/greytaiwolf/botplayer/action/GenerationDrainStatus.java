package io.github.greytaiwolf.botplayer.action;

/**
 * 动作代关闭的只读聚合状态。PENDING 只表示仍持有票据或租约，不能与已经
 * 隔离的 UNSAFE 混为一谈。
 */
public enum GenerationDrainStatus {
    PENDING,
    COMPLETE,
    UNSAFE;

    public static GenerationDrainStatus classify(
            boolean unsafe,
            boolean ticketRemaining,
            boolean leaseRemaining) {
        if (unsafe) {
            return UNSAFE;
        }
        return ticketRemaining || leaseRemaining
                ? PENDING
                : COMPLETE;
    }
}
