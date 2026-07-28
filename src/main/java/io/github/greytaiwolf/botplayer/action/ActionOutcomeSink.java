package io.github.greytaiwolf.botplayer.action;

/**
 * 动作 canonical 终态在服务器线程提交后的同步观察边界。
 *
 * <p>实现只能复制不可变证据或入有界队列，不得阻塞、访问异步世界对象或改变动作结果。
 */
@FunctionalInterface
public interface ActionOutcomeSink {
    void accept(ActionEnvelope envelope, ActionOutcome outcome);

    static ActionOutcomeSink noop() {
        return (envelope, outcome) -> {};
    }
}
