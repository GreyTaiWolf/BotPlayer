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
| [ADR-0015](0015-bounded-skill-runtime-and-menu-transactions.md) | 有界技能运行时与统一菜单事务 | Accepted | 当前连续集成分支的受限 P5 纵切已通过 Build #362 自动基线；P5 总退出门与跨 menu 通用事务仍未完成 |
| [ADR-0016](0016-durable-vanilla-death-consumption-handoff.md) | 原版死亡消费的耐久交接与失败关闭 | Accepted | P5 死亡纵切已由 Build #163 验证 |
| [ADR-0017](0017-bounded-player-technique-runtime.md) | `Action → Technique → Skill` 有界玩家技术动作层 | Accepted | 旧有 Technique runtime 与有限自卫单击窄 bridge 已通过 Build #362 自动基线；当前分支已迁为单 lifecycle coordinator Contract，仍待该提交 Java 21 CI；跳劈、真实施工和广泛战斗仍未实现 |
| [ADR-0018](0018-strict-consumable-pre-use-fence.md) | 严格消耗品的原版使用前围栏 | Accepted | P5B 牛奶纵切已通过 Build #362 自动基线；真实客户端/专用服验证仍待 |
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
| [ADR-0031](0031-bounded-token-reservation-ledger.md) | 有界 AI token 预留账本 | Accepted | P6-A0 已有 scope-local、并发安全的 conservative token reservation accounting Contract；未接 Scheduler/retry/Provider/client session/network，不是实际计费或通用 bridge |
| [ADR-0032](0032-physical-retry-attempt-budget-context.md) | 物理重试尝试预算上下文 | Accepted | P6-A1a 只固定完整 trusted budget context 与 upstream/retry/TTL 最早 deadline；尚未接 RetryProvider、Scheduler、client session 或真实 Provider 调用 |

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
reservation。它既不接 Scheduler/Retrying Provider/HTTP/client session，也不是 real billing、generic
bridge、聊天或 AI→Skill/world execution。

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
