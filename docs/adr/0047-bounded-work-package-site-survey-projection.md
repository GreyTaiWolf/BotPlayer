# ADR-0047：有界工作包站点调查证据投影

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0029、ADR-0030、ADR-0033、ADR-0035、ADR-0038、ADR-0039、ADR-0046

## 背景

ADR-0035 的 ConstructionSiteSurvey 是一个 complete、canonical 的全 Blueprint 原始 target-state
evidence snapshot；其中 UNKNOWN 表示缺少可信证据，而非空方块、已加载区块或任何施工许可。ADR-0046
又只把一个 exact candidate binding 中真实 work package 的 cell 映射为 canonical candidate coordinate
manifest。

future route 需要在一个有界 package 内重验该 package target 的原始 survey evidence，但不能把全图 survey
缩成 package-local accepted 或 ready 结论。这样会掩盖同一 binding 中其他 package 的 conflict/unknown，也会
错误地把观察 tick 当作 freshness、lease 或 world authority。

同一个 Blueprint 可有不同的合法 work-package partition；ConstructionWorkPackageKey 的 ordinal 不携带 cell
set。因此不能只按 Blueprint/key ordinal 配对 A10 manifest 与 survey，也不能再次 partition。

## 决策

### 1. 新增 private-constructor 的 raw package-survey projection

新增静态入口：

    ConstructionWorkPackageSiteSurveyProjection.project(
        ConstructionWorkPackageSiteTargetProjection targetManifest,
        ConstructionSiteSurvey survey)

结果保存 immutable targetManifest、complete survey 与 canonical TargetObservation(target, observation)
list。constructor 为 private，调用者不能提供任意 target/observation list。

### 2. 只接受同一个 exact candidate binding

targetManifest.binding() 必须 equals survey.binding()；比较失败时 fail closed。该 equality 覆盖 site identity、
exact work plan、Blueprint identity/revision/content/partition、dimension、anchor 与 derived bounds，不能只比较
Blueprint、work-package ordinal、target coordinate 或 observed tick。

factory 逐个使用 A10 manifest 中真实 package target 的 canonical order，并再次调用：

    binding.targetPosition(workPackageKey, target.cell().offset())

重验 package-key+offset 的双围栏和 candidate coordinate。随后只从 complete survey 按同一个 offset 查取原始
TargetObservation。不得调用 ConstructionWorkPlan.partition()；同 Blueprint 的另一合法 partition 即使重复
ordinal，也不能与当前 manifest 配对。

### 3. 只转发 raw evidence，不导出局部 readiness

projection 保留 UNKNOWN、EMPTY、OCCUPIED 和 occupied fingerprint 的原值。observedTick() 只转发 enclosing
survey 的原始 snapshot tick，不产生 freshness、TTL、lease 或 permit。

本类型不派生或输出 package-local assessment、status、accepted、ready、placeable count、remaining work、progress
或执行次数。
它不缩小 A3/A5 survey，也不接 A6 lease、A7/A8/A9 material、slot/source allocation、reservation、
protection/ownership/human confirmation、stand/facing/click/useOn、placement candidate、Action、Technique、
Skill、checkpoint、lifecycle、network、AI 或 world write。

future side-effecting route 必须在 authoritative server owner thread 再次验证自己的 current runtime/generation、
complete applicable survey/lease/material/source/protection/safety、原版 interaction 和真实结果；不得把本 projection
跨 tick/restart 当作 permit。

### 4. 本阶段明确不接线

不改 Blueprint/work-plan/binding/survey/assessment/lease/A7/A8/A9 语义；不读取 Minecraft registry/world/chunk/
container，不创建 native menu、stack、reservation、cache、thread、tick task、persistence、configuration 或
command；不接 placement candidate、Action、Technique、Skill、checkpoint、lifecycle、network、client、AI
或 redstone。

## 被否决方案

### 方案 A：从 package-local raw evidence 导出 accepted 或 ready

否决。一个 package 的 UNKNOWN/EMPTY/OCCUPIED 子集不能代表 complete site survey，也不等于 freshness、
lease、protection、material 或施工许可。

### 方案 B：以 Blueprint 与 ordinal 重新匹配或重新分包

否决。合法 alternative partition 可以复用 ordinal 但有不同 cell set；只能使用 A10 manifest 的 binding 内
实际 package 和同 binding 的 survey。

### 方案 C：同时把 A6 lease 或 A7/A8/A9 material 合并进输出

否决。这会把彼此独立、不同时间和不同 authority 的 snapshot 拼成看似可执行的 package readiness，且 A9
不能跨 package allocation。

### 方案 D：在投影中读取或重新抽样 Minecraft world

否决。A11 是纯 Java raw-evidence pairing；world loadedness、build height、保护、support/reachability、
contextual placement 和真实原版 result 是后续独立、动作前重验的 Contract。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 building/site pure Java projection/JUnit；site、material、reservation、Action、Technique、
  Skill、lifecycle、network、client 与 persistence 不变；
- 性能：一个 package 最多 64 targets，factory 只做已有 immutable binding/survey 的有界 map lookup 和
  coordinate fence recheck；不读 Minecraft、不创建线程/cache 或 Tick 工作；
- 安全：exact binding equality、A10 package-key+offset fence、canonical target order、full-survey lookup 和
  private constructor 阻止 binding drift、ordinal repartition、arbitrary coordinate、target/observation mismatch
  与 package-local readiness forgery；
- 许可证：只使用既有 DTO 与 JDK 标准库，不引入依赖或第三方内容。

## 迁移和回滚

当前没有 package survey projection persistence、placement candidate、execution route 或 checkpoint，故无需数据
迁移。future 若引入 survey freshness、rotation、alternative build order、material allocation、lease、permission
或 true placement，必须新增版本化 Contract，不能改写 A11 raw observation semantics。

回滚只删除 projection/test/documentation；不会改变 world、inventory、reservation、roster、payload、checkpoint
或 playerdata。

## 验证方式

- 纯 JUnit 源覆盖 65/256-cell 每个真实 package target 与 complete survey 的 exact observation/canonical order、
  UNKNOWN/EMPTY/OCCUPIED 原样保留、observed tick 只转发、same Blueprint alternative 16/49 partition、
  site binding drift、null、immutable list 和 private-constructor forgery/reorder/mismatch；
- 反射静态复核确认公开 DTO/API 不含 Minecraft runtime、ItemStack、assessment、lease、material、
  reservation、Action、Technique 或 Skill authority；
- 本阶段不使用 Minecraft API，故不新增专用 GameTest；对应提交仍须在 Java 21 环境通过 clean build、JUnit 与
  仓库的 runGameTestServer。本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过；
- 真实客户端、独立专用服、多 Bot、loaded-site survey、lease、保护/玩家修改、source allocation/reservation、
  placement candidate、原版 placement、checkpoint 与完整 construction/redstone E2E 仍需后续独立验证；
  P5D/P5 总退出门不因此关闭。
