# ADR-0021：客户端赞助 AI 请求的单一关联身份

- 状态：Accepted
- 日期：2026-08-13
- 相关：ADR-0012、ADR-0015、ADR-0019、ADR-0020

## 背景

P6 的服务端提案 gate 已经生成 `requestId + nonce`，而通用 `AiRequestScheduler` 又以
`AiRequest.requestId` 维护超时、取消、健康、隔离和完成记录。如果客户端 dispatch 从一个
request template 构造、调度器从另一个 template 构造、gate 再自行生成关联 ID，就会形成三个
不同身份。结果可能是：取消关闭旧 HTTP，但 Scheduler 仍等待另一个 ID；迟到 C2S 回传被错误
归类；或健康与熔断记录归到错误的 Bot 请求。

P6-R1 只读审阅刻意绕过通用 Scheduler，不能把它当作通用客户端赞助请求的关联证明。

## 决策

### 1. Gate 是关联 ID 的唯一来源

生命周期必须先在服务器线程由 `AiProposalSessionGate.open(...)`，或由协调器专用的
`openReplacing(...)`，生成不可预测的
`requestId` 与 `nonce`。请求模板只能携带模型、消息、输出约束和选项；模板中的
`requestId` 不具有会话意义，必须被 gate ID 替换。

### 2. 使用一个绑定对象构造两条下游路径

新增 `AiClientSponsoredRequest`。它由一个 gate envelope 构造且不可变地同时提供：

- `AiClientRequestDispatch`：发给精确 owner 客户端的无 credential 请求；
- `AiScheduledRequest`：交给 Scheduler 的请求，内部 `AiRequest.requestId` 必须等于同一 gate ID；
- 精确 `AiRequestCancellationPayload`：只可取消该完整关联；
- 对 C2S payload 和 gate terminal receipt 的纯关联比较。

任何未来通用 client-sponsored Provider bridge 都不得手工分别构造上述对象，也不得让客户端、
模型或 request template 选择 `requestId`、nonce、owner、bot、agent、generation、revision、
purpose 或 TTL。普通 `open(...)` 拒绝隐式替换；需要替换时必须使用 `openReplacing(...)` 并先
处理返回的旧 envelope。异步终态只能携带本绑定导出的完整 `AiRequestDispatchReceipt` 调用
`closeExact(receipt)`，不得用 botId 关闭新请求。

### 3. 终态和过期顺序

服务端 tick TTL 是权威期限，区间为 `[issuedAtTick, expiresAtTick)`；客户端 wall-clock 仅用于
提前停止本地 HTTP，不能延长 server gate。取消、owner 退出、解绑、死亡、generation 变化、
替换、停服和 TTL 到期必须用同一绑定生成精确 C2S cancel，并关闭 gate 和 Scheduler 的同一
`requestId`。只有 gate 已消费完整关联后的 terminal receipt 才可使 Provider/Scheduler 的该
requestId 结束。

### 4. 不改变执行权威

本 ADR 只解决请求身份，**不**启用模型到世界的执行。C2S 结果仍是不可信输入，必须经过
gate、ToolCallCodec、ToolFirewall、owner/agent/generation/revision/TTL 重验，以及未来 P5
端口的实时世界和授权复核。`AiClientSponsoredRequest.matches(...)` 只是廉价关联预检，不能
替代这些检查。

## 被否决方案

### 方案 A：Scheduler 使用 request template ID

否决。模板会被复用，且该 ID 不由 server gate 生成，无法与 nonce、取消和 C2S 回传形成一一
对应关系。

### 方案 B：只按 botId 取消或匹配响应

否决。同一 Bot 的旧请求、重绑、generation 更换和延迟网络包会误伤新请求。

### 方案 C：由客户端声明 requestId 或替换 nonce

否决。客户端和模型结果不拥有会话、权限或重放防护权。

### 方案 D：让 gate 收到回传就直接结束/执行 Scheduler 任务

否决。gate 只负责关联和静态审核；Scheduler 的清理、发布和后续 P5 实时复核必须各自保留
有界、可观察的终态。

## 兼容性、性能、安全与许可证影响

- 兼容性：这是服务端内部绑定合同，不改变现有 R1 payload 版本或客户端凭据格式；
- 性能：绑定为常数大小 DTO，不创建线程、网络请求或 Minecraft 活对象；
- 安全：诊断不输出 nonce、owner、prompt、schema、工具参数或 credential；完整关联比较拒绝
  任一字段不符的 C2S 结果；
- 许可证：只使用现有 JDK 与项目 DTO，不新增依赖。

## 迁移和回滚

现有 P6-R1 维持其固定只读、无 Scheduler 路径。未来通用 Provider bridge 必须先使用本绑定，
再接入生命周期队列；若回滚 bridge，关闭同一绑定的 gate/HTTP/Scheduler 请求，不修改本地
credential、P5 Skill 或世界状态。

## 验证方式

- 纯 Java：template ID 被 gate ID 替换；dispatch、Scheduler 和 cancel 使用同一 ID；bot/agent/
  generation/nonce/revision 任一漂移均不匹配；half-open tick TTL；receipt purpose/expiry 失配；
- 后续集成：断线、解绑、替换、超时和迟到 C2S 都只关闭同一 requestId；Scheduler health、
  cancellation receipt 与 gate receipt 可交叉核对；
- Java 21/NeoForge：完整 Gradle、payload codec 和生命周期清理必须在正式 CI 中验证；
- 审计：本 ADR 对应代码不得调用 `SkillRuntime`、Action、Technique 或世界修改。
