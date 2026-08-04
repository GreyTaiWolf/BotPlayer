package io.github.greytaiwolf.botplayer.safety;

/**
 * 将 L0 无法自行完成的恢复动作移交给受约束的生存技能。
 *
 * <p>实现只能记录不可变请求或启动 generation 绑定的技能，不能在回调中绕过动作层直接修改玩家状态。
 */
@FunctionalInterface
public interface SafetyHandoff {
    SafetyHandoffDecision request(SafetyHandoffRequest request);

    static SafetyHandoff unavailable() {
        return ignored -> SafetyHandoffDecision.FALLBACK;
    }
}
