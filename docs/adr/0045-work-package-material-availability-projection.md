# ADR-0045：工作包材料可用性只读投影

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0029、ADR-0030、ADR-0036、ADR-0039、ADR-0042、ADR-0043、ADR-0044

## 背景

ADR-0044 的 P5D-A8 `ConstructionWorkPackageMaterialDemand` 只从 exact work plan 中真实存在的一个
package 和完整 A4 declaration 重导 `(itemId, materialClass)` demand。它刻意保留 permanent/temporary class，
不包含 bot、inventory、slot/source allocation 或 reservation。

ADR-0043 的 P5D-A7 `ConstructionMaterialAvailability` 则只在 authoritative server thread 对活动精确 Bot body
的 empty native `InventoryMenu` 作一次短寿命、整 Blueprint 的 item-level observation。为了避免同一 item 的
permanent/temporary requirement 双计，A7 的 `Finding` 只按 `itemId` aggregate；`UNAVAILABLE_MENU` 和
`UNAVAILABLE_REGISTRY` 也刻意没有零库存 finding。

future construction route 仍需要诊断“这个 exact package 的 item totals 在这一次 A7 snapshot 中是否孤立足量”。
但是不能把 A8 的 class-level demand 与 A7 的 item-level count 错配，不能把一个 full-blueprint shortage 误当作
每个 package shortage，也不能把多个 package 分别看似足量误升格为并行 allocation、reservation 或施工许可。

## 决策

### 1. 新增只读、私有构造的 A8→A7 投影

新增 final immutable class：

```java
ConstructionWorkPackageMaterialAvailabilityProjection.project(
    ConstructionWorkPackageMaterialDemand demand,
    ConstructionMaterialAvailability sourceAvailability)
```

它保留完整 `demand` 和完整 `sourceAvailability`，并只公开 immutable `status`、canonical item-level
`findings` 与 `observedItemTotalsSufficient()`。constructor 为 private，调用者不能包装任意 status/finding。

`demand.declaredEvidence()` 必须严格 `equals(sourceAvailability.declaredEvidence())`；只相同 item ID、Blueprint UUID、
revision、content hash、package ordinal 或全 Blueprint requirement 都不足够。漂移时 fail closed。

### 2. 只为比较而临时按 item aggregate

当 A7 source 是 `AVAILABLE` 或 `SHORTAGE` 时，A9 只将 **这一个 package** 的 A8 requirements 暂时按
`itemId` 合并，再与 source `Finding.availableCount` 比较。每个 A9 finding 固定为：

```text
(itemId, packageRequiredCount, observedAvailableCount, packageShortageCount)
packageShortageCount = max(0, packageRequiredCount - observedAvailableCount)
```

`packageRequiredCount` 总数严格等于该 A8 package cell 数；同 item 的 permanent/temporary A8 requirement 在
projection 中只出现一次 item finding，**不会**给每个 class 重复使用 observed count，也不会从结果反推 class、slot、
stack、source 或 package allocation。

A7 的整蓝图 `SHORTAGE` 不妨碍某一个 package 的 item totals 在 isolation 中为
`OBSERVED_ITEM_TOTALS_SUFFICIENT`。反之，A7 `AVAILABLE` 也只是那一 snapshot 的 observation。多个 package
分别为 sufficient 时，它们不能相加、不能并发消费同一 observed item，也不表示 bot 已拥有任何 material。

source 是 `UNAVAILABLE_MENU` 或 `UNAVAILABLE_REGISTRY` 时，A9 原样映射为同名 unavailable status 且 findings
必须为空；不按零库存输出 package shortage。usable source 中若缺一个 package item 的 A7 finding，投影直接拒绝。

### 3. A9 不创建任何运行时 authority

`OBSERVED_ITEM_TOTALS_SUFFICIENT` 只表示“该次 accepted A7 snapshot 的 item total 足以覆盖这个 package 的
structural demand”。它**不**表示：

- 任何 permanent/temporary class、package、Bot 或未来 Tick 已得到 allocation；任何 item 存在于可移动的 slot、
  container、warehouse、transport 或多 Bot source；
- native menu fence、bot generation、registry/default state、source inventory、site、world/chunk/protection/safety 或
  player ownership 在当前仍有效；
- material 已 reservation、slot lock、move、consume、craft、transport、refund，或一个/多个 package 可以开始；
- `useOn` 能产生 complete state，或已经发生 placement candidate、Action、Technique、Skill、checkpoint、lifecycle、
  network、AI、redstone 或 world write。

future side-effecting route 必须在 server owner thread 重新获取并验证自己的 runtime/generation、A4-R1、A7 native
menu/source、allocation/reservation、site/world/protection/safety、原版 interaction 与真实结果；不得跨 tick/restart
replay A9 为 permit。

### 4. 本阶段明确不接线

不改 A7 sampler、A8 demand、registry/world/container、`ResourceReservationService`、spatial/temporary lease、slot/source
allocation、inventory transaction、placement candidate、Action、Technique、Skill、checkpoint、lifecycle、network、client、
AI 或 redstone；不创建 cache、thread、tick task、persistence、configuration、command 或 GameTest adapter。

## 被否决方案

### 方案 A：把 A7 count 分给 permanent 和 temporary requirement

否决。A7 特意在 item level aggregate；将相同 count 复制给两个 class 会双计，且没有 allocation/source/slot ownership。

### 方案 B：只看 A7 的整 Blueprint `AVAILABLE|SHORTAGE`

否决。full Blueprint `SHORTAGE` 时单 package 仍可 observationally sufficient；反之 status 也不能说明 item-level
package shortfall。必须比较 exact package totals 与 source finding。

### 方案 C：把所有 sufficient projection 当作可并行 work set

否决。每项 projection 都可能引用同一 source `availableCount`，没有 cross-package allocation 或 reservation。并行化会
重复消费同一 observation。

### 方案 D：把 A7 fence 或 A9 sufficient 当作施工许可

否决。A7 fence 是过时后即无效的 observation metadata，不是 live native handle；A9 不重新验菜单、registry、world、
site 或任何 interaction。真正执行必须有独立 server-thread revalidation 和 authority contract。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/material/` pure Java projection/JUnit；A7/A8、inventory、reservation、site、Action、
  Technique、Skill、lifecycle、network、client 与 persistence 不变；
- 性能：Blueprint 最多 256 cells，A8 package 最多 64 cells，A9 只作有界 DTO aggregation/index/compare；不读
  Minecraft、不创建线程/cache 或 Tick 工作；
- 安全：exact evidence equality、unavailable-without-findings、source finding coverage、one-item-row aggregation 和
  private constructor 阻止 evidence drift、零库存伪造、class double-count、手工 status/finding 以及 snapshot→permit
  提升；
- 许可证：只使用既有 DTO 与 JDK 标准库，不引入依赖或第三方内容。

## 迁移和回滚

当前没有 package allocation、material reservation、source/slot persistence、checkpoint 或 production construction route，
无需数据迁移。future alternative item、partial fulfillment、source allocation/reservation、package scheduling 或 real
placement 必须新增版本化 Contract，不能改变 A9 的 single-package observation-only 含义。

回滚只删除 projection/test/documentation；不改变 world、inventory、reservation、roster、payload、checkpoint 或
playerdata。

## 验证方式

- 纯 JUnit 覆盖同 item 跨 class 的一次 item-total 比较、full Blueprint `SHORTAGE` 而单 package sufficient、exact
  package shortage、unavailable 空 findings、evidence drift、65/256-cell 的真实 partition、canonical/immutable 和
  DTO purity/private constructor；
- 静态复核确认公开 API 无 Minecraft runtime、ItemStack、reservation、site/world、Action、Technique、Skill 或
  placement authority；
- 本阶段不使用 Minecraft API，故不新增专用 GameTest；对应提交仍须在 Java 21 环境通过 `clean build`、JUnit 与
  仓库的 `runGameTestServer`。本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过；
- 真实客户端、独立专用服、多 Bot、container/warehouse/source allocation、reservation、contextual placement、
  checkpoint、玩家修改与完整 construction/redstone E2E 仍需后续独立验证；P5D/P5 总退出门不因此关闭。
