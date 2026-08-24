# BotPlayer 更新日志

本文件记录已经进入仓库的变化。未来路线、想法和未完成任务不写成已发布功能；它们统一放在
[架构与路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## Unreleased

### 新增

- 新增 P5A 受限基础装备的直接 GameTest 源：经 lifecycle `SkillPlan` 分别覆盖请求的
  `EQUIP_BASIC_TOOL`、白名单 `EQUIP_EXACT_MAIN_HAND` 和显式普通
  `EQUIP_REQUESTED_OFFHAND` 都以精确原版 46 槽 `InventoryMenu` 的
  `WORLD_MENU_TRANSACTION` 完成，并验证空 crafting/cursor、选中栏/副手端点和物品守恒；普通
  副手盾牌、绑定诅咒的工具或已装备副手都在提交 action 前以 `FAILED/WORLD_CHANGED` fail-close。
  该测试源码仍待 Java 21 CI 与 NeoForge GameTest，通用 equipment/offhand 入口、装备策略与 P5
  总退出门仍未完成；
- 新增 P5C-S1 的 `/botplayer combat shield-hold <name>` 管理员窄入口：只在活动 bot 没有竞争
  owner、原版 `InventoryMenu`/空 cursor 静止且已经装备精确原版副手盾牌时，冻结该指纹并通过
  单个 lifecycle-owned Technique child 发出固定 8 Tick `UseItem(OFF_HAND, RELEASE_AFTER_HOLD)`。
  它没有目标、移动、换装、重试、反击或普通停止入口；纯 Java 回归与“无库存漂移/主手盾拒绝”
  GameTest 源已加入，但 Java 21 CI、NeoForge GameTest、真实受击格挡/耐久/斧破盾和 P5 总退出门
  仍待完成；
- 新增 P5A-M1a 原版 world-menu click dispatch 失败边界：`MenuTransaction` 现在会把已领取
  click 的 `clicked()` / `broadcastChanges()` 异常终结为 `CLICK_DISPATCH_FAILED`，不推进
  `confirmedClicks`、不把可能已经发生的变更当 ACK、也不允许下一 Tick 重派。适配器生产默认仍只走
  原版 click + broadcast，package test seam 可确定性模拟修改前或修改后抛错；异常后仅尝试一次
  exact native-menu reread 用于冻结诊断，identity/read 失败同样 fail-close，随后由既有 runtime
  cleanup 关闭 menu/cursor，绝不直接写库存或回滚猜测。纯 Java 回归覆盖前/后抛错、foreign/late
  observation 和 ACK/retry 拒绝；Java 21 CI、真实 `clicked()` 故障 GameTest、跨 menu 通用事务与
  lifecycle continuation 仍待完成，不能据此宣称 P5 总退出门完成；
- 接入 ADR-0041 的 P6-B1 R1 physical-attempt production bridge：protocol v3 注册 atomic S2C offer、
  C2S prepare ACK 与 S2C start grant，并删除旧 raw dispatch 生产入口。lifecycle 只在认证当前 owner、
  runtime/generation/agent、gate/ticket/nonce/identity 与 tick TTL 全部 exact 时 settle；grant 前 proposal
  保持 non-terminal 拒绝，direct R1 `accept(...)` 也被拒绝。client 仅 stage 后发送 ACK，唯一 Provider
  start 是 exact grant 的一次 claim，TTL、terminal、unbind、logout、death、retirement、shutdown 与 reaper
  都按 exact identity close；offer 可释放、settled grant 永不退款。当前增量仍待 Java 21 CI、NeoForge
  GameTest 与真实客户端/独立服丢包乱序 soak；它不是通用 AI bridge、真实 billing/usage reconciliation、
  聊天或 AI→世界执行，也不表示 P6 总退出门完成；
- 新增 P6-B1 `P6ReviewOnlyPhysicalAttemptGameTests`：在保留 NeoForge 配置的 mock owner connection
  上捕获真实 S2C offer/grant/cancellation，并经实际 server listener 注入 exact C2S prepare ACK；覆盖
  exact ACK 的 replay-stable grant、replacement 后的旧 ACK、unbind 和 tick-TTL expiry 的 stale ACK。
  该测试源码仍待 Java 21 CI/NeoForge GameTest，且不替代 payload codec 回归、真实客户端/Provider 或
  丢包乱序 E2E；
- 新增 ADR-0040 的 strict consumable 原版提交边界：活动 `BotServerPlayer` 的 `updateUsingItem` HEAD
  围栏覆盖所有 active strict `UseItem`，精确 `completeUsingItem()` invocation 前的再次复核和不可逆
  提交相位只限 natural completion；两个 native 点均在 action deadline/maxTicks 当 tick fail-close，避免
  先物理消费再被 runtime timeout。Finish 或 `PlayerTickEvent.Post` 才到达的取消不会以 mailbox 优先级覆盖
  已经消费的牛奶：Skill 保留 exact action receipt，success 先经 verifier 再结算请求的 cancel/preempt/pause，
  failed/stale 保持失败；已入 inbox 的 receipt 在同 tick plan deadline 前优先处理。Tick-event 漂移/取消、
  Finish/Post 取消、L0 pause/resume 与 pure timing fence 边界均有源码/隔离回归；本增量仍待 Java 21 CI、
  NeoForge GameTest 与 Minecraft 实机验证，不代表 P5 总退出门完成；
- 新增 ADR-0038 的 P5D-A5 loaded-world construction-site survey adapter：只在 authoritative server thread
  对 exact `ConstructionSiteBinding` 的 canonical Blueprint cells（最多 256）读取当前 tick 的已加载 state；先过
  build-height/`isLoaded` guard，unloaded/out-of-height/codec failure 为 `UNKNOWN`，air 为 `EMPTY`，其他以 registry
  ID + serialized full properties 为 `OCCUPIED` fingerprint。它不加载 chunk/ticket、不写世界、不读 NBT/容器，
  也不接 lease、材料、placement、Technique、Action、Skill、lifecycle、AI 或红石；snapshot/assessment 不是 site
  acceptance、许可或施工成功，P5D/P5 总退出门、Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0037 的 P6-B0 server-owned distributed physical-attempt handshake Contract：可信 server owner
  先为 exact dispatch reserve，再仅由 exact prepare ACK settle 并返回 replay-stable start grant；identity 同时
  绑定 server instance、owner、safe dispatch receipt、server attempt ID、nonce、client not-after 与更早的
  physical-start deadline。offer 的 close/expiry 只 release 未开始 reservation；一旦 `SETTLED`，grant 丢失、
  disconnect、terminal、close 与 periodic reaper 都只 tombstone、绝不退款。client-local gate 只 handoff 一次，
  queued Provider/HTTP start 必须原子 `tryClaimPhysicalStart()`，同时复核 binding/session/deadline/clock，重复、
  close/rebind、expiry 和 rollback 均失败关闭。它不发送 packet，不接 authenticated session、client queue、
  lifecycle/reaper、Provider/HTTP、真实 billing/usage、Skill、Action 或 Minecraft；P6 总退出门、Java 21 CI 与
  真实客户端/Provider E2E 仍待完成；
- 新增 ADR-0036 的 P5D-A4 pure Java blueprint placeable-item declaration Contract：每一种完整 expected
  `BlockStateFingerprint` 必须有一条显式 caller-supplied `PlaceableItemEvidence`，缺项、foreign/duplicate
  state 或按 blockId 猜 itemId 都失败关闭；它只按显式 itemId 与 permanent/temporary 类别派生 canonical declared
  quantity，不读取 registry、背包/容器或世界，也不证明 item 可用、是 `BlockItem`、能形成该 state、已预留或会被
  消耗。它不接 site/lease、材料 availability、placement、Technique、Action、Skill、checkpoint 或红石，P5D
  仍未实现，当前 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0035 的 P5D-A3 pure Java candidate construction-site survey/assessment Contract：一个 exact
  `ConstructionSiteBinding` 的所有真实 Blueprint target 必须恰好一次 canonical observation，`UNKNOWN`、
  `EMPTY` 与带 fingerprint 的 `OCCUPIED` 严格配对；assessment 只能从 complete survey 重算，已知 occupied
  mismatch 优先于 unknown，并按 preserve/temporary-ownership/human-confirmation policy fail-closed。
  `ACCEPTED_CANDIDATE` 只表示 caller-supplied 有界 evidence 的结构兼容，不是 loaded-world fact、accepted
  site、lease、ownership proof、human confirmation 或执行权限；它不读 Minecraft、检查保护/危险、预留材料、
  placement、Technique、Action、Skill、checkpoint 或红石，P5D 仍未实现，当前 Java 21 CI 与 Minecraft 实机
  验证仍待完成；
- 新增 ADR-0034 的 P6-A1b 受限 physical-retry hook：`RetryingAiProvider` 新增显式
  `completeBudgeted(...)`，只接受完整可信 `AiRetryAttemptBudgetContext`，并在每次真实
  `delegate.complete(...)` 前 fresh reserve、final cancel/deadline/circuit check 与 settle。只有
  `SETTLED` 才调用一次 delegate；settle 前 cancel/expiry/ledger/circuit 拒绝 release，settle 后的
  cancel、timeout、sync throw、null stage、callback failure 或 response/error 均不退款。普通
  `AiProvider.complete(...)`、Scheduler、HTTP/client session、network、Lifecycle、Skill、Action 与
  Minecraft 均未自动接线；它不是 generic bridge、真实 billing/usage reconciliation 或 P6 完成，当前
  Java 21 CI 与真实 Provider E2E 仍待完成；
- 新增 ADR-0033 的 P5D-A2 pure Java candidate construction-site binding Contract：完整 immutable
  `ConstructionWorkPlan` 以非零 siteId、dimension+anchor 绑定到由全部 Blueprint cell checked translation
  派生的 inclusive bounds，只有完整 package key 与真实 cell offset 可读取。它不做 survey/accepted site、
  chunk/world/保护检查、材料预留、checkpoint/human override、PlacementCandidate、Technique、Action、Skill、
  真实放置或红石；P5D 仍未实现，当前 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0032 的 P6-A1a pure Java physical-retry attempt budget context：可信 bridge 未来必须显式
  传入 ledger、完整 request binding、已接受 admission 与 upstream deadline；retry wrapper 每次 physical
  attempt 另传自身 deadline，账本统一取 upstream/retry/policy TTL 的最早值并返回 fresh exact reservation。
  ADR-0034 已把它接到 `RetryingAiProvider` 的显式 opt-in physical hook；它仍没有 production
  Scheduler/client session/transport/HTTP/Lifecycle bridge，也不是实际计费、预算统计、通用 AI bridge 或 P6
  完成，当前 Java 21 CI 与真实 Provider E2E 仍待完成；
- 新增 ADR-0031 的 P6-A0 有界纯 Java token-reservation ledger Contract：每个 runtime-local
  `(ownerId, botId, agentId)` scope 以完整 request binding 和随机 exact reservation 管理已接受
  `AiModelAdmission` 的输入/最大输出/总 token；`reserved + committed` 受上限约束，release/expiry
  只释放未开始 reservation，物理调用前 settle 后永不因 response、error、cancel、timeout 或
  `AiTokenUsage` 退款。同一 logical request 的每次 future physical retry 必须有新 reservation。
  它不接 Scheduler/Retrying Provider/Provider、client session/transport、HTTP、Lifecycle、Skill、Action
  或 Minecraft，也不是实际计费、预算统计或通用 AI bridge；当前 Java 21 CI 与真实 Provider E2E 仍待完成；
- 新增 ADR-0030 的 P5D-A1 有界纯 Java construction work-package Contract：每个 package 必须完整绑定
  `blueprintId + revision + contentHash + ordinal`，在一个 Blueprint 内精确覆盖一次；小蓝图只能有一个
  `1..64` cell package，较大蓝图每包 `16..64` cell、总数最多 16，并对完整 key prerequisite 验证 DAG
  与稳定拓扑序。材料输出仍只是按目标 blockId/permanent-temporary 的结构性聚合，绝不映射/预留/消耗
  inventory。它不接 site、world、placement、Technique、Action、Skill、checkpoint、玩家修改或红石，
  因而 P5D 仍未实现；当前提交的 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0029 的 P5D-A0 有界纯 Java Blueprint 数据 Contract：schema-v1 只接受 1–256 个唯一、
  canonical cell，限制 relative offset/span，派生确定性 SHA-256 content hash，并按目标 blockId 与
  permanent/temporary 聚合计划方块需求。它不含 BlockEntity NBT、不把 blockId 映射为 inventory item，
  也不接 site、材料预留、work package、Technique、Action、Minecraft 世界、建筑或红石；P5D 仍未实现，
  当前提交的 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0028 的 P6-C3 owner-thread 通用 proposal review transaction：完整 immutable C2S
  correlation 必须先由 coordinator ledger 预检，漂移/replay/replacement 的 payload 不进入 gate；gate
  terminal receipt 必须 exact-close 同一 ledger binding，gate/ledger 分歧只精确清理已预检 binding 并
  保持 coordinator fail-closed，绝不按 botId 宽泛关闭。结果仅供 server side 取得 review 与已移除
  binding，accepted proposal 仍未执行；不改 R1、Network、Lifecycle、Client、Scheduler、Skill、Action 或世界。
  当前提交的 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0026 的 P6-C2 登记后 Error cleanup：deadline、Provider factory/complete、completion
  registration 与 completion-time 本地再校验的受信任 `Error`，会先按精确 session 摘除、取消并在锁外
  至多一次投影已决定的安全 terminal receipt（setup/再校验/handoff Error 为 `FAILED`，不会因后续 cleanup
  Error 覆盖已决定的 `SUCCEEDED|CANCELLED`）；同步 setup 或 attachment 中同步暂存后由 `accept` 激活的
  completion Error 会随后重抛，attachment 返回后才到达的 callback 则只遵循 `CompletionStage` 的
  exceptional-stage 语义（本地 controller 不承诺其 host-level fatal propagation）。
  callback 在 `whenComplete` 正常返回前只暂存，防止“先 callback、后 attachment Error”发布不可撤回的
  provisional proposal。ADR-0027 另将 `ProposalHandoff` 内同步完成另一 session 的间接 completion
  失败关闭：嵌套 session 不会在外层 lock 内再次 handoff/token/observe，外层与嵌套 session 都为
  `FAILED`，外层 queue lease 立即失效，随后统一在锁外清理。不发送 packet、不接 Lifecycle 或通用
  client-sponsored bridge；当前提交 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0025 的受限 `Technique → Action` 预绑定与未注册 lifecycle Action runtime adapter Contract：只有单一 lifecycle coordinator
  正在分派的精确活动 child ticket 才能签发 opaque `TechniqueActionPermit`；permit 冻结
  run/ticket/revision、generation、Action origin/kind/channel/deadline/idempotency 与低于 L0 的 priority，
  route 默认拒绝未 allowlist 的 kind，且 route-bound Port 对 permit 只允许一次 ingress。它只允许
  Port 按 permit 提交、读取 terminal evidence 或 exact cancel-or-contain，不公开 raw
  Action/world DTO，不接现有 SafetyService 或 construction route；adapter 只委派既有 Action runtime
  的入队、full-envelope exact terminal drain 和 cancel-or-contain（同三元组不同 envelope 一律失败关闭），拒绝 ingress 使用本地 fenced receipt，
  且同步 close/preempt 后复核 exact active child。它不注册 construction route，因而不表示 `GroundPlace`、
  蓝图、红石或 P5D 已实现；当前提交的 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 P6-C2 客户端本地终态观察 Contract：`ClientAiRequestSessionController` 可选注入、默认
  no-op 的 `ClientAiRequestTerminalObserver`，只在已登记 session 首次精确终结后交付
  `AiRequestDispatchReceipt + SUCCEEDED|FAILED|CANCELLED`。安全 receipt 不含 nonce、owner、
  prompt/schema、Provider 响应、Throwable、凭据或取消句柄；观察器 runtime 异常隔离，token
  listener 的 `Error` 仍先完成本地清理与观察再重抛。它不发送 C2S/S2C payload，不接
  coordinator、Lifecycle、Scheduler 或 R1，也不表示 proposal 已送达、server gate 接受或世界
  执行成功；当前提交的 Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 ADR-0017 的窄 Contract：服务器生命周期以一个 owner-thread
  `TechniqueLifecycleCoordinator` 管理所有已注册 Technique；已有有限自卫单击 bridge 迁为
  一条受限 Action 路由，保留精确 child ticket、generation 关闭与 L0 抢占的失败关闭清理。
  该 Contract 不新增任何 P5D 建筑/红石、通用 Technique 路由或 AI 世界执行，当前提交的
  Java 21 CI 与 Minecraft 实机验证仍待完成；
- 新增 P6-R1 owner 手动只读审阅往返本地 consent，已通过
  [Build #354](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31756795111) Java 21 自动基线：
  物理客户端仅从
  `review-only-v1.json` 读取 `reviewOnly.enabled=false` 默认位；启用时只安装代码固定的
  `deepseek-chat` / `CHAT|TOOL_CALLS` / 零参数 `botplayer_review_snapshot` Provider，绝不保存或
  同步 endpoint、模型、profile、工具、prompt 或 Key。禁用/重载会取消本地请求、推进 connection
  epoch 并清空 factory，不发送 C2S 控制包；服务端 R1 gate 在通用解析前终态拒绝自由 prose 或
  非精确工具形状，接受结果仍只摘要并丢弃，不进入 Action/Skill/世界执行；真实客户端/
  Provider E2E 仍待验证；
- 新增 P5B 原版通用容器的受限闭环：严格白名单的普通单箱/双箱、木桶和原版潜影盒复用真实
  3×9/6×9 原版 menu 点击事务，支持双向全量/指定数量转移，并以完整快照、物品守恒、关闭/空 cursor
  与方块变化失败关闭约束；未知或模组 menu 仍拒绝。新增 Unit 与 NeoForge GameTest 矩阵；
  Build #354 已完成自动基线，真实客户端/专用服验证仍待完成；
- 新增 P5B 受限末影箱纵切：仅接受精确原版 `minecraft:ender_chest` 状态和 opener 类型，真正的
  27 槽账本必须是当前 Bot 自己打开的 `ChestMenu` 容器；每次点击前后和关闭前都重验同一原生
  menu 实例与私有账本身份，重入替换或状态漂移在点击前失败关闭。支持单次全量/指定数量转移；
  取消只承诺原版关闭后的空 cursor 与 Bot 自己 player+末影账本总量守恒，不承诺逐槽回滚。
  同一物理方块上的不同 Bot 在顺序操作中保持账本隔离；当前 skill 租约仍保守地按坐标串行。
  [Build #362](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/31778579094) 已通过 Java 21
  自动基线、161 项常规 GameTest 和两阶段重启；真实客户端、独立专用服与多 Bot soak 仍待验证；
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
  执行一次点击；取消或 cleanup 收口到经证明的初始或最终安全端点。另新增通用
  `InventoryMenu SWAP_SEQUENCE`：允许 1～16 次点击、最多 8 个槽位，前向与 cleanup
  每 Tick 只派发一次原生点击，并以跨 Tick `PENDING`、固定端点、双 ticket 阻塞和精确
  progress revision 收口；generic equipment/offhand 槽仍 `UNSUPPORTED`，盔甲专用路径
  保持独立。[PR #6](https://github.com/GreyTaiWolf/BotPlayer/pull/6) 的
  [Build #137](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30713366812) 已通过
  Java 21 `clean build`、Gradle `test`、83/83 GameTest 和 JAR 上传；真实五步场景直接
  覆盖上述通用事务合同。源码静态计数为 380 个 JUnit `@Test` 方法、26 个 P5 GameTest
  与 353 个 Java 源文件，不冒充 CI 日志逐项执行数。P5A 退出门仍未通过；跨 menu 统一
  事务、`clicked()` 故障注入、生命周期 `PENDING` continuation、TaskSensor/Reservation
  生产接线、Checkpoint、工具/副手、自卫、craft/chest/furnace/DAG，以及两次启动、独立
  专用服和多 Bot soak 仍未完成。
- 新增原版死亡消费的 V2 pre-drop tombstone 与 playerdata handoff：只有真实进入
  `keepInventory=false` 背包消费才登记事务，死亡体和 successor 分别执行两次精确保存、
  主副本/目录刷盘和有界回读；精确经验交接、4 次/20 Tick 有界重试、全服同 UUID topology
  验权和旧 predecessor 永久 no-save poison 防止已掉落物品被旧 NBT 或迟到保存复活。
  该协议优先防复制，不是掉落实体的 exactly-once 世界 WAL；真实二次启动与断电仍未验收。
- 接受 [ADR-0016](docs/adr/0016-durable-vanilla-death-consumption-handoff.md)，冻结死亡
  tombstone、双份 playerdata 提交、经验 handoff、失败关闭与回滚边界。

### 加固

- 严格消耗品的 Skill 取消现在携带完整不可变 `ActionEnvelope`：在普通 Action mailbox 于 bot
  `doTick()` 之后 drain 前，生命周期只为同一活动 strict `UseItem` 记录一次原版消费前拒绝标记；
  `LivingEntityUseItemMixin` 在原版 `updateUsingItem` 入口读取该标记并 release/stop，因而最后一次
  自然使用 Tick 的 `PlayerTickEvent.Pre` 取消不会先消费牛奶或清除效果。同 generation 的
  idempotent alias、围栏不匹配或 exact cancellation ingress 失败都会同步隔离该 generation，绝不让
  Skill 的已取消终态与未受控的物理动作并存；已接受取消与快照漂移也分别记录，避免低 command
  budget 将前者误判为预条件失败。它不直接写库存/效果，不扩展普通使用，也不表示 P5D 已实现；
  当前增量仍待 Java 21 CI 与 Minecraft 实机验证；
- P5 lifecycle Action 收口改为 full-envelope exactness：有限自卫 bridge 只会按其冻结的 immutable
  `ActionEnvelope` 查询 terminal 并委派 `cancelOrContainExact`，同一 `(botId, generation, actionId)` 的
  foreign envelope 不能成为 child evidence 或安全回执；已 release 的 P5D adapter permit 不再保留历史
  P2 cancellation receipt，避免历史回执按容量挤掉 live containment proof；从未入队的本地 fenced
  receipt 仍按 opaque permit 有界保留且绝不重入 P2。当前增量仍待 Java 21 CI 与 Minecraft 实机验证；
  这不新增建筑 route，也不表示 P5D 已实现；
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
- 当前 GameTest 在 Build #163 实际运行 40 个 batch，并在默认
  `server_player.maxBots=8` 下完成；Build #133/#135 暴露的 batch 超配已通过定向声音、
  绑定拒绝和生命周期场景拆批修复，未提高 `maxBots` 掩盖资源合同。
- 死亡保存许可改为一次性外层 owner，同步递归保存一律抑制；生命周期事务 fence 与旧
  body 永久 poison 分离，successor 只释放自己继承的 fence。ItemEntity 回调内嵌套
  `die()` 只允许外层执行一次物品/经验掉落，迟到 predecessor 的死亡与保存不能扰动当前
  ACTIVE successor；修改 `AUTO_RESPAWN` 的场景拆为独立 batch，消除全局配置竞态。
- `PlayerList.save` 的通用围栏不再消费仅属于 `PlayerList.remove` 内原版保存的一次性
  门闩；死亡精确保存许可与持久 no-save fence 仍在通用保存入口 fail-closed，避免普通保存
  抢先放开随后 remove 的隔离保存。
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
- 明确保存凭据不会自动启动模型请求；默认关闭的 P6-R1 是唯一固定、本地 opt-in 的
  review-only HTTPS 例外，回传只摘要并丢弃，不让 bot 变智能；
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
- [Build #163](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30897970406) 使用
  Temurin Java 21 执行完整 `clean build runGameTestServer`；Gradle `test`、91/91
  GameTest、clean build 与 JAR 上传均通过，日志实际运行 40 个 batch。当前源码静态计数
  为 437 个 JUnit `@Test` 方法、34 个 P5 GameTest 和 378 个 Java 源文件；这些不是 CI
  日志逐项执行数，P5A 退出门仍未关闭。

### 文档

- 重写 README，明确当前可用与不可用能力；
- 新增文档总目录、安装使用、配置和开发指南；
- 新增逐项原版能力矩阵和 1.0 发布门槛；
- 新增贡献规范和安全策略；
- 重写当前实现状态，区分已实现、部分完成和未实现；
- 记录虚拟连接仍缺发送回调、keepalive/teleport ack 和长时间在线验证；
- 补充当前真实配置键、命令语义、开发构件安装边界和排错；
- 扩充 ADR 索引及第三方研究/许可证边界；
- 新增 ADR-0016，并同步死亡持久化的当前实现、故障边界与 Build #163 证据；
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
