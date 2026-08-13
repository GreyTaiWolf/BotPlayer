package io.github.greytaiwolf.botplayer.safety;

/**
 * 将 L0 无法自行完成的恢复动作移交给受约束的生存技能。
 *
 * <p>实现只能记录不可变请求或启动 generation 绑定的技能，不能在回调中绕过动作层直接修改玩家状态。
 */
@FunctionalInterface
public interface SafetyHandoff {
    SafetyHandoffDecision request(SafetyHandoffRequest request);

    /**
     * 在 L0 观察到现有交接不再安全时，撤销低优先级的有限动作。
     *
     * <p>默认实现保持既有交接端口的兼容性；实现方若维护可抢占会话，必须只依据同一权威
     * {@link SafetyHandoffRequest} 做出取消决定。
     */
    default boolean preempt(SafetyHandoffRequest request) {
        return false;
    }

    static SafetyHandoff unavailable() {
        return ignored -> SafetyHandoffDecision.FALLBACK;
    }
}
