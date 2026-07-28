# BotPlayer P2 完成验收报告

> 报告日期：2026-07-28
>
> 验收分支：`agent/p2-complete`
>
> 报告状态：P2 实现与本地/远端自动化退出门已通过
>
> 发布状态：未发布

本文按“已编码事实、验证证据、未覆盖边界”报告 P2，不用“有源码”等价替代“已通过”。
验收分支见 [PR #3](https://github.com/GreyTaiWolf/BotPlayer/pull/3)，远端标准构建见
[GitHub Actions Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722)。

## 1. 当前判定

| 项目 | 当前状态 | 判定依据 |
|---|---|---|
| P2-A 确定性动作脊柱 | 本地已验证 | 严格编译、动作/运行时单测和 Minecraft 集成测试 |
| P2-B 输入与短程移动 | 本地已验证 | 五项普通玩家物理 GameTest 连续两轮通过 |
| P2-C 基础世界交互 | 本地已验证 | 六项交互 GameTest 连续两轮通过 |
| P2-D bot 自身背包会话 | 本地已验证 | 六项库存/会话 GameTest 连续两轮通过 |
| P2-E 集成与硬化 | 自动化门已通过 | 140 项单测、19 项 GameTest 双跑、干净构建及远端 CI 全绿 |
| 整体 P2 退出门 | **已通过（自动化验收范围）** | 客户端手工、专用服和长时 soak 继续单独标记 |

## 2. 已编码内容

### 2.1 生命周期与权威实例

- `BotRuntimeHandle` 按 bot 在服务器会话内稳定保留，并维护正 generation；
- 死亡重生的新 `BotServerPlayer` 绑定新 generation，旧 generation 立即失效；
- 动作以 `(botId, generation)` 重新解析 `PlayerList`、当前维度与 listener 的权威实例；
- 生命周期转换有中央合法状态表和有界历史；
- 死亡、卸载、换维度和停服会同步取消/清理相关动作与背包会话；
- 无客户端玩家采用受管理的两阶段 Tick，避免重复 `doTick()` 或旧网络位置回写；
- 虚拟连接记录丢弃包、拒绝包、callback、keepalive/teleport 处理和首个关闭原因，诊断为
  常量空间且不记录包内容。

### 2.2 P2-A 动作运行时

- 动作状态机：
  `QUEUED → VALIDATING → RUNNING → VERIFYING → SUCCEEDED`；
- 异常终态：
  `FAILED / CANCELLED / PREEMPTED / STALE`；
- 严格动作 envelope、origin、优先级、失败码、结果和有界 evidence；
- 有界提交/取消 mailbox、每 Tick 处理预算、活跃动作容量；
- 幂等 ledger、canonical envelope、别名加入与终态重放；
- `MOVE / LOOK / MAIN_HAND / OFF_HAND / INVENTORY / INTERACT / CHAT` 通道仲裁；
- 高优先级抢占、deadline、`maxTicks`、一次性 cleanup 和 generation quarantine；
- 有界 completion dispatcher，callback 不在服务器主线程执行，并为取消保留容量；
- shutdown、生命周期重入、阻塞 callback、清理失败和安全 reset 的失败关闭路径；
- `WAIT / LOOK_AT / STOP` 最小动作纵切片。

### 2.3 P2-B 输入与短程移动

- `PlayerInputController` 以 generation 和 action owner 绑定输入租约；
- 前后/横向输入、跑、蹲、跳和主动停止；
- 每个绝对服务器 Tick 只允许一次输入变更/应用；
- 取消、租约过期、死亡、卸载和抢占时清零输入；
- 使用普通玩家物理与碰撞，不通过传送完成移动；
- 以位置、速度、落地/离地、姿态、碰撞和水中状态形成验证证据；
- 已建立一格跳跃、静止蹲姿、浅水前移、撞墙不穿透和空中跳跃拒绝的 GameTest 来源。

### 2.4 P2-C 基础世界交互

已建立动作类型：

- `SELECT_HOTBAR`
- `USE_ITEM`
- `RELEASE_USE`
- `USE_ON_BLOCK`
- `BREAK_BLOCK`
- `ATTACK_ENTITY`
- `INTERACT_ENTITY`
- `DROP_SELECTED`
- `PICKUP_WAIT`

交互前置条件包括维度、目标 UUID/类型或方块状态、命中面/局部坐标、距离、视线、冷却和
手中物品指纹。副作用走普通服务端玩家交互路径；破坏支持分阶段进度和 STOP/ABORT；放置、
破坏、攻击、丢弃和拾取以世界前后状态验证，不用直接改方块或直接增删库存伪装成功。

已建立以下 GameTest 来源：

- 放置并验证手中物品守恒；
- 分阶段破坏与世界证据；
- 保护拒绝后方块保持；
- 攻击产生可验证伤害；
- 丢弃生成 ItemEntity 且数量守恒；
- 拾取等待绑定明确 ItemEntity UUID。

### 2.5 P2-D bot 自身背包

- 空主手、主手右键 bot 的打开入口；副手与持物品交互不误触；
- 41 个 bot 真实库存槽位：盔甲 4、副手 1、主背包 27、快捷栏 9；
- 36 个 viewer 库存槽位，总计 77 个 menu 槽位；
- 服务端绑定 bot 的真实原版 `Inventory`，客户端使用布局占位容器；
- owner 或服务器 OP 写权限；
- 同维度、双方存活和可配置距离检查；
- 每 bot 单 viewer 写锁、每 viewer 单会话、generation/nonce token；
- `OPENING → OPEN → CLOSING → CLOSED` 会话状态机和关闭墓碑；
- 打开前同步排空该 generation 的动作；会话期间 mutation gate 拒绝动作写库存；
- Shift 移动、盔甲/副手语义、关闭确认与总物品数量守恒；
- 死亡、重生、换维度、超距、viewer 退出、menu 替换和停服关闭路径；
- 客户端 screen 和 NeoForge `MenuType` 注册。

已建立入口、副手、权限、双 viewer、距离/生命周期、77 槽 Shift 移动和动作 mutation gate
的 GameTest 来源。

### 2.6 配置与测试门禁

新增 server 配置：

| 键 | 默认值 | 范围 |
|---|---:|---:|
| `actions.mailboxCapacity` | `1024` | `2..65536` |
| `actions.ledgerCapacity` | `4096` | `1..65536` |
| `actions.commandsPerTick` | `128` | `1..4096` |
| `actions.activeCapacity` | `512` | `1..16384` |
| `actions.completionCapacity` | `2048` | `2..65536` |
| `inventory.viewDistance` | `8.0` | `1.0..64.0` |

Java 编译警告已提升为错误，`runGameTestServer` 已接入 CI 工作流；本地编译、测试与
打包以及远端标准构建均已通过，结果见下节。

## 3. 验证状态

### 3.1 已具备的验证来源

- 动作契约、状态表、ledger、通道仲裁、运行时并发/重入/容量测试；
- 输入控制、移动动作和交互 DTO/前置条件纯 Java 测试；
- 库存布局、会话、锁、关闭和 mutation gate 纯 Java 测试；
- 虚拟连接常量空间 telemetry 测试；
- 生命周期、移动、交互和库存四组 NeoForge GameTest；
- P2 GameTest structure fixture；
- 严格 Java 编译和 CI GameTest 配置。

“测试源码存在”不等于测试已通过。

### 3.2 实际执行结果

| 验证项 | 结果 |
|---|---|
| Java 21 严格 `compileJava` / `compileTestJava` | 通过；`-Xlint:all -Werror` |
| 完整 `test` 数量与结果 | 140/140 通过，0 failed、0 skipped |
| `runGameTestServer` 数量与结果 | 同一持久 `run` 世界连续两次 19/19 通过；每轮含 100 次死亡—重生 |
| `clean build` 与最终 JAR | 通过；`botplayer-0.1.0-alpha.2.jar`，SHA-256 `b445ffd757d418ea0137677ad938166d670403a58d76a519e8005d84d31ffef5` |
| 推送后的 GitHub Actions | [Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722) 成功；`clean build runGameTestServer` 与 JAR 上传均通过 |
| 客户端 screen 手工交互 | 未验证 |
| 独立专用服务器 | 未验证 |
| 多 bot 长时间 soak / MSPT / 内存 | 未验证 |

本地运行环境无法访问 NeoForge/Mojang 依赖站点，因此通过只读本地依赖镜像完成离线构建；
GameTest 使用本地资源索引并跳过 `downloadAssets`。这些环境补丁没有进入仓库；远端 CI
已经使用仓库声明的正式依赖和标准任务重新验证成功。

## 4. 明确未覆盖的边界

### 4.1 不是 P2 的能力

- 长距离 A*、动态重规划、门/梯子/脚手架、载具、鞘翅和完整危险导航；
- 感知、语义事件、世界模型和玩家活动理解；
- 技能 FSM、DAG、制作/熔炼、生存闭环、战斗策略和建筑；
- DeepSeek Provider/HTTP、聊天、工具防火墙和预算；
- 长期记忆、目标恢复和多 bot 协作；
- 模组注册表知识、技能包和自定义 menu 适配器。

### 4.2 容器边界

P2-D 只处理 bot 自己的玩家库存。以下能力仍未实现：

- P5A：首条生存闭环所需的最小原版世界容器事务；
- P5B：箱子/木桶/潜影盒、制作/熔炉和更广泛原版工作站；
- P8：模组标准容器与自定义 menu/机器适配器。

### 4.3 仍需专项验证

- autoload、roster schema 迁移、完整 owner/trusted/observer ACL；
- 白名单、封禁、离线 profile 和改名迁移冲突；
- 保护/领地/PVP/反作弊模组的兼容矩阵；
- 主世界/下界/末地的完整动作与 menu 回归；
- `keepInventory`、断线、重启恢复和存档损坏故障注入；
- 100 次生成—卸载和多 bot 长时间运行；100 次死亡—重生已由 GameTest 连续两轮通过；
- 客户端视觉、键鼠交互、不同 UI 缩放与语言；
- 纯服务端安装和客户端/服务端版本不一致行为。

## 5. 最终退出清单

主线负责人只有在以下项目都有明确证据后，才能把“整体 P2”改为通过：

- [x] 严格 Java 编译通过；
- [x] 全部单元测试通过并记录数量；
- [x] 全部 P2 GameTest 通过并记录数量；
- [x] `clean build` 产出可安装 JAR；
- [x] 分支已推送，远端 CI 到达绿色终态；
- [x] README、更新日志、实现状态、配置和路线图与本地结果一致；
- [x] 未运行的客户端、专用服和 soak 仍明确标记为未验证；
- [x] 通用世界容器没有被误写成 P2 已实现；
- [x] P2 通过没有被误写成“完整 AI 玩家”或“DeepSeek 已接通”。

## 6. 发布回写

```text
提交：
- P2 代码（远端）：28e137b9127f9d49c2c4aac848dcc27ec7e78936
- 调研与本地验收文档（远端）：fe11fe6e9cd3b3a85267b584c3030a34962db2b4
- 本地对应代码检查点：a912486
- 本地对应文档检查点：e1ea26c

审阅：
- PR: https://github.com/GreyTaiWolf/BotPlayer/pull/3

验证：
- compileJava/compileTestJava: 通过（-Xlint:all -Werror）
- test: 140/140
- GameTest: 19/19，连续两轮
- clean build: 通过，botplayer-0.1.0-alpha.2.jar
- GitHub Actions: Build #18 成功
- CI: https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722

仍未验证：
- 客户端 screen
- 独立专用服务器
- 多 bot soak
```

P2 通过只代表 BotPlayer 获得了一个可信任、可取消、可验证的玩家身体。距离完整 AI 玩家
仍需按 P3–P10 完成感知、导航、技能、对话、记忆、模组适配、协作和发布硬化。
