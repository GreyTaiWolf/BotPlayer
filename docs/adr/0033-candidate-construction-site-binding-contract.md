# ADR-0033：候选施工站点绑定合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0017、ADR-0025、ADR-0029、ADR-0030

## 背景

ADR-0029 的 `Blueprint` 只定义有界、canonical 的相对方块内容，ADR-0030 的
`ConstructionWorkPlan` 只对同一 immutable Blueprint 给出 exact-cover、完整 package key 和有界 DAG。
两者都刻意不选择世界位置。未来真实施工不能只按 package ordinal 或蓝图 UUID 行动：它必须知道一个
候选 anchor、维度和由同一精确 Blueprint 派生的有限 construction bounds，才能把调查、区域 lease、材料、
placement 和人工确认精确关联起来。

但现在尚无安全的 `BuildSiteSurvey`、已加载 world snapshot、保护/领地检查、材料 item 映射、work-area
reservation、checkpoint 或实际 placement route。把其中任一项混入 A2 会把纯数据 Contract 错写成世界事实或
执行权限。

## 决策

### 1. anchor 只是一项候选坐标平移

新增 `ConstructionSiteAnchor(ResourceId dimension, BlockCoordinates origin)`。`origin` 表示 Blueprint
offset `(0,0,0)` 的候选坐标平移；Blueprint 不要求真的含有该 offset。`ResourceId` 与
`BlockCoordinates` 都是既有纯 Java 值对象，A2 不读取 `Level`、不加载 chunk，也不验证维度是否存在。

anchor 不是 survey result、accepted site、owner proof、区域锁、导航目标、材料权限或世界写入许可。

### 2. bounds 从所有 canonical cell 精确派生，平移溢出必须拒绝

`ConstructionSiteBounds.forBlueprint(blueprint, anchor)` 对每一个 canonical cell 使用 checked
`Math.addExact` 平移，并派生唯一的 inclusive minimum/maximum。横向结果仍经过
`BlockCoordinates.MAX_HORIZONTAL_COORDINATE`；y 的整数溢出和任何越界都抛出
`IllegalArgumentException`，绝不 wrap、截断或钳位。

bounds 不接受调用者声明的脚手架区、逃生缓冲、站位、清理区、未加载区或未来“建议范围”。它只表示目标
蓝图 cell 的精确 AABB；coordinate-only `contains(position)` 不推断维度，dimension-aware
`contains(dimension, position)` 才同时核对维度。

### 3. binding 同时围栏 exact WorkPlan、anchor 和派生 bounds

新增：

```text
ConstructionSiteBinding(
  non-zero siteId,
  ConstructionWorkPlan workPlan,
  ConstructionSiteAnchor anchor,
  ConstructionSiteBounds constructionBounds
)
```

公开构造器会重新从 `workPlan.blueprint()` 与 `anchor` 派生 bounds，任何不同 dimension/minimum/maximum
都拒绝。`binds(key)` 只接受当前 `workPlan.workPackages()` 中的完整
`(blueprintId, revision, contentHash, ordinal)` key，不能按 ID、hash 或 ordinal 部分匹配。
plan-level `targetPosition(offset)` 只平移实际存在于该 Blueprint 的 cell；任意“邻近”合法 offset 也必须
拒绝。future package consumer 必须使用 `targetPosition(key, offset)`，它还验证该 cell 属于 exact package，
防止 package A key 与 package B offset 混用。

`siteId` 只是 future survey/lease correlation 的非零候选 ID；A2 不维护 registry、持久化、授权或唯一性索引。

### 4. 本阶段明确不接线的内容

本 ADR 不实现或调用：

- `BuildSiteSurvey`、地形/流体/危险/保护/玩家建筑/加载状态检查，或 `UNKNOWN_UNLOADED` / accepted
  assessment；
- material item mapping、背包/容器读取、`ResourceReservationService`、work-area lease、checkpoint、
  human override 或玩家修改 diff；
- navigation、PlacementCandidate、Technique、Action、Skill、`UseOnBlock`、真实 BlockState 验证、
  NBT、temporary-support ownership、world write 或红石；
- Minecraft 活对象、`Level`、`BlockPos`、chunk loading 和任何网络/lifecycle bridge。

因此 `building/site` 是未来 survey/lease/placement 的输入 Contract，不是已完成 P5D 建造路径。

## 被否决方案

### 方案 A：直接保存调用者传入的 bounds 或 package ordinal

否决。它允许 stale revision、同内容异 ID、哈希漂移或扩大/缩小构建区，不能可靠关联后续 survey、lease 或
真实结果复核。

### 方案 B：A2 直接读取世界并判定 accepted site

否决。读取 loaded state、保护、实体和地形需要 main-thread、版本/权限和快照合同；此时把 DTO 标为
accepted 会绕过真正的 survey 退出门。

### 方案 C：提前把 block ID 映射为物品并预留材料

否决。block→item、替代配方、背包/容器证据、真人 viewer 写锁和原版消费验证还没有 P5D 权威路径；
`ResourceReservationService` 也不能替代一个已验证的 construction material model。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/site/` 纯 Java DTO/checked translation，不改 payload、world、Action、Technique、
  Skill、reservation 或 lifecycle API；
- 性能：Blueprint 已限制至 256 cell，bounds/target lookup 只在该有界集合内执行；不创建线程、队列、
  chunk 或 registry；
- 安全：完整 WorkPlan key 与派生 bounds 防止 partial identity/area drift；不把 siteId/anchor 当作权限；
- 许可证：只使用 JDK 和既有项目 DTO，不引入依赖。

## 迁移和回滚

未来 survey 只能把 server instance、generation、加载、危险、保护、人类确认和 world revision 的快照附着到此 exact binding；未来
work-area/material reservation、placement candidate 和 `ConstructWorkPackageSkill` 必须分别新增契约并在
实际原版玩家动作前重新验证 key/anchor/bounds。回滚 A2 只移除未接线 DTO，不会撤销任何世界、背包、
reservation 或 checkpoint 副作用。

## 验证方式

- 纯 Java 测试来源覆盖负/正/y 轴平移、inclusive bounds、known cell target、full package key fence、
  forged bounds/dimension、horizontal escape、integer overflow、零 site ID、未知 offset 与公开 DTO purity；
- 静态边界检查确认 `building/site` 只依赖 `action.interaction` 值对象及 `building.blueprint/construction`，
  不引入 Action runtime、Technique、Skill、reservation、checkpoint、navigation、lifecycle 或 Minecraft；
- 对应提交仍必须通过 Java 21 `clean build`、JUnit 与 `runGameTestServer`。它没有 Minecraft 行为，真实
  survey、材料、placement、5×5 小屋、玩家修改、checkpoint、红石和实机验证仍须后续独立完成。
