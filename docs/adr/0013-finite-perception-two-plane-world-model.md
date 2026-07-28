# ADR-0013：有限感知、双事件平面与有界世界模型

- 状态：Accepted
- 日期：2026-07-28
- 关联：ADR-0002、ADR-0007、ADR-0010、ADR-0012

## 背景

服务器可以访问所有已加载世界状态、NeoForge 事件和在线实体，但 AI 玩家不应因此成为
全知观察者。若把全服审计事件直接交给 bot，会产生以下问题：

- bot 能回答视线外、听觉外或发生在其他维度的事情；
- 权威事件中的 actor、目标和状态差异可能泄露给没有感知权限的 bot；
- 未加载区块被扫描或强制加载，改变服务器负载和游戏语义；
- 可取消事件或动作尝试被错误记录成成功；
- 世界变化后旧观察被误写成保证与服务器当前一致的真相，而不是带 TTL 的 bot 认知；
- 重生、换维度或卸载后的旧实例继续污染新 generation；
- 无界事件、事实、DTO 和扫描在多 bot 环境中拖垮服务器。

P3 需要既保留服务器权威审计，又让每个 bot 只形成自己当时能获得的知识。

## 决策

### 1. 权威事件与认知事件分离

运行时维护两类记录：

- `AuthorityEvent`：服务器确认发生的事件，用于审计、动作结果关联和世界 revision；
- `PerceivedEvent`：经距离、视线、维度、定向目标、权限与预算投影后，某个
  `(botId, generation)` 实际获得的认知事件。

`PerceivedEvent` 只保留 opaque `authorityEventId` 和重新构造的有界认知载荷，不得嵌入
完整 `AuthorityEvent`，也不得暴露 authority session/sequence、全局 world revision 或
全服预算计数。其 `perceivedSeq` 从 1 开始，仅在 `(botId, generation)` cognitive stream
内有序。全服权威记录不能自动成为所有 bot 的知识。

普通 authority audit、只含 `VISUAL/AUDIBLE` 候选的 spatial authority projection、
routed sound audit 使用三个独立有界 ring，但共享同一 runtime session 和唯一递增
authority `eventSeq`。声音审计与 `SELF/DIRECT` 洪泛都不得挤出空间候选。
每个 `(botId, generation)` 的非声音语义事件与声音也分环有界保存，并共享同一个 local
`perceivedSeq`。声音洪泛不得逐出动作、方块、伤害或活动证据。

认知通道固定为 `SELF / DIRECT / VISUAL / AUDIBLE / ADMIN`。普通玩法不启用 `ADMIN`
全知；未来若提供管理员调试模式，所有由此产生的事实必须标注
`ADMIN_OMNISCIENT`，不得混入正常观察。

`SELF/DIRECT` 是身份或目标明确的可靠通道：权威事件发布时同步路由到活动的精确
generation，不得因共享空间投影预算、旧 cursor backlog 或 authority ring 淘汰而丢失。
`VISUAL/AUDIBLE` 则从独立 spatial ring 尾部读取有界候选，并继续执行 same-tick 空间
投影；同 Tick 候选超预算时只选择最新窗口并计入管理员 coverage 诊断，不得为了追赶历史
而阻塞当前 Tick 的空间感知。所有路径按 opaque `authorityEventId` 去重。

actor 身份按通道最小披露。`SELF` 只保留当前 bot 自身 actor；`VISUAL` 仅保留逐个通过
同维度、已加载、距离、视锥和遮挡复核的 actor；两者删除未被该通道证明的通用身份
delta。事件位置可见不等于攻击者、受害者或其他参与者身份也可见。

### 2. 只有验证后的变化才能成为成功事实

P2 `ActionOutcome` 的唯一终态同步进入 P3 权威收集器。NeoForge 可取消事件只登记候选，
在同一服务器 Tick 的 Post/感知阶段于世界逻辑之后复核方块、实体或物品 post-state，
再发布 `COMMITTED`。

`ATTEMPTED / COMMITTED / CANCELLED / FAILED` 不得合并；只有 `COMMITTED` 可以作为
肯定事实或活动推断的正向证据。动作结果与平台事件描述同一变化时必须有界去重。

方块放置必须完整匹配事件记录的预期 `BlockState`，不能只比较 block ID。破坏候选只有
post-state 为空气时才可发布并归因 `BLOCK_BROKEN`；同 ID 属性变化不是破坏，变成另一种
非空气方块只能记录为不归因 actor 的泛化 `BLOCK_CHANGED`，避免把同 Tick 的第三方替换
错归因给原候选玩家。

break/place/toss 候选必须在捕获时冻结 actor 的 bot generation，验证后通过私有
`routing.bot_generation` 投递给原 generation；所有 `routing.*` 字段在构造认知投影时
删除。critical action-outcome ingress 必须按 P2 单 Tick 最大 canonical 终态吞吐设置；
成功 break 按两个发布单位计，其他终态按一个单位计，不能受较小常规 pending 上限截断。

### 3. 视觉与局部扫描不得强制加载区块

传感器只读取已加载区块：

- 射线在第一个未加载边界停止，并显式返回未知/截断；
- 方块和实体读取前检查位置已加载；
- 不调用 `getChunk` 或添加额外 ticket 来完成感知；
- 未加载、预算耗尽、读取 gap 和没有命中必须是不同状态；
- 未知或未加载不能当作空气。

局部扫描有半径、候选数、方块读取、实体读取和射线次数上限。对已经读取的候选使用稳定
排序；达到上限必须显式 `truncated`。超额密集场景不承诺入选子集完全独立于底层实体
迭代顺序。

### 4. 声音使用原版定向路由

普通声音感知优先截取原版已经发往具体 `BotGamePacketListener` 的位置或实体绑定客户端
声音包，并记录不可变 `SoundObservationCandidate`。P3 必须复核 same-tick、精确
`(botId, generation)` 和维度；已经定向送达的位置/实体声音以原版服务端封包路由作为
可听边界，不再使用 `hearingRange` 二次裁剪。普通权威语义事件的 `AUDIBLE` fallback
才按同维度和 `hearingRange` 投影。

不得仅监听全局声音事件后按一个自定义半径广播给所有 bot；那会绕过原版玩家跟踪、维度、
来源和包路由语义。声音认知载荷默认不携带未被听觉证明的 actor 或完整权威 delta。

定向声音候选必须按 `(botId, generation)` 分队列。单 generation 只占当前活动
generation 数决定的公平份额；全局入口满时从最大队列淘汰旧候选。消费按 generation
round-robin 且逐 Tick 轮换起点，单 generation 保持封包产生顺序，避免一个高频接收者
长期垄断入口。`SoundEventSensor` 读取独立历史声音 ring 时，若快照事件预算不足必须先
选择最新事件，再按 local `perceivedSeq` 恢复时间顺序输出。

`VISUAL` 与 `AUDIBLE` 只允许 same-tick 投影。事件积压、预算延迟、读取 gap 或晚到声音包
不能用“当前可见/可听”倒推过去的感知；这些情况一律 fail-closed。`SELF`/`DIRECT` 仍可
按明确 actor/target 语义处理。

### 5. DTO、队列与主线程边界

所有 Minecraft 活动对象只在服务器主线程的单次采样调用栈中访问。可交给未来异步规划器
的 `ObservationSnapshot`、事件、事实和证据必须是不可变、有 schema 上限的 DTO，不包含
`Level`、`Entity`、`ItemStack`、`BlockEntity` 或 `Menu` 引用。

以下资源必须有配置或代码硬上限：

- 普通 authority audit、spatial authority projection、routed sound audit 三个 ring，
  以及每 bot generation 的非声音语义/声音认知 ring；
- 待验证事件与定向声音候选；
- 每 bot 事实和服务器 revision scope；
- 每 Tick 全局工作量及每 bot 的事件、实体、方块、射线、库存读取量；
- 快照内实体、威胁、方块、事件、活动和字符串/映射字段。

服务器使用 EWMA MSPT 与恢复滞回切换 `NORMAL / DEGRADED / CRITICAL`。压力升高时降低或
暂停非关键感知；不能因此削弱 P4 未来的 L0 安全反射。权威投影和公开传感器必须分池
计费，未感知 authority 事件不能挤占或改变快照预算；全局工作量只在服务器内部限流，
AI-safe 快照只可携带当前 bot 的公开传感器分类预算报告。

SELF 状态必须在快照 Tick 成功采样；背包必须一次完整读取 41 个真实玩家槽位，且最近
成功采样不得超过 20 Tick。任一关键输入失效时撤下最新快照，不得发布半份背包或陈旧
身体状态。传感器异常不得刷新最后成功 Tick；视觉异常返回 `Unavailable`，旧视觉只在
新鲜度窗口内保留，过期后显式变成 `UNKNOWN_STALE`。

实体预算必须区分 `ENTITY_SCAN` 与 `ENTITY_READ`：实体索引每次原始回调先扣前者并计入
全服工作池，只有 selector 匹配后才扣后者。匹配读取总额再拆成威胁、视觉和普通局部实体
子配额，威胁传感器先于后两者采样；威胁 relevant selector 另受 raw scan 硬上限约束。
多个 bot 使用共享工作预算时逐 Tick 轮转采样起点，避免固定排序造成长期头部偏置。
`globalWorkPerTick` 不得低于 64，以保证 1/4、3/4 分池后公开池至少能原子读取完整 41 槽
背包。

### 6. scoped revision 与隐藏变化无侧信道

服务器内部 revision 至少包含维度和 target scope：

- 事实失效优先使用明确方块、实体或未来容器 target revision；
- scope 表有界；LRU 淘汰后若同一 scope 再出现，以当前全局内部单调值作为新纪元基线，
  保证 target revision 不回退；
- 实现可以保留全局内部计数用于排错，但不得放入 AI-safe `ObservationSnapshot`。

若 bot 感知到并确认提交的变化，可以让对应 scope 的旧事实变为 `STALE`，新观察可以
supersede 冲突值；若已投影事件的结果不确定，可以标为 `STALE_UNKNOWN`。若权威平面知道
scope 已变化但该 bot 完全没有感知，认知平面不得改变事实状态、local perceived
watermark 或公开传感器预算，也不得泄露 actor、新状态、时间细节或变化类型；否则
UNKNOWN 出现的时刻本身就是侧信道。

TTL 到期可以保守地把活动事实转为 `STALE_UNKNOWN`。权威事件环 coverage gap 也不得
触碰认知状态，因为远方隐藏事件洪泛同样会形成时序侧信道；它只进入管理员私有 coverage
诊断并快进内部 cursor。当前实现不提供 scope eviction 回调或逐项淘汰统计。

### 7. generation 生命周期

事件流、快照、事实、声音候选和传感器调度均按 `(botId, generation)` 隔离。死亡、重生、
维度轮换、卸载、断开与停服必须关闭旧 generation；新 generation 从当前服务器内部游标
开始，不继承旧玩家对象或生成前全服历史。内部游标不能随 AI-safe 快照暴露。

### 8. 玩家活动是可纠正的假设

`ActivityInferenceService` 只使用当前 bot 已感知事件的严格有界滑动窗口；每 Tick 剔除
刚越过窗口的证据，不能因水位未变化缓存过期结果。每次推断单次扫描并按 actor 聚合；
actor 候选只来自窗口内事件，按新近证据优先填充，压力分级上限为
`NORMAL 64 / DEGRADED 16 / CRITICAL 4`。服务以确定性权重、时间衰减和稳定排序输出
活动类型、置信度、区间与证据引用。证据只使用 opaque
`authorityEventId` 与 generation-local `perceivedSeq`，不能反推出 authority 顺序。
低置信度必须使用不确定表达。

玩家纠正作为新的 `PLAYER_CORRECTION` 事件追加，不删除或改写历史证据。模型文本、命令
成功返回或全服审计本身都不能成为 bot 已感知活动的证明。
通用 `use_on_block` 成功同样不足以证明 `BUILDING`；建造必须由 `BLOCK_PLACED` 等已验证
的专门语义事件支撑。

### 9. P3 不读取容器内容

P3 可以观察容器方块本身及其可见 `BlockState`，并维护 opaque 容器位置/方块事实与 scope
失效；不得生成内容 digest，不得读取容器内容、`BlockEntity` NBT、menu slot、carried
stack 或工作站内部状态来形成全知事实。容器能力固定延期：

- P5A：第一条生存闭环所需的最小原版世界容器事务；
- P5B：广泛原版容器与工作站；
- P8：模组标准接口和自定义 menu 适配。

## 被否决方案

### 把全服事件流直接提供给每个 bot

否决。审计权限不等于认知能力，会造成跨维度、遮挡外和隐私范围外的信息泄露。

### 每 Tick 扫描视距内全部方块和实体

否决。体素数量、射线和实体读取成本不可控，也会诱发强制区块加载。P3 只采样注视点、
脚下小邻域、已感知事件焦点和有界局部实体。

### 将未加载区域填充为空气或默认值

否决。会让路径、世界事实和 AI 计划基于虚假确定性。未知必须显式保留。

### 只使用全局 world revision

否决。任意位置的一次变化都会让全部事实过期，无法支持稳定局部世界模型。全局计数最多
保留在服务器内部排错，失效使用 scoped revision，AI-safe 快照不暴露它。

### 直接读取容器 NBT 作为感知

否决。它绕过玩家交互、权限、距离、menu 事务和 P5/P8 适配边界，等价于全知窥视。

### 在异步线程直接读取 `Level`/`Entity`

否决。Minecraft 活动对象不是安全的异步快照。异步消费者只能接收主线程构造的不可变 DTO。

## 兼容性、性能、安全与许可证影响

- 兼容性：事件和声音包 getter 对 1.21.1 映射敏感，应保持在平台接入边界，并由严格编译
  和 GameTest 保护。
- 性能：所有环、扫描和 DTO 有界；MSPT 降级可控制尖峰。当前尚无多 bot soak，不能据此
  宣称发布级性能。
- 安全/隐私：权威事件载荷与认知载荷分离，避免把未感知 actor、状态差异或全服活动泄露
  给普通 bot。未来聊天、ACL 和诊断导出仍需独立审查。
- 数据：P3 事实为运行时短期数据，不持久化。P7 引入长期记忆前必须重新审查 schema、
  数据保留、删除与迁移。
- 许可证：设计参考公开 API、文档和论文，采用 clean-room 实现；不复制 Mineflayer、
  prismarine-world、Baritone、Malmo、Voyager 或 STEVE-1 源码，不新增运行时依赖。

## 迁移和回滚

P3 当前没有持久 schema，因此回滚不会迁移世界数据。回滚到 P2 时：

1. 移除 P3 事件、传感器、事实和命令装配；
2. 保持 P2 `ActionOutcome` 唯一终态和 lifecycle 不变量；
3. 删除新增 `perception.*` server 配置前，允许旧 TOML 中未知键被 NeoForge 忽略；
4. 不把运行时 eventSeq、factId 或 revision 写入 roster/playerdata。

未来若 P7 持久化 P3 派生事实，必须新增 ADR 和版本化迁移，不能修改本 ADR 假装兼容。

## 验证方式

接受本决策不等于候选实现已经通过。P3 合并前至少验证：

- `-Xlint:all -Werror` 下 Java 21 严格编译；
- 权威环容量/gap 只进入管理员私有诊断并快进内部 cursor；认知侧事实、水位和公开预算
  不受影响，只见 generation-local `perceivedSeq` 与 opaque `authorityEventId`；
- DTO 字段与集合上限、预算耗尽和 MSPT 滞回；
- SELF 当前 Tick 与完整背包 TTL 门、视觉异常 `Unavailable/UNKNOWN_STALE`；
- 多 generation 声音公平份额/round-robin，以及威胁/视觉/普通实体子配额；
- scoped revision、冲突、TTL、可见失效和 `STALE_UNKNOWN`；
- 同一认知事件回放得到确定活动输出和 generation-local `perceivedSeq`；
- 视觉遮挡、未知未加载边界、定向声音隔离；
- `VISUAL/AUDIBLE` 积压、预算延迟和晚到声音 fail-closed；
- 独立 spatial authority ring 不被 `SELF/DIRECT` 洪泛或旧 backlog 阻塞；同 Tick
  超预算只取最新窗口并计 coverage，可靠路由不被共享预算丢失；
- `SELF` 与 `VISUAL` 不泄露未被对应通道证明的 actor 身份；
- 全服权威事件不自动成为其他 bot 的认知；
- generation 轮换、卸载和停服清理旧快照/声音/事实；
- 感知不会增加未加载区块；
- `clean build` 与 NeoForge GameTest 在 CI 取得可追溯绿色终态。

当前候选验证状态见 [P3 完成报告草案](../P3_COMPLETION_REPORT_CN.md)。
