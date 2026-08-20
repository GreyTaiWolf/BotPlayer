# ADR-0026：客户端 AI 会话登记后的 Error 清理

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0012、ADR-0019、ADR-0021、ADR-0024

## 背景

`ClientAiRequestSessionController` 会先把一个已通过本地 owner、binding 和 TTL 检查的请求登记到
request/bot 双索引，再安排 deadline、构造 Provider、启动 completion 并安装 callback。此前这些登记后
边界主要只处理 `RuntimeException`。受信任 scheduler、factory、provider、completion registration 或
completion-time clock/binding 读取若抛出 `Error`，可能绕过精确摘除，留下取消后的 active session，直到
将来某次 expiry 才偶然收束。

此外，外部 `CompletionStage` 可以在 `whenComplete` 注册期间同步调用 callback，随后才抛出 attachment
错误。若 callback 当场完成 proposal handoff，则 attachment 失败发生得太晚，已发布的 provisional success
无法撤回。

这不是把第三方 `Error` 变成普通 Provider 失败的理由；同步 setup 边界（包括 attachment 中同步到达、
待 attachment 成功后由 `accept` 激活的 signal）仍须在清理后重抛。attachment 已成功返回后才到达的
completion callback 则按 `CompletionStage` 自身的 returned-stage 语义表示。先发生异常而不清理会扩大
旧 binding、deadline 和 credential token 的存活窗口。

## 决策

### 1. 登记后的 Error 先精确收口，再按所属边界报告

在 session 已进入 controller 索引后，deadline 安排、Provider factory、Provider `complete`、
completion callback 注册以及 completion 内的本地再校验遇到 `Error` 时，controller 必须：

1. 只按该 `ActiveSession` 的精确 request/bot identity 摘除；
2. 取消 deadline 与 token；
3. 在 lock 外至多一次投影 session 已决定的安全 terminal observation：setup、再校验或 handoff Error
   会成为 `FAILED`，但后续 cleanup Error 绝不能覆盖已经决定的 `SUCCEEDED` 或 `CANCELLED`；
4. 对同步 deadline/factory/provider/registration setup 边界，以及 attachment 中同步到达、在正常返回后
   由 `accept` 激活的 completion signal，再抛出原始 `Error`；若清理本身也失败，作为 suppressed evidence
   附在原始错误上。

若已登记的 deadline future 在取消时抛出 `Error`，它不能打断双索引摘除、binding epoch
释放、token 取消或 terminal observation；controller 先保留该清理失败，待锁外 token 与 observation
均已执行后再作为原始同步登记后错误的 suppressed evidence 传播。

该保留的 cancellation cleanup failure 只能由精确摘除 session 的 terminal owner 在 observation 后消费并
报告。迟到 completion 可以请求同一 token 的幂等取消，但不得抢走该 failure；否则它会被丢进无主的
dependent stage，原 terminal owner 就无法在自己的终态之后报告该错误。

同一 session 已开始取消时，迟到返回的 deadline future 不得重新写入 session；它必须直接取消，避免
terminal owner 已完成后再出现第二次取消或无主 cleanup Error。

当 Provider 在 attachment 已正常返回后才完成时，`completeSession` 仍会在 action 内先收口再抛出；标准
`CompletionStage.whenComplete` 可以把该异常捕获到它返回的 dependent stage。该 controller 当前不持有
或向 host 报告这个 returned stage，因此不承诺这类**异步** callback 的 host-level fatal propagation。相反，
若 callback 在 attachment 期间同步到达，它会先暂存，随后由 `accept` 在 attachment 正常返回后处理；该
同步处理的 trusted `Error` 会在清理后从 `accept` 重抛。

### 2. callback 在 attachment 返回前只能暂存

`whenComplete` attachment 处于 provisional 状态时，callback 只能保留第一份 `(response, failure)` signal，
不能调用 `completeSession`、proposal handoff 或 terminal observer。只有 `whenComplete` 正常返回后，该 signal
才可激活并完成 session。若 attachment 在同步 callback 后抛出 `RuntimeException` 或 `Error`，暂存 signal
必须丢弃，并把仍精确登记的 session 失败关闭；因此 attachment failure 绝不能发布 provisional
`SUCCEEDED` proposal。

普通 `RuntimeException` 保持既有行为：已登记 session 失败关闭并返回本地不可用/失败结果。Provider
completion 的 `failure` 参数仍是不可信 Provider 结果，不因为其中包装了 `Error` 而向网络或世界执行
路径泄露异常。

### 3. 这不改变 C2 的权限或网络边界

本 ADR 不新增 payload、线程、Provider、server coordinator 调用、Lifecycle 接线、Scheduler 生产接线、
C2S proposal、SkillPlan 或 Minecraft 世界动作。terminal observation 仍只是 receipt 加
`SUCCEEDED|FAILED|CANCELLED` 的本地安全投影，不能关闭 server gate 或表示发送/执行成功。

当前 direct public-method reentry 仍会被 controller 拒绝。ADR-0027 已另行把 `ProposalHandoff` 内同步
完成另一 session 的**间接** completion reentry 失败关闭：scope 内只结构摘除嵌套 session，外层和嵌套
session 均为 `FAILED`、外层 lease release，token/observer 在 lock 外统一完成；它不能撤销违反 queue/lease
契约而已经同步直发的 payload。该状态机不扩展本 ADR 的 network 或 AI 权限边界。

## 被否决方案

- **吞掉 Error 并返回 PROVIDER_UNAVAILABLE**：会隐藏受信任边界失效，也不利于宿主隔离。
- **先抛 Error，等待下一次 TTL 清理**：会让已取消的 session、deadline 或 binding epoch 在不可预测的
  时点前继续存在。
- **让 observer 在 lock 内清理一切**：会把外部 callback 引入相关性检查的 monitor，破坏 cancellation-
  before-queue 的既有时序。

## 验证方式

- scheduler、factory、provider 和 completion-registration 的同步 `AssertionError`：active count 归零，
  token 已取消，只有一次精确 `FAILED` observation，随后 Error 继续传播；
- callback 同步给出有效 response 后再抛 attachment `AssertionError`：不得 handoff proposal，暂存 signal
  必须丢弃，且只观察一次 `FAILED`；
- deadline future `cancel` 的 `AssertionError`：不得中断 tombstone/binding/token/terminal cleanup，且应在
  原始登记后 Error 上作为 suppressed evidence 保留；若 terminal 已先决定为 `SUCCEEDED|CANCELLED`，
  cleanup Error 不得改写该 status；
- completion-time clock/binding/session guard Error：不能遗留 active map，迟到 Provider response 不得
  handoff；
- completion-time Error 的 token listener 再次调用 `cancelRequest`：Error owner 必须先摘除，listener
  不得把该 request 抢成 `CANCELLED`，最终只允许一次 `FAILED` observation；
- terminal owner 已摘除并暂存 deadline `cancel` Error 时的 stale completion race：只有 owner 可在唯一
  `CANCELLED` observation 后报告该 Error，stale completion 不得消费或吞掉它；
- scheduler 返回 deadline 与终态取消交错：已开始取消的 session 必须直接取消这个迟到 future，不能把它
  重新装入 session 或再次取消；
- 保留 C2 的 observer Runtime/Error、replacement、expiry、cancel race、direct reentry 与 ADR-0027
  双 bot 间接 completion/lease 失效/锁外 observer 回归；
- 当前提交仍需 Java 21 GitHub `clean build`、JUnit 与 NeoForge GameTest。真实客户端/Provider E2E、
  通用 client-sponsored bridge 和 AI→世界执行不属于本 ADR。
