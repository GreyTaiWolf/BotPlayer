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
| [ADR-0017](0017-bounded-player-technique-runtime.md) | `Action → Technique → Skill` 有界玩家技术动作层 | Accepted | Technique runtime 与已有有限自卫单击的窄 bridge 已通过 Build #362 自动基线；跳劈、真实施工和广泛战斗仍未实现 |
| [ADR-0018](0018-strict-consumable-pre-use-fence.md) | 严格消耗品的原版使用前围栏 | Accepted | P5B 牛奶纵切已通过 Build #362 自动基线；真实客户端/专用服验证仍待 |
| [ADR-0019](0019-owner-manual-review-only-ai-round-trip.md) | Owner 手动只读 AI 审阅往返 | Accepted | P6-R1 固定快照/本地 review-only Provider 纵切已通过 Build #362 自动基线；真实客户端/Provider E2E 仍待 |
| [ADR-0020](0020-bounded-ai-scheduler-supervisor.md) | 有界 AI 调度监督器与 Provider-start 围栏 | Accepted | P6 纯 Java scheduler 的受信任有界 lane 合同已通过 Build #362 自动基线；尚未接入生产 lifecycle/client-sponsored bridge |
| [ADR-0021](0021-client-sponsored-request-correlation.md) | 客户端赞助 AI 请求的单一关联身份 | Accepted | gate→dispatch→scheduler 的纯 DTO 绑定已编码；通用客户端 Provider bridge 与世界执行仍未接线 |
| [ADR-0022](0022-client-sponsored-request-ledger.md) | 客户端赞助请求账本的精确生命周期 | Accepted | 通用 binding 的纯 Java 生命周期账本已编码；不发送网络包、不启动 Provider/Scheduler，通用 bridge 与世界执行仍未接线 |
| [ADR-0023](0023-atomic-aim-and-place-action-contract.md) | 原子瞄准并放置方块动作合同 | Accepted | P2 共享动作合同已编码；尚未接入 Technique、Skill、蓝图、AI 或 P5D 建筑能力 |
| [ADR-0024](0024-client-sponsored-request-coordinator.md) | 客户端赞助请求的 server-thread 协调器与有界终态邮箱 | Accepted | gate+ledger 的纯 Java owner-thread 协调器已编码；不接 Lifecycle/Network/Client/Scheduler，通用 bridge 与世界执行仍未接线 |

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
因此即使该窄 bridge 已编码，也不表示任何战斗或建筑能力成熟度提升。

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
受限全局容量与不阻塞的安全终态邮箱。邮箱 observation 没有自动 close 权力，也不含 response、
Throwable、owner、nonce 或 prompt；该基础设施仍不发送网络包、不启动 Provider/Scheduler，不构成
通用 client-sponsored bridge、聊天或 AI→世界执行。

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
