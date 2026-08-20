package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.Objects;
import java.util.Optional;

/**
 * 每 Tick 至多发出一次点击的通用菜单事务执行器。
 *
 * <p>该类只消费不可变快照和点击计划。Minecraft 适配器负责真实打开、调用原版
 * {@code clicked}、广播变化及关闭窗口，并在每个边界把完整新快照反馈给本类。
 */
public final class MenuTransaction {
    private final MenuFamily expectedFamily;
    private final MenuTransactionLimits limits;
    private final long openedAtTick;
    private MenuTransactionState state;
    private MenuSnapshot observedSnapshot;
    private MenuTransactionPlan plan;
    private int confirmedClicks;
    private long lastClickTick;
    private MenuTransactionFailure failure;

    private MenuTransaction(
            MenuFamily expectedFamily,
            MenuTransactionLimits limits,
            long openedAtTick) {
        this.expectedFamily = Objects.requireNonNull(
                expectedFamily, "expectedFamily");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (openedAtTick < 0L) {
            throw new IllegalArgumentException(
                    "openedAtTick must not be negative");
        }
        this.openedAtTick = openedAtTick;
        state = MenuTransactionState.OPENING;
        lastClickTick = Long.MIN_VALUE;
    }

    public static MenuTransaction opening(
            MenuFamily expectedFamily,
            MenuTransactionLimits limits,
            long openedAtTick) {
        return new MenuTransaction(expectedFamily, limits, openedAtTick);
    }

    public MenuFamily expectedFamily() {
        return expectedFamily;
    }

    public MenuTransactionLimits limits() {
        return limits;
    }

    public long openedAtTick() {
        return openedAtTick;
    }

    public MenuTransactionState state() {
        return state;
    }

    public Optional<MenuSnapshot> observedSnapshot() {
        return Optional.ofNullable(observedSnapshot);
    }

    public Optional<MenuTransactionPlan> plan() {
        return Optional.ofNullable(plan);
    }

    public int confirmedClicks() {
        return confirmedClicks;
    }

    /**
     * 当前观测快照是否来自至少一次已经 ACK 的、仍可继续的 click 前缀。
     *
     * <p>这个资格专门供取消收口使用：正在 {@link MenuTransactionState#ACK} 的 click
     * 尚未确认，{@link #failAfterClickDispatchException(MenuSnapshot, long)} 留下的诊断
     * snapshot 也绝不能取得资格。调用方仍须重读原版 menu，并证明该快照未漂移。
     */
    public boolean hasConfirmedApplyingPrefix() {
        return state == MenuTransactionState.APPLYING
                && confirmedClicks > 0
                && failure == null;
    }

    public Optional<MenuTransactionFailure> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * 接收原版成功打开后的一次完整快照。
     */
    public boolean observeOpened(MenuSnapshot snapshot, long currentTick) {
        requireState(MenuTransactionState.OPENING);
        if (!withinDeadline(currentTick)) {
            return fail(MenuTransactionFailure.TIMEOUT);
        }
        if (snapshot == null || snapshot.family() != expectedFamily) {
            return fail(MenuTransactionFailure.UNEXPECTED_MENU);
        }
        observedSnapshot = snapshot;
        state = MenuTransactionState.SNAPSHOT;
        return true;
    }

    /**
     * 进入计划阶段并交出创建计划所需的完整快照。
     */
    public MenuSnapshot beginPlanning(long currentTick) {
        requireState(MenuTransactionState.SNAPSHOT);
        if (!withinDeadline(currentTick)) {
            fail(MenuTransactionFailure.TIMEOUT);
            throw new IllegalStateException("menu transaction timed out");
        }
        state = MenuTransactionState.PLANNING;
        return observedSnapshot;
    }

    /**
     * 安装只基于刚刚观测到的精确 stateId 构造的计划。
     */
    public boolean installPlan(
            MenuTransactionPlan candidate, long currentTick) {
        requireState(MenuTransactionState.PLANNING);
        if (!withinDeadline(currentTick)) {
            return fail(MenuTransactionFailure.TIMEOUT);
        }
        if (candidate == null
                || candidate.family() != expectedFamily
                || candidate.orderedSteps().size() > limits.maxClicks()
                || !candidate.initialSnapshot().equals(observedSnapshot)) {
            return fail(MenuTransactionFailure.INVALID_PLAN);
        }
        plan = candidate;
        state = MenuTransactionState.APPLYING;
        return true;
    }

    /**
     * 只在当前 Tick 尚未点击时发出下一次原版点击。
     *
     * <p>{@code beforeClick} 必须是适配器在点击边界重新读取的完整服务端快照；它不能
     * 用规划时缓存替代。这样外部菜单变化会在真正调用原版前失败关闭。
     */
    public Optional<MenuClick> issueNextClick(
            long currentTick, MenuSnapshot beforeClick) {
        requireState(MenuTransactionState.APPLYING);
        if (!withinDeadline(currentTick)) {
            fail(MenuTransactionFailure.TIMEOUT);
            return Optional.empty();
        }
        if (currentTick <= lastClickTick) {
            return Optional.empty();
        }
        MenuClickStep step = plan.orderedSteps().get(confirmedClicks);
        if (!acceptBeforeClickSnapshot(beforeClick, step)) {
            return Optional.empty();
        }
        observedSnapshot = beforeClick;
        lastClickTick = currentTick;
        state = MenuTransactionState.ACK;
        return Optional.of(step.click());
    }

    /**
     * 接收一次真实 {@code clicked} 后的完整快照与 stateId 回执。
     */
    public boolean acknowledge(MenuSnapshot afterClick, long currentTick) {
        requireState(MenuTransactionState.ACK);
        if (!withinDeadline(currentTick)) {
            return fail(MenuTransactionFailure.TIMEOUT);
        }
        if (currentTick < lastClickTick) {
            return fail(MenuTransactionFailure.STALE_STATE);
        }
        MenuClickStep step = plan.orderedSteps().get(confirmedClicks);
        if (afterClick == null || !afterClick.sameMenu(observedSnapshot)) {
            return fail(MenuTransactionFailure.UNEXPECTED_MENU);
        }
        if (afterClick.stateId() <= observedSnapshot.stateId()) {
            return fail(MenuTransactionFailure.STALE_STATE);
        }
        if (!step.expectedAfter().layoutEqualsIgnoringState(afterClick)) {
            return fail(MenuTransactionFailure.SNAPSHOT_DRIFT);
        }
        if (!step.conservation().matches(observedSnapshot, afterClick)) {
            return fail(MenuTransactionFailure.CONSERVATION_BREACH);
        }
        observedSnapshot = afterClick;
        confirmedClicks++;
        state = confirmedClicks == plan.orderedSteps().size()
                ? MenuTransactionState.VERIFYING
                : MenuTransactionState.APPLYING;
        return true;
    }

    /**
     * 记录已经领取的原版 click 在 dispatch 边界抛出。
     *
     * <p>原版 {@code AbstractContainerMenu.clicked(...)} 可以在修改前抛出，也可以在已经
     * 修改部分状态后由 slot hook、事件或 {@code broadcastChanges()} 抛出。两种情况都不能
     * 假设 click 未发生，更不能把刚读到的 after snapshot 当作 ACK。因此本方法只在
     * {@link MenuTransactionState#ACK} 使用，保留同 Tick 已领取 click 的事实，绝不推进
     * {@code confirmedClicks}，并把事务终结为失败。适配器可以提供一次受限的 after
     * snapshot 供失败诊断/关闭使用；它必须与已确认的 native menu family/containerId 一致，
     * 否则不会覆盖此前的权威 snapshot。
     *
     * <p>返回值固定为 {@code false}，与其他会把事务推进到失败终态的方法保持一致。
     */
    public boolean failAfterClickDispatchException(
            MenuSnapshot observedAfterException, long currentTick) {
        requireState(MenuTransactionState.ACK);
        if (currentTick == lastClickTick
                && observedAfterException != null
                && observedSnapshot != null
                && observedAfterException.sameMenu(observedSnapshot)) {
            observedSnapshot = observedAfterException;
        }
        return fail(MenuTransactionFailure.CLICK_DISPATCH_FAILED);
    }

    /**
     * 在关闭窗口前复核最终布局和当前 stateId。
     */
    public boolean verify(MenuSnapshot finalSnapshot, long currentTick) {
        requireState(MenuTransactionState.VERIFYING);
        if (!withinDeadline(currentTick)) {
            return fail(MenuTransactionFailure.TIMEOUT);
        }
        if (finalSnapshot == null
                || !finalSnapshot.sameMenu(observedSnapshot)) {
            return fail(MenuTransactionFailure.UNEXPECTED_MENU);
        }
        if (finalSnapshot.stateId() < observedSnapshot.stateId()) {
            return fail(MenuTransactionFailure.STALE_STATE);
        }
        if (!plan.finalSnapshot().layoutEqualsIgnoringState(finalSnapshot)) {
            return fail(MenuTransactionFailure.SNAPSHOT_DRIFT);
        }
        observedSnapshot = finalSnapshot;
        state = MenuTransactionState.CLOSING;
        return true;
    }

    /**
     * 只应在适配器已调用原版 {@code closeContainer()} 后确认完成。
     */
    public boolean closeConfirmed(long currentTick) {
        requireState(MenuTransactionState.CLOSING);
        if (!withinDeadline(currentTick)) {
            return fail(MenuTransactionFailure.TIMEOUT);
        }
        state = MenuTransactionState.COMPLETED;
        return true;
    }

    public boolean cancel() {
        if (state.terminal()) {
            return false;
        }
        failure = MenuTransactionFailure.CANCELLED;
        state = MenuTransactionState.CANCELLED;
        return true;
    }

    private boolean acceptBeforeClickSnapshot(
            MenuSnapshot beforeClick, MenuClickStep step) {
        if (beforeClick == null
                || !beforeClick.sameMenu(observedSnapshot)) {
            return fail(MenuTransactionFailure.UNEXPECTED_MENU);
        }
        if (beforeClick.stateId() < observedSnapshot.stateId()) {
            return fail(MenuTransactionFailure.STALE_STATE);
        }
        if (!step.expectedBefore().layoutEqualsIgnoringState(beforeClick)) {
            return fail(MenuTransactionFailure.SNAPSHOT_DRIFT);
        }
        return true;
    }

    private boolean withinDeadline(long currentTick) {
        return currentTick >= openedAtTick
                && currentTick - openedAtTick <= limits.maxTicks();
    }

    private boolean fail(MenuTransactionFailure reason) {
        failure = Objects.requireNonNull(reason, "reason");
        state = MenuTransactionState.FAILED;
        return false;
    }

    private void requireState(MenuTransactionState expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "illegal menu transaction state transition");
        }
    }
}
