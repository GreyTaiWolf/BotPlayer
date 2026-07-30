package io.github.greytaiwolf.botplayer.safety;

/**
 * L0 与生存技能之间的单 Tick 交接结果。
 */
public enum SafetyHandoffDecision {
    /**
     * 对应技能已经接受请求；L0 保持导航挂起，但不争用技能需要的动作通道。
     */
    DELEGATED,

    /**
     * 同一代际、同一危险的恢复技能已经在运行。
     */
    ALREADY_DELEGATED,

    /**
     * 当前没有可安全执行的技能，L0 应使用自己的保守回退。
     */
    FALLBACK;

    public boolean delegated() {
        return this == DELEGATED || this == ALREADY_DELEGATED;
    }
}
