# BotPlayer 架构决策记录（ADR）

ADR 用于记录会长期影响代码、数据、兼容性、安全或许可证的决定。实现与已接受决策冲突时，
必须先新增 ADR，再修改代码和总架构文档，不能静默偏离。

## 状态

| 状态 | 含义 |
|---|---|
| Proposed | 正在讨论，不能作为稳定依赖 |
| Accepted | 已接受，代码和后续设计必须遵守 |
| Superseded | 已被新的 ADR 取代，但保留历史 |
| Rejected | 已评估并否决 |

## v1 架构基线

最初 11 项决定在项目编码前一起形成，因此集中保存在
[总架构文档第 24 节](../ARCHITECTURE_AND_ROADMAP_CN.md#24-关键架构决策记录)。

| 编号 | 决策 | 状态 | 当前落实 |
|---|---|---|---|
| ADR-0001 | bot 主体继承 `ServerPlayer` | Accepted | P1 已落实 |
| ADR-0002 | LLM 高层决策，Java 确定性执行 | Accepted | 架构边界已落实，动作层待 P2 |
| ADR-0003 | 1.21.1 只使用窄、可验证的版本 Mixin | Accepted | P1 已落实 |
| ADR-0004 | 客户端只负责 UI，AI 与 secret 在服务端 | Superseded | 被 ADR-0012 取代；服务端权威原则保留 |
| ADR-0005 | SavedData 索引 + SQLite 长期记忆 | Accepted | 待 P1/P7 |
| ADR-0006 | 外部技能只允许声明式 DAG | Accepted | 待 P5 |
| ADR-0007 | 默认有限感知，不做全知 bot | Accepted | ADR-0013 细化；P3 自动化退出门已通过 |
| ADR-0008 | 模组兼容采用 C0–C3 分级 | Accepted | 待 P8 |
| ADR-0009 | 不把 LGPL 寻路源码并入 MIT 核心 | Accepted | 当前无该依赖 |
| ADR-0010 | P0–P2 通过前不接 DeepSeek | Accepted | 当前遵守 |
| ADR-0011 | 在 `ServerPlayer.die` TAIL 确认死亡 | Accepted | P1 已落实 |
| [ADR-0012](0012-client-sponsored-ai-credentials.md) | 客户端赞助的 AI 凭据与每 bot 独立智能体 | Accepted | 客户端凭据、受限 Provider/HTTP 传输与默认关闭的 P6-R1 审阅路径已通过 Build #362 自动基线；真实客户端/Provider E2E、通用聊天与 AI→世界执行仍待 P6 |
| [ADR-0013](0013-finite-perception-two-plane-world-model.md) | 有限感知、双事件平面与有界世界模型 | Accepted | P3 实现已编码并通过 Build #28 自动化退出门 |
| [ADR-0014](0014-bounded-navigation-and-safety-plane.md) | 有界导航快照、分段路径与独立 L0 安全平面 | Accepted | P4 自动化退出门已通过 |
| [ADR-0015](0015-bounded-skill-runtime-and-menu-transactions.md) | 有界技能运行时与统一菜单事务 | Accepted | 当前连续集成分支的受限 P5 纵切已通过 Build #362 自动基线；当前 M1a 已编码 world-menu click dispatch exception 的 pure fail-closed 边界，仍待 Java 21 CI/真实故障 GameTest；P5 总退出门与跨 menu 通用事务仍未完成 |
| [ADR-0016](0016-durable-vanilla-death-consumption-handoff.md) | 原版死亡消费的耐久交接与失败关闭 | Accepted | P5 死亡纵切已由 Build #163 验证 |
| [ADR-0017](0017-bounded-player-technique-runtime.md) | `Action → Technique → Skill` 有界玩家技术动作层 | Accepted | 旧有 Technique runtime 与有限自卫单击窄 bridge 已通过 Build #362 自动基线；当前分支已迁为单 lifecycle coordinator Contract，仍待该提交 Java 21 CI；跳劈、真实施工和广泛战斗仍未实现 |
| [ADR-0018](0018-strict-consumable-pre-use-fence.md) | 严格消耗品的原版使用前围栏 | Superseded | 被 ADR-0040 取代；其旧 P5B 牛奶纵切已通过 Build #362 自动基线 |
| [ADR-0019](0019-owner-manual-review-only-ai-round-trip.md) | Owner 手动只读 AI 审阅往返 | Accepted | P6-R1 固定快照/本地 review-only Provider 纵切已通过 Build #362 自动基线；真实客户端/Provider E2E 仍待 |
| [ADR-0020](0020-bounded-ai-scheduler-supervisor.md) | 有界 AI 调度监督器与 Provider-start 围栏 | Accepted | P6 纯 Java scheduler 的受信任有界 lane 合同已通过 Build #362 自动基线；尚未接入生产 lifecycle/client-sponsored bridge |
| [ADR-0021](0021-client-sponsored-request-correlation.md) | 客户端赞助 AI 请求的单一关联身份 | Accepted | gate→dispatch→scheduler 的纯 DTO 绑定已编码；通用客户端 Provider bridge 与世界执行仍未接线 |
| [ADR-0022](0022-client-sponsored-request-ledger.md) | 客户端赞助请求账本的精确生命周期 | Accepted | 通用 binding 的纯 Java 生命周期账本已编码；不发送网络包、不启动 Provider/Scheduler，通用 bridge 与世界执行仍未接线 |
| [ADR-0023](0023-atomic-aim-and-place-action-contract.md) | 原子瞄准并放置方块动作合同 | Accepted | P2 共享动作合同已编码；尚未接入 Technique、Skill、蓝图、AI 或 P5D 建筑能力 |
| [ADR-0024](0024-client-sponsored-request-coordinator.md) | 客户端赞助请求的 server-thread 协调器与有界终态邮箱 | Accepted | gate+ledger 的纯 Java owner-thread 协调器与默认 no-op 的客户端安全 terminal-observation Contract 已编码；不接 Lifecycle/Network/通用 Client bridge/Scheduler，世界执行仍未接线 |
| [ADR-0025](0025-restricted-technique-action-prebinding-port.md) | 受限 Technique→Action 预绑定 Port | Accepted | 精确 child permit、Action provenance 与 L0 以下 priority，以及未注册的 lifecycle Action runtime adapter Contract 已编码；尚无 approved construction route 或 P5D 世界能力 |
| [ADR-0026](0026-client-session-post-admission-error-cleanup.md) | 客户端 AI 会话登记后的 Error 清理 | Accepted | P6-C2 已登记 session 的 trusted Error 先精确收口、锁外已决定的安全终态观察；同步 setup/inline signal 重抛，异步 callback 保留 CompletionStage exceptional-stage 语义；不接 Network/Lifecycle/通用 bridge |
| [ADR-0027](0027-proposal-handoff-indirect-completion-reentry.md) | `ProposalHandoff` 间接 completion 重入围栏 | Accepted | 同线程嵌套 completion 会使外层与嵌套 session 失败关闭、释放外层 queue lease，并在锁外完成 token/observer cleanup；不发送 packet 或接入 generic bridge |
| [ADR-0028](0028-client-sponsored-proposal-review-transaction.md) | 通用 client-sponsored proposal 的精确 owner-thread 审阅事务 | Accepted | coordinator 已有 ledger-first C2S correlation precheck、gate terminal 后 exact ledger close 与分歧 fail-closed 合同；不接 Lifecycle/Network/Client/Scheduler/R1 或世界执行 |
| [ADR-0029](0029-bounded-blueprint-data-contract.md) | 有界蓝图数据契约 | Accepted | P5D-A0 已有 1–256 cell、canonical content hash 与计划方块需求的纯 Java DTO；不含 NBT/物品映射/世界或施工接线，P5D 仍未实现 |
| [ADR-0030](0030-bounded-construction-work-package-contract.md) | 有界施工工作包图合同 | Accepted | P5D-A1 已有完整 Blueprint identity 绑定、exact-cover、16 package 上限和稳定拓扑的纯 Java DTO；不含 site/材料预留/Technique/Action/world，P5D 仍未实现 |
| [ADR-0031](0031-bounded-token-reservation-ledger.md) | 有界 AI token 预留账本 | Accepted | P6-A0 已有 scope-local、并发安全的 conservative token reservation accounting Contract；ADR-0034 的 explicit retry hook 已使用它，但仍未接 Scheduler/client session/network，不是实际计费或通用 bridge |
| [ADR-0032](0032-physical-retry-attempt-budget-context.md) | 物理重试尝试预算上下文 | Accepted | P6-A1a 固定完整 trusted budget context 与 upstream/retry/TTL 最早 deadline；ADR-0034 的 opt-in hook 使用它，仍无 production Scheduler/client bridge |
| [ADR-0033](0033-candidate-construction-site-binding-contract.md) | 候选施工站点绑定 | Accepted | P5D-A2 只将 exact WorkPlan/Blueprint 绑定到 dimension+anchor 的派生 bounds 与已知 cell target；不 survey/accepted/lease/material/placement/world，P5D 仍未实现 |
| [ADR-0034](0034-budgeted-physical-retry-hook.md) | 受限物理重试预算调用点 | Accepted | P6-A1b 只为显式 `RetryingAiProvider.completeBudgeted(...)` 在每个 physical delegate retry 前 reserve/settle；普通 SPI、Scheduler、client bridge、真实计费与 P6 总完成仍未接线 |
| [ADR-0035](0035-bounded-construction-site-survey-assessment-contract.md) | 有界候选施工站点调查与评估 | Accepted | P5D-A3 只对 caller-supplied exact target evidence 作 fail-closed 纯 Java assessment；`ACCEPTED_CANDIDATE` 不是 world read、accepted site、lease、ownership/human proof 或 placement，P5D 仍未实现 |
| [ADR-0036](0036-bounded-blueprint-placeable-item-evidence.md) | 有界蓝图可放置物品声明 | Accepted | P5D-A4 只要求 full-state 的 explicit item declaration 并派生 declared quantity；不猜 blockId→itemId，也不是 registry proof、inventory/reservation、placement 或 P5D 完成 |
| [ADR-0037](0037-server-owned-physical-attempt-handshake.md) | 服务器拥有的跨边界物理尝试握手 | Accepted | P6-B0 只增加有界 server-owned offer/ACK/settle/start-grant Contract 与 client-local one-claim fence；没有 network/session/Provider/HTTP/lifecycle bridge，也不是真实计费或 P6 完成 |
| [ADR-0038](0038-loaded-world-construction-site-survey-adapter.md) | 已加载世界候选施工站点调查适配器 | Accepted | P5D-A5 只在 server thread 对 exact binding 的已加载 cell 生成 immutable survey；不加载 chunk、不写世界，也不是 lease/placement/Technique/Action/Skill 或 P5D 完成 |
| [ADR-0039](0039-construction-site-spatial-lease-adapter.md) | 施工站点空间租约适配器 | Accepted | P5D-A6 只将 exact binding 的 bounds 映射为最多 8 个 owner-thread `WORK_AREA` TTL tile lease；不预留材料/临时区，也不接 Action/Technique/Skill/world 或 P5D 完成 |
| [ADR-0040](0040-strict-consumable-commit-boundary.md) | 严格消耗品的原版提交边界与精确终态优先级 | Accepted | `HEAD` 围栏所有 active strict `UseItem`，completion/`ENTERED` 只限 natural；两个点均拒绝 action deadline/maxTicks 当 tick 的物理消费。新增回归仍待 Java 21/NeoForge CI 与实机验证 |
| [ADR-0041](0041-r1-physical-attempt-transport-bridge.md) | R1 物理尝试的有界传输桥接 | Accepted | P6-B1 已将 v3 atomic offer/prepare ACK/start grant 接入 R1 production bridge：认证 ACK 后 exact settle，grant 前不得 proposal/provider start，exact TTL/terminal/logout/rebind/death/retirement/shutdown close；仍待 Java 21 CI、GameTest、真实客户端/独立服 E2E，不构成通用 bridge、billing/usage 或 P6 完成 |
| [ADR-0042](0042-server-thread-blueprint-default-block-item-validator.md) | 服务端线程蓝图默认方块物品声明验证器 | Accepted | P5D-A4-R1 只核验现有完整 declaration 的显式 item 是否为 non-air `BlockItem` 且 default full state 精确相等；不证明 `useOn`/contextual placement、inventory/reservation 或 P5D 完成，当前仍待 Java 21 CI/GameTest/实机验证 |
| [ADR-0043](0043-server-thread-construction-material-availability-observation.md) | 服务端线程施工材料可用性观察 | Accepted | P5D-A7 只在活动精确 Bot body 的 empty native `InventoryMenu` 以 default fingerprint 读取 main/hotbar 瞬时 aggregate availability/shortage；unavailable 不等于零库存，不读 world 或容器内容、不预留/移动/放置材料，也不是 P5D 完成，当前仍待 Java 21 CI/GameTest/实机验证 |
| [ADR-0044](0044-bounded-construction-work-package-material-demand.md) | 有界施工工作包显式材料需求 | Accepted | P5D-A8 只从 exact plan 的真实 package 与 A4 evidence 重导 `(itemId, materialClass)` demand；拒绝 blueprint/key/requirement drift，不把 A7 availability 变为 allocation/reservation/permit，也不是 P5D 完成，当前仍待 Java 21 CI |
| [ADR-0045](0045-work-package-material-availability-projection.md) | 工作包材料可用性只读投影 | Accepted | P5D-A9 只将一个 A8 demand 与同 evidence 的 A7 snapshot 作 item-total projection；单 package sufficient 不能当 allocation/reservation/permit，也不是 P5D 完成，当前仍待 Java 21 CI |
| [ADR-0046](0046-bounded-work-package-candidate-site-target-projection.md) | 有界工作包候选站点目标投影 | Accepted | P5D-A10 只将 binding 内真实 package 的 exact cells 投影为 candidate coordinate manifest；不重分包、不含 survey/lease/material/placement/permit，也不是 P5D 完成，当前仍待 Java 21 CI |
| [ADR-0047](0047-bounded-work-package-site-survey-projection.md) | 有界工作包站点调查证据投影 | Accepted | P5D-A11 只将 A10 exact package targets 与同 binding 的 complete raw survey 逐 cell 配对；UNKNOWN 不变、不产生 package-local assessment/readiness/permit，也不是 P5D 完成，当前仍待 Java 21 CI |

“待 Pn”表示决策已经接受，但对应功能尚未实现。ADR-0012 只取代 ADR-0004 中“AI 与
secret 必须只在服务端”的部署决定；客户端不拥有世界权威、ADR-0010 禁止当前阶段接入
DeepSeek 等边界仍然有效。ADR-0013 落实 ADR-0007：服务器全服审计与某个 bot 的认知
必须分离，且 P3 不读取世界容器内容。

ADR-0015 在此基础上固定 P5A 的 Skill FSM/DAG、有限任务查询、Safety handoff、统一
menu 事务、检查点和运行时资源预留；对应纵切片尚未通过前仍不得标记能力完成。
ADR-0016 固定 `keepInventory=false` 消费的 pre-drop tombstone、双份 playerdata 提交、
一次性经验 handoff、旧 body 永久禁存和有界失败恢复；Build #163 只验证单进程故障边界，
真实跨进程恢复仍未验收，且它不承诺掉落实体 exactly-once。

ADR-0017 在 P2 原子 Action 与 P5 任务级 Skill 之间增加短生命周期 Technique FSM，用于
渐进瞄准、跳劈、侧移攻击、蹲边放置、垫柱和临时脚手架。Technique 只编排已有 Action、
Navigation 和 menu 合同，允许在互不冲突的 `ActionChannel` 上持有最多三个有界 child，
且始终受 generation、L0 安全、权限、世界 revision、cleanup 和真实结果证据约束。当前
唯一生产接线仅接受已有有限自卫已经授权的一次 `MELEE_ATTACK`，并将其不可变地绑定到
一个 `AttackEntity` child；它没有目标选择、移动、装备、重试、连击或泛化 Action 路由。
当前 lifecycle 只驱动一个 `TechniqueRuntime`，有限自卫 bridge 作为其受限路由；因此即使
该窄 bridge 已编码，也不表示任何战斗或建筑能力成熟度提升。

ADR-0021 固定 client-sponsored 通用请求必须先由 server gate 生成唯一 requestId/nonce，再用
同一绑定构造客户端 dispatch、Scheduler 请求和精确取消。该合同不开放通用聊天或 AI→世界
执行；P6-R1 仍是独立的只读路径。

ADR-0022 让 server-thread coordinator 以同一不可变 binding 保存通用请求的活动生命周期；
普通打开不隐式替换，替换、过期、退出和停服都返回精确旧 binding 供下游清理。它本身不发包、
不创建 Provider/Scheduler，也不改变 P6-R1 或世界执行边界。

ADR-0023 增加独立于旧 `PlaceBlock` 的 P2 原子瞄准放置动作：它在同一 `LOOK`、主手和交互
租约内完成瞄准、最终重验、原版包和精确验证。它只是后续受限 Technique 的共享前置合同，
不创建 P5D 的 Technique/lifecycle 接线，不开放导航、脚手架、蓝图、红石或 AI 建筑。

ADR-0024 将 ADR-0022 的 gate 和 immutable binding 账本收敛到一个 server-thread owner，并加入
受限全局容量与不阻塞的安全终态邮箱。P6-C2 还在客户端本地 session 增加默认 no-op 的安全终态
观察；两类 observation 都没有自动 close 权力，也不含 response、Throwable、owner、nonce 或 prompt；
该基础设施仍不发送网络包、不启动 Provider/Scheduler，不构成通用 client-sponsored bridge、聊天或
AI→世界执行。

ADR-0025 在 ADR-0017 的 child ticket 与 P2 Action 之间增加不透明的预绑定 permit：只有正在
处理精确活动 child 的已注册 route 才能由 coordinator 取得它；permit 把 run/ticket/revision、
bot generation、Action origin/channel/deadline/idempotency 和低于 L0 的 priority 一并冻结。其
未注册的 lifecycle adapter 只转调已冻结 Action 的 ingress、exact-envelope terminal drain 与 exact
containment，不开放 raw Action submission 或 future，也不接现有 SafetyService。它不表示已有建筑
route、真实放置或 P5D 能力。

ADR-0026 收紧 P6-C2 已登记 session 的 trusted Error 路径：deadline、factory、Provider、completion
registration 和 completion-time 再校验抛出 Error 时，必须先精确摘除、取消并在锁外交付一次安全
terminal observation：setup/再校验/handoff Error 为 `FAILED`，cleanup Error 不覆盖已决定的
`SUCCEEDED|CANCELLED`。同步 callback 在 attachment 返回前只暂存，故 attachment Error 不能发布
provisional proposal。同步 setup 或暂存后由 `accept` 激活的 inline signal Error 会重抛；attachment 返回
后才到达的 callback Error 由 `CompletionStage` 的 returned stage 表示，controller 不承诺其 host-level
fatal propagation。它不增加 packet 或 bridge。

ADR-0027 补齐 `ProposalHandoff` 内同步完成另一 controller-owned stage 的间接重入：scope 内的嵌套
completion 只会精确结构摘除，外层与嵌套 session 都失败关闭，外层 queue lease 被 release，token 和
terminal observer 统一延后到 lock 外。它仍不撤销违反 handoff queue/lease 契约而已经同步直发的 packet，
也不接入任何 generic bridge 或世界执行。

ADR-0028 将通用 C2S proposal review 固定为 coordinator 内的 owner-thread 事务：完整 ledger
correlation 必须先于 gate；gate terminal receipt 必须 exact-close 同一 ledger binding；预检后的
gate/ledger 分歧只精确清理已知 ledger binding 并保持 coordinator fail-closed，不按 botId 猜测关闭。
accepted review 依然只是未执行 DTO。它不改变 R1，也不接入 Lifecycle、Network、Client、Scheduler、
Skill 或世界执行。

ADR-0029 固定 P5D-A0 的纯 Java Blueprint 数据边界：schema-v1 只接受有界、唯一且 canonical 的
cell，并派生 stable content hash 和 planned block requirements。它不含 BlockEntity/NBT，不把目标 block
id 猜成背包 item，也不接 site、reservation、work package、Technique、Action 或 world；因此不表示
P5D 建造或红石能力已经实现。

ADR-0030 在 A0 Blueprint 上只增加有界 construction work-package 数据图：完整 immutable key 绑定
`blueprintId/revision/contentHash/ordinal`，精确覆盖 Blueprint cell、small blueprint 单包例外、large
blueprint `16..64` cell package、最多 16 package、完整 key DAG 与确定性拓扑读取。它不建立 site、
材料预留、placement、Technique、Action、Skill、checkpoint、world 或红石生产路径；因此同样不表示
P5D 建造能力已经实现。

ADR-0031 在 P6 只增加每 `(ownerId, botId, agentId)` scope 独立的纯 Java token reservation
accounting：accepted admission 的 reserved input/output/total 先占用 `reserved`，只有物理调用前
settle 才转入 `committed`，其后永不退款；同一 requestId 的 future retry 必须使用新的 exact
reservation。ADR-0034 现已在显式 retry hook 使用它；账本自身仍不接 Scheduler/HTTP/client session，
也不是 real billing、generic bridge、聊天或 AI→Skill/world execution。

ADR-0032 为 physical retry hook 固定完整 immutable budget context：trusted bridge 给出 ledger、binding、
admission 与 upstream deadline，retry wrapper 每次 attempt 给出自身 deadline，账本取最早 deadline/TTL 并
产生新的 exact reservation instance。ADR-0034 已将它接到 `RetryingAiProvider.completeBudgeted(...)` 的
explicit opt-in delegate 边界；普通 Provider SPI、scheduler、client session、网络与 production bridge 仍未接线。

ADR-0034 固定 P6-A1b 的 reserve → circuit admit → settle → one delegate-call 顺序；cancel/timeout 在
settle 前胜出时 release，`SETTLED` 后绝不退款。它不构造 trusted context，也不提供 generic bridge、真实
billing、聊天或 AI→Skill/world execution。

ADR-0033 只为 P5D future survey/lease/placement 增加 candidate `building.site` DTO：完整 WorkPlan
与 dimension+anchor 派生 exact bounds，target 只允许 Blueprint 中真实存在的 offset。它不是 site survey、
accepted area、material reservation、checkpoint、Technique、Action、Skill 或世界写入。

ADR-0035 只在 ADR-0033 exact binding 上增加 complete canonical target evidence 的纯 Java survey 与
derived-only assessment：`UNKNOWN` 不会被猜成 empty，known occupied mismatch 高于 unknown，三类 Blueprint
replace policy 都产生 fail-closed finding。`ACCEPTED_CANDIDATE` 只表示 supplied evidence 与 Blueprint
结构相容，不是 loaded world、accepted site、lease、ownership/human confirmation 或真实施工许可。

ADR-0036 只在 immutable Blueprint 上增加 full `BlockStateFingerprint` 到显式 caller-supplied item ID 的
exact-cover declaration，并按 itemId/material class 导出 bounded declared quantity。它不按同名 block/item 或
properties 缺失猜 mapping，不查询 registry，也不代表 inventory availability、reservation、placement 或施工许可。

ADR-0042 保持 ADR-0036 的纯 DTO 不变，只在 server thread 对已 complete declaration 的每个显式 item 做一次
native registry/default-state candidate equality 检查：item 必须是 non-air `BlockItem`，其 block default 的 full
serialized fingerprint 必须与目标相等。它不调用 `useOn`、不读 world、不证明 contextual placement、inventory、
reservation 或 construction permit。

ADR-0043 只在 ADR-0042 通过后，在 authoritative server thread 对仍精确绑定的活动 Bot body 读取一次 empty
native `InventoryMenu` snapshot；它只统计 default stack fingerprint 的 main/hotbar `0..35`，先按 item 聚合
permanent/temporary declaration，输出当前 tick 的 availability/shortage 或 unavailable。它不把 unavailable 当作零库存，
不读 world/container、不移动或预留材料，也不接 placement、Technique、Skill、Action 或 P5D 完成。

ADR-0044 只从 complete work plan 中真实存在的 exact package、其完整 key 与 `equals` 的 A4 explicit evidence
重导该 package 的 canonical `(itemId, materialClass)` demand；它拒绝 blueprint/key/requirement drift，保留
permanent/temporary 类别，也不将 A7 item-level availability 升格为 allocation、reservation 或 permit。它不读取
Minecraft/world/container，不接 placement、Technique、Skill、Action 或 P5D 完成。

ADR-0045 只将一个 A8 exact package demand 与同 evidence 的 A7 source snapshot 作 item-total projection；它把该
package 的 class-level requirements 仅为 comparison 合并成一项 item finding。整图 shortage 时一个 package 可在
isolation 中 sufficient，但多个 sufficient projection 不能相加或并行，也不会产生 class/slot/source allocation、
reservation 或 permit。unavailable source 不伪造零库存 finding；它不读 Minecraft/world/container，也不接 placement、
Technique、Skill、Action 或 P5D 完成。

ADR-0046 只从 candidate site binding 自己完整 plan 的真实 package 逐 cell，经 package-key+offset fence 重导 immutable
candidate coordinate manifest；同 Blueprint 的另一合法 partition 即使重复 ordinal 也不会改变 binding 的 package。
它不读取或缩小 survey/assessment，不接 lease/material/A7/A8/A9、placement candidate、Action、Technique、Skill 或
P5D 完成。

ADR-0047 只将 ADR-0046 的 actual package target manifest 与同一 exact binding 的 complete raw survey 按 cell/offset
配对；UNKNOWN、EMPTY、OCCUPIED 和 observed tick 原样保留，不派生或输出 package-local assessment/readiness/
freshness/permit。它不重分包、不接 lease/material/A7/A8/A9、placement candidate、Action、Technique、Skill 或
P5D 完成。

ADR-0037 在 P6 定义 server-owned distributed physical-attempt handshake：exact active dispatch 可先
reserve，再由 exact prepare ACK 一次 settle 并返回 replay-stable grant；identity 同时绑定 server instance、owner、
receipt、attempt、nonce、client-not-after 和更早的 physical-start-not-after。grant 是 no-refund 的可能 start
承诺，不是 HTTP/Provider 事实；offer 可 release，grant/丢包/断线/expiry 只 tombstone，客户端须在实际 start
边界原子 `tryClaimPhysicalStart()`。ADR-0041 的 P6-B1 已将该 Contract 接入固定 R1 的 packet、认证 session、
client queue 与 lifecycle/reaper；它仍没有 billing、通用 bridge 或 AI→世界执行。

ADR-0038 只把 ADR-0035 的 caller-supplied survey 接到一个 stateless server-thread adapter：它只遍历 exact
binding 的 canonical Blueprint cell，在 build-height 和 `isLoaded` guard 后读取 native `BlockState`，以完整
serialized properties 产生 `EMPTY|OCCUPIED`，未加载/越界/codec failure 一律 `UNKNOWN`。它不加载 chunk、不写
world，也不构成 accepted site、lease、material proof、placement、Technique、Action、Skill 或 P5D 建造路径。

ADR-0039 只在该 immutable site binding 之外复用已有 `ResourceReservationService` 的 TTL authority：它用
`Math.floorDiv` 将最多 32-block axis span 映射为最多 8 个 exclusive `WORK_AREA` tile，并把 full dimension 保留在
key scope；scope 放不下的 dimension 显式失败而不截断/hash。adapter cache 每次使用都重查底层 token，external
`releaseRun`、`closeGeneration` 与 expiry 都会失效。它不是 accepted site、material/temporary reservation、placement
candidate、Technique、Skill、Action 或 world permission。

ADR-0040 取代 ADR-0018 的单一 pre-use 围栏：`HEAD` 对所有 active strict `UseItem` 做 native
preflight，精确 `completeUsingItem()` invocation 前的二次复核和不可逆提交相位只限 strict natural
`UseItem`。两个点均拒绝 action deadline/maxTicks 当 tick 的物理消费。Finish/Post 才到达的取消不再覆盖
真实 Action receipt；success 先经 verifier 再结算请求的 Skill terminal，failed/stale 保持失败，已入队 receipt
在同 tick plan deadline 前优先处理。该补强仍只限 strict Bot 消耗，不开放一般 world 或 AI 路径，仍待 Java 21/
NeoForge/实机验证。

ADR-0041 将 ADR-0037 的纯 Java attempt identity 映射为 R1 专用的 v3 transport Contract：原子 S2C
offer 携带已受限 dispatch 和 exact identity，C2S ACK/S2C grant 只带 identity；server 仅在 authenticated
sender、live gate/ticket、runtime/binding、identity、TTL 与 server-owned conservative admission/ledger 全部
exact 时 settle，client 仅在 local grant lease 的 atomic claim 后紧邻启动 Provider。当前 bridge 已注册并接入
lifecycle/client session，pre-grant proposal 与 direct R1 admission 都 fail closed；仍待 Java 21 CI、GameTest
与真实客户端/独立服 E2E，不能称为通用 Provider bridge 或 P6 完成。

## 新 ADR 文件规则

从 v1 基线之后，每个新决策单独创建：

```text
docs/adr/NNNN-short-title.md
```

编号递增，不复用、不覆盖。文件至少包含：

```markdown
# ADR-NNNN：标题

- 状态：Proposed / Accepted / Superseded / Rejected
- 日期：YYYY-MM-DD
- 取代：可选

## 背景
## 决策
## 被否决方案
## 兼容性、性能、安全与许可证影响
## 迁移和回滚
## 验证方式
```

已接受 ADR 不能删除。需要改变时新增 ADR，并在旧文件中标记 `Superseded by ADR-NNNN`。
