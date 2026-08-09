package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把一个固定的节点规划器接到现有 P2 action mailbox 的窄桥。
 *
 * <p>规划器可在服务器线程通过受控 port 读取当前 Bot，并返回一条已冻结的原版动作；
 * 这里负责 run identity、deadline、completion→signal 的异步边界和取消。completion
 * 回调只向线程安全 inbox 写不可变 {@link SkillSignal}，绝不读取 Minecraft 对象或推进
 * 节点状态。
 */
public final class ActionBackedSkillNodeHandler
        implements SkillNodeHandler {
    private static final int DEADLINE_GRACE_TICKS = 20;

    private final Thread ownerThread;
    private final OperationPlanner planner;
    private final ActionGateway actions;
    private final SignalSink signals;
    private final Map<UUID, PendingAction> pendingByRun =
            new LinkedHashMap<>();

    public ActionBackedSkillNodeHandler(
            OperationPlanner planner,
            ActionGateway actions,
            SignalSink signals) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.signals = Objects.requireNonNull(signals, "signals");
        ownerThread = Thread.currentThread();
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (pendingByRun.containsKey(context.runId())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "技能节点重复提交了未完成动作");
        }
        Operation operation;
        try {
            operation = planner.plan(context).orElse(null);
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "技能节点在冻结原版动作前检测到世界变化");
        }
        if (operation == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ACTION_REJECTED,
                    "技能节点当前没有可安全执行的原版动作："
                            + context.node().skillId()
                            + "#"
                            + context.nodeIndex());
        }
        UUID actionId = UUID.randomUUID();
        long deadlineTick;
        try {
            deadlineTick = Math.min(
                    Math.subtractExact(context.deadlineTick(), 1L),
                    Math.addExact(
                            context.currentTick(),
                            Math.addExact(operation.maximumTicks(),
                                    DEADLINE_GRACE_TICKS)));
        } catch (ArithmeticException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.TIMEOUT,
                    "技能节点动作截止时间超出安全范围");
        }
        if (deadlineTick <= context.currentTick()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.TIMEOUT,
                    "技能节点已没有足够的动作时间预算");
        }
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                context.botId(),
                context.botGeneration(),
                idempotencyKey(context, operation.operationKey()),
                deadlineTick,
                operation.maximumTicks(),
                operation.action(),
                ActionOrigin.fromSkillRun(context.runId()));
        ActionMailbox.Submission submission;
        try {
            submission = actions.submit(envelope, operation.priority());
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "技能节点动作提交器抛出异常");
        }
        if (submission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            return SkillNodeDirective.fail(
                    submissionFailure(submission.status()),
                    "技能节点动作未入队：" + submission.status().name());
        }
        PendingAction pending = new PendingAction(
                actionId,
                context.botId(),
                context.botGeneration(),
                context.nextStateRevision(),
                operation);
        pendingByRun.put(context.runId(), pending);
        submission.completion().orElseThrow().whenComplete(
                (outcome, throwable) -> offerCompletion(
                        context.runId(), pending, outcome, throwable,
                        context.currentTick()));
        return SkillNodeDirective.waitFor(
                operation.waitingKind(), operation.waitingSummary());
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        PendingAction pending = pendingByRun.get(context.runId());
        if (pending == null
                || !pending.actionId.equals(signal.operationId())
                || signal.type() != SkillSignalType.ACTION) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "技能节点收到不属于当前动作的回执");
        }
        pendingByRun.remove(context.runId(), pending);
        if (signal.status() != SkillSignalStatus.SUCCEEDED) {
            return SkillNodeDirective.fail(
                    signal.failureCode() == SkillFailureCode.NONE
                            ? SkillFailureCode.INTERNAL_ERROR
                            : signal.failureCode(),
                    "技能节点原版动作未成功完成");
        }
        try {
            return pending.operation.successVerifier().verify(context, signal);
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "技能节点动作后置条件无法确认");
        }
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        PendingAction pending = pendingByRun.remove(context.runId());
        if (pending == null) {
            return;
        }
        try {
            actions.cancel(
                    pending.botId,
                    pending.actionId,
                    ActionCancellationReason.REQUESTED);
        } catch (RuntimeException ignored) {
            // 生命周期 action runtime 仍会按 generation 关闭该票据；不能因回调异常
            // 让通用运行时遗留 reservation 或接受迟到结果。
        }
    }

    private void offerCompletion(
            UUID runId,
            PendingAction pending,
            ActionOutcome outcome,
            Throwable throwable,
            long submittedTick) {
        SignalProjection projection = projectOutcome(
                pending.actionId, outcome, throwable, submittedTick);
        signals.offer(new SkillSignal(
                UUID.randomUUID(),
                runId,
                pending.botId,
                pending.generation,
                pending.runRevision,
                pending.actionId,
                SkillSignalType.ACTION,
                projection.status,
                projection.failureCode,
                projection.evidence,
                projection.summary,
                projection.finishedTick));
    }

    private static SignalProjection projectOutcome(
            UUID actionId,
            ActionOutcome outcome,
            Throwable throwable,
            long submittedTick) {
        if (throwable != null
                || outcome == null
                || !actionId.equals(outcome.actionId())) {
            return new SignalProjection(
                    SkillSignalStatus.FAILED,
                    SkillFailureCode.INTERNAL_ERROR,
                    List.of(),
                    "技能节点动作 completion 无法核验",
                    submittedTick);
        }
        return new SignalProjection(
                signalStatus(outcome.state()),
                mapFailure(outcome.failureCode()),
                outcome.evidence(),
                outcome.safeSummary(),
                outcome.finishedTick());
    }

    private static SkillSignalStatus signalStatus(ActionState state) {
        return switch (state) {
            case SUCCEEDED -> SkillSignalStatus.SUCCEEDED;
            case FAILED -> SkillSignalStatus.FAILED;
            case CANCELLED -> SkillSignalStatus.CANCELLED;
            case PREEMPTED -> SkillSignalStatus.PREEMPTED;
            case STALE -> SkillSignalStatus.STALE;
            case QUEUED, VALIDATING, RUNNING, VERIFYING ->
                    throw new IllegalArgumentException(
                            "action completion must be terminal");
        };
    }

    private static SkillFailureCode mapFailure(ActionFailureCode code) {
        return switch (code) {
            case NONE -> SkillFailureCode.NONE;
            case BOT_NOT_ACTIVE -> SkillFailureCode.BOT_NOT_ACTIVE;
            case STALE_GENERATION -> SkillFailureCode.STALE_GENERATION;
            case DEADLINE_EXCEEDED, MAX_TICKS_EXCEEDED ->
                    SkillFailureCode.TIMEOUT;
            case LEDGER_CAPACITY_EXCEEDED,
                    ACTION_ALIAS_CAPACITY_EXCEEDED,
                    RUNTIME_CAPACITY_EXCEEDED,
                    CHANNEL_BUSY -> SkillFailureCode.SERVER_OVERLOADED;
            case PRECONDITION_FAILED -> SkillFailureCode.WORLD_CHANGED;
            case UNSAFE_CONTROL_STATE -> SkillFailureCode.UNSAFE_CONTROL_STATE;
            case PERMISSION_DENIED -> SkillFailureCode.PERMISSION_DENIED;
            case TARGET_UNAVAILABLE -> SkillFailureCode.TARGET_GONE;
            case CANCELLED, PREEMPTED -> SkillFailureCode.DANGER_PREEMPTED;
            case INTERNAL_ERROR, BACKEND_RESULT_MISMATCH ->
                    SkillFailureCode.INTERNAL_ERROR;
            case INVALID_REQUEST,
                    DUPLICATE_IN_PROGRESS,
                    IDEMPOTENCY_CONFLICT,
                    UNSUPPORTED -> SkillFailureCode.ACTION_FAILED;
        };
    }

    private static SkillFailureCode submissionFailure(
            ActionMailbox.SubmissionStatus status) {
        return switch (status) {
            case BOT_GENERATION_CLOSED -> SkillFailureCode.STALE_GENERATION;
            case MAILBOX_FULL, COMPLETION_BACKPRESSURE ->
                    SkillFailureCode.SERVER_OVERLOADED;
            case RUNTIME_CLOSED -> SkillFailureCode.RUNTIME_CLOSED;
            case ENQUEUED -> throw new IllegalArgumentException(
                    "enqueued status is not a rejection");
        };
    }

    private static String idempotencyKey(
            SkillNodeContext context, String operationKey) {
        /*
         * ActionEnvelope 对幂等键有 128 字符的硬上限。runId 与 nodeId 已经足以
         * 把一次节点动作和同一 run 内的重试隔离；把 revision 再拼进去会在两个 UUID
         * 都取满时越界，导致本应安全拒绝的动作在提交点抛异常。
         */
        return "skill:"
                + context.runId()
                + ":"
                + context.node().nodeId()
                + ":"
                + operationKey;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "action-backed skill handler requires server thread");
        }
    }

    /** 只允许规划器交出一条已冻结、有限的真实动作。 */
    @FunctionalInterface
    public interface OperationPlanner {
        Optional<Operation> plan(SkillNodeContext context);
    }

    public interface ActionGateway {
        ActionMailbox.Submission submit(
                ActionEnvelope envelope, ActionPriority priority);

        void cancel(
                UUID botId,
                UUID actionId,
                ActionCancellationReason reason);
    }

    @FunctionalInterface
    public interface SignalSink {
        SkillSignalInbox.OfferStatus offer(SkillSignal signal);
    }

    @FunctionalInterface
    public interface SuccessVerifier {
        SkillNodeDirective verify(
                SkillNodeContext context, SkillSignal signal);
    }

    /** 单节点只允许一个等待动作，避免 stateId、输入 owner 或回执交叉。 */
    public record Operation(
            String operationKey,
            ActionRequest action,
            ActionPriority priority,
            int maximumTicks,
            SkillNodeDirective.Kind waitingKind,
            String waitingSummary,
            SuccessVerifier successVerifier) {
        public Operation {
            Objects.requireNonNull(operationKey, "operationKey");
            if (!operationKey.matches("[a-z][a-z0-9_-]{0,31}")) {
                throw new IllegalArgumentException(
                        "operationKey must be a short safe identifier");
            }
            action = Objects.requireNonNull(action, "action");
            priority = Objects.requireNonNull(priority, "priority");
            if (maximumTicks < 1
                    || maximumTicks > ActionEnvelope.MAX_ACTION_TICKS) {
                throw new IllegalArgumentException(
                        "maximumTicks is outside ActionEnvelope bounds");
            }
            waitingKind = Objects.requireNonNull(waitingKind, "waitingKind");
            if (!waitingKind.isWaiting()) {
                throw new IllegalArgumentException(
                        "operation must enter one waiting state");
            }
            waitingSummary = requireSummary(waitingSummary);
            successVerifier = Objects.requireNonNull(
                    successVerifier, "successVerifier");
        }
    }

    private record PendingAction(
            UUID actionId,
            UUID botId,
            long generation,
            long runRevision,
            Operation operation) {
        private PendingAction {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0L || runRevision < 1L) {
                throw new IllegalArgumentException(
                        "pending action identity is invalid");
            }
            Objects.requireNonNull(operation, "operation");
        }
    }

    private record SignalProjection(
            SkillSignalStatus status,
            SkillFailureCode failureCode,
            List<io.github.greytaiwolf.botplayer.action.ActionEvidence> evidence,
            String summary,
            long finishedTick) {
        private SignalProjection {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(failureCode, "failureCode");
            evidence = List.copyOf(Objects.requireNonNull(
                    evidence, "evidence"));
            summary = requireSummary(summary);
            if (finishedTick < 0L) {
                throw new IllegalArgumentException(
                        "finishedTick must be non-negative");
            }
        }
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "summary");
        if (value.isBlank()
                || value.length() > SkillNodeDirective.MAX_SUMMARY_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "summary must be a trimmed non-blank safe string");
        }
        return value;
    }
}
