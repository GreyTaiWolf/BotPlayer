# ADR-0024：客户端赞助请求的 server-thread 协调器与有界终态邮箱

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0019、ADR-0020、ADR-0021、ADR-0022

## 背景

ADR-0022 已有不可变 `AiClientSponsoredRequest` 账本，并规定未来 Lifecycle 必须同时拥有 gate、
账本、Scheduler handle 和主线程 mailbox。但只把 gate 与账本并排留给调用者，仍有三个容易被错误
接线的空隙：

- 全局在途上限可能只在 Scheduler 一侧检查，令同一生命周期在分发前就积累无界 client 请求；
- `openReplacing(...)` 可能先移除旧 gate envelope，随后才发现 client dispatch 的 epoch deadline
  无效，导致旧 binding 没有精确清理路径；
- client 或 Scheduler lane 可能直接关闭 gate/账本，或把 response、Throwable、nonce、owner、prompt
  等不应跨线程的数据塞进无限队列。

P6-R1 继续使用其独立、固定且只读的 ticket book；它不是这里的调用者，也不证明通用 bridge 已经
存在。

## 决策

### 1. 一个 server-thread coordinator 同时持有 gate 与账本

新增 `AiClientSponsoredRequestCoordinator`。构造时捕获 owner thread，并私有持有一个
`AiProposalSessionGate` 与一个 `AiClientSponsoredRequestLedger`。仅该线程可调用：

- `open`、`openReplacing`；
- `closeExact`、`closeBot`、`closeExpiredThrough`、`closeAll`；
- 活跃计数和终态 mailbox drain。

普通 `open` 返回稳定的 `BOT_BUSY` 或 `CAPACITY_EXHAUSTED`，不改变现有绑定。显式 replacement
在成功时返回新 binding 和唯一被替换的旧 binding；旧 receipt 永远不能关闭新 binding。任何 gate/
账本不一致都按精确已知 receipt 失败关闭并报 invariant，而不是按 `botId` 猜测清理。

### 2. 在 gate mutation 前完成完整 dispatch preflight

协调器在正常打开或 replacement 之前校验 server/bot/owner/agent、generation、revision、tick TTL、
Firewall policy、provider/template，以及真实 `issuedAtEpochMillis/expiresAtEpochMillis`。为此
`AiClientRequestDispatch.requireDispatchableTemplate(...)` 增加带 epoch deadline 的 overload。

所以无效的 epoch deadline、超时配置、模型、消息或 schema 会在 `openReplacing` 删除旧 envelope
**之前**被拒绝。gate 成功后若出现不变量异常，协调器只关闭已知的新 envelope 和该 bot 的当前精确
ledger binding，宁可将该会话完全关闭也不保留半开关联。

### 3. 受限全局容量与终态 mailbox

协调器有独立 `Limits`：活动请求最大值不超过既有 Scheduler 的绝对硬上限 128，默认仅 4；终态
mailbox 默认 256 项，单次 owner-thread drain 默认最多 16 项。所有上限均有不可放大的硬界。

唯一允许从任意线程调用的方法是非阻塞的 `offerTerminalObservation(...)`。它只接受：

```text
AiRequestDispatchReceipt + { SUCCEEDED | FAILED | CANCELLED }
```

receipt 不含 nonce、owner、prompt、schema、credential 或模型正文；observation 也不含 response、
Throwable、HTTP/Scheduler handle。满队列返回 `false`，调用者不得在 Tick 上自旋或阻塞。

drain 只按 FIFO 交给未来 owner-thread 生命周期观察，**不会**验证 receipt 是否仍活动，也不会自动关闭
gate 或账本。迟到/伪造/旧 replacement receipt 因而没有改变会话的权力。

### 4. 本阶段明确不接线的边界

本 ADR 不改 `BotLifecycleManager`、network payload/handler、客户端 credential、Provider、
`AiRequestScheduler`、C2S proposal review、聊天、Tool→Skill port、Technique、Action 或 Minecraft
世界。它不发送 packet，不启动/取消 HTTP 或 Scheduler，也不把 AI 输出转换为计划或执行。

后续 bridge 仍必须在服务器线程按同一 immutable binding 依次完成 lifecycle ownership、精确 S2C
dispatch、client/Scheduler terminal reporting、C2S gate review、snapshot/revision/world recheck，最后才
可交给受限 P5 port。不能把本 coordinator 标记为通用聊天或 AI→世界执行完成。

## 被否决方案

### 方案 A：让 Scheduler 或 client worker 直接 close gate/账本

否决。它们不拥有 Minecraft 生命周期、owner/agent/generation 状态，且迟到终态会误伤替换后的请求。

### 方案 B：只在 Scheduler 提交时检查全局容量

否决。client dispatch 和关联已可能先被创建，生命周期仍会积累无法归因的在途 work。

### 方案 C：终态 mailbox 自动按 receipt close 会话

否决。观察线程不知道该 terminal 是否对应当前 lifecycle、是否需要保留 C2S review 或是否已经替换；
自动关闭会把迟到 callback 变成权限。

### 方案 D：replacement 后再校验 wall-clock deadline

否决。这样一个坏模板可以先让 gate 丢失旧 envelope；preflight 必须发生在 mutation 前。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增纯 Java transport 类型和 ledger 的 package-private 查询；不改变 R1 或既有 payload。
- 性能：活动索引仍为两个 UUID Map；每个 foreign terminal report 只做一次非阻塞有界 queue offer；
  owner 每 tick 最多 drain 配置上限。
- 安全：公开诊断不输出 owner、nonce、prompt、schema、credential、response 或 Throwable；所有 close
  都需要完整 receipt 或 coordinator 已拥有的 immutable binding。
- 许可证：只使用 JDK 集合和既有项目类型，不引入依赖。

## 迁移和回滚

本阶段仍无生产调用点。未来接入可先创建 coordinator，再逐项接入精确 dispatch、Scheduler handle
与 lifecycle cleanup；每一步都必须保留 R1 的独立路径。若回滚本阶段，停止创建 coordinator，并在
其 owner thread 调 `closeAll()` 取得精确 binding 供下游清理；不删除 credential、不触及 P5/world。

## 验证方式

- 纯 Java：同 bot 与全局容量拒绝不改活动 binding；满容量时同 bot replacement 返回唯一旧 binding；
  无效 epoch deadline 在 replacement 前拒绝；old receipt 不关闭 replacement；TTL、bot close 与
  shutdown 使 gate/ledger 同步；foreign thread 只能 offer；mailbox FIFO、有界且 drain 不自动 close。
- Java 21 自动基线：本 ADR 对应提交必须通过 GitHub Actions 的完整 Gradle `clean build`、JUnit 与
  NeoForge GameTest；提交前不得把本地缺少 Java 21/Gradle 的静态检查当作该结论。
- 后续集成：真实 client、Provider/Scheduler lane、owner 退出、断线、死亡、TTL 与迟到 C2S 仍必须
  验证只影响同一完整 binding；在此之前通用 bridge、聊天和 AI 世界执行均未实现。
