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
| ADR-0004 | 客户端只负责 UI，AI 与 secret 在服务端 | Accepted | 基线已落实 |
| ADR-0005 | SavedData 索引 + SQLite 长期记忆 | Accepted | 待 P1/P7 |
| ADR-0006 | 外部技能只允许声明式 DAG | Accepted | 待 P5 |
| ADR-0007 | 默认有限感知，不做全知 bot | Accepted | 待 P3 |
| ADR-0008 | 模组兼容采用 C0–C3 分级 | Accepted | 待 P8 |
| ADR-0009 | 不把 LGPL 寻路源码并入 MIT 核心 | Accepted | 当前无该依赖 |
| ADR-0010 | P0–P2 通过前不接 DeepSeek | Accepted | 当前遵守 |
| ADR-0011 | 在 `ServerPlayer.die` TAIL 确认死亡 | Accepted | P1 已落实 |

“待 Pn”表示决策已经接受，但对应功能尚未实现。

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
