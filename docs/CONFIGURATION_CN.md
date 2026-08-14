# BotPlayer 配置说明

本文描述当前 server 配置、客户端本地凭据存储和 P6-R1 的本地只读审阅 opt-in。P2 提供
动作运行时容量与 bot 自身背包查看距离；P3 实现新增有限感知的范围、读取预算、事件/事实
容量和 MSPT 降级阈值。
P4 新增导航快照/A*、安全反射和默认关闭的 Terrain Assist 配置。P3、P4 配置分别通过
Build #28、Build #97 自动化门；通用 AI Provider、模型调用、技能和记忆配置仍不可用。P6-R1
是默认关闭的固定审阅往返，已通过
[Build #354](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31756795111) Java 21 自动验证；
真实客户端/Provider E2E 仍待验证，不代表 P6 完成。实时
状态见 [当前实现状态](IMPLEMENTATION_STATUS_CN.md)，P4 边界见
[P4 完成验收报告](P4_COMPLETION_REPORT_CN.md)。

## 配置文件

BotPlayer 当前注册一个 NeoForge `SERVER` 配置，默认文件名：

```text
botplayer-server.toml
```

世界覆盖文件通常位于：

- 客户端单人世界：`.minecraft/saves/<world>/serverconfig/botplayer-server.toml`
- 专用服务器：`<server>/world/serverconfig/botplayer-server.toml`

NeoForge 默认从物理端配置目录加载：客户端为 `.minecraft/config`，专用服务器为
`<server>/config`。`SERVER` 类型还可以被当前世界的 `serverconfig` 文件覆盖，并同步给
客户端。服主也可以使用实例的 `defaultconfigs` 机制为新世界预置文件；它不替代
`<server>/config` 的基线来源。最终以启动日志和当前世界覆盖文件为准。

建议停止服务器后编辑，再重新启动。不要依赖开发阶段的热重载行为。

## 当前 server 配置项

| TOML 键 | 类型 | 默认值 | 范围 | 当前作用 |
|---|---|---:|---:|---|
| `server_player.maxBots` | 整数 | `8` | `1..128` | 本次服务器运行期允许的最大在线 bot 数 |
| `server_player.showInPlayerList` | 布尔 | `true` | `true/false` | `BotServerPlayer.allowsListing()` 是否允许显示在 Tab |
| `server_player.autoRespawn` | 布尔 | `true` | `true/false` | 死亡后是否请求原版重生 |
| `server_player.respawnDelayTicks` | 整数 | `20` | `0..1200` | 自动重生等待 Tick；正常 20 TPS 时 20 Tick 约 1 秒 |
| `server_player.chunkTrackingRefreshTicks` | 整数 | `10` | `1..200` | 刷新连接位置和玩家区块跟踪的间隔 |
| `actions.mailboxCapacity` | 整数 | `1024` | `2..65536` | 等待服务器线程处理的动作/取消命令总容量 |
| `actions.ledgerCapacity` | 整数 | `4096` | `1..65536` | 动作幂等 ledger 保留的 canonical 条目容量 |
| `actions.commandsPerTick` | 整数 | `128` | `1..4096` | 每个服务器 Tick 最多处理的动作 mailbox 命令数 |
| `actions.activeCapacity` | 整数 | `512` | `1..16384` | 所有 BotPlayer 合计最多活跃动作数 |
| `actions.completionCapacity` | 整数 | `2048` | `2..65536` | 等待 callback dispatcher 的完成通知容量 |
| `inventory.viewDistance` | 浮点数 | `8.0` | `1.0..64.0` | 同维度玩家可编辑 bot 自身背包的最大距离（方块） |
| `perception.visualRange` | 浮点数 | `24.0` | `4.0..64.0` | 注视射线与视觉语义事件的已加载世界最大范围 |
| `perception.entityRadius` | 浮点数 | `24.0` | `4.0..64.0` | 附近实体候选的已加载世界最大半径 |
| `perception.localBlockRadius` | 整数 | `1` | `0..2` | bot 脚下已加载方块小邻域半径；不是视距体素扫描 |
| `perception.hearingRange` | 浮点数 | `32.0` | `4.0..128.0` | 语义听觉复核上限；原版已路由声音包仍是首要依据 |
| `perception.entityReadsPerBot` | 整数 | `64` | `1..512` | 正常压力下每 bot 每次感知最多读取的实体候选数 |
| `perception.blockReadsPerBot` | 整数 | `96` | `1..1024` | 正常压力下每 bot 每次感知最多读取的已加载方块数 |
| `perception.raycastsPerBot` | 整数 | `24` | `1..256` | 正常压力下每 bot 每次感知最多执行的视线射线数 |
| `perception.eventReadsPerBot` | 整数 | `128` | `1..1024` | 每 bot 每 Tick 在权威投影池、公开传感器池各自最多读取的事件候选数 |
| `perception.inventoryReadsPerBot` | 整数 | `64` | `41..512` | 每次背包采样最多读取的槽位数；有效最小值覆盖完整 41 格玩家库存 |
| `perception.globalWorkPerTick` | 整数 | `4096` | `64..65536` | 所有 bot 共用的权威投影与公开传感器预算工作单元总额 |
| `perception.authorityEventCapacity` | 整数 | `4096` | `64..65536` | 普通 authority audit、spatial projection、routed sound audit 三个 ring 各自的容量 |
| `perception.eventCapacityPerBot` | 整数 | `512` | `32..8192` | 每 generation 的非声音语义 ring 与声音 ring 各自的容量 |
| `perception.pendingEventCapacity` | 整数 | `2048` | `32..8192` | post-state 验证队列与定向声音候选队列各自的有界容量 |
| `perception.factCapacityPerBot` | 整数 | `2048` | `64..65536` | 每个 bot 保留的短期世界事实上限 |
| `perception.revisionScopeCapacity` | 整数 | `8192` | `64..65536` | 服务器保留的精确 block/entity/container revision scope 上限 |
| `perception.recentEventLimit` | 整数 | `64` | `8..512` | 单个快照的 `recentEvents` 与 `recentSounds` 各自上限 |
| `perception.activityWindowTicks` | 整数 | `400` | `20..12000` | 确定性玩家活动推断的滑动窗口 |
| `perception.degradeMspt` | 浮点数 | `45.0` | `5.0..200.0` | EWMA MSPT 达到该值时进入降级感知 |
| `perception.criticalMspt` | 浮点数 | `50.0` | `5.0..200.0` | EWMA MSPT 达到该值时进入临界感知 |
| `perception.recoverMspt` | 浮点数 | `38.0` | `1.0..199.0` | 降级/临界恢复到正常所需的低水位 |
| `perception.criticalRecoverMspt` | 浮点数 | `45.0` | `1.0..199.0` | 离开临界感知所需水位 |
| `safety.criticalHealth` | 浮点数 | `4.0` | `0.0..2048.0` | L0 停止普通任务的生命阈值 |
| `safety.criticalFood` | 整数 | `4` | `0..20` | L0 停跑并阻塞消耗型远行的食物阈值 |
| `safety.criticalAir` | 整数 | `40` | `0..300` | 水下上浮干预阈值 |
| `safety.criticalFrozenTicks` | 整数 | `100` | `0..1000` | 冻结风险阈值 |
| `safety.entityRadius` | 浮点数 | `12.0` | `1.0..32.0` | 每 Tick 有界近场实体读取半径 |
| `safety.hostileRadius` | 浮点数 | `10.0` | `1.0..entityRadius` | 已锁定 Bot 的敌对生物撤退半径 |
| `safety.projectileRadius` | 浮点数 | `10.0` | `1.0..entityRadius` | 来袭弹射物闪避半径 |
| `safety.explosionRadius` | 浮点数 | `12.0` | `1.0..entityRadius` | 已点燃爆炸物撤离半径 |
| `safety.maximumEntityReads` | 整数 | `32` | `1..128` | 每 Bot 每 Tick 接受的威胁摘要上限 |
| `safety.maximumRawEntityReads` | 整数 | `256` | `maximumEntityReads..2048` | 每 Bot 每 Tick 原始实体回调上限 |
| `safety.clearStableTicks` | 整数 | `20` | `1..100` | 危险解除后恢复导航所需连续安全 Tick |
| `safety.maximumInterventions` | 整数 | `6` | `1..16` | 单个 incident 的物理干预上限 |
| `safety.retreatInputTicks` | 整数 | `3` | `2..5` | 每次逃生输入短租约 |
| `safety.maximumSafeDrop` | 整数 | `3` | `0..4` | L0 认为前方仍安全的最大落差 |
| `navigation.horizontalRadius` | 整数 | `24` | `4..48` | 单次局部运动快照水平半径 |
| `navigation.verticalRadius` | 整数 | `8` | `2..16` | 单次局部运动快照垂直半径 |
| `navigation.snapshotCellsPerBotTick` | 整数 | `2048` | `64..8192` | 单 Bot 每 Tick 快照采样上限 |
| `navigation.snapshotGlobalCellsPerTick` | 整数 | `8192` | `snapshotCellsPerBotTick..65536` | 所有 Bot 共享的每 Tick 快照预算 |
| `navigation.maximumSnapshotTicks` | 整数 | `20` | `1..100` | 单个快照构造期限 |
| `navigation.maximumExpansions` | 整数 | `50000` | `100..250000` | 单次 A* 节点扩展上限 |
| `navigation.maximumConcurrentPlans` | 整数 | `2` | `1..8` | 后台规划并发上限 |
| `navigation.maximumQueuedPlans` | 整数 | `16` | `1..128` | 后台规划等待队列上限 |
| `navigation.maximumGoalDistance` | 整数 | `2048` | `16..16384` | 同维度目标最大水平距离 |
| `navigation.followerInputTicks` | 整数 | `3` | `2..5` | follower 每次普通输入租约 |
| `navigation.stuckWindowTicks` | 整数 | `20` | `5..40` | 动作后端无进展检测窗口 |
| `navigation.waypointTolerance` | 浮点数 | `0.45` | `0.1..1.0` | 水平 waypoint 容差；垂直节点另做严格复核 |
| `navigation.minimumSprintFood` | 整数 | `7` | `minimumTravelFood..20` | 允许 follower 发 sprint 的最小食物值 |
| `navigation.minimumTravelFood` | 整数 | `5` | `0..20` | 允许继续普通远行的最小食物值 |
| `navigation.minimumTravelHealth` | 浮点数 | `6.0` | `0.0..2048.0` | 允许继续普通远行的最小生命值 |
| `navigation.allowTerrainBreak` | 布尔 | `false` | `true/false` | 服务端是否允许请求显式授权的局部通道挖掘 |
| `navigation.maximumTerrainBlocksBroken` | 整数 | `4` | `0..8` | 单次导航 Terrain Assist 破坏硬上限 |
| `navigation.allowTerrainPlace` | 布尔 | `false` | `true/false` | 服务端是否允许请求显式授权的简单搭桥 |
| `navigation.maximumTerrainBlocksPlaced` | 整数 | `4` | `0..8` | 单次导航 Terrain Assist 放置硬上限 |
| `permissions.commandPermissionLevel` | 整数 | `2` | `0..4` | 使用 `spawn/list/remove` 所需权限等级；P3/P4 管理诊断固定要求等级 2 |

## 当前 server TOML 示例

```toml
[server_player]
maxBots = 8
showInPlayerList = true
autoRespawn = true
respawnDelayTicks = 20
chunkTrackingRefreshTicks = 10

[actions]
mailboxCapacity = 1024
ledgerCapacity = 4096
commandsPerTick = 128
activeCapacity = 512
completionCapacity = 2048

[inventory]
viewDistance = 8.0

[perception]
visualRange = 24.0
entityRadius = 24.0
localBlockRadius = 1
hearingRange = 32.0
entityReadsPerBot = 64
blockReadsPerBot = 96
raycastsPerBot = 24
eventReadsPerBot = 128
inventoryReadsPerBot = 64
globalWorkPerTick = 4096
authorityEventCapacity = 4096
eventCapacityPerBot = 512
pendingEventCapacity = 2048
factCapacityPerBot = 2048
revisionScopeCapacity = 8192
recentEventLimit = 64
activityWindowTicks = 400
degradeMspt = 45.0
criticalMspt = 50.0
recoverMspt = 38.0
criticalRecoverMspt = 45.0

[safety]
criticalHealth = 4.0
criticalFood = 4
criticalAir = 40
criticalFrozenTicks = 100
entityRadius = 12.0
hostileRadius = 10.0
projectileRadius = 10.0
explosionRadius = 12.0
maximumEntityReads = 32
maximumRawEntityReads = 256
clearStableTicks = 20
maximumInterventions = 6
retreatInputTicks = 3
maximumSafeDrop = 3

[navigation]
horizontalRadius = 24
verticalRadius = 8
snapshotCellsPerBotTick = 2048
snapshotGlobalCellsPerTick = 8192
maximumSnapshotTicks = 20
maximumExpansions = 50000
maximumConcurrentPlans = 2
maximumQueuedPlans = 16
maximumGoalDistance = 2048
followerInputTicks = 3
stuckWindowTicks = 20
waypointTolerance = 0.45
minimumSprintFood = 7
minimumTravelFood = 5
minimumTravelHealth = 6.0
allowTerrainBreak = false
maximumTerrainBlocksBroken = 4
allowTerrainPlace = false
maximumTerrainBlocksPlaced = 4

[permissions]
commandPermissionLevel = 2
```

键名大小写必须与生成文件一致。

## 使用建议

### `maxBots`

这只是在线数量硬上限，不代表服务器已经通过 128 bot 的性能验证。当前没有多 bot soak、
感知或 AI 压力测试，开发测试建议保持较小数量。

### `showInPlayerList`

关闭后只影响 `allowsListing()`；它不会把 bot 从 `PlayerList` 或世界中移除，也不是隐身、
权限或反作弊绕过功能。

### `autoRespawn`

关闭时，死亡 bot 保持 `DEAD`，当前没有手工重生子命令。重新打开配置后，管理器在后续
Tick 可以重新安排重生，但动态配置更改还没有专门 GameTest。

### `respawnDelayTicks`

`0` 表示达到管理器 Tick 时尽快请求重生，并不保证在死亡调用栈内部立即重生。

### `chunkTrackingRefreshTicks`

较小值会增加服务端工作量。它用于保持无物理客户端玩家的区块跟踪位置，不是独立的强制
区块加载器，也不保证 bot 离线后继续加载区块。

### `actions.mailboxCapacity`

这是等待服务器主线程接收的动作与取消命令的总容量。达到上限后新提交会被拒绝，而不是
无限堆积。最小值 `2` 用于给普通提交与取消至少留下可用空间；它不是“每 bot”容量。

### `actions.ledgerCapacity`

幂等 ledger 保存 canonical 动作及其进行中/终态信息。已完成条目可以按有界策略淘汰，
进行中条目不会为容纳新请求而被随意驱逐。容量过小会更快失去历史重放窗口；容量过大则
增加常驻内存。它不能代替持久任务历史。

### `actions.commandsPerTick`

限制一个服务器 Tick 内从 mailbox 处理的动作与取消命令数，用于控制尖峰主线程成本。
调高该值会缩短排队时间，也可能增加单 Tick 负载；它不改变动作自己的 deadline 或
`maxTicks`。

### `actions.activeCapacity`

限制整个服务器当前可处于校验、运行或验证阶段的动作数。达到上限的新动作会得到容量失败，
不会绕过通道仲裁。该值不是每个 bot 的并行度；同一 bot 仍受动作通道租约约束。

### `actions.completionCapacity`

限制等待异步 completion callback 的通知数。dispatcher 有界并对入口施加背压，避免阻塞
callback 把服务器长期运行变成无界线程/队列增长。调大前应先定位 callback 消费变慢的原因。

### `inventory.viewDistance`

真人只有在与活动 bot 同一维度、双方存活且距离不超过该值时，才能打开或继续编辑 bot
自身背包。会话期间每 Tick 复核；超距、死亡、换维度或退出会关闭会话。该配置只作用于
bot 自身 41 格库存 GUI，不开启箱子、工作站或模组容器自动化。

### `perception.visualRange`、`entityRadius`、`localBlockRadius` 与 `hearingRange`

这些值是感知上限，不是区块加载半径：

- 视觉射线遇到未加载区块就停止并报告未知；
- 实体只从已加载局部候选中读取，并继续受视线、读取数和射线预算约束；
- `localBlockRadius` 只控制脚下固定小邻域，默认 `1`、最大 `2`，用于地面和近身环境，
  不会扫描整个视觉范围；
- 听觉优先以原版已发送给具体 bot 的位置/实体声音包为依据；其可听范围服从服务端封包
  路由，不再使用 `hearingRange` 二次裁剪。`hearingRange` 只约束普通权威语义事件的
  `AUDIBLE` fallback，不会把全服声音广播给每个 bot。

提高范围会放大候选数，但不会绕过读取预算，也不会使未加载世界变成可见。

### `perception.*ReadsPerBot` 与 `globalWorkPerTick`

`entityReadsPerBot`、`blockReadsPerBot`、`raycastsPerBot`、`eventReadsPerBot` 和
`inventoryReadsPerBot` 是一个 bot 在正常压力下的分类预算。当前候选把
`globalWorkPerTick` 的四分之一分配给服务器私有权威投影，四分之三分配给公开传感器；
所有 bot 分别共享这两个池。每 bot 分类上限也在两池独立执行，其中快照只报告公开传感器
池，因此未感知的权威事件不会挤占或改变公开预算。`entityReadsPerBot` 还在运行时分为
威胁、注视实体和普通局部实体子配额，威胁先采样；它不是三种传感器都能各自消费一次的
独立总额。多个 bot 共享全局池时逐 Tick 轮转采样起点，降低固定排序造成的长期头部偏置。
实体索引的原始回调另有从 `entityReadsPerBot` 派生的 `ENTITY_SCAN` 分类预算，每次回调
同时计入全服工作池；被过滤的中立实体也不会成为未记账工作。预算耗尽时快照会标记相应
公开传感器截断，不会继续无界读取。`globalWorkPerTick` 的最小值为 64，确保按 1/4、3/4
切池后公开池仍能原子读取完整 41 槽背包。降低这些值可减少主线程
工作，但规划器必须接受观察不完整；提高前应先取得多 bot MSPT/soak 证据。玩家背包固定
有 41 个可观察槽位，因此配置层不允许
`inventoryReadsPerBot` 小于 41；运行时构造仍保留防御性兜底，避免绕过正常配置入口时把
自身库存快照悄悄截成半份。SELF 状态必须来自快照当前 Tick，背包超过 20 Tick 未完整
刷新时会撤下快照；这些新鲜度门目前不是可配置项。定向声音归并与 post-state 候选复核不使用
`globalWorkPerTick`，但分别受独立的每 Tick drain/flush 上限和有界队列约束。

### `perception.*Capacity`

`authorityEventCapacity` 分别限制普通 authority audit、只收 `VISUAL/AUDIBLE` 候选的
spatial authority projection 和 routed sound audit 三个 ring；三者共享唯一 authority
`eventSeq`。`SELF/DIRECT` 或定向声音洪泛不会逐出空间候选；同 Tick 空间事件超预算时
只选 spatial ring 的最新有界窗口，遗漏计入管理员 coverage 诊断。
`eventCapacityPerBot` 分别限制每个
`(botId, generation)` 的非声音语义 ring 和声音 ring；二者共享 local `perceivedSeq`，
声音洪泛不会逐出 action/block/damage/activity 证据。`pendingEventCapacity` 分别限制
待 post-state 验证队列和声音候选队列，`factCapacityPerBot` 限制短期事实，
`revisionScopeCapacity` 限制精确 revision scope。事件/事实结构达到容量时采用有界淘汰
或丢弃；普通 audit coverage gap 只记录管理员私有诊断并快进内部 cursor，不改变 bot
事实、认知水位或公开预算。AI-safe
快照不会暴露 authority 游标或全服计数。revision scope 使用有界 LRU，淘汰后重入时以
全局内部单调值作为新纪元，当前没有 scope eviction 回调或逐项淘汰统计。这些数据都不是
P7 长期记忆，不能靠调大容量获得持久历史。P3 的 `container` scope 只让 opaque
block-entity 位置事实随对应方块变化失效；它不表示内容 revision。P3 也不因
`UseOnBlock` 成功就推测容器内容已变化，真正内容事件与事务留到 P5。若
`recentEventLimit` 高于 `eventCapacityPerBot`，运行时会记录警告并把快照
`recentEvents`/`recentSounds` 的各自上限降到对应 ring 容量。

post-state break/place/toss 候选在进入待验证队列时同时捕获 bot generation，验证后用
仅内部可见的 `routing.*` 元数据定向原 generation；这些字段不会出现在认知投影。
动作终态使用独立 critical ingress，其硬上限与 P2 单 Tick 最大 canonical 吞吐对齐：
成功 break 按 2 个发布单位计，其余终态按 1 个单位计。该上限不是配置项，也不随较小的
`pendingEventCapacity` 一起缩小；常规候选仍受 `min(pendingEventCapacity, 2048)` 的
每 Tick ingress/flush 上限约束。

声音候选的全局容量虽然由 `pendingEventCapacity` 提供，但实际按活动
`(botId, generation)` 分队列：每个 generation 受动态公平份额约束，满载时优先从最大
队列淘汰旧候选，消费使用 round-robin 并轮转起点，单队列保持封包产生顺序。快照侧的
`SoundEventSensor` 在事件预算不足时先选择历史 ring 中的最新声音，再按认知序号恢复
时间顺序。该机制避免单 bot 明显独占入口，
仍不等于已经通过多 bot 声音洪泛 soak。

### `perception.recentEventLimit` 与 `activityWindowTicks`

前者限制一次快照携带的最近认知事件，后者控制活动推断的滑动时间窗口（默认 400 Tick，
正常 20 TPS 下约 20 秒）。增大窗口会保留更旧证据，但不会让未被该 bot 感知的权威事件
进入推断，也不会开启聊天或长期记忆。

### `perception.*Mspt`

四个阈值必须满足：

```text
recoverMspt < degradeMspt < criticalMspt
recoverMspt <= criticalRecoverMspt < criticalMspt
```

不满足时运行时记录警告并使用安全默认值 `38 / 45 / 45 / 50`。控制器使用 EWMA 与恢复
滞回，避免单个慢 Tick 让传感器频率来回抖动。`CRITICAL` 会暂停局部方块和普通实体等
非关键扫描；预算与降级代码存在不等于已经通过多 bot 性能门。

### `safety.*`

L0 每 Tick 运行，不依赖 P3 传感器频率。三个具体威胁半径都受 `entityRadius` 总边界
约束；`maximumRawEntityReads` 不能小于 `maximumEntityReads`。调大半径或原始回调数会
直接增加每 Bot 每 Tick 工作量，未取得多 Bot soak 证据前不要提高。

临界生命、食物、空气和冻结值是保守抢占阈值，不是药水/食物技能策略。P4 在这些阈值下
可以停止、上浮或撤退，但不会主动进食、喝药、换甲或战斗。

### `navigation.*`

`horizontalRadius/verticalRadius` 控制单个不可变局部快照；远目标由滚动 frontier 分段，
不会因提高 `maximumGoalDistance` 自动加载未知区块。单 Bot 快照预算不得高于全局预算。
规划工作池和队列满时请求以 `SERVER_OVERLOADED` 失败，不会无限堆积。

`minimumSprintFood` 必须不低于 `minimumTravelFood`。低于 sprint 阈值时 follower 改用
普通移动；低生命或低食物的长距离请求返回 `SUPPLY_REQUIRED`。这些门不等于 P5 的补给技能。

Terrain Assist 的两个 `allow*` 默认必须保持 `false`。即使服务端打开，请求 policy 仍须
逐次显式允许，实际数量取请求预算与服务端预算的较小值。`navigation go` 管理命令使用
`safeDefault()`，不会开启 Terrain Assist。保护事件拒绝或结果不匹配时动作终止，不靠
自动重试绕过保护。

### `commandPermissionLevel`

原版等级大致为：

| 等级 | 常见含义 |
|---:|---|
| 0 | 所有人 |
| 1 | 绕过生成保护等有限权限 |
| 2 | 常用管理命令，当前默认 |
| 3 | 玩家管理 |
| 4 | 最高管理权限 |

生产服务器不要为了方便将它设为 `0`。当前这个值只控制 `spawn`、`list` 和 `remove`；
`perception inspect/correct`、`navigation go/stop/inspect` 与 `safety inspect` 为避免
局部知识、世界修改和诊断信息泄露，固定要求原版权限等级 `2`，不随该配置降级。
`/botplayer settings <name>` 不读取这个值：它只允许真实玩家，并精确比较 roster 中
持久 owner；提高 OP 等级或降低该配置都不能打开别人的凭据界面。trusted/observer ACL
尚未实现。

## 尚不存在的配置

下列内容只在架构路线图中设计，当前 server TOML 中不存在：

- 除 P6-R1 固定本地审阅外的 DeepSeek provider、模型、API URL、超时和预算；
- owner、trusted、observer 和动作 ACL；
- 面向普通玩家的逐请求挖掘/搭桥授权、PVP 和高风险确认；
- 自定义导航代价 profile、跨维度路线和多 Bot 动态 MSPT 导航降级；
- 通用世界容器、工作站和模组 menu 的距离、事务和适配策略；
- 长期记忆、聊天保存和数据保留；
- 自动加载 roster 和每 bot 独立策略。

不要自行添加这些键并期待生效。

## 客户端本地凭据

API Key 不属于 NeoForge `SERVER` 配置，也不写进普通 `CLIENT` TOML。客户端通过本地 GUI
维护：

- credential profile：保存 profile ID、固定的 `deepseek` provider 标签和 Key；
- bot binding：以 `(serverInstanceId, ownerUuid, botId)` 绑定一个 credential profile；
- agentId：每个 bot 独立生成，不能因共用 credential profile 而共用智能体状态。

文件位置（`<client-game-dir>` 是当前客户端游戏目录，默认启动目录通常是 `.minecraft`）：

```text
<client-game-dir>/config/botplayer/
  credentials-v1.json   # 明文 Key；不要分享
  bindings-v1.json      # server/owner/bot/profile/agent ID；不含 Key
  review-only-v1.json   # 只有 P6-R1 本地 enabled 位；默认 false，不含 Key
```

同一个 credential profile 可以绑定给 owner 的多个 bot，Key 只保存一次。在相同 profile
ID 下输入新 Key 会替换共享 Key，并影响所有引用该 profile 的本地 bot binding；Key 输入留空
会继续使用已有 profile。当前支持创建/替换 profile 和绑定/解绑 bot，不支持删除 credential
profile；解绑不会删除共享 Key。

### P6-R1 本地只读审阅开关

`review-only-v1.json` 不是 NeoForge `SERVER`/`CLIENT` TOML，不会被服务器读取、覆盖或同步。
以下行为是已通过 Build #354 Java 21 自动验证、但真实客户端/Provider E2E 仍待验证的 P6-R1
窄路径，不代表通用 AI 功能已可用。
首次物理客户端启动会原子创建并加载以下唯一 schema；若文件缺失，行为等同于 `false`：

```json
{
  "schemaVersion": 1,
  "reviewOnly": {
    "enabled": false
  }
}
```

本地用户把 `reviewOnly.enabled` 改为 `true`，并重新启动客户端，才会安装
R1 Provider；后续仍只有服务器持久 owner 能通过 owner/binding gate 发起审阅。它固定为
`deepseek-chat`、`CHAT|TOOL_CALLS` 和至少 256 output tokens；唯一工具是零参数
`botplayer_review_snapshot`。文件不接受、也不能扩展 endpoint、model、provider、key、credential
profile、tool catalog 或 prompt。未知字段、错误类型、错误 schema 或超过 1 KiB 的文件会失败关闭，
不会被自动覆盖。

每次审阅只使用同一 bot generation 的已完成感知快照：命令 Tick 本身或紧邻前一 Tick（年龄
只能为 `0` 或 `1`）；未来快照和早于两 Tick 的快照都会拒绝。它保留快照原有的 ID/Tick，
不会为命令额外读取世界以“刷新”输入。

关闭开关或每次重载都会推进客户端连接 epoch、取消正在运行的本地 Provider session、清空
session controller 与 factory；这不会发送 C2S 取消包。保存 Key、替换 profile 或绑定 bot 只会
取消受影响的已有 session，绝不会把本开关由 `false` 改为 `true`。当前没有把此开关塞进凭据
Screen，以免保存 Key 隐式变成付费 Provider opt-in。

保存、替换或解绑某个 bot binding 会先推进该 bot 独立的本地 binding epoch，再取消其
session；因此已经交给 Minecraft 线程队列的旧回传，即使重新保存后仍使用相同的 agentId/profile，
也不会发送 C2S proposal。后续新的有效请求会取得新 epoch；此类普通 binding 变化不会推进或
影响其他 bot 的 connection epoch。

本地文件当前是明文存储。实现优先使用原子替换，文件系统不支持时退化为同目录覆盖，并
尽力收紧文件权限；它不是加密、操作系统 keychain 或防本机恶意软件的安全区。在支持
POSIX 权限的文件系统上，目录尽力设为仅 owner 可读/写/进入，文件尽力设为仅 owner
可读写；Windows 或不支持 POSIX 的文件系统不能保证这些位。不要把文件放入云同步、支持
包、截图、Git 或公开备份。损坏或未知 schema 会让本次客户端运行拒绝加载和覆盖，不能从
服务端恢复 Key。

当前输入限制：

| 字段 | 规则 |
|---|---|
| profile ID | 1–64 位；首位字母或数字，后续可用字母、数字、`.`、`_`、`-` |
| API Key | 8–512 位，不允许空白或控制字符 |
| provider | credential profile 固定为 `deepseek`；只有另行开启 P6-R1 本地开关时才可用于固定审阅，不代表通用 Provider 已接入 |
| profile 数量 | 每个客户端本地 store 最多 64 个 |
| binding 数量 | 每个客户端本地 store 最多 2048 个 |
| 文件大小 | credential 文件最多 128 KiB；binding 文件最多 1 MiB |

## API Key 传输规则

除 P6-R1 显式本地开启后的固定 review-only HTTPS 请求外，当前没有通用 DeepSeek Provider
或 HTTP 请求。Key 只能从客户端本地 Screen 进入本地凭据存储；原始值和可还原值不会发送给
服务端。

禁止把 Key 放入：

- 聊天或 `/botplayer` 命令；
- `botplayer-server.toml`；
- 普通客户端 TOML、游戏选项或语言资源；
- Minecraft 自定义 payload；
- 世界 NBT、SavedData 或 playerdata；
- 日志、崩溃报告、Issue 和截图。

只有服务端 roster 中持久 owner 与当前玩家一致、并且当前在线的 bot 才能打开界面和修改
服务端运行时 binding。`serverInstanceId` 与 `ownerUuid` 用于隔离不同服务器/owner；
编辑客户端文件不能改变 owner 或获得服务端权限。owner 退出、bot 卸载或停服会清除服务端
active agent binding，但客户端 `bindings-v1.json` 保留。
P6-R1 以及未来使用本地 Key 的 Provider HTTP 都必须在 owner 客户端执行，且 owner 离线时不可用。

如果 Key 已经泄漏，应立即在提供商后台撤销并创建新 Key，不能只删除聊天或日志。

## 配置变更规则

开发者新增或修改 server 配置时必须同时更新：

1. `BotPlayerConfig` 的类型、默认值、范围和注释；
2. 本文的配置表和示例；
3. README 或安装文档中的相关行为；
4. `CHANGELOG.md`；
5. 配置加载、边界值和迁移测试。

改变客户端凭据或 `review-only-v1.json` 格式时还必须同步更新 schema 版本、原子迁移/回滚、
权限处理、删除语义、ADR-0012/ADR-0019 与本地配置测试；不得把“能保存 Key”写成“已经能
调用通用 DeepSeek”。

配置文件位置与 `SERVER` 类型规则可参考
[NeoForge 1.21.1 Configuration 文档](https://docs.neoforged.net/docs/1.21.1/misc/config)。
