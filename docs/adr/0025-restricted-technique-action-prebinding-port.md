# ADR-0025：受限 Technique→Action 预绑定 Port

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0015、ADR-0017、ADR-0023

## 背景

ADR-0017 已冻结 `Action → Technique → Skill` 的分层和单个 owner-thread
`TechniqueLifecycleCoordinator`；ADR-0023 则提供了可供后续地面放置复用的原子
`AimAndPlaceBlock`。两者之间仍缺少一个可审计的交接面：如果一个 Technique route 能直接
提交任意 `ActionEnvelope`，它会绕开 child ticket、generation、L0 优先级和原版消费时序的
精确关联；如果暴露 `BotActionRuntime` 或 Action completion future，又会让回调在 lifecycle
tick 边界之外改变 Technique 状态。

当前唯一生产 route 是有限自卫的单次 `MELEE_ATTACK`，其内部已有专用冻结、精确取消和 owner
thread drain 逻辑。P5D 不能把这条专用路径泛化成未授权的建筑入口，也不能仅因
`AimAndPlaceBlock` 已存在就开始放置世界方块。

## 决策

### 1. 使用 coordinator 发行的 opaque permit，而非原始 Action 提交

新增 `TechniqueActionPermit` 与 `TechniqueActionPort` 抽象基类合同。permit 只能由
`TechniqueLifecycleCoordinator` 在一个已注册 route 正在处理其**精确、活动** child ticket 时
发行；route、run、ticket 和当前 runtime view 必须完全一致。

Port 只有三个公开操作：

```text
submit(permit)
completed(permit)                 // 仅返回 terminal evidence
cancelOrContain(permit, reason)   // 精确物理收口
```

Port 的构造器是 package-private，且绑定一个 route；其 final 方法会先原子地 claim permit，失败
claim 不会到达 Action ingress，而成功 claim 后该 permit 不可重试，不能把同一 child 变成第二个
Action。Port 不接受 `ActionRequest`、
`ActionEnvelope`、supplier、世界 DTO、Minecraft 对象或 caller
completion future。permit 对 Technique 侧只公开 run/ticket/bot/generation/action ID、kind、channel
和 deadline 等 scalar 审计字段；原始 envelope 与 priority 保持 lifecycle adapter 内部可见。

claim 还必须发生在**同一个原始 `submitChild` 调用尚未返回**时、同一 coordinator owner thread
上，并再次确认 run 为 `WAITING_CHILDREN`、ticket 仍精确 `ACTIVE`。因此 retained permit 在 child
返回后、L0 preempt、generation close、shutdown 或 tick 边界之后只能被拒绝，不能进入延迟重试队列。

### 2. 每个 permit 都冻结完整 child→Action 身份

发行时必须同时满足：

- `techniqueRunId`、`skillRunId`、`botId`、generation、ticket ID 和 ticket revision 与当前
  `TechniqueRunView` 完全匹配；
- ticket 仍为 `ACTIVE`，并仍存在于该 run 的 child 列表；
- Action 的 bot/generation、channels、deadline 与 child/run window 完全匹配，Action 不能比
  Technique deadline 存活更久；
- Action ledger idempotency key 必须由该 run/ticket/revision 唯一派生；
- `ActionOrigin` 增加 `TechniqueChildOrigin`，其中精确记录 Technique run、ticket 与 revision，
  并要求同一 `skillRunId`；controller-owned Action 与 technique-child Action 互斥；
- Action kind、channels、deadline 和受限 priority 一经 permit 发行不可变。
- route 必须显式 allowlist 当前 ticket 可绑定的 `ActionKind`；默认拒绝，因此现有 route 不会因
  获得 child ticket 而意外成为泛化 Action 入口。

该合同不让 Action 层持有 `TechniqueRuntime` 对象，也不让 Technique 持有活动 Action backend、
`ControlArbiter` lease 或 Minecraft 对象。

### 3. Technique priority 永远低于 L0

permit 只允许 `BACKGROUND`、`AUTONOMOUS` 或 `OWNER_TASK`。它拒绝 `OWNER_CONTROL`、
`SURVIVAL`、`LIFECYCLE_CLEANUP` 和 `EMERGENCY`，所以 Technique 不能伪装成真人直接控制、
生命周期清理或 L0 安全干预。

物理通道仲裁和抢占仍由 `BotActionRuntime` 与 `ControlArbiter` 权威处理。现有
`SafetyService` 的 handoff/preempt 仍只接有限自卫；本 ADR 不把它改成“按 bot 广播取消所有
Technique”。未来建筑 route 必须先有 manager-owned、精确 run/generation 的 safety handoff
合同，再接入该 Service。

### 4. 终态和取消必须留在 owner-thread drain 时序

`ENQUEUED` 只代表 Action ingress 被接受，不代表 Action 成功、原版包已发出、世界已改变或
Technique 已收到 child signal。Port 只能在 Action 有精确 terminal `ActionOutcome` 后返回
`TechniqueActionTerminal`；route 必须在既有

```text
TechniqueLifecycleCoordinator.tick
→ ActionRuntime.tick
→ drainCompletedChildren
→ finishTick
```

顺序中把它转换为对应 ticket 的 `TechniqueSignal`。不得通过 completion future 直接推进
Technique。

取消使用 permit 的 exact Action identity 和 `ActionCancellationReceipt`。只有
`safelyRetracted()` 的精确 receipt 才能说明未启动；`STARTED`、`TERMINAL`、`ALIAS` 或
`UNKNOWN` 都必须失败关闭、保持 generation 不安全或等待真实终态，不能伪装成已取消。L0、
generation 关闭和停服的 richer Technique reason 会被保留在 port DTO，即使 P2 当前的取消枚举
只能接收其较窄映射。

### 5. 本提交只落地合同，不接建筑能力

本提交不实现 `LifecycleTechniqueActionPort` adapter、不注册 `GroundPlaceTechnique`、不创建
placement candidate、蓝图、材料预留、hotbar 固定、checkpoint、人类覆盖、navigation 或真实
Minecraft 放置。它也不改变现有 self-defense bridge、SafetyService、Action runtime、原版死亡
消费或任何网络/AI 路径。

## 被否决方案

- **公开 `submit(ActionEnvelope)`**：任意 route 会获得泛化世界副作用入口，无法证明它属于当前
  child ticket。
- **让 Technique 读取/改写 `BotActionRuntime` 或 `ControlArbiter`**：会复制仲裁器并破坏 L0
  和 cleanup 权威。
- **把 `EMERGENCY` 复用给建筑 Technique**：会让普通施工竞争或覆盖安全干预。
- **依赖 caller completion future**：会跳过 owner-thread 的 `drainCompletedChildren`/
  `finishTick` 边界，并允许迟到回调改变已封闭 tick。
- **现在接 `AimAndPlaceBlock`/GroundPlace**：材料、工作包、站位、checkpoint、human override
  和精确 safety handoff 尚未存在，不能以 P2 原子动作替代 P5D 授权链。

## 兼容性、性能、安全与许可证影响

这是纯 Java 内部合同和 `ActionOrigin` 的加法。既有 Action origin 构造器保持兼容；现有
navigation、安全、Skill 和自卫路径不产生 technique origin。permit 不引入网络包、持久化格式、
Minecraft 对象、第三方依赖或许可证义务。

所有比较均为固定数量 UUID、枚举、集合和 deadline 检查；不扫描世界、不取得 lease、不读取
背包或菜单。任何 identity、priority、deadline 或 cancellation receipt 不确定性均失败关闭。

## 迁移和回滚

下一阶段只能在 lifecycle owner 中实现一个把 permit 映射到既有 `BotActionRuntime` 的窄 adapter，
并复用自卫 route 的 prebind→同步重入复核→exact cancel-or-contain→owner-thread terminal drain
模式。随后才可为已审核的 route 签发特定 Action kind 的 permit。

若需回滚，删除未被任何生产 route 使用的 permit/port 合同与 `TechniqueChildOrigin` 即可；现有
Action、Skill、自卫、P2 状态机和网络协议无需迁移。

## 验证方式

### 纯 Java

- coordinator 只在精确 child dispatch 内签发 permit，且同一 child 只能得到一份 permit；
- run/ticket/bot/generation/revision/channel/origin/idempotency/deadline/tick budget/priority 不匹配
  全部拒绝；
- public route 不能构造任意 permit，terminal/cancellation 不能跨 permit identity；
- permit 绑定 route、一次性 ingress claim、cross-port reuse 和取消前 ingress 围栏均失败关闭；
- `ENQUEUED` 与 terminal/安全取消语义分离。

### 后续 adapter 与 NeoForge

- synchronous generation close、L0 preempt 和授权撤销发生在 Action ingress 期间时，只能 exact
  cancel-or-contain 一次，迟到 SUCCESS 不能让 Technique 成功；
- 原版死亡消费前先关闭 Technique，且没有 exact child cleanup proof 时 generation 不静止；
- 未来单块 `AimAndPlaceBlock` 的真实 GameTest 覆盖 target/support/material drift、取消、换代和
  safety；之后再做真实客户端、独立专用服和 soak。

当前提交仍须通过 GitHub Java 21 `clean build`、JUnit 和 GameTest；在这些证据及后续真实放置
实现之前，P5D 仍为未实现。
