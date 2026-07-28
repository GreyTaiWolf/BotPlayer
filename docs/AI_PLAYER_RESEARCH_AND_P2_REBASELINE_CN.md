# BotPlayer AI 玩家调研与 P2 重新基线

> 文档状态：研究结论与实施基线 v2
>
> 更新日期：2026-07-28
>
> 适用分支：P2 验收分支
>
> 第一目标平台：Minecraft Java 1.21.1、NeoForge 21.1.244、Java 21

本文回答两个问题：

1. BotPlayer 距离“完整 AI 玩家”还缺什么；
2. 在接入 DeepSeek、长期记忆或复杂寻路前，P2 必须交付什么样的可靠身体。

它不是功能宣传页。当前代码事实与最终验证状态分别以
[当前实现状态](IMPLEMENTATION_STATUS_CN.md) 和
[P2 完成报告](P2_COMPLETION_REPORT_CN.md) 为准。

## 1. 结论摘要

BotPlayer 继续使用真实 `ServerPlayer` 是正确方向，但仅有真实玩家实例并不等于拥有可靠
AI 身体。完整闭环至少需要：

```text
权威世界事实
→ 有界感知
→ 目标/计划
→ 可恢复技能
→ 可仲裁原子动作
→ 原版/NeoForge 玩家路径
→ 世界证据验证
→ 事件与记忆
```

P2 的角色不是“提前接入大模型”，而是建立确定性执行脊柱。它必须先保证：

- 重生、卸载和换维度后，旧 generation 的动作不能操作新实例；
- 每个副作用动作有身份、幂等键、时限、通道租约、取消和唯一终态；
- 移动使用玩家输入与原版物理，普通世界变化使用服务端玩家交互路径；
- 成功来自方块、实体、物品和玩家状态证据，不来自调用返回或模型文本；
- 真人打开 bot 背包时，动作侧的库存写入被互斥阻断；
- 所有 mailbox、ledger、活跃动作、完成通知、证据和诊断都有硬上限。

因此，状态机不应设计成一个包含所有行为的巨型 FSM。BotPlayer 采用六个职责正交、通过
ID/generation/事件组合的状态层；P2 实现其中与可靠身体有关的前四层，P5/P7 再实现技能和
目标层。

## 2. 调研范围与可借鉴边界

### 2.1 原版与 NeoForge

| 资料 | 可借鉴内容 | BotPlayer 的采用方式 |
|---|---|---|
| [ServerPlayer 1.21.1 映射](https://mappings.dev/1.21.1/net/minecraft/server/level/ServerPlayer.html) | 玩家 Tick、视角、连接、死亡与维度权威入口 | 继续以 `BotServerPlayer extends ServerPlayer` 为身体，不创建自定义 Mob |
| [PlayerList 1.21.1 映射](https://mappings.dev/1.21.1/net/minecraft/server/players/PlayerList.html) | 登录、重生、在线实例替换 | 生命周期管理器保存稳定 handle/generation，动作按代际重新解析实例 |
| [NeoForge 交互管线](https://docs.neoforged.net/docs/1.21.1/items/interactionpipeline) | 玩家交互顺序、返回值和事件语义 | 放置、使用、破坏和实体交互走普通服务端玩家路径，不直接改世界 |
| [NeoForge Menu](https://docs.neoforged.net/docs/1.21.1/gui/menus/) | menu 是权威数据的视图，不应成为第二份库存 | bot menu 绑定真实原版 `Inventory`，客户端只使用占位容器布局 |
| [NeoForge GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/) | 在真实世界 Tick 中验证交互和结果 | P2 将生命周期、移动、交互和库存拆成独立批次 |

核心结论是：`FakePlayer` 适合事件上下文或测试替身，不适合作为需要登录、重生、
playerdata、区块跟踪和真人可见同步的完整长期玩家。原版生物的 `GoalSelector`、
`PathNavigation` 又建立在 Mob 身体模型上，不能直接成为 `ServerPlayer` 的总控制器。

### 2.2 成熟 Bot 与寻路项目

| 项目 | 值得借鉴 | 不能直接照搬 |
|---|---|---|
| [Baritone](https://github.com/cabaletta/baritone) | 目标与路径分离、路径成本、取消/停止、动态重算 | 其客户端控制与依赖边界不同；P2 只建立输入层，P4 才实现服务端权威导航 |
| [Mineflayer](https://github.com/PrismarineJS/mineflayer) | 高层 API、插件边界、事件驱动、能力模块化 | 它是网络客户端 bot；BotPlayer 是服务器内真实玩家，不能用协议模拟代替服务器权威校验 |
| [Mineflayer Pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder) | 静态/动态/组合目标、可替换移动策略 | 路径搜索结果不能直接驱动传送或绕过碰撞；必须落到 P2 输入和 P4 follower |

这些项目共同说明，“寻路算法”和“玩家身体执行”是两个系统。P2 先提供稳定、可取消、
可观察的输入和动作接口；P4 再在不可变局部世界快照上做有预算寻路。

### 2.3 具身智能研究项目

| 项目 | 值得借鉴 | BotPlayer 的安全改写 |
|---|---|---|
| [Project Malmo](https://github.com/microsoft/malmo) | mission、observation、action、reward/结束条件分离 | 用作 GameTest 场景与成功条件设计，不作为生产运行时 |
| [MineDojo](https://minedojo.org/) | 大规模多任务、统一观察/动作空间、可量化基准 | 能力矩阵按场景与证据验收，不用单个演示代表“完整会玩” |
| [Voyager](https://arxiv.org/abs/2305.16291) | 自动课程、可组合技能库、环境反馈、执行错误与自验证 | 保留技能/反馈思想；禁止运行模型生成代码，技能必须预注册、受审核且走动作防火墙 |

研究系统通常优化任务完成率或学习速度；长期多人服务器还必须额外处理权限、保护事件、
物品守恒、生命周期、资源预算、隐私、停服和版本迁移。这些不是模型提示词能够替代的。

### 2.4 许可证与代码边界

调研只用于理解公开结构和行为。本轮没有因调研直接引入第三方运行时依赖，也没有复制
外部源码或提示模板。未来引入寻路库、协议库、数据集、模型提示或技能包前，必须单独审查
许可证、版本兼容和服务端部署边界。

## 3. 历史能力审计快照

2026-07-27、P2 实现开始前，对
[原版能力矩阵](VANILLA_CAPABILITY_MATRIX_CN.md) 的 123 个能力项做过一次历史审计：

| 成熟度 | 当时数量 |
|---|---:|
| `NONE` | 111 |
| `KERNEL` | 12 |
| `BASIC` | 0 |
| `RELIABLE` | 0 |
| `VERIFIED` | 0 |

这组 `111 NONE / 12 KERNEL / 0 BASIC` 是**当时的基线快照，不是当前统计，也不是本轮
完成后的能力声明**。P2 已增加动作、移动、交互和 bot 自身背包实现，并通过本地严格
编译、140/140 单测和连续两轮 19/19 GameTest；只有在客户端手工验证及对应能力矩阵逐项
复核后，才能更新当前成熟度。
不能用“存在类”“有测试源码”或“路线图打勾”自动把能力提升为 `BASIC` 或 `VERIFIED`。

## 4. 六层可组合状态机

### 4.1 为什么不是一个巨型 FSM

生命周期、身体控制、原子动作、GUI 会话、技能和长期目标的时间尺度、失败语义与持久化
要求不同。把它们塞进一个 FSM 会造成状态组合爆炸，例如
“RESPAWNING_AND_MINING_AND_MENU_OPEN_AND_GOAL_PAUSED”。拆层后，每层只维护自己的
合法转换，并通过稳定 ID、generation、结构化结果和事件协调。

| 层 | 权威对象 | 典型状态 | 责任边界 | 首次阶段 |
|---|---|---|---|---|
| 1. 玩家生命周期 FSM | `BotLifecycleManager` / runtime handle | `SPAWNING → ACTIVE → DEAD → RESPAWNING → ACTIVE`，或 `DESPAWNING` | 当前权威玩家实例、generation、死亡/重生/卸载 | P1/P2 加固 |
| 2. 控制协调 FSM | 通道仲裁与输入 owner | 空闲、租约持有、被抢占、清理/隔离 | `MOVE/LOOK/HAND/INVENTORY/INTERACT` 等互斥、优先级和安全抢占 | P2-A |
| 3. 原子动作 FSM | `BotActionRuntime` | `QUEUED → VALIDATING → RUNNING → VERIFYING → 终态` | 单个动作的校验、副作用、验证、取消、超时和幂等 | P2-A～C |
| 4. 背包会话 FSM | `BotInventorySessionManager` | `OPENING → OPEN → CLOSING → CLOSED` | viewer 权限、距离、generation、一人写锁和 mutation gate | P2-D |
| 5. 技能运行 FSM | `SkillRuntime` | `READY/RUNNING/PAUSED/RECOVERING/SUCCEEDED/FAILED/CANCELLED` | 多动作组合、检查点、补偿、资源预留和局部恢复 | P5A |
| 6. Goal/计划 FSM | Goal/Plan 服务 | `PROPOSED/ACCEPTED/PLANNING/EXECUTING/BLOCKED/PAUSED/COMPLETED/ABANDONED` | 用户意图、承诺、DAG、跨重启恢复和长期优先级 | P7；P6 仅生成受限提案 |

### 4.2 原子动作的固定状态表

```text
QUEUED
  → VALIDATING
    → RUNNING
      → VERIFYING
        → SUCCEEDED
```

允许的异常终态是：

- `FAILED`：校验、执行、验证或内部协议明确失败；
- `CANCELLED`：调用者、生命周期或停服取消；
- `PREEMPTED`：更高优先级动作取得冲突通道；
- `STALE`：generation、实例或权威事实已经变化。

终态不可复活；重复幂等请求只能加入或重放 canonical 动作的结果，不能再次执行副作用。

### 4.3 跨层协议

1. 生命周期进入非活动状态前，同步关闭该 generation 的动作入口并清理输入。
2. 动作开始前以 `(botId, generation)` 解析 `PlayerList`、维度与 listener 中的同一权威实例。
3. 动作取得所需通道租约后才能开始副作用；高优先级抢占必须先清理被抢占者。
4. 背包会话打开前排空该 generation 的库存写动作；会话存在时 mutation gate 拒绝动作侧写入。
5. 动作只返回结构化 `ActionOutcome` 和有界证据，技能不能把“调用没有抛异常”当成成功。
6. P5 技能只组合 P2 原子动作；P6 模型只能提出已注册技能/工具调用；P7 目标层不直接操作玩家。

## 5. P2 重新定界

### P2-A：确定性动作脊柱

范围：

- 严格 `ActionEnvelope`、动作 ID、幂等键、botId、generation、deadline 和 `maxTicks`；
- 中央动作状态表、结构化失败码、结果与有界证据；
- 有界 mailbox、活跃集合、幂等 ledger 和 completion dispatcher；
- 控制通道、优先级、原子抢占和一次性 cleanup；
- 同 generation 生命周期同步关闭、停服 shutdown、清理失败后的安全 reset/隔离；
- `WAIT / LOOK_AT / STOP` 作为最小纵切片。

阶段门：

- 纯 Java 测试覆盖状态转换、重复、别名、抢占、取消、超时、迟到 generation、回调背压、
  cleanup 失败和重入生命周期；
- Minecraft 主线程接入完成；
- 旧实例/旧 generation 不能产生副作用；
- 编译、单元测试和相应 GameTest 取得可追溯结果。

### P2-B：输入与短程移动

范围：

- 有界前后/横向输入、跑、蹲、跳、视角和停止；
- 每个绝对服务器 Tick 只应用一次输入并运行一次玩家物理阶段；
- 普通碰撞、姿态、浅水移动、跳跃因果证据、卡墙/无位移失败；
- 取消、租约过期、死亡、卸载和抢占时主动清零输入。

不在 P2：

- A*、长距离寻路、动态重规划、门/梯子/脚手架策略和危险成本模型；这些属于 P4；
- 船、矿车、坐骑和鞘翅等高级移动；这些属于 P5C。

### P2-C：基础世界交互

范围：

- 选择快捷栏、使用/持续使用/释放物品；
- 使用方块、分阶段破坏与中止；
- 攻击、与实体交互；
- 丢弃选中物品、按明确 ItemEntity UUID 等待拾取；
- 维度、距离、视线、冷却、手中物品、目标方块/实体指纹等前置条件；
- 通过玩家路径执行，并以方块、实体生命、物品栈和 ItemEntity 的前后证据验证。

不在 P2：

- 箱子、木桶、潜影盒等通用世界容器的点击事务；
- 工作台、熔炉等配方/工作站流程；
- 任意模组自定义 menu。

这些边界分别进入 P5A、P5B 和 P8，见第 7 节。

### P2-D：bot 自身背包会话

范围：

- 真人以空主手、主手交互打开 bot 自身背包；副手和持物品交互不误触；
- 41 个 bot 真实库存槽位（盔甲 4、副手 1、主背包 27、快捷栏 9）；
- 加上 viewer 自己的 36 个槽位，共 77 个 menu 槽位；
- 绑定真实 bot `Inventory`，不复制第二份权威库存；
- owner 或服务器 OP 写权限、同维度/存活/距离检查；
- 每 bot 单 viewer 写锁、每 viewer 单会话、generation/nonce token；
- 打开前排空会改库存的动作；打开期间 mutation gate 拒绝动作写入；
- Shift 移动、装备槽规则、关闭确认和总物品数量守恒；
- 死亡、重生、换维度、超距、退出、menu 替换和停服强制关闭。

这里只实现“查看/编辑 bot 自己的玩家背包”，不是通用容器自动化。

### P2-E：集成、故障注入与验收

范围：

- 将动作、输入、交互、背包会话与生命周期接到同一个服务端 Tick；
- 虚拟连接 callback、teleport/keepalive 记账和常量空间诊断；
- 生命周期转换、generation、两阶段无客户端玩家 Tick 和旧实例拒绝；
- 生命周期、移动、世界交互、背包四组 NeoForge GameTest；
- 严格 Java 编译警告、完整单元测试、CI GameTest 门禁；
- 清理失败、回调阻塞、容量耗尽、重复取消、死亡重入和停服竞态的故障测试。

退出判定必须同时列出“已实现、已通过、未覆盖”。本地存在测试源码但尚未执行、CI 尚未
到终态或只在开发客户端运行，都不能写成 P2 已验证。

## 6. P2 必须保持的不变量

1. **实例权威**：只操作 `PlayerList`、当前维度和 listener 共同认可的当前实例。
2. **代际隔离**：旧 generation 的请求只能得到 `STALE`/取消，不能重定向到新身体。
3. **单一终态**：动作只完成一次，completion callback 不能在服务器主线程运行。
4. **幂等副作用**：同一幂等键不能偷偷改变动作 ID、generation、参数或目标。
5. **有界资源**：所有输入、队列、等待者、诊断和证据都有容量。
6. **普通玩家路径**：不使用传送、`setBlock` 或直接增删库存来伪装动作成功。
7. **验证后成功**：副作用与世界证据不一致时必须失败。
8. **取消即清理**：取消、抢占、死亡、卸载和停服后不残留移动、挖掘或持续使用。
9. **背包互斥**：真人写会话和动作侧库存写不能并发。
10. **失败默认关闭**：未知、超时、越权、超距、视线不明、容量不足或 cleanup 不安全时拒绝。

## 7. P3–P10 阶段门

| 阶段 | 进入条件 | 核心交付 | 不可提前宣称 |
|---|---|---|---|
| P3 感知与世界模型 | P2 身体动作可验证 | 权威事件、感知预算、不可变 observation、revision、玩家活动推断 | 能聊天不代表能感知；扫描未加载世界不算观察 |
| P4 导航与安全反射 | P2 输入稳定，P3 提供局部快照 | 有预算路径搜索、follower、动态重算、stuck 恢复、L0 安全抢占 | P2 固定短程移动不等于寻路 |
| P5A 技能运行时与首条生存闭环 | P2/P4 动作与导航可恢复，P3 结果可观察 | 技能 FSM、检查点、DAG、补偿；**最小原版世界容器驱动**；木头到铁工具闭环 | 单个脚本或直接改库存不算技能 |
| P5B 生产与日常生活 | P5A 通过且菜单事务稳定 | **更广泛的原版容器与工作站**、制作、熔炼、农业、交易和维护 | P2 bot 自身背包 GUI 不等于箱子/工作站自动化 |
| P5C 运输、进程与高级战斗 | P4/P5A 安全与资源闭环 | 载具、下界/末地、远程战斗、Boss 与返程 | 单场战斗演示不代表完整游戏进程 |
| P5D 建筑与红石 | P3 revision、P4 导航、P5 技能恢复 | 蓝图、预算、分层施工、差异修复与基础红石 | 放置一个方块不等于建造能力 |
| P6 DeepSeek 与聊天 | P5A 本地闭环已通过；ADR-0010/0012 边界保持 | client-sponsored Provider、结构化工具、防火墙、预算、澄清与汇报 | 保存 API Key 不等于 AI 已接通；模型不能逐 Tick 控制 |
| P7 长期记忆与目标 | P3 事件来源、P5 检查点、P6 对话边界稳定 | 目标/计划 FSM、承诺、来源化记忆、迁移和删除 | 文本摘要不能替代权威事实 |
| P8 模组适配 | 原版通用接口稳定 | C0–C3、环境指纹、声明式包、`BotMenuAdapter`；**模组/自定义 menu** | 未知 GUI 默认询问或拒绝，不能盲点 |
| P9 多 bot 协作 | P7 目标与锁、P5 资源事务稳定 | task board、资源/区域锁、交接、死锁检测 | 多个独立 bot 同时在线不等于协作 |
| P10 发布硬化 | REQUIRED 能力已在更早阶段实现 | 性能、soak、兼容、安全、迁移、发布与回归 | P10 不允许首次补核心玩法 |

### 7.1 容器边界的固定归属

为避免把“能打开 bot 背包”误写成“会操作所有容器”，阶段归属固定如下：

| 范围 | 阶段 |
|---|---|
| bot 自己 41 格玩家库存的真人 GUI、77 槽 menu、单 viewer 写锁 | P2-D |
| 生存闭环所需的最小原版世界容器读写事务 | P5A |
| 箱子/木桶/潜影盒、制作/熔炉及更广泛原版工作站 | P5B |
| 标准接口可描述的模组容器 | P8 C1 |
| 自定义 menu/机器语义的版本化适配器 | P8 C3 |

## 8. 完整 AI 玩家仍需完成的工作

即使 P2 全部通过，BotPlayer 也只是获得了可信的“身体”，仍不是完整 AI 玩家。后续至少
还需要：

- 对自身、视线、声音、局部世界和其他玩家活动的有限感知；
- 服务器权威世界模型、事实来源、revision 和失效；
- 动态寻路、安全反射、导航恢复和风险成本；
- 可恢复技能、原版容器/制作/生产、战斗、建筑和完整游戏进程；
- 受控 DeepSeek 接入、聊天、澄清、工具防火墙和费用/速率预算；
- 长期目标、承诺、记忆、隐私、迁移和删除；
- 模组 C0–C3 能力、版本重验证和未知机制拒绝；
- 多 bot 资源锁、任务交接和死锁/循环对话防护；
- 专用服、客户端、保护模组、多 bot、长时间 soak 和发布级迁移验证。

P2 的价值在于让这些能力未来都只能通过同一个可取消、可审计、可验证的玩家动作边界进入
世界，而不是让 P2 自己冒充完整智能。

## 9. 文档与验收纪律

- 路线图复选框表示代码/测试来源是否存在，不自动表示阶段已通过；
- `clean build`、单元测试、GameTest、客户端手工测试、专用服和 soak 必须分别报告；
- 能力矩阵成熟度只能依据该能力自己的证据更新；
- 外部 Maven/工具链环境补丁不是仓库产品代码，不能写进发布功能；
- 每次 P2 状态变化要同步 README、实现状态、配置、更新日志和完成报告；
- DeepSeek、记忆、寻路和通用容器仍未实现时，任何界面或凭据功能都不能暗示已经具备。
