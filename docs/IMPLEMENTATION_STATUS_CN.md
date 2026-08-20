# BotPlayer 当前实现状态

> 更新日期：2026-08-20
>
> 当前 P5/P6 集成验收载体：
> [`agent/p5-p6-next`](https://github.com/GreyTaiWolf/BotPlayer/tree/agent/p5-p6-next)
>
> [Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) 已在 Java 21
> 完成 `clean build`、Gradle `test`、161 项常规 NeoForge GameTest 与两阶段重启
> GameTest（各 1 项）。
>
> 当前阶段：P2-A～P2-E、P3 与 P4 自动化退出门已通过；Build #362 验证了受限 P5 纵切与
> P6-R1 的先前基线。当前分支的 P5A 修复、P5C 单 lifecycle Contract 和 P6 会话协调器仍待各自
> Java 21 CI；P5 的通用生存能力和 P6 的通用 client-sponsored bridge 均未完成。
>
> 发布状态：尚未发布，不建议用于重要存档

本文只记录能够从当前源码、资源、构建文件和测试来源核对的事实。路线图目标见
[ARCHITECTURE_AND_ROADMAP_CN.md](ARCHITECTURE_AND_ROADMAP_CN.md)，P2 的调研和重新定界见
[AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md](AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md)，
P2 最终验证结果见 [P2_COMPLETION_REPORT_CN.md](P2_COMPLETION_REPORT_CN.md)。P3 的设计
依据与自动化验收、剩余缺口分别见
[AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md) 和
[P3_COMPLETION_REPORT_CN.md](P3_COMPLETION_REPORT_CN.md)。P4 的设计与最终实现边界见
[AI_PLAYER_RESEARCH_AND_P4_DESIGN_CN.md](AI_PLAYER_RESEARCH_AND_P4_DESIGN_CN.md) 和
[P4_COMPLETION_REPORT_CN.md](P4_COMPLETION_REPORT_CN.md)。P5A-0 的范围、运行时合同与
退出门见
[AI_PLAYER_RESEARCH_AND_P5_DESIGN_CN.md](AI_PLAYER_RESEARCH_AND_P5_DESIGN_CN.md)。

## P5 当前开发切片

当前 P5/P6 集成基线为
[`agent/p5-p6-next`](https://github.com/GreyTaiWolf/BotPlayer/tree/agent/p5-p6-next)。该候选的
[Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) 已通过 Java 21
`clean build`、Gradle `test`、161 项常规 GameTest，以及在同一持久世界身份上分两次
JVM 启动的 phase-one/phase-two 重启验证。它证明当前窄纵切的自动化门，不替代真实客户端、
独立专用服或长时间多 bot 验收；它也不覆盖该基线之后的 P5A/P5C/P6 增量提交。下文的
Build #163 是早期 P5A 基线记录，不是当前候选的完整验证计数。

原生 `InventoryMenu` 适配器会冻结 41 槽、cursor、选择槽和 stateId，并以动态槽权限、
完整布局与物品多重集验证。通用 `SWAP_SEQUENCE` 允许 1～16 次点击、最多 8 个槽位，
每 Tick 只派发一次点击；真实五步场景验证跨 Tick `PENDING`、固定安全端点、旧 owner/
新 claimant 双 ticket 阻塞和精确 progress revision。generic equipment/offhand 仍
`UNSUPPORTED`；盔甲热栏单击及主背包 2～3 步路径保持独立。无法证明安全时 fail-closed，
这不是无条件回滚，也不表示跨 menu 统一事务完成。

Build #362 已自动覆盖基线中的 P5A/P5B/P5C 受限生产 DAG、白名单容器/工作站（含 Bot 私有
末影箱）、作物/交易/牛奶、有限自卫、保存围栏和两阶段重启路径；这仍不是 P5 的总退出门。
末影箱只隔离每个 Bot 的私有账本，同一物理方块仍按坐标串行；跨 menu 通用事务、任意配方/
作物/交易、工具/副手、广泛战斗、独立专用服和多 Bot soak 仍未完成或未验证。

主动进食当前只接受无剩余容器、无声明有害效果且无自定义完成逻辑的原版基础 `Item`
食物；可疑炖菜、紫颂果、蜂蜜瓶和模组食物保守拒绝，不能据此宣称通用食物支持。
业务成功由 `UseItem` 验证完成当刻冻结的 item count / food level 证据判定，不依赖下一
Tick 可能受 exhaustion 或拾取影响的活状态。
业务动作与补偿动作使用分离时限；失败、取消或抢占必须先恢复临时槽位和快捷栏选择，
原槽被可解释的外部插入占用时不搬未知物并以 `WORLD_CHANGED` 失败；只有物品守恒无法
解释或无法证明补偿安全时才隔离整个 generation。死亡、换维度、卸载和停服还要求动作
清理与背包布局两张独立回执；布局回执由一次性 run 租约与全背包结构化计数约束，食物
只能减少零或一个且非目标物必须严格守恒，不能把无活动 ticket 或无动作的补偿调用误当成
临时交换已恢复。一次性 fence 绑定完整布局 payload 并拒绝已消费 run 重放；换维度、
死亡复活和 replacement 激活都受两张回执约束，无法取得安全布局回执时不会把同一物理
背包带入新的活动 generation。断线 listener 使用跨越 pre-save / vanilla remove /
post-finalize 的精确 pending 记录；请求一出现即撤销动作与 handoff 权威，物理补偿必须
在原版写玩家数据之前完成，同 generation 的 unsafe 回执不可被后续重试升级；post 阶段
只消费预关闭回执并做幂等 teardown。replacement/respawn 只允许一次真实排队重试；
身份仍不收敛时用一次性 no-save 门闩绕过 `PlayerList.remove` 内部保存并隔离。Build #163
已为上述基线、通用五步事务和主背包盔甲切片提供 Java 21/NeoForge 运行证据。

`VANILLA_DEATH_CONSUMED` 只在原版真实进入 `keepInventory=false` 背包消费并正常返回后成立；
它消费已掉落布局，不把旧菜单补偿回滚到死亡前。消费前先耐久发布 V2 tombstone，旧 body
随后以空背包死亡态执行两次精确保存、主副本/备份/目录刷盘和有界双回读，提交成功后才清
marker。playerdata 中的一次性经验 handoff 继续跨越重生：successor 的两份 NBT 必须都
证明存活、空背包、精确经验且 handoff 消失，才可进入 ACTIVE。已移除 predecessor 保留
永久 no-save poison，迟到的 `die()` 或保存不能覆盖 successor。Build #163 已验证这条
单进程纵切及 91/91 GameTest；真实第二次服务器启动、断电和跨平台目录刷盘仍未验收。

基础盔甲切片使用确定性 `ArmorItem` 防御/韧性/剩余耐久比较，按头、胸、腿、脚固定顺序
逐件重新规划；拒绝零耐久、不可装备、当前槽绑定和装备后会绑定的候选。候选来自 carried
inventory `0..35`，每件升级提交为独立原生菜单动作，最多四件，可由
`/botplayer skill equip-armor <name>` 手动启动。盔甲路径保持专用且已由 Build #137 运行
验证；当前不选择工具或副手，也不把盾牌格挡计入基础装备。

## 状态含义

| 标记 | 含义 |
|---|---|
| 已编码 | 生产代码或测试来源存在，已做源码级核对；不自动表示构建或 GameTest 已通过 |
| 已编码；Build #97 编译/测试通过 | 生产路径已编译，相关自动测试任务已通过；不自动表示逐项手工或兼容性验证 |
| 本地已验证 | 对应严格编译、自动测试或构建已在当前分支实际通过 |
| 远端已验证 | 对应严格编译、自动测试或构建已由可追溯 CI 运行通过 |
| 待主线验证 | 已接入候选分支，最终本地命令或远端 CI 终态尚待回写 |
| 部分完成 | 主路径存在，但广度、异常、迁移或专项测试仍缺失 |
| 未实现 | 只有设计，不能在游戏中使用 |

本轮不使用单一“已完成”掩盖不同验证层。严格编译、单元测试、GameTest、客户端、专用服和
soak 必须分别报告。

## 工程与验证基线

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| Minecraft 1.21.1 / NeoForge 21.1.244 / Java 21 | 已编码 | `gradle.properties`、Java toolchain |
| ModDevGradle 2.0.142 / Gradle 9.2.1 | 已编码 | `build.gradle`、Wrapper |
| 开发版本 `0.2.0-alpha.1` | 自动化构建已验证 | 尚未正式发布；Build #362 已验证 P5/P6 先前集成基线，当前增量提交待 CI |
| 模组元数据和 Mixin 配置 | 已编码 | `neoforge.mods.toml` 模板、`botplayer.mixins.json` |
| P2 严格 Java 编译 | 本地与远端已验证 | `compileJava` / `compileTestJava` 在 `-Xlint:all -Werror` 下通过 |
| P2 纯 Java 单元测试 | 本地与远端已验证 | 140/140 通过，0 failed、0 skipped |
| P2 NeoForge GameTest | 本地与远端已验证 | 同一持久世界连续两次 19/19 通过；Build #18 通过 |
| GitHub Actions | 远端已验证 | P2 Build #18、P3 Build #28、P4 Build #97、P5 Build #163 与 P5/P6 基线 Build #362 均已完成对应自动化门；当前 P5A/P5C/P6 增量提交待 CI |
| P3 严格编译与单元测试 | 远端已验证 | Build #28 的 Temurin Java 21.0.11 编译与 Gradle `test` 通过；P3 43、全仓 183 是源码静态 `@Test` 计数 |
| P3 NeoForge GameTest | 远端已验证 | Build #28 日志明确 `All 27 required tests passed`、P3 batch 8；`P3SoundTarget/Other` 与 `P3FactStale` 成功 |
| P3 clean build / JAR | 远端已验证 | `BUILD SUCCESSFUL in 50s`，JAR upload 通过；artifact `botplayer-neoforge-1.21.1`，ID `8702261459`，`653364` bytes，SHA-256 `90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac` |
| P4 严格编译与单元测试 | 远端已验证 | Build #97 使用 Temurin Java 21.0.11；源码静态计数为全仓 200 个 JUnit `@Test` 方法 |
| P4 NeoForge GameTest | 远端已验证 | Build #97 日志明确 `All 55 required tests passed`；P4 直接场景 28 个 |
| P4 clean build / JAR | 远端已验证 | `BUILD SUCCESSFUL in 50s`；artifact ID `8721162398`，`838883` bytes，SHA-256 `b36a69f607e4f0e028e2afff15946a03bddd64004638c2d64d479c704706ddcd` |
| P5/P6 当前自动化门 | 待主线验证 | Build #362：Java 21 `clean build`、Gradle `test`、161 项常规 GameTest 与 phase-one/phase-two 重启 GameTest 均通过，但它先于当前 P5A/P5C/P6 增量提交；这些提交仍待 Java 21 CI，也不等于实机客户端或专用服验收 |
| GameTest batch Bot 预算 | 远端已验证当前布局 | 当前 Build #362 的 161 项常规 GameTest 在默认 `maxBots=8` 配置下完成；历史超配通过拆批修复，未提高上限 |
| 客户端 screen 手工测试 | 基础场景已验证 | 用户已在真实客户端确认 `176×256` 原版玩家风格视觉修复有效；多语言、资源包与全部 GUI Scale 组合仍未专项验证 |
| 独立专用服务器 | 未验证 | 当前不宣称纯服务端或版本不一致兼容 |
| 多 bot soak / 性能 | 未实现 | 没有长时间 MSPT、内存、队列与区块残留证据 |
| 正式发布包 | 未实现 | 当前仍是未发布开发候选 |

P2、P3 与 P4 的完整数字、命令结果和 CI 链接分别见
[P2 完成报告](P2_COMPLETION_REPORT_CN.md) 与
[P3 完成验收报告](P3_COMPLETION_REPORT_CN.md)、[P4 完成验收报告](P4_COMPLETION_REPORT_CN.md)。

## P3：有限感知、事件与世界模型

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 权威/认知双事件平面 | 已编码；Build #28 编译/测试通过 | `AuthorityEvent` 与每 `(botId, generation)` 的 `PerceivedEvent` 分离；认知侧只保留 opaque `authorityEventId` |
| 单调序号与有界事件环 | 已编码；Build #28 编译/测试通过 | 普通 audit、独立 spatial projection、routed-sound audit 三个 authority ring 共享唯一内部序号；每 generation 的非声音/声音认知分环共享从 1 开始的 local `perceivedSeq` |
| 动作结果事件 | 已编码；Build #28 编译/测试通过 | P2 canonical 终态同步进入 `AuthorityEventCollector`；sink 异常隔离计数 |
| NeoForge 事件收集 | 已编码；Build #28 编译/测试通过 | 放置要求完整预期方块状态；破坏只在变空气时归因 actor，同 ID 属性变化不算破坏，非空气替换泛化为无 actor 的 `BLOCK_CHANGED`；break/place/toss 候选在捕获时冻结 generation，复核后用不公开的 `routing.*` 完成 SELF 路由 |
| 权威事件投影 | 已编码；Build #28 编译/测试通过 | `SELF/DIRECT` 在发布时可靠路由；`VISUAL/AUDIBLE` 读取独立 spatial projection ring，定向洪泛不挤占；同 Tick 超预算只选最新窗口并计管理员 coverage，历史空间事件 fail-closed |
| 视觉事件投影 | 已编码；Build #28 编译/测试通过 | 同维度、距离、FOV/射线与已加载检查；事件位置可见不等于所有 actor 可见，actor 逐个复核且身份 delta 脱敏；仅 same-tick |
| 定向声音感知 | 已编码；Build #28 编译/测试通过 | 只从发往具体 bot listener 的位置/实体绑定声音包生成候选；每 generation 独立 FIFO、动态公平份额与 round-robin；历史声音 ring 在快照预算不足时优先最新事件，再按序输出；仅 same-tick |
| 自身/背包传感器 | 已编码；Build #28 编译/测试通过 | 输出不可变状态与 41 槽完整摘要，不保留 `ItemStack`；SELF 必须为当前 Tick，完整背包超过 20 Tick 未成功刷新时撤下快照 |
| 注视/实体/威胁/局部方块传感器 | 已编码；Build #28 编译/测试通过 | 有界可中止枚举、稳定排序和 chunk DDA；威胁优先并有 relevant selector、raw scan 上限与独立读取子配额；视觉异常返回 `Unavailable`；事件焦点按最新证据优先 |
| 感知预算 | 已编码；Build #28 编译/测试通过 | 权威投影/公开传感器分池；每池有每 bot 六类预算并共享全服工作份额；`ENTITY_SCAN` 对实体索引每次原始回调计费，`ENTITY_READ` 只对匹配候选计费；`globalWorkPerTick` 最小 64 |
| MSPT 降级 | 已编码；Build #28 编译/测试通过 | EWMA 与恢复滞回，`NORMAL/DEGRADED/CRITICAL` 调度 |
| 不可变 `ObservationSnapshot` | 已编码；Build #28 编译/测试通过 | 只含 generation-local stream/snapshot/perceived 水位、观察、活动与本 bot 分类预算 |
| AI-safe 快照脱敏 | 已编码；Build #28 编译/测试通过 | 不暴露 authority session/seq、全局 `worldRevision` 或全服预算计数；`SELF` 仅保留 bot 自身 actor，`VISUAL` 仅保留逐个可见 actor，两者删除通用身份 delta |
| scoped world revision | 已编码；Build #28 编译/测试通过 | 服务器内部维度/target scope；精确 scope 容量有界 |
| 短期 `WorldModelService` | 已编码；Build #28 编译/测试通过 | 来源、证据、TTL、冲突、`STALE/STALE_UNKNOWN/SUPERSEDED`；不持久化 |
| 隐藏变化侧信道隔离 | 已编码；Build #28 编译/测试通过 | 未投影 authority 与 authority gap 不改变事实、local perceived watermark 或公开传感器预算；gap 只进入管理员私有诊断 |
| 玩家活动推断 | 已编码；Build #28 编译/测试通过 | 每 Tick 严格剔除窗口外证据并单次按 actor 聚合，新近 actor 优先；候选上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，只接受相关 actor 的 `COMMITTED` 证据，`use_on_block` 单独不足以证明 building |
| 玩家纠正 | 已编码；Build #28 编译/测试通过 | 追加 `PLAYER_CORRECTION` 事件，不改写历史证据 |
| generation/lifecycle 清理 | 已编码；Build #28 编译/测试通过 | 死亡、重生、换维度、卸载、断开、回滚和停服关闭旧代际认知 |
| P3 诊断命令 | 已编码；Build #28 编译/测试通过 | `perception inspect` 有界输出快照/活动/事实；`correct` 记录活动纠正 |
| 纯 Java 测试来源 | Gradle `test` 通过 | 源码静态计数：perception 24、worldmodel 16、`BotActionRuntimeTest` 新增 3，即 P3 43、全仓 183；不是日志直接报告数 |
| P3 GameTest | 8/8 通过 | 全仓 27/27；定向声音目标 generation 隔离与已感知 committed 方块变化使旧事实 stale 的新增场景已通过 |
| 通用世界容器内容 | 未实现 | P3 只维护 opaque 位置/方块事实与失效，不生成内容 digest、不读 `BlockEntity` NBT/menu slot；最小/广泛原版容器分别在 P5A/P5B，模组 menu 在 P8 |

P3 的生产路径已编码并通过 Build #28 自动化退出门；这表示提交 `38851d1791b84e73705b302be8438e441c3f26ff`
的编译、Gradle `test`、27 项 GameTest、clean build 和构件上传成功。它仍不表示每个
行为都有直接 GameTest，或覆盖客户端手工、独立专用服、多 bot soak。

## P4：导航、安全反射与玩家规则兼容

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 导航契约与 session FSM | 已编码；Build #97 编译/测试通过 | goal/policy/deadline/generation 显式；终态、失败码和诊断计数有界 |
| 不可变运动快照 | 已编码；Build #97 编译/测试通过 | 主线程分时读取已加载世界；未知区块不当成空气、不主动加 ticket |
| 有界分段 A* | 已编码；Build #97 编译/测试通过 | 节点扩展、并发、队列、结果 inbox 和最大目标距离均有硬上限 |
| 真实玩家 follower | 已验证基础场景 | 只经 P2 look/move/jump/swim/climb/use 动作；终点复核真实脚位，禁止传送 |
| 动态重算 | 部分验证 | 动态墙使旧路线失效并绕行；有界前方实体覆盖层与“短等后重算”路径已通过 Build #362 全仓自动基线，专项实机验证仍待运行 |
| 特殊移动 | 部分完成 | 一格跳跃、木门、浅水、梯子有直接 GameTest；脚手架、藤蔓、复杂水流、载具和鞘翅未完成 |
| stuck 恢复 | 部分验证 | 停止、重新对齐、重采样和重算次数有界；真实碰撞后的 `STOP` 确认与强制卡住 GameTest 已通过 Build #362 自动基线，专项实机验证仍待完成 |
| 资源门槛 | 已验证 | 低生命/食物拒绝普通远行，低食物停止 sprint，真实 sprint 触发原版 exhaustion |
| L0 `SafetyFrame` | 已编码；Build #97 编译/测试通过 | 每 Tick 有界读取真实生命/吸收/护甲/食物/空气/环境/效果/伤害/威胁 |
| 安全抢占 | 已验证基础场景 | 悬崖、燃烧、溺水、箭、TNT、敌对目标会挂起导航并抢占普通输入 |
| 敌对仇恨与真实攻击 | 已验证 | 僵尸 `target == bot`、原版近战造成生命下降、L0 观察并撤退 |
| 饥饿与伤害 | 已验证 | sprint exhaustion、临界食物停跑、Hard 难度零食物饥饿伤害 |
| 原版伤害/护甲/效果 | 已验证基础场景 | 最终身体伤害、护甲减免、正负效果、属性变化和自然到期 |
| 标准模组兼容基线 | 已验证 fixture | 动态 `botplayer:compatibility_probe` DamageType 与 `PlayerTickEvent.Post` 属性 Buff；fixture 不进正式 JAR |
| generation 隔离 | 已验证 | 死亡使旧导航 `STALE_GENERATION`，新身体只产生新 generation 安全帧 |
| Terrain Assist | 已验证、默认关闭 | 请求+服务端双门控；双格通道破坏、四格短桥、预算/库存守恒、服务端拒绝均有直接 GameTest |
| P4 管理命令 | 已编码 | `navigation go/stop/inspect` 与 `safety inspect` 固定要求权限等级 2 |
| P4 GameTest | 28/28 通过 | 全仓 55/55；完整清单见 P4 完成报告 |
| 长距离/性能发布门 | 部分完成 | 滚动局部 frontier 已编码；平地 200 格直接 GameTest、多 Bot MSPT/内存 soak 尚未完成 |
| 自主生存/战斗 | 受限纵切已自动验证；总退出门未计数 | Build #362 已覆盖受限生产 DAG、保存围栏、白名单工作站（含 Bot 私有末影箱）与有限自卫。跨 menu 通用事务、故障注入、通用 lifecycle continuation、工具/副手、广泛战斗与完整生存能力仍未完成或未验证 |

P4 自动化门证明了“受控导航、安全反射和真实玩家规则底座”，不证明所有原版移动组合、
所有伤害/效果或所有模组兼容，更不等于完整生存 AI。具体边界见
[P4 完成验收报告](P4_COMPLETION_REPORT_CN.md)。

## 真实玩家内核

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| `BotServerPlayer extends ServerPlayer` | 已编码 | 没有自定义 `EntityType`，不继承 `FakePlayer` |
| `EmbeddedChannel` 虚拟连接 | 已编码 | `BotConnection` 提供合法本地 channel 与幂等关闭 |
| bot 专用 packet listener | 部分完成 | callback 隔离、teleport ack、进程内 keepalive 记账和常量空间诊断已编码；长时间在线待验证 |
| 登录保持 bot listener | 已编码 | `PlayerList.placeNewPlayer` 精确包装 |
| 重生保持 bot 类型 | 已编码 | `PlayerList.respawn` 精确包装 |
| 死亡完成确认 | 已编码 | `ServerPlayer.die` TAIL 只观察正常完成路径 |
| 原版死亡消费持久化 | 已编码；Build #163 远端验证 | pre-drop V2 tombstone、死亡体/后继双份 NBT 提交、经验 handoff、有界重试与旧 body 永久禁存；不保证掉落实体 exactly-once |
| 稳定 runtime handle / generation | 本地已验证 | 同服务器会话稳定 handle；重生新实例递增代际，旧动作/会话失效 |
| 权威动作实例解析 | 已编码 | 同时复核 runtime、handle、`PlayerList`、维度与 listener 当前实例 |
| 两阶段无客户端玩家 Tick | 本地已验证 | 输入/玩家物理/连接基线由生命周期管理器统一调度，防重复 Tick |
| 稳定身份 | 部分完成 | roster 持久保存 botId/player UUID；重命名、旧身份迁移和离线冲突仍缺 |
| owner 记录 | 部分完成 | 创建者持久化并用于凭据与背包授权；owner 管理、trusted/observer ACL 待补 |
| 真人/名字/UUID 冲突 | 部分完成 | 在线与 roster 冲突已检查；离线 profile/白名单/封禁冲突未完成 |
| 原版 playerdata | 部分完成 | 死亡消费的 `.dat/.dat_old` 双保存、刷盘和回读已有专项验证；一般迁移、跨进程恢复与其他保存路径仍缺 |
| 生成事务回滚 | 部分完成 | 当前异常路径可清理；完整故障点注入与残留诊断未完成 |
| 生命周期状态机 | 已编码，待完整验收 | 中央转换表和有界历史；autoload/持久 OFFLINE/FAILED 仍缺 |
| 持久 roster | 部分完成 | schema v1 保存身份、owner 与 `serverInstanceId`；autoload/迁移故障测试待补 |
| 原版死亡后自动重生 | 本地与远端已验证基础路径 | 100 次死亡—重生曾连续两轮通过；Build #163 另验证消费后空背包 successor 与旧 predecessor 隔离 |
| 死亡恢复交接 | 已编码；跨进程未验收 | 显式 spawn 前读取 tombstone/两份 playerdata handoff 并以规范死亡体恢复；真实第二次启动和断电故障仍缺 |
| 维度切换 | 部分完成 | 代码路径和会话关闭已处理；三维度往返 GameTest 未完成 |
| 区块跟踪 | 部分完成 | 按在线玩家位置刷新；长期 ticket/残留/负载未验收 |
| 停服清理 | 部分完成 | 动作、输入、会话、连接和 manager 清理已接线；真实两次启动、断电窗口和平台文件系统故障仍未验证 |
| 自动加载 roster | 未实现 | roster 已持久化，重启后仍需管理员重新执行 spawn |

## P2-A：确定性动作脊柱

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 动作契约 | 已编码 | ID、botId、generation、幂等键、deadline、`maxTicks`、typed action |
| 动作 FSM | 已编码 | `QUEUED → VALIDATING → RUNNING → VERIFYING → 终态` |
| 结构化结果 | 已编码 | 唯一终态、失败码、安全摘要、有界 evidence |
| 有界 mailbox | 已编码 | 提交/取消入口、每 Tick drain 预算、排队取消原子摘除 |
| 幂等 ledger | 已编码 | canonical envelope、别名加入、冲突拒绝、终态重放、有界淘汰 |
| 控制仲裁 | 已编码 | `MOVE/LOOK/MAIN_HAND/OFF_HAND/INVENTORY/INTERACT/CHAT` 通道 |
| 优先级与抢占 | 已编码 | 高优先级原子抢占、被抢占动作 cleanup 和唯一终态 |
| deadline / `maxTicks` | 已编码 | 排队与运行时限分别检查 |
| completion dispatcher | 已编码 | 有界队列、callback 不在主线程、入口背压、取消保留容量 |
| cleanup / reset / quarantine | 已编码 | cleanup 失败不能把不安全结果写成成功；无法恢复时隔离 generation |
| 生命周期同步关闭 | 已编码 | 死亡/卸载/停服与重入 callback 可在 Tick 返回前关闭同 generation |
| `WAIT / LOOK_AT / STOP` | 已编码 | Minecraft backend 最小纵切片 |
| 动作运行时测试 | 本地已验证 | 重复、别名、取消、抢占、容量、重入、背压和清理故障已纳入 140 项单测 |

## P2-B：输入与短程移动

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 输入 owner/lease | 已编码 | 精确 action/generation owner、过期主动清零、旧 cleanup 不覆盖新 owner |
| 有界移动输入 | 已编码 | 前后/横向、跑、蹲；有限值与互斥组合校验 |
| 跳跃 | 已编码 | 使用普通玩家输入与物理；空中无因果跳跃拒绝 |
| 视角 | 已编码 | `LOOK_AT` 使用玩家视角并验证 yaw/pitch 与目标 |
| 停止 | 已编码 | 清理输入和控制状态；不冒充“释放所有持续物品”的万能动作 |
| 一 Tick 一次 | 已编码 | 每绝对服务器 Tick 输入变更/应用有去重 |
| 原版物理证据 | 已编码 | 位置、速度、姿态、碰撞、水中与跳跃因果 |
| 移动 GameTest | 本地已验证 | 一格跳跃、静止蹲姿、浅水前移、撞墙、空中跳跃拒绝连续两轮通过 |
| 长距离寻路 | 已编码，基础场景已验证 | P4 有界分段 A*、动态重算和 stuck 恢复已接线；200 格直接 GameTest 与跨未加载区域仍缺 |
| 高级移动 | 部分完成 | P4 已验证梯子/门/浅水；脚手架、藤蔓、船、矿车、坐骑、鞘翅未完成 |

## P2-C：基础世界交互

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 选择快捷栏 | 已编码 | 精确槽位与选中物品指纹 |
| 使用/持续使用/释放物品 | 已编码 | 明确主副手、模式、hold Tick 和预期物品 |
| 使用方块/放置 | 已编码 | 明确维度、方块状态、命中面/局部坐标、视线与物品 |
| 分阶段破坏与中止 | 已编码 | 玩家 game mode 入口、进度 Tick、STOP/ABORT、结果证据 |
| 攻击 | 已编码 | 实体 UUID/类型、距离/视线/冷却检查和伤害证据 |
| 实体交互 | 已编码 | 区分 generic/specific interaction 与主副手 |
| 丢弃 | 已编码 | 丢一个/整栈与 ItemEntity/库存数量守恒 |
| 拾取等待 | 已编码 | 有界等待，可绑定明确 ItemEntity UUID |
| 交互前置条件 | 已编码 | generation、维度、区块/目标、指纹、距离、LOS、冷却和手持物 |
| 世界交互 GameTest | 本地已验证 | 放置、破坏、保护拒绝、攻击、丢弃、指定 UUID 拾取连续两轮通过 |
| 保护模组兼容 | 部分完成 | 使用普通玩家路径可接收取消；具体领地/PVP/反作弊矩阵未验证 |
| 通用世界容器 | 部分完成 | Build #362 已自动验证普通单箱/双箱、木桶、原版潜影盒以及 Bot 私有末影箱的严格白名单转移与受限工作站纵切；末影箱不读取方块实体账本、同方块跨 Bot 保守串行；通用工作站、模组 menu 与实机验收仍未完成 |
| 模组/自定义 menu | 未实现 | C1/C3 适配在 P8 |

## P2-D：bot 自身背包 GUI

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 空主手、主手入口 | 已编码 | NeoForge `EntityInteract`；bot、副手和持物品不触发 |
| 权威库存 | 已编码 | 直接绑定 bot 原版 41 格 `Inventory`，没有第二份服务端库存 |
| 77 槽 menu | 已编码 | bot 41 槽 + viewer 36 槽；盔甲反向映射、副手和快捷栏明确 |
| 客户端 screen / MenuType | 已编码，用户已验证 | 自定义 screen 与 `IMenuTypeExtension` 注册；`176×256` 上下堆叠布局，上方 bot、下方 viewer |
| 原版玩家视觉 | 已编码，用户已验证 | 上方按原版玩家背包排列盔甲、副手、主背包和快捷栏，并渲染真实 bot 的 3D 玩家模型 |
| 合成区与快捷栏高亮 | 已编码，用户已验证 | 原版 2×2 合成区域被背景覆盖且没有 menu slot；服务端同步 bot 当前快捷栏索引并只读高亮 |
| GUI 资源边界 | 已编码，未专项验证资源包 | 运行时引用 `inventory.png`、`generic_54.png` 与原版 HUD 选中框；未复制或打包 Mojang PNG，可跟随资源包替换 |
| GUI Scale 边界 | 已编码，基础场景已验证 | 画布逻辑高度为 256；窗口或显示高度不足时需要降低游戏 GUI Scale，全部比例组合仍未专项验证 |
| 会话 FSM | 已编码 | `OPENING → OPEN → CLOSING → CLOSED` |
| generation/nonce token | 已编码 | 旧代际、错误 nonce 和关闭墓碑拒绝 |
| 权限 | 已编码 | 持久 owner 或服务器 OP；trusted/observer 尚未实现 |
| 距离/维度/存活 | 已编码 | `inventory.viewDistance`，每 Tick 复核并失败关闭 |
| 单 viewer 写锁 | 已编码 | 每 bot 单 viewer、每 viewer 单会话 |
| 动作互斥 | 已编码 | 打开前同步排空；打开期间 mutation gate 拒绝动作库存写 |
| Shift 移动与守恒 | 已编码 | bot/viewer 区间、合法装备槽和总数量检查 |
| 生命周期关闭 | 已编码 | 死亡、重生、换维度、超距、退出、menu 替换和停服 |
| 库存 GameTest | 本地已验证 | 入口/副手、权限、双 viewer、距离/生命周期、77 槽、mutation gate 连续两轮通过 |
| 通用世界容器自动化 | 未实现 | P2-D 只处理 bot 自身玩家背包 |

## 当前用户可见操作

| 操作 | 状态 | 说明 |
|---|---|---|
| `/botplayer spawn <name>` | 已编码 | 新 bot 出现在执行者位置；既有 playerdata 使用保存位置 |
| `/botplayer list` | 已编码 | 列出本次服务器运行期内 bot 和生命周期状态 |
| `/botplayer remove <name>` | 已编码 | 从在线运行时卸载，不删除 playerdata |
| `/botplayer settings <name>` | 已编码 | 只允许活动 bot 的持久 owner；不要求 OP，OP 也不能绕过凭据 owner |
| `/botplayer perception inspect <name>` | 已编码；Build #28 编译/测试通过 | 固定要求原版权限等级 2；有界显示活动 bot 的快照、generation-local 活动证据序号和最近事实 |
| `/botplayer perception correct <bot> <actor> <activity>` | 已编码；Build #28 编译/测试通过 | 固定要求原版权限等级 2；actor 必须在线，纠正作为新证据事件 |
| `/botplayer navigation go <name> <x> <y> <z>` | 已编码；Build #97 编译/测试通过 | 固定要求等级 2；只使用 `safeDefault()`，不会开启挖掘或搭桥 |
| `/botplayer navigation stop/inspect <name>` | 已编码；Build #97 编译/测试通过 | 取消活动导航或输出有界 session/重算/恢复/Terrain Assist 计数 |
| `/botplayer safety inspect <name>` | 已编码；Build #97 编译/测试通过 | 输出当前 incident 与有界权威身体帧，不包含长期记忆 |
| 空主手、主手右键 bot | 已编码，待客户端验收 | owner 或 OP 在范围内打开 bot 自身背包；不是世界容器自动化 |
| 客户端 API Key 管理 | 已编码 | 创建/替换 profile、绑定/解绑；profile 删除未实现 |
| 服务端 agent binding | 部分完成 | 只保存 botId↔agentId 运行时关系；owner 离线/卸载/停服时清除 |
| `/botplayer ai review <name>` | 已编码；Build #362 自动验证通过 | 仅真实持久 owner、活动 bot 和 active binding；owner 本地显式开启 R1 后发送固定只读快照，结果只作安全摘要并丢弃，不进入 Skill、Action 或世界 |
| 通过管理命令导航 | 已编码 | P4 提供 OP 诊断入口；不是普通玩家任务系统、技能或 AI 调用入口 |
| 聊天与 DeepSeek | 部分编码 | Provider/codec/firewall/context、客户端 DeepSeek 传输和默认关闭的 owner R1 只读审阅链已通过 Build #362 自动验证；没有通用聊天、自动计划或世界执行，R1 的真实客户端/Provider E2E 仍待验证 |

## 当前 server 配置

现有配置分为：

- `server_player.*`：最大 bot 数、Tab、自动重生、重生延迟、区块跟踪刷新；
- `actions.*`：mailbox、ledger、每 Tick 命令、活跃动作和 completion 五个容量；
- `inventory.viewDistance`：bot 自身背包查看/编辑距离；
- `perception.*`：范围、彼此分离的权威投影与公开传感器预算（传感器侧含每 bot/全局
  限额）、事件/事实/revision 容量、活动窗口和 MSPT 降级/恢复阈值；
- `navigation.*`：局部快照、A*、规划池、goal、follower、补给阈值，以及默认关闭的
  Terrain Assist 服务端门和方块预算；
- `safety.*`：生命/食物/空气/冻结阈值、威胁半径、实体读取预算、incident 稳定与干预上限；
- `permissions.commandPermissionLevel`：`spawn/list/remove` 的原版权限等级；P3
  `perception` 与 P4 `navigation/safety` 管理命令固定要求等级 2，不随该值降级。

准确键、默认值、范围和建议见 [CONFIGURATION_CN.md](CONFIGURATION_CN.md)。

## P2-E 验收状态

| 门禁 | 当前 |
|---|---|
| 严格 `compileJava` / `compileTestJava` | 通过，`-Xlint:all -Werror` |
| 完整 `test` | 140/140 通过 |
| P2 `runGameTestServer` | 19/19，连续两轮通过 |
| `clean build` / JAR | 通过，产出 `botplayer-0.1.0-alpha.2.jar` |
| 推送后 GitHub Actions | 通过；[Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722) |
| 客户端 screen 手工测试 | 本轮基础场景已验证 |
| 独立专用服 | 未验证 |
| 多 bot soak / 性能 | 未验证 |

本地与远端自动化退出门均已通过，因此整体 P2 的实现与自动化验收判定为完成。客户端
screen、独立专用服和长时间 soak 是明确保留的专项验证，不随自动化门一起冒充完成。

## P2 之后的阶段状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P3 | 感知、语义事件、世界模型、玩家活动理解 | 自动化退出门已通过；客户端、独立专用服与 soak 未验证 |
| P4 | 导航、安全反射、动态重规划、玩家规则兼容 | 自动化退出门已通过；复杂移动、专用服、保护模组与 soak 未验证 |
| P5A | 技能 FSM、首条生存闭环、最小原版世界容器驱动 | Build #362 已自动验证受限资源—制作—存放 DAG、保存围栏和两阶段重启；跨 menu 通用事务、工具/副手与独立专用服仍未完成或未验证 |
| P5B | 广泛原版容器/工作站、制作、生产和日常生活 | Build #362 已自动验证严格白名单容器、工作站边界、Bot 私有末影箱、受限 wheat/甘蔗收获、牛繁殖、单笔村民交易和牛奶解毒纵切；末影箱只承诺账本隔离与守恒取消，不承诺逐槽回滚或同方块跨 Bot 并行。这不等于通用容器、任意配方/作物/交易或自动药物策略，真实客户端与专用服仍待验证 |
| P5C | 运输、游戏进程和高级战斗 | Build #362 已自动验证旧有的、有限自卫会话授权的单次 `MELEE_ATTACK` 窄 bridge；当前分支已把该路由迁入单个 owner-thread `TechniqueLifecycleCoordinator` 的 Contract，但此提交仍待 Java 21 CI。目标选择、移动、装备、重试、连击、泛化 Technique 路由和其余 P5C 能力仍未实现 |
| P5D | 建筑与红石 | 未实现 |
| P6 | DeepSeek、聊天、Tool Firewall、预算 | 部分编码：Provider/故障边界、codec/firewall、上下文、session 修复与 R1 固定只读审阅往返已由 Build #362 自动验证；R1 绝不执行世界动作。ADR-0022 的通用 binding 账本及 ADR-0024 的 owner-thread gate+ledger 协调器（全局上限、精确 close、有界安全 terminal mailbox）已编码；P6-C2 另增加默认 no-op 的客户端本地安全 terminal-observation Contract，只投影 receipt+status，不发包、不接 coordinator 或 R1。上述当前增量提交仍待 Java 21 CI；Lifecycle、Network、通用 Client bridge 与 Scheduler 均未接线。通用 client-sponsored bridge、聊天/模型策略与 AI→技能计划/世界执行仍未实现，真实客户端/Provider E2E 仍待验证 |
| P7 | 长期记忆、目标、承诺和恢复 | 未实现 |
| P8 | 模组 C0–C3、标准容器与自定义 menu 适配 | 未实现 |
| P9 | 多 bot 协作 | 未实现 |
| P10 | 性能、兼容、安全和正式发布硬化 | 未实现 |

## 下一道门

1. 把 P5 的受限自动化纵切扩展为跨 menu 通用事务、工具/副手、任意配方/作物/交易和广泛战斗；
2. 补 P4 保留的 200 格、实体阻挡、stuck、熔岩/窒息/冰冻与喷溅药水专项场景；
3. 在保护/领地模组上验证 Terrain Assist 拒绝后不重试、不伪装成功；
4. 完成 P5/P6 的真实客户端、独立专用服与多 Bot soak 专项验证；
5. 不把 P4 通用避险宣传成完整吃饭、用药或战斗能力；主动药物仍固定在 P5B；
6. 不把 P3 方块观察宣传成容器内容读取；
7. 不把客户端凭据或默认关闭的 R1 固定审阅往返宣传成通用 DeepSeek、聊天、规划或 AI 世界执行；Build #362 已完成 Java 21 自动基线，P6 通用 bridge 与真实客户端/Provider 验证仍待完成。

## 验证命令

```bash
./gradlew --no-daemon clean test
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon clean build
```

开发运行：

```bash
./gradlew runClient
./gradlew runServer
```

构建成功只证明编译、测试和打包任务成功，不等于客户端、专用服、多 bot 或长时间运行通过。

## 客户端凭据的准确边界

- Key 只写入 owner 客户端的独立本地明文文件，优先原子替换并尽力收紧权限；
- 凭据文件位于客户端游戏目录 `config/botplayer/credentials-v1.json`；非 secret binding
  文件是同目录 `bindings-v1.json`；
- Key 不进入命令、聊天、Minecraft payload、服务端、世界数据或日志；
- `(serverInstanceId, ownerUuid, botId)` 隔离不同服务器、owner 与 bot 的绑定；
- 一个 credential profile 可以被多个 bot 引用，但 agentId 按 bot 独立；
- 只有 roster 中持久 owner 可以配置；修改客户端文件不能改变服务端 owner；
- 当前支持创建/替换 profile 以及绑定/解绑；不支持删除 credential profile；
- owner 离线时，P6-R1 与未来 client-sponsored LLM 均不可用；
- 除 R1 外当前没有任何通用 API 请求。真实持久 owner 以本地 `reviewOnly.enabled=true`
  显式 opt-in 后，`/botplayer ai review <name>` 才会在 owner 客户端发起固定 review-only
  HTTPS 请求；其结果只形成安全摘要并丢弃，不能聊天、规划或改变世界。该真实客户端/
  Provider E2E 仍待验证。
