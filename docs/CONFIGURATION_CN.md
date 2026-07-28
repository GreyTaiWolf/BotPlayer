# BotPlayer 配置说明

本文描述当前 server 配置和客户端本地凭据存储。P2 提供动作运行时容量与 bot 自身背包
查看距离；P3 候选实现新增有限感知的范围、读取预算、事件/事实容量和 MSPT 降级阈值。
P3 配置已编码但仍待 Java 21 CI 验证；AI Provider、模型调用、寻路、技能和记忆配置仍
不可用。实时状态见 [当前实现状态](IMPLEMENTATION_STATUS_CN.md)，P3 验证缺口见
[P3 完成报告草案](P3_COMPLETION_REPORT_CN.md)。

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
| `permissions.commandPermissionLevel` | 整数 | `2` | `0..4` | 使用 `spawn/list/remove` 所需权限等级；P3 `perception` 固定要求等级 2 |

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
`perception inspect` 与 `perception correct` 为避免局部知识和诊断信息泄露，固定要求
原版权限等级 `2`，不随该配置降级。
`/botplayer settings <name>` 不读取这个值：它只允许真实玩家，并精确比较 roster 中
持久 owner；提高 OP 等级或降低该配置都不能打开别人的凭据界面。trusted/observer ACL
尚未实现。

## 尚不存在的配置

下列内容只在架构路线图中设计，当前 server TOML 中不存在：

- DeepSeek provider、模型、API URL、超时和预算；
- owner、trusted、observer 和动作 ACL；
- 挖掘、放置、PVP、搭桥和高风险确认；
- 路径节点、导航代价和安全反射 Tick 预算；
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
```

同一个 credential profile 可以绑定给 owner 的多个 bot，Key 只保存一次。在相同 profile
ID 下输入新 Key 会替换共享 Key，并影响所有引用该 profile 的本地 bot binding；Key 输入留空
会继续使用已有 profile。当前支持创建/替换 profile 和绑定/解绑 bot，不支持删除 credential
profile；解绑不会删除共享 Key。

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
| provider | 当前固定为 `deepseek`；这不代表 Provider 已接入 |
| profile 数量 | 每个客户端本地 store 最多 64 个 |
| binding 数量 | 每个客户端本地 store 最多 2048 个 |
| 文件大小 | credential 文件最多 128 KiB；binding 文件最多 1 MiB |

## API Key 传输规则

当前没有 DeepSeek Provider 或 HTTP 请求。Key 只能从客户端本地 Screen 进入本地凭据
存储；原始值和可还原值不会发送给服务端。

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
未来使用本地 Key 的 Provider HTTP 必须在客户端执行，且 owner 离线时不可用。

如果 Key 已经泄漏，应立即在提供商后台撤销并创建新 Key，不能只删除聊天或日志。

## 配置变更规则

开发者新增或修改 server 配置时必须同时更新：

1. `BotPlayerConfig` 的类型、默认值、范围和注释；
2. 本文的配置表和示例；
3. README 或安装文档中的相关行为；
4. `CHANGELOG.md`；
5. 配置加载、边界值和迁移测试。

改变客户端凭据格式时还必须同步更新 schema 版本、原子迁移/回滚、权限处理、删除语义、
`SECURITY.md` 和 ADR-0012；不得把“能保存 Key”写成“已经能调用 DeepSeek”。

配置文件位置与 `SERVER` 类型规则可参考
[NeoForge 1.21.1 Configuration 文档](https://docs.neoforged.net/docs/1.21.1/misc/config)。
