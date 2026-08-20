package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import java.util.List;

/**
 * 单个 descriptor 版本的服务器主线程执行器。
 *
 * <p>处理器只能通过其已有的受控 action/navigation/menu 端口改变世界；本接口
 * 本身不提供活的玩家、世界或网络对象。
 */
public interface SkillNodeHandler {
    /**
     * 节点在开始前必须原子持有的资源。运行时按 canonical 顺序申请、在等待期间续租，
     * 并在节点完成、暂停、取消、代际关闭或停服时释放。
     *
     * <p>处理器只能返回纯 {@link ReservationRequest}；不能以此携带活的方块、实体或
     * 菜单对象。
     */
    default List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        return List.of();
    }

    SkillNodeDirective begin(SkillNodeContext context);

    default SkillNodeDirective tick(SkillNodeContext context) {
        return SkillNodeDirective.continueRunning("技能节点继续运行");
    }

    default SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        return SkillNodeDirective.fail(
                io.github.greytaiwolf.botplayer.skill.core
                        .SkillFailureCode.ACTION_FAILED,
                "技能节点收到了未处理的异步回执");
    }

    /**
     * Gives a handler one narrowly controlled chance to recover from an already
     * terminal failed signal.
     *
     * <p>The default remains terminal.  A handler may request a current-node
     * replan only by returning the action bridge's one-shot capability after
     * it has checked the reviewed no-native-side-effect condition. This hook
     * is never used for cancellation, preemption, or stale signals.
     */
    default FailedSignalDisposition failed(
            SkillNodeContext context, SkillSignal signal) {
        return FailedSignalDisposition.terminate();
    }

    default void cancelled(SkillNodeContext context, String reason) {
        // 处理器可在此撤销自己的受控动作；运行时随后会释放所有 reservation。
    }

    /**
     * Requests cancellation before the runtime commits its own terminal state.
     *
     * <p>The default preserves the existing one-step cancellation contract.
     * An action-backed strict natural use may instead report that native
     * completion has already been entered; the runtime then keeps the exact
     * action receipt live and settles it before deciding the Skill terminal.
     */
    default CancellationAdmission requestCancellation(
            SkillNodeContext context, String reason) {
        cancelled(context, reason);
        return CancellationAdmission.IMMEDIATE;
    }

    enum CancellationAdmission {
        /** The handler safely accepted ordinary immediate cancellation. */
        IMMEDIATE,
        /** A strict native action has already crossed its completion boundary. */
        AWAIT_EXACT_ACTION_TERMINAL
    }

    /**
     * A sealed failed-signal result.  Handlers cannot fabricate a replan: the
     * only replan implementation is minted after an action bridge consumes its
     * exact terminal completion capability.
     */
    sealed interface FailedSignalDisposition
            permits TerminalFailedSignalDisposition,
                    ActionBackedSkillNodeHandler.NoSideEffectActionReplan {
        static FailedSignalDisposition terminate() {
            return TerminalFailedSignalDisposition.INSTANCE;
        }

        /** Whether this result carries the bridge-minted replan capability. */
        boolean permitsCurrentNodeReplan();
    }
}

/** Package-private terminal singleton; a handler cannot turn it into a retry. */
enum TerminalFailedSignalDisposition
        implements SkillNodeHandler.FailedSignalDisposition {
    INSTANCE;

    @Override
    public boolean permitsCurrentNodeReplan() {
        return false;
    }
}
