# ADR-0046：有界工作包候选站点目标投影

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0029、ADR-0030、ADR-0033、ADR-0035、ADR-0038、ADR-0039、ADR-0044、ADR-0045

## 背景

ADR-0030 的 ConstructionWorkPlan 可以为同一 exact Blueprint 合法地产生不同的 exact-cover work-package
partition；ConstructionWorkPackageKey 绑定 Blueprint identity/revision/content 和 ordinal，但不包含该 package
的 cell list。因此不得仅凭同一个 Blueprint/key ordinal 重建或猜测 package cell。

ADR-0033 的 ConstructionSiteBinding 已把一个 exact plan 与 candidate dimension+anchor 绑定，并提供
targetPosition(workPackageKey, offset)：该 overload 同时验证真实 package key 与该 package 的真实 cell offset，
再派生坐标。它仍不是 loaded-world state、survey、lease、material proof、placement 或 world authority。

future construction 需要一个稳定、bounded 的 package→candidate-site target manifest 供后续独立 route 重验；但不能把
A3/A5 survey/assessment、A6 lease 或 A8/A9 material observation 拼进局部 package “ready”结论，也不能在此阶段选择
站位、朝向、点击面或原版 interaction。

## 决策

### 1. 新增 private-constructor 的 exact package target manifest

新增静态入口：

    ConstructionWorkPackageSiteTargetProjection.project(
        ConstructionSiteBinding binding,
        ConstructionWorkPackageKey workPackageKey)

结果保存 immutable binding、exact key 和 canonical Target(cell, targetPosition) list；constructor 为 private，
调用者不能提供任意 coordinate/target list。Target 只携带既有 immutable BlueprintCell 与已派生
BlockCoordinates，dimension 只从 enclosing binding.anchor().dimension() 获得，不在每条 target 上重写。

### 2. 只使用 binding 自己的 plan 和 package-key/offset 双围栏

factory 只能在 binding.workPlan().workPackages() 中找到 equals 的真实 key 后继续。它遍历这个实际
ConstructionWorkPackage.cells() 的 canonical order（最多 64），对每个 cell 必须调用：

    binding.targetPosition(workPackageKey, cell.offset())

来重导 candidate coordinate。key 不属于 binding、同 Blueprint 的 foreign/missing/stale revision/content/ordinal，
或任何 future 伪造的 cell/coordinate 都 fail closed。projection 不得调用 ConstructionWorkPlan.partition()：
同一个 Blueprint 的另一份合法 partition 使用相同 ordinal 时，也只能使用 binding 已冻结的 cell list。

targetCount() 只是该 immutable manifest 的 cell 数，不是 progress、remaining work、placeable count 或执行次数。

### 3. A10 不是局部 site/material readiness 或施工授权

A10 只表达：“这一个 exact candidate binding 内，这一个 exact package 的 structural target cell 应映射到哪些 candidate
coordinates。”它不表示：

- A3/A5 survey 仍 fresh、完整、loaded，或 ACCEPTED_CANDIDATE；不能以局部 package 掩盖同 binding 内其他 package
  的 BLOCKED 或 INCOMPLETE；
- A6 lease、protection/ownership/human confirmation、world/chunk/build height、安全、support、可达或玩家状态已通过；
- A7/A9 inventory observation、A8 demand、slot/source allocation、material reservation、temporary-area ownership、
  crafting/transport 已满足；
- 任一 target 有 placement candidate、stand position、look/facing、click face/hit vector、hand/item stack、useOn
  context、Action、Technique、Skill、checkpoint、lifecycle、network、AI 或 world write。

future side-effecting route 必须在 server owner thread 重新验证自身 exact runtime/generation、all applicable plan/binding/
survey/lease/material/source/protection/safety constraints、原版 interaction 与真实 result；不得跨 tick/restart replay
A10 manifest 为 permit。

### 4. 本阶段明确不接线

不改 Blueprint/work-plan/binding/survey/assessment/lease/A7/A8/A9 语义；不读 Minecraft registry/world/chunk/container、
不创建/读取 native menu、stack、reservation、cache、thread、tick task、persistence、configuration 或 command；不接
placement candidate、Action、Technique、Skill、checkpoint、lifecycle、network、client、AI 或 redstone。

## 被否决方案

### 方案 A：以 (Blueprint, ordinal) 重新调用默认 partition

否决。同 Blueprint 可以有不同合法 package partition，而 key 不携带 cell set；repartition 会把 ordinal 映射到错误 cell。
A10 必须只从 binding 的 already-validated plan 读取实际 package。

### 方案 B：把 A3/A5 survey/assessment 缩成 package-local accepted

否决。survey 是 complete plan 的 exact-cover evidence；局部 accepted 不能说明其他 package 的 conflict/unknown，也不等于
freshness、lease、permission 或 placement。

### 方案 C：把 A8/A9 sufficient 加到 target

否决。材料 demand/observation 与 site target 当前故意独立；A9 还会重复引用同一 snapshot count。拼合会伪造
material-ready/施工 permit。

### 方案 D：提前生成 stand/facing/click/useOn 参数

否决。它需要 loaded world、support/reachability/collision、原版 context 与真实结果验证；这些是后续独立的
PlacementCandidate/interaction Contract。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 building/site/ pure Java projection/JUnit；site、material、reservation、Action、Technique、Skill、
  lifecycle、network、client 与 persistence 不变；
- 性能：一个 package 最多 64 cells，factory 只作已有 binding 的有界 package lookup 与 checked translation；不读
  Minecraft、不创建线程/cache 或 Tick 工作；
- 安全：binding-plan membership、exact key、package-key+offset coordinate fence、canonical list 和 private constructor
  阻止 stale/foreign identity、ordinal repartition、arbitrary coordinate 与 target-list forgery；
- 许可证：只使用既有 DTO 与 JDK 标准库，不引入依赖或第三方内容。

## 迁移和回滚

当前没有 package target persistence、placement candidate、execution route 或 checkpoint，故无需数据迁移。future 若引入
plan compilation version、site rotation、alternative build order、survey freshness、material allocation 或 true placement，
必须新增版本化 Contract，不能改写 A10 的 exact binding-plan projection。

回滚只删除 projection/test/documentation；不会改变 world、inventory、reservation、roster、payload、checkpoint 或
playerdata。

## 验证方式

- 纯 JUnit 覆盖 65/256-cell 每个真实 package 的 exact cell/coordinate/total、canonical/immutable list、foreign/
  stale/missing key，以及同 Blueprint 的 alternative legal partition 不会按 ordinal re-partition；
- 反射静态复核确认公开 DTO/API 不含 Minecraft runtime、ItemStack、reservation、survey/assessment/lease、A7/A8/A9
  material type、Action、Technique 或 Skill authority；
- 本阶段不使用 Minecraft API，故不新增专用 GameTest；对应提交仍须在 Java 21 环境通过 clean build、JUnit 与
  仓库的 runGameTestServer。本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过；
- 真实客户端、独立专用服、多 Bot、loaded-site survey、lease、保护/玩家修改、source allocation/reservation、
  placement candidate、原版 placement、checkpoint 与完整 construction/redstone E2E 仍需后续独立验证；
  P5D/P5 总退出门不因此关闭。
