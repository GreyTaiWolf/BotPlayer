# ADR-0044：有界施工工作包显式材料需求合同

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0015、ADR-0017、ADR-0029、ADR-0030、ADR-0036、ADR-0042、ADR-0043

## 背景

ADR-0030 的 `ConstructionWorkPlan` 只把一个 exact Blueprint 分成有界、exact-cover 的
`ConstructionWorkPackage`。ADR-0036 的 `BlueprintPlaceableItemEvidence` 只为**整个** Blueprint 的每个
complete target state 提供 explicit item declaration，并按 `(itemId, materialClass)` 得到全蓝图 declared
quantity。两者都没有 package-level item manifest。

ADR-0043 的 A7 又只观察活动 Bot 自己背包中 default stack 的 item-level aggregate count；它刻意把相同 item 的
permanent/temporary quantity 合并以避免双计，也不分配 slot、来源或 reservation。因此 future construction 不能把
A7 的 `AVAILABLE`、一个裸 package ordinal 或一份全蓝图 aggregate 直接解释为某个 work package 已拥有材料。

在任何 material reservation、container/warehouse sourcing、placement candidate、Technique、Skill 或 world write 之前，
需要一个纯 Java 的中间 Contract：从一个 complete plan 中**真实存在的一个 package**及其 complete A4 declaration
重导该 package 的 explicit item demand。它必须完整绑定 plan/key/evidence，保留 permanent/temporary 类别，拒绝
blueprint/key/requirement 漂移，但不能产生 allocation 或执行 authority。

## 决策

### 1. 新增完整绑定的 immutable package demand

新增：

```java
ConstructionWorkPackageMaterialDemand(
    ConstructionWorkPlan workPlan,
    ConstructionWorkPackageKey workPackageKey,
    BlueprintPlaceableItemEvidence declaredEvidence,
    BlueprintPlaceableItemRequirements declaredRequirements)
```

推荐的公开 derive factory 为：

```java
ConstructionWorkPackageMaterialDemand.derive(
    ConstructionWorkPlan workPlan,
    ConstructionWorkPackageKey workPackageKey,
    BlueprintPlaceableItemEvidence declaredEvidence)
```

结果保存 complete immutable `workPlan`，而不是裸 package ordinal 或任意 cell list。`workPackageKey` 必须实际存在于
`workPlan.workPackages()`；即使一个 foreign/missing key 使用相同 ordinal，或带有相同 Blueprint 的合法-looking
identity，也一律拒绝。这样 package cell、prerequisite 图和 Blueprint 的 id/revision/content 都仍由同一个 plan 固定。

`declaredEvidence.blueprint()` 必须 `equals(workPlan.blueprint())`。不同 blueprint UUID、revision、content hash 或
canonical cell content 的 evidence 都不得被重用；A4 的 full-state exact-cover 仍是唯一 item mapping 输入。

### 2. demand 必须从该 package 的 exact cells 重导

factory 对目标 package 的每一个 `BlueprintCell` 调用既有 exact offset declaration，再按：

```text
(explicit placeableItemId, BlueprintMaterialClass)
```

聚合为 `BlueprintPlaceableItemRequirements`。其 canonical order、defensive copy、每项/总量边界复用既有 A4 DTO；
`totalDeclaredItems()` 必须严格等于该 package 的 cell 数。permanent 和 temporary 即使映射到同一 explicit item 也保持
两条独立 requirement，不能提前依 A7 的 item-level aggregate 分配。

public canonical constructor 重新导出 requirements，并要求 supplied `declaredRequirements` 与重导值完全相等。因此
forged count、遗漏/多余 item/class、来自全蓝图或另一个 package 的 aggregate 都 fail closed；结果不是可由 caller
手工包装的 loose summary。

### 3. A8 不是可用性、reservation 或施工授权

`ConstructionWorkPackageMaterialDemand` 只表示：“若 future route 选择这个 exact package，A4 declaration 对该
package cell 集合结构上要求哪些 explicit item/class quantity。”它不表示：

- A7 已在本 tick 看见足量材料，或这些 material 已在 permanent/temporary package、slot、container、warehouse、
  player transport 或多 Bot 间分配；
- item 已被 reservation、slot lock、移动、扣除、制作、运输、生成、退款或在任何 source 中存在；
- site/world 已 loaded、fresh、empty、accepted、leased、可达或保护/ownership/human confirmation/安全已通过；
- item 会在真实 `useOn` context 中放出完整 state，或任何 `PlacementCandidate`、Action、Technique、Skill、
  checkpoint、lifecycle、AI、network、redstone 或 world write 已发生。

future side-effecting route 必须在自己的 server owner thread 重新验证 current bot generation、A4-R1 registry/default
state、A7 native inventory/menu（若使用）、slot/source allocation、reservation、site/world/protection/safety、原版
interaction 和真实结果；不得将本 DTO 跨 tick/restart 重放为 permit。

### 4. 本阶段明确不接线

本 ADR 不改 A0/A1/A4/A4-R1/A7 现有语义；不读 Minecraft registry/world/chunk/container/NBT/item stack；不接
`ResourceReservationService`、materials/temporary-area lease、inventory transaction、container/warehouse、Action、
Technique、Skill、checkpoint、lifecycle、network、client、AI 或 redstone。它也不创建 cache、thread、tick task、
persistence、configuration 或 command。

因此 A8 只是 exact package demand manifest，不是 `AVAILABLE`、material-ready proof、reservation 或 P5D/P5 完成。

## 被否决方案

### 方案 A：从全 Blueprint requirement 按比例或 package ordinal 分摊

否决。多个 package 可以含相同 item/class，比例/ordinal 无法从完整 target-state mapping 重建哪个 exact cell 属于
哪个 package，且会在 revision/content/package partition 变化后误分配。A8 只能遍历 exact package cells。

### 方案 B：把 A7 的 item-level aggregate availability 直接拆回 permanent/temporary package

否决。A7 故意只消除同 item 的双计；它没有 slot/source ownership、类别 allocation、TTL、release 或 post-observation
revalidation。将其拆分会把瞬时 observation 伪装成 material reservation。

### 方案 C：直接为每个 `(itemId, class)` 调用 `ResourceReservationService`

否决。已有 reservation key 没有可信 slot/source/count allocation 语义；提前锁 item ID 会造成 stale、double-count 或
“未物理持有”的租约。材料 reservation 必须在独立 ADR 中先定义 source/quantity、owner thread、TTL、release、
generation cleanup 与 native revalidation。

### 方案 D：在 demand 中加入 site、world、placement 或 Action handle

否决。这会跨越 A1/A4 的纯数据边界，并把结构性需求误写为施工许可。site、registry、availability、reservation 与
真实 interaction 必须各自独立重新验证。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/material/` pure Java record 和 JUnit；既有 Blueprint/work plan/evidence/A7、reservation、
  Action、Technique、Skill、lifecycle、network、client 与 persistence 不变；
- 性能：一个 plan 最多 256 cells、一个 package 最多 64 cells，derive 只作有界 state-to-item lookup 与 aggregation，
  不读 Minecraft、不创建线程/cache 或 Tick 工作；
- 安全：exact plan membership、complete evidence equality、re-derived requirement equality 与 class separation 阻止
  stale/foreign package、item mapping drift、forged count 和 A7 aggregate 被提升为 allocation/permit；
- 许可证：只使用既有 DTO 与 JDK 标准库，不引入依赖或第三方内容。

## 迁移和回滚

当前没有 construction package material persistence、payload、checkpoint 或 production route，故无需数据迁移。
future 如支持 alternative item、package split/merge、source/slot allocation、material reservation 或 partial fulfillment，
必须新增版本化 Contract，不能修改 A8 的 exact re-derivation 含义。

回滚只删除未接线 manifest/test/documentation；不会改变 world、inventory、roster、reservation、payload、
checkpoint 或 playerdata。

## 验证方式

- 纯 JUnit 覆盖 canonical/immutable demand、同 item 跨 permanent/temporary class、65/256-cell 的真实 plan
  partition/total、evidence revision/content/identity drift、foreign/missing key、forged requirements 和 DTO purity；
- 静态复核确认公开 DTO 与 factory 不含 Minecraft runtime、ItemStack、reservation、site/world、Action、Technique、
  Skill 或 placement authority；
- 本阶段没有 Minecraft API，故不新增专用 GameTest；对应提交仍须在 Java 21 环境通过 `clean build`、JUnit 与仓库
  的 `runGameTestServer`。本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过；
- 真实客户端、独立专用服、多 Bot、container/warehouse/source allocation、material reservation、contextual
  placement、checkpoint、玩家修改与完整 construction/redstone E2E 仍需后续独立验证；P5D/P5 总退出门不因此关闭。
