# BotPlayer 更新日志

本文件记录已经进入仓库的变化。未来路线、想法和未完成任务不写成已发布功能；它们统一放在
[架构与路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## Unreleased

### 新增

- 新增 schema v1 持久 bot roster，保存规范名字、稳定 bot/player UUID、owner 和
  `serverInstanceId`；
- 新增 `/botplayer settings <name>`：只有 roster 中精确 owner 可以打开本地界面，OP
  也不能越过 owner 检查；
- 新增客户端本地 API Key 管理界面，可创建/替换 credential profile、绑定/解绑 bot；当前
  不提供 profile 删除；
- 同一个 credential profile 可以供 owner 的多个 bot 使用，但 agentId 与后续状态按 bot
  隔离；
- owner 退出、bot 卸载或停服时清除服务端运行时 agent binding，Key 始终只在客户端；
- 新增根目录 `AGENTS.md`，按需求指引开发者读取源码、架构、安全和同步文档。
- 新增 P2 确定性动作运行时：有界 mailbox、幂等 ledger、动作状态机、控制通道仲裁、
  deadline、`maxTicks`、取消、抢占、shutdown 和结构化结果/证据；
- 新增 generation 绑定的 `WAIT / LOOK_AT / MOVE_INPUT / JUMP / STOP`，使用普通玩家输入、
  碰撞和物理完成短程移动；
- 新增选择快捷栏、使用/释放物品、使用方块、分阶段破坏、攻击/实体交互、丢弃与指定
  ItemEntity UUID 拾取等待；
- 新增 bot 自身背包 GUI：空主手、主手右键入口，41 个 bot 真实库存槽位与 36 个 viewer
  槽位组成 77 槽 menu；
- 新增 generation/nonce 背包会话、一人写锁、owner/OP 权限、距离/生命周期校验、
  Shift 移动、关闭确认与动作侧 `InventoryMutationGate`；
- 新增虚拟连接常量空间 telemetry，记录丢弃/拒绝包计数、callback、keepalive/teleport
  处理与首个关闭原因，不记录包内容。
- 新增 P3 有限感知候选：权威 `AuthorityEvent` 与每 bot generation 的
  `PerceivedEvent` 双平面、有界运行时序号环和读取 gap；
- 新增动作终态、NeoForge post-state 验证事件和原版定向声音包收集入口；全服权威事件
  不自动成为所有 bot 的认知；
- 新增自身、背包、注视、附近实体、威胁、局部方块和声音传感器；只读取已加载世界，
  未加载边界显式为未知；
- 新增彼此隔离的权威投影/公开传感器预算、EWMA MSPT 降级与 AI-safe 不可变
  `ObservationSnapshot`；快照只暴露 generation-local 认知水位和本 bot 公开传感器
  分类预算；
- 新增全局/维度/target scope revision、运行时短期事实、已投影变化/TTL 失效与确定性
  玩家活动推断；
- 新增 `/botplayer perception inspect <name>` 和
  `/botplayer perception correct <bot> <actor> <activity>` 管理诊断/纠正入口；
- 开发版本进入 `0.2.0-alpha.1` P3 候选；Build #28 自动化退出门已通过并上传构件，但
  尚未正式发布。
- 新增 P4 导航请求/session、不可变已加载世界运动快照、有界分段 A*、真实输入 follower、
  动态 revision 重算、有限 stuck 恢复，以及 `navigation go/stop/inspect` 管理入口；
- 新增每 Tick L0 `SafetyFrame` 与 incident FSM，可对悬崖、燃烧、低空气、弹射物、TNT、
  敌对目标、低生命/食物、伤害和有害效果关闭菜单、挂起导航并抢占普通输入；
- 新增真实玩家规则兼容基线：僵尸原版仇恨与近战、护甲减伤、饥饿/exhaustion、效果与
  属性、动态 `DamageType` 和 NeoForge `PlayerTickEvent` 均落在真实 Bot 身体；
- 新增默认关闭的 Terrain Assist；请求/服务端双门控后，仅允许受白名单、工具、支撑、
  库存、保护事件、精确结果和单次预算约束的短通道挖掘与简单短桥；
- 新增 `/botplayer safety inspect <name>` 与 P4 完成验收报告；Build #97 已通过全仓
  55/55 GameTest，其中 P4 直接场景 28 个，但尚未正式发布。
- 冻结 P5A-0 的有界 Skill、DAG、Safety handoff、菜单事务、checkpoint 与资源预留
  合同；首批源码加入有界核心、TTL 预留、背包到快捷栏交换原语、`skill inspect` 和主动
  进食开发切片，并为失败/取消/抢占增加补偿窗口、一次性布局租约、结构化物品守恒、
  独立生命周期背包回执与无法恢复时的 generation 隔离；换维度、死亡复活与
  `ServerPlayer` replacement 只有在动作和物理背包布局两张回执均安全后才允许激活新
  generation；断线请求会立即冻结 listener 权威，并在原版保存/移除前用精确
  body/listener/connection/generation 与不可升级的旧代回执完成布局补偿，避免把临时
  选槽持久化；replacement/respawn 未收敛时只允许一次排队重试，异常身份则通过一次性
  no-save removal 门闩隔离，不能由 `PlayerList.remove` 把未验证布局写盘。
- 新增确定性基础装备策略和扫描 carried inventory `0..35` 的盔甲规划器，拒绝零耐久、
  目标绑定与装备后会绑定的候选；新增原生 `InventoryMenu` 41 槽完整快照，按精确
  stateId/layout、动态槽权限、物品多重集与 generation/replacement 回执验证。
  `/botplayer skill equip-armor <name>` 可启动最多四个装备槽的逐件重规划运行：热栏
  候选使用单击 `SWAP`，主背包首次穿甲使用 2 步、替换已有盔甲使用 3 步，每 Tick 最多
  执行一次点击；取消或 cleanup 最多执行一次物理点击，把已知计划前缀收口到经证明的
  初始或最终安全端点。这是盔甲专用有界多步路径，不是通用 menu FSM 或无条件回滚。
  [PR #6](https://github.com/GreyTaiWolf/BotPlayer/pull/6) 的 Build #109 已通过
  Java 21 `clean build`、322 个 JUnit 与 76 个 GameTest，覆盖热栏和主背包盔甲路径；
  P5A 退出门仍未通过。工具/副手、有限自卫、Checkpoint、工作站与生产链仍未实现。

### 加固

- runtime handle 在同一服务器会话内按 bot 稳定保留；重生新实例递增 generation，旧代际
  动作和菜单不能重定向到新身体；
- 生命周期关闭可同步排空同 generation 的已排队/活跃动作，清理失败进入安全 reset 或
  代际隔离；
- completion callback 使用有界 dispatcher 与入口背压，并为取消通知保留容量；
- 无客户端玩家采用受管理的两阶段 Tick，避免重复玩家 Tick 或把新位置回写成旧网络位置；
- 死亡、卸载、换维度、viewer 退出、超距、menu 替换和停服会关闭相关背包会话；
- 修复 bot 背包 screen 的纯色占位视觉，改为 `176×256` 的完整原版玩家风格：上方显示
  bot 的 3D 模型、盔甲、副手、主背包、快捷栏和当前选择，下方显示 viewer 物品栏；
  原版 2×2 合成区域隐藏且不可交互；
- 背包 screen 改为运行时组合 Minecraft 1.21.1 的原版 inventory、container 和 HUD 资源，
  不复制或打包 Mojang PNG；资源包可替换对应原版资源。用户已在真实客户端确认本轮
  视觉修复有效；多语言、资源包与全部 GUI Scale 组合仍未专项验证，
  `176×256` 画布在可用逻辑高度不足时需要降低 GUI Scale；
- 普通世界交互使用服务端玩家路径，并以方块、实体和物品前后状态验证，不以调用成功代替
  世界成功。
- P3 事件、快照、事实、revision scope、声音候选、扫描和证据全部有界；死亡、重生、
  换维度、卸载、回滚和停服关闭旧 generation 认知；
- 定向声音 ingress 按 generation 分队列、限制动态公平份额并 round-robin 抽取；全局
  容量满时优先从最大队列淘汰旧候选，单队列保持封包顺序；历史声音 ring 在快照预算
  不足时先选择最新事件，再按认知序号恢复时间顺序；
- SELF 状态必须在当前 Tick 成功，完整 41 槽背包必须仍在 20 Tick 新鲜度内；关键输入
  失效时撤下快照，其他传感器按各自 TTL 降级或清空；
- 实体传感器使用达到预算即中止的有界枚举；威胁/视觉/普通实体使用子配额且威胁优先，
  每次实体索引原始回调扣 `ENTITY_SCAN`、匹配候选再扣 `ENTITY_READ`，威胁 relevant
  selector 另有 raw scan 上限；视觉 loaded-ray 使用最多 64 chunk 的 DDA；
- 第三方实体/物品/效果/方块属性的单项异常只截断本次观察；含 `BlockEntity` 的方块只
  输出 opaque 标记，不读取对象或内容；视觉异常返回 `Unavailable`，不伪造为未命中；
- `SELF/DIRECT` 在权威事件发布时按精确 actor/target 可靠路由；`VISUAL/AUDIBLE`
  使用独立 spatial authority projection ring，定向洪泛不会挤出空间候选；同 Tick
  空间事件超预算时只选最新有界窗口，并把遗漏计入管理员 coverage 诊断；
- `SELF` 只保留 bot 自身 actor，`VISUAL` 只保留逐个通过视距、视锥、已加载和遮挡复核
  的 actor；两类投影删除未由对应通道证明的通用身份 delta；
- 方块放置只有完整预期 `BlockState` 匹配才提交；破坏只有变空气才归因 actor，同 ID
  属性变化不再冒充破坏，非空气替换只记录无 actor 的泛化 `BLOCK_CHANGED`；
- 活动推断每 Tick 严格剔除滑动窗口外证据，单次按 actor 聚合并按新近证据选择候选；
  actor 上限随压力为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`；单独 `use_on_block`
  不足以证明 building，必须有方块放置等已提交语义证据；
- `PerceivedEvent` 只引用并重构权威事件的允许字段，不携带完整权威载荷；完全未感知的
  变化不触碰该 bot 的事实、认知水位或公开预算，避免泄露变化发生时刻；
- `PerceivedEvent` 只保留 opaque `authorityEventId`，不暴露 authority session/seq；
  `VISUAL/AUDIBLE` 只允许 same-tick 投影，积压和晚到事件 fail-closed；
- 普通 authority audit、spatial authority projection 与 routed sound audit 三环独立，
  但共享唯一递增 `eventSeq`；每 generation 的声音/非声音认知也分环并共享 local
  `perceivedSeq`，声音洪泛不逐出动作、方块、伤害或活动证据；
- 待复核 break/place/toss 候选在捕获时冻结 bot generation，发布时仅用私有
  `routing.*` 元数据完成 SELF 路由，认知投影不会公开这些字段；
- critical action-outcome ingress 上限与 P2 最大 canonical 吞吐对齐；成功 break 预留
  两个事件单位，其他终态按一个单位计，避免常规 pending 容量截断关键终态；
- P3 明确不读取 `BlockEntity` NBT 或 menu slot 获取容器内容；最小/广泛原版世界容器
  分别延期到 P5A/P5B，模组自定义 menu 属于 P8。
- 成功 `UseOnBlock` 不再被猜测成 `CONTAINER_CHANGED` 或推进容器 revision；真正容器
  内容变化事件与验证留到 P5。
- P4 planner 只读取主线程生成的不可变快照；未知/未加载区块不视为空气且不强制加载；
  所有搜索、队列、并发、结果、恢复和世界修改量均有硬上限；
- 路线 follower 只签发短租约 P2 动作并以真实身体终态判定到达；禁止传送、直接改速度
  或在路径计算完成时伪造成功；
- 导航、安全、动作和菜单全部绑定 generation；死亡、重生、换维度、卸载和停服会关闭
  旧代际状态；
- P4 不建立第二套生命、饥饿、护甲、效果或伤害系统，也不为未知模组伤害/效果给予免疫；
  主动进食、用药、反击、持盾与装备选择明确留给 P5/P8。

### 配置

- 新增 `actions.mailboxCapacity`、`actions.ledgerCapacity`、
  `actions.commandsPerTick`、`actions.activeCapacity` 和
  `actions.completionCapacity` 五个有界动作运行时配置；
- 新增 `inventory.viewDistance`，默认 `8.0` 方块，只控制 bot 自身背包 GUI 的同维度
  查看/编辑距离。
- 新增 `perception.*`：视觉/实体/听觉范围、脚下局部方块半径、彼此分离的权威投影与
  公开传感器预算（传感器侧含每 bot/全局限额）、事件/事实/revision 容量、活动窗口和
  MSPT 降级/恢复阈值；
- `perception.globalWorkPerTick` 最小值为 `64`，保证 1/4、3/4 分池后公开池仍可原子
  读取完整 41 槽背包；
- `perception.localBlockRadius` 默认 `1`、范围 `0..2`，只控制脚下已加载小邻域，不是
  未计费体素泛扫；
- `permissions.commandPermissionLevel` 继续控制 `spawn/list/remove`；P3
  `perception inspect/correct` 为避免配置降到 `0` 后泄露 bot 局部知识，固定要求原版
  权限等级 `2`。
- 新增 `safety.*` 每 Tick 探针、阈值、扫描、incident 和稳定恢复配置；
- 新增 `navigation.*` 快照、搜索、队列、follower、重算、stuck、补给和 Terrain Assist
  配置；Terrain Assist 默认关闭，单次默认最多破坏/放置各 4 格，策略硬上限为 8；
- P4 `navigation/safety` 管理命令固定要求原版权限等级 `2`，不随普通命令权限降低。

### 安全

- API Key 只写入 owner 客户端的独立本地明文存储，优先原子替换并尽力收紧文件权限；
- Key 不进入命令、聊天、Minecraft payload、服务端、世界数据、日志或诊断；
- 只有 roster 中持久 owner 可以配置对应 bot；服务器实例 ID 防止不同服务器错误复用绑定；
- 明确当前没有 DeepSeek Provider 或 HTTP 请求，凭据保存不会让 bot 变智能；
- 接受 ADR-0012，以客户端赞助凭据模式取代 ADR-0004 的服务端 secret 部署决定；ADR-0010
  仍然有效。

### 测试

- 新增 `ClientCredentialStoreTest`，覆盖 round-trip、共享 profile 与独立 agentId、
  server/owner 隔离、共享 Key 替换、解绑保留 profile、损坏/未知/超限 schema 拒绝、非法
  输入、错误脱敏，以及 Key 只进入 credential 文件。
- 新增动作契约、状态表、幂等、仲裁、运行时重入/背压/故障、输入、移动、交互指纹/前置
  条件、库存布局/会话和连接 telemetry 的纯 Java 测试来源；
- 新增生命周期、短程移动、放置/破坏/保护拒绝/攻击/丢弃/拾取以及 77 槽背包会话的
  NeoForge GameTest 来源和 structure fixture；
- Java 编译启用 `-Xlint:all -Werror`，CI 在 `clean build` 后运行
  `runGameTestServer`；
- 本轮本地严格编译、140/140 单元测试、同一持久世界连续两轮 19/19 GameTest 与
  `clean build` 已通过；远端 GitHub Actions Build #18 的标准
  `clean build runGameTestServer` 也已通过并上传 JAR。
- 新增 P3 DTO/设置/预算/MSPT、投影与双事件平面、revision/短期事实和活动推断单元测试
  来源；按 `@Test` 方法静态计数新增 43 个，完整源码为 183 个；这是当前源码计数，不是
  CI 日志直接报告的通过数；Build #28 的 Gradle `test` 已通过当前源码；
- 扩展 `BotActionRuntimeTest`，覆盖 P3 outcome sink 的 canonical 单次通知、replay 不重复
  和 sink 异常隔离；
- 新增并通过 8 个 P3 NeoForge GameTest，覆盖自身/背包快照、视觉遮挡、远方权威事件与
  actor 不进入 local cognitive stream、generation 新 stream、超远方块焦点不强制加载，
  未提交破坏/放置/丢弃候选不进入权威流、定向声音只进入目标 generation，以及已感知
  committed 方块变化使旧事实 stale；
- [PR #4](https://github.com/GreyTaiWolf/BotPlayer/pull/4) 的
  [Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181)
  在提交 `38851d1791b84e73705b302be8438e441c3f26ff` 使用 Temurin Java 21.0.11 执行
  `./gradlew --no-daemon clean build runGameTestServer`；
  `compileJava`、`compileTestJava`、Gradle `test`、27/27 GameTest、clean build 与
  JAR upload 全部通过，日志明确 `All 27 required tests passed`，P3 batch 为 8 tests；
  artifact 为 `botplayer-neoforge-1.21.1`（ID `8702261459`，`653364` bytes，SHA-256
  `90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac`）。
- 新增导航状态/成本/快照/A*、安全 incident、危险分类、补给门控和 Terrain Assist policy
  的纯 Java 测试；当前源码静态 `@Test` 计数为全仓 200；
- 新增 28 个 P4 NeoForge GameTest，直接覆盖真实输入导航、动态墙、门/跳跃/水/梯子、
  无路/补给、挖掘/短桥、悬崖/火/低空气/箭/TNT、僵尸仇恨与伤害、护甲/效果/属性、
  饥饿、动态伤害类型、玩家 Tick 以及死亡重生 generation 隔离；
- [PR #5](https://github.com/GreyTaiWolf/BotPlayer/pull/5) 的
  [Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204) 在提交
  `9fec0388c36870248a204d7ff21b1b663b62bebf` 使用 Temurin Java 21.0.11 执行完整
  `clean build runGameTestServer`；严格编译、Gradle `test`、55/55 GameTest、clean
  build 与 JAR upload 全部通过。

### 文档

- 重写 README，明确当前可用与不可用能力；
- 新增文档总目录、安装使用、配置和开发指南；
- 新增逐项原版能力矩阵和 1.0 发布门槛；
- 新增贡献规范和安全策略；
- 重写当前实现状态，区分已实现、部分完成和未实现；
- 记录虚拟连接仍缺发送回调、keepalive/teleport ack 和长时间在线验证；
- 补充当前真实配置键、命令语义、开发构件安装边界和排错；
- 扩充 ADR 索引及第三方研究/许可证边界；
- 明确 DeepSeek、技能和长期记忆仍未实现；P2 动作、bot 自身背包与 P3 感知按实际自动化
  验证状态报告；客户端 API Key 仍只完成本地管理基础。
- 明确临时名称 UUID 的大小写语义、审查分支过渡规则和双端开发测试边界。
- 将已经合并的 P0/P1 审查分支说明改为默认 `main` 开发基线；
- 同步客户端凭据、owner、服务器实例隔离、离线限制和明文存储风险。
- 新增 AI 玩家外部调研与 P2 重新基线，确定生命周期、控制协调、原子动作、背包会话、
  技能和 Goal/计划六层可组合状态机；
- 新增 P2 完成验收报告，分别记录已编码、实际验证和未覆盖边界；
- 将 P2 重排为 P2-A～P2-E，并明确 bot 自身背包属于 P2-D；最小原版世界容器、广泛原版
  容器/工作站和模组自定义 menu 分别延期到 P5A、P5B 和 P8。
- 新增 P3 感知与世界模型调研设计、P3 完成验收报告和 ADR-0013，固定有限感知、权威/
  认知双平面、有界 DTO、定向声音、无强制区块加载、scoped revision 与容器延期边界；
- 同步 README、实现状态、能力矩阵、配置、安装用法、开发指南、路线图和第三方研究边界；
  回写 Build #28 结果，并保留客户端、独立专用服和 soak 缺口。
- 新增 P4 导航与安全反射调研设计、P4 完成验收报告，并同步 README、实现状态、能力矩阵、
  配置、安装用法、开发指南、路线图和代理指南；准确记录自动化证据与 P5/P8 保留边界。

## 0.1.0-alpha.1 — 开发基线（2026-07-26，尚未正式发布）

### 新增

- Minecraft 1.21.1、NeoForge 21.1.244、Java 21 项目；
- `BotServerPlayer extends ServerPlayer`；
- 本地虚拟连接和 bot packet listener；
- 登录、重生和死亡完成观察的最小 Mixin；
- 在线生命周期管理、死亡延迟重生和停服清理；
- 临时名字派生 UUID 与原版 playerdata 读取路径；
- `/botplayer spawn|remove|list`；
- server 配置和 GitHub Actions Java 21 构建。

### 加固

- 生成失败时回滚 PlayerList、Level、连接和 runtime；
- 维度切换失败不错误更新 handle；
- 重生完成后再绑定新玩家实例；
- 被取消的死亡不会错误安排重生；
- 停服逐 bot 隔离异常，并保证 manager 引用移除。

### 已知限制

- 没有 roster、autoload、owner/ACL 和 generation；
- 没有单元测试或 GameTest；
- 没有背包 GUI、动作、感知、技能、DeepSeek 或记忆；
- 仅用于开发，不适合重要世界和公网生产服务器。
