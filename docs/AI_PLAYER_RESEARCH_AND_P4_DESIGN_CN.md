# BotPlayer P4 导航与安全反射：调研、详细设计与验收计划

> 文档状态：已实施的设计基线；最终能力与证据以
> [P4 完成验收报告](P4_COMPLETION_REPORT_CN.md) 为准
> 调研日期：2026-07-29
> 代码基线：`main@3ee61ad`
> 适用版本：Minecraft Java 1.21.1、NeoForge 21.1.244、Java 21
> 前置阶段：P2、P3 自动化退出门已通过
> 实现结果：Build #97 已通过 55/55 GameTest，其中 P4 直接场景 28 个；本文中的计划项
> 不自动代表超出完成报告边界的能力已经验证

---

## 1. 结论摘要

P4 的正确目标不是“加入一个 A* 类”，而是建立两条能够互相抢占、又不会混成巨型状态机
的本地确定性控制闭环：

1. **导航闭环**：把同维度目标转换为分段路线，在真实 `BotServerPlayer` 身体上通过 P2
   原子动作到达；世界变化、实体堵塞、卡住、generation 轮换和服务器压力都能得到有界、
   可解释的处理。
2. **L0 安全闭环**：每 Tick 从服务端权威近场状态评估熔岩、火、坠落、溺水、窒息、
   弹射物、爆炸、敌对生物、低生命和饥饿风险；必要时关闭背包、抢占导航与普通动作，
   执行白名单避险，并在危险稳定解除后才恢复。

P4 采用以下核心方案：

- 使用**有界、分段 A\***，不在首版实现 D* Lite；
- 世界读取发生在服务器主线程，生成只含原始值的**不可变运动快照**；
- 路径搜索只读取不可变快照，可放到有界导航执行器；
- 长距离目标通过滚动局部窗口和 `PARTIAL_FRONTIER` 路段推进，不扫描或强制加载远处区块；
- 动态方块变化使受影响路线失效；短时实体阻挡先等待/局部绕行，持续阻挡才重算；
- 挖掘和搭桥默认禁止，只有请求策略与服务器上限同时允许时才可启用；
- 首版不在 A* 状态中模拟任意长的假想世界修改；受限挖掘/搭桥作为局部恢复动作执行，
  每次真实修改后重新取快照和规划；
- L0 不读取可能降频或过期的 P3 `ObservationSnapshot`，而使用独立、常量上限的
  `SafetyFrame`；
- 导航的内部运动网格不进入 bot 的语义世界知识、LLM 上下文或长期记忆；
- 怪物仇恨、运动饥饿消耗和饥饿伤害进入 P4 的真实 GameTest 基线；
- 原版/NeoForge 的伤害、护甲、附魔、吸收、状态效果和属性修饰继续作用于真实
  `ServerPlayer` 身体；标准注册表中的模组 `DamageType`/`MobEffect` 不能因为未知 ID
  被跳过；
- P4 只完成撤退、避险、停跑和补给阻塞，不完成食物获取、自动选食/进食、治疗物品或
  正式战斗；这些属于 P5A/P5B。

P4 完成后，bot 应该能够在没有 DeepSeek、没有 owner 在线、没有 API Key 的情况下安全地
移动和避险。它仍不会独立完成“砍树到铁工具”的生存任务，那是 P5A 的退出门。

---

## 2. 当前真实基线

### 2.1 已经可以复用的 P0–P3 能力

| 现有能力 | P4 复用方式 | 当前证据 |
|---|---|---|
| `BotServerPlayer extends ServerPlayer` | 所有移动继续走原版玩家物理，不传送 | `kernel/BotServerPlayer.java` |
| generation 与稳定 runtime handle | 路线、异步结果、危险 incident 全部绑定 generation | `lifecycle/BotLifecycleManager.java` |
| 有界动作运行时 | follower 和安全反射只提交受控原子动作 | `action/BotActionRuntime.java` |
| `MOVE/LOOK/...` 通道仲裁 | 导航使用 `AUTONOMOUS`，安全使用 `SURVIVAL/EMERGENCY` 抢占 | `action/ControlArbiter.java` |
| 玩家输入控制器 | follower 只租用输入，不直接写位置/速度 | `action/input/PlayerInputController.java` |
| 短程移动、跳跃、注视、停止 | 作为 P4 路段执行原语 | `MoveInputAction`、`JumpAction`、`LookAtAction`、`StopAction` |
| 原版交互、破坏、放置 | 门、受限挖掘和搭桥走 P2 世界交互后端 | `action/minecraft/*WorldInteraction*` |
| P3 自身与威胁观察 | 用于语义事实、诊断和后验记录，不作为 L0 唯一输入 | `perception/sensor/*` |
| 真实玩家伤害/效果规则 | 不覆盖 `hurt`、免疫、效果增删或属性计算；继续走原版身体与 NeoForge 事件 | `BotServerPlayer`、`BotPerceptionEvents` |
| 状态效果有限摘要 | 已按注册表 ID 读取 amplifier、duration 等字段；P4 增加真实生效与动态 ID 验收 | `SelfStateSensor` |
| P3 权威/认知双事件平面 | 发布经过脱敏的导航和安全事件 | `perception/event/*` |
| 背包会话 `DANGER` 关闭原因 | L0 抢占时关闭真人查看界面 | `inventory/InventoryCloseReason.java` |
| TPS 压力滞回 | 导航复用压力档位；L0 不降级 | `PerceptionLoadController`、`PerceptionPressure` |

### 2.2 当前缺口

现有 `MoveInputAction` 只是在固定 Tick 内施加固定前进/横移输入：

- 没有坐标目标；
- 没有局部网格；
- 没有 A*；
- 没有 waypoint follower；
- 没有动态重算；
- 没有门、梯子、脚手架的组合执行；
- 没有导航级 stuck 恢复；
- 没有每 Tick 安全反射；
- 没有怪物仇恨和实际饥饿闭环的专项验收；
- 没有伤害减免顺序、药水/状态效果 Tick、非原版注册表 ID 和模组标准事件链的专项验收。

P3 `NearbyThreatSensor` 在正常压力下默认每 3 Tick 运行，并受预算、视线、扫描和降级控制。
这适合“bot 知道附近有危险”的认知层，不适合保证下一 Tick 不走进熔岩或被即将命中的箭
射中。因此 P4 必须新增独立 L0 探针。

当前生命周期 Tick 的主链为：

```text
原版玩家/世界 Tick
→ Bot 无客户端连接阶段与玩家物理
→ 生命周期/重生处理
→ 背包会话复核
→ 动作运行时
→ P3 感知
```

P4 需要在背包会话复核与动作运行时之间加入安全和导航控制：

```text
原版玩家/世界 Tick
→ Bot 无客户端连接阶段与玩家物理
→ 生命周期/重生处理
→ 背包会话复核
→ L0 SafetyReflexService
→ NavigationService
→ BotActionRuntime
→ PerceptionService
```

这样 P4 在下一次玩家物理消费输入前有机会清零或改写危险输入，同时继续让原子动作运行时
负责唯一的通道租约、抢占和终态。

---

## 3. 调研范围与采用结论

### 3.1 原版与 NeoForge

| 资料/现有源码 | 关键结论 | P4 采用方式 |
|---|---|---|
| 当前 `BotServerPlayer` 与 P2 GameTest | bot 已走真实玩家 Tick、碰撞和物理 | follower 只生成输入和交互，不直接移动实体 |
| [NeoForge GameTest 1.21.1](https://docs.neoforged.net/docs/1.21.1/misc/gametest/) | 场景在真实世界 Tick 中执行，可按 Tick 注入变化和断言 | 动态封路、怪物仇恨、饥饿、危险抢占必须用 GameTest |
| [NeoForge 交互管线](https://docs.neoforged.net/docs/1.21.1/items/interactionpipeline) | 使用、放置和事件返回必须保留原版顺序 | 开门、挖掘和搭桥只调用 P2 动作后端 |
| [NeoForge 伤害类型与伤害源](https://docs.neoforged.net/docs/1.21.1/resources/server/damagetypes) | `DamageType` 是数据包动态注册表内容，类型、标签、来源实体和最终减免不能压成固定原版枚举 | P4 保存动态资源键并观察真实最终身体变化，未知类型采用保守策略 |
| [NeoForge 状态效果与药水](https://docs.neoforged.net/docs/1.21.1/items/mobeffects) | `MobEffect` 是注册表对象，`MobEffectInstance` 携带时长和等级，效果可每 Tick 或瞬时影响实体 | P4 验证效果作用于 bot；主动选药、喝药和解毒仍留给 P5 |
| 当前 `FoodData`、生命和威胁读取路径 | P3 已证明读取值与权威玩家身体一致 | P4 新增“真实消耗与反应”测试，不复制一套饥饿值 |
| 原版 Mob 目标字段与当前威胁传感器 | `mob.getTarget() == bot` 可以作为直接仇恨证据 | P4 必须生成真实敌对生物验证锁定和攻击 |

原版 `PathNavigation` 面向 `Mob` 身体、`GoalSelector` 和 NodeEvaluator 体系。BotPlayer 的
主体是 `ServerPlayer`，并且已经有自己的动作通道、generation、背包锁和玩家输入适配，
因此不能把 bot 改成 Mob，也不能把 Mob 导航直接当作玩家控制器。

### 3.2 Baritone

[Baritone 功能文档](https://github.com/cabaletta/baritone/blob/1.19.4/FEATURES.md)展示了几项
重要工程经验：

- 长距离路径分段计算，并在当前段结束前准备下一段；
- 到达已加载边界或搜索超时时，可以选择一条有进展的部分路径；
- 破坏、放置、掉落、梯子、门和危险都属于不同成本/移动类型；
- 危险方块、液体旁挖掘和下落方块需要额外限制；
- 目标与路径执行分离。

BotPlayer 采用“分段、部分结果、移动成本、目标与执行分离”的概念，但不复制其源码。
Baritone 使用 [LGPL-3.0](https://github.com/cabaletta/baritone/blob/1.19.4/LICENSE)，而本项目
核心是 MIT；ADR-0009 已决定不把其实现内嵌到核心。

### 3.3 Mineflayer Pathfinder

[Mineflayer Pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder)公开接口进一步
验证了以下边界：

- 静态、动态和组合目标应与 movement profile 分开；
- 每类移动可以有不同成本；
- 实体交叉、流体、破坏、放置和不可进入区域需要独立策略；
- 路径结果至少要区分 `success / partial / timeout / noPath`；
- block update、chunk load、goal moved、dig/place error、stuck 都是不同的重算原因。

本项目只借鉴公开语义，不复制 Node.js 源码。该项目是 MIT，但引入第三方代码仍需单独
固定 commit、保留版权并审查；P4 首版没有必要增加运行时依赖。

### 3.4 A* 与 D* Lite

- [A* 原始论文](https://ieeexplore.ieee.org/document/4082128/)给出启发式最小成本路径的
  形式基础；
- [D* Lite 原始论文](https://aaai.org/Papers/AAAI/2002/AAAI02-072.pdf)适合边成本变化后
  重用搜索结果。

P4 首版选择有界 A*，暂不选择 D* Lite，理由是：

1. P4 的局部窗口小，路径按段滚动，单次重算范围可控；
2. Minecraft 的边成本不仅随方块变化，还随生命、饥饿、工具、库存、权限和实体变化；
3. P4 需要先证明快照、玩家物理执行、失效和恢复正确；
4. D* Lite 会增加持久搜索状态、更新传播、generation 清理和调试复杂度；
5. 没有性能证据前引入增量算法属于过早优化。

若 P4/P10 的统计证明相同局部窗口频繁重算成为主要瓶颈，再基于同一 `RoutePlanner`
接口评估 D* Lite。首版接口不能把 A* 的内部 open/closed set 暴露给上层。

---

## 4. P4 范围冻结

### 4.1 必须实现

1. 同维度固定位置目标和半径目标；
2. 不强制加载区块的滚动局部导航；
3. 主线程不可变运动快照；
4. 有节点、时间、队列和并发上限的 A*；
5. 真实玩家输入 follower；
6. 平地、斜向、台阶、一格跳跃、楼梯和半砖；
7. 木门、栅栏门的正常交互；
8. 梯子、藤蔓和脚手架的保守上下移动；
9. 水面/浅水游泳与换气路线；
10. 动态方块失效和局部重算；
11. 短时实体阻挡、等待、绕行和持续阻挡重算；
12. stuck 检测、有限恢复和诚实失败；
13. 默认关闭、显式授权的受限挖掘与简单搭桥；
14. 熔岩、火、坠落、溺水、窒息、弹射物、爆炸和敌对生物 L0；
15. 生命、饥饿、装备对路径风险、疾跑和是否继续远行的影响；
16. 安全抢占、背包关闭、导航暂停、稳定解除后恢复；
17. generation、死亡、重生、换维度、卸载和停服清理；
18. 原版伤害源、护甲/附魔/吸收/抗性与有害、增益状态效果的真实继承基线；
19. 通过标准 `DamageSource`、注册表资源键、属性修饰和 NeoForge 玩家 Tick/伤害事件
    工作的模组伤害/效果兼容基线；
20. 压力降级、指标、诊断命令、单测和 GameTest。

### 4.2 明确不属于 P4

- 跨维度传送门规划；
- 船、矿车、马、鞘翅；
- 末影珍珠、水桶落地、梯子救落等高风险技巧；
- 1×1 垫高、跑酷跳跃和空中放置；
- 自动寻找、获取、选择或食用食物；
- 主动寻找、选择和使用药水、牛奶、治疗物品或模组解药，以及自动换甲；
- 主动近战、弓弩、盾牌战术；
- 死亡物品找回；
- 持久地点记忆和跨重启路线缓存；
- 完整探索、地标、返程与跨维度旅行；
- 模组自定义方块移动语义；
- 任意范围的挖山、拆墙或铺桥；
- DeepSeek、聊天、Goal DAG 和正式技能运行时；
- 强制区块票或远程扫描。

### 4.3 P4 与 P5A 的生存边界

| 场景 | P4 | P5A |
|---|---|---|
| 读取生命/饥饿/饱和度 | 权威读取并用于风险 | 继续复用 |
| 跑步造成饥饿消耗 | 真实 GameTest 验证 | 作为技能资源预算 |
| 食物低 | 禁止/取消疾跑，缩短路线，暂停远行，返回 `SUPPLY_REQUIRED` | 找食物、选食物、进食并验证 |
| 生命低 | 提高风险成本、撤退、避免掉落和敌人 | 治疗、装备和正式自卫策略 |
| 怪物锁定 bot | 验证原版仇恨；L0 撤退/脱离 | 反击、武器选择、击杀与拾取 |
| 已着火/进入水下 | 立刻执行白名单逃生 | 使用水桶、药水等物品策略 |
| 受到药水/状态效果 | 真实身体照常获得、Tick、叠加、刷新和移除；风险策略只读 | 选择牛奶、蜂蜜、药水或模组解药 |
| 受到原版/模组伤害 | 保留标准伤害事件与减免链；未知类型保守撤退 | 根据来源选择盾牌、装备、治疗和反击 |
| 获得正面 Buff/属性增益 | 身体真实生效；只使用可证明的权威值修正移动风险 | 主动获取、续期和组合 Buff |

P4 不能在没有食物时声称“已处理饥饿”，也不能把不断逃跑写成“会战斗”。

---

## 5. 新增架构不变量

1. **运动地图不是语义知识**：导航快照只能用于本地运动计算，不进入 P3 世界事实、LLM
   上下文或长期记忆。
2. **不强制加载**：快照只读取已经加载的区块；未知必须保留为 `UNKNOWN`。
3. **主线程采样**：`Level`、`BlockState`、`VoxelShape`、`Entity`、`ItemStack` 只在
   服务器主线程访问。
4. **异步只持有原始值**：导航执行器只接收不可变 primitive/ID/enum/packed array。
5. **异步结果必须复核**：返回主线程后复核 botId、generation、navigationId、
   snapshotId、目标 revision 和即将执行的路段。
6. **真实玩家动作**：follower 不调用 `setPos`、`teleportTo`、`setDeltaMovement` 或
   `setBlock` 完成普通导航。
7. **L0 永不降级**：服务器压力可以减少路径重算，但不能关闭安全探针。
8. **失败默认停止**：快照不完整、路径过期、控制权不明、危险解不出或预算耗尽时，
   先清零输入并停在可站立位置。
9. **玩家规则不分叉**：不得为 bot 自建生命、护甲、吸收、状态效果、属性或伤害结算；
   `BotServerPlayer` 不覆盖原版免疫/受伤/效果接口来获得特殊待遇。
10. **动态 ID 不枚举丢弃**：伤害类型、状态效果、属性和模组命名空间一律以注册表资源键
    表达；未知只影响策略理解，不影响它对真实身体生效。
11. **兼容观察不取消事件**：P4 只在逻辑服务端观察标准 NeoForge 事件和 Tick 前后状态；
    不取消、重发或人工重排第三方伤害/效果事件。
12. **世界修改默认关闭**：请求允许与服务器允许必须同时成立；每次修改都走原版动作并
   验证数量、耐久、掉落和方块结果。
13. **安全不是采集借口**：L0 不能借“逃生”执行普通挖矿、收集或大范围破坏。
14. **终态唯一**：导航 session 和安全 incident 都只能产生一个 canonical 终态。
15. **代际隔离**：旧 generation 的路径、规划 future、动作完成和危险状态不得进入新身体。

运动地图与 P3 有限认知的边界会长期影响架构，实施前应新增 ADR-0014，主题建议为：
“有界运动快照、分段路径与独立 L0 安全平面”。

---

## 6. 运行时总结构

```text
                 主线程
 BotServerPlayer ─────────────┐
                              v
                  NavigationSnapshotBuilder
                              |
                              | 不可变 DTO
                              v
                Bounded RoutePlanner Executor
                              |
                              | RoutePlan
                              v
 SafetyReflexService → NavigationService → PathFollower
          |                |                   |
          └────抢占────────┴────提交───────────┘
                              v
                     BotActionRuntime
                              v
                  原版玩家输入/交互/物理
                              v
                  结果、revision、重新规划
```

### 6.1 推荐包结构

```text
navigation/
  NavigationService.java
  NavigationRequest.java
  NavigationGoal.java
  NavigationPolicy.java
  NavigationState.java
  NavigationFailure.java
  NavigationOutcome.java
  NavigationCheckpoint.java
  NavigationSettings.java
  snapshot/
    NavigationSnapshotBuilder.java
    NavigationSnapshot.java
    TraversalCell.java
    StaticHazardMask.java
    SnapshotBuildCursor.java
  path/
    RoutePlanner.java
    BoundedAStarPlanner.java
    RoutePlan.java
    RoutePlanStatus.java
    RouteNode.java
    TraversalEdge.java
    TraversalKind.java
    MovementCostModel.java
  follow/
    PathFollower.java
    SteeringController.java
    TraversalExecutor.java
    StuckDetector.java
    ProgressWindow.java
  invalidation/
    NavigationInvalidationIndex.java
    DirtyRegion.java
  terrain/
    TerrainAssistController.java
    NavigationBreakPolicy.java
    NavigationPlacePolicy.java

safety/
  SafetyReflexService.java
  SafetyFrameBuilder.java
  SafetyFrame.java
  HazardEvaluator.java
  HazardAssessment.java
  HazardType.java
  HazardSeverity.java
  ReflexArbiter.java
  SafetyIncident.java
  SafetyState.java
  SafetyIntervention.java
  EmergencyEscapePlanner.java
  SafetySettings.java
```

不应为了目录完整提前创建未使用空类。以上是最终职责地图，按 P4 子阶段逐步落地。

---

## 7. 导航契约

### 7.1 请求

建议的稳定 DTO：

```java
record NavigationRequest(
    UUID navigationId,
    UUID botId,
    long botGeneration,
    NavigationGoal goal,
    NavigationPolicy policy,
    long deadlineTick,
    int maxDurationTicks,
    String idempotencyKey
) {}
```

`NavigationGoal` 首版只允许：

```text
ExactPositionGoal(dimension, x, y, z, horizontalTolerance, verticalTolerance)
NearPositionGoal(dimension, x, y, z, radius)
```

同维度是 P4 固定前置条件。移动实体跟随、组合目标、最近资源和跨维度目标留给 P5/P7，
但接口应使用 sealed goal，避免以后把所有参数塞进一个 nullable DTO。

### 7.2 请求策略

`NavigationPolicy` 至少包含：

- `allowSprint`；
- `allowSwim`；
- `allowClimb`；
- `allowOpenWoodenDoor`；
- `closeDoorAfterPass`；
- `allowBreak`；
- `maxBlocksBroken`；
- `allowPlace`；
- `maxBlocksPlaced`；
- `maximumSafeDrop`；
- `maximumExpectedDamage`；
- `avoidHostiles`；
- `avoidDeepWater`；
- `minimumFoodToContinue`；
- `minimumHealthToContinue`；
- 明确禁止区域集合；
- 允许使用的脚手材料 tag；
- 总路线距离、Tick 和重算上限。

请求策略只能缩小服务器配置上限，不能放大。例如服务端关闭导航挖掘时，
`allowBreak=true` 仍然必须拒绝。

### 7.3 状态机

导航 session 使用独立状态机：

```text
CREATED
  → SNAPSHOTTING
  → PLANNING
  → FOLLOWING
  → VERIFYING
  → SUCCEEDED
```

允许旁路：

```text
FOLLOWING → INTERACTING → FOLLOWING
FOLLOWING → REPLANNING → SNAPSHOTTING
FOLLOWING → RECOVERING → FOLLOWING/REPLANNING
任意活动状态 → SUSPENDED_BY_SAFETY → REPLANNING
任意活动状态 → CANCELLED/FAILED/STALE
```

约束：

- `SUSPENDED_BY_SAFETY` 不等于失败；危险稳定解除后必须重新验证目标和路线；
- `PLANNING` future 完成后不能直接改状态，只能向主线程结果 inbox 投递；
- generation 变化直接进入 `STALE`；
- 维度变化使 P4 同维度导航进入 `FAILED(DIMENSION_CHANGED)`，不能把旧坐标套到新维度；
- terminal 状态不可恢复；
- 每个 session 同时最多拥有一个快照构建、一个规划 future 和一个前台原子动作。

这不是在现有六层状态机之外再创建一个跨域巨型 FSM。导航 session 是 L2 本地控制器，
安全 incident 是 L0 反射状态；它们仍只通过 generation、通道租约、动作 outcome 和事件
与生命周期/动作/背包层协调。

### 7.4 结果

```java
record NavigationOutcome(
    UUID navigationId,
    NavigationState state,
    NavigationFailure failure,
    SpatialPoint finalPosition,
    long startedTick,
    long finishedTick,
    int segmentsCompleted,
    int replans,
    int recoveryAttempts,
    int blocksBroken,
    int blocksPlaced,
    long expandedNodes,
    List<EvidenceRef> evidence,
    String safeSummary
) {}
```

建议失败码：

```text
NONE
INVALID_REQUEST
BOT_NOT_ACTIVE
STALE_GENERATION
WRONG_DIMENSION
GOAL_OUT_OF_RANGE
SNAPSHOT_INCOMPLETE
UNLOADED_FRONTIER_TIMEOUT
NO_PATH
POLICY_BLOCKED
STUCK
ACTION_FAILED
DANGER_PREEMPTED
SUPPLY_REQUIRED
SERVER_OVERLOADED
BUDGET_EXHAUSTED
DEADLINE_EXCEEDED
CANCELLED
INTERNAL_ERROR
```

`NO_PATH` 只表示在完整覆盖相关局部问题、且没有未知边界的快照中确定无路；
“前方区块尚未加载”必须返回 `UNLOADED_FRONTIER_TIMEOUT` 或继续等待，不能伪装成无路。

### 7.5 动作来源追踪

当前 `ActionOrigin` 只有 `planId/skillRunId`。P4 不应把 `navigationId` 假装成 P5
`skillRunId`。推荐向后兼容增加：

```java
record ControllerOrigin(
    ControllerKind kind,
    UUID controllerRunId
) {}

enum ControllerKind {
    NAVIGATION,
    SAFETY
}
```

`ActionOrigin` 增加可选 `ControllerOrigin`，现有 `none/fromPlan/fromSkillRun` 工厂保持原
语义并补默认空值。这样每个 P4 原子动作能追溯到 navigation session 或 safety incident，
而不会提前伪造 P5 技能运行时已经存在。此 DTO 变更必须同步单元测试、幂等比较、事件和
诊断输出。

---

## 8. 不可变运动快照

### 8.1 与 P3 观察快照的区别

| 项目 | P3 `ObservationSnapshot` | P4 `NavigationSnapshot` |
|---|---|---|
| 用途 | bot 认知、世界事实、活动理解 | 内部运动可行性 |
| 范围 | 视线/听觉/局部传感器 | 有界局部走廊 |
| 是否受感知降级 | 是 | 构建预算降级，但不改变 L0 |
| 是否可进入 AI 上下文 | 可以，经过 AI-safe 限制 | 不可以 |
| 未加载区块 | 未知 | 未知，禁止搜索进入 |
| 内容 | 语义观察 | clearance、support、fluid、hazard 等运动原语 |

导航不应把墙后的矿物、容器内容或完整 BlockState 列表泄露给世界模型。

### 8.2 快照内容

`NavigationSnapshot` 建议包含：

- `snapshotId`；
- botId、generation、dimension；
- 构建开始/结束 Tick；
- 起点、目标投影与边界；
- loaded/unknown mask；
- packed `TraversalCell[]`；
- 静态 hazard bitset；
- 可开门位置；
- 可攀爬位置；
- 受保护/禁止位置；
- 构建时的导航 change sequence；
- 截断、预算耗尽和异常统计；
- 不含 `Level`、`BlockState`、`VoxelShape`、`Entity`、`ItemStack` 引用。

单元格主线程采样至少考虑：

- 以 0.6×1.8 玩家包围盒为基准的身体 clearance；
- 地面碰撞形状和有效站立高度；
- 半砖、楼梯、地毯、积雪等非整方块表面；
- 栅栏/墙等高于一格的碰撞；
- 水、熔岩和其他流体；
- 火、营火、岩浆块、仙人掌、甜浆果、细雪等静态危险；
- 木门、栅栏门、活板门等交互阻挡；
- 梯子、藤蔓、脚手架；
- 下落方块和液体相邻挖掘风险；
- 世界边界和构建高度。

首版不把任意模组方块猜成安全。没有标准碰撞/流体/标签语义时保守视为
`UNKNOWN_UNSAFE`，P8 再添加适配。

### 8.3 构建方式

- 每个 Tick 消耗固定 `snapshotCellsPerTick`；
- 使用 `SnapshotBuildCursor` 跨 Tick 构建；
- 构建期间 generation、维度或目标改变立即取消；
- 只读取当前已加载区块；
- 单个异常方块/第三方碰撞实现不能崩溃服务器，快照标记不完整并拒绝相关路径；
- 快照区域优先是“起点到局部 frontier 的走廊 + 绕行边距”，不是无条件立方体扫描；
- 同一 bot 同时最多一个快照构建；
- 全局有快照单元格预算，按 bot round-robin，避免一个 200 格目标饿死其他 bot。

建议首版默认值：

| 参数 | 候选默认 | 硬上限 |
|---|---:|---:|
| 水平局部半径 | 24 格 | 48 格 |
| 垂直半径 | 8 格 | 16 格 |
| 每 bot 每 Tick 单元格 | 2,048 | 8,192 |
| 全局每 Tick单元格 | 8,192 | 65,536 |
| 单快照最大单元格 | 65,536 | 262,144 |
| 快照最大构建时间 | 20 Tick | 100 Tick |

最终默认值应由 P4 GameTest 和性能记录校准，不能只凭直觉写死。

---

## 9. 路径搜索设计

### 9.1 节点与移动模式

路径节点至少是：

```text
RouteNode(x, y, z, locomotionMode)
```

`locomotionMode` 首版：

```text
GROUND
WATER
CLIMB
```

边类型：

```text
WALK_CARDINAL
WALK_DIAGONAL
STEP_UP
JUMP_UP_ONE
DROP_SAFE
SWIM_HORIZONTAL
SWIM_UP
SWIM_DOWN
CLIMB_UP
CLIMB_DOWN
OPEN_DOOR
WAIT_FOR_OBSTACLE
```

挖掘和放置不直接成为可任意组合的 A* 边；见第 12 节的 Terrain Assist。

### 9.2 A* 约束

- 使用 fixed-point `long` 成本，避免浮点排序在不同 JVM/平台漂移；
- open set 排序固定为 `f → h → g → packedNodeId`；
- 启发式只使用不高估的水平 octile/垂直最低移动成本；
- 危险、门、游泳等正惩罚不进入启发式；
- 对角移动必须验证两侧正交 clearance，禁止穿角；
- 所有加法使用饱和检查，成本溢出直接拒绝；
- 节点扩展、open set、parent 表、deadline 和取消均有上限；
- 同一输入快照、目标和 policy 必须产生相同 route/status/统计；
- 不按共享 CI 的绝对毫秒判断算法正确性，正确性使用节点上限；时间只作安全后备。

建议首版预算：

| 参数 | 候选默认 | 硬上限 |
|---|---:|---:|
| 单次节点扩展 | 50,000 | 250,000 |
| 单次规划 deadline | 50 ms | 500 ms |
| 全局进行中规划 | 2 | 8 |
| 等待队列 | 16 | 128 |
| 单导航最大重算 | 16 | 64 |
| 同维度目标最大距离 | 2,048 格 | 16,384 格 |

### 9.3 完整、部分与失败

`RoutePlanStatus`：

```text
COMPLETE
PARTIAL_FRONTIER
NO_PATH
BUDGET_EXHAUSTED
CANCELLED
STALE_SNAPSHOT
INVALID_SNAPSHOT
```

当目标在局部窗口外或路径到达已加载边界时，规划器可以返回
`PARTIAL_FRONTIER`，但必须满足：

- 比起点至少有最小进展；
- 终点是可稳定站立位置；
- 终点不在致命危险邻域；
- 不把未知单元当作空气；
- follower 到达前准备下一段；
- 若下一段未准备好，先安全停下，不能盲走进未知。

### 9.4 为什么不做全局地图

P4 还没有 P7 地点记忆和可靠跨重启地图。滚动局部路径能满足平地 200 格、动态重算和
一般障碍绕行，同时避免：

- 全世界扫描；
- 把导航地图泄露成 bot 全知；
- 持久化 schema 和迁移提前进入 P4；
- 区块缓存与真实世界长期漂移；
- 为未验证优化引入大量状态。

路线图中的“粗粒度区域路线”在 P4 被具体收敛为：**同维度长目标分解为滚动局部 frontier
路段**。它不是持久化区域图，也不记忆地标；真正的地点图、返程语义和跨重启区域关系进入
P7，跨维度交通进入 P5C/P7。

---

## 10. 成本模型

### 10.1 基础成本

建议使用 `1 格直行 = 1000` 的 fixed-point 标尺：

| 边/因素 | 候选成本 | 说明 |
|---|---:|---|
| 水平直行 | 1,000 | 基准 |
| 水平对角 | 1,414 | 近似 √2 |
| 台阶上行 | +250 | 不触发独立跳跃时 |
| 一格跳跃 | +700 | 包含时序和失败风险 |
| 安全下一格 | +100～1,500 | 按高度、生命和落地点 |
| 游泳 | +1,800/格 | 根据空气和出水距离继续增加 |
| 攀爬 | +1,400/格 | 梯子/藤蔓/脚手架 |
| 开门 | +750 | 包含交互与重新验证 |
| 短时实体占位 | +5,000 | 可等待或绕行 |
| 敌对实体威胁区 | +10,000～拒绝 | 按距离、目标状态和生命 |
| 静态致命危险 | 不可通行 | 熔岩、火焰主体、虚空等 |

这些值是测试起点，不是游戏规则。实际提交需在固定地图比较路线是否符合直觉，再冻结。

### 10.2 动态风险输入

成本至少读取：

- 当前/最大生命；
- 饥饿和饱和度；
- 空气；
- 盔甲与相关状态效果的权威摘要；
- 当前是否燃烧、在水中、下落；
- 已知静态危险；
- L0 当前 incident；
- 动态实体占位；
- 敌对生物是否正在 `target == bot`；
- 返回到稳定地面的距离。

禁止简单写成：

```text
总成本 = 距离 + 危险分数
```

必须把“绝对禁止”和“软惩罚”分开。已知会造成超过 policy 允许伤害的边直接拒绝，不能
因为路线短而抵消。

### 10.3 饥饿和生命策略

候选默认阈值：

| 状态 | 导航行为 |
|---|---|
| 食物 > 6 且 policy 允许 | 可在安全直线段疾跑 |
| 食物 ≤ 6 | 禁止新疾跑 |
| 食物 ≤ 4 | 普通远行暂停；短距离安全返回可继续 |
| 生命 ≤ 8 | 提高所有掉落、敌人和环境伤害成本 |
| 生命 ≤ 4 | 普通导航暂停，进入安全撤退/稳定站立 |
| 空气进入警戒区 | 只允许通向已验证换气位置的水下路径 |

阈值必须受配置约束，并在每 Tick 使用真实权威值复核。P4 不自行重写原版饥饿伤害、
自然恢复或难度规则。

装备在 P4 只是只读风险输入：

- 已穿盔甲可以影响敌对/爆炸风险估计；
- 已穿靴子的相关原版减伤只有在能用 1.21.1 权威规则可靠计算时才计入预计坠落伤害；
- 无法证明的减伤按不存在处理；
- P4 不自动换甲、持盾、消耗耐久或移动副手；
- 主动进食、基础装备和撤退/有限自卫属于 P5A；药水、牛奶和治疗物品属于 P5B；
  盾牌、远程武器与高级战斗属于 P5C。

### 10.4 状态效果、属性与未知模组 Buff

P4 只读取身体已经结算后的权威状态，不重新实现效果公式。`SafetyFrame` 使用有界、
不可变摘要：

```text
effectId
category(BENEFICIAL/HARMFUL/NEUTRAL/UNKNOWN)
amplifier
remainingDuration
ambient/visible
movementSpeed
armor/armorToughness
knockbackResistance
maxHealth/currentHealth/absorption
```

规则：

- 原版与模组效果都按动态资源键记录，不能用只含原版常量的 `switch` 过滤；
- `speed/slowness/levitation/jump_boost` 等只有在最终权威属性或身体状态可验证时才修正
  follower；不能仅凭效果名字猜公式；
- `regeneration/resistance/fire_resistance/absorption/water_breathing` 可以降低对应风险，
  但每 Tick 必须复核仍存在且真实属性/免疫仍有效；
- `poison/wither/hunger` 或未知有害效果触发持续伤害/资源风险；P4 停止普通远行并寻找
  安全站立点，但不擅自喝奶、药水或模组解药；
- 未知 `BENEFICIAL` 只记录为正面效果，不据此放宽致命路径；
- 未知 `HARMFUL` 或无法分类且伴随生命/吸收下降的效果按保守 `ONGOING_DAMAGE` 处理；
- 效果集合仍使用固定数量上限；发生截断时不得宣称“没有其他有害效果”；
- 最大生命、速度等属性改变后，阈值按新权威值计算，不能继续使用应用 Buff 前的缓存；
- 效果自然到期、被移除、死亡重生、换维度或第三方清除时，下一 Tick 快照必须反映真实
  结果，旧 generation 不保留效果推断。

P4 的兼容目标是“先正确承受和观察，再做通用保守反应”。理解某模组效果的专用解法、
物品联动和主动续期属于 P8 的声明式适配器与 P5/P10 的技能硬化。

---

## 11. PathFollower 与真实玩家执行

### 11.1 follower 职责

`PathFollower` 不改变世界，只负责：

1. 验证 session/generation/route；
2. 选择下一 traversal edge；
3. 重新验证前方固定数量节点；
4. 计算注视与输入；
5. 提交 P2 原子动作；
6. 接收 `ActionOutcome`；
7. 根据真实位置、速度、接地/流体/攀爬状态判断进展；
8. 成功推进、触发交互、恢复或重算。

动作完成回调只向有界 outcome inbox 投递不可变结果，导航状态只能在服务器主线程更新。

### 11.2 steering

- waypoint 目标使用单元格中心和真实站立高度；
- 角度差先平滑注视，再给相对前进/横移；
- 输入按 2～5 Tick 短租约提交，不用一个 200 Tick 固定方向动作；
- 每段都设置最大位移和 stuck 窗口；
- 直线、安全、食物足够时才疾跑；
- 临近悬崖、门、梯子、水边、转弯和 waypoint 时关闭疾跑；
- 取消、抢占、路径过期和异常必须清零输入；
- 到达判定使用水平/垂直容差、速度、碰撞与可站立状态，不只比较 `BlockPos`。

### 11.3 动作原语缺口

P2 当前 `MoveInputAction` 不支持持续 jump 输入，`JumpAction` 只接受地面或水中起跳。
梯子、藤蔓和脚手架需要 P4 增加一个窄的攀爬输入原语，或在不破坏 P2 语义的前提下扩展
输入动作。推荐：

```text
ClimbInputAction(forward, strafe, ascend, descend, ticks)
```

它必须：

- 只在原版判定可攀爬/脚手架时开始；
- 仍通过 `PlayerInputController`；
- 不直接设置 Y 速度；
- 从真实 Y 位移、攀爬状态和终端输入清零验证；
- generation、取消和抢占语义与现有移动动作一致。

### 11.4 门

- 只把可正常交互的木门/栅栏门视为可开；
- 铁门默认不可通过，除非后续技能有合法红石操作；
- follower 到交互距离后停止、注视、`use_on_block`、验证 open BlockState，再通过；
- `closeDoorAfterPass` 只在回身可见且不会夹住实体时执行；
- 门被玩家拆除、替换或锁住时重新取快照；
- 活板门首版只支持作为平面通道、且固定 GameTest 通过的保守姿态；复杂爬洞可延期，
  不能把“门”测试代替所有活板门姿态。

### 11.5 水域

- 优先水面或有明确换气点的路线；
- 空气不足时 L0 接管向上/最近空气位置；
- 不把流动水和静水完全等价；
- 不允许未知深度水域在低生命/低食物下成为捷径；
- 不能用 `setSwimming(true)` 伪造游泳，继续沿用原版状态派生。

---

## 12. Terrain Assist：受限挖掘与搭桥

### 12.1 为什么不直接放进 A* 世界状态

如果每个 A* 节点都携带“假设已经挖掉/放置的方块集合”，状态空间会从位置扩展为
位置 × 世界修改组合，首版很容易出现指数增长、权限漂移和验证困难。

P4 采用局部恢复：

1. 纯当前世界路径失败或卡在局部 frontier；
2. `TerrainAssistController` 在主线程检查紧邻阻挡；
3. 请求 policy、服务器 policy 和剩余数量预算同时允许；
4. 只提出一个小型 assist episode；
5. 通过 P2 原子动作真实执行；
6. 验证世界、库存、工具和事件结果；
7. 立即使旧路线失效并重新规划。

### 12.2 挖掘

默认 `allowBreak=false`。即使允许，也禁止：

- 带 `BlockEntity` 的方块；
- 不可破坏方块；
- 门、箱子、工作站、传送门、刷怪笼等敏感类别；
- 与熔岩/水相邻且破坏可能放出流体的方块；
- 上方有下落方块且会压住 bot；
- 保护/领地事件拒绝的方块；
- 超出局部一人通道所需的方块；
- 未知模组方块。

首版一次 assist episode 最多清理 2 个身体 clearance 方块，单导航默认最多 4 个，硬上限
8 个。所有掉落、耐久、工具速度和中止继续由 P2/原版处理。

### 12.3 搭桥

默认 `allowPlace=false`。首版简单桥：

- 一格宽；
- 最多连续 4 格，硬上限 8 格；
- 两端都有已验证稳定站立面；
- 不允许垫高、空中跑酷放置或向未知区块延伸；
- 不跨已知熔岩/虚空，除非未来更高风险策略单独定义；P4 默认拒绝；
- 只使用服务器 tag 允许的普通脚手材料；
- 每放一格都验证方块状态、库存减量和保护事件；
- 任意一步失败立刻停止并重算，不能重复消耗。

---

## 13. 动态重算与 stuck 恢复

### 13.1 方块失效

新增 `NavigationInvalidationIndex`，由已经确认 `COMMITTED` 的方块变化、区块加载/卸载和
相关门状态变化喂入。它是内部运动失效索引，不进入 bot 认知。

路径只检查：

- 下一小段 corridor；
- 自快照 change sequence 之后的 dirty regions；
- 是否发生 ring overflow。

若 overflow 或无法证明未受影响，fail closed：清零输入并重新取快照。

### 13.2 实体阻挡

实体不写入静态快照。主线程每 Tick 在前方短 corridor 构建有界 overlay：

- 玩家、村民、动物等短时阻挡：停下并等待少量 Tick；
- 持续阻挡：局部绕行/重算；
- 掉落物、小实体或明确可穿过实体：只增加软成本；
- 敌对实体：由威胁 overlay 与 L0 决定绕行或撤退；
- 不推挤 owner 或用攻击清路。

### 13.3 stuck 判定

不能只检查“位置没变”。`StuckDetector` 使用滚动窗口：

- 到当前 waypoint 的距离变化；
- 实际水平/垂直位移；
- 速度；
- 碰撞；
- onGround/inWater/onClimbable；
- 最近输入；
- 当前动作是否本来应该等待；
- 是否重复经过相同节点；
- 路线是否来回切换；
- 动态实体是否占位。

建议候选：

- 40 Tick 窗口；
- 最小有效进展 0.15 格；
- 正常 waypoint 最长无进展 60 Tick；
- 水/梯子使用独立阈值；
- 服务器低 TPS 时按实际 Tick 而不是墙钟时间。

### 13.4 恢复阶梯

```text
清零输入并重新对准
→ 短后退/侧移
→ 等待短时实体
→ 重建近场快照并局部重算
→ policy 允许时执行一个 Terrain Assist
→ 回退到最后稳定 waypoint
→ STUCK 或 NO_PATH
```

每级最多固定次数，总恢复默认最多 5 次。相同位置、相同失败、相同恢复动作连续出现时触发
循环检测，不能无限左右横跳。

---

## 14. L0 安全反射

### 14.1 独立 SafetyFrame

L0 每 Tick 生成常量上限的 `SafetyFrame`，只包含当前身体与近场逃生需要的信息：

- 位置、速度、朝向、包围盒、接地、fallDistance；
- 生命、最大生命、护甲摘要；
- 吸收生命、最终移动/护甲/韧性/击退抗性属性；
- 食物、饱和度、空气；
- 燃烧、水下、流体、窒息、冰冻等权威状态；
- 有界状态效果摘要、效果截断标志、最近真实生命/吸收下降；
- 最近伤害类型动态资源键、direct/cause 实体是否已知以及标准伤害标签摘要；
- 当前和下一步附近的支撑、碰撞、流体与危险；
- 固定半径内、固定数量上限的实体；
- 来袭弹射物相对位置/速度；
- TNT/爆炸倒计时与近场敌对目标；
- 当前动作、导航、输入 owner 和背包会话摘要；
- 不含任意远处世界扫描。

`SafetyFrame` 不是 P3 快照，也不受 P3 `DEGRADED/CRITICAL` 采样间隔影响。

实体读取必须使用可提前 `ABORT` 的有界空间索引迭代，不能先获取区域内完整实体列表再
截断。除命中实体上限外还要有 raw scan 上限；任意截断都标记 `coverageIncomplete`。
在覆盖不完整且已存在危险信号时，L0 采取保守 STOP/保持位置，不得据此宣称“附近安全”。

### 14.2 危险类型与等级

`HazardType`：

```text
UNSAFE_NEXT_STEP
FALL_IMMINENT
VOID_EXPOSURE
LAVA_CONTACT
FIRE_CONTACT
DROWNING
SUFFOCATING
FREEZING
PROJECTILE_IMPACT
EXPLOSION_IMMINENT
HOSTILE_TARGETING
ONGOING_DAMAGE
HARMFUL_EFFECT
UNKNOWN_DAMAGE
HEALTH_CRITICAL
FOOD_CRITICAL
```

`HazardSeverity`：

```text
ADVISORY
WARNING
URGENT
EMERGENCY
```

排序不能只按单一浮点分数。建议确定性顺序：

```text
预计致命性
→ 预计命中/伤害 Tick
→ 可逆性
→ 当前是否已受伤
→ 稳定 HazardType 顺序
```

### 14.3 安全状态机

```text
CLEAR
  → OBSERVING
  → INTERVENING
  → VERIFYING
  → COOLDOWN
  → CLEAR
```

异常分支：

```text
INTERVENING/VERIFYING
  → ESCALATING
  → INTERVENING
  → BLOCKED/FAILED
```

`SafetyIncident` 保存：

- incidentId；
- botId/generation；
- hazard type/severity；
- 首次/最后观察 Tick；
- 有界权威证据；
- 当前 intervention；
- 尝试次数；
- 是否关闭背包/抢占动作；
- 验证结果；
- clear-stable 计数；
- 不保存活动实体对象。

危险必须连续安全若干 Tick 后才解除，避免阈值附近反复抢占。

### 14.4 允许的 intervention

P4 白名单：

```text
STOP_AND_CLEAR_INPUT
CLOSE_INVENTORY
DISABLE_SPRINT
BACK_AWAY
SIDESTEP
MOVE_TO_SAFE_NEIGHBOR
SWIM_UP
MOVE_TO_NEAREST_AIR
MOVE_AWAY_FROM_EXPLOSION
DODGE_PROJECTILE
RETREAT_FROM_HOSTILE
HOLD_POSITION
SUSPEND_NAVIGATION
RESUME_WITH_REPLAN
```

不在 P4 白名单：

```text
ATTACK
PLACE_WATER
USE_POTION
EAT_FOOD
EQUIP_ARMOR
BREAK_ARBITRARY_BLOCK
TELEPORT
```

### 14.5 近场逃生规划

L0 不能等待普通异步 A* 才停止。`EmergencyEscapePlanner` 在固定小半径运动格上进行
严格上限搜索：

- 最多 256 个候选/扩展；
- 优先邻近安全站立格；
- 以最坏危险距离最大化为主，不追求普通路程最短；
- 不进入未知、无支撑或更高严重度危险；
- 失败时保持 STOP、报告 `BLOCKED`，不盲走。

长期撤退可在完成第一步脱险后交给 `NavigationService`，使用 `SURVIVAL` 优先级。

### 14.6 各危险反应

| 危险 | 首次反应 | 后续验证 |
|---|---|---|
| 前方悬崖/致命下落 | 清零前进、后退或蹲伏 | 未越过安全边界 |
| 已下落 | 不伪造空中控制；只允许有限横向安全修正 | 实际落点和伤害 |
| 火/熔岩 | 停止深入，寻找最近非危险邻格 | 不再接触危险且伤害趋势停止 |
| 溺水 | 向已验证空气位置；未知时优先向上 | 空气回升、头部离开水体 |
| 窒息 | 反向离开最近碰撞/回到上一稳定位置 | `isInWall`/碰撞解除 |
| 弹射物 | 预测最近交点，向安全垂直方向短侧移 | 弹体越过且未进入新危险 |
| TNT/爆炸 | 沿远离源且有支撑方向移动，必要时寻找遮挡 | 距离/遮挡改善 |
| 敌对生物锁定 | 暂停普通任务、撤退到安全距离 | `target != bot` 或距离/视线稳定解除 |
| 持续伤害/有害效果 | 停止耗血路线，移动到已验证安全点 | 生命/吸收下降停止或效果解除 |
| 未知模组伤害 | 停止普通任务；来源位置已知时远离，否则保守停稳 | 同类型伤害不再重复发生 |
| 低生命 | 停止高风险路线和追逐 | 到达安全站立点 |
| 低食物 | 禁止疾跑，普通远行阻塞 | 不继续消耗型路线 |

P4 不保证所有不可逃场景都存活。验收标准是：在存在合法逃生路径时采取正确行动；无解时
停止、留下真实失败证据，不能用传送或直接改状态作弊。

---

## 15. 安全抢占与跨系统协议

### 15.1 优先级

沿用现有动作优先级：

```text
AUTONOMOUS(导航)
< OWNER_TASK
< OWNER_CONTROL
< SURVIVAL
< LIFECYCLE_CLEANUP
< EMERGENCY
```

规则：

- `WARNING/URGENT` 通常使用 `SURVIVAL`；
- 即将致命的下落、溺水、爆炸、熔岩或弹射物使用 `EMERGENCY`；
- 生命周期清理仍可终止所有 P4 状态；
- 同优先级安全 incident 不互相抢占，由 `ReflexArbiter` 选择一个 canonical intervention。

### 15.2 背包

发生需要移动或可能受伤的危险时：

1. `forceCloseInventory(..., DANGER)`；
2. 提交高优先级 STOP；
3. viewer 菜单关闭完成前，禁止任何安全路径使用库存修改动作；
4. P4 本身不进食/换甲，所以普通逃生不等待库存解锁；
5. 关闭失败必须记录，但不能阻止清零移动输入。

这完成 P2 文档中“真正的 L0 危险触发在 P4 做集成验收”的延期项。

### 15.3 generation 与生命周期

- 死亡：导航 `STALE/CANCELLED`，安全 incident 终止，所有输入清零；
- 重生：新 generation 从 `CLEAR` 开始，不继承旧危险和旧路线；
- 换维度：P4 导航失败为 `WRONG_DIMENSION/DIMENSION_CHANGED`；
- 卸载/停服：取消 snapshot cursor、planner future、outcome inbox 和动作；
- 迟到异步结果：只计 `staleResultDropped`，不触碰新身体；
- 复活后的原目标由 P7/P5 检查点决定是否恢复，P4 不自行记住长期任务。

---

## 16. 真实玩家规则、模组兼容、仇恨与饥饿专项

### 16.1 “继承玩家规则”的可验收定义

Bot 是 `ServerPlayer` 子类不等于所有兼容性已经被证明。P4 必须把下列契约固定为运行期
证据：

1. **同一身体**：生命、最大生命、吸收、护甲、韧性、击退抗性、空气、饥饿、效果和属性
   都来自当前 `BotServerPlayer`，不建立 bot 专用镜像数值。
2. **同一 Tick**：效果持续时间、周期效果、自然恢复、伤害无敌帧、燃烧、冰冻和饥饿继续
   由原版 `ServerPlayer/LivingEntity` Tick 推进；标准 NeoForge 玩家 Tick 监听器仍能
   观察并作用于 bot。
3. **同一伤害链**：攻击、弹射物、爆炸、环境、魔法、饥饿和标准模组伤害继续进入原版
   `DamageSource` 与 NeoForge living/player 事件；BotPlayer 的观察器不得提前取消或重放。
4. **同一减免链**：游戏模式、难度、护甲、附魔、抗性、吸收、免疫、伤害标签和其他模组
   事件修改按平台最终顺序生效；P4 不计算第二套“估算后再扣血”。
5. **同一效果链**：药水、信标、食物、生物攻击、环境和模组通过标准接口施加的
   `MobEffectInstance` 应正常应用、合并、刷新、Tick、到期和移除。
6. **同一死亡结果**：任何来源把真实生命降至死亡条件后，继续走 P2 已验证的死亡、掉落、
   重生和 generation 轮换；不能因来源是未知模组 ID 绕开生命周期。
7. **注册表资源键兼容**：`minecraft:*` 之外的 `DamageType`、`MobEffect` 和属性 ID 不能因
   BotPlayer 不认识语义而被丢弃。未知只使策略保守，不使身体免疫。
8. **标准玩家扩展兼容**：模组通过玩家事件、属性修饰、装备/附魔或附件数据最终改变身体
   时，BotPlayer 不拦截、不清零，也不在重生时擅自复制第三方状态；实际持久/复制规则由
   原版、NeoForge 和该模组自己的事件处理决定。

P4 不承诺兼容蓄意绕过标准玩家 API、直接写字段、只接受精确
`player.getClass() == ServerPlayer.class`、依赖真实客户端私有网络握手，或主动排除特定
玩家 UUID 的模组。遇到这些情况必须记录为专项适配需求，不能用 Mixin 全局伪装。

### 16.2 伤害继承与观测

必须覆盖的来源族：

| 来源族 | 代表场景 | P4 必须保留的语义 |
|---|---|---|
| 实体近战 | 僵尸/玩家攻击 | causing entity、难度、护甲、无敌帧、仇恨 |
| 弹射物 | 骷髅箭/模组弹体 | direct 与 causing entity 分离、弹道危险 |
| 爆炸 | TNT/苦力怕/模组爆炸 | 爆炸标签、距离/遮挡、减伤 |
| 环境 | 火、熔岩、坠落、溺水、窒息、冰冻、仙人掌、虚空 | 没有攻击者时仍能形成自身危险 |
| 魔法/效果 | 瞬间伤害、毒、凋零 | 瞬时与周期变化都进入真实身体 |
| 资源规则 | 饥饿 | 难度和 gamerule 不被硬编码覆盖 |
| 动态模组类型 | 非 `minecraft` 命名空间 `DamageType` | 动态资源键保留；未知类型不免疫 |

观测分成两个互补证据，不允许互相替代：

```text
NeoForge/原版事件候选
  → 记录原因：damageTypeId、标签摘要、direct/cause、位置、Tick

Tick 前后权威身体差量
  → 记录结果：healthDelta、absorptionDelta、死亡、效果/属性变化

两者在同 Tick 有界关联
  → DamageObservation（原因可能 UNKNOWN，结果永远以身体为准）
```

约束：

- 不能把事件请求伤害量直接当成最终掉血；护甲、附魔、抗性、吸收、其他模组和无敌帧都
  可能改变结果；
- 不能只比较 `health`，因为伤害可能先消耗 absorption；
- 同 Tick 多次伤害候选要有固定上限和确定排序，溢出只标记 coverage，不合并出虚假来源；
- 直接攻击者、间接攻击者和动态 damage type ID 必须分开保存；
- 来源未被 P3 视觉/听觉感知时，公开认知只允许“我受到某类/未知伤害”，不能泄漏隐藏
  攻击者 UUID；
- 未知伤害首次发生时生成 `UNKNOWN_DAMAGE`；相同类型连续发生时合并为有界 incident，
  不每 Tick 刷新普通事件；
- 防火、抗性等效果让某次伤害为零时，应记录“规则成功阻止/修改伤害”，不能伪报受伤；
- 任何主动测试伤害注入只允许用于 GameTest fixture，生产安全逻辑不得直接调用
  `hurt` 来模拟危险。

### 16.3 药水、状态效果与属性 Buff

P4 必须验证 bot 作为玩家目标会真实受到：

- 瞬间伤害、瞬间治疗；
- 中毒、凋零、饥饿等持续负面效果；
- 速度、缓慢、跳跃提升、漂浮等运动相关效果；
- 再生、抗性、生命提升、吸收、抗火、水下呼吸等生存相关效果；
- 由装备、附魔、信标或模组标准属性修饰产生的最大生命、移动速度、护甲、韧性和击退
  抗性变化；
- 由标准 NeoForge 玩家 Tick 事件或附件状态驱动、并最终反映到权威身体/属性的 Buff；
- 非 `minecraft` 命名空间、但通过标准注册表和玩家接口施加的模组效果。

至少验收以下生命周期：

```text
无效果
→ 新增
→ 同效果低/高等级合并或拒绝
→ 时长刷新
→ 每 Tick 作用
→ 自然到期/主动移除
→ 死亡或重生后的原版结果
```

P4 的策略边界：

- 身体效果必须完整生效；
- P3/P4 必须能以有界动态 ID 摘要看到当前效果；
- follower 使用最终权威移动属性，不硬编码“速度 II 一定是多少”；
- L0 可因持续掉血、漂浮、行动变慢或水下呼吸即将到期调整风险；
- P4 不主动饮用、投掷、酿造、补充、清除或分享药水；
- P4 不根据名称猜测模组解药，也不自动喝奶清除可能有价值的正面效果。

### 16.4 模组伤害与 Buff 的兼容等级

P4/P8/P10 使用分级结论，不能用一次测试宣称“兼容所有模组”：

| 等级 | 定义 | P4 目标 |
|---|---|---|
| C0 身体继承 | 模组使用标准 `DamageSource`、`MobEffect`、属性、附件和玩家 Tick | 必须：真实生效，不崩溃 |
| C1 通用观察 | 动态 ID、最终身体变化和标准事件可被有界记录 | 必须：未知类型保守反应 |
| C2 语义适配 | 适配器声明危险类别、反制物品、环境来源和可叠加规则 | P8；P4 只提供接口边界 |
| C3 技能掌握 | 会主动获取、使用、续期、解除并验证效果 | P5/P8/P10 按能力逐项完成 |

P4 的模组测试 fixture 至少提供：

1. 一个非 `minecraft` 命名空间的测试 `DamageType`；
2. 一个标准注册的测试有害效果；
3. 一个标准注册的测试增益/属性效果；
4. 标准 NeoForge 事件监听器修改或取消一次伤害；
5. 一个玩家 Tick/附件驱动且最终改变权威属性的测试 Buff；
6. 只在 GameTest/dev source set 加载，不把测试内容当作正式玩法发布；
7. 分别断言 C0 与 C1，不能因为没有 C2 语义就判定身体兼容失败。

若当前 ModDevGradle 无法隔离 GameTest-only 注册内容，P4-0 先用独立最小兼容 fixture 模组
加入测试运行配置；不得把无意义的测试药水或伤害类型注册进正式 BotPlayer 内容来省事。

### 16.5 敌对生物仇恨

必须把“结构上应该生效”升级为直接证据：

1. 在非和平难度、夜间/无日光场景生成真实僵尸；
2. 只有 bot 在可见跟随范围；
3. 等待原版 AI；
4. 断言 `zombie.getTarget() == bot`；
5. 允许一次真实攻击，断言 bot 生命减少；
6. 断言 P4 生成 `HOSTILE_TARGETING` incident；
7. 断言普通导航被暂停/抢占；
8. 在可逃地图中断言 bot 拉开距离；
9. 危险解除后断言原导航以重算恢复。

补充骷髅/苦力怕锁定测试，至少覆盖：

- 近战目标；
- 远程弹射物目标；
- 爆炸接近目标。

GameTest 必须控制难度、日照和批次清理，不能依赖测试世界偶然状态。

### 16.6 真实饥饿

P4 至少验证：

1. 将 bot 饱和度置为 0、食物置为可观察起点；
2. 通过真实疾跑/跳跃/移动输入产生 exhaustion；
3. 断言权威食物值最终下降，而不是直接调用测试方法伪造；
4. 食物降到不能安全疾跑的阈值后，P4 不再提交 sprint；
5. 食物 0 时，在适用难度/规则下观察真实饥饿伤害；
6. `naturalRegeneration`、难度与游戏模式改变时不硬编码错误结论；
7. 没有食物时返回 `SUPPLY_REQUIRED`，不能声称已经进食。

P4 不新增 `eat_food`。P5A 将使用 P2 的选快捷栏/使用物品动作完成：

```text
识别可食用物
→ 价值/副作用/稀缺度选择
→ 选择快捷栏
→ 持续使用
→ 物品减少
→ FoodData/效果验证
```

---

## 17. 负载、线程与公平性

### 17.1 线程

| 线程 | 允许 | 禁止 |
|---|---|---|
| Server main | 快照构建、L0、状态提交、动作、实体 overlay | 阻塞等待 future、长 A* |
| Navigation executor | 纯 DTO A*、成本与部分路径 | `Level`、`Entity`、`BlockState`、`ItemStack` |
| Completion callback | 向有界 inbox 投递不可变结果 | 直接改导航状态 |

### 17.2 公平性

- 快照读取按 bot round-robin；
- 规划队列每 bot 最多一个待执行和一个进行中；
- 同 bot 新 revision 请求可以取消/合并旧规划；
- 安全 L0 不进入普通规划队列；
- 全局队列满时普通导航返回/保持 `SERVER_OVERLOADED`，不无限堆积；
- 停服等待导航执行器的时间有上限，之后中断并丢弃迟到结果。

### 17.3 压力档位

可复用 P3 `NORMAL / DEGRADED / CRITICAL`：

| 档位 | 导航 | L0 |
|---|---|---|
| NORMAL | 正常快照和预取下一段 | 每 Tick |
| DEGRADED | 减少快照单元格、只重算当前 bot 的必要段、关闭预取 | 每 Tick |
| CRITICAL | 不启动非必要新规划；走到稳定 waypoint 后暂停 | 每 Tick |

不能在 CRITICAL 下继续沿过期路径，也不能为了保 TPS 关闭防坠落。

---

## 18. 配置候选

建议在 SERVER 配置增加：

```text
[navigation]
localHorizontalRadius = 24
localVerticalRadius = 8
snapshotCellsPerBotPerTick = 2048
snapshotCellsGlobalPerTick = 8192
maxSnapshotCells = 65536
maxNodeExpansions = 50000
plannerDeadlineMillis = 50
maxInFlightPlans = 2
plannerQueueCapacity = 16
maxRouteDistance = 2048
replanCooldownTicks = 10
maxReplans = 16
stuckWindowTicks = 40
minimumProgressBlocks = 0.15
maxRecoveryAttempts = 5
allowTerrainBreak = false
allowTerrainPlace = false
maxBlocksBroken = 4
maxBlocksPlaced = 4

[safety]
enabled = true
entityRadius = 12
maximumEntityReadsPerBot = 96
maximumRawEntityScansPerBot = 256
projectileHorizonTicks = 12
clearStableTicks = 20
maximumInterventionsPerIncident = 6
criticalHealth = 4.0
retreatHealth = 8.0
minimumFoodForSprint = 7
minimumFoodForLongTravel = 5
```

要求：

- 每个值有合理上下界；
- 致命安全项不能由普通 owner 关闭，只能由服务器管理员配置；
- 请求 policy 只能收紧配置；
- 配置加载失败使用安全默认值；
- 变更同步更新配置文档、实现状态和开发文档；
- 性能参数不能宣传为所有服务器通用最优值。

---

## 19. 诊断与事件

### 19.1 管理命令

P4 需要测试入口，但不能伪装成 P6 自然语言能力。建议：

```text
/botplayer navigation go <bot> <x> <y> <z> [radius]
/botplayer navigation stop <bot>
/botplayer navigation inspect <bot>
/botplayer safety inspect <bot>
```

- 固定要求 OP 2 或精确持久 owner；
- `go` 默认不允许挖掘/放置；
- 高风险 policy 只通过管理员调试子命令显式启用；
- 输出有界，不打印完整网格；
- 命令是诊断/验收入口，不是 Goal/聊天系统。

### 19.2 指标

至少记录：

- 活动导航数；
- snapshot cells、构建 Tick、截断与异常；
- planner queue、in-flight、expanded nodes、status、deadline；
- 路段数、到达 Tick、重算原因；
- stuck、恢复级别和终态；
- 动态实体等待 Tick；
- 挖掘/放置数量与拒绝；
- L0 incident 类型、严重度、抢占、验证和失败；
- 真实伤害候选数、health/absorption 差量、动态 damage type ID 和未知类型次数；
- 当前效果数量、效果摘要截断、持续伤害 incident 和未知有害效果次数；
- stale generation 结果丢弃；
- 压力档位下的暂停次数；
- 强制背包关闭次数；
- 每 bot 有界最近诊断，不保留无限历史。

### 19.3 语义事件

建议进入权威事件平面并按 SELF/DIRECT 规则投影：

```text
NAVIGATION_STARTED
NAVIGATION_SEGMENT_REACHED
NAVIGATION_REPLANNED
NAVIGATION_BLOCKED
NAVIGATION_COMPLETED
SAFETY_INCIDENT_STARTED
SAFETY_INTERVENTION
SAFETY_INCIDENT_CLEARED
```

公开载荷只包含 bot 自己的状态、失败类别和有界测量，不包含完整运动网格、全服路径请求数
或 authority/global counters。

L0 可以在内部使用 `mob.getTarget() == bot` 保护身体，但只有 P3 已经视觉、听觉或直接
感知到该实体时，公开事件才可包含其精确 UUID/类型。否则只能发布“存在正在追踪本 bot 的
近场威胁”这类脱敏自身事实，防止安全平面成为绕过 P3 有限认知的侧门。

---

## 20. 实施阶段

### P4-0：设计冻结与原版基线测试

交付：

- [ ] 新增 ADR-0014；
- [ ] 固定导航、L0 与 P5A 边界；
- [ ] 先补怪物锁定、真实运动饥饿、伤害/效果继承和无真人在线区块推进基线；
- [ ] 冻结 `BotServerPlayer` 不覆盖伤害、免疫、效果或属性结算的契约；
- [ ] 建立只在 dev/GameTest 环境加载的动态 damage type/effect 兼容 fixture；
- [ ] 验证标准 NeoForge 伤害事件修改/取消对 bot 与真人遵循同一结果；
- [ ] 新建 P4 GameTest support，不复制 P2 大量等待/清理代码；
- [ ] 记录 `main` 既有测试基线；
- [ ] 建立 capability ID 到测试映射。

退出条件：

- 不改 P4 行为也能证明原版仇恨/饥饿哪些真实生效；
- 不改 P4 行为也能证明原版/标准模组伤害和状态效果作用于真实 bot 身体；
- 测试失败时能区分“身体内核问题”和“尚未有 P4 反应”。

### P4-A：L0 安全脊柱

交付：

- [ ] `SafetyFrame`、Hazard 类型和优先级；
- [ ] 有界 `DamageObservation` 与 health/absorption 差量关联；
- [ ] 动态效果/属性摘要和 coverage 标志；
- [ ] `SafetyIncident` 状态机；
- [ ] STOP、关闭背包、导航暂停；
- [ ] generation/死亡/重生/卸载清理；
- [ ] 生命/食物疾跑门槛；
- [ ] 持续伤害、有害效果与未知模组伤害的保守 STOP/撤退；
- [ ] 熔岩/火/坠落/水下/窒息基础检测；
- [ ] L0 不随压力降级的测试。

退出条件：

- 危险能抢占 P2 普通移动并清零输入；
- 背包以 `DANGER` 关闭；
- API/DeepSeek 完全不存在时仍工作。

### P4-B：快照与有界 A*

交付：

- [ ] 导航 DTO/状态机；
- [ ] 主线程运动快照；
- [ ] loaded/unknown 边界；
- [ ] 有界异步 A*；
- [ ] fixed-point 成本和确定性 tie-break；
- [ ] COMPLETE/PARTIAL/NO_PATH；
- [ ] 队列、公平性、取消和停服。

退出条件：

- 纯单测覆盖可达、不可达、部分、预算、取消、溢出和确定性；
- 异步线程不能访问 Minecraft 活动对象；
- 迟到 generation 结果被拒绝。

### P4-C：Follower 与普通移动

交付：

- [ ] 短租约 steering；
- [ ] 平地、转弯、斜向；
- [ ] 一格台阶/跳跃；
- [ ] 楼梯/半砖；
- [ ] 到达验证；
- [ ] 200 格分段路线；
- [ ] 下一段预取与安全停点。

退出条件：

- 全程无传送；
- 每个路段绑定真实动作 outcome；
- 200 格目标到达且输入终端清零。

### P4-D：特殊移动、动态重算与 stuck

交付：

- [ ] 木门/栅栏门；
- [ ] 梯子、藤蔓、脚手架；
- [ ] 水域和换气；
- [ ] 方块 dirty region；
- [ ] 实体 overlay；
- [ ] stuck detector 和恢复阶梯；
- [ ] 路线循环检测。

退出条件：

- 动态封路重算；
- 短时实体不导致永久失败；
- 无路时在上限内诚实结束。

### P4-E：Terrain Assist

交付：

- [ ] 双重 policy gate；
- [ ] 敏感方块/流体/下落方块拒绝；
- [ ] 最多 2 方块局部 clearance 挖掘；
- [ ] 最多 4 格简单桥；
- [ ] 每次世界变化后重新规划；
- [ ] 物品、掉落和耐久验证；
- [ ] 保护拒绝不伪装成功。

退出条件：

- 默认策略绝不修改世界；
- 显式允许场景数量守恒；
- 任何失败后无重复放置/挖掘。

### P4-F：完整安全反射与收口

交付：

- [ ] 弹射物预测；
- [ ] TNT/爆炸撤离；
- [ ] 敌对目标撤退；
- [ ] 安全小半径逃生规划；
- [ ] incident 滞回、冷却、升级和循环检测；
- [ ] 导航恢复；
- [ ] 压力降级和指标；
- [ ] 管理诊断；
- [ ] 文档、能力矩阵、CHANGELOG、完成报告。

退出条件：

- P4 全部自动化门通过；
- 未执行的客户端、专用服和 soak 项明确标记；
- 不把撤退写成战斗，不把饥饿判断写成会进食。

---

## 21. 测试计划

### 21.1 纯 Java 单元测试

#### 路径算法

- [ ] 直线、转弯、对角和垂直移动；
- [ ] 对角不穿角；
- [ ] 可采纳启发式不高估；
- [ ] 多条等价路线固定 tie-break；
- [ ] hazard hard deny 与 soft penalty；
- [ ] COMPLETE/PARTIAL/NO_PATH；
- [ ] 节点预算和 deadline；
- [ ] long 成本溢出；
- [ ] 取消；
- [ ] 相同回放确定性；
- [ ] 固定种子随机网格性质测试。

#### 快照

- [ ] loaded/unknown 区分；
- [ ] 不可变 defensive copy；
- [ ] 单元格和总容量上限；
- [ ] 构建 cursor 跨 Tick；
- [ ] generation/维度变化取消；
- [ ] 第三方碰撞异常 fail closed；
- [ ] 快照对象图不含 Minecraft 活动对象。

#### follower 与 stuck

- [ ] yaw/steering；
- [ ] waypoint 容差；
- [ ] 短租约终端清零；
- [ ] 正常等待不误判 stuck；
- [ ] 真正无进展触发；
- [ ] 恢复次数上限；
- [ ] 路线左右摆动循环；
- [ ] 安全暂停后恢复必须重算。

#### 安全

- [ ] 多危险确定性排序；
- [ ] 预计命中 Tick；
- [ ] projectile 最近交点；
- [ ] 食物/生命阈值；
- [ ] health 与 absorption 差量不重复计算；
- [ ] 多伤害候选关联、上限、截断和未知来源；
- [ ] 动态 effect ID、类别、等级、时长和集合截断；
- [ ] 运动/最大生命属性变化使缓存阈值失效；
- [ ] 未知有害效果不能放宽路线风险；
- [ ] clear-stable 滞回；
- [ ] intervention 次数上限；
- [ ] L0 在 CRITICAL 压力仍运行；
- [ ] 旧 generation incident 清除；
- [ ] 无安全邻格返回 BLOCKED。

#### 策略

- [ ] 请求不能放大服务器 policy；
- [ ] 默认禁止挖掘/放置；
- [ ] BlockEntity、流体、下落方块和未知模组拒绝；
- [ ] 数量预算；
- [ ] 无食物时 `SUPPLY_REQUIRED`；
- [ ] 不允许 L0 调用攻击、传送、进食或任意破坏。

### 21.2 NeoForge GameTest

建议直接固定以下场景：

| ID | 场景 | 必须断言 |
|---|---|---|
| P4-NAV-01 | 平地 200 格 | 分段到达、无传送、终端输入清零 |
| P4-NAV-02 | 一格跳跃、楼梯、半砖 | 真实碰撞和 Y 变化 |
| P4-NAV-03 | 木门/栅栏门 | 正常 use、状态变化、通过 |
| P4-NAV-04 | 梯子/脚手架 | 真实攀爬、无直接 Y 修改 |
| P4-NAV-05 | 浅水与换气 | 空气安全、到达 |
| P4-NAV-06 | 路中动态放墙 | 旧路线失效、重算绕行 |
| P4-NAV-07 | 实体短时堵路 | 先等待，移开后继续 |
| P4-NAV-08 | 悬崖和熔岩岔路 | 选择安全长路、无接触危险 |
| P4-NAV-09 | 明确允许简单桥 | 放置数量、库存守恒、到达 |
| P4-NAV-10 | 明确允许局部挖掘 | 工具/掉落/方块结果、重新规划 |
| P4-NAV-11 | 默认禁止世界修改 | 不挖、不放，返回 policy/no path |
| P4-NAV-12 | 完全封闭无路 | 有限时间 `NO_PATH` |
| P4-STUCK-01 | 可恢复卡住 | 恢复阶梯成功且次数有界 |
| P4-SAFE-01 | 危险时背包打开 | `DANGER` 关闭、普通动作被抢占 |
| P4-SAFE-02 | 火/熔岩可逃 | 离开危险、实际伤害不继续扩大 |
| P4-SAFE-03 | 水下/窒息可逃 | 恢复空气/解除碰撞 |
| P4-SAFE-04 | 悬崖前进输入 | L0 在越界前清零/后退 |
| P4-SAFE-05 | 来袭箭/TNT | 生成 incident，安全侧移/撤离 |
| P4-AGGRO-01 | 僵尸锁定并攻击 bot | `target == bot`、真实生命下降 |
| P4-AGGRO-02 | 锁定期间普通导航 | 安全撤退抢占，解除后重算 |
| P4-HUNGER-01 | 真实疾跑/跳跃 | exhaustion 导致 FoodData 下降 |
| P4-HUNGER-02 | 低食物导航 | 不提交 sprint、普通远行阻塞 |
| P4-HUNGER-03 | 食物为 0 | 适用难度下真实饥饿伤害 |
| P4-RULE-01 | 原版伤害与减免链 | 伤害、护甲/附魔/抗性/吸收和事件后的最终身体结果正确 |
| P4-POTION-01 | 喷溅瞬间伤害与持续药水 | 真实命中 bot、生命/效果变化、自然 Tick |
| P4-EFFECT-01 | 正负效果与属性 Buff | 最终速度/最大生命/护甲等权威属性变化，P3/P4 摘要一致 |
| P4-EFFECT-02 | 效果刷新、到期、移除和重生 | 生命周期符合原版，旧 generation 不残留 |
| P4-MOD-01 | 动态模组 DamageType 与事件修改/取消 | 非原版 ID 不丢失，最终伤害服从 NeoForge 链 |
| P4-MOD-02 | 动态模组有害/增益效果 | 身体真实生效；未知语义触发通用保守策略而不崩溃 |
| P4-MOD-03 | 玩家 Tick/附件驱动的属性 Buff | 监听器接收到 bot Tick，最终属性生效且移除后恢复 |
| P4-LIFE-01 | 路途中死亡重生 | 旧路线/incident/action 不串代 |
| P4-LOAD-01 | CRITICAL 压力 | 导航降级暂停，L0 仍抢占 |
| P4-OFFLINE-01 | 无 active AI binding | 导航与安全完全本地运行 |

测试纪律：

- 使用真实 P4 service 与 `BotServerPlayer`；
- 不直接调用 planner 后宣称完成导航；
- 断言世界位置、碰撞、生命、饥饿、物品和方块事实；
- 断言 health、absorption、属性、效果动态 ID、时长和最终事件结果；
- 断言无 teleport acknowledgement 或直接位置写入；
- 每个场景清理 bot、实体、难度/时间和世界改动；
- 同一持久 GameTest 世界至少连续运行两轮；
- 200 格场景若受结构模板范围限制，使用独立 batch/测试 lane，并保证不会污染并行测试。

### 21.3 客户端手工测试

- [ ] 第三人称观察 200 格自然移动；
- [ ] 动画、转头、疾跑、跳跃、游泳和攀爬自然；
- [ ] 背包危险关闭有明确提示；
- [ ] 资源包/GUI Scale 不影响安全关闭；
- [ ] 真人站路中时 bot 不推挤/攻击；
- [ ] 动态放方块后可见重算；
- [ ] bot 避开悬崖/熔岩行为不反复横跳；
- [ ] 导航诊断输出可理解且不刷屏。

### 21.4 独立专用服与性能

- [ ] 零真人在线，1 个 bot 跨多个区块前进；
- [ ] 只依赖正常玩家 chunk tracking，不添加强制票；
- [ ] bot 卸载后无 chunk、planner future 和输入残留；
- [ ] 8 bot 同时导航 10 分钟；
- [ ] 动态封路反复注入；
- [ ] 记录 MSPT、snapshot cells、expanded nodes、队列、GC 和内存；
- [ ] TPS 压力时导航降级而 L0 继续；
- [ ] 30 分钟长时间专项结果单独记录；未执行不能写成已验证。

P10 仍负责正式发布级长时间 soak；P4 至少要证明结构有界和基本多 bot 公平。

---

## 22. P4 自动化退出门

只有同时满足以下条件，才可以写“P4 自动化退出门已通过”：

1. Java 21、`-Xlint:all -Werror` 编译通过；
2. 全仓纯 Java 单测通过；
3. P4 固定单元测试全部通过；
4. 全仓 NeoForge GameTest 连续两轮全过；
5. 本文 P4 必选 GameTest 有明确日志和数量；
6. `clean build` 和 JAR 上传通过；
7. 无直接传送、`setBlock`、直接库存增减完成普通路线；
8. 异步线程测试证明不读取 Minecraft 活动对象；
9. 默认导航从未挖掘或放置；
10. 怪物仇恨、真实饥饿、伤害/效果继承、动态模组 ID 和安全抢占有直接运行期证据；
11. generation、取消、停服和 planner queue 无残留；
12. 文档准确区分自动化、客户端、专用服和 soak；
13. 能力矩阵只提升有直接证据的行；
14. 未实现的自动进食和战斗仍明确归 P5A。

建议 P4 完成后能力目标：

| 能力 | 最低目标成熟度 | 说明 |
|---|---|---|
| MOVE-02 行走/跑步/停止 | RELIABLE 候选 | 动态封路、取消和 stuck 恢复 |
| MOVE-03 边缘保护 | RELIABLE 候选 | L0 与真实悬崖场景 |
| MOVE-04 跳跃/台阶 | RELIABLE 候选 | follower 组合路径 |
| MOVE-05 游泳/换气 | BASIC～RELIABLE 候选 | 取决于水流与深水覆盖 |
| MOVE-06 攀爬 | BASIC 候选 | 首版只覆盖保守结构 |
| MOVE-07 门 | BASIC 候选 | 活板门复杂姿态可能未完整 |
| SURV-01 生命/饥饿判断 | RELIABLE 候选 | 增加真实消耗和低值决策 |
| SURV-03～05 | BASIC～RELIABLE 候选 | 只按有直接场景提升 |
| SURV-10 伤害/效果被动继承 | BASIC～RELIABLE 候选 | 原版与标准模组动态 ID、身体和事件链 |
| COMBAT-01 | BASIC 候选 | 威胁排序和仇恨证据，不含攻击 |
| COMBAT-07 | BASIC 候选 | 只有撤退/脱战，完整自卫在 P5A |
| PROG-01 | BASIC 候选 | 只有局部到达，无长期地标记忆 |
| LIFE-07 | BASIC 候选 | 需要零真人专用服区块证据 |

“候选”必须在完成报告中根据真实证据最终裁定，不能在规划阶段先改状态。

---

## 23. 主要风险与规避

| 风险 | 后果 | 规避 |
|---|---|---|
| 把 P3 快照当 L0 | 降级/过期时来不及避险 | 独立每 Tick `SafetyFrame` |
| 全局扫描世界 | 卡 Tick、全知泄漏 | loaded-only 局部走廊、总预算 |
| 异步访问 `Level` | 竞态、崩溃 | 主线程快照、DTO 对象图测试 |
| A* 状态包含任意修改集合 | 状态爆炸 | Terrain Assist 单次真实修改后重算 |
| 只看 BlockState 是否空气 | 半砖/楼梯/栅栏碰撞错误 | 主线程按玩家 AABB 和碰撞形状栅格化 |
| follower 长时间固定输入 | 绕圈、冲下悬崖 | 2～5 Tick 短租约、前方复核 |
| 动态实体当永久墙 | 路径抖动/误失败 | 短时等待，持续才重算 |
| 安全反射循环 | 左右横跳、饿死任务 | incident 滞回、次数上限、循环检测 |
| L0 借口破坏世界 | 拆家/越权 | 白名单 intervention，默认不破坏 |
| 怪物“理论会仇恨”未验证 | 生存结论虚假 | 真实目标和攻击 GameTest |
| `ServerPlayer` 子类被误当成天然兼容 | 模组伤害/Buff 静默失效 | C0/C1 fixture、动态 ID 与事件链 GameTest |
| 只按原版枚举识别伤害/效果 | 新模组内容被忽略或误判安全 | 注册表资源键、未知类型保守策略 |
| 把事件伤害量当最终掉血 | 护甲、吸收或其他模组结果重复计算 | 事件原因 + Tick 前后身体差量 |
| 未知 Buff 按名称猜行为 | 错误放宽路线导致死亡 | 只信最终属性/身体结果；语义留给 P8 |
| 低食物仍疾跑 | 饿死、路线成本错误 | 权威 FoodData 每 Tick门控 |
| 压力下降低 L0 | TPS 低时死亡 | 只暂停规划，L0 常量预算保留 |
| 旧 generation future 返回 | 新身体执行旧路线 | 四元组复核和迟到丢弃 |
| 过早实现 D* Lite | 复杂度高、难验收 | 先记录重算性能，P10 决策 |
| 直接复制 Baritone | 许可证/架构冲突 | clean-room，仅研究公开行为 |

---

## 24. 预计改动面

生产代码：

- 新增 `navigation/`、`safety/` 实际使用类；
- 在 `safety/` 增加有界伤害/效果观察 DTO；只观察标准事件与身体差量，不改结算；
- `BotLifecycleManager` 接入 Tick、generation 和停服；
- `BotPlayerConfig` 新增 P4 配置；
- `ActionOrigin` 增加导航/安全 trace，或新增兼容的 controller provenance；
- 必要时新增窄攀爬输入动作；
- `AuthorityEventCollector`/语义事件枚举增加导航与安全事件；
- `BotPlayerCommands` 增加有界诊断；
- `BotInventorySessionManager` 完成真实 `DANGER` 集成；
- 不新增 Mixin，除非实现证明原版公开入口无法满足；如需 Mixin，必须先新增单独 ADR。

测试：

- 新增路径、快照、follower、stuck、安全和策略单测；
- 新增 `P4NavigationAcceptanceGameTests`；
- 新增 `P4SafetyAcceptanceGameTests`；
- 新增伤害、药水、效果与动态模组 fixture 的兼容 GameTest；
- 抽取通用 GameTest 等待/清理 support，避免复制 P2/P3 大段代码；
- 新增必要场景结构或独立测试 lane。

文档：

- 本文；
- ADR-0014；
- `ARCHITECTURE_AND_ROADMAP_CN.md`；
- `IMPLEMENTATION_STATUS_CN.md`；
- `VANILLA_CAPABILITY_MATRIX_CN.md`；
- `DEVELOPMENT_CN.md`；
- `CONFIGURATION_CN.md`；
- `README.md`；
- `CHANGELOG.md`；
- `THIRD_PARTY_NOTICES.md`；
- P4 完成验收报告。

---

## 25. 推荐实施顺序

推荐严格按以下顺序开始 P4：

```text
先补怪物仇恨/真实饥饿/伤害效果继承/零真人区块基线
→ 接入 L0 STOP、背包关闭和 generation 清理
→ 建不可变快照与有界 A*
→ 做平地/台阶/200 格 follower
→ 做动态封路、实体和 stuck
→ 做门/攀爬/水域
→ 最后加入默认关闭的挖掘/搭桥
→ 补齐弹射物、爆炸、敌对撤退
→ 压力、诊断、两轮 GameTest 和文档收口
```

这样可以先证明“真实身体会饿、会被怪物锁定、会承受原版与标准模组伤害/Buff、危险可以
抢占”，再逐层增加导航复杂度。
Terrain Assist 最后实施，避免在基础 follower 不稳定时用挖墙/铺路掩盖寻路错误。

---

## 26. 研究与许可证边界

本设计参考：

- NeoForge 1.21.1 官方 GameTest 与交互文档；
- NeoForge 1.21.1 官方 DamageType/DamageSource 与 MobEffect/Potion 文档；
- 当前 BotPlayer P0–P3 源码和测试；
- Baritone 公开功能、使用和许可证说明；
- Mineflayer Pathfinder 公开 README/API；
- A* 与 D* Lite 原始论文。

本轮没有复制第三方源码、测试、资源、数据或提示模板，也没有新增运行时依赖。实际 P4
实现继续 clean-room 编写。若后续决定链接 Baritone、复制 Mineflayer Pathfinder 代码或
引入其他库，必须先重新审查许可证、固定版本/commit，并更新
`THIRD_PARTY_NOTICES.md` 与 ADR。
