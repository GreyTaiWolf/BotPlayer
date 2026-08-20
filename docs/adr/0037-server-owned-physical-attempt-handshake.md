# ADR-0037：服务器拥有的跨边界物理尝试握手

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0020、ADR-0021、ADR-0022、ADR-0024、ADR-0031、ADR-0032、ADR-0034

## 背景

ADR-0031/0032/0034 已定义单进程 retry delegate 边界的 token reservation：每个可能实际触发
Provider 的 retry 先 reserve，只有在真实调用前 settle 成功才成为不可退款的 committed accounting。
但 client-sponsored credential 的真实 Provider/HTTP 必须在物理客户端执行；服务端既不能把一条
C2S “我会开始”当作 HTTP 已启动，也不能因为包重复、丢失、断线、rebind、过期或本地队列延迟而
重复授予同一笔 physical start。

在尚未实现 R1 之外的通用 network/session/lifecycle bridge 时，需要一个小的、可单元测试的
跨边界 Contract。它必须让服务端保留预算与生命周期权威，客户端只持有一次性、本地 fenced 的
开始能力；它不能借此把 DTO 误称为 packet、计费、Provider 成功或 AI→世界执行。

## 决策

### 1. 服务端 owner 维护有界 `offer → ACK → start grant` 状态机

新增 owner-thread `AiPhysicalAttemptBudgetCoordinator`。可信 future bridge 只能以精确、当前的
`AiClientRequestDispatch` 和已接受的 `AiRetryAttemptBudgetContext` 调用：

```text
offer (reserve fresh token)
  -> OFFERED
  -> exact C2S prepare ACK
  -> ledger.settleAttempt(exact reservation)
  -> START_GRANTED
  -> close/tombstone
```

offer 不会 settle，不会启动 Provider/HTTP，也不会发送 packet。ACK 是唯一可调用
`settleAttempt(...)` 的 transition；exact duplicate ACK 只返回同一 immutable grant，不再次 settle。
attempt ID 由 server 选择，活跃 attempt、tombstone 和候选 ID 数量均有硬上限。所有 receipt、attempt ID、
nonce 或 deadline drift 都拒绝且不修改 reservation/accounting。

`START_GRANTED` 的含义仅为“服务器已为一个**可能**的 physical start 做出不可退款预算承诺”。它不是
客户端已收到 grant、已进入本地队列、已调用 Provider、HTTP 已发出、远端已计费、收到 response 或完成任务的
事实证据。

### 2. 每个 grant 使用完整、不可延长的精确 identity

`AiPhysicalAttemptIdentity` 绑定：

```text
serverInstanceId
+ ownerId
+ complete safe AiRequestDispatchReceipt
+ server-chosen attemptId
+ dispatch nonce
+ clientNotAfterEpochMillis
+ physicalStartNotAfterEpochMillis
```

`ownerId` 与 receipt 都不能由短 request ID 替代。identity 只包含必要的 correlation 与 deadline；普通
`toString()` 不输出 owner、request、nonce、attempt ID、prompt、credential、token 或 Provider/HTTP data。

`physicalStartNotAfterEpochMillis` 从实际 `AiTokenReservation.expiresAt()` 保守传递，因而已经同时受
upstream deadline、retry attempt deadline 和 ledger TTL 的最早边界约束，且不得晚于 client not-after。
ACK、periodic reaper 和客户端 physical-start claim 都在该 half-open 边界拒绝；不得把较宽的 client
not-after 当作可延长的 start permission。

future bridge 仍必须在 ACK 前验证 sender 是当前 authenticated owner/session、dispatch 仍是 server-side
active binding，且其 authoritative tick/generation/replacement/terminal fences 尚未失效。本 Contract 不把
epoch wall-clock identity 变成 session 授权，也不读取或替代 `expiresAtTick`。

### 3. 预提交可释放；已提交永不退款并可回收 slot

pre-commit OFFERED close 或到期会对同一 exact reservation 调用 `release(...)`，随后写 tombstone。settle
失败同样必须 tombstone 并清理未开始 reservation；没有 grant。

只要 `SETTLED` 已发生，disconnect、terminal、grant 丢失、client close、重复 ACK 或 reaper 都**不得**调用
release/refund。它们只移除活跃记录并写 tombstone。可信 server lifecycle/session maintenance 必须定期在
owner thread 调用：

```java
coordinator.expireDueAttempts();
coordinator.expireTombstones();
```

前者在 reservation deadline 释放未开始 entry，在 physical-start deadline tombstone 已 granted entry，避免丢失
grant 永久耗尽 active capacity；后者只在 client not-after 之后移除 replay tombstone。`closeExact(...)` 与
`closeAll()` 同样保持“未开始释放、已提交不退款”。本阶段没有 production lifecycle 调用者，故这些方法是
future bridge 的明确维护义务，而不是已完成的 disconnect/terminal 接线。

### 4. 客户端只 handoff 一次，并用原子 claim 线性化实际 start

`AiPhysicalAttemptClientGrantGate` 仅在本地 dispatch 完整匹配 offer、binding epoch、session 和 clock fence
当前有效时接受 exact grant。它至多向 injected handoff 交付一次 `LocalStartLease`；重放、同步重入和抛出
handoff 都不能产生第二次 handoff。

future client bridge 在真正 Provider/HTTP start 的紧邻边界必须调用：

```java
if (lease.tryClaimPhysicalStart()) {
    // exactly one immediate local Provider/HTTP start
}
```

`tryClaimPhysicalStart()` 在同一 lock 内重新验证 exact identity、binding epoch、local session、client deadline 和
physical-start deadline，并一次性消费 capability。close 先胜则任何未 claim lease 都不能启动；claim 先胜即是
本地不可撤销 physical-start linearization，之后 close 不会伪造 refund 或允许第二次 claim。该 API 不发送
任何 terminal state，也不允许 caller 请求另一 grant。

客户端 wall clock 回退、负时间、client-not-after 到期和 physical-start-not-after 到期均永久 latch 为
fail-closed；即使 duplicate grant 已经是 `ALREADY_HANDED_OFF`，后续观测到的到期/rollback 也会阻止尚未
claim 的 queued handoff。

### 5. 本阶段明确不接线的内容

本 ADR 不实现或调用：

- R1 之外的 S2C/C2S payload、codec、handler、authenticated sender/session lookup、active dispatch/tick
  authority、disconnect/terminal/replacement hook 或 scheduler；
- `ClientAiRequestSessionController`、真实 client queue、Provider、DeepSeek HTTP、credential、retry wrapper
  integration、response/usage/billing reconciliation 或真实计费；
- Skill、Action、Technique、lifecycle、Minecraft world read/write、construction、Tool execution 或 AI→世界执行。

因此 DTO/gate 的存在不代表 generic client-sponsored bridge、聊天、真实 Provider 调用或 P6 总退出门已经完成。

## 被否决方案

### 方案 A：客户端收到 offer 时立即开始 HTTP，或把 ACK 当作已开始事实

否决。网络重复/丢失和本地队列延迟会让服务端无法精确决定 budget 是否应 settle；ACK 只能证明客户端准备接收
一次 grant，不是 HTTP start evidence。

### 方案 B：只用 request ID、nonce 或较宽的 client deadline 做关联

否决。它无法防止 owner/session drift、server instance collision、重复 attempt 或较早 reservation deadline
之后的 delayed start。完整 identity 与不可延长 physical-start deadline 是最小 fail-closed fence。

### 方案 C：grant 后在丢包/断线/过期时 refund

否决。服务器无法区分“客户端尚未收到”与“HTTP 已实际开始但 terminal 丢失”。settle 后退款会把真实 remote
调用变成可重复免费调用。

### 方案 D：暴露可重复的 `isCurrent()` 供排队任务在稍后检查

否决。观察与 Provider start 之间存在 close/rebind/并发 TOCTOU，同一 lease 也可能被两个任务使用。必须由
一次性、锁内 `tryClaimPhysicalStart()` 取得明确胜者。

## 兼容性、性能、安全与许可证影响

- 兼容性：仅新增 `ai/` 内纯 Java coordinator、identity、DTO 与 client-local gate；既有 `AiProvider` SPI、
  `RetryingAiProvider.completeBudgeted(...)`、Scheduler、network、lifecycle 与 client controller 均不改；
- 性能：每 owner coordinator 只保存有界 active/tombstone maps；每次 ACK/close/reaper 线性扫描至多
  `MAX_ACTIVE_ATTEMPTS`，client gate 只使用一把短时本地 lock；
- 安全：server owns reservation/settlement/close；client supplied/drifted identity 不能变更账本，deadline/rollback
  默认拒绝，diagnostic 不泄露 correlation 或 secret；
- 许可证：只使用 JDK 和既有 BotPlayer 值对象，不新增依赖、协议或外部服务。

## 迁移和回滚

future bridge 必须先单独设计和测试 transport codec、authenticated exact session lookup、server tick expiry、
disconnect/terminal/replacement cleanup、periodic reaper 和 client queue handoff，并在 Provider/HTTP start 的唯一
实际调用点紧邻 `tryClaimPhysicalStart()`。不得从 prompt、全局 map、ThreadLocal 或部分 request ID 重建 identity。

若以后需要 server-authoritative monotonic/network time、usage reconciliation、retries after a settled physical
start 或 multiple providers，必须新增 ADR 和版本化 Contract，不能改变当前 grant 的 no-refund/one-claim 语义。

回滚只删除未接线的 pure Java Contract 与测试；它不迁移 payload、SavedData、credential、world 或真实账本。

## 验证方式

- 纯 Java 回归覆盖 offer/ACK/duplicate grant、receipt/attempt/nonce/owner drift、pre-commit close/expiry、
  settlement failure、grant 丢失/断线 no-refund、committed reaper capacity reclaim、tombstone/attempt-ID bounds、
  server/client clock rollback、earlier physical deadline、duplicate handoff/reentry/throw、close/rebind 和并发
  `tryClaimPhysicalStart()` 恰一胜者；
- 静态边界检查确认 coordinator/gate 不依赖 Minecraft、Provider、HTTP、Scheduler 或现有 client session
  controller，也不把 DTO diagnostics 变成 secret/correlation log；
- 对应提交仍必须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`。本 Contract 没有 Minecraft
  production behavior；真实联机 packet、丢包/乱序、owner disconnect/reconnect、client queue/clock、Provider HTTP、
  billing/usage 和 end-to-end P6 验收仍须后续实现与实机验证。
