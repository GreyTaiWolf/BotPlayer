# ADR-0028：通用 client-sponsored proposal 的精确 owner-thread 审阅事务

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0019、ADR-0021、ADR-0022、ADR-0024、ADR-0027

## 背景

ADR-0024 已让 `AiClientSponsoredRequestCoordinator` 在同一 server owner thread 持有通用
`AiProposalSessionGate` 和不可变 binding ledger，但该 coordinator 原先只管理 open、replace、
close 与安全 terminal mailbox。若未来调用方各自直接调用 gate review，容易产生三个错误：

- 先让 gate 查看不完整 C2S correlation，会给旧 nonce、replacement 或 requestId 提供状态 oracle，
  甚至错误消费会话；
- gate 已终态消费后只清理 gate、不清理同一 ledger binding，会让下游 client/Scheduler lifecycle
  保留不再存在的请求；
- gate 与 ledger 意外分歧时按 `botId` 宽泛清理，可能误伤同 Bot 的后续 request。

P6-R1 的固定 review-only ticket book 和 `BotLifecycleManager.reviewAiProposal(...)` 是独立路径；
它不调用本 ADR 的通用 coordinator，不能因本地合同而被改写或视为 generic bridge 已接入。

## 决策

### 1. 由 coordinator 提供单一 owner-thread review transaction

新增 `AiClientSponsoredRequestCoordinator.reviewProposal(payload, authority, currentTick)`。它只能在构造
时捕获的 owner thread 调用，输入是已经解码但仍完全不可信的 `AiProposalPayload`，而不是网络 handler。
方法不会发送 packet、启动 HTTP/Provider/Scheduler、创建可执行 Skill/Action，或读取 Minecraft/world
对象。

调用顺序固定为：

```text
ledger complete-tuple precheck
  -> gate dynamic authority / TTL / codec / Firewall review
  -> terminal receipt exact-match
  -> ledger exact close
  -> server-side result
```

### 2. 不完整 correlation 在 gate 前静默丢弃

首先用 `ledger.findMatching(payload)` 比较完整 immutable C2S 元组：

```text
botId + agentId + generation + requestId + nonce + revision
```

未匹配时返回没有 review、没有 terminal binding 的 server-side result，**绝不调用 gate**。因此旧 replacement、
部分猜测或 nonce/revision 漂移无法消费 gate、关闭新 binding，或得知 gate 的内部拒绝状态。

匹配只说明该 immutable tuple 当前存在；gate 仍必须重新检查 bot/owner/agent/generation、half-open TTL、
purpose shape、codec 和 Firewall。它不授权任何世界执行。

### 3. terminal 必须精确同步关闭 ledger，分歧失败关闭

gate 只要返回 terminal receipt，coordinator 就要求它完整匹配预检 binding，随后仅以该 receipt
`ledger.closeExact(...)`。返回给调用者的 terminal binding 就是被移除的同一个 immutable 对象。

若 terminal receipt 不匹配、ledger 无法关闭，或完整预检后出现不可能的 non-terminal correlation/
terminal-only 状态，coordinator 仅尝试精确移除已预检的 ledger binding 并抛出 invariant failure。它**不**
按 `botId` 宽泛关闭 gate；无法证明为同一会话的 gate 状态被保留，之后 coordinator 的 gate/ledger count
检查会持续 fail-closed，而不是猜测性地影响替换后的会话。

真正可保留的 non-terminal 只来自调用当刻的动态 authority 缺失：bot 不活动、sender 非 owner、
persistent owner 暂缺、active agent 暂缺/不一致，或 active generation 暂缺。`GENERATION_MISMATCH`
仅在 active generation 暂缺时可保留；若它已存在，完整预检后的该状态表示 gate/ledger generation 分歧，
必须失败关闭。

### 4. 结果只能表达 server-side 审阅状态，不授予执行

`AiClientSponsoredProposalReviewResult` 由私有构造器与 package-private factory 创建，避免外部伪造
“未审阅却带 terminal binding”的状态。它只供 owner-thread server 侧调用者取得：

- 可选的 `AiProposalReview`；
- gate terminal 后已精确移除的可选 binding；
- 不渲染 nonce、owner、prompt、tool 参数或 binding correlation 的 `toString()`。

binding 是后续精确 client/Scheduler cleanup 所需的 server-side 对象，不得序列化、写入日志或交给客户端。

即使 `AiProposalReview` 为 `ACCEPTED_NO_EXECUTION`，内部 `ProposedSkillPlan` 仍只是未授权 DTO；它不会被
提交给 P5、Technique、Action 或世界。

### 5. 本阶段明确不接线的边界

本 ADR 不修改 `BotPlayerNetwork`、`BotLifecycleManager`、R1、客户端 R1-only admission、credential、
Provider、`AiRequestScheduler`、mailbox consumer、chat、`AiSkillPlanPort`、Technique、Action 或 Minecraft
世界执行。generic C2S handler、client opt-in/forwarding、owner logout/retirement、Scheduler/client terminal
协调、snapshot/revision/world recheck 与受限 Skill plan port 仍是后续独立阶段。

## 被否决方案

### 方案 A：先调用 gate，再查 ledger

否决。gate 可能暴露 correlation-specific status 或消费会话；ledger precheck 必须是唯一第一步。

### 方案 B：terminal 后只关闭 gate

否决。client/Scheduler lifecycle 仍会保留旧 binding，后续 exact cleanup 无法可靠判断是否已终态。

### 方案 C：分歧时按 botId 关闭 gate 或 ledger

否决。同 Bot 的 replacement 是合法状态；只有完整 receipt 或已预检 binding 能作为清理权力。

### 方案 D：accepted proposal 立即送入 P5

否决。P5 Checkpoint、TaskSensor/Reservation、DAG、snapshot/revision/world 复核和 `AiSkillPlanPort`
尚未稳定；P6 也不得直接调用 Technique。

## 兼容性、性能、安全与许可证影响

- 兼容性：新增纯 Java coordinator method/result；不改变既有 gate public review、R1 payload 或网络协议。
- 性能：每次 owner-thread review 只进行常数个 UUID map lookup、一次 gate review 与最多一次 exact close；
  不新增 queue、扫描或异步 work。
- 安全：不完整 correlation 不进入 gate；terminal 只精确关闭；result diagnostic 不暴露 secret 或模型
  内容，binding 也不得离开 server side；分歧保持 fail-closed。
- 许可证：仅使用现有项目类型和 JDK 集合，不引入依赖或第三方内容。

## 迁移和回滚

generic bridge 的未来 lifecycle 可调用本方法，而不能自行拼接 gate/ledger review；R1 保持不变。回滚时
停止调用该 method，现有 coordinator 的 open/close/mailbox 与 R1 均不受影响；不删除 credential、不发送
控制包，也不触及 P5/world。

## 验证方式

- 纯 Java：完整匹配 accepted proposal 只返回 `ACCEPTED_NO_EXECUTION` 并同步精确关闭；bot/agent/
  generation/requestId/nonce/revision 任一漂移在 gate 前丢弃；old replacement proposal 不影响新 binding；
  dynamic owner/generation 缺失保留 binding；expiry、owner change、malformed tool、Firewall rejection 都
  exact-close ledger；foreign thread 被拒绝；结果诊断不泄露 owner/nonce/prompt/tool。
- 分歧回归：构造 gate/ledger 同 requestId 但不同 generation，active generation 存在时必须精确关闭
  ledger 并抛 invariant；不得按 bot 宽泛关闭 gate，coordinator 后续保持 fail-closed。
- Java 21 自动门：对应提交仍必须通过 GitHub Actions 的 `clean build`、JUnit、`runGameTestServer` 与
  two-start recovery；本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过。
- 后续实机：generic bridge 真正接入后，真实客户端、owner reconnect/retirement、独立专用服、多 bot
  soak 与 Provider E2E 必须分别验证；本 ADR 自身没有 Minecraft 生产路径。
