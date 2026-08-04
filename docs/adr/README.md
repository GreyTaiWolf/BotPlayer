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
| [ADR-0012](0012-client-sponsored-ai-credentials.md) | 客户端赞助的 AI 凭据与每 bot 独立智能体 | Accepted | 客户端凭据基础设施已落实；Provider/HTTP 待 P6 |
| [ADR-0013](0013-finite-perception-two-plane-world-model.md) | 有限感知、双事件平面与有界世界模型 | Accepted | P3 实现已编码并通过 Build #28 自动化退出门 |
| [ADR-0014](0014-bounded-navigation-and-safety-plane.md) | 有界导航快照、分段路径与独立 L0 安全平面 | Accepted | P4 自动化退出门已通过 |
| [ADR-0015](0015-bounded-skill-runtime-and-menu-transactions.md) | 有界技能运行时与统一菜单事务 | Accepted | P5A-0 设计冻结、开发中 |
| [ADR-0016](0016-durable-vanilla-death-consumption-handoff.md) | 原版死亡消费的耐久交接与失败关闭 | Accepted | P5 死亡纵切已由 Build #163 验证 |

“待 Pn”表示决策已经接受，但对应功能尚未实现。ADR-0012 只取代 ADR-0004 中“AI 与
secret 必须只在服务端”的部署决定；客户端不拥有世界权威、ADR-0010 禁止当前阶段接入
DeepSeek 等边界仍然有效。ADR-0013 落实 ADR-0007：服务器全服审计与某个 bot 的认知
必须分离，且 P3 不读取世界容器内容。

ADR-0015 在此基础上固定 P5A 的 Skill FSM/DAG、有限任务查询、Safety handoff、统一
menu 事务、检查点和运行时资源预留；对应纵切片尚未通过前仍不得标记能力完成。
ADR-0016 固定 `keepInventory=false` 消费的 pre-drop tombstone、双份 playerdata 提交、
一次性经验 handoff、旧 body 永久禁存和有界失败恢复；Build #163 只验证单进程故障边界，
真实跨进程恢复仍未验收，且它不承诺掉落实体 exactly-once。

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
