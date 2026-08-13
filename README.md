# BotPlayer

[![Build](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml)

BotPlayer 是面向 Minecraft Java 的真实服务端玩家 AI 框架。项目首先支持
Minecraft 1.21.1 + NeoForge，后续版本在 1.21.1 架构稳定后再迁移。

> **当前状态：P2、P3 与 P4 自动化退出门已通过；仍不是正式版本。**
>
> 当前代码已建立真实 `BotServerPlayer`、generation 隔离、确定性动作运行时、短程输入、
> 基础世界交互和 bot 自身背包 GUI；P2 的 140 项单测与 19 项 GameTest 连续两轮全绿，
> GitHub Actions 也已全绿。P3 新增有限感知、权威/认知事件、短期世界事实和玩家活动
> 推断。P4 新增已加载世界中的有界分段导航、真实输入路线跟随、每 Tick L0 安全反射、
> 真实玩家伤害/效果兼容基线和默认关闭的受限 Terrain Assist；Build #97 已通过全仓
> 55/55 GameTest，其中 P4 直接场景 28 个。P5 以
> [Draft PR #6](https://github.com/GreyTaiWolf/BotPlayer/pull/6) 作为远端验收载体；
> [Build #163](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30897970406) 已通过
> Java 21 `clean build`、Gradle `test`、91/91 NeoForge GameTest 和 JAR 上传。当前源码
> 静态计数为 437 个 JUnit `@Test` 方法、34 个 P5 GameTest、378 个 Java 源文件；这些是
> 源码计数，不是 CI 日志逐项报告的测试执行数。通用 `InventoryMenu SWAP_SEQUENCE` 已支持
> 1～16 次点击、最多 8 个槽位，每 Tick 只执行一次点击；真实五步场景验证了跨 Tick
> `PENDING`、固定安全端点、旧 owner/新 claimant 双 ticket 阻塞和精确 progress revision。
> 通用 equipment/offhand 槽仍返回 `UNSUPPORTED`，盔甲继续走独立专用路径，不能据此计入
> P5A 退出门。新增死亡纵切会在原版实际消费背包前发布耐久 tombstone，只有主副本、备份
> 与 successor 的精确交接全部提交后才激活新 generation；这优先防复制，但不承诺掉落
> exactly-once。P5B 仅新增一个受限的原版容器技能运行时切片：严格白名单内的普通单箱/双箱、
> 木桶和原版潜影盒可走真实 3×9/6×9 原版 menu 点击事务；本地 Java 21/NeoForge 实跑尚未完成，不能
> 宣称已具备通用容器、工作站或完整生存能力。项目仍没有跨 menu 统一事务、聊天、通用
> DeepSeek 或长期记忆。P6-R1 仅有默认关闭的固定本地只读审阅往返代码，Java 21/CI 尚待
> 验证，且绝不进入世界动作。保存 Key 不代表 AI 已经接通，方块观察也不代表能读取箱子内容。
> 请以
> [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md) 为准，不要把路线图中的目标当成已完成。

## 设计目标

BotPlayer 最终要成为由 AI 控制的长期服务器伙伴，而不是换皮生物或只会执行命令的 NPC：

- 主体直接继承原版 `ServerPlayer`，不注册自定义玩家实体；
- 使用原版玩家背包、装备、生命、饥饿、经验、死亡、重生、维度和 playerdata；
- 真人客户端看到标准玩家模型、动作、装备和名称；
- DeepSeek 负责聊天、意图理解和高层规划；
- Java 动作与技能系统负责真正移动、挖掘、放置、战斗、制作和验证结果；
- 通过感知、世界模型和记忆理解玩家正在做什么、附近发生了什么、以前发生过什么；
- 通过内部 Java 技能和受审核的外部声明式技能包逐步理解原版及模组玩法；
- 所有动作服从服务器权限、保护事件、安全策略和资源预算。

完整定义见
[架构、编码规范与 P0–P10 路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## 当前技术基线

| 项目 | 当前值 |
|---|---|
| Minecraft | `1.21.1` |
| NeoForge | `21.1.244` |
| Java | `21` |
| ModDevGradle | `2.0.142` |
| Gradle Wrapper | `9.2.1` |
| 模组 ID | `botplayer` |
| 开发版本 | `0.2.0-alpha.1` |
| 发布状态 | 尚未发布，仅开发构件 |

版本号是开发标识；P2–P4 自动化验收通过不等于正式发布或完整 AI 玩家。

## 已经实现

- `BotServerPlayer extends ServerPlayer`；
- 本地 `EmbeddedChannel` 虚拟连接；
- bot 专用 `BotGamePacketListener`；
- 登录时替换 packet listener 的窄 Mixin；
- 重生时保持 `BotServerPlayer` 类型的窄 Mixin；
- 只在原版死亡真正完成后进入重生流程；
- `keepInventory=false` 且原版实际进入背包消费时，先发布 V2 pre-drop tombstone；旧 body
  以空背包死亡态双保存、刷盘并回读 `.dat/.dat_old`，successor 再按精确经验 handoff
  双保存后才进入 ACTIVE；已移除 predecessor 永久禁存，marker 到世界掉落保存之间仍有
  选择防复制而可能丢物的崩溃窗口；
- 临时、确定性的名字派生 UUID；
- schema v1 持久 roster、规范名字、稳定 bot/player UUID、owner 和服务器实例 ID；
- 只有持久 owner 可进入客户端凭据配置；
- `/botplayer settings <name>` 打开客户端本地 API Key 设置界面；
- 客户端可创建/替换凭据 profile、绑定/解绑 bot；每 bot 使用独立 agentId，profile 删除
  尚未实现；
- P6-R1 owner 手动只读审阅往返已编码但 Java 21/CI 尚待验证：物理客户端只在本地
  `reviewOnly.enabled=true` 后构造固定 `deepseek-chat` review Provider；默认关闭，回传仅为
  零参数确认和安全数字摘要，绝不执行 Action、Skill 或世界变更；
- `PlayerListMixin` 除登录 listener 与重生类型包装外，还为 P5 异常隔离提供一次性
  no-save `PlayerList.remove` 保存包装；事务期 fence 与已移除旧 body 的永久 no-save
  poison 分离，Build #163 已验证迟到旧 body 不能覆盖 successor；跨 menu `clicked()`
  故障注入仍待补；
- 既有 playerdata 检测与保存位置保留；
- 服务器线程生命周期管理；
- 自动重生、维度切换基础路径和区块跟踪刷新；
- 稳定 runtime handle、generation 与旧实例拒绝；
- 生成失败回滚、幂等断开和停服异常隔离清理；
- 有界动作 mailbox、幂等 ledger、通道仲裁、取消/抢占/超时和结构化结果；
- `WAIT / LOOK_AT / MOVE_INPUT / JUMP / STOP` 与普通玩家输入/物理适配；
- 选择快捷栏、使用/释放物品、使用方块、分阶段破坏、攻击/实体交互、丢弃与拾取等待；
- P5 有界 Skill/DAG/TTL 预留底座、主动进食，以及扫描 carried inventory `0..35` 的
  确定性基础盔甲升级；
- 原生 `InventoryMenu` 41 槽完整快照、前后指纹、物品多重集守恒与
  generation/replacement 绑定清理；通用 `SWAP_SEQUENCE` 支持 1～16 次点击、最多 8 个
  槽位和逐 Tick 一击，盔甲热栏单击及主背包 2～3 步路径保持独立；
- 空主手、主手右键打开 bot 自身 41 格真实库存，77 槽 menu、单 viewer 写锁、距离和
  lifecycle 校验、动作 mutation gate；
- 背包 screen 使用 `176×256` 的上下堆叠原版玩家风格：上方是 bot 的盔甲、副手、
  3D 玩家模型、主背包和快捷栏，下方是 viewer 物品栏；2×2 合成区域隐藏且没有可交互槽位，
  bot 当前快捷栏选择会同步高亮；
- 背包背景、槽位和 HUD 选中框在运行时引用 Minecraft 1.21.1 原版资源，不在模组中复制或
  打包 Mojang PNG，因此兼容替换这些原版 GUI 资源的资源包；
- 虚拟连接 callback 与 keepalive/teleport 诊断记账；
- 权威 `AuthorityEvent` 与每 bot generation 的 `PerceivedEvent` 双平面；普通 audit、
  spatial projection、声音审计三个权威环共享唯一序号，非声音/声音认知分环共享
  generation-local 序号；
- `ActionOutcome`、NeoForge post-state 验证事件与原版定向声音包的 P3 收集入口；
  break/place/toss 候选冻结 generation 且私有 `routing.*` 不进入认知载荷，critical
  outcome ingress 与 P2 最大吞吐对齐（成功 break 按 2，其他终态按 1）；
- 自身、背包、注视、附近实体、威胁、局部方块和声音有限传感器，不强制加载区块；
- SELF 必须来自快照当前 Tick，41 槽完整背包超过 20 Tick 未刷新时撤下快照；威胁、
  视觉和普通实体使用子配额，视觉异常不伪造未命中；
- 相互独立的权威投影预算与公开传感器预算；后者实行每 bot/全局限额和 EWMA MSPT
  降级，并产出 AI-safe 不可变 `ObservationSnapshot`；
- 快照只暴露 generation-local 认知水位与本 bot 分类预算，不暴露 authority/global
  counters；`SELF/VISUAL` actor 身份按通道最小披露，视觉/听觉历史积压 fail-closed；
- `SELF/DIRECT` 同步可靠路由，空间事件从独立 authority projection ring 的当前尾部
  有界投影；定向洪泛不会挤出空间候选，定向声音按 generation 公平份额和 round-robin
  进入独立声音认知环；
- 服务器内部 scoped revision、运行时短期事实，以及已感知变化/TTL 的可溯源失效；
- 基于认知事件的确定性玩家活动推断、证据引用、置信表达与管理纠正；
- 活动窗口每 Tick 严格淘汰过期证据，单次按 actor 聚合并优先保留新近候选；压力分级
  actor 上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，单独一次 `use_on_block`
  不会被猜成 building；
- `/botplayer perception inspect|correct` 管理诊断入口；
- 不可变已加载世界运动快照、有界分段 A*、真实玩家输入 follower、方块 revision
  失效重算与有限 stuck 恢复；
- 跳跃、木门、浅水、梯子基础路线，以及低生命/食物的长途请求拒绝和低食物停跑；
- 每 Tick 有界 `SafetyFrame` 与 incident FSM；悬崖、燃烧、溺水、来袭箭、已点燃 TNT、
  敌对目标等危险可关闭背包、挂起导航并抢占普通输入；
- 原版僵尸目标与近战伤害、护甲减伤、饥饿/exhaustion、药水/效果/属性、动态
  `DamageType` 和标准 NeoForge 玩家 Tick 对真实 Bot 身体生效；
- 默认关闭的 Terrain Assist；只有请求 policy 与服务端配置同时允许时，才可在白名单、
  工具、支撑、库存、保护事件和单次预算约束下挖掘短通道或搭建简单短桥；
- `/botplayer navigation go|stop|inspect` 与 `/botplayer safety inspect` 管理入口；
- P2 生命周期、移动、交互和库存 GameTest 来源；
- `/botplayer spawn|remove|list`；
- NeoForge server 配置；
- GitHub Actions Java 21 构建与 GameTest 门禁配置。

以上 P2–P4 项已通过自动化退出门。完整证据见
[P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)、
[P3 完成验收报告](docs/P3_COMPLETION_REPORT_CN.md)和
[P4 完成验收报告](docs/P4_COMPLETION_REPORT_CN.md)。

## 尚未实现

- 自动恢复、trusted/observer ACL 与完整数据迁移；
- 背包 screen 的多语言、资源包与 GUI Scale 组合专项验收、独立专用服和多 bot 长时间 soak；
- 跨未加载区块/维度的长期路线、船/矿车/坐骑/鞘翅、复杂水流、脚手架和藤蔓；
- 自动寻找/生产食物与完整补给闭环；主动进食、盔甲专用路径和通用
  `InventoryMenu SWAP_SEQUENCE` 已由 Build #137 运行验证；主动用药/解毒、正式反击/
  持盾、工具/副手仍未实现；
- 除受限 P5B 白名单切片外的通用世界容器、工作站与制作/熔炼流程；
- 独立专用服与多 bot 性能验证；
- 持久世界模型、长期来源化记忆和自然语言“刚才发生了什么”对话；
- 战斗策略、建造和生存技能；
- 通用 DeepSeek Provider/HTTP、聊天、工具防火墙和预算；P6-R1 的窄本地审阅代码不代表这些
  能力已经完成或通过 Java 21/CI 验证；
- 分层长期记忆、目标恢复和模组适配；
- 多 bot 协作与正式发布级性能验证。

## 快速开发验证

### 前置条件

- 64 位 JDK 21；
- Git；
- 能下载 Gradle、NeoForge 和 Maven 依赖的网络环境。

### 构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
./gradlew --no-daemon clean build
```

Windows PowerShell/CMD 使用：

```bat
gradlew.bat --no-daemon clean build
```

构件位于 `build/libs/`。在当前开发阶段，规定的测试拓扑是客户端、服务端安装同一 JAR；
客户端 screen 与独立专用服务器行为尚无完整自动验收，纯服务端安装也尚未验证。P2
背包 screen 包含客户端代码。

开发基线是仓库默认 `main`。功能分支应从最新 `main` 创建。

### 开发运行

```bash
./gradlew runClient
./gradlew runServer
```

P2 已加入生命周期、移动、交互和库存 GameTest；P3 新增感知测试；P4 新增导航、安全、
饥饿、仇恨、伤害/效果兼容和 Terrain Assist 测试：

```bash
./gradlew --no-daemon runGameTestServer
```

本地结果为 140/140 单元测试和同一持久世界连续两轮 19/19 GameTest；远端
[GitHub Actions Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722)
也通过了标准 `clean build runGameTestServer`，完整证据见
[P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)。

P3 提交 `38851d1791b84e73705b302be8438e441c3f26ff` 由
[PR #4](https://github.com/GreyTaiWolf/BotPlayer/pull/4) 的
[GitHub Actions Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181)
使用 Temurin Java 21.0.11 执行
`./gradlew --no-daemon clean build runGameTestServer`。`compileJava`、
`compileTestJava`、Gradle `test`、clean build 与 JAR upload 全部通过；GameTest 日志
明确报告 `All 27 required tests passed`，其中 P3 batch 为 8 tests。当前源码静态计数为
P3 新增 43 个 `@Test` 方法、全仓 183 个；这是源码计数，不是 CI 日志直接报告的通过数。
上传 [artifact `botplayer-neoforge-1.21.1`](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181/artifacts/8702261459)
ID 为 `8702261459`，大小 `653364` bytes，SHA-256
`90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac`。

P4 提交 `9fec0388c36870248a204d7ff21b1b663b62bebf` 由
[PR #5](https://github.com/GreyTaiWolf/BotPlayer/pull/5) 的
[GitHub Actions Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204)
使用 Temurin Java 21.0.11 执行同一完整命令。严格编译、Gradle `test`、clean build、
JAR upload 均通过；GameTest 日志明确报告 `All 55 required tests passed`，其中 P4
直接场景为 28 个。当前源码静态计数为全仓 200 个 JUnit `@Test` 方法；该数字是源码计数，
不是 CI 日志打印的执行数。构件 ID 为 `8721162398`，大小 `838883` bytes，SHA-256
`b36a69f607e4f0e028e2afff15946a03bddd64004638c2d64d479c704706ddcd`。

P5 当前候选由
[GitHub Actions Build #163](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30897970406)
使用 Temurin Java 21 执行同一完整命令。严格编译、Gradle `test`、clean build、JAR 上传
均通过；日志明确报告 `All 91 required tests passed`，实际运行 40 个 batch。当前源码
静态计数为 437 个 JUnit `@Test` 方法、34 个 P5 GameTest、378 个 Java 源文件；真实二次
服务器启动、断电、跨平台目录刷盘、独立专用服和多 Bot soak 尚未验收。

更完整的步骤见：

- [安装与当前用法](docs/INSTALLATION_AND_USAGE_CN.md)
- [开发指南](docs/DEVELOPMENT_CN.md)
- [配置说明](docs/CONFIGURATION_CN.md)

## 当前命令

`spawn`、`list`、`remove` 需要达到 `permissions.commandPermissionLevel`，默认是
`2`。P3 `perception`、P4 `navigation/safety` 与 P5 `skill` 管理命令固定要求原版权限
等级 `2`，不随该配置降级。
`settings` 不要求 OP 等级，但只能由 roster 中记录的精确 owner 对活动 bot 执行；OP
也不能配置别人的 bot。

```text
/botplayer spawn <name>
/botplayer list
/botplayer remove <name>
/botplayer settings <name>
/botplayer perception inspect <name>
/botplayer perception correct <bot> <actor> <activity>
/botplayer navigation go <name> <x> <y> <z>
/botplayer navigation stop <name>
/botplayer navigation inspect <name>
/botplayer safety inspect <name>
/botplayer skill equip-armor <name>
/botplayer skill inspect <name>
```

感知 `inspect` 有界显示活动 bot 的最新快照、置信活动/generation-local 证据序号和最近
短期事实；`correct` 将对在线玩家 actor 的活动纠正追加为证据事件。允许的 activity 是
`idle|moving|exploring|mining|building|combat|farming|crafting|smelting|none`。这两个
命令是管理诊断入口，不代表 bot 已能聊天或回答自然语言问题。`navigation go` 只使用
不挖掘、不搭桥的 `safeDefault()`；导航/安全 `inspect` 输出有界运行状态，不提供普通玩家
任务或 AI 技能入口。`skill equip-armor` 会手动启动扫描 carried inventory `0..35` 的
基础盔甲升级；
`skill inspect` 只显示当前或最近一条 P5 生存技能 run 的 generation、状态、revision、
操作序号与安全摘要。主动进食、盔甲专用路径，以及 1～16 步通用
`InventoryMenu SWAP_SEQUENCE` 已由 Build #137 运行验证；通用 equipment/offhand 仍拒绝。

名称必须是 1–16 位 ASCII 字母、数字或下划线。现阶段 UUID 由名称的小写形式派生：只改
字母大小写仍得到同一临时 UUID，其他改名会得到新身份；当前没有重命名约束或迁移工具，
因此不要把改名当作受支持操作。

P2 还提供 bot 自身背包入口：持久 owner 或服务器 OP 与活动 bot 同维度、存活且
在 `inventory.viewDistance` 内时，用**空主手的主手交互**右键 bot；副手和持物品不会打开。
GUI 以完整原版玩家背包风格在上方展示 bot 的 41 格真实库存和 3D 模型，在下方展示 viewer
自己的 36 格库存；原版 2×2 合成区域被隐藏且不可交互。画布为 `176×256`，窗口或显示高度
在当前 GUI Scale 下不足 256 个逻辑 GUI 像素时，需要调低游戏的“界面尺寸”。用户已在
真实客户端确认本轮视觉修复有效；多语言、资源包与全部 GUI Scale 组合仍未专项验证。
它不代表已经支持查看者的世界容器、通用工作站或模组容器自动化。

## 文档导航

- [文档总目录与维护规则](docs/README_CN.md)
- [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md)
- [AI 玩家调研与 P2 重新基线](docs/AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md)
- [P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)
- [P3 感知与世界模型调研设计](docs/AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md)
- [P3 完成验收报告](docs/P3_COMPLETION_REPORT_CN.md)
- [P4 导航与安全反射调研设计](docs/AI_PLAYER_RESEARCH_AND_P4_DESIGN_CN.md)
- [P4 完成验收报告](docs/P4_COMPLETION_REPORT_CN.md)
- [完整架构与 P0–P10 路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)
- [原版玩法能力矩阵与发布门槛](docs/VANILLA_CAPABILITY_MATRIX_CN.md)
- [安装与当前用法](docs/INSTALLATION_AND_USAGE_CN.md)
- [当前配置](docs/CONFIGURATION_CN.md)
- [开发与测试](docs/DEVELOPMENT_CN.md)
- [架构决策记录](docs/adr/README.md)
- [参与开发](CONTRIBUTING.md)
- [安全策略](SECURITY.md)
- [更新日志](CHANGELOG.md)
- [第三方研究与许可证边界](THIRD_PARTY_NOTICES.md)

## AI 与安全边界

通用 DeepSeek、聊天和规划尚未接入。P6-R1 只有已编码、默认关闭且尚待 Java 21/CI 验证的
本地只读审阅往返：客户端必须显式启用自己的 `reviewOnly.enabled`，才能对固定
`deepseek-chat` 发起受限请求；它只接受零参数审阅确认和安全摘要，绝不执行世界动作。保存或
绑定 API Key 本身不会启用它，也不会让 bot 聊天、规划或行动。凭据边界是：

- Key 只在 owner 客户端游戏目录的 `config/botplayer/credentials-v1.json` 保存（默认启动
  目录通常是 `.minecraft`）；
  `(serverInstanceId, ownerUuid, botId) → profileId/agentId` 绑定写在同目录
  `bindings-v1.json`；当前是明文落盘，优先原子替换（不支持时退化为同目录覆盖）并尽力
  收紧文件权限，不宣称加密或系统密钥库；
- Key 不进入聊天或命令参数、Minecraft payload、服务端、世界 NBT/SavedData、playerdata、
  普通日志、崩溃报告或 Git；
- 一个本地 credential profile 可以绑定多个 bot，但每个 bot 使用独立 agentId 和状态；
- `review-only-v1.json` 只保存 `reviewOnly.enabled`，不保存或同步 endpoint、模型、工具、
  prompt、profile 或 Key；关闭或重载会取消本地 session 并清空 Provider factory；
- 只有持久 owner 可以配置；未来 client-sponsored LLM 在 owner 离线时不可用；
- 服务端 active agent binding 在 owner 退出、bot 卸载或停服时清除；客户端本地 binding
  保留，重新打开界面后可以再次绑定；
- LLM 不逐 Tick 控制，不直接运行代码、命令、脚本或任意 HTTP；
- 模型只能提出结构化计划，不能直接改变方块、物品或玩家状态；
- 每个有副作用的动作都要经过权限、风险、范围、幂等和结果验证；
- 未知模组玩法先询问、适配或拒绝，不能用直接改数据来伪装成功。

发现密钥泄漏或安全问题时请先阅读 [SECURITY.md](SECURITY.md)。

## 开发路线

```text
P0 工程基线
→ P1 真实服务端玩家内核
→ P2 原子动作与背包 GUI
→ P3 感知与玩家活动理解
→ P4 导航与安全反射
→ P5 生存技能闭环
→ P6 DeepSeek 与聊天
→ P7 长期记忆与目标
→ P8 模组适配
→ P9 多 bot 协作
→ P10 硬化与发布
```

P2、P3 与 P4 自动化退出门均已关闭。P5 候选已由 Build #163 完成 Java 21
`clean build`、Gradle `test`、91/91 GameTest 和 JAR 上传；40 个实际运行 batch 在默认
`maxBots=8` 下完成，Build #133/#135 暴露的超配仍由拆批而非提高上限修复。P5A 仍缺跨 menu
统一事务、`clicked()` 故障注入、生命周期 `PENDING` continuation、TaskSensor/Reservation
生产接线、Checkpoint、工具/副手、自卫、craft/chest/furnace/DAG，以及两次启动、独立
专用服和多 bot soak；模组自定义 menu 和专用语义属于 P8。

## License

BotPlayer 自有代码使用 [MIT License](LICENSE)。参考项目只用于理解公开结构和行为；
任何实际引入的依赖、代码、资源或提示模板都必须经过单独的许可证审查，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
