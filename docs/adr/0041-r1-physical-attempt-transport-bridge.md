# ADR-0041：R1 物理尝试的有界传输桥接

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0019、ADR-0021、ADR-0031、ADR-0037

## 背景

ADR-0037 已定义纯 Java 的 server-owned `offer → exact ACK → settle → start grant` 状态机，
但现有 P6-R1 生产路径仍直接发送 `AiRequestDispatchPayload`。客户端收到该 dispatch 后，
`ClientAiRequestSessionController.accept(...)` 会在本地 binding 检查后直接调用
`provider.complete(...)`。因此把 ACK 接在 proposal 回传、Provider 调用或响应之后，都不能
证明 server budget 是在实际物理 start 之前结算的。

R1 是固定的 owner 手动只读审阅路径；ADR-0024 的通用 client-sponsored coordinator 没有
R1 projection ticket、ADR-0037 identity 或物理 start fence，不能为了接线而替代该窄路径。

## 决策

### 1. R1 使用 protocol v3 的原子 offer / ACK / grant 三包

替换旧 R1 的直接 S2C dispatch 注册，v3 只允许以下 R1 物理尝试序列：

```text
server exact R1 dispatch + reserve
  → S2C AiPhysicalAttemptOfferPayload(dispatch, offer)
  → client local stage（binding、factory、deadline；Provider 尚未开始）
  → C2S AiPhysicalAttemptPrepareAckPayload(exact identity)
  → server authenticated recheck + settle
  → S2C AiPhysicalAttemptStartGrantPayload(exact identity)
  → client local one-shot claim
  → provider.complete(...)
```

offer 将完整已受限的 `AiClientRequestDispatch` 与相同 identity 原子编码，不能让排队客户端
观察到可用 dispatch 却没有 ACK/grant correlation。ACK 和 grant 只携带 identity。identity
编码完整且固定地包含 server instance、owner、safe dispatch receipt、attempt id、nonce、两种
not-after deadline；不携带 credential、endpoint、prompt/schema、response、token 数量、usage、
billing 或 HTTP 事实。所有 payload 的 `toString()` 必须保持 redacted。

当前提交只落地这三种 payload/codec 和它们的 round-trip/redaction 测试；在 network registrar、
生命周期和客户端同时接线前，它们不得注册为生产入口，也不得称为 Provider bridge。

### 2. 服务端保持 R1 专用 owner 与精确 lifecycle 清理

后续接线在 `BotLifecycleManager` 的既有 R1 gate/ticket 旁维护 R1 专用 owner state，而不使用
`AiClientSponsoredRequestCoordinator`：每个 owner 有一个 owner-thread
`AiPhysicalAttemptBudgetCoordinator`，并按 `(ownerId, botId, agentId)` scope 保存有界
`AiTokenBudgetLedger`。`AiReviewOnlyTicket` 必须携带与其完整 receipt 相等的 physical identity，
使 replace、proposal terminal、TTL、logout、unbind、death、generation retirement 和 shutdown
都能 `closeExact(identity)`；offer 释放 reservation，grant 后只 tombstone，永不退款。

C2S ACK handler 不信任 payload owner。它先重新确认 sender 是当前真实 persistent owner，server
instance、active runtime、generation、agent binding、live gate、full ticket receipt、purpose、tick TTL
和 ticket identity 都精确匹配，才调用 coordinator。duplicate exact ACK 可重发同一 grant，不能二次
settle。grant send 失败在已 settle 后只能 exact-close/tombstone，并关闭同一 receipt 的 R1 gate；
不能按 botId 猜测或关闭 replacement。trusted tick reaper 调用 attempt expiry/tombstone reaping。

R1 的 server admission 是固定而显式的：仅 canonical `REVIEW_ONLY_V1` dispatch，可由固定 request
shape 的保守输入上界与 `MAXIMUM_OUTPUT_TOKENS=256` 组成 accepted admission；其 token policy、scope
lifetime 和上限必须由 server code/配置拥有，绝不复用 client local model capability、provider usage 或
客户端时钟。它是 conservative reservation accounting，不是 billing/usage reconciliation。

### 3. 客户端只在 grant 后紧邻实际 Provider start

客户端先登记 session、deadline、credential binding 和 local Provider factory，但在收到 grant 前不得
调用 `provider.complete(...)`。同一个 exact session 创建 `AiPhysicalAttemptClientGrantGate`；connection
epoch、binding epoch、local session、clock rollback、physical-start deadline 和 client deadline 全部必须
在 grant handoff 与实际调用边界重验。cancel、logout、factory reload、rebind、replacement、expiry 和
server cancellation 都关闭未 claim 的 gate/lease。

`LocalStartLease.tryClaimPhysicalStart()` 的 true 是本地物理 start 线性化点；成功返回后，
`provider.complete(dispatch.toAiRequest(), token)` 必须是直接相邻的下一实际调用。duplicate grant、
duplicate ACK、expiry、rebind、connection change、handoff re-entry 或 send failure 都不得产生第二个
Provider 调用。grant/settlement 仍只表示 server 已为“可能的” start 记账，不表示 HTTP 已启动、到达
Provider、成功或返回可执行结果。

## 被否决方案

### 方案 A：保留 raw dispatch，并把 ACK 加在 Provider 回传之后

否决。Provider 已可能开始，ACK 无法再充当 server settle 前的 local readiness fence；旧 raw dispatch
也会形成绕过 grant 的生产入口。

### 方案 B：把客户端 local capability 或 Provider usage 当成 server admission

否决。客户端配置和响应 usage 都不是 server authority，且 usage 在 start 后才能得知。R1 只能使用
服务端固定的保守 admission/ledger。

### 方案 C：让通用 coordinator 接管 R1

否决。它没有 R1 fixed projection/ticket/physical-attempt 生命周期含义；混用会扩大 generic bridge
权限，且不能证明 exact close/replacement 关联正确。

### 方案 D：grant 后用异步队列延迟再 claim

否决。claim 与 Provider start 分离会重新打开 logout/rebind/deadline 窗口。若必须跨队列，队列项只能
持有 lease，并在真正调用前 claim。

## 兼容性、安全与性能

- 兼容性：R1 wire shape 从 protocol v2 升为 v3，旧直接 dispatch 不再是 v3 的生产 packet；不同版本
  不协商，按 NeoForge payload protocol fail closed。
- 安全：每个 wire identity 字段均由 decoder/immutable DTO 验证；服务器还必须独立认证 sender 和
  lifecycle state。payload 不承载 secret 或远端执行结果；R1 一直只产出已丢弃的安全 review summary。
- 性能：每 owner 有小容量 attempt owner，每 scope 有 bounded ledger；ticket/attempt/tombstone 的
  生命周期均有 TTL，禁止无限重试、扫描或 global request-id fallback。
- 许可证：不新增依赖。

## 验证方式

- codec：offer/ACK/grant exact round-trip、identity drift/非法字段拒绝、diagnostic redaction；
- server：authenticated sender、gate/ticket/runtime drift、duplicate ACK、send failure、TTL/reaper、
  logout/rebind/terminal exact close 与 no-refund；
- client：offer 前 Provider 调用为 0，grant/claim 后恰为 1，duplicate/rebind/expiry/cancel 的未 claim
  lease 为 0，`tryClaimPhysicalStart()` 与 `provider.complete(...)` 的直接相邻性；
- Java 21 `clean build`、JUnit 与 `runGameTestServer`，再加真实客户端、独立专用服、断线/reconnect、
  丢包/乱序和多 bot soak 验收。以上实机验收不能由 DTO 或纯 Java 测试替代。
