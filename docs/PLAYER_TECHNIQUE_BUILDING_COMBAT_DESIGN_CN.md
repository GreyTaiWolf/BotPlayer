# BotPlayer 玩家技术动作、真实建造与战斗设计

> 文档状态：设计基线 v0.1；尚未实现，不计入能力成熟度
>
> 更新日期：2026-08-06
>
> 适用版本：Minecraft Java 1.21.1、NeoForge 21.1.x、Java 21
>
> 架构决定：[ADR-0017](adr/0017-bounded-player-technique-runtime.md)
>
> 关联阶段：P5A 有限自卫、P5C 高级战斗、P5D 建筑与红石、P7 检查点/长期记忆、P9 多 Bot

本文解决一个现有路线图尚未展开的关键问题：BotPlayer 已有真实玩家身体、原子 Action、有限
感知、导航、安全和 P5 Skill 底座，但“跳劈、冲刺击退、侧移攻击、渐进瞄准、蹲搭、垫柱、
临时脚手架、从真实站位逐块盖房”等玩家操作既不是一个 Action，也不应由 LLM 或长期 Skill
逐 Tick 操作。

本设计在 `Action` 与 `Skill` 之间增加有界 `Technique` 层，并分别冻结战斗和建筑的首批
纵切片、失败语义、性能预算和验收门。它是编码依据，不是当前功能清单。当前事实仍以
[IMPLEMENTATION_STATUS_CN.md](IMPLEMENTATION_STATUS_CN.md) 为准，能力成熟度仍以
[VANILLA_CAPABILITY_MATRIX_CN.md](VANILLA_CAPABILITY_MATRIX_CN.md) 为准。

---

## 1. 仓库现状审计

### 1.1 已有且必须复用的底座

当前 `main` 已具备以下可复用合同：

| 层 | 已有内容 | 本设计如何复用 |
|---|---|---|
| 玩家身体 | `BotServerPlayer extends ServerPlayer`、真实生命周期、generation | Technique 只持有 ID/generation，不缓存旧 body |
| 原子动作 | `BotActionRuntime`、`ActionEnvelope`、通道仲裁、deadline、cleanup、证据 | 所有移动、跳跃、攻击、放置继续提交 Action |
| 玩家输入 | `PlayerInputController`、`MoveInputAction`、`JumpAction`、`ClimbInputAction` | 不建立第二套 WASD 或直接速度修改器 |
| 世界交互 | `UseItem`、`UseOnBlock`、`BreakBlock`、`AttackEntity`、实体交互和拾取 | 战斗/建筑仍走普通玩家服务端入口 |
| 感知 | 有界 `ObservationSnapshot`、威胁、实体、方块、声音和语义事件 | Technique 只读当前目标相关的有限快照 |
| 世界模型 | 有来源、TTL、scope revision 的短期事实 | Skill/Plan 使用，不把隐藏世界变化注入 Technique |
| 导航 | 有界快照、分段 A*、真实输入 follower、Terrain Assist 双门控 | Technique 使用短距离站位请求，不复制路径规划器 |
| 安全 | 独立 L0 `SafetyService` 和抢占 | 任意战斗/建筑技巧都可被安全平面打断 |
| P5 Skill | descriptor/schema、局部 DAG、run identity、信号和资源预留基础 | Technique 由 Skill 启动并把结构化结果返回 Skill |

### 1.2 当前结构缺口

现有路线图 §10.3 将战斗分成威胁、战术、控制和验证四层，§10.4 定义了蓝图、材料、区域锁和
分层施工，但仍缺以下可编码合同：

1. **没有 Action 与 Skill 之间的短时编排层。** `AttackEntity` 只能表示一次攻击，
   `JumpAction` 只能表示一次真实跳跃；没有统一机制表达“接近 → 跳跃 → 下落时攻击 →
   落地恢复”。
2. **当前 `LookAtAction` 是一次立即转向。** 它适合 P2 原子验证和精确交互，但不等于有
   转速、预测、反应延迟和持续跟踪的真人式瞄准。
3. **没有战斗黑板和攻击窗口。** 缺少冷却、距离、目标速度、视线、逃生路线、附近敌人和
   当前 Technique 的统一局部状态。
4. **没有建筑放置候选求解器。** 蓝图位置不等于玩家能从某个站位、点击某个面、以某个
   命中点和姿态成功放置。
5. **没有施工依赖图和临时结构合同。** 地基、梁、楼板、墙、屋顶、脚手架、清理和逃生
   路线尚未形成可验证工作包。
6. **没有拟人表现与正确性边界的分离。** 反应延迟、转头速度和行为偏好需要可配置、可
   重放，但不能通过随机犯错实现“像真人”。
7. **缺少直接 GameTest 纵切。** 现有动作测试不证明跳劈成立，P4 导航测试也不证明 Bot
   能以真实站位盖完一座房子。

### 1.3 不新建重复系统

本设计明确不新增：

- 第二套移动器；
- 第二套攻击结算；
- 第二套背包；
- 绕过 Action 的方块写入；
- LLM 驱动的 Tick 脚本；
- 运行时下载技能或执行生成代码。

---

## 2. 目标与非目标

### 2.1 目标

1. 让短时玩家技巧成为统一、确定性、可取消、可验证的 FSM；
2. 让战斗 Skill 能组合普通近战、跳劈、冲刺击退、侧移、格挡、远程和撤退；
3. 让建筑 Skill 能从真实可达站位逐块施工，并正确处理方向、支撑、边缘、脚手架和错误修复；
4. 保留原版物理、伤害、物品消耗、保护事件和 NeoForge 兼容；
5. 让 AI 只负责目标、设计偏好和高层计划，不进入 20 TPS 身体闭环；
6. 所有失败都能局部恢复、上交重规划或诚实报告；
7. 每个纵切片都能由纯 Java 测试、GameTest 和长期 soak 分别证明。

### 2.2 非目标

- 不以冒充真人身份绕过服务器自动化规则；
- 不承诺第一版具备竞技 PVP 或速通级技巧；
- 不使用漏洞、取消机制、穿墙、复制或未定义行为；
- 不让模型直接返回原始 WASD、yaw/pitch、攻击 Tick 或几千个未经校验的方块操作；
- 不让普通 Bot 直接写任意 BlockEntity NBT；
- 不把一次成功演示写成完整战斗或建筑能力已经 `VERIFIED`。

---

## 3. 控制层级与术语

```text
Goal / Commitment
    ↓
TaskPlan：技能 DAG、预算、权限、承诺
    ↓
Skill：任务级执行与检查点
    ↓
Technique：数 Tick 到数百 Tick 的玩家操作技巧
    ↓
Action / Navigation / Menu：原子副作用
    ↓
BotServerPlayer / 原版与 NeoForge 权威结果
```

| 概念 | 典型持续时间 | 示例 | 是否跨重启 |
|---|---:|---|---|
| `Action` | 1～数十 Tick | 跳一下、攻击一次、使用方块、移动输入一段时间 | 否；只做 cleanup/终态回执 |
| `Technique` | 数 Tick～约 20 秒 | 跳劈、侧移攻击、蹲边放置、垫柱一段 | 否；中断后从安全边界重建 |
| `Skill` | 秒～分钟/更久 | 击败目标、施工一个工作包、取得材料 | 是；保存纯数据检查点 |
| `TaskPlan` | 分钟～任务周期 | 收集材料→建地基→建墙→屋顶→验收 | 是 |
| `Goal` | 会话或长期 | 给 owner 建房、守卫基地、维护食物 | P7 后是 |

Technique 是**实现层**，不会新增玩家可见能力 ID。它主要服务现有能力矩阵：

- `MOVE-01/02/03/04`；
- `ACT-01/03`；
- `COMBAT-02/03/04/05/06/07`；
- `BUILD-01/02/03/04/06/07`；
- `RULE-01/02/03/05`。

---

## 4. `TechniqueRuntime` 核心合同

### 4.1 目标包结构

```text
technique/
  core/
    TechniqueId.java
    TechniqueVersion.java
    TechniqueDescriptor.java
    TechniqueRiskLevel.java
    TechniqueParameters.java
    TechniqueFailureCode.java
    TechniqueOutcome.java
  runtime/
    TechniqueRuntime.java
    TechniqueRun.java
    TechniqueState.java
    TechniqueContext.java
    TechniqueLimits.java
    TechniqueChildTicket.java
    TechniqueSignal.java
    TechniqueVerifier.java
  snapshot/
    BodyTechniqueSnapshot.java
    TargetTechniqueSnapshot.java
    PlacementTechniqueSnapshot.java
  aim/
    AimController.java
    AimProfile.java
    AimTarget.java
    AimWindow.java
  combat/
  building/
```

### 4.2 统一接口草案

```java
interface PlayerTechnique<P extends TechniqueParameters> {
    TechniqueDescriptor descriptor();

    TechniquePrecondition check(
        TechniqueContext context,
        P parameters
    );

    TechniqueStep start(
        TechniqueContext context,
        P parameters
    );

    TechniqueStep tick(
        TechniqueContext context,
        TechniqueRun run
    );

    TechniqueStep cancel(
        TechniqueContext context,
        TechniqueRun run,
        TechniqueCancelReason reason
    );

    TechniqueVerification verify(
        TechniqueContext context,
        TechniqueRun run
    );
}
```

Technique 不接收活动 `Entity`、`Level` 或 `ItemStack`。`TechniqueContext` 提供：

- 当前 `botId + generation`；
- 当前服务器 Tick；
- 有界身体/目标/局部施工快照；
- `BotActionRuntime` 提交和查询接口；
- `NavigationService` 短距站位接口；
- L0 handoff 状态；
- Skill run、权限、风险和世界改动预算；
- 结构化诊断 sink。

### 4.3 FSM

```text
CREATED
→ PREPARING
→ RUNNING
↔ WAITING_ACTION
↔ WAITING_NAVIGATION
→ VERIFYING
→ SUCCEEDED
```

旁路：

```text
任意运行态 → RECOVERING → PREPARING/RUNNING
任意可取消态 → CANCELLING → CANCELLED
任意运行态 → PREEMPTING → PREEMPTED
任意运行态 → FAILED
```

Technique 必须记录：

```java
record TechniqueRun(
    UUID techniqueRunId,
    UUID skillRunId,
    UUID botId,
    long botGeneration,
    TechniqueId techniqueId,
    TechniqueVersion version,
    long startedTick,
    long deadlineTick,
    long revision,
    TechniqueState state,
    String phase,
    int submittedActions,
    int recoveryAttempts,
    List<TechniqueChildTicket> childTickets
) {}
```

### 4.4 并发与通道边界

玩家技巧经常需要同时维持不同通道，例如：

- `MOVE` 保持冲刺/侧移，同时 `MAIN_HAND + INTERACT` 攻击；
- `MOVE` 保持蹲姿，同时 `LOOK` 对准并 `MAIN_HAND + INTERACT` 放置；
- `LOOK` 跟踪目标，同时 `MAIN_HAND` 拉弓。

因此初始实现允许一个 Technique 同时持有少量**互不冲突**的子 Action：

- 每个 `ActionChannel` 最多一个活动 owner；
- 初始总并发 child ticket 硬上限为 `3`；
- 同一 Technique 不能同时拥有 Navigation follower 和冲突的手工 `MOVE` 输入；
- 新 Action 提交前必须确认所有目标通道可用；
- 任何 child 失败、过期或丢失 generation 都必须进入统一收口；
- Technique 终结前必须取得所有 child 的终态/cleanup 回执。

Technique 不另建通道仲裁器，最终所有权仍由现有 `ControlArbiter` 与 Action Runtime 决定。

### 4.5 初始预算

服务端配置可以收紧，但不能突破硬上限：

| 预算 | 初始默认 | 硬上限 |
|---|---:|---:|
| 单 Technique 总 Tick | 200 | 600 |
| 阶段数 | 16 | 32 |
| 提交 Action 数 | 32 | 128 |
| 同时活动 child | 2 | 3 |
| 局部恢复次数 | 2 | 4 |
| 目标/站位候选数 | 16 | 64 |
| 每 Tick Technique 状态推进 | 每 Bot 1 次 | 每 Bot 1 次 |

需要超过上限的行为必须拆成多个 Technique，由 Skill 管理工作包和检查点。

### 4.6 标准失败码

```text
ACTION_FAILED
ACTION_CLEANUP_UNSAFE
NAVIGATION_FAILED
TARGET_CHANGED
TARGET_GONE
TARGET_NOT_VISIBLE
POSE_INVALID
WINDOW_MISSED
OUT_OF_REACH
PLACEMENT_UNREACHABLE
PLACEMENT_STATE_MISMATCH
WORLD_CHANGED
MISSING_ITEM
PERMISSION_DENIED
SAFETY_PREEMPTED
GENERATION_CHANGED
TIMEOUT
BUDGET_EXCEEDED
UNSUPPORTED
```

恢复顺序：

```text
等待当前 child 安全收口
→ 重新采样局部状态
→ 同 Technique 有界重试/换候选
→ 返回 Skill 选择另一 Technique
→ Skill 重新导航/补给/重规划
→ 向玩家报告阻塞
```

### 4.7 不持久化半完成身体动作

服务器停止、死亡、换维度或 replacement 时：

- 不保存“正在半空”“正在转头”“准备挥剑”的 Technique 内部状态；
- 先收口所有 child Action、姿态、持续使用和临时背包布局；
- Skill 只保存安全工作包、目标 ID、蓝图 revision 和已验证结果；
- 重新上线后重新观察，再从一个新的 Technique 开始。

---

## 5. 渐进视角、姿态与攻击窗口

### 5.1 保留 `LookAtAction`，新增有界视角步进

当前 `LookAtAction` 适合作为“立即对准并验证”的原子动作，不应破坏其既有测试。新增目标
原语：

```text
RotateViewAction / ViewStepAction
```

它只允许在单 Tick 或小窗口内改变有限 yaw/pitch，并验证：

- 起始与结束角度有限；
- 单 Tick 变化不超过请求上限；
- generation 与 owner 未变化；
- 最终角度、视线和同步状态有效。

`AimController` 通过连续 ViewStep 形成自然跟踪，而不是一次瞬间锁头。

### 5.2 `AimProfile`

```java
record AimProfile(
    float maximumYawPerTick,
    float maximumPitchPerTick,
    float settleToleranceDegrees,
    int minimumReactionTicks,
    int maximumReactionTicks,
    float predictionSeconds,
    float preAimVariationDegrees
) {}
```

规则：

- 最终攻击/放置窗口内禁用会导致误点击的随机抖动；
- 预测只使用已感知目标的当前位置和速度，不读取隐藏未来；
- 目标失去视线时停止攻击，不通过全服实体索引继续锁定；
- 高风险施工和近悬崖场景优先精确性，不能为了拟人降低安全。

### 5.3 身体窗口

Technique 使用有界只读 DTO：

```java
record BodyTechniqueSnapshot(
    long tick,
    Vec3Value position,
    Vec3Value velocity,
    float yaw,
    float pitch,
    boolean onGround,
    boolean inWater,
    boolean climbing,
    boolean sprinting,
    boolean crouching,
    boolean usingItem,
    float health,
    int foodLevel,
    float attackStrength,
    Optional<String> activeUseItem,
    Optional<String> selectedItem
) {}
```

版本相关字段由 Minecraft 适配层读取，Technique 不复制原版公式。

---

## 6. 战斗系统详细规划

### 6.1 分层

```text
ThreatAssessment
→ CombatTacticSelector
→ CombatSkill
→ CombatTechnique
→ Action / Navigation
→ CombatVerifier
```

| 层 | 负责 |
|---|---|
| `ThreatAssessment` | 敌我关系、数量、伤害潜力、地形、友军、逃生和任务风险 |
| `CombatTacticSelector` | 选择近战、远程、格挡、风筝、守点或撤退 |
| `CombatSkill` | 任务目标、补给、追击边界、死亡/失败处理 |
| `CombatTechnique` | 瞄准、接近、跳跃、侧移、攻击时机和局部验证 |
| `CombatVerifier` | 权威伤害、目标死亡/脱离、友军存活和任务完成 |

### 6.2 `CombatBlackboard`

每 Tick 或按事件更新当前目标的有限局部状态：

```java
record CombatBlackboard(
    UUID targetEntityId,
    String targetType,
    long targetRevision,
    double distance,
    boolean visible,
    Vec3Value targetPosition,
    Vec3Value targetVelocity,
    float botHealth,
    float targetHealthEstimate,
    float attackStrength,
    boolean weaponReady,
    boolean shieldReady,
    int nearbyHostiles,
    Optional<RouteSummary> escapeRoute,
    Optional<HazardSummary> localHazard,
    CombatTactic currentTactic
) {}
```

`targetHealthEstimate` 只有实际可感知/获准读取时才存在；未知时不能伪造精确值。

### 6.3 首批战斗 Technique

| ID | 作用 | 阶段 |
|---|---|---|
| `combat.basic_melee` | 冷却、距离、视线、普通攻击和落地恢复 | PT2 / P5A |
| `combat.jump_critical` | 真实跳跃、跟踪下落窗口、普通玩家攻击 | PT2 / P5A→P5C |
| `combat.sprint_hit` | 有界冲刺接近并利用原版攻击结果 | PT2 / P5C |
| `combat.strafe_attack` | 侧移保持距离、攻击后换位 | PT2 / P5C |
| `combat.backstep_reengage` | 后撤等待冷却再接近 | PT2 / P5C |
| `combat.shield_hold` | 抬盾、维持、释放和反击窗口 | PT5 / P5C |
| `combat.bow_shot` | 装备、拉弓、提前量、释放和落点证据 | PT5 / P5C |
| `combat.break_line_of_sight` | 利用附近遮挡脱离远程攻击 | PT5 / P5C |

### 6.4 `basic_melee` FSM

```text
ACQUIRE_TARGET
→ VERIFY_HOSTILITY
→ EQUIP_WEAPON
→ ALIGN_VIEW
→ APPROACH_RANGE
→ WAIT_ATTACK_STRENGTH
→ ATTACK
→ VERIFY_HIT
→ RECOVER_DISTANCE
→ SUCCEEDED / CONTINUE
```

失败与恢复：

- 目标消失：`TARGET_GONE`；
- 目标离开追击边界：返回 Skill，不无限追击；
- 失去视线：短距重定位或换战术；
- 冷却未完成：保持安全距离，不连续空挥；
- 多敌人超过阈值：优先撤退或守门；
- 攻击被 PVP/保护/事件拒绝：`PERMISSION_DENIED`，不重复刷请求。

### 6.5 `jump_critical` FSM

```text
ASSESS
→ EQUIP_WEAPON
→ ALIGN_VIEW
→ APPROACH_LAUNCH_RANGE
→ ARM_SPRINT_OR_FORWARD_INPUT
→ START_JUMP
→ CONFIRM_AIRBORNE
→ TRACK_TARGET
→ WAIT_DESCENDING_WINDOW
→ ATTACK
→ VERIFY_DAMAGE_AND_CLASSIFICATION
→ LAND_OR_SAFE_RECOVERY
```

强制规则：

1. 不直接设置暴击标记或伤害倍率；
2. 跳跃必须由 `JumpAction` 与真实玩家物理产生；
3. 只有实际进入允许的身体/冷却/距离/视线窗口才攻击；
4. 目标移动后重新预测，超过局部修正上限就放弃本次挥击；
5. 附近出现悬崖、熔岩、爆炸或低血时，L0 可取消本次跳劈；
6. 攻击成功只由权威伤害和目标状态证明；
7. 只有服务端能够给出足够证据时才标为 `CRITICAL_CONFIRMED`，否则最多是
   `HIT_CONFIRMED`，不能根据伤害数值猜测。

### 6.6 战术选择

初始使用确定性规则和配置，不依赖 LLM：

```text
低血 / 无逃生余量 → RETREAT
爆炸型近身危险 → KEEP_DISTANCE / BREAK_LOS
单一普通近战敌人 → BASIC_MELEE / JUMP_CRITICAL
远程敌人且有安全接近路线 → CLOSE_DISTANCE
多目标超阈值 → CHOKE_POINT / RETREAT
目标不可识别 → DEFEND / RETREAT / REPORT
```

P6/P7 的 AI 可以解释目标、调整风险偏好或提出高层战术，但不能直接选择攻击 Tick。

### 6.7 战斗结果证据

至少记录：

- 攻击 actionId 与 target fingerprint；
- 攻击前后 Tick、距离、视线和 attack strength；
- 权威伤害事件 ID；
- 目标生命/存活状态变化（仅在允许观察时）；
- 武器、耐久、冷却和状态效果变化；
- 目标死亡、脱战、逃离或权限拒绝；
- L0 抢占和最终撤退结果。

---

## 7. 真实玩家建筑系统详细规划

### 7.1 总流水线

```text
玩家需求 / BuildingBrief
→ 设计参数校验
→ BlueprintCompiler
→ 选址与地形调查
→ 材料清单与物流计划
→ ConstructionGraph
→ 小型 WorkPackage
→ PlacementCandidateFinder
→ BuildingTechnique
→ 每块/每包验证
→ 临时结构清理
→ 最终差异验收
```

### 7.2 AI 与本地生成器的边界

AI 可以提出：

```json
{
  "type": "house",
  "footprint": {"width": 9, "length": 11},
  "floors": 2,
  "style": "oak_stone_cottage",
  "roof": "gable",
  "rooms": ["living", "storage", "bedroom"],
  "palette": [
    "minecraft:oak_log",
    "minecraft:oak_planks",
    "minecraft:cobblestone"
  ]
}
```

本地 Java 负责：

- 尺寸、方块和数量上限；
- 配色和方块可用性；
- 结构连通、门窗、楼梯和逃生；
- 施工依赖、支撑、旋转和镜像；
- 材料消耗、站位与玩家交互；
- 世界差异和最终验收。

第一版不接受 AI 直接发送无限方块数组。以后允许 `BlueprintProposal` 时也必须经过 schema、
体积、方块白名单、结构、风险和材料预算校验。

### 7.3 核心数据结构

```java
record BuildingBrief(
    UUID briefId,
    String type,
    int width,
    int length,
    int floors,
    String style,
    List<String> palette,
    List<String> rooms,
    BuildConstraints constraints
) {}

record Blueprint(
    UUID blueprintId,
    int schemaVersion,
    long revision,
    String hash,
    BoundingBoxValue bounds,
    List<BlueprintCell> cells,
    List<BlueprintModule> modules
) {}

record BlueprintCell(
    BlockOffset offset,
    BlockStateFingerprint expectedState,
    PlacementRole role,
    ReplacePolicy replacePolicy,
    boolean temporary,
    Optional<PostPlacementSemantic> postPlacement
) {}
```

普通 Blueprint 不携带可任意写入的 BlockEntity NBT。箱子命名、告示牌文本、容器填充、床、
门和机器配置由已注册的 `PostPlacementSemantic` 适配器通过真实菜单/交互完成。

### 7.4 选址调查

`BuildSiteSurvey` 至少评估：

- 地面高度差、支撑和挖填成本；
- 水、熔岩、洞穴、悬崖和窒息风险；
- 玩家建筑、保护区域和不可替换方块；
- 建筑入口与基地/道路的连通性；
- 周围敌对实体、光照和施工安全；
- 材料箱、工作站和运输距离；
- 建筑范围、脚手架范围与逃生缓冲区；
- 已加载边界；不为调查强制加载远处区块。

```java
record BuildSiteAssessment(
    BlockPosValue anchor,
    BoundingBoxValue constructionBounds,
    double terrainVariation,
    int clearCost,
    int foundationCost,
    int supportCost,
    List<SiteConflict> conflicts,
    List<HazardSummary> hazards,
    boolean accepted
) {}
```

当前 `P5D-A2` 在 `building/site/` 提供 `ConstructionSiteAnchor`、由 Blueprint 全部 cell 派生的
`ConstructionSiteBounds`，以及完整 WorkPlan 的 candidate binding；`P5D-A3` 只在该 exact binding 上
接受 caller-supplied 的 complete canonical target evidence，并产出 `BLOCKED`、`INCOMPLETE` 或
`ACCEPTED_CANDIDATE` 的 fail-closed 数据评估。A3 不读取世界；`UNKNOWN` 也不等于 chunk 未加载，
`ACCEPTED_CANDIDATE` 更不等于上述 `BuildSiteAssessment` 的 `accepted`、冲突/危险/加载状态、保护结论、
ownership proof、human confirmation 或区域 lease。这些仍是实际选址调查的后续前置，不能把
anchor/bounds/survey DTO 当作可施工许可。

### 7.5 材料清单与施工背包

```text
Blueprint
→ BillOfMaterials
→ 当前背包
→ 已授权仓库
→ 已预留材料
→ 缺口
→ 采集/制作/运输子计划
```

材料必须区分：

- 永久建筑材料；
- 临时脚手架/垫柱材料；
- 工具和照明；
- 易碎或有方向要求的特殊方块；
- 稀有材料和替代策略。

建筑 Skill 为一个 WorkPackage 准备稳定快捷栏布局，避免每放一块都全背包重排。背包变化
继续受 P5 menu 事务、物品守恒和真人 viewer 写锁约束。

当前 `P5D-A4` 先在 `building/material/` 固定输入边界：每一种完整 Blueprint target
`BlockStateFingerprint` 都必须由 caller 显式声明一个 item ID，随后才能按 item ID 和 permanent/temporary
类别导出 bounded declared quantity。它不从同名 blockId 猜 itemId，也不读取 registry、背包或容器。ADR-0042 的
`P5D-A4-R1` 再在 authoritative server thread 对这份已经完整的 declaration 作一个无状态 native registry
检查：每个显式 item 必须是 non-air `BlockItem`，且其 block default 的所有 serialized properties 必须与 target
fingerprint 精确相等；missing/non-block/partial/non-default state 都拒绝。它仍不调用真实 `useOn`，不能证明 item
可用、已预留或能在真实 interaction/context 中产出该状态；因此不是本节的 `BillOfMaterials` availability、材料背包、
reservation 或施工许可。

`P5D-A7` 只补一个更窄的 own-inventory observation：在 authoritative server thread，先复用 A4-R1，再要求
runtime handle 仍精确绑定传入 Bot body、active exact native `InventoryMenu`、46-slot shape 和 empty cursor；随后仅按
default-stack fingerprint 读取 main/hotbar `0..35`。相同 explicit item 的 permanent/temporary quantity 先聚合，输出
当前 tick 的 `AVAILABLE|SHORTAGE|UNAVAILABLE_MENU|UNAVAILABLE_REGISTRY`；unknown menu/registry 绝不伪造为零库存。
它不读取 chest/ender chest/世界容器，不移动、扣除或预留材料，也不接 site、placement、Technique、Skill 或 Action；因而
仍不是本节的 `BillOfMaterials` availability、reservation 或施工许可。

`P5D-A6` 现只为 construction area 增加一项更窄的 owner-thread 空间 lease：同一 exact site binding 的
32-block bounded bounds 保守映射为至多 8 个 TTL `WORK_AREA` tile，防止相交施工区并发；它不证明材料可用或
已预留，也不覆盖临时结构区，且没有任何 placement/Technique/Skill 接线。因此它不是本节的 material reservation
或真实施工许可。

### 7.6 施工依赖图

```text
清理与基准点
→ 地基
→ 承重柱/梁
→ 一层墙与开口
→ 楼板/楼梯
→ 上层结构
→ 屋顶骨架
→ 屋面
→ 门窗/照明
→ 内饰/工作站
→ 临时结构清理
→ 最终验收
```

`ConstructionGraph` 不是逐方块的无界全图调度器。编译后拆成有界 WorkPackage：

```java
record ConstructionWorkPackage(
    UUID packageId,
    UUID blueprintId,
    long blueprintRevision,
    String moduleId,
    BoundingBoxValue bounds,
    List<PlacementTask> tasks,
    Set<UUID> prerequisites,
    MaterialReservation reservation,
    WorkPackageState state
) {}
```

每包建议 16～64 个方块；超过硬上限继续拆分。Skill 只在包边界保存检查点。

### 7.7 放置候选求解

蓝图给出“目标位置和状态”，求解器必须回答“玩家从哪里、点击哪里、以什么姿态放”。

```java
record PlacementCandidate(
    BlockPosValue standingPosition,
    BlockPosValue supportPosition,
    DirectionValue clickedFace,
    Vec3Value hitLocation,
    float requiredYaw,
    float requiredPitch,
    boolean requiresCrouch,
    boolean requiresTemporarySupport,
    double navigationCost,
    double fallRisk,
    double escapeCost
) {}
```

候选生成和排序至少检查：

- 站位碰撞箱、脚下支撑和头部空间；
- 玩家交互距离和视线；
- 可点击支撑面是否仍存在；
- 放置后是否产生期望 BlockState；
- 是否会把 Bot 封住或切断唯一逃生路径；
- 是否需要蹲姿防止打开容器或从边缘跌落；
- 是否会覆盖真人修改或不可替换方块；
- 施工后下一任务是否仍可达；
- 临时支撑的放置、预留和最终清理成本。

候选优先级：

```text
当前安全站位
→ 同层短距可达站位
→ 合法脚手架站位
→ 有界垫柱/临时桥
→ 调整施工顺序
→ 返回 PLACEMENT_UNREACHABLE
```

### 7.8 首批建筑 Technique

| ID | 作用 | 关键验证 |
|---|---|---|
| `building.ground_place` | 从普通地面站位放一块 | 精确 BlockState、消耗、事件 |
| `building.crouch_edge_place` | 保持蹲姿在边缘放置 | 未跌落、姿态、命中面、状态 |
| `building.pillar_up` | 脚下逐格垫高到有界高度 | 每格支撑、材料、头部空间 |
| `building.pillar_down` | 安全拆除/离开临时柱 | 不坠落、不遗留不可接受方块 |
| `building.temporary_scaffold` | 建立短施工桥或平台 | 临时标记、预算、可清理性 |
| `building.overhead_place` | 放置头顶梁/屋顶单元 | 站位、视角、碰撞和状态 |
| `building.replace_wrong_block` | 经策略允许拆错块并重放 | 不覆盖真人修改、守恒 |
| `building.escape_enclosure` | 检测并避免/退出自封闭 | 逃生路径仍有效 |
| `building.cleanup_temporary` | 清理属于本工作包的临时结构 | 精确 ownership，不拆玩家方块 |

### 7.9 单块放置 FSM

```text
READ_TASK
→ VERIFY_BLUEPRINT_REVISION
→ VERIFY_MATERIAL
→ FIND_CANDIDATES
→ SELECT_SAFE_CANDIDATE
→ NAVIGATE_TO_STAND
→ ALIGN_VIEW
→ APPLY_REQUIRED_POSE
→ USE_ON_BLOCK
→ VERIFY_BLOCKSTATE_AND_CONSUMPTION
→ RELEASE_POSE
→ MARK_TASK_VERIFIED
```

如果实际 BlockState 与期望不同：

- 不立即无条件破坏；
- 先判断是方向/邻接变化、世界中途变化、保护拒绝还是适配器缺失；
- 只有 `ReplacePolicy`、风险和 ownership 允许时才调用修复 Technique；
- 真人在施工区修改的方块默认标为 `HUMAN_OVERRIDE` 并暂停相关分区。

### 7.10 施工差异状态

```text
CORRECT
MISSING
WRONG_BLOCK
WRONG_STATE
OBSTRUCTED
PROTECTED
HUMAN_OVERRIDE
UNKNOWN_UNLOADED
UNREACHABLE
TEMPORARY_REMAINS
```

最终完成必须同时满足：

- 所有必需 Cell 为 `CORRECT`；
- 没有未授权 `WRONG_BLOCK/WRONG_STATE`；
- 临时结构按策略清理；
- 材料与工具变化可解释；
- 入口、楼梯和逃生路径可用；
- 玩家 override 已确认、保留或通过新蓝图 revision 接受。

---

## 8. 拟人行为与技能水平

### 8.1 表现配置

```java
record TechniqueProfile(
    TechniqueSkillLevel skillLevel,
    CombatStyle combatStyle,
    BuildingStyle buildingStyle,
    float caution,
    float aggression,
    int reactionDelayMinTicks,
    int reactionDelayMaxTicks,
    float maximumYawPerTick,
    float maximumPitchPerTick,
    long deterministicSeedSalt
) {}
```

可改变：

- 反应延迟；
- 渐进转头速度；
- 侧移、后撤和攻击 Technique 的偏好；
- 施工时是否优先搭脚手架而不是垫柱；
- 检查蓝图和环顾环境的频率；
- 风险容忍和补给阈值。

不可改变：

- owner/ACL、PVP 和保护结果；
- L0 安全底线；
- 物品守恒和 BlockState 验证；
- 未感知事实；
- 最大破坏/放置/追击预算；
- API Key、工具白名单或模型权限。

### 8.2 确定性变化

行为变化使用：

```text
hash(botId, skillRunId, techniqueRunId, phase, profileSalt)
```

相同输入和种子必须可回放。随机只用于多个同等安全候选的排序、表现延迟或方向偏好，不用于
决定是否攻击友军、是否覆盖玩家方块或是否跳入危险。

### 8.3 自然动作示例

- 接近工地后先停下确认边界；
- 高处施工前主动蹲下；
- 夜间施工时把照明作为独立安全/工作包；
- 战斗后先确认生命、周围敌人和掉落，再恢复施工；
- 路径受阻时后退重新观察，而不是持续撞墙；
- 完成一层后检查差异并汇报，而不是最后一次性发现全部错误。

---

## 9. AI、Goal 与 Technique 的边界

### 9.1 AI 可以输出

- 自然语言意图；
- `BuildingBrief`；
- 高层战术偏好，如“优先保护玩家”“不要追出基地”；
- Skill DAG 草案；
- 失败摘要后的重新规划建议；
- 向玩家的澄清、进度和解释。

### 9.2 AI 不能输出

- 每 Tick WASD；
- 具体攻击 Tick；
- 未注册 Technique 的内部状态；
- 原始 `Entity`/`BlockEntity`/`ItemStack`；
- 直接伤害、方块写入、背包修改或命令；
- 无边界方块数组、脚本、Java/JS/Python；
- 伪造 `ActionOutcome`、伤害事件或蓝图验收结果。

### 9.3 推荐调用方式

模型只调用 Skill 级工具：

```text
plan_building
construct_blueprint
defeat_target
retreat_to_safe_position
report_progress
```

具体使用 `jump_critical` 还是 `basic_melee`、从哪个面放方块、是否蹲搭，由本地 Skill、
TacticSelector 和 PlacementCandidateFinder 决定。

---

## 10. 安全、权限与世界变化

### 10.1 战斗

- owner、队友、驯服实体和非敌对目标默认不可攻击；
- PVP 需要服务器与 owner 双重授权；
- 追击有距离、时间、维度和区域边界；
- 低生命、装备不足、目标过多或无逃生路线时撤退；
- 未知模组生物关系先防御、撤退或询问，不主动猜测；
- 战斗 Technique 不能在真人打开 Bot 背包时写背包。

### 10.2 建筑

- 施工区、临时结构区和材料必须有资源预留；
- 每个 WorkPackage 有最大放置、破坏和临时方块数量；
- 不可替换方块、玩家作品和保护拒绝默认 fail-closed；
- 只清理由当前蓝图/work package 创建并有证据标记的临时方块；
- Terrain Assist 的许可不能自动扩展成建筑拆除许可；
- 发现真人修改后暂停分区，不立即“修回蓝图”。

### 10.3 L0 抢占

```text
危险出现
→ Technique 停止提交新 child
→ child Action 清理/终结
→ 释放姿态、使用物品和临时所有权
→ Safety 执行
→ Skill 重新观察
→ 继续、换 Technique、暂停或失败
```

---

## 11. 性能与线程预算

1. Minecraft 对象只在服务器线程读取；
2. Technique 每 Bot 每 Tick 最多推进一次；
3. Aim/Combat 核心只处理当前目标和 O(1) 身体状态；
4. 放置候选扫描有半径、数量和方块读取预算；
5. 复杂蓝图编译、依赖排序和候选评分可在不可变快照上异步执行；
6. 异步结果返回后复核 generation、blueprint revision、目标 revision 和已加载状态；
7. 多 Bot 共享全局 Technique/候选工作预算并轮转起点；
8. TPS 压力下先降低非必要拟人观察、候选重排和表现延迟，不降低 L0 安全；
9. 诊断记录耗时、候选数、Action 数、失败码和重试，不记录无界逐 Tick上下文。

建议指标：

```text
active_techniques
technique_ticks_total
technique_action_submissions
technique_recoveries
technique_timeouts
combat_hits_confirmed
combat_windows_missed
placement_candidates_examined
placements_verified
placement_state_mismatches
temporary_blocks_remaining
```

---

## 12. 测试矩阵

### 12.1 纯 Java

| 领域 | 必测 |
|---|---|
| Technique FSM | 合法转换、非法转换、终态幂等、deadline |
| child 管理 | 不冲突并发、通道冲突拒绝、迟到/重复回执、统一 cleanup |
| generation | 换代、旧回执、旧目标 revision、旧蓝图 revision |
| Aim | 转速上限、目标预测、视线丢失、确定性 profile |
| Combat | 攻击窗口、目标移动、冷却、战术切换、撤退阈值 |
| Building | 依赖图、工作包拆分、候选排序、逃生、自封闭、临时 ownership |
| Diff | wrong block/state、human override、unloaded、protected |
| Budget | Tick、Action、候选、重试、工作包硬上限 |

### 12.2 NeoForge GameTest：战斗

1. 单个僵尸：普通近战命中且冷却真实；
2. 真实跳跃进入下落窗口后发起一次攻击；
3. 目标中途移动出距离，本次跳劈不隔空命中；
4. 目标被其他玩家击杀，Technique 返回 `TARGET_GONE`；
5. 悬崖/熔岩/低血出现时 L0 抢占；
6. PVP/保护拒绝不伪装成功；
7. 取消、死亡、换代后无残留输入或持续使用；
8. 战斗完成后拾取掉落由独立 Skill/Action 完成，不混入命中判定。

### 12.3 NeoForge GameTest：建筑

1. 地面站位放置普通方块；
2. 蹲边放置且 Bot 不跌落；
3. 垫柱到有限高度并安全下来；
4. 临时脚手架完成后只清理自身 ownership 方块；
5. 支撑方块中途被移除，重新求候选或失败；
6. 材料中途变化，物品不复制、不负数；
7. 方块放出错误 BlockState，进入差异而非伪装成功；
8. 真人修改施工位，标记 `HUMAN_OVERRIDE` 并暂停；
9. 取消、死亡、换代、停服时工作包从安全边界恢复；
10. `5×5` 小屋从真实背包逐块施工，无 `setBlock` 快捷路径。

### 12.4 集成、客户端和 soak

- 真人客户端观察转头、移动、跳跃、挥手、蹲姿和装备切换；
- 独立专用服运行；
- 1/2/4 Bot Technique 预算公平；
- 战斗打断施工后恢复；
- 8 小时以上无 Technique、Action、目标实体、输入 owner、区域锁和临时方块泄漏；
- 物品复制和未知方块覆盖计数必须为 0。

---

## 13. 分阶段开发路线

本设计使用 `PT`（Player Technique）作为横向开发轨，不新增 P0–P10 顶层阶段。

| 轨道 | 内容 | 对应路线图 | 退出证据 |
|---|---|---|---|
| `PT0` | ADR、设计、边界与测试矩阵 | 当前文档 | 文档审查；不计能力完成 |
| `PT1` | Technique Runtime、child 管理、ViewStep、身体快照 | P5A 后半/基础设施 | 纯 Java全门 + 最小 GameTest |
| `PT2` | `basic_melee`、`jump_critical`、`strafe_attack`、撤退交接 | P5A 有限自卫 → P5C | 单目标完整战斗纵切 |
| `PT3` | 放置候选、地面/蹲边/垫柱/脚手架 Technique | P5D 前置 | 单块和临时结构 GameTest |
| `PT4` | BlueprintCompiler、施工图、材料、`5×5` 小屋 | P5D | 真实材料小屋端到端 |
| `PT5` | 盾/弓/多战术、屋顶/楼梯/特殊 BlockState、修复 | P5C/P5D | 扩展矩阵与故障恢复 |
| `PT6` | profile、长期统计、多 Bot 分区与 soak | P7/P9/P10 | 重启、性能和多 Bot 证据 |

### 13.1 推荐实施顺序

```text
先完成当前 P5A 生命周期/menu/checkpoint/生产链门
→ PT1 Technique 内核
→ PT2 单目标近战与跳劈
→ PT3 单块真实施工技巧
→ PT4 小屋纵切
→ P6/P7 让 AI/Goal 调用已验证 Skill
→ PT5 高级战斗与复杂建筑
→ PT6 多 Bot 和发布硬化
```

不能为了演示跳劈或盖房跳过 P5A 的 Action cleanup、背包守恒、generation 和 Skill
检查点边界。

---

## 14. 首轮代码交付建议

### 14.1 `PT1-A`：Technique 纯 Java 核心

新增：

```text
technique/core/*
technique/runtime/*
src/test/.../technique/*
```

完成：

- FSM；
- child ticket 与通道冲突检查；
- deadline/预算；
- cancel/preempt/cleanup；
- generation/revision；
- 确定性 profile seed；
- scripted Action outcome 测试。

不接 Minecraft，不新增空占位包。

### 14.2 `PT1-B`：Minecraft 适配纵切

新增：

```text
technique/snapshot/*
technique/aim/*
action/RotateViewAction.java（或等价名称）
Minecraft 视角适配器
```

GameTest：

- 有界转头；
- 移动目标跟踪；
- 取消后停止；
- generation 变化拒绝旧 child。

### 14.3 `PT2-A`：单目标近战

新增：

```text
combat/CombatBlackboard.java
combat/CombatTacticSelector.java
technique/combat/BasicMeleeTechnique.java
technique/combat/JumpCriticalTechnique.java
skill/builtin/combat/DefeatTargetSkill.java
```

先只支持一个明确敌对、近距离、无复杂远程能力的目标；完成后再扩目标广度。

### 14.4 `PT3-A`：单块真实施工

新增：

```text
building/PlacementCandidate.java
building/PlacementCandidateFinder.java
technique/building/GroundPlaceTechnique.java
technique/building/CrouchEdgePlaceTechnique.java
```

先验证普通方块与楼梯/台阶代表性状态，不立即覆盖全部 BlockState。

### 14.5 `PT4-A`：小屋工作包

当前已落地 `P5D-A0` 的 `building/blueprint/` 纯 Java 数据边界、`P5D-A1` 的
`building/construction/` exact-cover work-package DAG、`P5D-A2` 的 `building/site/` candidate coordinate
binding，以及 `P5D-A3` 的 caller-supplied exact target evidence/fail-closed assessment：A0 限制 Blueprint
cell/offset/span、重复坐标与 content hash；A1 只把同一 immutable Blueprint 按完整
`(id, revision, hash, ordinal)` 分为有界分包并提供稳定拓扑读取；A2 只把 exact plan 绑定到非零 siteId、
dimension+anchor 与从真实 Blueprint cell 派生的 bounds/known target；A3 的
`ACCEPTED_CANDIDATE` 只表示 supplied evidence 的结构兼容；A4 只要求 full target state 的 explicit item
declaration 并导出 declared quantity。A4 records 仍只给出结构性输入，**不**读取 Minecraft、把 blockId 自动映射为
背包物品；另有 A4-R1 server-thread registry/default-state candidate check，只核对现有 explicit `BlockItem` 与其 default
full state，仍不证明 `useOn` 或 contextual placement。A7 只在同一 server thread 对活动精确 Bot body 的 empty native
`InventoryMenu` 统计默认 stack 的 main/hotbar 瞬时 aggregate availability/shortage；它不把 unknown menu/registry
当作零库存，也不读任何容器、不移动/预留材料或接 placement。它们均不接 NBT、真实 survey/accepted site、保护/加载检查、
materials/lease、ownership/human confirmation、modules/`PostPlacementSemantic`、真实施工图、Technique 或真实世界放置。
以下仍是后续 PT4-A 目标：

后续扩展（其中 `building/site/` 已有 A2 binding 和 A3 caller-evidence assessment DTO，`building/material/`
已有 A4 explicit declaration、A4-R1 default-state candidate check 与 A7 narrow own-inventory snapshot，尚缺可信
world/contextual-registry placeability sampler、真实 survey/lease、container/warehouse/material reservation 与施工 availability）：

```text
building/blueprint/*
building/site/*                    # future survey/assessment/lease inputs
skill/builtin/building/ConstructWorkPackageSkill.java
```

第一个端到端目标：

```text
固定或程序化 5×5 单层小屋
→ 地基
→ 四角柱
→ 留门窗的墙
→ 简单屋顶
→ 门和火把
→ 清理临时结构
→ 最终差异验证
```

材料全部来自 Bot 真实背包或已验收容器，不允许创造模式或直接世界写入。

---

## 15. Definition of Done

### 15.1 Technique 内核

- 所有状态、child、预算和 cleanup 有纯 Java 测试；
- 旧 generation、迟到回执和冲突通道 fail-closed；
- L0 抢占后无残留输入、使用物品、菜单或 Action owner；
- Technique 不持有活动 Minecraft 对象；
- 诊断包含 `botId/generation/skillRunId/techniqueRunId/actionId`；
- 不影响既有 P2–P5 回归。

### 15.2 基础战斗

- 单目标普通近战和跳劈均走真实玩家入口；
- 冷却、距离、视线、装备和危险由权威状态约束；
- 不能证明暴击时不宣称暴击；
- 目标变化、权限拒绝、低血和多敌人能撤退或失败；
- 死亡/换代后不继续攻击旧目标；
- GameTest、客户端观察和专用服分别记录结果。

### 15.3 基础建筑

- 每个方块从真实可达站位通过 `UseOnBlock` 放置；
- 精确 BlockState、材料消耗和保护事件被验证；
- 施工不会无意封死 Bot 或覆盖真人修改；
- 临时结构有 ownership、预算和清理证据；
- 工作包可取消并从安全边界恢复；
- `5×5` 小屋无直接世界写入、无物品复制、无未清临时方块；
- 通过后再扩大型模块化建筑和 AI 风格生成。

---

## 16. 文档同步要求

后续实现每个 PT 切片时必须同步检查：

- `IMPLEMENTATION_STATUS_CN.md`：只写真实已编码/已验证事实；
- `VANILLA_CAPABILITY_MATRIX_CN.md`：只有直接证据才能提升相关 ID；
- `ARCHITECTURE_AND_ROADMAP_CN.md`：Technique 层和 P5C/P5D 任务；
- `DEVELOPMENT_CN.md` 与 `AGENTS.md`：新增实际包和源码路由；
- `CHANGELOG.md`：用户可见能力；
- `THIRD_PARTY_NOTICES.md`：若引入外部算法或代码；
- GameTest、客户端、专用服和 soak 结果必须分别报告。

本设计进入仓库只表示 `PT0` 完成，不表示 Bot 已经会跳劈、会盖房或达到完整战斗能力。
