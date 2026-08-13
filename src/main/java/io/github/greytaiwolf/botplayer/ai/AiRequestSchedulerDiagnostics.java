package io.github.greytaiwolf.botplayer.ai;

import java.util.List;
import java.util.Objects;

/**
 * 只读的调度器活性/隔离诊断。
 *
 * <p>{@code quarantinedCleanupCount} 包含 cleanup wrapper 的 start-loss 和物理 stall；其中
 * {@code quarantinedPhysicalCleanupCount} 是实际已经开始并仍占住 cleanup lane 的子集。其余
 * {@code quarantined*Count} 表示对应外部工作已经开始且占住固定物理 lane。任一物理隔离或保留
 * recovery 存在时，{@code dispatchDegraded} 会阻止新的 Provider admission，直到该 work 返回且
 * 所有保留的 recovery handoff 已被重新提交；绝不能为保持“逻辑并发”而扩展线程池或把
 * {@link AiRequestSchedulerPolicy#maximumGlobalInFlight()} 误解成可突破的物理 Provider 上限。</p>
 *
 * <p>{@code recentQuarantines} 是有界的最近事件历史；每项保留 request ID、终态原因和隔离
 * 类型，供上层与 {@link AiScheduledRequestHealth} 对照。它不代表当前活动集合，活动数量由
 * 前述计数给出。</p>
 *
 * <p>{@code pendingRecoveryDispatchCount} 非零表示 start watchdog 已使一个 cleanup 或 public
 * publication attempt 失效，但其恢复记录仍有界地保留。生命周期所有者修复可信 supervisor
 * 容量后可调用 {@link AiRequestScheduler#resumeDispatch()}；调度器不会忙等、不会重试 Provider
 * start，也不会把工作回退到 submitter/pump/timer 线程。</p>
 */
public record AiRequestSchedulerDiagnostics(
        boolean dispatchDegraded,
        int pendingCleanupAcknowledgements,
        int quarantinedCleanupCount,
        int quarantinedPhysicalCleanupCount,
        int activeProviderInvocationCount,
        int quarantinedProviderInvocationCount,
        int activeTokenSetupCount,
        int quarantinedTokenSetupCount,
        int activeCompletionDeliveryCount,
        int quarantinedCompletionDeliveryCount,
        long rejectedDispatchCount,
        int pendingRecoveryDispatchCount,
        List<AiRequestSchedulerQuarantine> recentQuarantines) {
    public AiRequestSchedulerDiagnostics {
        if (pendingCleanupAcknowledgements < 0
                || quarantinedCleanupCount < 0
                || quarantinedPhysicalCleanupCount < 0
                || quarantinedPhysicalCleanupCount > quarantinedCleanupCount
                || activeProviderInvocationCount < 0
                || quarantinedProviderInvocationCount < 0
                || quarantinedProviderInvocationCount > activeProviderInvocationCount
                || activeTokenSetupCount < 0
                || quarantinedTokenSetupCount < 0
                || quarantinedTokenSetupCount > activeTokenSetupCount
                || activeCompletionDeliveryCount < 0
                || quarantinedCompletionDeliveryCount < 0
                || quarantinedCompletionDeliveryCount > activeCompletionDeliveryCount
                || rejectedDispatchCount < 0L
                || pendingRecoveryDispatchCount < 0) {
            throw new IllegalArgumentException("scheduler diagnostics cannot be negative");
        }
        recentQuarantines = List.copyOf(Objects.requireNonNull(
                recentQuarantines, "recentQuarantines"));
    }
}
