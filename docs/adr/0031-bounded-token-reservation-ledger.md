# ADR-0031：有界 AI token 预留账本合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0020、ADR-0022、ADR-0024、ADR-0028

## 背景

现有 `AiModelPolicy` 会从可信的 `ContextBudget` 输入估算和最大输出得出
`AiModelAdmission`，但它本身不保存一次尚未开始的远端调用、已开始调用或累计额度。若调用
方把 Provider 回传的 `AiTokenUsage` 当作唯一计费事实，则空 usage、低报 usage、超时、取消和
同步 HTTP 失败都可能把已经发出的请求错误退款。

此外，`RetryingAiProvider` 会用同一个 logical `requestId` 多次调用其 delegate。一次 scheduler
提交或 executor handoff 不是一次物理 Provider 调用，不能只按 requestId 预留或结算一次。

这不能由 ADR-0022/0024 的 client-sponsored binding ledger 解决：后者是 server-owner-thread 的
session correlation/teardown 边界，不是 Provider lane 可用的 token 账户，也不得承担真实调用前
的结算线性化点。

## 决策

### 1. 每个 owner/bot/agent 使用一个独立、非持久化的账本

新增纯 Java 的 `AiTokenBudgetScope(ownerId, botId, agentId)` 与
`AiTokenBudgetLedger`。scope 只能来自已有的可信生命周期绑定；一个 ledger 只管理这一个
scope，不能跨 bot/agent 合并，也不能当作 credential binding、ACL、client session receipt 或持久
计费记录。

未来 lifecycle adapter 对同一精确 scope 最多持有一个 live ledger；跨 scope registry、跨 bot 总额和
持久化配额刻意不在 A0 内，不能用一个无界全局 map 偷渡实现。

每次 reserve 输入一个最小 `AiTokenBudgetRequestBinding`：

```text
scope + requestId + revision
```

它不携带 prompt、model、response、nonce、credential、network payload、Minecraft 对象或 Provider
handle。账本要求 binding 的 scope 与自身完整相等，但不自行验证 owner 仍在线、session gate、
server instance 或世界 revision；这些仍是未来 lifecycle/bridge 的职责。

### 2. 使用 conservative admission 总量，并冻结为不透明 reservation

`reserve(binding, admission, expiresAt)` 仅接受 `admission.accepted() == true`，并冻结：

```text
随机 reservationId + request binding
+ estimatedInputTokens + reservedOutputTokens + reservedTotalTokens
+ reservedAt + expiresAt
```

`AiModelAdmission` 本身没有 request identity 且公开可构造，所以它不是授权 capability；调用方必须在
同一可信路径先完成 model-policy admission。拒绝 admission 即使携带 token 数也绝不改变账本。

reservation 不以 requestId 唯一。每个未来物理 Provider attempt 都必须取得一个新的
`reservationId`，即使它们复用同一个 requestId。账本只接受**同一对象实例**的 release/settle；
复制、漂移或旧 reservation 不能影响新的 live reservation。

### 3. 上限、状态机与不退款规则

`AiTokenBudgetPolicy` 对一个 scope 限制：

- `maximumTokens`：`committedTokens + reservedTokens` 的累计上限；
- `maximumActiveReservations`：尚未 settle 的 reservation 数上限；
- `maximumReservationAge`：每个 reservation 的硬 TTL 上限。

状态机为：

```text
reserve accepted -> RESERVED          reserved += total
release before remote attempt -> RELEASED / reserved -= total
expiry at now >= expiresAt -> EXPIRED / reserved -= total
settle immediately before remote call -> SETTLED / reserved -= total; committed += total
```

`SETTLED` 是唯一允许调用真实 Provider 的线性化结果。此后不论响应成功、HTTP 同步抛错、
Provider 失败、取消、超时、回调丢失，或 `AiTokenUsage` 为零/缺失/更低，都**绝不退款或按 usage
校正**。settled 条目立即从活动表移除，只保留有界累计数，避免用终态 map 无限保存 request。

`close()` 只释放尚未 settle 的 reservation，保留 committed 诊断并关闭后续 reserve；它不是
ADR-0022/0024 session 的 close，也不取消 HTTP、Scheduler 或 client work。

### 4. 并发、时间与诊断

账本使用一把私有 lock；不会在 lock 内调用 Clock、UUID supplier、Provider、Scheduler、network、
callback 或 Minecraft。`settleAttempt`、`release`、expiry 和 `close` 对同一 exact reservation 只能有
一个终态胜出，其他调用返回 no-op/fail-closed status。

TTL 为半开区间：`now >= expiresAt` 时过期。时钟早于此前观测值或无效 expiration 都不得倒退或
改变 accounting；需要当前时间的读取/expiry 抛出失败，reserve/settle 返回 `CLOCK_ROLLBACK` 或
`INVALID_EXPIRATION`。若 `now + maximumReservationAge` 超过 `Instant.MAX`，仍可安全接受一个更早、
可表示且晚于 `now` 的 expiration，因为它必然在概念 TTL 内；不得把表示范围错误地当作额度退款。
普通 `toString()` 与 snapshot 不显示 owner/bot/agent、
requestId、reservationId、prompt、credential、model、response 或 usage。

### 5. 本阶段明确不接线的内容

本 ADR 不修改或调用：

- `AiRequestScheduler`、`AiRequestSchedulerSupervisor`、`RetryingAiProvider`、`AiProvider` 或
  `CancellationToken`；
- client credential/DeepSeek/HTTP、R1、`ai.transport` 的 gate/coordinator/ledger、network 或
  lifecycle；
- chat、Tool→Skill、Technique、Action、世界写入或任何 Minecraft API。

下一阶段必须在每次实际 `delegate.complete(...)` 前紧邻地插入新的 reserve/settle hook，并把
Scheduler deadline、client/session deadline 与 policy TTL 取最早值。该 hook、generic bridge、聊天、
模型策略接线、AI→Skill/world 执行、真实 Provider 计费和 E2E 都仍未实现。

## 被否决方案

### 方案 A：用 `AiTokenUsage` 成功后对账或退款

否决。usage 可能缺失、低报、缓存相关或根本无法在远端请求已发出但本地失败时得到；它不是
远端副作用未发生的证明。

### 方案 B：每个 logical `requestId` 只预留一次

否决。retry 会复用 requestId 但产生多个物理调用；这样会让第二次调用绕过额度。

### 方案 C：复用 client-sponsored session ledger

否决。它受 server owner thread 约束，管理完整 gate correlation/teardown，不在 Provider lane 的
物理调用边界，也不能成为第二个会话 gate。

### 方案 D：settle 后按取消或 Provider error 退款

否决。远端可能已经计费或执行；退款会让后续调用穿透总额度。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `ai/` 纯 Java DTO/账本，不改 payload、R1、credential 文件或现有 scheduler API；
- 性能：单 scope 至多 128 个活动 reservation，操作在一把私有 lock 内为常数或小表 TTL 扫描；
- 安全：scope 不跨 bot/agent 合并；复制/旧 token 无权修改 live reservation；诊断不泄露身份或
  内容；
- 许可证：只使用 JDK，不引入依赖。

## 迁移和回滚

当前没有生产调用点。未来 adapter 可为每个绑定 scope 创建 ledger，并在其精确物理 attempt hook
处使用；不得把它写入世界、SavedData、client binding 或 credential storage。回滚时停止创建该
ledger；不存在要退款的外部副作用，也不触及现有 session gate 或 P5/world。

## 验证方式

- 纯 Java：accepted/rejected admission、scope drift、输入/输出/总量冻结、累计/活动上限、exact
  identity、release/expiry/close、settle 后不退款、同 requestId 的独立 retry reservation、clock rollback/
  overflow、settle-vs-release/expiry 并发与诊断脱敏；
- Java 21 自动门：本 ADR 对应提交仍须通过 GitHub Actions 的 `clean build`、JUnit 和
  `runGameTestServer`。本地缺少可下载 Gradle 或 Java 21 时不得把静态检查写成自动通过；
- 后续集成：真实 Provider retry、client/scheduler/lifecycle deadline、owner disconnect、generic bridge
  和真实 client/Provider E2E 必须单独验证；在此之前不得宣称 P6 预算统计、真实计费、通用 bridge
  或 AI 世界执行已经完成。
