# ADR-0015：有界技能运行时与统一菜单事务

- 状态：Accepted
- 日期：2026-07-29
- 实现注记更新：2026-07-30
- 关联：ADR-0006、ADR-0013、ADR-0014

> 当前实现说明：事务模型可以表达主背包换甲的 2～3 步计划，但生产后端暂时只接受单次
> 可逆 `InventoryMenu` `SWAP`。首个装备纵切因此只扫描热栏；多步执行必须等跨 Tick
> cleanup FSM、运行期失败注入和 NeoForge 验收闭合后才能开放。

## 背景

P2 已提供 generation 绑定的确定性动作、原版玩家交互和 Bot 自身背包 menu；P3 已提供
有限感知；P4 已提供导航和独立 L0 安全平面。它们仍不能安全地表示“取得资源、制作工具、
进食或自卫”这种跨多个 Tick、多个动作并需要恢复的业务行为。

如果 Skill 直接保存 Minecraft 对象、写背包、调用方块实体，或把容器点击分散到各个
技能，会产生四类不可接受的问题：

1. 死亡、重生、换维度和重启后复活旧对象或旧 generation；
2. stateId、carried stack、真人/漏斗并发变化导致吞物或复制；
3. 高层任务绕过 P2 动作、P3 有限认知或 P4 安全抢占；
4. 无界 DAG、重试、查询、持久数据和资源等待拖垮服务器。

P5A 还需要明确“关服恢复”和“自动上线”的区别。Skill 可以在同一 Bot 重新上线后从
检查点恢复，但不能私自替代尚未完成的 roster autoload 生命周期功能。

## 决策

### 1. 有界 Skill Runtime

每次 Skill run 绑定 `skillRunId + botId + generation + skillId/version + deadline`。
所有队列、转换、历史、重试、恢复、证据和并发都有服务器配置上限与不可突破的绝对上限。

中央 FSM 使用：

```text
CREATED
PREPARING
RUNNING
WAITING_ACTION / WAITING_NAVIGATION / WAITING_MENU /
WAITING_QUERY / WAITING_TIMER
PAUSING / PAUSED / RESUMING
RECOVERING
VERIFYING
SUCCEEDED / FAILED / CANCELLED / PREEMPTED
```

实现逐项枚举合法转换。`SUCCEEDED/FAILED/CANCELLED/PREEMPTED` 是不可复活终态。
普通 L0 安全中断若可恢复，必须走 `PAUSING → PAUSED → RESUMING`；`PREEMPTED` 只表示
更高优先级运行明确替代且旧运行不再恢复。成功必须由权威后置条件验证。

Skill 提交的动作、导航、查询和 menu 事务都携带 run 来源与稳定幂等键。迟到的旧
generation 或旧 state revision 结果被丢弃。

### 2. 有界局部 DAG

P5A DAG 只编排一个已批准任务中的已注册 Skill，不承担 P7 的长期目标、承诺或记忆。
计划必须：

- 使用稳定 plan/node ID、正数 revision 和严格参数 schema；
- 在入队前检查缺失能力、环路、悬空边、ACL、风险和预算；
- 限制节点、边、深度、理论 Tick、并发、重试和补偿；
- 以稳定 node ID 决定就绪顺序；
- 只让必需前驱成功后启动节点；
- 在所有节点完成后仍执行最终权威 verifier。

外部 Skill Pack 只能是声明式白名单 DAG，不能携带任意 Java、脚本、反射或命令。内容
摘要或版本变化后必须重新审批。

### 3. 有限 TaskSensor

P5A 使用任务私有、主线程、有预算的 `TaskSensor` 补充 P3 通用快照。查询只允许读取：

- 当前 Bot 的真实身体、背包和已装备物；
- 已加载、局部、任务已经合法定位的方块、资源、掉落和威胁；
- 当前真实打开 menu 的槽位和工作站状态；
- 原版配方可行性所需的有限纯数据。

输出只包含不可变 DTO、采样 Tick、scope revision、截断/不可用标记和有界证据。它不得
扫描未加载区块、透视未打开容器、返回活对象或扩大 P3 对外认知。

### 4. Safety handoff

P4 L0 保留每 Tick 观察和最高抢占权。Safety 可以发出绑定 incident、Bot 和 generation
的不可变 handoff 请求，但不能直接提交攻击、进食、换甲或 menu 点击。

P5A 只接受：

- `FOOD_CRITICAL → eat_food`；
- 满足健康、单一敌对目标、友军/PVP/权限和撤退条件时的有限 `self_defend`。

低生命和有害效果在 P5A 只触发避险/阻塞报告。药水、牛奶和治疗物品冻结在 P5B；战斗
投掷药水仍在 P5C。handoff 不可用或拒绝时，L0 执行保守回退；技能运行期间 L0 可以立即
再次抢占。

### 5. 统一 Inventory / World Menu Transaction

所有 Bot 自身背包和世界 menu 操作使用同一事务内核：

```text
OPENING → SNAPSHOT → PLANNING → APPLYING →
ACKNOWLEDGING → VERIFYING → CLOSING → TERMINAL
```

事务捕获并复核 `botId/generation`、维度/目标、`menuType`、`containerId`、`stateId`、
槽位布局、物品指纹/数量、carried stack 和 scope revision。一次只允许一个未确认点击，
每次点击和完整事务都验证物品及配方剩余物守恒。

点击必须经过原版 serverbound/menu handler 路径，遵守 `Slot`、配方、燃料、时间、
数据组件、距离、游戏模式和 NeoForge/保护模组结果。Skill 禁止直接写 `Inventory`、
`Slot`、`ItemStack` 或工作站 NBT。

P5A 必需白名单为 `InventoryMenu`、`CraftingMenu`、`FurnaceMenu` 和一个 3×9 单箱
`ChestMenu`。双箱/其他行数及其他广泛原版 menu 在 P5B，自定义 menu 在 P8。未知 menu
默认返回 `MENU_UNSUPPORTED`。

外部变化使 stateId、槽位或 revision 不匹配时，事务停止并重新快照或失败；不能把新状态
套入旧点击计划。取消必须通过原版路径安全处理 carried stack；无法解释的物品差额以
`ITEM_CONSERVATION_VIOLATION` 失败并隔离 generation。

### 6. Checkpoint

检查点只保存有界纯数据：

- schema/integrity 版本和稳定 ID；
- server instance、Bot/player 身份；
- Skill/plan 版本、语义阶段、状态 revision、尝试/恢复计数；
- 维度、坐标、实体 UUID、预期指纹和最后已验证摘要；
- 有界失败码和 evidence。

检查点禁止保存 Minecraft 活对象、`ItemStack`、完整 NBT、menu/container 实例、
carried 可重放状态、动作 future、输入 owner、运行时租约、secret 或无界文本。

P5A 使用独立 Overworld `SavedData`
`<world>/data/botplayer_skill_checkpoints.dat` 作为恢复权威。它不修改 roster schema、
不复制 playerdata，也不依赖 P7 SQLite。记录以 botId 索引，并用 roster 的
`serverInstanceId` 复核世界身份；P7 可以镜像完成历史和统计，但未经新 ADR 与迁移不能
改变恢复权威。

只在 menu 已安全关闭、没有悬空输入/动作且阶段可重新验证的安全点写入。重启后由管理员
或既有生命周期入口让同一 Bot 重新上线；Runtime 绑定新 generation，重新观察、重新验证
并重新取得资源后继续。旧 generation 的动作、menu、事件水位和租约永远不复活。
autoload 仍是独立生命周期前置，不由 Skill Runtime 实现或宣称完成。

### 7. Resource Reservation

预留是服务器主线程上的有界 TTL 租约，不是 Minecraft 所有权。键只含规范化纯数据，
覆盖目标方块/实体、容器、物品范围和工作区。支持共享/排他模式、重复申请幂等、续租旧
token 拒绝、canonical 多键原子获取、容量/TTL 上限和 generation/run 级清理。

真人或其他系统仍可改变世界；变化使观察与计划失效。检查点只保存资源需求，不保存可复活
token，恢复时必须重新申请。

### 8. P5A 纵切片与阶段边界

首批 P5A 纵切片固定为：

1. 主动进食，包括把主背包槽 9+ 的食物经真实 `InventoryMenu` 移到快捷栏；
2. 基础盔甲、工具和普通副手选择；
3. 撤退和满足严格资格门的单目标基础近战自卫；
4. 空背包开始，经原木、木板/工作台、木镐、石头/石镐、燃料、至少三个 raw iron 和
   熔炉取得至少三个真实铁锭，再通过真实工作台制作铁镐；
5. 通过 3×9 单箱完成指定数量存取，证明世界储存 menu 的 stateId、carried 和物品守恒。

最终以权威背包和物品守恒验证，不以动作提交、DAG 节点数量或模型文字验证。盾牌格挡、
远程/多目标战斗、主动药物、广泛工作站、长期目标和模组自定义 menu 不属于 P5A。

## 被否决方案

- **每个 Skill 自己实现背包/容器点击**：会复制 stateId、carried、布局和守恒逻辑，
  无法形成统一故障边界。
- **直接修改 `Inventory`、`Slot`、工作站 NBT 或生成配方产物**：绕过原版规则、事件和
  保护模组，并带来复制风险。
- **把所有 menu 当成通用槽位数组**：不同 menu 的槽位角色、Quick Move、配方和关闭
  语义不同；未知布局必须默认拒绝。
- **把完整 Minecraft 对象图序列化为检查点**：对象跨 Tick、generation、进程和模组版本
  都不稳定，也不能安全限制大小。
- **恢复时继续旧动作或旧 containerId/stateId**：旧身体和旧 menu 已无权威性。
- **让 Safety 直接吃饭或攻击**：会把 L0 常量时间反射扩展为高风险业务执行，并绕过
  Skill 审计、预算和验证。
- **让 Skill 关闭或降频 L0 Safety**：任务吞吐不能优先于生存安全。
- **把 P3 快照扩成任务所需的一切**：会污染有限认知、增加全服成本并引入容器透视。
- **用互斥锁阻止真人改变世界**：内部预留不能改变 Minecraft 权限和玩家主权。
- **把 P5A DAG 当成长期 Goal planner**：会提前耦合 P7 的承诺、记忆和模型推理。
- **在 P5A 顺便实现主动药物与高级战斗**：缺少效果/稀缺度/友军和专项测试合同，扩大
  首条闭环风险。
- **用单 JVM respawn 代替真重启测试**：不能证明检查点落盘、版本解析和旧租约不复活。

## 兼容性、性能、安全与许可证影响

### 兼容性

- 原版和标准模组仍通过真实 `ServerPlayer`、menu、recipe、事件和组件规则生效；
- 未知 menu、特殊模组工作站和专用效果默认拒绝，后续按 P8 适配；
- Skill/checkpoint/pack 都显式版本化，升级时不静默解释为新语义；
- P5A 不改变 P1 roster/playerdata schema 的权威地位。

### 性能

- plan、队列、传感器、事务、租约、检查点、重试和诊断均有每 Bot/全服硬上限；
- 主线程只做有界读取和状态变更，纯数据静态校验可在受限执行器执行；
- MSPT 压力时普通 Skill 可降频/背压，L0 Safety 不降级；
- menu 一次一个未确认点击，牺牲少量吞吐以换取确定性和守恒。

### 安全

- schema 默认拒绝未知字段，外部 pack 只允许白名单声明式 DAG；
- 所有可变世界行为仍受 ACL、风险、P2 动作、P4 Safety、原版事件和保护模组约束；
- generation/revision/deadline 防止迟到结果影响新身体；
- carried 或物品差额无法解释时 fail closed 并隔离，不自动重试；
- 诊断和检查点不得包含 secret、完整 NBT、无界文本或未感知私有容器内容。

### 许可证

本决策不引入第三方运行时或复制外部 AI/寻路源码。DAG、FSM、事务和租约基于通用软件
工程模式自行实现；新增依赖仍需单独审查许可证与分发要求。

## 迁移和回滚

P5A 首次引入 Skill/checkpoint schema 时使用独立版本、SavedData key 和命名空间，不修改
既有 roster、playerdata 或 P3 世界事实。旧世界没有检查点时保持正常，只是不恢复 Skill。

升级步骤：

1. 先部署能读取旧版本并拒绝未知新版本的 reader；
2. 只迁移纯数据字段，迁移前后验证大小和内容摘要；
3. 找不到精确 Skill 版本时隔离记录并报告；
4. 不迁移运行中 menu、carried、动作、租约或 generation；
5. 迁移完成后仍执行完整恢复复核。

回滚时关闭 Skill 提交和恢复入口，取消/清理在线 run，安全关闭 menu 并释放租约。保留
有界检查点供再次升级或管理员导出；旧版无法理解的检查点不会影响 Bot 登录、playerdata、
P2 动作、P3 感知或 P4 安全。若发现守恒或 generation 隔离缺陷，应立即禁用对应 menu
adapter/Skill，而不是放宽验证。

## 验证方式

- 纯 Java 单测覆盖 descriptor/schema、中央 FSM、DAG、幂等、迟到结果、预算、checkpoint、
  reservation、menu 模型和失败映射；
- NeoForge GameTest 覆盖主动进食、基础装备、Safety 暂停/恢复、有限自卫、真实
  Inventory/Crafting/Furnace menu、carried、外部变化和物品守恒；
- 完整纵切片从空背包取得真实铁锭并制作铁镐，另验证 3×9 单箱存取；覆盖树/工作站变化、
  满包、权限拒绝、死亡和断开；
- 使用固定 Bot 身份和完整 fixture 清理，在同一持久 GameTest 世界连续运行两轮；
- 独立两次服务器启动验证同一 Bot 显式重新上线后的检查点恢复，不能用单 JVM respawn
  替代；
- P4 的 200 格、实体阻挡、强制 stuck、熔岩/窒息/冰冻、喷溅药水、伤害事件变化和
  自定义效果 hardening 在 P5A 总退出门前关闭或明确延期；
- 客户端、独立专用服、保护模组与多 Bot soak 分别记录，不由单元测试或 GameTest 冒充。

完整设计、分阶段实现和测试矩阵见
[P5 调研与 P5A-0 设计冻结](../AI_PLAYER_RESEARCH_AND_P5_DESIGN_CN.md)。
