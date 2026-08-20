# ADR-0027：`ProposalHandoff` 间接 completion 重入围栏

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0022、ADR-0024、ADR-0026

## 背景

`ClientAiRequestSessionController` 必须在 controller lock 内完成 Provider response 的最后
cancellation-before-queue 检查，再调用非阻塞的 `ProposalHandoff`。此前已有 public API 的直接重入
拒绝，但 handoff 若同步完成另一个 controller-owned `CompletionStage`，会经私有 callback 直接进入
`completeSession`；Java monitor 可重入，因此第二个 session 会在外层 handoff 尚持锁时再次 handoff、
取消 token 并运行 terminal observer。

这既绕过 direct public API guard，也会让 observer 或 token listener 在外层相关性检查中重入。简单抛出
异常不足以修复它：`CompletionStage` 可以把 callback exception 收到 returned dependent stage，而外层
handoff 未必知道已经出现嵌套 completion。

## 决策

### 1. lock 内只在 handoff 调用期间安装局部 scope

controller 在进入 `ProposalHandoff.accept` 前安装一个只受 controller lock 保护的
`ProposalHandoffScope`。其他线程不能在外层 lock 释放前看到或使用该 scope；因此 scope 存在时进入
`completeSession` 的 callback 只能是 handoff 当前线程的同步 monitor 重入。

scope 只记录精确嵌套 `ActiveSession` 和其待完成终态，不保存 Provider response、prompt、credential 或
其他模型内容，也不是跨 tick 的全局队列。

### 2. 嵌套 completion 先结构摘除，锁外才取消和观察

scope 内发现的 session 立即只按 request/bot 双索引精确摘除，写 tombstone、释放 active binding epoch，
并标记 `FAILED`。它**不会**在外层 handoff 的 lock 范围内调用 proposal handoff、取消 token 或投影
terminal observer。

外层 handoff 离开 synchronized block 后，controller 按确定顺序为自身和所有 scope 中的嵌套 session
执行 `cancelAndObserve`。即使一个 token listener 或 observer 抛 `Error`，其余 deferred session 的
cleanup 仍继续；已有 handoff `Error` 仍为 primary，后续 cleanup failure 只作为 suppressed evidence。

### 3. 间接 completion 使外层 handoff 失败关闭

只要 scope 记录了嵌套 completion，即使 `ProposalHandoff.accept` 正常返回，外层 session 也必须为
`FAILED`，且其 `BindingEpochHandoff` 立即 release。已排队的物理客户端发送在真正发送前检查
`isCurrentFor` 时会得到 false，不能继续使用这份 proposal。嵌套 session 也为 `FAILED`，不会进入第二次
handoff。

这不是对违反契约的同步直发网络操作的回滚：若 handoff 在返回前已经绕过 lease 直接发送 payload，
controller 无法撤回。生产 handoff 因而必须保持非阻塞排队，并在物理发送时检查且最终 release lease。

直接调用 controller public API 仍由既有 guard 拒绝并使本地 handoff `FAILED`。来自另一个线程的 completion
会阻塞到外层 lock 和 scope 均已释放后才正常处理，不会误把已解锁后的并发完成标记为 scope poison；handoff
不得等待该线程完成，否则它自己违反非阻塞契约并可能死锁。

### 4. 本 ADR 不扩展 AI 或网络权限

本变更不发送 payload、不启动新的 Provider 或 Scheduler、不连接 server coordinator/Lifecycle，也不把
AI 输出转为 Skill、Action 或世界操作。它只收紧已存在的 client-local session cleanup 时序。

## 被否决方案

- **把 `ProposalHandoff` 整体移到 lock 外**：会失去 cancellation-before-queue 的最终原子检查。
- **允许嵌套 session 正常 handoff**：会递归调用未知外部代码并使 token/observer 在外层 lock 内运行。
- **只关闭嵌套 session 而保留外层成功/lease**：外层排队 proposal 已发生在违反时序的 handoff 中，必须
  失败关闭并撤销其可发送 lease。
- **在 lock 内直接 `failSession` 嵌套 session**：会在外层 monitor 内触发 token listener 与 observer。

## 验证方式

- 两个不同 bot 的 A/B session：A handoff 同线程完成 B 的有效 Provider future；A handoff 仅调用一次，
  B handoff 为零，A/B 各精确 `FAILED` 一次、token 均取消、active index 清空；observer 调用
  `activeRequestCount()` 不得遇到 lock reentry；A 捕获的排队 lease 必须已无效；
- handoff/runtime/cleanup `Error` 与 ADR-0026 的 primary/suppressed 规则组合时，所有已 scope 摘除的
  session 都仍要完成一次 cleanup；
- 仍需 GitHub Java 21 `clean build`、JUnit 与 NeoForge GameTest。真实客户端/Provider E2E、通用
  client-sponsored bridge、聊天与 AI→世界执行不属于本 ADR。
