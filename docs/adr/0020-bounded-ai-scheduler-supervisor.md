# ADR-0020：有界 AI 调度监督器与 Provider-start 围栏

- 状态：Accepted
- 日期：2026-08-12
- 相关：ADR-0012、ADR-0015

## 背景

P6 的纯 Java `AiRequestScheduler` 会穿过 Provider、外部 `CancellationToken`、
`CompletionStage` 和调用方 continuation 等不可信/可阻塞边界。把这些工作交给调用方提供的
任意 `Executor` 有两个不可接受的问题：

- `Executor.execute()` 正常返回只表示它接受了 wrapper，不表示 wrapper 会真正开始；它可以
  静默丢弃、无限排队、重复调用，或调用 wrapper 后再抛异常；
- cached/unbounded executor 为了绕过一个卡住的 Provider 或 callback 扩线程，会把逻辑并发
  上限伪装成物理并发上限，最终把外部阻塞转换成无界线程和远端请求风险。

Provider start 还必须与取消形成精确线性化：简单地在锁外读取 token 后调用 `complete()` 会在
“取消已终态、Provider 仍被调用”的窗口内失去可审计语义。

## 决策

`AiRequestScheduler` 的生产 API 不再接受 raw `Executor`/dispatcher 集合。它要么自行拥有
`AiRequestSchedulerSupervisor`，要么接收一个一次性 claim 的、调用方生命周期拥有的 supervisor。
supervisor 不暴露 lane executor，并提供：

- Provider start、token setup、terminal cleanup 和 completion delivery 的物理隔离、固定大小、
  有界队列 lanes；deadline timer 与独立 start/stall watchdog 也互不复用；
- 每一次外部 handoff 的正数 `attemptId` 与单次 CAS `DispatchAttempt`；先 arm 独立 start
  watchdog，再交给 broker；wrapper 只有赢得 `STARTED` 才可提交工作，迟到/重复 wrapper 一律
  no-op；
- accepted-but-dropped 或 broker/physical lane 拒绝不会被当成已启动。watchdog 使该 exact
  attempt 失效：Provider/token start 失败关闭且绝不重试 Provider；cleanup/publication 的恢复
  记录有界保留，生命周期修复后由 `resumeDispatch()` 重投；
- supervisor 关闭时先失效所有尚未 `STARTED` 的 attempt，因此 broker 或 physical queue 中的
  cleanup/publication wrapper 不会被静默丢弃，而是进入同一套有界恢复记录；共享 supervisor 的
  owner 仍必须在恢复完成前保持其生命周期。关闭 start gate 与 `ARMED → STARTED` 使用同一线性化
  屏障，因此 close 已开始后迟到的 wrapper 也不能在失效循环之前提交；
- 已开始但阻塞的 Provider、token setup、cleanup 或 completion delivery 进入显式 quarantine。
  诊断按类型计数，调度器进入 degraded 并停止新的 Provider admission，而不是创建新线程；
  物理工作返回后才可恢复。
- 每个已提交 wrapper 的 body 只有正常返回才可 ACK 对应 lane。任何非 fatal `Throwable` 都会进入
  exact lane 的 failure/quarantine 路径；cleanup、detached cleanup 与 completion delivery 保留
  有界 recovery，绝不因为 wrapper 的 finally 而把未确认清理或 publication 标成完成。fatal VM
  错误也必须先留下同一 fail-closed 状态和 observer 记录，之后才允许向 worker 重抛。

Provider start 的提交点位于 scheduler lock 中。取消在此点之前终态化时，wrapper 不会调用
`AiProvider.complete()`；提交先发生时，Provider **可能已经开始**。取消回执只确认逻辑取消和
已确认/已 quarantine 的清理，不承诺远端调用已经停止。公开 handle 仍只提供 read-only
response 与异步 cancellation receipt。

terminal cleanup 在 cleanup ACK 或 quarantine 后才进入 publication；completion wrapper 的
commit 点随后才允许 successor Provider starts。调度器不等待任意 caller continuation 从
`CompletableFuture.complete()` 返回。

唯一 raw executor seam 是 package-private、明确命名为 adversarial testing 的 supervisor
factory。它仅让测试在 broker 内模拟 drop、double-run、blocking `execute()` 和
invoke-then-throw；生产调用方无法进入该 seam。

## 被否决方案

### 方案 A：保留 public dispatcher record，只要求调用方“提供正确 executor”

否决。通用 `Executor` 无法证明线程池容量、拒绝语义、wrapper 是否启动或生命周期所有权；
Javadoc 不能把不可验证的约定变成安全边界。

### 方案 B：在 Provider 卡住时使用 cached pool 继续启动 successor

否决。它会无限增加物理线程，并可能让取消后的远端调用与 successor 同时增长，破坏总量
上限和故障隔离。

### 方案 C：以 `execute()` 返回或 token 的一次锁外读取作为启动证明

否决。前者无法识别 drop/延迟/重复 wrapper；后者不能在线性化地阻止取消与
`Provider.complete()` 交错。

### 方案 D：等待 completion continuation 返回后再开始 successor

否决。调用方 continuation 可以永久阻塞；publication commit 必须与其执行完成分离。

## 兼容性、性能、安全与许可证影响

- API：删除旧的 public raw dispatcher 构造面，新增 owned 或 lifecycle-owned
  `AiRequestSchedulerSupervisor`；一个 supervisor 只能绑定一个 scheduler；
- 性能：所有队列与物理 worker 数有上限。lane 被卡住时吞吐降低并拒绝新 admission，这是
  明确的降级语义，不以扩线程掩盖；
- 安全：quarantine/health/diagnostics 只保存 request ID、固定 failure/reason/kind 和时间，
  不保存 prompt、响应正文、异常正文或凭据；
- 许可证：只使用 JDK 并发原语，不新增依赖。

## 迁移和回滚

调用方将原有 dispatcher 创建替换为默认构造，或创建一个有明确生命周期的
`AiRequestSchedulerSupervisor` 并传入 scheduler。不得把现有共享/无限 executor 适配回生产
API。若必须回滚代码，恢复旧 API 前不得声称其具备物理 boundedness 或 accepted-but-dropped
恢复保证。

## 验证方式

- capacity、每 bot 公平、响应归属和 read-only handle 回归；
- actual hostile executors：accepted-but-dropped、重复 wrapper、blocking `execute()`、
  wrapper 后抛异常；
- Provider `complete()`、`whenComplete` attachment、token registration、listener close、stage
  cancel 与 completion-lane body 的 `Error`：只有成功 body 可 ACK；错误 body 必须保留/恢复，
  invoke-then-Error 不得回滚已提交 wrapper；
- provider-start 与 cancellation 两侧竞态：取消先赢时零 Provider call，提交先赢时 quarantine
  与 receipt 语义；
- blocked Provider、blocked token setup、blocked completion delivery、dropped cleanup 与
  dropped publication；
- Java 21 Gradle `test`/`clean build` 已由 Build #354 在项目 toolchain 完成。当前改动本身
  不访问 Minecraft 活动对象，也尚未接入生产 client-sponsored lifecycle。
