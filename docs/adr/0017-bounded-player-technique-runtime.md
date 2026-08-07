# ADR-0017：有界玩家技术动作运行时

- 状态：Accepted
- 日期：2026-08-06
- 相关：ADR-0002、ADR-0013、ADR-0014、ADR-0015

## 背景

BotPlayer 已经把普通玩家行为拆成了若干层：

- P2 `Action` 负责一次可验证、可取消、受通道仲裁约束的原子动作；
- P3 提供有限感知、权威/认知事件和平面化世界事实；
- P4 提供有界导航、真实玩家输入 follower 与独立 L0 安全反射；
- P5 `Skill` 负责秒级到分钟级任务、资源预留、检查点和结果验证；
- P6/P7 的计划与目标只负责更高层意图、承诺和任务编排。

但是“像玩家一样操作”还存在一个结构空缺。跳劈、冲刺击退、侧移攻击、渐进瞄准、蹲下沿边
放置、脚下垫高、临时脚手架和从可达站位放置方块，都不是单个原子动作，也不应膨胀成长期
Skill。它们通常持续数 Tick 到数百 Tick，需要连续读取身体、目标或局部施工状态，按阶段
提交多个既有 Action，并在世界变化、安全抢占或动作失败后局部恢复。

如果直接把这些逻辑塞进 Skill，会让每个战斗/建造 Skill 重复实现按 Tick 身体控制、动作
等待和清理；如果把 Action 扩成长序列，则会破坏 P2 的原子性、幂等和通道所有权；如果让
LLM 逐 Tick 输出 WASD、视角或攻击时机，则会把网络延迟、模型幻觉和不可重放随机性引入
服务器物理闭环。

因此需要在 `Action` 与 `Skill` 之间增加一个短时、确定性、可验证的玩家技术动作层。

## 决策

### 1. 固定五层控制边界

```text
Goal / Commitment
    ↓
TaskPlan（Skill DAG）
    ↓
Skill（任务级、可检查点、秒到分钟）
    ↓
Technique（短时玩家技术动作、Tick 反馈闭环）
    ↓
Action / Navigation / Menu（原子副作用与真实玩家入口）
```

各层职责固定如下：

| 层 | 负责 | 不负责 |
|---|---|---|
| `Action` | 一次移动输入、跳跃、转向、攻击、使用、放置、破坏或菜单点击及其真实结果证据 | 组合战术、蓝图施工顺序、长期恢复 |
| `Technique` | 在有界时间内编排多个已注册 Action，依据局部反馈完成一种玩家操作技巧 | 长期目标、资源生产、跨重启中途续跑、任意世界修改 |
| `Skill` | 完成“击败目标”“放置一个蓝图工作包”“收集材料”等任务，管理检查点、资源和失败升级 | 逐 Tick 直接操纵玩家身体 |
| `TaskPlan` | 只编排已注册 Skill 的依赖、预算、权限和成功条件 | 输出逐 Tick 动作或 Technique 内部状态 |
| `Goal` | 表示承诺、维护、安全和自治目标 | 直接拥有 Minecraft 对象或动作通道 |

### 2. `TechniqueRuntime` 是短生命周期确定性 FSM

新增目标包：

```text
technique/
  core/
  runtime/
  aim/
  combat/
  building/
```

Technique 至少具有以下状态：

```text
CREATED
→ PREPARING
→ RUNNING
↔ WAITING_ACTION / WAITING_NAVIGATION
→ VERIFYING
→ SUCCEEDED
```

旁路终态为 `FAILED`、`CANCELLED`、`PREEMPTED`。允许在同一 Technique 内进行少量有界
`RECOVERING`，但恢复次数、持续 Tick、阶段数和动作提交数都必须受服务端上限约束。

初始实现采用以下硬边界：

- 每个 bot 同时最多一个会改变身体、视角、主副手或背包的前台 Technique；
- 每个 `ActionChannel` 最多存在一个由该 Technique 持有的活动 child；
- 一个 Technique 可同时持有少量互不冲突的 child Action，初始总并发硬上限为 3；
- Navigation follower 与手工 `MOVE` child 不得同时占用移动控制；
- 阶段数、动作数、局部重试、候选站位和总运行 Tick 均有明确上限；
- 每 Tick 只进行 O(1) 状态推进和有限 DTO 判断，不进行无界方块/实体扫描；
- 所有 world read 通过服务器线程构造的有界快照或现有服务接口完成；
- Technique 只保存 `botId`、generation、ID、枚举、坐标、计数、revision 和不可变 DTO，
  不跨 Tick 长期持有 `Level`、`Entity`、`ItemStack`、`Menu` 等活动对象。

允许少量不冲突 child 的理由是，真实玩家技巧需要同时维持不同输入通道，例如：

- `MOVE` 保持冲刺或侧移，同时 `MAIN_HAND + INTERACT` 攻击；
- `MOVE` 保持蹲姿，同时 `LOOK` 对准并 `MAIN_HAND + INTERACT` 放置；
- `LOOK` 跟踪目标，同时 `MAIN_HAND` 维持拉弓。

### 3. Technique 不建立第二套动作或输入所有权

Technique 只能通过既有 `BotActionRuntime`、`NavigationService`、菜单事务和结果回执提交
副作用。它不得：

- 直接调用 `setBlock`、`hurt`、`teleportTo` 或修改背包/NBT；
- 绕过 `ActionChannel`、`ControlArbiter`、`PlayerInputController` 或保护事件；
- 伪造攻击命中、暴击、方块放置或物品消耗；
- 在任一目标通道仍由未终结 child 占用时提交冲突动作；
- 同时运行 Navigation follower 与冲突的手工移动 child；
- 把“没有抛异常”当作成功。

Technique 可以拥有自己的 `techniqueRunId` 与阶段 revision，但 Action 的幂等键、通道租约、
副作用和 cleanup 仍由 P2 动作层权威管理。Technique 终结前必须取得全部 child 的终态或
安全 cleanup 回执。

### 4. L0 安全拥有最高抢占权

L0 安全反射可在任意 Technique 阶段抢占。Technique 收到安全 handoff 后必须：

1. 停止提交新动作；
2. 等待所有活动 child Action 按既有合同完成或清理；
3. 释放临时 Technique 资源；
4. 返回可恢复的安全边界或明确失败；
5. 由 Skill 在危险解除后重新观察并决定是否重新启动 Technique。

Technique 不得借“战斗”“脚手架”或“拟人行为”覆盖 L0 的坠落、熔岩、窒息、爆炸、低血量
或菜单强制关闭决定。

### 5. 中途 Technique 不跨服务器重启恢复

Technique 表示短时身体动作。服务器停止、死亡、换代或重启时，不持久化“正在半空跳劈”
“视角转到一半”或“已执行脚手架动作的第 3 Tick”。生命周期关闭必须先完成 Action 和临时
布局清理；上层 Skill 只在安全工作包/目标阶段保存检查点。

恢复时流程为：

```text
读取 Skill 检查点
→ 重新绑定新 generation
→ 重新观察身体、目标和世界
→ 判断原工作是否已完成
→ 从安全阶段重新创建 Technique
```

### 6. 战斗技巧必须依赖原版真实结算

`jump_critical`、`sprint_hit`、`shield_counter`、`bow_shot` 等 Technique 只负责：

- 选择或确认装备；
- 有界转向与接近；
- 等待真实攻击冷却和身体窗口；
- 通过普通玩家攻击/物品使用入口提交动作；
- 根据权威伤害、目标状态、耐久、冷却和位置变化验证结果。

不得直接乘算伤害或强制设置“暴击”。跳劈只有在真实跳跃、下落、距离、视线和原版攻击
结算成立后才成功；原版、NeoForge、保护或其他模组拒绝攻击时必须诚实失败或换战术。

### 7. 建筑技巧必须从真实可达站位放置

`ground_place`、`crouch_edge_place`、`pillar_up`、`temporary_scaffold`、
`overhead_place` 等 Technique 必须由本地放置候选求解器给出：

- 可站立位置；
- 可点击支撑方块与面；
- 命中点；
- 所需视角、主副手和蹲姿；
- 交互距离、碰撞、坠落和脱离路径；
- 预期物品与目标 BlockState 指纹。

实际放置继续走 P2 `UseOnBlock`/玩家交互入口，成功由世界 BlockState、背包消耗、事件和
revision 共同证明。普通 Bot 不允许直接写入任意 BlockEntity NBT；告示牌、容器、床、门、
红石机器等特殊语义由已注册适配器或真实菜单/交互完成。

### 8. 拟人风格只能改变表现，不能降低正确性边界

允许 `TechniqueProfile` 配置有界反应延迟、转头速度、偏好的侧移方向、风险容忍和施工习惯。
变化使用由 `botId + skillRunId + techniqueRunId` 派生的确定性种子，以便 GameTest 和回放
复现。

拟人风格不得：

- 随机丢物、拆错方块或攻击友军；
- 绕过安全、权限、材料和结果验证；
- 为了“像真人”故意制造不可恢复错误；
- 把随机抖动用于需要精确命中面的最终放置。

## 被否决方案

### 方案 A：所有组合动作都写进 Skill

否决。Skill 会同时承担任务规划、资源、持久化和逐 Tick 身体控制，战斗与建筑会复制大量
状态机、动作等待和清理逻辑，难以统一测试和抢占。

### 方案 B：把 Action 扩展成长序列或脚本

否决。Action 必须保持原子、可幂等和可独立验证。长序列会模糊一次副作用的身份、deadline、
通道所有权和补偿边界。

### 方案 C：LLM 每 Tick 输出移动、视角和攻击

否决。API 延迟、模型不确定性、成本和迟到结果不适合 20 TPS 物理闭环，也无法保证服务器
线程、权限、重放和安全。

### 方案 D：为了表现直接改伤害、方块或位置

否决。这会绕过原版玩家规则、NeoForge 事件、保护模组和审计，并破坏 BotPlayer 的真实
`ServerPlayer` 产品定义。

### 方案 E：只使用随机行为树，不保留结果证据

否决。行为树可以作为 Technique 内部实现手段之一，但不能取代 generation、revision、
ActionOutcome、失败码、超时、清理和最终世界验证。

## 兼容性、性能、安全与许可证影响

### 兼容性

- Technique 复用普通玩家入口，保护、PVP、属性、附魔、状态效果和模组事件仍有机会生效；
- 与版本相关的攻击冷却、姿态、命中和放置细节集中在 Minecraft 1.21.1 适配层；
- 未知模组实体或自定义菜单不由通用 Technique 猜测，继续按 P8 C0–C3 适配。

### 性能

- 技巧推进为有界 Tick FSM；
- 瞄准、攻击窗口和姿态读取只使用当前 Bot 与当前目标的有限快照；
- 施工站位候选必须限制半径、数量和扫描预算，复杂排序可对不可变快照异步执行；
- 多 Bot 共享每 Tick Technique 工作预算并轮转起点。

### 安全

- L0 安全、owner/ACL、PVP、保护事件和动作层通道优先级不变；
- 临时脚手架、可破坏区域、追击距离和世界改动量必须由 Skill/Plan 明确授权；
- Technique 输出和诊断不包含 API Key、完整模型上下文或未感知的世界信息。

### 许可证

本决策不引入第三方运行时依赖。若后续参考或移植外部战斗、寻路、建筑或行为树实现，必须
先核对许可证并更新 `THIRD_PARTY_NOTICES.md`；不得直接并入不兼容许可源码。

## 迁移和回滚

1. 先实现纯 Java `TechniqueRuntime`、状态和测试，不修改现有 Action 语义；
2. 用 `basic_melee`、`jump_critical` 和 `ground_place` 三个最小纵切验证边界；
3. P5A 的有限自卫可选择最小战斗 Technique，P5C 扩展完整战斗；
4. P5D 的蓝图 Skill 通过建筑 Technique 施工，不直接调用世界修改；
5. 现有 Skill 在迁移前仍可直接提交单个 Action；不强制一次性重写；
6. 若 Technique 层未通过退出门，可删除未被 Skill 使用的实现包，既有 Action/Skill 数据
   和协议不需要迁移。

## 验证方式

### 纯 Java

- FSM 合法/非法转换；
- 阶段、动作数、Tick、重试、并发 child 和候选上限；
- 相同/不同 ActionChannel 的并发与冲突；
- generation/revision 失效；
- Action 成功、失败、取消、迟到和重复回执；
- L0 抢占、全部 child cleanup 与重新创建；
- 确定性风格种子与回放一致性。

### NeoForge GameTest

- 真实普通近战与攻击冷却；
- 真实跳跃、下落窗口、移动目标和一次跳劈尝试；
- 攻击被保护/PVP/世界变化拒绝时不伪装成功；
- 地面放置、蹲边放置、垫柱和临时脚手架；
- 放置目标、支撑或材料中途变化后的局部恢复；
- 安全抢占、死亡、换代和卸载后无残留输入、动作或临时所有权；
- 所有方块与物品变化守恒。

### 后续集成与 soak

- 单 Bot 小屋完整施工；
- 战斗打断施工后安全恢复；
- 多 Bot 技巧预算公平、无通道死锁；
- 长时间运行无 Technique、Action、目标实体或施工候选泄漏。
