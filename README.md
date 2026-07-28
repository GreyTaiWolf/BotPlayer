# BotPlayer

[![Build](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml)

BotPlayer 是面向 Minecraft Java 的真实服务端玩家 AI 框架。项目首先支持
Minecraft 1.21.1 + NeoForge，后续版本在 1.21.1 架构稳定后再迁移。

> **当前状态：P2 实现与自动化验收已通过；P3 候选已编码、待 Java 21 CI 验证；仍不是
> 正式版本。**
>
> 当前代码已建立真实 `BotServerPlayer`、generation 隔离、确定性动作运行时、短程输入、
> 基础世界交互和 bot 自身背包 GUI；P2 的 140 项单测与 19 项 GameTest 连续两轮全绿，
> GitHub Actions 也已全绿。P3 候选新增有限感知、权威/认知事件、短期世界事实和玩家活动
> 推断，但当前环境无法运行所需 Java 21/Gradle 门禁，不能沿用 P2 绿色数字。它还没有
> 长距离寻路、技能闭环、通用世界容器、聊天、DeepSeek 或长期记忆。保存 Key 不代表 AI
> 已经接通，P3 方块观察也不代表能读取箱子内容。请以
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

版本号是开发标识；P2 自动化验收通过不等于正式发布或完整 AI 玩家。

## 已经实现

- `BotServerPlayer extends ServerPlayer`；
- 本地 `EmbeddedChannel` 虚拟连接；
- bot 专用 `BotGamePacketListener`；
- 登录时替换 packet listener 的窄 Mixin；
- 重生时保持 `BotServerPlayer` 类型的窄 Mixin；
- 只在原版死亡真正完成后进入重生流程；
- 临时、确定性的名字派生 UUID；
- schema v1 持久 roster、规范名字、稳定 bot/player UUID、owner 和服务器实例 ID；
- 只有持久 owner 可进入客户端凭据配置；
- `/botplayer settings <name>` 打开客户端本地 API Key 设置界面；
- 客户端可创建/替换凭据 profile、绑定/解绑 bot；每 bot 使用独立 agentId，profile 删除
  尚未实现；
- 既有 playerdata 检测与保存位置保留；
- 服务器线程生命周期管理；
- 自动重生、维度切换基础路径和区块跟踪刷新；
- 稳定 runtime handle、generation 与旧实例拒绝；
- 生成失败回滚、幂等断开和停服异常隔离清理；
- 有界动作 mailbox、幂等 ledger、通道仲裁、取消/抢占/超时和结构化结果；
- `WAIT / LOOK_AT / MOVE_INPUT / JUMP / STOP` 与普通玩家输入/物理适配；
- 选择快捷栏、使用/释放物品、使用方块、分阶段破坏、攻击/实体交互、丢弃与拾取等待；
- 空主手、主手右键打开 bot 自身 41 格真实库存，77 槽 menu、单 viewer 写锁、距离和
  lifecycle 校验、动作 mutation gate；
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
- P2 生命周期、移动、交互和库存 GameTest 来源；
- `/botplayer spawn|remove|list`；
- NeoForge server 配置；
- GitHub Actions Java 21 构建与 GameTest 门禁配置。

以上 P2 项已通过本地与远端自动化退出门；P3 项目前只代表候选代码已编码。P2 完整证据
见 [P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)，P3 待验证门禁见
[P3 完成报告草案](docs/P3_COMPLETION_REPORT_CN.md)。

## 尚未实现

- 自动恢复、trusted/observer ACL 与完整数据迁移；
- 客户端 screen 手工验收、独立专用服和多 bot 长时间 soak；
- 长距离寻路、动态重规划、完整移动模式和安全反射；
- 箱子/木桶/潜影盒等通用世界容器、工作站与制作/熔炼流程；
- P3 Java 21 严格编译、GameTest、独立专用服与多 bot 性能验证；
- 持久世界模型、长期来源化记忆和自然语言“刚才发生了什么”对话；
- 战斗策略、建造和生存技能；
- DeepSeek Provider/HTTP、聊天、工具防火墙和预算；
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

P2 已加入生命周期、移动、交互和库存 GameTest；P3 候选新增感知相关测试来源：

```bash
./gradlew --no-daemon runGameTestServer
```

本地结果为 140/140 单元测试和同一持久世界连续两轮 19/19 GameTest；远端
[GitHub Actions Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722)
也通过了标准 `clean build runGameTestServer`，完整证据见
[P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)。

这组数字只对应 P2 合并基线。P3 候选尚未在当前环境完成 Java 21 编译、单元测试、
GameTest 或干净构建；当前静态计数新增 42 个 P3 `@Test` 方法（完整源码 182 个）和
6 个 P3 GameTest 来源，均尚未执行。推送后必须以新的 CI 结果回写，不能把 Build #18
当作 P3 证据。

更完整的步骤见：

- [安装与当前用法](docs/INSTALLATION_AND_USAGE_CN.md)
- [开发指南](docs/DEVELOPMENT_CN.md)
- [配置说明](docs/CONFIGURATION_CN.md)

## 当前命令

`spawn`、`list`、`remove` 需要达到 `permissions.commandPermissionLevel`，默认是
`2`。P3 `perception` 管理命令固定要求原版权限等级 `2`，不随该配置降级。
`settings` 不要求 OP 等级，但只能由 roster 中记录的精确 owner 对活动 bot 执行；OP
也不能配置别人的 bot。

```text
/botplayer spawn <name>
/botplayer list
/botplayer remove <name>
/botplayer settings <name>
/botplayer perception inspect <name>
/botplayer perception correct <bot> <actor> <activity>
```

`inspect` 有界显示活动 bot 的最新快照、置信活动/generation-local 证据序号和最近短期
事实；`correct` 将对在线玩家 actor 的活动纠正追加为证据事件。允许的 activity 是
`idle|moving|exploring|mining|building|combat|farming|crafting|smelting|none`。这两个
命令是管理诊断入口，不代表 bot 已能聊天或回答自然语言问题。

名称必须是 1–16 位 ASCII 字母、数字或下划线。现阶段 UUID 由名称的小写形式派生：只改
字母大小写仍得到同一临时 UUID，其他改名会得到新身份；当前没有重命名约束或迁移工具，
因此不要把改名当作受支持操作。

P2 还提供 bot 自身背包入口：持久 owner 或服务器 OP 与活动 bot 同维度、存活且
在 `inventory.viewDistance` 内时，用**空主手的主手交互**右键 bot；副手和持物品不会打开。
GUI 展示 bot 的 41 格真实玩家库存和 viewer 自己的 36 格库存。它不代表已经支持箱子、
工作站或模组容器自动化。

## 文档导航

- [文档总目录与维护规则](docs/README_CN.md)
- [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md)
- [AI 玩家调研与 P2 重新基线](docs/AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md)
- [P2 完成验收报告](docs/P2_COMPLETION_REPORT_CN.md)
- [P3 感知与世界模型调研设计](docs/AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md)
- [P3 完成报告草案](docs/P3_COMPLETION_REPORT_CN.md)
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

DeepSeek 尚未接入。当前客户端可以本地保存和绑定 API Key，但没有 Provider 或 HTTP
请求；bot 不会因此聊天、规划或行动。凭据边界是：

- Key 只在 owner 客户端游戏目录的 `config/botplayer/credentials-v1.json` 保存（默认启动
  目录通常是 `.minecraft`）；
  `(serverInstanceId, ownerUuid, botId) → profileId/agentId` 绑定写在同目录
  `bindings-v1.json`；当前是明文落盘，优先原子替换（不支持时退化为同目录覆盖）并尽力
  收紧文件权限，不宣称加密或系统密钥库；
- Key 不进入聊天或命令参数、Minecraft payload、服务端、世界 NBT/SavedData、playerdata、
  普通日志、崩溃报告或 Git；
- 一个本地 credential profile 可以绑定多个 bot，但每个 bot 使用独立 agentId 和状态；
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

P2 的严格编译、单元测试、GameTest 和干净构建已在本地及远端通过，自动化退出门已经
关闭。P3 有限感知与世界模型已形成 `0.2.0-alpha.1` 候选，但仍待 Java 21 CI 验证；
通用世界容器分别延期到 P5A/P5B，模组自定义 menu 属于 P8。

## License

BotPlayer 自有代码使用 [MIT License](LICENSE)。参考项目只用于理解公开结构和行为；
任何实际引入的依赖、代码、资源或提示模板都必须经过单独的许可证审查，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
