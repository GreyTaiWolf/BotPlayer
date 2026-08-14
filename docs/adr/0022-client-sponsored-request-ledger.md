# ADR-0022：客户端赞助请求账本的精确生命周期

- 状态：Accepted
- 日期：2026-08-14
- 相关：ADR-0012、ADR-0019、ADR-0020、ADR-0021

## 背景

ADR-0021 已固定一个通用 client-sponsored 请求必须从同一个 gate envelope 派生客户端
dispatch、Scheduler request、取消 payload 与终态 receipt。它解决了关联 ID 的来源问题，但
不能单独保存一条**正在运行**的绑定：`AiProposalSessionGate.openReplacing(...)` 能返回旧
envelope，调用者却没有一个受限的生命周期所有者来确保旧 HTTP、Scheduler 和 gate 只按该旧
关联清理。

若由生命周期按 `botId` 保存或删除 future，会有三个风险：

- 旧 C2S、Scheduler 或取消回调可能误关同一 Bot 的新 requestId；
- gate 已显式替换旧 envelope 后，调用者可能静默丢失旧客户端 HTTP 或 Scheduler 的清理身份；
- 后续实现可能在网络线程、Provider lane 或 Minecraft 生命周期对象之间分别维护不一致的会话
  表。

P6-R1 固定只读审阅有独立 ticket book，且刻意不经通用 Scheduler；它不证明通用桥接已存在。

## 决策

### 1. 账本只持有已有的不可变 binding

新增 server-thread-confined `AiClientSponsoredRequestLedger`。它只存放已由
`AiClientSponsoredRequest.bind(...)` 创建的不可变 binding，并以两份私有索引维护：

```text
requestId -> binding
botId     -> requestId
```

账本不生成 requestId 或 nonce，不创建 gate envelope，不发送 S2C/C2S 包，不启动 Provider，
不调用 `AiRequestScheduler`，不把 `AiProposalPayload` 变为 `AiResponse`，也不触及
Skill、Technique、Action、世界或 Minecraft 对象。它不是第二个 gate，不能授权模型输出。

### 2. 普通打开禁止隐式替换

`open(binding)` 仅在该 Bot 和 requestId 都没有活动 binding 时成功。相同 Bot 的普通打开失败，
不得静默覆盖旧绑定。

显式替换只能由 `replace(newBinding, gateReplacedReceipt)` 完成：

- 若账本已有该 Bot 的旧 binding，gate 返回的旧 receipt 必须存在并完整匹配旧 binding；
- 若账本没有旧 binding，gate replacement receipt 必须为空；
- requestId 必须是新的、未被任何活动 binding 占用的值；
- 任一不符时账本不变，协调器必须失败关闭并用已知的精确关联清理，而不是猜测 botId。

成功时账本原子移除旧 binding、登记新 binding，并返回旧 binding。调用者只可用返回的对象生成
旧客户端 cancel、关闭旧 Scheduler request 或记录精确 cleanup receipt。

### 3. 迟到输入和终态只可精确消费

`findMatching(payload)` 必须检查 `botId`、`agentId`、generation、requestId、nonce 与 revision
全部相同；它只是廉价相关性预检，后续仍必须由 gate 重验 owner、active agent、generation、TTL、
schema 与 Firewall。

`closeExact(receipt)` 仅在 purpose、expiry、revision、generation、bot、agent 和 requestId 全部
匹配当前 binding 时移除它。旧 request 的终态因此不能删除新 request。TTL 使用服务器半开区间
`[issuedAtTick, expiresAtTick)`；`closeExpiredThrough(tick)` 在 `tick >= expiresAtTick` 时返回原始
binding。bot retirement 与 shutdown 也只返回原始 binding，绝不重建宽泛取消身份。

### 4. 未来 bridge 的所有权顺序

未来通用协调器必须在服务器线程同时拥有 gate、ledger、Scheduler handle 与主线程网络 mailbox。
它只能：

1. 先让 gate 创建或显式替换 envelope；
2. 从 envelope 构造唯一 binding 并登记到账本；
3. 从账本返回的精确 binding 发 dispatch、请求 Scheduler cleanup 或发送 cancel；
4. 对 C2S 先做 ledger 完整相关性预检，再调用 gate 的完整授权/Firewall 审核；
5. 在 owner 退出、解绑、死亡、generation 变化、TTL、替换和停服时以同一 binding 关闭所有
   下游工作。

后台 Scheduler/Provider lane 不得读取或修改账本，更不得调用 Minecraft；它只能向服务器线程
mailbox 报告带有精确 receipt 的终态。

## 被否决方案

### 方案 A：由 gate 或 Scheduler 各自按 botId 管理生命周期

否决。两个系统的寿命、线程和失败语义不同，且 botId 不能区分替换后的新旧请求。

### 方案 B：普通 `open` 自动替换旧 binding

否决。调用者会失去通知旧客户端 HTTP 与 Scheduler 的可靠机会，迟到回调也更容易误伤新 binding。

### 方案 C：账本直接发送 packet、启动 Provider 或调用 Scheduler

否决。这样会把 server-thread session 身份、网络主线程要求、客户端 Key、后台 lane 与
Minecraft 生命周期耦合在一个 DTO 表中，无法独立审计或失败关闭。

### 方案 D：以 requestId 单独作为 close 授权

否决。revision、generation、expiry 与 purpose 漂移时，单一 ID 不能证明这仍是同一 gate
生命周期。

## 兼容性、性能、安全与许可证影响

- 兼容性：账本为新纯 Java 内部对象，不改 R1 payload、客户端 credential 文件或现有 gate wire
  codec；R1 继续使用其独立 ticket book。
- 性能：每个活动通用请求只保存一个已有 immutable binding 和两项 UUID 索引；账本继承 gate
  的“每个 Bot 最多一个活动请求”边界，不形成队列。所有操作是常数 Map 查询或按活动 binding
  数量的 shutdown/expiry 扫描。
- 安全：公共诊断只输出活动数量，不输出 owner、nonce、prompt、schema、credential 或模型结果；
  不匹配的 receipt/payload 不改变账本。
- 许可证：只使用现有 JDK 集合，不引入新依赖。

## 迁移和回滚

当前不接线 Lifecycle、Network、Client 或 Scheduler。未来 bridge 先以此账本建立精确 teardown，
再逐步接入主线程 mailbox。若回滚 bridge，调用 `closeAll()` 并使用返回 binding 清理同一
requestId 的下游工作；不修改 credential，也不触及 P5/world。

## 验证方式

- 纯 Java：普通同 Bot 打开拒绝；替换只接受 gate 的旧精确 receipt；bot/agent/generation/
  requestId/nonce/revision 任一 payload 漂移均不匹配；旧/漂移 receipt 不关闭 replacement；
  TTL 半开边界、bot close 与 shutdown 返回原始 binding；诊断不泄漏 owner、nonce 或 prompt。
- Java 21 自动基线：完整 Gradle build/test 与 GameTest 由本 ADR 对应提交的 GitHub Build
  验证。
- 后续集成：真实 client、Scheduler lane、owner 退出、断线、解绑、死亡、TTL 和迟到 C2S
  必须在主线程协调器中证明只清理同一完整 binding；在此之前不得宣称通用 bridge 或 AI 世界
  执行已经完成。
