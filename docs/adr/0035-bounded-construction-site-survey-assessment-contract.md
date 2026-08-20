# ADR-0035：有界候选施工站点调查与评估合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0017、ADR-0025、ADR-0029、ADR-0030、ADR-0033

## 背景

ADR-0033 已把一个完整 immutable `ConstructionWorkPlan` 精确绑定到 candidate `siteId`、dimension、anchor
和由所有 Blueprint cell 派生的 bounds/target coordinate。但 binding 本身刻意不读取世界：它不能说明任一
target 是否为空、是否已匹配、是否存在玩家方块、是否可替换，或是否可施工。

后续真实世界调查必须能把有限、可复核的 target evidence 附着到**同一个 exact binding**，同时又不能在
尚无 main-thread snapshot、chunk/保护/危险/lease/材料/placement 合同的阶段，把“调用者给出的观察”冒充成
已读取、已加载或已许可的 Minecraft 世界事实。

## 决策

### 1. Survey 只接受完整、canonical 的 caller-supplied target evidence

新增：

```text
ConstructionSiteSurvey(
  ConstructionSiteBinding binding,
  long observedTick,
  List<TargetObservation> observations
)
```

`observedTick` 必须非负。`observations` 必须恰好一次覆盖 `binding.workPlan().blueprint()` 的每一个真实
`BlueprintOffset`：少项、重复项、任意相邻但不属于蓝图的 offset 都拒绝。构造器按 offset canonicalize 并
`List.copyOf`，不保存调用者的可变 list；target world coordinate 始终由封闭的 exact binding checked
translation 派生，不能由 observation 声称。

每个 `TargetObservation` 只有以下互斥状态：

- `UNKNOWN`：本 survey 没有该 exact target 的可信状态证据；它**不**表示 chunk 一定未加载；
- `EMPTY`：调用者声称该 exact target 有可信的空位证据；
- `OCCUPIED`：必须同时携带恰好一个 immutable `BlockStateFingerprint`。

`UNKNOWN` 和 `EMPTY` 必须没有 fingerprint；`OCCUPIED` 缺 fingerprint 也必须拒绝。A3 不采集、验证或
授权这些 evidence 的来源；未来 main-thread sampler 必须另行定义 world revision、loaded-state、thread、
protection 和 stale-snapshot 语义。

### 2. Assessment 必须由完整 survey 重算，且以已知冲突优先

新增：

```text
ConstructionSiteAssessment(
  ConstructionSiteSurvey survey,
  Status status,
  List<Finding> findings
)
```

`ConstructionSiteAssessment.assess(survey)` 是正常入口。即使调用者直接使用 record 构造器，其 `status` 和
canonical `findings` 也必须逐项等于从 survey 重算的唯一结果；因此不能伪造
`ACCEPTED_CANDIDATE`、省略 conflict 或把已知 conflict 降级为 `INCOMPLETE`。

评估规则固定为：

```text
任一 known mismatching OCCUPIED target -> BLOCKED
否则任一 UNKNOWN target                 -> INCOMPLETE
否则（EMPTY 或 exact matching OCCUPIED） -> ACCEPTED_CANDIDATE
```

所以 `BLOCKED` 高于 `INCOMPLETE`。`ACCEPTED_CANDIDATE` 只表示 supplied finite evidence 在结构上与
immutable Blueprint 相容；它不是 accepted site、loaded-world fact、区域 lease、保护批准、temporary
ownership proof、人类确认或 Minecraft Action/Technique/Skill 的执行权。

### 3. 已知 mismatch 按 Blueprint replace policy 失败关闭

当 `OCCUPIED` fingerprint 不等于该 `BlueprintCell.expectedState()` 时，assessment 产生一个 canonical
`Finding(offset, kind)`：

| Blueprint policy | Finding | 含义 |
|---|---|---|
| `PRESERVE_EXISTING` | `PRESERVE_EXISTING_CONFLICT` | 不得覆盖已知内容 |
| `REPLACE_OWNED_TEMPORARY` | `OWNED_TEMPORARY_PROOF_REQUIRED` | 当前阶段没有 ownership proof，必须阻塞 |
| `REQUIRE_HUMAN_CONFIRMATION` | `HUMAN_CONFIRMATION_REQUIRED` | 当前阶段没有 confirmation capability，必须阻塞 |

`UNKNOWN` 产生 `UNKNOWN_TARGET` finding，但不把未知内容假定为空、临时方块或可替换。A3 不产生任何
“replace allowed”结论。

### 4. 本阶段明确不接线的内容

本 ADR 不实现或调用：

- Minecraft `Level`/`BlockPos`/chunk loading、world read、loaded-state、world revision、地形/流体/危险、
  实体、光照、导航、保护/领地/玩家建筑或权限检查；
- accepted area、work-area lease、temporary-support ownership、human override、玩家修改 diff、材料 item
  mapping、库存/容器读取、reservation、checkpoint；
- `PlacementCandidate`、Technique、Action、Skill、`UseOnBlock`、真实 BlockState verification、NBT、world
  write、红石、network 或 lifecycle bridge。

因此 A3 是 future trusted sampler/lease/placement 的纯 Java 输入与 fail-closed assessment Contract，
不是 P5D 的实际选址或施工路径。

## 被否决方案

### 方案 A：让 caller 直接声明 `accepted=true`

否决。它会跳过 exact target coverage、未知 evidence、expected-state comparison 与 replace policy，不能安全
关联 future lease 或 placement。

### 方案 B：把 `UNKNOWN` 当作 air/empty 或把 temporary policy 当作可替换

否决。缺少可信 evidence、ownership proof 或 explicit human confirmation 时允许继续，会把调查空洞变成
覆盖玩家作品的权限。

### 方案 C：A3 直接读取 `Level` 并决定真实 site

否决。Minecraft world 访问需要另行定义 main-thread、loaded chunk、revision、保护和 snapshot 生命周期；
当前纯 DTO 不能伪造这些保证。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/site/` 纯 Java records，不修改 Action、Technique、Skill、reservation、world、
  lifecycle、payload 或 client API；
- 性能：Blueprint 最多 256 cell，construction/assessment 都只遍历这一个有界集合；不创建线程、队列、
  chunk、registry 或 cache；
- 安全：exact cover、canonical immutable observations、derived-only assessment 和 known-conflict priority
  防止 partial/stale/fabricated candidate 进入 future execution；
- 许可证：只使用 JDK 和既有项目值对象，不新增依赖。

## 迁移和回滚

未来 server-owned sampler 必须为这个 exact binding 产生明确 source、server instance、generation、world
revision、loaded-state 和 observation tick 语义，并在 lease/placement 前重新验证。未来 ownership proof、
human confirmation、material evidence、lease、placement candidate 与 `ConstructWorkPackageSkill` 必须各自新增
合同；不得把 A3 的 `ACCEPTED_CANDIDATE` 当作这些证明的替代品。

回滚 A3 只移除未接线 DTO/测试，不会撤销世界、背包、reservation、checkpoint 或网络副作用。

## 验证方式

- 纯 Java 测试覆盖 canonical/defensive copy、non-negative tick、exact target cover、foreign/duplicate offset、
  state/fingerprint pairing、三类 replace policy、`BLOCKED > INCOMPLETE > ACCEPTED_CANDIDATE`、伪造
  assessment、65-cell multi-package plan 与公开 DTO purity；
- 静态边界检查确认 records 不携带 Minecraft runtime、world/lease/reservation/Action/Technique/Skill execution
  authority；
- 对应提交仍必须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`。A3 没有 Minecraft 生产行为；
  real sampler、保护/危险/loaded chunk、lease、材料、placement、5×5 小屋、玩家修改、checkpoint、红石与
  实机验证仍须后续独立完成。
