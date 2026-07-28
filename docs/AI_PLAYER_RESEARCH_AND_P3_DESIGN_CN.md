# BotPlayer P3 感知与世界模型调研设计

> 文档状态：研究结论与候选实现设计 v1
>
> 更新日期：2026-07-28
>
> 适用分支：`agent/p3-perception`
>
> 验证状态：生产代码与测试来源已开始接线；受当前环境 JDK/网络限制，仍待 Java 21 CI
> 执行严格编译、单元测试和 NeoForge GameTest

本文回答 P3 的核心问题：一个服务器内真实 `ServerPlayer` bot 应当知道什么、如何知道、
什么时候必须承认“不知道”，以及怎样把短期观察变成可失效而非永久正确的世界事实。

当前代码事实见 [当前实现状态](IMPLEMENTATION_STATUS_CN.md)，验证结果与缺口见
[P3 完成报告草案](P3_COMPLETION_REPORT_CN.md)。本文是设计与调研记录，不是测试通过证明。

## 1. 结论摘要

P3 采用“权威事件平面 + 每 bot 认知平面”，而不是把服务器能访问的数据全部交给 bot：

```text
动作结果 / NeoForge 事件 / 定向客户端声音包
                    ↓
              AuthorityEvent
                    ↓
       距离、视线、维度、目标与预算投影
                    ↓
      (botId, generation) PerceivedEvent
                    ↓
   不可变 ObservationSnapshot / 短期 WorldFact
                    ↓
      有证据、带置信度的玩家活动假设
```

这条链路固定以下边界：

- `AuthorityEvent` 是服务器审计与结果验证事实，不自动成为任何 bot 的知识；
- `PerceivedEvent` 重新构造并脱敏认知载荷，只保留 opaque `authorityEventId`，不暴露
  权威会话、全局序号、world revision 或完整权威载荷；
- 视觉只读取已加载世界、受距离和遮挡约束；未知或未加载不等于空气；
- 声音优先使用原版已经路由到该 bot 的客户端声音包，避免把全服声音广播成全知信息；
- 完全未被 bot 感知的世界变化不触碰其事实、认知水位或公开传感器预算，避免用失效时刻
  泄露隐藏变化；已投影但结果不确定的变化或 TTL 才能产生 `STALE_UNKNOWN`；
- 所有事件环、事实、revision scope、候选队列、扫描数、射线数和 DTO 字段都有硬上限；
- P3 不读取箱子、木桶、潜影盒、工作站或模组 menu 的内容。原版世界容器事务仍属于
  P5A/P5B，模组自定义 menu 属于 P8。

## 2. 调研范围

### 2.1 NeoForge 与 Minecraft 事件语义

| 资料 | 关键发现 | BotPlayer 采用方式 |
|---|---|---|
| [NeoForge 事件系统](https://docs.neoforged.net/docs/1.21.1/concepts/events) | 事件可能可取消，注册阶段、优先级和逻辑端决定观察语义 | 可取消事件只登记候选，在同一服务器 Tick 的 Post/感知阶段于世界逻辑之后复核实际状态，再发布 `COMMITTED` |
| [NeoForge GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/) | 必须在真实世界 Tick 中验证遮挡、区块和事件顺序 | P3 GameTest 目标覆盖视觉遮挡、定向声音、知识隔离、事实失效与 generation 清理 |
| [NeoForge 调试分析器](https://docs.neoforged.net/docs/1.21.1/misc/debugprofiler/) | 主线程工作需要有可观察预算和性能证据 | 使用共享工作预算、每 bot 分类预算、EWMA MSPT 与恢复滞回；多 bot soak 仍待后续 |
| [Minecraft `GameEvent`](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/minecraft/world/level/gameevent/GameEvent.html) | 游戏事件表示世界语义，不等同于某个玩家实际接收的声音或知识 | 不把全局 `GameEvent` 直接写入每个 bot 的认知流 |
| [DynamicGameEventListener](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/minecraft/world/level/gameevent/DynamicGameEventListener.html) | 动态监听器与已加载区块边界密切相关 | 当前先采用定向声音包与有界传感器；不为听觉强制加载或订阅远方区块 |

方块破坏、放置、物品丢弃等“尝试”不能仅凭事件回调或方法返回写成成功。候选实现把
`ActionOutcome` 的终态同步送入 P3 收集器，并对可取消世界事件做 post-state 验证；只有
实际方块、实体或物品状态符合预期时，才生成可用于知识和活动推断的 `COMMITTED` 事件。

### 2.2 成熟 bot、世界抽象与具身智能

| 项目/资料 | 值得借鉴 | 明确不照搬 |
|---|---|---|
| [Mineflayer API](https://github.com/PrismarineJS/mineflayer/blob/master/docs/api.md) | 高层事件、观察 API 与能力模块化 | Mineflayer 是网络客户端 bot；BotPlayer 不能用协议侧知识替代服务器内感知边界 |
| [prismarine-world API](https://github.com/PrismarineJS/prismarine-world/blob/master/docs/API.md) | 未加载/未知区域需要显式表达，世界读取应增量且有界 | 不把未知方块当空气，不对世界执行无界体素扫描 |
| [Baritone](https://github.com/cabaletta/baritone) | 预算、缓存、取消和动态重算思想 | 不复制或内嵌 LGPL 源码；P3 不实现寻路，P4 独立设计 |
| [Project Malmo](https://github.com/microsoft/malmo) | observation、action、成功条件分离 | 只借鉴实验边界与验收思想，不引入旧运行时或数据 |
| [Voyager](https://arxiv.org/abs/2305.16291) | 环境反馈、执行错误和自验证循环 | 不执行模型生成代码，也不把模型总结当世界事实 |
| [STEVE-1](https://arxiv.org/abs/2306.00937) | 时序观察对活动理解的重要性 | 当前只实现确定性滑动窗口，不引入模型权重或训练数据 |
| [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) | 事实应保留来源、证据与派生关系 | `WorldFact`、活动假设和纠正事件保留有界证据引用 |

这些项目共同支持三个判断：

1. 观察必须和动作、成功条件分开；
2. 未知、未加载、过期和未验证是正常状态，不能被默认值掩盖；
3. 活动理解需要时间窗口和多项证据，但输出仍应是可纠正的假设。

## 3. 权威事件平面

### 3.1 来源

候选实现的事件来源包括：

- P2 `ActionOutcome` 的唯一终态；
- NeoForge 方块破坏/放置、伤害、拾取、丢弃等事件候选及 post-state 复核；
- 原版已经发往具体 bot listener 的 `ClientboundSoundPacket` 与
  `ClientboundSoundEntityPacket`；
- 玩家通过管理命令提交的活动纠正；
- 未来阶段的聊天、技能与区域事件。

权威平面内部使用运行时 `sessionId + eventSeq` 标识读取位置。`eventSeq` 只保证同一
服务端运行会话内单调递增，不冒充跨重启持久序号。它是服务器内部审计/投影游标，不进入
AI-safe `ObservationSnapshot` 或 `PerceivedEvent`。事件环被淘汰时，服务器内部读取窗口
按 fail-closed 处理；消费者不能把缺口解释成“期间没有发生事情”。

权威平面使用三个独立有界 ring：普通 authority audit、只收
`VISUAL/AUDIBLE` 的 spatial authority projection、以及 routed sound audit。三者共享
同一 runtime session 和唯一递增 `eventSeq`，不会发生序号碰撞。定向声音只进入声音审计
ring，`SELF/DIRECT` 洪泛也只进入普通审计 ring，因此都不会逐出待空间复核的候选。

三类通道采用不同读取语义：

- `SELF/DIRECT` 是身份/目标已经明确的可靠通道，在权威事件发布时同步路由给活动的精确
  generation，不依赖后续共享投影预算或游标追赶；
- `VISUAL/AUDIBLE` 是只在发生 Tick 有意义的空间通道，每 Tick 从独立 spatial ring 尾部
  取有界窗口投影，避免定向洪泛或早期 backlog 挡住最新空间事件；若同 Tick 候选超过
  预算，只选择最新窗口并把遗漏记入管理员 coverage 诊断。窗口仍逐项执行维度、距离、
  视锥/遮挡和 same-tick 检查，不能把跳过的历史事件补成过去感知。

普通 audit cursor 继续用于覆盖诊断。空间窗口与可靠路由都以
`authorityEventId` 去重，不产生重复认知证据。

### 3.2 事件结果

`ATTEMPTED / COMMITTED / CANCELLED / FAILED` 必须分开：

- 尝试事件可用于诊断，不能自动证明世界变化；
- 只有 `COMMITTED` 可以提升事实或作为肯定活动证据；
- 保护插件拒绝、方块未改变、物品数量不守恒时不能发布成功；
- 同一个动作结果与 NeoForge 候选可能描述同一变化，收集器需要有界去重。

方块复核进一步避免“看似变化”的错误归因：放置必须完整匹配候选记录的预期
`BlockState`；破坏只有 post-state 为空气才成为带 actor 的 `BLOCK_BROKEN`。同 ID
属性变化不是破坏，变成另一种非空气方块只发布无 actor 的泛化 `BLOCK_CHANGED`，因为
收集器无法证明这次替换由原破坏候选 actor 完成。

break/place/toss 的 post-state 候选在捕获时同时冻结 actor 的 bot generation；稍后验证
成功时，以私有 `routing.bot_generation` 绑定原 generation，不能把旧代际候选投给同 UUID
的新 bot。所有 `routing.*` 字段只供服务器内部路由，构造认知投影时删除。

动作终态走独立 critical ingress，上限按 P2 单 Tick 最大 canonical 终态吞吐
`(MAX_ACTIVE_ACTIONS + MAX_COMMANDS_PER_TICK) * 2 = 40960` 个发布单位计算，不受常规
pending 容量的较小 ingress 上限截断。成功 break 可能同时发布 `ACTION_COMPLETED` 和
`BLOCK_BROKEN`，因此按 2 个单位计；其他终态按 1 个单位计。

### 3.3 revision

服务器内部世界模型保留：

- 维度 revision：说明该维度发生过可追踪变化；
- target revision：对明确方块、实体或未来容器 scope 精确失效。

实现可以保留不对 AI 暴露的全局内部计数用于排错，但事实验证优先比较 scope revision，
AI-safe 快照不携带 `worldRevision`。仅使用全局 revision 会导致世界任意位置变化都让
所有事实过期；完全不使用 revision 又会让旧事实无限存活。scope 表采用有界 LRU 保留；
被淘汰的 scope 再次出现时以当前全局内部单调值作为新纪元基线，保证 target revision
不回退。权威事件环出现 coverage gap 时只记录管理员私有诊断并快进内部 cursor，不改变
任何 bot 的事实、local perceived watermark 或公开传感器预算；实现也不宣称存在 scope
eviction 回调或逐项淘汰统计。

## 4. 每 bot 认知平面

### 4.1 投影通道

| 通道 | 含义 | 典型规则 |
|---|---|---|
| `SELF` | bot 自己执行或承受的结果 | 精确 `(botId, generation)`，不依赖远距离视线 |
| `DIRECT` | 明确告知该 bot 的信息 | 必须含目标 bot 标识；未来还需 ACL/会话复核 |
| `VISUAL` | 位置事件或实体处于视野 | 同维度、范围、视锥/射线、途中区块均已加载 |
| `AUDIBLE` | 原版已路由或语义声音可听 | 定向包服从原版路由；语义 fallback 复核同维度与 `hearingRange` |
| `ADMIN` | 明确开启的调试全知 | 当前普通路径不启用；事实必须标注 `ADMIN_OMNISCIENT` |

一个事件在多个通道可见时，投影仍只生成有界认知载荷。`PerceivedEvent` 不保存完整
`AuthorityEvent` 对象，也不暴露 authority session/sequence/revision；只留下 opaque
`authorityEventId` 用于同一认知流内去重/追溯，避免把内部审计字段、未感知 actor 或
敏感 delta 透传给规划器。

`SELF` 与 `DIRECT` 可以按明确目标语义投影；历史积压中的 `VISUAL`/`AUDIBLE` 不允许用
“当前仍在范围内”倒推“过去曾经看到/听到”。视觉/听觉只有 `event.gameTick ==
currentTick` 才可投影，积压、预算延迟或读取 gap 一律 fail-closed。声音包候选也必须在
同 Tick、精确 `(botId, generation)` 且同维度消费，否则丢弃。已经由服务端定向送到该
listener 的原版位置/实体声音以原版路由作为可听边界，不再用 `hearingRange` 二次裁剪；
普通权威语义事件的 `AUDIBLE` fallback 才使用 `hearingRange`。

actor 身份也按通道最小披露：`SELF` 只保留当前 bot 自身 actor，不因“我受到伤害”自动
得知不可见攻击者；`VISUAL` 不能因事件位置可见就透传全部参与者，而要对每个 actor 分别
复核同维度实体、已加载位置、视距、视锥和遮挡。两类投影都会删除通用 actor/target/
attacker/victim UUID delta；`AUDIBLE` 默认不携带 actor。

### 4.2 隐藏变化与无时序侧信道

假设 bot 昨天看到坐标 `x,y,z` 是石头，今天远方玩家在 bot 看不到时把它挖掉：

- 权威平面知道方块 revision 已变化；
- 该变化不进入 bot 的 `PerceivedEvent`，也不改变其事实状态、local perceived watermark
  或公开传感器预算；
- 旧事实仍表示“bot 在 TTL 内的当前认知”，不等价于服务器此刻真相；
- 只有重新观察到冲突值时，旧事实才被 supersede；若 TTL 到期或已投影事件结果不确定，
  则可以转为 `STALE_UNKNOWN`。authority coverage gap 同样不触碰认知状态。

如果隐藏变化一发生就给 bot 标记 UNKNOWN，标记时刻本身会泄露“远方刚刚有变化”。不触碰
认知状态才是“服务器权威但 bot 非全知”的关键区别。

### 4.3 generation 隔离

感知运行时、事件环、声音候选、快照和事实都按 `(botId, generation)` 隔离。死亡、重生、
换维度导致 generation 轮换、卸载、断开和停服时，旧代际的认知入口必须同步关闭。新代际
从当前服务器内部游标开始，不能读取生成前的全服历史或复用旧玩家对象；该内部游标不随
快照暴露。

每个 generation 的非声音语义事件与声音各有独立有界 ring，并共享同一个 generation-local
`perceivedSeq`。快照分别读取 `recentEvents` 与 `recentSounds`；声音洪泛不会逐出动作、
方块、伤害或活动证据。

声音 ingress 也按 generation 隔离：每个活动 `(botId, generation)` 使用独立有界 FIFO，单
队列容量不超过按当前活动 generation 数计算的公平份额；全局容量满时优先从最大队列
淘汰最旧候选。每 Tick 从排序后的 generation 队列 round-robin 抽取，并轮转起始位置；
单队列保持原版封包产生顺序。超过同 Tick 处理上限的余量保守丢弃，不把晚到包补投影成
历史听觉。随后 `SoundEventSensor` 从独立历史声音 ring 取快照时，若 `EVENT_READ` 预算
不足则先选择最新事件，再按 `perceivedSeq` 恢复时间顺序输出，避免旧历史挤掉最新听觉。

## 5. 有界传感器与不可变快照

### 5.1 当前传感器候选

| 传感器 | 当前候选内容 | 正常/降级/临界默认间隔 |
|---|---|---:|
| `SelfStateSensor` | 位置、速度、视角、生命、饥饿、空气、火、水、姿态、状态效果 | `1 / 1 / 1` |
| `InventorySensor` | bot 原版背包槽位的物品 ID、数量、耐久与摘要 digest | `1 / 5 / 20` |
| `VisionRaySensor` | 注视方块或实体、遮挡、未加载边界 | `1 / 2 / 5` |
| `NearbyThreatSensor` | 已加载局部敌对/危险实体 | `3 / 5 / 5` |
| `LocalEntitySensor` | 已加载、有视线的附近实体摘要 | `5 / 10 / 暂停` |
| `LocalBlockSensor` | 注视点、脚下小邻域、事件焦点；仅实际支撑方块免 LoS，其余需视线 | `10 / 20 / 暂停` |
| `SoundEventSensor` | 已经投影给当前 bot 的声音事件 | `1 / 2 / 5` |

传感器只在服务器主线程读取短生命周期 Minecraft 对象，输出 DTO 后不保留 `Level`、
`Entity`、`ItemStack`、`BlockState`、`BlockEntity` 或 `Menu` 引用。已经读取的候选使用
距离、坐标、UUID 等稳定键排序；事件焦点按最新 `perceivedSeq` 优先，达到快照上限后不
让较旧证据挤掉新焦点。实体枚举在候选访问前扣预算，达到读取上限立即中止并
显式 `truncated`。超出上限的密集场景不承诺入选子集完全独立于底层实体迭代顺序。单个
第三方实体/物品/效果/方块属性异常只截断本次传感器，不让异常穿透服务器 Tick。含
`BlockEntity` 的方块只暴露
`botplayer.opaque_block_entity=true`，不读取对象或内容。

`ObservationSnapshot` 有两项关键新鲜度前置条件：`SelfStateSensor` 必须在当前 Tick
成功，`InventorySensor` 必须保有一次完整采样且成功时间不早于 20 Tick。任一条件不满足
就撤下 `latest`，而不是发布带旧位置/生命或半份背包的新快照。其他传感器按各自 TTL
降级：视觉过期变为 `UNKNOWN_STALE`，威胁/实体/方块/声音过期清空；快照记录每个传感器
最后成功 Tick，调用失败不能刷新该时间。

视觉射线遇到预算耗尽、第三方碰撞形状异常、实体枚举截断或方块状态读取异常时返回
`SensorResult.Unavailable`；服务保留上一份不可变视觉值直到新鲜度窗口结束，之后显式
转为 `UNKNOWN_STALE`，不会把异常伪装成 `MISS`。

### 5.2 不强制加载

视觉射线会在第一个未加载区块边界停止并返回 `UNKNOWN_UNLOADED`。局部实体和方块传感器
在读取前检查位置已加载，不调用 `getChunk` 来制造观察。P3 的原则是“看不到就不知道”，
而不是为了回答问题改变服务器的区块加载状态。

### 5.3 预算与降级

每个 bot 拥有实体索引回调、匹配实体读取、方块读取、射线、事件读取和库存槽读取六类
预算；所有 bot 再共享每 Tick 工作单元上限。权威事件投影和公开传感器使用分离的
全局/每 bot 预算池；前者只在
服务器内部计费，后者才写入 `PerceptionLimits`，因此未感知的权威事件不会改变公开预算
报告。服务使用 EWMA MSPT 和恢复滞回切换：

公开实体读取总额进一步拆为有硬边界的子配额：威胁约占 `1/3`、注视实体约占 `1/4`，
普通局部实体使用余量；传感器顺序为自身、完整背包、威胁、注视实体、普通实体、方块和
声音。这样拥挤场景下普通实体扫描不能吃掉威胁检测份额，威胁也不能无限透支视觉或普通
实体。多个 bot 共享全局工作预算时，运行时按稳定 bot/generation 顺序逐 Tick 轮转起点，
降低固定头部 bot 长期优先的风险；这仍需多 bot soak 验证，不等于已证明发布级公平。
威胁枚举先用危险类型 selector 筛除普通中立实体，并有独立 raw scan 硬上限；未匹配实体
不消耗威胁的 `ENTITY_READ` 子配额，但实体索引的每次原始回调都会先扣 `ENTITY_SCAN`
并计入全服工作池，因此密集中立实体不能形成未记账扫描。`globalWorkPerTick` 最小为 64，
保证 1/4、3/4 分池后公开池仍能原子读取完整 41 槽背包。

- `NORMAL`：按正常间隔采样；
- `DEGRADED`：减少非关键读取并拉长间隔；
- `CRITICAL`：保留自身和最低限度事件/声音，暂停局部方块和普通实体扫描。

预算耗尽、读取截断和当前压力会写入 `PerceptionLimits`。全局工作预算仍在服务器内部
限流，但 AI-safe 快照只暴露当前 bot 的分类预算计数，不暴露全服/global 使用量。下游
不能把截断快照描述成完整世界。

## 6. 世界事实与活动理解

### 6.1 短期事实

当前 `WorldModelService` 保存有界运行时事实，状态包括：

- `ACTIVE`：仍是 bot 在 TTL 内的当前认知，不保证隐藏世界没有变化；
- `STALE`：已投影且确认提交的 scope 变化使旧事实过期；
- `STALE_UNKNOWN`：已投影变化结果不确定或 TTL 到期，只能确认旧认知不再可靠；
- `SUPERSEDED`：同一键出现相冲突的新值；
- `RETRACTED`：被明确撤回；
- `UNVERIFIED`：来源或 revision 不足以作为事实。

P3 世界模型不是长期记忆数据库。当前事实不会跨重启保存，长期来源化记忆、删除、迁移和
隐私策略仍属于 P7。对容器，P3 最多保留 opaque 位置/方块类型事实以及对应 scope
失效；不生成内容 digest，不读取 `BlockEntity`、槽位、carried stack 或 menu 状态。

### 6.2 活动推断

`ActivityInferenceService` 只读取该 bot 非声音语义 ring 中 `observedAtTick` 不晚于当前
Tick、且年龄不超过窗口的 `PerceivedEvent`。窗口每 Tick 重新计算，证据刚越界就失效，
不会因认知水位未变化而额外缓存。每次快照只调用一次批量推断，对当前窗口事件按 actor
聚合，避免为每个 actor 重复规范化/扫描事件。actor 候选按新近证据优先，数量随压力限制
为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`（包含当前 bot），避免旧 actor 长期占位并
在压力下保持有界。服务按固定权重、时间衰减和稳定排序推断
`MINING / BUILDING / COMBAT / FARMING / EXPLORING` 等候选。输出保留：

- actor UUID；
- 开始与最后证据 Tick；
- `confidence` 和置信区间；
- 最多固定数量的当前 cognitive stream 证据引用。

证据序号是 `(botId, generation)` 的局部 `perceivedSeq`，不能反推出全服事件数量或
authority 顺序。中文输出按置信度使用“正在”“看起来正在”“可能正在”“我不确定是否正在”。玩家纠正通过
新的 `PLAYER_CORRECTION` 事件进入证据流，不删除或重写历史事件。`CRAFTING`、`SMELTING`
等枚举存在不等于 P3 已经观察通用容器；在 P5 容器/工作站事件接入前不能据此宣称会识别
完整生产流程。动作完成只为明确的 `break_block` 和 `attack_entity` 提供活动证据；
`use_on_block` 可能是开门、按按钮、使用容器或许多其他行为，单独不足以证明
`BUILDING`，建造需要 `BLOCK_PLACED` 等已提交语义证据。

## 7. 当前候选实现映射

| 职责 | 当前代码 |
|---|---|
| P3 编排与 lifecycle | `perception/PerceptionService`、`lifecycle/BotLifecycleManager` |
| 权威事件收集 | `perception/AuthorityEventCollector`、`action/ActionOutcomeSink` |
| 权威/认知事件环 | `perception/event/SemanticEventBus` |
| 认知投影 | `perception/EventPerceptionProjector` |
| 定向声音入口 | `kernel/BotGamePacketListener`、`perception/SoundObservationCandidate` |
| 传感器 | `perception/sensor/*Sensor` |
| 快照与预算 DTO | `perception/ObservationSnapshot`、`PerceptionBudget`、`PerceptionLimits` |
| revision 与事实 | `worldmodel/WorldRevisionTracker`、`WorldModelService` |
| 活动理解 | `worldmodel/ActivityInferenceService`、`ActivityReportFormatter` |
| 管理诊断与纠正 | `/botplayer perception inspect\|correct` |

这些类存在只说明“已编码”。当前环境不能下载/运行所需 Java 21 Gradle 工具链，因此编译、
测试和运行期语义必须由 CI 再确认。

## 8. 明确延期

P3 不包含：

- 箱子、木桶、潜影盒、末影箱、工作台、熔炉或模组机器的内容读取和事务；
- 通过 `BlockEntity` NBT 进行全知容器窥视；
- 长距离寻路、导航网格和安全反射；
- 自主生存技能、任务 DAG 或跨重启检查点；
- DeepSeek、聊天、模型 HTTP、长期记忆；
- 管理员全知模式的产品化配置；
- 专用服、多 bot soak 和发布级性能结论。

容器边界保持：P5A 实现第一条生存闭环所需的最小原版世界容器驱动，P5B 扩展广泛原版
容器和工作站，P8 处理模组标准/自定义 menu。

## 9. 验收计划

P3 退出门至少需要：

1. Java 21 下 `-Xlint:all -Werror` 严格编译；
2. 事件环、DTO 上限、预算、负载滞回、revision、事实冲突/TTL/认知侧失效、活动回放的
   单元测试；
3. NeoForge GameTest 验证自身/背包快照、视觉遮挡、定向声音隔离、全服事件不自动进入
   bot 知识、方块事实失效、generation 清理且不强制加载区块；
4. 相同认知事件回放得到相同活动类型、置信区间和 generation-local `perceivedSeq`；
5. `clean build` 与 CI `runGameTestServer` 取得可追溯绿色终态；
6. 未执行的客户端、独立专用服和多 bot soak 继续明确为未验证。

在以上证据回写前，README、能力矩阵和完成报告只使用“已编码/候选/待 CI 验证”，不写
“P3 已通过”或“完整 AI 玩家”。

## 10. 许可证与 clean-room 边界

本轮只阅读公开文档、论文和 API 描述，独立设计 Java 数据结构与算法：

- 没有复制 Mineflayer、prismarine-world、Baritone、Malmo、Voyager 或 STEVE-1 源码；
- 没有引入第三方运行时依赖、模型权重、数据集或提示模板；
- Baritone 的 LGPL 边界继续遵守 ADR-0009，不把其实现并入 MIT 核心；
- 论文中的方法只作为“需要时序证据和环境验证”的研究依据，不复现受限训练资产；
- 若未来引入代码、库、数据或模型，必须单独固定版本、审查许可证并更新
  [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

规范性决定见 [ADR-0013](adr/0013-finite-perception-two-plane-world-model.md)。
