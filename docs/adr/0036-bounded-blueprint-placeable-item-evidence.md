# ADR-0036：有界蓝图可放置物品声明合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0017、ADR-0029、ADR-0030、ADR-0033、ADR-0035

## 背景

ADR-0029 的 `BlueprintBlockRequirements` 只把 Blueprint cell 按 `(blockId, materialClass)` 作结构性聚合。
它有意不把 block ID 猜成 inventory item ID；而且这个聚合已经丢失了完整 `BlockStateFingerprint` 的
properties。现实中的物品能否在特定姿态、支撑、邻接和版本注册表下生成某个完整 BlockState，不能从同名
资源 ID、namespace、字符串替换或一份背包快照推导。

在没有 main-thread registry resolver、inventory/容器 evidence、材料 reservation、placement candidate 和
真实结果验证的阶段，后续施工规划仍需要一个小而明确的入口：未来可信 producer 必须逐个声明它认为可用于
**完整目标状态**的 item ID；缺声明时必须失败关闭，而不是默认把 blockId 当作 itemId。

## 决策

### 1. 单项声明只绑定完整 target state 和显式 item ID

新增：

```text
PlaceableItemEvidence(
  BlockStateFingerprint targetState,
  ResourceId placeableItemId
)
```

两字段都必须非空。它只是 caller-supplied declaration：本 DTO 不查询 Minecraft registry，不验证 item 是
`BlockItem`，不证明这个 item 在某个 click/context 下会得到 `targetState`，也不证明该 item 存在、可达、
已授权、已预留或会被成功消耗。

同一 `blockId` 但 properties 不同的 state（例如不同朝向/half 的 stairs）是不同 key；一个 state 与 item
恰好同名也只因为 caller 显式提供了该 declaration 才会被接受。

### 2. Blueprint evidence 必须 exact-cover 每一种 distinct full expected state

新增：

```text
BlueprintPlaceableItemEvidence(
  Blueprint blueprint,
  List<PlaceableItemEvidence> evidence
)
```

构造器要求 `evidence` 恰好一次覆盖 `blueprint.cells()` 中的每一种 distinct
`BlueprintCell.expectedState()`；缺项、foreign state、重复 state、null 或超过 Blueprint 的 256 项上限都拒绝。
它按 Blueprint 已 canonical cell order 中每个 state 的首次出现顺序 canonicalize，并 defensive-copy。

`placeableItemFor(BlueprintOffset)` 只接受该 exact immutable Blueprint 内的真实 target offset，先由 cell
取得完整 expected state 再查询 declaration；邻近/foreign offset 不能借用任何 mapping。没有“未声明时回退
到 blockId”的 API。

### 3. 物品数量只能从 exact state declaration 派生，且仍是 declared quantity

新增：

```text
BlueprintPlaceableItemRequirement(
  ResourceId placeableItemId,
  BlueprintMaterialClass materialClass,
  int count
)

BlueprintPlaceableItemRequirements(
  List<BlueprintPlaceableItemRequirement> entries
)
```

`BlueprintPlaceableItemEvidence.declaredItemRequirements()` 对每个 cell 先用其完整 expected state 查找
explicit evidence，再按 `(placeableItemId, materialClass)` aggregation；永久/临时材料永远分开。结果 canonical、
immutable，单项与总量都限制在 Blueprint 的 256 cell 边界。`totalDeclaredItems()` 只是这些声明数量的精确和，
不是 inventory availability、reservation、consumption、缺口或 procurement plan。

`BlueprintPlaceableItemRequirements` 自身故意只是未绑定的汇总，**不**携带 blueprint/work-package/site identity；
它不能单独作为 stale/replay fence、reservation、placement 或任何授权证据。

Blueprint object 本身保持在 evidence record 中，因此 blueprintId、revision、canonical cells/content 均被固定；
future work-package consumer 仍须以自己的完整 package key 再做 revalidation，不能把 A4 当作 package lease。

### 4. 本阶段明确不接线的内容

本 ADR 不实现或调用：

- Minecraft `Registry`、`BlockItem`、`ItemStack`、component/NBT、tag/配方/替代候选、真实 placeability 或
  placement result verification；
- inventory/容器读取、available/shortage 计算、`ResourceReservationService`、材料 lease、扣除/退款、
  production/采集/制作/运输；
- candidate site/survey/lease、world read、chunk/保护/危险、人类确认/ownership、navigation、
  `PlacementCandidate`、Technique、Action、Skill、checkpoint、network、lifecycle、world write 或红石。

因此 A4 是 future registry-aware producer 的纯 Java declaration/aggregation Contract，不是 material-ready
proof、物品映射权威、实际 placement 或 P5D 建筑路径。

## 被否决方案

### 方案 A：默认 `blockId == itemId` 或按 namespace/字符串猜测

否决。许多 block 没有同名 item，而相同 item 也可能受完整 state/context 影响；猜测会把未经证明的物品
授权变成施工输入。

### 方案 B：只在 `BlueprintBlockRequirements` 的 blockId 层做 mapping

否决。该汇总已经丢失 properties；不同完整状态会被悄悄合并，无法让 future resolver 对状态差异失败关闭。

### 方案 C：复用 `ItemStackFingerprint`、inventory snapshot 或 production ledger

否决。这些模型分别携带 count/damage/components、背包观察或受限生产白名单语义，不是 registry/placeability
证明；复用会让 declared mapping 误看成可用库存或预留。

### 方案 D：在 A4 内联读取 registry/world

否决。注册表版本、主线程、安全 snapshot、loaded state 和原版 use 结果需要独立 adapter/验证合同；纯 DTO
不能假装拥有这些保证。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/material/` 纯 Java records，不修改 Blueprint、site、Action、Technique、Skill、
  reservation、inventory、world、lifecycle、payload 或 client API；
- 性能：最多 256 cell/unique state/aggregate entries，查找和 aggregation 均在这一有界集合内，不创建线程、
  registry、cache 或 inventory snapshot；
- 安全：full-state exact cover、explicit-only mapping 和 offset fence 防止默认/partial mapping 被当作物品或
  placement 权限；
- 许可证：只使用 JDK 与既有值对象，不新增依赖。

## 迁移和回滚

未来 registry-aware main-thread adapter 必须说明 evidence source、server/registry revision 和 state/context
限制；未来 inventory availability/reservation 在 native use 前必须另行取得受限快照并重新验证 item/state/
package/site identity。若支持替代 item、contextual states 或 candidate ranking，必须增加新的有界版本化合同，
而不是把一项 A4 evidence 扩成模糊 alternatives。

回滚 A4 只移除未接线 DTO/测试，不会影响背包、reservation、world、network 或 checkpoint。

## 验证方式

- 纯 Java 测试覆盖 canonical/defensive copy、full properties distinct state、显式非同名 mapping、missing/
  foreign/duplicate/null/unbounded evidence、exact offset fence、65-cell aggregation、permanent/temporary
  separation、requirements bounds/duplicates 与公开 DTO purity；
- 静态边界检查确认 material records 不携带 Minecraft registry/world、`ItemStack`、inventory/reservation、
  site、Action、Technique 或 Skill authority；
- 对应提交仍必须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`。A4 没有 Minecraft production
  behavior；registry-aware evidence、inventory/lease、placement、5×5 小屋、玩家修改、checkpoint、红石和实机
  验证仍须后续独立完成。
