# BotPlayer P5A 暂停期间并行开发规划

> 状态：开发流程历史基线 v0.1；当前 P5/P6 进度以实施状态页和连续集成分支为准。当前
> `agent/p5-p6-next` 上的单 `TechniqueLifecycleCoordinator` 仅是 ADR-0017 的共享 Contract：
> 它把已有有限自卫窄 route 迁到一个 lifecycle-owned runtime，未实现 P5D 建筑/红石，也不
> 放宽本文件的 P6/Technique 并行边界。
>
> 更新日期：2026-08-07
>
> 目的：当 P5A 因 Token、模型额度或其他资源暂时暂停时，允许继续开发低耦合的 PT/P6 基础设施，同时保证恢复 P5A 后不会产生接口分叉、重复实现或大规模合并冲突。
>
> 相关设计：[P5A 设计](AI_PLAYER_RESEARCH_AND_P5_DESIGN_CN.md)、[玩家技术动作设计](PLAYER_TECHNIQUE_BUILDING_COMBAT_DESIGN_CN.md)、[总架构路线图](ARCHITECTURE_AND_ROADMAP_CN.md)、[ADR-0015](adr/0015-bounded-skill-runtime-and-menu-transactions.md)、[ADR-0017](adr/0017-bounded-player-technique-runtime.md)

> 本文的“当前事实基线”固定在 2026-08-07，用于说明当时的并行策略。它已被
> [当前实现状态](IMPLEMENTATION_STATUS_CN.md) 与
> [`agent/p5-p6-next`](https://github.com/GreyTaiWolf/BotPlayer/tree/agent/p5-p6-next) 取代；不要将其中“P6 尚未实现”
> 或 P5 接线缺口当作当前仓库状态。

本文不是新的 P0–P10 阶段，也不改变阶段退出门。它只定义**开发顺序、并行边界、分支策略、共享接口规则和暂停/恢复交接格式**。

---

## 1. 当前事实基线

截至 2026-08-07：

- P5 基线已经合并 `main`：有界 Skill 核心、局部 DAG 校验、TTL 预留原型、主动进食、基础盔甲、通用 `InventoryMenu SWAP_SEQUENCE`、生命周期/死亡恢复纵切已经存在；
- P5A **总退出门未关闭**，仍缺：跨 menu 统一事务、`clicked()` 故障注入、生命周期 `PENDING` continuation、TaskSensor/Reservation 生产接线、Checkpoint、工具/副手、有限自卫、craft/chest/furnace/DAG、木头到铁镐生产链，以及更完整的重启/专用服/多 Bot 验证；
- PT0 设计已经合并：`Action → Technique → Skill` 边界、战斗和真实建造设计已冻结，但没有 Technique 生产代码；
- P6 尚未实现 `AiProvider`、真实 DeepSeek HTTP、Tool Firewall、ContextAssembler、RequestScheduler 或 AI→SkillPlan 生产接线；当前只有客户端本地凭据/profile/binding 基础。

因此现在最适合做的是：

```text
P5A：保持现状，未来从最新 main 继续
PT：只推进不依赖未稳定 P5A 接口的纯 Java/低耦合基础设施
P6：只推进 Provider、DTO、解析、安全和 Mock 等低耦合基础设施
```

不得把 P5A 暂停解释成 P6/PT 可以绕过 P5A 直接进入世界执行。

---

## 2. 并行开发总原则

### 2.1 核心原则

1. **只并行低耦合切片**：并行轨道优先新增自己的 package 和纯 Java 合同，不修改其他轨道正在演进的核心实现。
2. **小 PR 快合并**：每个 PR 只完成一个可独立验证的子目标，尽快进入 `main`；禁止三条长期大分支最后一次性合并。
3. **从最新 main 开分支**：每个新切片都从创建时最新的 `main` 开始；旧分支只作为历史参考，不继续堆新功能。
4. **共享核心先做 Contract PR**：如果某轨道需要修改 P5/P2/P4 的共享核心接口，先单独做一个小型合同 PR，合并后其他轨道再基于新合同开发。
5. **P6 不直接调用 Technique**：AI 只产生 Skill/Plan 级意图；Technique 由本地 Skill/TacticSelector/施工规划器选择。
6. **PT 不复制 Skill Runtime**：Technique 只通过窄 Port 提交/查询 Action、Navigation 和安全状态，不实现第二套检查点、长期 DAG 或资源预留。
7. **P5A 继续拥有任务级权威**：Checkpoint、TaskSensor、Reservation、menu/workstation、生产链仍由 P5A 主线定义。
8. **不因并行提前抬能力成熟度**：Provider/Technique 核心存在，不等于 DeepSeek、跳劈、建造或 P5A 已完成。

### 2.2 分支不是长期产品线

错误：

```text
agent/p5a      持续开发数周
agent/pt       持续开发数周
agent/p6       持续开发数周
最后三方大合并
```

正确：

```text
main
├─ agent/p6-provider-spi          → PR → main
├─ agent/pt1-runtime-core         → PR → main
├─ agent/p6-scripted-chaos        → PR → main
├─ agent/p5a-checkpoint           → PR → main
├─ agent/pt1-aim                  → PR → main
├─ agent/p6-tool-codec            → PR → main
└─ ...
```

每个 PR 合并后，下一切片重新从最新 `main` 开始。

---

## 3. 三轨职责与所有权

### 3.1 P5A 轨：任务执行与生存闭环

P5A 继续拥有：

```text
skill/core/
skill/plan/
skill/runtime/
skill/reservation/
action/interaction/menu/
P5 world container / crafting / furnace adapters
Skill checkpoint persistence
TaskSensor production wiring
```

当前后续建议拆成：

| 切片 | 内容 | 并行属性 |
|---|---|---|
| P5A-R1 | 真实二次启动/死亡 handoff 故障注入 | 独立，可恢复 P5A 时先做 |
| P5A-C1 | Checkpoint schema、保存、恢复、generation 复核 | **P6 最终接线前置** |
| P5A-T1 | TaskSensor + Reservation 生产接线 | **P6 Context/Plan 接线前置** |
| P5A-M1 | 跨 menu 事务、`clicked()` 前后故障注入 | 工作站/生产链前置 |
| P5A-E1 | 工具与普通副手选择 | 生产/自卫前置 |
| P5A-W1 | chest/crafting/furnace 最小驱动 | 生产链前置 |
| P5A-P1 | 木头→铁镐生产 DAG | P5A 总退出核心 |
| P5A-D1 | 有限自卫 Skill | 可先用简单 Action；以后迁移到 PT2 |

P5A 的上述接口在完成前视为**演进中**。PT/P6 不应自行猜测其最终内部结构。

### 3.2 PT 轨：玩家短时技术动作

PT 独占新增：

```text
technique/core/
technique/runtime/
technique/snapshot/
technique/aim/
technique/combat/
technique/building/
```

PT 当前允许开发：

| 切片 | 是否可立即开发 | 原因 |
|---|---:|---|
| PT1-A Technique DTO/FSM/Limits | 是 | 纯 Java，几乎不依赖 P5A |
| PT1-B child Action 管理模型 | 是 | 可先通过 fake Port/Scripted outcome 测试 |
| PT1-C 确定性 TechniqueProfile seed | 是 | 独立纯 Java |
| PT1-D Body/Target immutable snapshot DTO | 是 | 先定义内部 DTO，不做世界读取接线 |
| PT1-E Aim 数学/步进控制器 | 是 | 纯 Java；不修改现有 `LookAtAction` |
| PT1-F Minecraft RotateView/Port 接线 | 条件允许 | 需要小型 Contract PR，避免侵入 Action Runtime |
| PT2 basic melee/jump critical | 暂缓生产接线 | 需要 PT1 + P5A 自卫/装备边界稳定 |
| PT3 building placement | 暂缓大范围接线 | 需要 P5A 材料/menu/工作站和 P5D 范围 |

### 3.3 P6 轨：AI Provider 与安全网关

P6 独占新增目标包：

```text
ai/core/
ai/provider/
ai/request/
ai/tool/
ai/security/
ai/mock/
client/ai/
```

P6 当前允许开发：

| 切片 | 是否可立即开发 | 原因 |
|---|---:|---|
| P6-A `AiProvider` SPI + DTO | 是 | 不依赖 P5A |
| P6-B `ScriptedAiProvider` | 是 | 纯 Java、后续 CI 基础 |
| P6-C `ChaosAiProvider` | 是 | 纯 Java、安全测试基础 |
| P6-D `AiRequest/AiResponse/AiCapabilities/ProviderHealth` | 是 | DTO 合同 |
| P6-E `RedactionFilter` | 是 | 与世界执行无关，且越早越好 |
| P6-F `AiCircuitBreaker` + Retry/Backoff policy | 是 | 可用 fake clock 测试 |
| P6-G DeepSeek JSON/SSE parser | 是 | 可用固定 fixture，不需要真实 Key |
| P6-H 客户端 DeepSeek HTTP transport | 条件允许 | 只能使用本地 credential；不得接世界执行 |
| P6-I `ToolCallCodec` + schema parser | 是 | 只解析为不可信 Proposed DTO |
| P6-J Tool Firewall 基础静态规则 | 是 | 只做类型/大小/字段/白名单，不接 Skill runtime |
| P6-K ContextAssembler 完整生产接线 | 暂缓 | 依赖 TaskSensor、Checkpoint/Goal/Memory 边界 |
| P6-L AI → SkillPlan 提交/执行 | **禁止提前** | 必须等待 P5A DAG/Checkpoint/Reservation 接口稳定 |

---

## 4. 冲突矩阵

### 4.1 轨道级

| 组合 | 风险 | 规则 |
|---|---:|---|
| P5A ↔ PT1 pure Java | 低 | 可以并行 |
| P5A ↔ P6 Provider/Mock/Parser | 低 | 可以并行 |
| PT1 ↔ P6 | 极低 | P6 不直接依赖 Technique |
| P5A ↔ PT Minecraft Action 接线 | 中 | 先做 Contract PR |
| P5A ↔ P6 Tool Firewall 基础 | 低/中 | 只消费稳定 DTO；不写 Skill Runtime |
| P5A ↔ P6 AI→SkillPlan | 高 | 等 P5A 合同稳定后再做 |
| PT2 Combat ↔ P5A SelfDefend | 中 | P5A Skill 调 PT，不反向依赖 |
| PT3/4 Building ↔ P5A Production/Menu | 高 | 等材料/容器/Checkpoint稳定后集成 |

### 4.2 共享高风险文件

以下文件/包默认视为共享核心，任何并行轨道要修改都必须先评估是否需要 Contract PR：

```text
BotLifecycleManager.java
BotActionRuntime.java
ControlArbiter.java
PlayerInputController.java
SafetyService.java
NavigationService.java
SkillRegistry.java
SkillPlan*.java
SurvivalSkillService.java
action/interaction/menu/**
BotPlayerConfig.java
BotPlayerCommands.java
network/**
```

规则：

- 新轨道优先增加自己的 Adapter/Port，不直接改核心内部；
- 如果确实需要核心支持，PR 标题必须含 `contract`，只做接口与最小测试，不同时加业务能力；
- Contract PR 合入后，依赖它的 PT/P6/P5A 切片重新从最新 `main` 开分支。

---

## 5. 推荐 Port/Adapter 解耦

### 5.1 PT 只依赖 Action Port

目标：PT 不直接读取 `BotActionRuntime` 内部表。

```java
interface TechniqueActionPort {
    TechniqueActionSubmission submit(TechniqueActionRequest request);
    Optional<TechniqueActionView> inspect(UUID actionId);
    TechniqueActionCancelResult cancel(UUID actionId, String reason);
}
```

实现 Adapter 由 action/runtime 集成层提供。Port DTO 只包含 ID、generation、ActionKind、状态、失败码、证据引用和 revision。

### 5.2 PT 只依赖 Navigation Port

```java
interface TechniqueNavigationPort {
    NavigationSubmission goTo(TechniqueNavigationRequest request);
    Optional<NavigationSessionView> inspect(UUID navigationId);
    void cancel(UUID navigationId);
}
```

Technique 不建立第二套 pathfinder。

### 5.3 P6 只依赖 Skill Catalog/Submission Port

在 P5A 合同稳定后引入：

```java
interface AiSkillCatalogPort {
    List<AiVisibleSkillDescriptor> visibleSkills(AiSkillQuery query);
}

interface AiSkillPlanPort {
    AiPlanValidation validate(ProposedSkillPlan proposal);
    AiPlanSubmission submit(ValidatedSkillPlan plan);
}
```

P6 不直接调用 `SurvivalSkillService`，也不知道 Technique。

### 5.4 为什么先 Port 后集成

这样未来 P5A 内部可以继续重构：

```text
P5A 内部变化
    ↓
Adapter 更新
    ↓
PT/P6 Port 合同不变
```

避免三条轨道一起修改 `BotActionRuntime` 或 `SurvivalSkillService`。

---

## 6. Token/额度不足时的暂停与切轨规则

### 6.1 暂停一个高上下文任务前必须留下 Handoff

每次因 Token/模型额度暂停复杂任务，必须在 PR 描述、Issue 或专用 Markdown 注释中留下：

```text
Workstream: P5A-C1
Base main SHA: <sha>
Branch: <branch>
Last verified commit: <sha>
Completed:
- ...
In progress:
- ...
Not started:
- ...
Known failing tests:
- ...
Shared contracts touched:
- ...
Files currently owned:
- ...
Resume first:
- ...
Do not change:
- ...
```

如果工作分支有未合并代码，不允许另一个轨道基于它继续开发不相关功能。

### 6.2 暂停后可以切到什么任务

优先顺序：

```text
纯 Java、独立 package、无共享写入
→ Mock/fixture/parser/security
→ 文档/测试夹具
→ 需要窄 Contract 的基础设施
→ 最后才是跨轨生产集成
```

### 6.3 恢复 P5A 时

恢复流程固定：

1. 读取 handoff；
2. 查看当前 `main` 与暂停时 base 的差异；
3. 不直接继续旧落后分支；
4. 如果旧分支有未合并独立提交，先 rebase/cherry-pick 到新的恢复分支；
5. 重新运行目标单测和 `clean build`；
6. 检查 PT/P6 是否新增了共享 Contract；
7. 只在确认合同兼容后继续 P5A。

---

## 7. 当前推荐的实际开发队列

### Phase A：P5A 暂停期间——现在就可以做

#### A1：P6-A Provider Core

范围：

```text
AiProvider
AiRequest / AiResponse
AiCapabilities
ProviderHealth
AiError / AiFailureKind
CancellationToken
```

验收：纯 Java 单元测试；没有 HTTP、没有真实 Key、没有 Minecraft 对象。

#### A2：PT1-A Technique Core

范围：

```text
TechniqueId / Version / Descriptor
TechniqueState
TechniqueRun
TechniqueLimits
TechniqueFailureCode
TechniqueOutcome
child ticket model
```

验收：纯 Java FSM、预算、generation/revision、取消/抢占、确定性测试。

#### A3：P6-B Mock + Redaction + Circuit Breaker

范围：

```text
ScriptedAiProvider
ChaosAiProvider
RedactionFilter
AiCircuitBreaker
RetryPolicy
```

验收：无真实网络，测试 401/429/5xx/timeout/非法响应状态模型。

#### A4：PT1-B Aim Core

范围：

```text
AimProfile
AimTarget DTO
AimWindow
AimController pure math
```

验收：yaw/pitch wrap、最大转速、目标预测、丢失视线状态、确定性 profile。

#### A5：P6-C ToolCallCodec

范围：

```text
ToolCallCodec
ProposedToolCall
ProposedSkillPlan DTO
JSON schema strict parsing
unknown field rejection
size/depth/count limits
```

验收：所有输入都只是“不可信提案”，绝不触发世界副作用。

### Phase B：需要小型 Contract PR

只有 A 阶段全绿后才进入：

- `contract/technique-action-port`；
- `contract/technique-navigation-port`；
- `contract/ai-skill-catalog-port`（等 P5A Skill descriptor 稳定）；
- AI client session/request payload 的无 secret 合同。

一个 Contract PR 不同时实现 jump critical、DeepSeek 或生产链。

### Phase C：P5A 恢复后

优先恢复：

```text
P5A-C1 Checkpoint
→ P5A-T1 TaskSensor/Reservation
→ P5A-M1 Menu fault injection / cross-menu
→ P5A-W1 craft/chest/furnace
→ P5A-P1 木头→铁镐
```

这期间 P6/PT 继续只在自己的 package 内推进。

### Phase D：首次跨轨集成

P5A 核心合同达到稳定后：

```text
P5A SelfDefendSkill
→ PT2 BasicMeleeTechnique
→ TechniqueActionPort
→ ActionRuntime
```

以及：

```text
P6 ProposedSkillPlan
→ AiSkillPlanPort
→ P5 SkillPlanValidator
→ P5 Skill runtime
```

两条集成分开 PR，不要一次把 P5+PT+P6 全接通。

---

## 8. 合并顺序和 CI 门

### 8.1 每个小 PR

至少：

```text
git diff --check
./gradlew --no-daemon clean build
```

有 Minecraft 行为时再：

```text
./gradlew --no-daemon runGameTestServer
```

P6 parser/provider 的纯 Java PR 不需要为了“形式一致”伪造 GameTest；但必须有 JUnit 和 fixture。

### 8.2 合并顺序

同一时间有多个低耦合 PR 时：

1. 先合 Contract；
2. 再合不依赖它的纯 Java基础设施；
3. 再合 Adapter；
4. 最后合生产集成。

后合并 PR 必须在合并前同步最新 `main` 并重新 CI。

### 8.3 禁止的合并模式

- P6 分支携带 P5A 尚未合并实现；
- PT 分支复制 P5A Skill Runtime 类；
- 一个 PR 同时修改 `BotLifecycleManager + BotActionRuntime + SkillRuntime + AI Provider + Technique`；
- 用大规模冲突解决覆盖另一轨已验证逻辑；
- CI 只跑目标模块而不跑全仓 `clean build`；
- 文档提前把基础设施标成用户能力。

---

## 9. 共享文档冲突策略

最容易产生文本冲突的文件：

```text
AGENTS.md
CHANGELOG.md
docs/README_CN.md
docs/IMPLEMENTATION_STATUS_CN.md
docs/ARCHITECTURE_AND_ROADMAP_CN.md
docs/DEVELOPMENT_CN.md
docs/adr/README.md
```

策略：

- 纯基础设施 PR 只更新与自身事实直接相关的最少文档；
- 能力成熟度只在该能力直接证据齐全时更新；
- `CHANGELOG` 只记录已合并代码，不记录未来计划；
- 如果两个 PR 都需要改实现状态，后合并者基于最新 `main` 重写对应段，不使用机械冲突覆盖；
- 设计文档可以先合并，但明确“不计入实现状态”。

---

## 10. 当前工作优先级

在 P5A 因 Token 暂停的情况下，推荐优先级：

```text
P6-A Provider Core                 最高安全并行优先级
PT1-A Technique Core               最高安全并行优先级
P6-B Mock/Redaction/CircuitBreaker 高
PT1-B Aim Core                     高
P6-C ToolCallCodec                 高
P6 DeepSeek HTTP transport         中；只做客户端传输，不接世界
PT Minecraft Adapter               中；需 Contract PR
P6 ToolFirewall runtime            中；静态部分先做
PT2 Combat                         暂缓完整接线
P6 AI→SkillPlan                    阻塞：等待 P5A
PT3/4 Building                     阻塞大部分：等待 P5A/P5D 基础
```

这能在不浪费开发时间的前提下最大程度保持 P5A、PT、P6 互相可组合。

---

## 11. Definition of Done：并行策略本身

只有满足以下条件，才认为一个并行切片安全进入 `main`：

- 从当时最新 `main` 建立；
- 没有复制其他轨道正在负责的运行时；
- 共享核心修改已经通过独立 Contract PR，或明确证明无需共享修改；
- 新 DTO/Port 有版本、大小/数量上限和失败语义；
- 纯 Java/Gradle/GameTest 按适用层级通过；
- 没有真实 API Key、真实外部付费调用或秘密进入测试；
- 没有把 P6 Provider 基础描述成 AI 已接通；
- 没有把 PT Core 描述成 Bot 已会跳劈/盖房；
- 没有把 P5A 暂停任务误标为完成；
- 后续恢复 P5A 时可以仅通过 Port/Adapter 接入，不需要重写 PT/P6 核心。

最终目标不是“同时开更多分支”，而是让每个 Token 窗口都能完成一个**独立、可验证、可快速合并、不会给主线制造技术债**的小阶段。
