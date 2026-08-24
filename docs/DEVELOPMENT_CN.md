# BotPlayer 开发指南

本文面向准备修改 BotPlayer 代码、测试或文档的开发者。

## 环境

| 工具 | 要求 |
|---|---|
| JDK | 21 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.244 |
| Gradle | 只使用仓库 Wrapper 9.2.1 |
| 默认编码 | UTF-8 |

不要使用系统 Gradle 替代 `gradlew`，也不要在同一功能提交中顺便升级映射、NeoForge 或
构建插件。

## 获取和构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
./gradlew --no-daemon clean build
```

当前贡献基线是仓库默认 `main`。新功能分支从最新 `main` 创建，不要继续从已经合并的
P0/P1 审查分支派生。

Windows：

```bat
gradlew.bat --no-daemon clean build
```

常用任务：

```bash
./gradlew runClient
./gradlew runServer
./gradlew generateModMetadata
./gradlew processResources
```

P2 已加入生命周期、移动、交互和库存 GameTest；P3 加入有限感知与世界事实场景；P4
加入导航、安全、玩家规则兼容与 Terrain Assist 场景。当前 P5/P6 集成验收载体为
[`agent/p5-p6-next`](https://github.com/GreyTaiWolf/BotPlayer/tree/agent/p5-p6-next)；
[Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) 已通过 Java 21
`clean build`、Gradle `test`、161 项常规 GameTest 和 phase-one/phase-two 重启
GameTest（各 1 项）。P5 总退出门和 P6 总退出门仍未关闭；真实客户端、专用服和多 bot soak
不由此替代。Build #362 是当前 P5A 修复/受限工具-主手-副手 native-menu GameTest 源、P5C lifecycle Contract/固定副手盾牌持有、P6 会话协调器、本地
ledger-first proposal review、P6-A0/A1a/A1b token-reservation/physical-retry budget、P6-B0 server-owned
physical-attempt handshake、P6-B1 mock payload-path GameTest 源与 P5D-A0/A1/A2/A3/A4/A5/A6 有界蓝图/施工工作包/candidate-site/survey-assessment/placeable-item/loaded-world survey/spatial-lease 数据增量提交之前的自动化基线；这些提交仍须各自通过 Java 21 CI。涉及
Minecraft 行为的提交必须运行：

```bash
./gradlew --no-daemon runGameTestServer
```

## 当前源码结构

```text
src/main/java/io/github/greytaiwolf/botplayer/
  BotPlayer.java                 模组入口与 server config 注册
  command/                       当前 /botplayer 命令
  config/                        当前 server 配置 schema
  event/                         NeoForge 生命周期事件入口
  identity/                      临时名字派生 UUID
  profile/                       持久 BotProfile DTO 与 NBT 编解码
  persistence/                   schema v1 roster、owner 与 serverInstanceId
  kernel/                        ServerPlayer、连接、listener、runtime handle
  lifecycle/                     在线实例状态机、generation、动作/会话装配和管理器
  action/                        动作契约、FSM、mailbox/ledger/仲裁、输入与 Minecraft backend
  ai/                            P6 纯 Java Provider DTO/策略/上下文、P6-A0 accounting、P6-A1a context、P6-A1b opt-in physical retry hook 与 P6-B0 server-owned offer/ACK/start-grant handshake + client-local one-claim gate；不接 Scheduler/network/client generic bridge、Provider/HTTP 或真实计费
  inventory/                     77 槽 menu、session、写锁与 mutation gate
  perception/                    P3 预算、快照、事件收集/投影与 generation 编排
    event/                       有界 AuthorityEvent/PerceivedEvent 双平面
    sensor/                      只读已加载世界的有限传感器
  navigation/                    P4 请求/session、运动快照、A*、follower 与 Terrain Assist
  safety/                        P4 每 Tick SafetyFrame、incident FSM、威胁探针与抢占
  skill/                         P5 有界 Skill 契约、DAG、资源预留与当前生存纵切
  technique/                     短生命周期玩家 Technique；当前一个 lifecycle coordinator + 有限自卫单次近战及固定副手盾牌 route
  building/blueprint/            P5D-A0 有界纯 Java Blueprint、计划方块需求与内容 hash；不接 Minecraft/Action/Technique/world
  building/construction/         P5D-A1 有界纯 Java work-package exact-cover/DAG 数据合同；不接 site/material/Technique/Action/world
  building/site/                 P5D-A2 candidate anchor/derived-bounds/work-plan binding，A3 caller-supplied exact target evidence 的 fail-closed assessment；A5 `minecraft/` 只在 server thread 将 binding 的已加载 canonical target state 转为 immutable survey；A6 只复用现有 TTL reservation 将 exact bounds 映射为最多 8 个 conservative spatial tile lease；不创建材料/临时区 reservation、许可、placement/Technique/Action
  building/material/             P5D-A4 full-state explicit item declaration 与 declared quantity；不猜 blockId→itemId，不读 registry/inventory/world，也不表示 reservation/placement/许可
  worldmodel/                    scoped revision、短期事实与确定性活动推断
  gametest/                      P2–P6 NeoForge GameTest
  network/                       界面打开与 agentId 绑定 payload；永不传 Key
  client/
    BotPlayerClient.java         CLIENT 物理端装配本地 store 与 payload 实现
    ClientPayloadHandlers.java   common-safe facade，不引用 net.minecraft.client
    PhysicalClientPayloadHandler.java 真实客户端 Screen/payload 处理
    credential/                  profile、binding、严格 JSON 与原子保存
    screen/                      Key GUI 与 bot 自身背包 screen
  mixin/                         四个最小版本接入类

src/main/resources/
  assets/botplayer/lang/         客户端文本
  data/botplayer/structure/      GameTest structure fixture
  botplayer.mixins.json          Mixin 清单

src/gameTestFixtures/
  java/                          只参与 GameTest 的动态 DamageType/PlayerTick 兼容 fixture
  resources/                     只参与 GameTest 的数据驱动伤害类型

src/main/templates/
  META-INF/neoforge.mods.toml    构建时展开的模组元数据
```

完整目标包结构见
[架构文档第 16 节](ARCHITECTURE_AND_ROADMAP_CN.md#16-推荐包结构与类职责)。

## 核心不变量

任何提交都必须保持：

1. bot 主体是 `BotServerPlayer extends ServerPlayer`；
2. 不为 bot 注册自定义 Mob 或专用 `EntityType`；
3. 不通过继承 NeoForge `FakePlayer` 获得捷径；
4. 所有 Minecraft 活动对象只在服务器主线程访问；
5. 重生后不能继续持有旧 `BotServerPlayer`；
6. 世界副作用最终必须经过动作层和结果验证；
7. 模型输出不是权限、事实或成功证据；
8. API Key 只进入 owner 客户端的独立本地凭据 store，不进入命令、聊天、Minecraft
   payload、服务端、世界或日志；
9. 异常生成和卸载不能在 PlayerList、Level 或 manager 留残余；
10. 版本相关 NMS/Mixin 代码集中在平台接入边界；
11. roster 是 bot 身份、持久 owner 和 `serverInstanceId` 的服务端权威源；
12. 共用 credential profile 不得合并不同 bot 的 agentId 或状态；
13. 客户端凭据落盘当前是明文与尽力文件权限，不得描述成加密或 keychain；
14. 没有 Provider/HTTP 时不得把凭据 UI 描述成 DeepSeek 已接入。
15. 全服 `AuthorityEvent` 不得自动成为任何 bot 的 `PerceivedEvent`；
16. 传感器不得用 `getChunk` 或 ticket 为感知强制加载区块，未知不等于空气；
17. P3 DTO、事件环、声音候选、事实、revision scope、扫描和证据都必须有硬上限；
18. 完全未感知的变化不得触碰该 bot 的事实、认知水位或公开传感器预算；只有已投影但
    结果不确定的事件或 TTL 才能令事实 `STALE_UNKNOWN`；authority coverage gap 也只能
    进入管理员私有诊断并快进内部 cursor；
19. P3 不读取 `BlockEntity` NBT/menu slot 获取容器内容；世界容器仍属于 P5A/P5B/P8。
20. 导航搜索只读取服务器线程生成的不可变、已加载世界快照；异步 planner 不得持有
    `Level`、`Entity`、`BlockState` 或其他活动 Minecraft 对象；
21. 路线 follower 只通过有界 P2 输入动作驱动真实玩家物理，不得传送、直接改位置/速度
    或跳过碰撞来伪造到达；
22. L0 安全每 Tick 读取权威近场，不能依赖可能降频的 P3 快照；危险必须能够关闭菜单、
    挂起导航并以 `SURVIVAL/EMERGENCY` 抢占普通输入；
23. Bot 使用原版/NeoForge 玩家伤害、护甲、饥饿、效果和属性链；不得建立第二套数值、
    特殊免疫或重复扣血；
24. Terrain Assist 默认关闭，必须请求 policy 与服务器配置同时允许；所有破坏/放置
    必须经过 P2 动作、保护事件、精确结果验证和单次预算，成功后重新采样和规划。

## Roster 与客户端凭据检查

当前 roster SavedData 名是 `botplayer_roster`，schema v1 保存随机持久
`serverInstanceId`，以及每个 profile 的 botId、规范名字和可选 owner。修改它时必须保证：

- 首次由真实玩家创建才分配 owner；控制台、命令方块和 bot 创建得到无 owner profile；
- 同名大小写归一命中既有 profile，后续 spawn 不覆盖 owner；
- 不支持的 schema、重复 botId/名字和缺失 serverInstanceId 安全失败；
- roster 不保存 Key、credential profile ID、agentId 或客户端 binding；
- autoload、owner claim/transfer、永久删除和正式重命名当前仍未实现。

客户端文件位于当前游戏目录的 `config/botplayer/credentials-v1.json` 和
`bindings-v1.json`（默认启动目录通常是 `.minecraft`）。`ClientCredentialStore` 必须
继续保持：

- credential JSON 与 binding JSON 分离，后者使用
  `(serverInstanceId, ownerUuid, botId)`；
- profile 可创建/替换，bot 可绑定/解绑；当前没有 profile 删除；
- 相同 profile ID 替换 Key 时，共享它的其他 bot binding 不丢失；
- 每个 binding 的 agentId 唯一且稳定，不能被另一个 bot 同时占用；
- 严格字段/schema 校验、临时文件优先原子替换（不支持时同目录覆盖）、POSIX 权限尽力设置；
- 读取损坏文件时拒绝加载和覆盖，错误与 `toString()` 不输出 secret；
- `/botplayer settings <name>` 只对活动 bot 的精确持久 owner 成功；OP 无绕过；
- payload 只包含 serverInstanceId、botId、botName、agentId 和状态，不包含 Key、profile ID
  或 Key 派生信息；
- owner 退出、bot 卸载和停服清除服务端运行时 agent binding。

## 当前四个 Mixin

| 类 | 目的 | 修改行为 |
|---|---|---|
| `ConnectionAccessor` | 为本地连接设置私有 channel | 只暴露字段写入 |
| `PlayerListMixin` | 登录时换 listener；重生时保持 bot 类型；按精确 fence/permit 拦截不安全保存 | 两处 `NEW` 包装；`save` HEAD 只检查持久 fence/死亡精确许可，`remove` 内的 `save` 包装才消费一次性门闩 |
| `ServerPlayerDeathMixin` | 标记正常死亡 TAIL 并通知 manager | 取消死亡的早退路径不进入；业务收口留在 BotPlayer/生命周期层 |
| `LivingEntityUseItemMixin` | 严格消耗品在原版提交前复核快照和时间 | `HEAD` 围栏全部 active strict `UseItem`；`completeUsingItem()` 前复核/不可逆提交相位只限 natural completion。两个点在 action deadline/maxTicks 当 tick release/stop 并取消该次消费，避免原版先消费后 timeout；Finish/Post 的已提交取消等待 exact Action receipt，围栏/时间/取消入口失败由 lifecycle 同步隔离该 generation |

修改 Mixin 时必须：

- 固定精确方法和目标描述符；
- 使用 `require = 1`，让版本漂移在开发期失败；
- 证明真人玩家路径保持原样；
- 增加对应 GameTest；
- 更新 ADR 和架构文档；
- 在目标 NeoForge 版本做干净构建和运行验证。

不要把普通业务逻辑塞进 Mixin。

## 生命周期修改检查

修改生成、死亡、重生、维度或卸载时至少考虑：

- 相同名字和 UUID 已在线；
- playerdata 存在与不存在；
- `placeNewPlayer` 中途抛异常；
- NeoForge 取消死亡；
- 重生创建新的玩家实例；
- 维度转换返回 `null`；
- `/kick`、命令移除、停服；
- 单个登出事件抛异常；
- 零真人玩家；
- 多 bot 顺序和并发请求；
- PlayerList、Level、连接、区块和 runtime handle 残留。

## 当前 P2 动作层规则

P2 候选中的普通世界变化必须经过：

```text
意图/技能
→ ActionEnvelope
→ 权限与安全 Guard
→ ServerPlayer 动作入口
→ NeoForge/原版校验
→ ActionOutcome
→ 世界状态验证
```

不能以这些方式伪造任务完成：

- 直接 `setBlock` 代替合法放置；
- 直接删除方块代替挖掘；
- 直接修改背包代替容器操作；
- 传送代替正常寻路；
- 接受 LLM 的“已完成”文本而不查世界状态。

测试夹具、管理员恢复工具和迁移器可以有受限的直接写入，但必须与普通技能入口隔离并审计。

## 当前 P3 感知层规则

P3 的固定顺序是：

```text
P2 ActionOutcome / NeoForge 候选 / 定向声音包
→ post-state 验证与 AuthorityEvent
→ 维度/目标/距离/视线/预算投影
→ (botId, generation) PerceivedEvent
→ 有界不可变 ObservationSnapshot
→ scoped revision 与短期 WorldFact
→ 有证据、带置信度的 ActivityHypothesis
```

修改感知时至少检查：

- `PerceivedEvent` 不嵌入完整 `AuthorityEvent` 或未授权 delta，只保留 opaque
  `authorityEventId`；其 `perceivedSeq` 必须按 generation-local stream 递增；
- 普通 authority audit、独立 spatial authority projection 与 routed sound audit 三环
  共享唯一 `eventSeq`；声音审计不得进入空间投影环，`SELF/DIRECT` 洪泛不得挤出
  `VISUAL/AUDIBLE` 候选；每 generation 的声音/非声音认知分环并共享 local
  `perceivedSeq`，声音洪泛不得逐出普通语义证据；
- AI-safe 快照不暴露 authority session/seq、全局 `worldRevision` 或全服预算计数；
- `VISUAL/AUDIBLE` 只做 same-tick 投影；空间事件超预算时只选独立 spatial ring 的最新
  有界窗口并计管理员 coverage，积压、预算延迟、gap 和晚到声音 fail-closed；
- 动作终态 sink 同步、隔离失败且不重复发布 replay/alias；
- 可取消事件在世界结果验证前不写 `COMMITTED`；
- break/place/toss 待验证候选在捕获时冻结 bot generation；`routing.*` 只供内部
  generation 定向，不得进入 `PerceivedEvent`；
- critical action-outcome ingress 与 P2 最大 canonical 吞吐对齐；成功 break 按两个
  发布单位计，其他终态按一个单位计；
- 射线在未加载边界停止，方块/实体读取前检查已加载；
- 实体索引每次原始回调先扣 `ENTITY_SCAN`，selector 匹配后再扣 `ENTITY_READ`，达到任一
  上限立即中止，不能先构造无界候选 `List`；
- 局部方块只有实际支撑块可免 LoS，其余邻域/焦点必须防 X-ray；
- `BlockState.hasBlockEntity()` 只能输出 opaque 标记，不能调用 `getBlockEntity`；
- 对已读取候选使用稳定排序；超出读取上限必须显式 `truncated`，不承诺密集场景的入选
  子集完全独立于底层实体迭代顺序；
- `NORMAL/DEGRADED/CRITICAL` 下自身关键观察仍有明确频率；
- 权威扫描与公开传感器分池计费；快照只暴露公开传感器预算，未感知权威事件不能改变它；
- `globalWorkPerTick` 不得低于 64，否则 3/4 公开池无法原子读取完整 41 槽背包；
- 预算耗尽和未加载通过本 bot limits/截断暴露；权威环淘汰只在服务器内部 fail-closed；
- 死亡、重生、换维度、卸载、回滚和停服关闭旧 generation；
- 完全未感知的世界变化不触碰该 bot 的世界模型；已投影 `COMMITTED` 变化令 scope
  `STALE`，已投影但结果不确定的变化或 TTL 才令事实 `STALE_UNKNOWN`；authority gap
  不改变事实、认知水位或公开预算；
- 诊断命令有界，不输出全服权威载荷、authority/global counters 或任意容器内容；
- 容器事实只能是 opaque 位置/方块类型与失效，不得生成内容 digest。
- 活动窗口每 Tick 严格剔除过期证据，推断单次按 actor 聚合并按窗口内新近证据优先；
  actor 上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，不得把 `use_on_block`
  单独解释为 building。

设计依据见
[P3 调研设计](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md) 和
[ADR-0013](adr/0013-finite-perception-two-plane-world-model.md)。

## 当前 P4 导航与安全规则

P4 主线程/异步边界是：

```text
主线程采样已加载世界
→ 不可变 MotionSnapshot
→ 有界 planner executor
→ generation/revision 复核
→ 短租约 P2 输入动作
→ 真实玩家物理与结果验证
```

修改导航或安全时至少检查：

- 未加载区块保持未知，不调用加载/ticket API，不把未知当空气；
- planner 的节点、距离、队列、并发、结果 inbox、deadline 和取消全部有硬上限；
- 终点必须以真实身体位置、速度和可站立面确认，不以“路线已算出”当作到达；
- 方块 revision、动作后状态、偏航和 stuck 会使旧路线失效；恢复次数有限且失败码诚实；
- L0 安全在任何 P3 压力档位继续每 Tick 运行，并只使用停止、后退/侧移、安全邻格、
  上浮、闪避和远离威胁等 P4 动作；
- 低生命/食物可以阻塞普通远行，低食物禁止 sprint；当前 P5 开发切片只委派主动进食，
  主动用药和未满足冻结资格门的自卫不得截断 P4 安全回退；
- 伤害与效果观察以真实身体最终值为准，不通过固定原版枚举拒绝动态 `DamageType` 或
  `MobEffect`；
- Terrain Assist 的请求允许不是强制世界修改；存在纯移动路线时优先纯移动；
- 破坏/放置白名单、工具、支撑、流体、方块实体、库存、远端锚点、保护事件和预算任一
  不满足都要安全拒绝；
- 死亡、重生、换维度、卸载、停服和 generation 变化关闭旧 session、incident 与动作。

完整边界和证据见
[P4 调研设计](AI_PLAYER_RESEARCH_AND_P4_DESIGN_CN.md)与
[P4 完成验收报告](P4_COMPLETION_REPORT_CN.md)。

## 当前 P5 开发切片规则

P5 当前源码建立有界 Skill 核心、确定性 DAG 校验、TTL 资源预留、Safety handoff、
主动进食、扫描 carried inventory `0..35` 的基础盔甲升级、受限资源—制作—存放 DAG、
白名单容器/工作站（含 Bot 私有末影箱）和有限自卫。Build #362 已自动覆盖这些窄纵切；
它们不能据此计入 P5 总退出门。
P5C 的窄接线把已有有限自卫会话已经授权的一个 `MELEE_ATTACK`，以不可变
`AttackEntity`/target/generation/ticket 绑定交给单 child Technique。另有管理员请求的
P5C-S1：只从生命周期已冻结、已经装备的精确原版副手盾牌创建一条 generic
`UseItem(OFF_HAND, RELEASE_AFTER_HOLD)`，固定 8 Tick 后释放；入口要求所有竞争 owner
静止、原版 `InventoryMenu` 与空 cursor。服务器生命周期只驱动一个 owner-thread
`TechniqueLifecycleCoordinator`；两条 bridge 都是受限 Action 路由，并不拥有第二个 runtime。
盾牌 route 不接受目标、移动、换装、重试、反击、自由时长或普通停止，且不能宣称真实受击
格挡、耐久变化或斧破盾。它们都不提供任何通用 Technique→Action 路由，更不构成 P5D
建筑/红石能力；P5C-S1 的纯 Java/GameTest 源仍待 Java 21 CI 与 NeoForge GameTest。
管理入口为：

```text
/botplayer skill equip-armor <name>
/botplayer skill inspect <name>
/botplayer combat shield-hold <name>
```

`equip-armor` 启动基础盔甲升级，`inspect` 只查看 run 状态。`combat shield-hold` 是固定、
无普通停止的副手盾牌 hold/release 管理入口，而非战斗规划或通用副手选择。修改这批代码时至少检查：

- 每个运行、异步动作和管理视图都绑定 `botId + generation + runId + revision`；
- 异步回调只提交不可变信号，世界读取与状态推进留在服务器主线程；
- 业务成功读取动作验证边界冻结的 item/food 证据，不用下一 Tick 的活状态重判已完成
  动作；缺失、重复或格式错误的证据默认失败；
- deadline、信号队列、运行视图、资源租约、incident 重试与每 Tick drain 均有硬上限；
- 主背包食物只在存在空快捷栏槽时临时交换，不覆盖已有物品；前后指纹和物品多重集必须
  通过真实 `InventoryMenu` 路径复核；
- 业务动作必须在专用 cleanup reserve 前结束；失败、取消或抢占后先恢复临时槽位和原
  选择再发布终态；原槽被可解释的外部插入占用时保留全部当前物品并以 `WORLD_CHANGED`
  失败，只有物品多重集无法解释或无法证明动作补偿安全时才隔离整个 generation；
- 生命周期关闭必须同时取得动作清理回执和背包布局回执；后者用一次性 `runId` 租约绑定
  初始结构化物品计数、食物指纹、source/temp 槽和原 selection。目标食物只允许减少零或
  一个，全部非目标物数量必须守恒；远程查看者、旧租约重放、补偿无进展、增殖或丢失均
  fail-closed，不能用“无活动 ticket”推断已完成交换已经恢复；
- 动作与布局两张回执都安全后才允许换代或复活；replacement cleanup 必须绑定同 UUID、
  同 runtime handle、同一连接的旧/新 body，且只在旧 body 已离开所有维度后执行
  body-local 补偿。旧维度的世界局部清理不得通过新 body 发包；
- 断线请求必须先原子冻结 listener 权威，再在原版保存/移除玩家之前关闭 generation；
  pending 记录绑定精确 body、listener、底层 connection 与 generation，清理同步回调
  触发的重复 remove/disconnect 只能并入，不能抢先 finalize 或形成双重保存；同代
  retirement 回执只能保持或降级，不能用二次 cleanup 把 unsafe 改写为 safe；
- replacement/respawn 收敛只允许一个真实排队重试；异常身份的 teardown 必须使用
  一次性 no-save removal 门闩，不能让 `PlayerList.remove` 隐式保存未验证布局；
- 只有 `dropEquipment()` 真实进入 `keepInventory=false` 消费、pre-drop tombstone 已耐久
  发布且原版方法正常返回，才能产生 `VANILLA_DEATH_CONSUMED`；死亡 TAIL 未取得该回执时
  必须按 `PRESERVED` 收口，不能只看 gamerule 推断；
- 原版已经掉落的布局只允许“消费并关闭”，不得再执行普通菜单补偿把物品回滚进旧 body；
  ticket 必须绑定 transaction、bot、generation、Tick 与精确经验策略；
- 死亡 playerdata 提交顺序固定为 `save × 2 → force .dat/.dat_old/目录 → bounded read × 2
  → clear marker → force marker 目录`。任一步失败都不能清票、释放 fence 或开放重生；
- 死亡体和 successor 的持久化各最多尝试 4 次，名义间隔 20 Tick，接近原 retirement
  deadline 时向截止 Tick 收敛；耗尽后保留当前阶段已耐久的 marker、handoff 或
  canonical-alive NBT 并 no-save 隔离，不得每 Tick 无限写盘；
- successor 只有在两份 NBT 都证明存活、空背包、精确经验且 handoff 消失后，才可释放
  继承 fence；已移除 predecessor 必须永久 no-save。精确保存许可只放行首个外层 save，
  同步递归保存必须被抑制；
- 食物属性通过当前 `ItemStack` 与 Bot 身体动态查询；当前只接受无剩余容器、无声明
  有害效果且无自定义完成逻辑的原版基础 `Item` 食物，可疑炖菜、紫颂果、蜂蜜瓶和模组
  食物默认拒绝；
- 成功必须观察真实食物值上升，并恢复临时背包布局和原快捷栏选择；
- 盔甲候选来自 carried inventory `0..35`，按 HEAD/CHEST/LEGS/FEET 固定顺序比较原版
  防御、韧性和剩余耐久；当前槽绑定、候选装备后绑定、零耐久、非 `ArmorItem` 或不可
  装备都拒绝；
- 通用 `SWAP_SEQUENCE` 只接受 1～16 次点击、最多 8 个槽位；每 Tick 最多派发一次点击，
  cleanup 跨 Tick 返回 `PENDING` 并保持首次冻结端点，旧 owner 和 claimant 在非端点均
  不得完成。每个确认前缀只推进一次 revision；不得同步循环点击或描述为无条件回滚；
- generic `SWAP_SEQUENCE` 的 equipment/offhand 槽必须继续 `UNSUPPORTED`；受限 P5A handler
  只能把请求工具、白名单精确主手物品和显式普通副手冻结为原版 46 槽菜单事务，基础盔甲的热栏
  单击和主背包 2～3 步路径保持独立，不得借通用序列绕过装备限制；
- 每件盔甲仍是独立 `InventoryMenu` 事务；动作完成信号进入技能 FSM 后必须再次读取权威
  41 槽布局，外部修改以 `WORLD_CHANGED` 失败，不能用冻结计划自证成功；
- 敌对目标继续走 P4 安全回退；当前唯一例外是已有有限自卫已完成授权的单一
  `MELEE_ATTACK` 可以走受限单击 bridge，以及管理员触发、预装备精确原版副手盾牌的固定
  8 Tick hold/release。后者没有真实受击格挡、耐久或斧破盾证据；两者都不补足武器选择、
  目标选择、撤退路线、逐击重观察或脱战后置条件，不能据此宣称有限自卫或高级战斗已完成；
- 当前受限生产链已具备 TaskSensor/Reservation、Checkpoint、craft/chest/furnace/DAG 和
  有限自卫纵切；请求工具、白名单精确主手物品与显式普通副手的 handler 已有经 lifecycle plan
  的真实 `InventoryMenu` / `WORLD_MENU_TRANSACTION` GameTest 源，普通副手盾牌在 action 前
  fail-close；该测试仍待 Java 21 CI。P5A-M1a 另为 world-menu 的原版 `clicked()` / `broadcastChanges()` 异常建立了
  `CLICK_DISPATCH_FAILED` fail-closed 边界：已领取 click 不会被 ACK 或重派，适配器只作一次
  exact reread 后交给既有原版 close cleanup。该 pure Java/adapter seam 仍待 Java 21 CI 和真实
  修改前/后抛错 GameTest；跨 menu 统一事务、生命周期通用 continuation、泛化工具/副手策略、任意
  配方/作物/交易和广泛战斗仍未实现或未验证，P5 总退出门没有完成。

完整冻结合同与退出门见
[P5 调研设计](AI_PLAYER_RESEARCH_AND_P5_DESIGN_CN.md)和
[ADR-0015](adr/0015-bounded-skill-runtime-and-menu-transactions.md)。死亡消费的磁盘与交接合同见
[ADR-0016](adr/0016-durable-vanilla-death-consumption-handoff.md)。

## 测试层次

| 测试 | 适合内容 |
|---|---|
| 纯 Java 单元测试 | schema、状态机、权限、DAG、失败码、路径成本 |
| NeoForge GameTest | 玩家生命周期、动作、方块、实体、菜单、维度 |
| 集成测试 | HTTP mock、SQLite、配置和适配器 fixture |
| 客户端凭据单元测试 | `ClientCredentialStoreTest`：round-trip、共享 profile、agent 隔离、替换/解绑、损坏拒绝与脱敏 |
| 回放测试 | 感知事件、活动理解、目标和记忆 |
| Chaos/Fuzz | 非法 AI 输出、迟到、重复、超限、取消 |
| 手工客户端 | 玩家外观、Tab、动画、背包 Screen |
| Soak/性能 | 多 bot、内存、MSPT、连接和任务泄漏 |

当前 CI 运行 `clean build`、`runGameTestServer` 并上传 JAR。P2 基线由 Build #18
验证；P3 提交 `38851d1791b84e73705b302be8438e441c3f26ff` 的
[Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181)
使用 Temurin Java 21.0.11 执行 `./gradlew --no-daemon clean build runGameTestServer`，
`compileJava`、`compileTestJava`、Gradle `test`、27/27 GameTest、clean build 与 JAR
upload 全部通过，日志明确 `All 27 required tests passed`，P3 batch 为 8 tests。
源码静态 `@Test` 计数是 P3 43、全仓 183，不是 CI 日志直接报告的通过数。日志中的
GameTest 27/P3 batch 8 是实际运行结果。artifact 为 `botplayer-neoforge-1.21.1`
（ID `8702261459`，`653364` bytes，SHA-256
`90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac`）。这些自动化结果
不证明客户端 screen、独立专用服或多 bot soak。

P4 提交 `9fec0388c36870248a204d7ff21b1b663b62bebf` 的
[Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204) 使用同一
Temurin Java 21.0.11 和完整命令通过严格编译、Gradle `test`、55/55 GameTest、clean
build 与 JAR upload，日志明确 `All 55 required tests passed`，其中 P4 直接场景为 28 个。
源码静态 `@Test` 计数为全仓 200，不是 CI 日志打印的执行数。artifact ID 为
`8721162398`，大小 `838883` bytes，SHA-256
`b36a69f607e4f0e028e2afff15946a03bddd64004638c2d64d479c704706ddcd`。
客户端组合、独立专用服、保护模组矩阵和多 Bot soak 仍需专项验证。

当前 P5/P6 远端证据为
[Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094)：Java 21
`clean build`、Gradle `test`、161 项常规 GameTest 和 phase-one/phase-two 重启 GameTest
均通过；它是当前 P5A/P5C/P6（含 P5A 受限工具-主手-副手 native-menu GameTest 源、本地 ledger-first proposal review、P5C-S1 固定副手盾牌和 P6-B1 mock payload-path GameTest 源）增量提交之前的基线，不能代替
这些提交待完成的 Java 21 CI。
真实进程崩溃/断电、死亡 handoff 的跨进程边界、Windows 或其他文件系统的目录刷盘、模组化
XP/掉落事件矩阵、独立专用服和多 Bot soak 仍需专项验证。

早期 P5 基线证据为
[Build #163](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30897970406)：Java 21
`clean build`、Gradle `test`、91/91 GameTest 和 JAR 上传均成功。源码静态计数为 437 个
JUnit `@Test` 方法、34 个 P5 GameTest 与 378 个 Java 源文件；这些不是 CI 日志逐项计数。
日志中的 40 个实际运行 batch 在默认 `server_player.maxBots=8` 下完成；Build #133/#135
暴露的超配仍通过拆批修复，没有提高上限。

P5 GameTest 使用 `P5GameTestSupport` 显式传入固定 Bot 名字；清理只卸载活动 Bot，
不会删除 roster/profile。这样同一持久 GameTest 世界连续运行时会复用同一身份与
playerdata，而不是用随机名字绕开恢复问题。真正的测试 profile 清理工具仍是测试债，
不得为测试向生产 roster 增加删除后门。会修改 `AUTO_RESPAWN` 或 `keepInventory` 的死亡
场景必须留在独立 batch，避免与普通 P5 场景并行污染全局状态。

## 每次提交前

```bash
git diff --check
./gradlew --no-daemon clean build
```

存在 GameTest，因此还要执行：

```bash
./gradlew --no-daemon runGameTestServer
```

同时人工检查：

- 没有生成文件、IDE 文件、世界存档或 secret；
- 没有意外修改用户的无关文件；
- 配置、命令、公开 API 和数据变化有文档；
- 当前状态没有把计划写成已完成；
- 新依赖已更新第三方说明；
- 安全边界变化已有 ADR。

## 提交与 PR

- 从最新默认 `main` 创建 `agent/<简短主题>`；
- 一次提交只覆盖一个可解释范围；
- 推荐提交摘要：`类型: 中文说明`；
- PR 正文写清变化、原因、用户影响、风险和验证；
- 大型或高风险变更先使用 Draft PR；
- CI 和所需测试全绿后才标记 ready；
- 不把“能编译”写成“功能已经在游戏内验证”。

常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`chore`。

## 文档同步

文档职责和事实优先级见 [文档总目录](README_CN.md)。改变以下内容时不得只改代码：

- 命令和权限；
- 配置键、默认值和位置；
- 当前可用能力；
- roster schema、owner 和 `serverInstanceId`；
- 客户端 credential profile、agent binding、文件路径和明文风险；
- 生命周期或 Mixin；
- 持久化 schema；
- AI provider、工具和 API Key；
- 新依赖或参考代码；
- 阶段完成状态。

## 关键资料

- [NeoForge 1.21.1 Getting Started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge 1.21.1 Events](https://docs.neoforged.net/docs/1.21.1/concepts/events)
- [NeoForge 1.21.1 GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
- [NeoForge 1.21.1 Debug Profiler](https://docs.neoforged.net/docs/1.21.1/misc/debugprofiler/)
- [NeoForge 1.21.1 Configuration](https://docs.neoforged.net/docs/1.21.1/misc/config)
- [P3 感知与世界模型调研设计](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md)
- [架构与路线图](ARCHITECTURE_AND_ROADMAP_CN.md)
- [贡献规范](../CONTRIBUTING.md)
