# ADR-0032：物理重试尝试预算上下文合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0020、ADR-0022、ADR-0024、ADR-0028、ADR-0031

## 背景

ADR-0031 已把单一 `(ownerId, botId, agentId)` scope 的 token reservation、调用前 settle 与
settle 后永不退款固定为纯 Java 账本。它刻意没有接入 `AiRequestScheduler` 或
`RetryingAiProvider`。

这一区分很重要：scheduler 的一次 provider-start commit 只说明 logical wrapper 可以开始，
但 `RetryingAiProvider` 的每个 retry 才会再次物理调用 `delegate.complete(...)`。现有
`AiProvider.complete(AiRequest, CancellationToken)` 也不携带可信 owner/bot/agent/revision/admission
或 client/session deadline，不能从 `requestId`、ThreadLocal、全局 map 或 prompt 反推预算 scope。

在真正改 retry 调用点前，需要一个窄而可审计的输入合同，保证未来 adapter 不会遗漏多个
deadline 的最早边界，或把同一个 logical request 的 reservation 误用于多个 physical attempt。

## 决策

### 1. 单一 logical request 使用显式、不可变的可信上下文

新增：

```text
AiRetryAttemptBudgetContext(
  AiTokenBudgetLedger ledger,
  AiTokenBudgetRequestBinding binding,
  AiModelAdmission admission,
  Instant upstreamDeadline
)
```

未来可信 bridge 必须从其已权威的 owner/bot/agent/session request 建立 `binding` 和
`upstreamDeadline`；它不能由 `AiRequest`、requestId、模型文本、凭据或线程局部状态猜测。
context 不是 credential、session receipt、Provider handle 或执行授权；它不授予 client、Skill、Action
或 Minecraft 世界操作权限。

### 2. 每个 physical attempt 必须重新保留并取三个期限的最早值

`reservePhysicalAttempt(attemptDeadline)` 由未来 retry wrapper 在每次实际尝试前调用：

```text
effective deadline = min(upstreamDeadline, attemptDeadline)
effective expiration = min(effective deadline, ledger maximumReservationAge)
```

ledger 新增 `reserveForDeadline(...)`，用自身 Clock 和 policy 原子推导最后一个 TTL，不让 adapter
猜测或延长 reservation。若 deadline 已到、admission/scope 不匹配、账本关闭、额度/活动数耗尽、
UUID 候选耗尽或 clock rollback，返回既有 fail-closed status 且不产生 physical Provider 调用。
并发调用即使因 Clock 在 lock 外采样而按相反顺序取得 lock，也只会保守得到
`CLOCK_ROLLBACK`，不会双花或延长 reservation。

每次 `reservePhysicalAttempt` 都要求账本给出新的 exact reservation instance；即使 requestId/revision 相同，
retry 也不得复用旧 reservation。未来实际 hook 仍必须在锁外协调取消与开始：取消在 settle 前胜出时
release；仅当 `settleAttempt(...) == SETTLED` 后才紧邻地调用一次 remote delegate；settle 后同步 throw、
response/error/cancel/timeout/usage 都不得退款。

### 3. 本 ADR 仍不接入真实调用点

本阶段**不**修改 `AiRequestScheduler`、`RetryingAiProvider`、`AiProvider`、HTTP/client session、
network、Lifecycle、R1、Tool/Skill/Action 或 Minecraft。它只提供 future retry hook 的完整输入与
deadline/TTL 算法；尚未有生产 bridge 创建 context，也没有真实 delegate 调用使用它。

## 被否决方案

### 方案 A：在 scheduler 提交时只 reserve/settle 一次

否决。一次 logical scheduler start 可能触发多次 physical retry，且 scheduler 当前未把完整可信 scope
传给 Provider wrapper；按 requestId 一次计费会漏掉后续 remote attempt。

### 方案 B：让 retry provider 从 `AiRequest` 或全局表推导 scope

否决。`AiRequest` 不包含 owner/bot/agent/revision/admission；全局可变表和 ThreadLocal 会把 session
漂移、替换、重入和生命周期泄漏变成预算授权路径。

### 方案 C：由调用者手工计算 TTL

否决。Clock、policy 与边界溢出必须由账本统一控制；分散计算会造成 reservation 超过真实 deadline
或 policy 年龄。

## 兼容性、性能、安全与许可证影响

- 兼容性：仅新增 `ai/` 纯 Java context 与 ledger helper，不改现有 `AiProvider` SPI、payload、credential
  文件、scheduler 或 retry 行为；
- 性能：每次预留仍受 ADR-0031 的单 scope 有界活动表和 lock 约束；context 无线程、队列或全局 registry；
- 安全：普通诊断不输出 owner/bot/agent/request ID；deadline 只能缩短，不能绕过账本 admission、scope
  或总额上限；
- 许可证：只使用 JDK，不引入依赖。

## 迁移和回滚

未来先由可信 client-sponsored bridge 在已有精确 request binding 上构造 context，再以独立变更把它接到
`RetryingAiProvider.RequestRun.startAttempt()` 的真实 delegate 边界。该变更必须验证 cancellation、timeout、
sync throw、null stage、callback failure、retry、ledger close 与 expiry 的胜负次序。

回滚本 ADR 的代码只会移除未接线 helper/context；不会撤销外部 Provider 调用、写入 session、world 或
credential storage。

## 验证方式

- 纯 Java 测试来源覆盖 upstream deadline、retry deadline、ledger TTL 的最早值，近 `Instant.MAX`
  的有界 expiry、每 retry 新 reservation、settle 后累计和 rejected admission；
- 静态边界检查应确认新类型不引用 Provider/Scheduler/retry/client/network/lifecycle/Skill/Action/Minecraft；
- 对应提交仍必须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`；真实 Provider retry、client/
  scheduler/lifecycle deadline、owner disconnect 和 E2E 另行验证。在这些门完成前，不得宣称 P6 预算、
  billing、generic bridge、聊天或 AI→世界执行已经完成。
