# BotPlayer

[![Build](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml)

BotPlayer 是面向 Minecraft Java 的真实服务端玩家 AI 框架。项目首先支持
Minecraft 1.21.1 + NeoForge，后续版本在 1.21.1 架构稳定后再迁移。

> **当前状态：P2、P3 与 P4 自动化退出门已通过；Build #362 已验证受限 P5 纵切与
> P6-R1 的先前自动化基线。其后的 P5A 修复与受限工具/精确主手/普通副手 native-menu
> GameTest 源、单 `TechniqueLifecycleCoordinator` Contract 与
> P5C-S1 固定副手盾牌持有、P6 会话协调器、客户端安全 terminal-observation/Error-cleanup/间接重入收口、ledger-first proposal review、P6-A0/A1a/A1b token-reservation/physical-retry budget 与 P6-B0 handshake / P6-B1 R1 physical-attempt production bridge 及其 mock payload-path GameTest 源、受限 Technique→Action permit Contract 与 P5D-A0/A1/A2/A3/A4/A4-R1/A5/A6/A7/A8/A9/A10/A11 有界蓝图/施工工作包/candidate-site/survey-assessment/placeable-item/default-state registry/loaded-world survey/spatial-lease/own-inventory-material observation/work-package-material demand/item-total projection/package-site target manifest/package-survey raw evidence pairing 数据 Contract
> 提交仍待各自 Java 21 CI；仍不是
> 正式版本，P5/P6 总退出门均未关闭。**
>
> 当前代码已建立真实 `BotServerPlayer`、generation 隔离、确定性动作运行时、短程输入、
> 基础世界交互和 bot 自身背包 GUI。P3 提供有限感知和短期世界事实；P4 提供有界导航与 L0
> 安全反射。当前 P5/P6 候选为
> [`agent/p5-p6-next`](https://github.com/GreyTaiWolf/BotPlayer/tree/agent/p5-p6-next)；
> [Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) 已通过 Java 21
> `clean build`、Gradle `test`、161 项常规 NeoForge GameTest 与 phase-one/phase-two
> 重启 GameTest（各 1 项）。这是当前分支增量提交之前的自动化基线；这不替代真实客户端、
> 独立专用服或多 bot soak 验收。
>
> 当前候选自动覆盖受限 P5 资源—制作—存放、白名单容器/工作站（含 Bot 私有末影箱）、
> 作物/交易/牛奶、有限自卫、保存围栏和两阶段重启路径；仍不代表通用容器、任意配方/作物/
> 交易、广泛战斗或完整生存能力。末影箱只使用 Bot 自己的私有账本，不读取方块实体物品；
> 同一物理方块跨 Bot 目前保守串行。
> P5C-S1 新增的管理员固定入口只接受已装备的精确原版副手盾牌，冻结后固定持有
> 8 Tick 并释放；它没有目标、移动、换装、重试、普通停止或通用 Action 入口。源码包含无库存
> 漂移和主手盾拒绝的 GameTest，但本增量仍待 Java 21 CI 与 NeoForge GameTest；它不证明真实受击
> 格挡、耐久变化或斧破盾。
> 当前 P5D 已有未注册的 lifecycle-owned `TechniqueActionPort` adapter Contract：它只把精确 permit
> 映射到既有 Action runtime 的入队、exact terminal drain 与 cancel-or-contain，不暴露 future 或 world DTO。
> P5D-A0 另有纯 Java 的有界 `Blueprint`/content hash/计划方块需求 Contract，P5D-A1 只把同一
> immutable Blueprint 分成完整 `(id, revision, hash, ordinal)` 绑定、exact-cover、最多 16 个包和稳定
> 拓扑的 work-package 数据图；P5D-A2 只将该 exact plan 绑定到 candidate dimension+anchor 与由真实
> Blueprint cell 派生的 bounds/target 坐标；P5D-A3 只对 caller-supplied、完整 canonical target evidence
> 产生 `BLOCKED|INCOMPLETE|ACCEPTED_CANDIDATE` 的纯数据评估。A3 的 candidate 不代表已读取/已加载世界、
> accepted site、lease、ownership proof 或 human confirmation。P5D-A4 要求每一种完整 expected BlockState
> 都有显式 caller-supplied item declaration，并只产生按 itemId/永久-临时类别聚合的 declared quantity；它不把
> blockId 猜成 itemId，也不证明物品可用/可放置。P5D-A5 只在 server thread 对同一 exact binding 的 canonical
> cells 作已加载世界快照：height/`isLoaded` 外为 `UNKNOWN`，air 为 `EMPTY`，其余仅记录完整 native block-state
> fingerprint；它不加载 chunk、不写世界，也不表示 snapshot 仍新鲜、accepted site、lease 或许可。仍没有材料预留、
> NBT、placement 或世界动作。仍没有 approved construction route、`GroundPlace`、
> 材料授权或任何真实建筑/红石世界动作。
> P5D-A4-R1 额外在 server thread 逐项检查既有完整 item declaration：item 必须解析为 non-air `BlockItem`，
> 且它的 default 完整 block state 与 target fingerprint 精确相等。它不猜朝向/含水等 placement context，不调用
> 原版 `useOn`，也不是 inventory、reservation、site lease 或 construction permit。
> P5D-A7 只在 authoritative server thread 对同一个活动 Bot body 的 empty exact native `InventoryMenu` 作一次
> 只读 snapshot：先复用 A4-R1，再将 permanent/temporary 的相同 explicit item 聚合，仅按 default-stack
> fingerprint 统计 main/hotbar `0..35`，返回 `AVAILABLE|SHORTAGE|UNAVAILABLE_MENU|UNAVAILABLE_REGISTRY`。它不读
> 世界或容器内容、不移动/扣除/预留材料，也不是 site/placement/Technique/Skill/Action 或 construction permit。
> P5D-A8 则只从同一 complete work plan 中真实存在的一个 package、其完整 key 和 A4 explicit evidence 重导该
> package 的 `(itemId, materialClass)` demand；evidence、key 或 supplied requirements 只要漂移即拒绝。它不把 A7
> availability 分配给 package，不读 world/container、不 reservation、移动、放置或接入 Technique/Skill/Action，因而
> 同样不是 material-ready proof 或 construction permit。
> P5D-A9 只把一个 A8 demand 与同一 evidence 的 A7 snapshot 按 item total 比较：全 Blueprint `SHORTAGE` 时某个
> package 仍可在 isolation 中 observed sufficient，多个 package 的 sufficient 绝不能相加或并行消费同一 observation。
> unavailable source 仍为空 findings；A9 不分配 class/slot/source、不延长 menu fence、不 reservation、移动、放置或接入
> Technique/Skill/Action，因此也不是 readiness proof 或 construction permit。
> P5D-A10 只将 candidate site binding 中的真实 work package 逐 cell 重导为其 exact candidate coordinate manifest；
> 即使同一 Blueprint 的另一个合法 partition 使用同一 ordinal，也只使用 binding 已冻结的 package cell。它不读取/缩减 survey
> 或 assessment、不接 lease/material/placement candidate/站位/点击参数、Action/Technique/Skill 或世界动作，因此不是
> site-ready 或 construction permit。
> P5D-A11 只将 A10 的一个真实 package target manifest 与同一个 exact candidate binding 的完整 raw survey 逐 cell
> 配对；它保留 UNKNOWN/EMPTY/OCCUPIED 和原始 observed tick，不把局部观测变为 assessment、freshness、site-ready、
> material-ready、lease 或 construction permit。同 Blueprint 的另一个合法 partition 或任一 binding drift 一律拒绝；
> 它不接 A6/A7/A8/A9、placement candidate、Action/Technique/Skill 或世界动作。
> P6-R1 是默认关闭的固定本地只读审阅往返，只有真实 owner 本地 `reviewOnly.enabled=true`
> 时才会尝试发起固定 Provider HTTPS；回传只形成安全摘要并丢弃，绝不进入 Skill、Action 或
> 世界动作。通用 DeepSeek/chat、通用 client-sponsored bridge 与 AI→世界执行尚未实现；R1
> 的真实客户端/Provider E2E 仍待验证。保存 Key 不代表 AI 已经接通，方块观察也不代表能读取
> 箱子内容。
> P6-C2 的已登记 Error cleanup 与同线程间接 completion reentry 收口只保证本地 session 失败关闭；
> P6-C3 的通用 owner-thread coordinator 仍未接线；R1 则使用更窄的 P6-B1 production bridge，已在
> network、Lifecycle 与 client stage/grant fence 中完成 exact correlation、terminal close 和分歧
> fail-closed。通用 bridge、聊天与 AI→世界执行仍未实现。
> P6-A0 的 `AiTokenBudgetLedger` 只为单个 `(owner, bot, agent)` scope 冻结已接受 admission 的
> token 预留：release/expiry 只退未开始的 reservation，物理调用前 settle 后永不退款。P6-A1a 的
> `AiRetryAttemptBudgetContext` 把 trusted binding/admission 和 upstream deadline 与 retry 自身 deadline、
> 账本 TTL 取最早值；P6-A1b 已新增 `RetryingAiProvider.completeBudgeted(...)` 的显式 opt-in 物理
> delegate hook，每个 retry 用新 reservation，settle 后即使 cancel/timeout/同步失败也不退款。普通
> `AiProvider.complete(...)`、Scheduler/HTTP/client session 仍不自动接预算，也没有 production bridge；
> 因此这不是真实计费或预算统计。P6-B0 另有 server-owned `offer → prepare ACK → settle → start grant`
> Contract：identity 精确绑定 owner、receipt、attempt、nonce、client not-after 与更早的 physical-start
> deadline；未 settle offer 可 release，settle 后 grant/断线/丢包/expiry 一律不退款，客户端只可在实际
> Provider/HTTP start 边界原子 claim 一次本地 lease。P6-B1 已将该 Contract 仅为固定 R1 接入 v3 packet、
> authenticated session、client queue 与 lifecycle reaper。`P6ReviewOnlyPhysicalAttemptGameTests` 在保留配置的
> mock owner connection 上验证真实 S2C offer/grant/cancellation 与 server listener 的 C2S ACK 路径，覆盖
> replay、replacement、unbind 和 tick-TTL stale ACK；这批源码仍待 Java 21 CI/NeoForge GameTest。仍没有
> billing/usage reconciliation、通用 bridge、聊天或 AI→世界执行，真实客户端/Provider E2E 也仍待验证。
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
- P6-R1 owner 手动只读审阅往返已通过 Build #362 的 Java 21 自动验证：物理客户端只在本地
  `reviewOnly.enabled=true` 后构造固定 `deepseek-chat` review Provider；默认关闭，回传仅为
  零参数确认和安全数字摘要，绝不执行 Action、Skill 或世界变更；真实客户端/Provider E2E
  仍待验证；
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
- 自动寻找/生产食物与完整补给闭环；主动进食、受限生产链、盔甲专用路径和通用
  `InventoryMenu SWAP_SEQUENCE` 已由 Build #362 自动验证；主动用药/解毒、正式反击/
  策略性盾牌格挡（含受击耐久与斧破盾）、工具/通用副手仍未实现；
- 除受限 P5B 白名单切片外的通用世界容器、工作站与制作/熔炼流程；
- 独立专用服与多 bot 性能验证；
- 持久世界模型、长期来源化记忆和自然语言“刚才发生了什么”对话；
- 战斗策略、建造和生存技能；
- 通用 client-sponsored Provider bridge、聊天、模型策略、预算与 AI→Skill/Action/世界执行；
  已有的 Provider/codec/firewall 基础和 P6-R1 不代表这些能力或 P6 退出门已经完成；
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

早期 P5 基线由
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
`2`。P3 `perception`、P4 `navigation/safety` 与 P5 `skill/combat` 管理命令固定要求原版权限
等级 `2`，不随该配置降级。
`settings` 不要求 OP 等级，但只能由 roster 中记录的精确 owner 对活动 bot 执行；OP
也不能配置别人的 bot。

```text
/botplayer spawn <name>
/botplayer list
/botplayer remove <name>
/botplayer settings <name>
/botplayer ai review <name>
/botplayer perception inspect <name>
/botplayer perception correct <bot> <actor> <activity>
/botplayer navigation go <name> <x> <y> <z>
/botplayer navigation stop <name>
/botplayer navigation inspect <name>
/botplayer safety inspect <name>
/botplayer skill equip-armor <name>
/botplayer skill inspect <name>
/botplayer combat shield-hold <name>
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
`InventoryMenu SWAP_SEQUENCE` 已由 Build #137 运行验证；其通用 equipment/offhand 入口仍拒绝。
已注册的受限 P5A handler 则只能经真实原版 `WorldMenuTransaction` 处理请求的工具、白名单精确
主手物品和显式普通副手；盾牌仍在该普通副手路径 fail-close，并仅能走下述单独固定入口。
这三条路径的直接 GameTest 源仍待 Java 21 CI/NeoForge GameTest，且不构成通用装备或策略选择。
`combat shield-hold` 只允许 OP 对无竞争所有者、原版 `InventoryMenu` 空 cursor 且已预装备
精确原版副手盾牌的活动 bot 触发固定 8 Tick 持有后释放；它没有普通停止、目标或换装参数，
也不代表真实格挡。该新增源码仍待 Java 21 CI 与 NeoForge GameTest。
`ai review` 是唯一 P6-R1 手动入口：只允许活动 bot 的真实持久 owner，在已有本地 agent
binding 和 0/1 Tick 已完成快照时发起固定只读审阅；本地开关未启用时不会启动 Provider。它不
接收用户 prompt、不创建计划，也不执行 Skill、Action 或世界变更。该 binding 指向的
`deepseek` credential profile 缺失或无可读 Key 时会失败关闭，不会发出 HTTP。
当前 P6-B1 GameTest 源在 mock connection 上走真实 offer/ACK/grant/cancellation packet 路径，但仍待
Java 21 CI/NeoForge GameTest；Build #362 只证明这批增量之前的 R1 基线，并非真实客户端或 Provider E2E。

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

通用 DeepSeek、聊天和规划尚未接入。P6-R1 是已通过
[Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) Java 21 自动
验证、默认关闭的本地只读审阅往返：客户端必须显式启用自己的 `reviewOnly.enabled`，才能对
固定 `deepseek-chat` 尝试发起受限请求；它只接受零参数审阅确认和安全摘要，绝不执行世界
动作。真实客户端/Provider E2E 仍待验证。保存或绑定 API Key 本身不会启用它，也不会让 bot
聊天、规划或行动。凭据边界是：

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
- 只有持久 owner 可以配置；P6-R1 与未来 client-sponsored LLM 在 owner 离线时不可用；
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

P2、P3 与 P4 自动化退出门均已关闭。当前 P5/P6 候选已由 Build #362 完成 Java 21
`clean build`、Gradle `test`、161 项常规 GameTest 与 phase-one/phase-two 重启验证。
P5 仍缺跨 menu 通用事务、任意配方/作物/交易、工具/通用副手、策略性盾牌格挡与广泛战斗、独立专用服和多 bot
soak；P6 仍缺通用 client-sponsored bridge、聊天、模型策略和 AI→世界执行。模组自定义 menu
和专用语义属于 P8。

## License

BotPlayer 自有代码使用 [MIT License](LICENSE)。参考项目只用于理解公开结构和行为；
任何实际引入的依赖、代码、资源或提示模板都必须经过单独的许可证审查，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
