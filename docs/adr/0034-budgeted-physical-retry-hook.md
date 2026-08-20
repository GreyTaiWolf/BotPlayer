# ADR-0034：受限的物理重试预算调用点

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0020、ADR-0031、ADR-0032

## 背景

ADR-0031 已固定 scope-local token reservation 的“不退款”会计语义，ADR-0032 已固定一项
可信 `AiRetryAttemptBudgetContext`：每个 physical retry attempt 要用新的 reservation，并取
upstream deadline、retry request deadline 与 ledger TTL 的最早值。

但在此前代码中，`RetryingAiProvider.RequestRun.startAttempt()` 仍直接调用
`delegate.complete(...)`。`AiRequestScheduler` 的一次 logical provider-start 不等于一次 remote
调用：retry 会复用同一 requestId 但多次调用 delegate。因此不能在 scheduler、requestId 表或
`AiProvider` 的普通 SPI 上猜测预算 scope。

另一方面，现有 `AiProvider.complete(AiRequest, CancellationToken)` 没有 owner/bot/agent/revision、
已接受 admission 或 upstream deadline。把账本强塞入该 SPI 会让所有既有调用被错误地宣称已计费，
或迫使 wrapper 从 ThreadLocal、全局 map 或 prompt 推断权威身份。

## 决策

### 1. 只新增显式 budgeted entry point

`RetryingAiProvider` 新增：

```java
CompletionStage<AiResponse> completeBudgeted(
    AiRequest request,
    CancellationToken token,
    AiRetryAttemptBudgetContext context)
```

它不修改 `AiProvider` SPI。既有 `complete(request, token)` 保持完全 unbudgeted；只有未来可信
bridge 显式传入 context 才会进入本路径。入口在占用 request slot、预留 token 或调用 delegate 前要求
`context.binding().requestId()` 与 `request.requestId()` 精确相等；不等直接以安全
`INVALID_REQUEST` 失败。

此入口也不是 generic bridge：本仓库当前没有 production client/session/scheduler 构造该 context。

### 2. 每个 physical delegate 调用的唯一顺序

每个 `RequestRun` retry 都按下列顺序处理：

```text
initial cancel/deadline check
-> reservePhysicalAttempt(retry request deadline) [run lock 外]
-> final cancel/deadline + circuit admission + permit-expiry check [同一 run lock]
-> exact-current circuit permit + ledger.settleAttempt(exact reservation)
   [同一 run lock 内的 breaker 原子 local-accounting gate]
-> SETTLED 后 unlock
-> exactly one delegate.complete(...)
```

最终检查仍持有 `RequestRun` lock；随后按 `RequestRun → AiCircuitBreaker → AiTokenBudgetLedger`
顺序进入 breaker 的受限原子 gate。该 gate 先应用 lease expiry、验证 exact current permit，才在 breaker
monitor 内运行**仅限有界本地 accounting** 的 `ledger.settleAttempt(...)`：它不得调用 Provider、scheduler、
callback、Minecraft 或重入 breaker。若 observer expiry 先胜，gate 不会运行 ledger settle，也不会调用
delegate；若 ledger gate 返回失败或抛错，exact unstarted permit 在同一 breaker monitor 内无 outcome 回收。

这样 cancellation/timeout 若先取得 `RequestRun` lock，reservation 必须 release 且没有 remote call；若 atomic
gate 的 `SETTLED` 先取得胜利，它就是 circuit-side logical physical-start linearization，health 已以 physical
attempt 更新，随后 cancel/timeout、sync throw、null stage、callback attachment failure、response/error 或
usage 都不得 release/refund，并且仍必须尝试恰好一次 delegate 调用。delegate 本身始终在 lock 外调用；之后的
lease expiry 属于已经开始的 attempt，不能撤销这一次已线性化的 delegate 调用。

未开始 reservation 后，circuit admission 若拒绝、deadline/permit 已失效、cancel 先胜、settle 失败或
账本异常，都会 release reservation；已取得但从未进入 atomic gate 的 current circuit permit 以专用
`abandonUnstartedPermit(...)` 无 outcome 归还，避免本地预算门或本地 lease fence 自行制造 Provider failure。

### 3. 本地账本拒绝只终止此 logical request

账本结果投影到既有脱敏 `AiProviderException` 表面：过期为 `TIMEOUT`，额度/活动 reservation
耗尽为 `OVERLOADED`，admission/scope 无效为 `INVALID_REQUEST`，closed/clock/id 或未知账本边界为
`UNAVAILABLE`。这些是本地 budget gate 的稳定兼容映射，不表示 remote Provider 返回了同名故障。
它们不会进入 retry policy，也不会**直接**降级 Provider health；首次失败记录为
zero-physical-attempt `REJECTED`，已完成的 retry 后失败保留先前 physical attempt 次数。独立的 circuit
lease expiry 若先胜会保留其保守 `TIMEOUT` 诊断，但 atomic gate 会在这种情况下阻止本次 settle 与 delegate。

### 4. 明确不接线的内容

本 ADR 不修改 `AiRequestScheduler`、`AiProvider` SPI、DeepSeek/HTTP、client session、network、
Lifecycle、R1、聊天、模型策略、真实 usage reconciliation、billing、Tool/Skill/Action 或 Minecraft。
它只使一个**显式调用者**能够在真实 `RetryingAiProvider` delegate 边界使用已构造的 context；没有该
调用者就不会发生预算计费路径。

此外，`upstreamDeadline` 在本阶段是 reserve/physical-start fence：它在 reserve 前、reserve 后和 settle
前均会被检查；一旦 `SETTLED`，仍由既有 `AiRequest.options().timeoutMillis()` deadline future 管理
in-flight delegate。future bridge 若把 upstream 作为 session hard deadline，必须另行调度 active delegate
cancel，且不能退款已 committed attempt。

## 被否决方案

### 方案 A：在 scheduler logical start 一次性 settle

否决。一个 logical request 可以产生多个 physical retry，少结算后续调用。

### 方案 B：修改普通 `AiProvider` SPI 或从 requestId 推导 context

否决。SPI 缺少权威 identity/admission/deadline；全局 map、ThreadLocal 与 prompt 反推会把漂移和
重入变成计费授权路径，并把普通调用误标为已预算。

### 方案 C：先 settle、再尝试获取 circuit permit

否决。circuit 拒绝将造成已 committed token 却没有 remote call。必须 reserve → admit → settle。

## 兼容性、安全与性能

- 兼容性：普通 `AiProvider` 调用、scheduler 和 client 入口不变；新增 concrete overload 是 opt-in；
- 安全：binding mismatch、closed/expired/rejected ledger 均 fail-closed，普通 diagnostics 不打印
  scope、request、reservation、prompt、credential 或 response；
- 性能：每个 real attempt 增加一次有界 ledger 操作；最终短临界区只含 circuit/ledger accounting，绝不含
  delegate 或 callback；
- 许可证：只使用 JDK，不新增依赖。

## 验证与剩余工作

纯 Java 回归覆盖：每 retry 新 reservation、额度耗尽不产生第二次 physical call、binding mismatch、
closed ledger、reserve 前取消 release、upstream deadline 在 reserve/settle 间到期、settle 后 cancel、
sync throw、null stage 和 callback attachment failure 均不退款，以及 legacy unbudgeted path。

该提交仍须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`。真实 Provider retry、client/
scheduler/lifecycle deadline、owner disconnect、generic bridge、usage/billing reconciliation 与
client/Provider E2E 仍需单独实现和验证；P6 总退出门未关闭。
