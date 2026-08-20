# ADR-0030：有界施工工作包图合同

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0015、ADR-0017、ADR-0023、ADR-0025、ADR-0029

## 背景

ADR-0029 已把 P5D 的小型 Blueprint 收敛为不可变、受限且可 hash 的纯 Java 数据，但它刻意不含
施工图、工作包或任何世界权限。设计中的真实建筑流程仍需要在 package 边界保存 checkpoint、按依赖
准备材料并在安全中断后恢复；若直接把未绑定 revision/hash 的裸 package index、任意 cell 子列表或
线性 Action 序列交给未来 Technique，会让旧蓝图、内容漂移和任意分包绕过后续 site、材料、安全和
原版交互验证。

本阶段只需要一个可以单独审阅的、有限的数据图边界。它必须能精确表示“某 Blueprint 的哪一份有界
cell 分区依赖哪些其他分区”，但不能把该图宣传成真实施工顺序、材料预留或世界修改许可。

## 决策

### 1. `building.construction` 只引入不可变、完整绑定的工作包 DTO

新增 `ConstructionWorkPackageKey`、`ConstructionWorkPackage` 和 `ConstructionWorkPlan`：

- key 必须同时绑定非零 `blueprintId`、正 `blueprintRevision`、完整 `BlueprintContentHash` 与
  `ordinal`；ordinal 只在 `0..15`，不能用裸 index、UUID 或历史 hash 代替；
- package 保存不超过 64 个 canonical `BlueprintCell` 和完整 key 前置列表，防御性复制、拒绝空包、
  包内重复 offset、自依赖、重复 prerequisite 和超过 15 个 prerequisite；
- plan 保存原始 immutable `Blueprint` 与 package 列表，所有 key 必须精确等于该 Blueprint 派生的
  `(id, revision, contentHash, contiguous ordinal)`；不能把同 ordinal 的旧 revision/content、不同 UUID
  或 future hash 当作同一个包。

这些类型没有 package state、完成回执、world position、site、module、placement task、reservation、
checkpoint、Technique permit 或 Action envelope。

### 2. plan 必须是有界精确覆盖，而不是任意 cell 列表

`ConstructionWorkPlan` 在构造时强制：

- 每个 Blueprint cell 按完整 `BlueprintCell` 语义恰好被一个 package 覆盖；缺失、重叠、同 offset
  的状态漂移和 Blueprint 外 cell 全部拒绝；
- 当 Blueprint 至多 64 cell 时，只允许一个 `1..64` cell 的小蓝图 package；当 Blueprint 超过 64
  cell 时，每包必须 `16..64` cell；
- package 总数不超过 `ceil(cellCount / 16)`，也不超过 16（ADR-0029 的 256 cell 上界）；
- prerequisite 必须是同一 plan 中存在的完整 key，整体必须是 DAG。

这样可防止小包把一个有界 Blueprint 扩成无界 scheduler，也可防止未来 checkpoint 仅凭 ordinal 误接到
另一版本内容。

### 3. 只提供确定性的内容分区和拓扑读取

`ConstructionWorkPlan.partition(Blueprint)` 对 canonical cell 列表做均衡、有限分割，并以相邻
package 建立保守线性依赖。它只为未来安全 checkpoint 的数据单元提供可复现输入，**不**宣称 canonical
offset 顺序就是可放置顺序。

`topologicallyOrderedPackages()` 使用 package ordinal 为 ready-node tie breaker，保证同一有效 DAG
无论传入 package/prerequisite 列表顺序如何都输出相同拓扑序。未来 site-aware compiler 必须在获得
真实站位、支撑、权限、材料、世界 revision 与 human override 证据后重新生成或复核物理依赖；不得直接
执行本阶段的线性分区。

### 4. material 信息仍只是结构性聚合

`ConstructionWorkPackage.plannedBlockRequirements()` 复用 `BlueprintBlockRequirements.fromCells`。它只
按目标 block id 和 permanent/temporary class 聚合；不把 block id 猜成 item id，也不创建、预留、消耗
或退款任何库存。为使该纯 Java factory 能被 `building.construction` 使用，`fromCells` 公开并明确保留
同一无库存/无世界语义。

### 5. 本 ADR 不建立生产建筑路径

本阶段不读写 Minecraft 世界，不注册 Technique route，不接 `AimAndPlaceBlock`、Action、Skill、
Navigation、Safety、Lifecycle、Network、Client、AI、红石或任何 MaterialReservation。它同样不解决
选址、BlockState rotation/mirror/connectivity、水、脚手架 ownership/cleanup、玩家修改、重启恢复或
真实 `5×5` 小屋。所有 P5D 路线图复选框继续未完成。

## 被否决方案

- **只用 package ordinal 或 UUID 标识分区**：revision/content 改变后会把旧 checkpoint 或 prerequisite
  错接到新 Blueprint。
- **允许任意 1 cell package**：256 cell Blueprint 可被伪装成无界逐方块调度，失去 package 边界与
  预算意义。
- **将 canonical offset 顺序当作施工顺序**：没有站位、支撑、临时结构、材料、保护或玩家变更证据时，
  它不是安全的物理计划。
- **在 package 中加入 item mapping、reservation 或 world task**：会跳过尚未建立的 registry、库存、
  site、权限与原版消费验证。
- **复用未跟踪的 `building.blueprint` 草稿**：其没有完整 immutable key、16 package 上限、小蓝图例外或
  稳定拓扑输出，且会混淆 A0 数据 schema 与 A1 construction boundary。

## 兼容性、性能、安全与许可证影响

- 兼容性：只增加纯 Java DTO 和一个原有 structural aggregation factory 的公开入口；不改网络、配置、
  持久化、Minecraft payload 或已运行 Skill/Action API。
- 性能：最多 256 cell、16 package、每包 64 cell、15 prerequisite；构造、精确覆盖和拓扑排序均有固定
  上界，不新增线程、队列、扫描或 Tick 工作。
- 安全：foreign/stale key、非连续 ordinal、重复/遗漏/漂移 cell、未知/self/duplicate prerequisite 和 cycle
  均 fail-closed；所有 DTO 不携带 Minecraft runtime object、NBT、ItemStack 或执行 capability。
- 许可证：仅使用项目已有 DTO 和 JDK 标准库，不新增第三方代码或依赖。

## 迁移和回滚

当前没有 construction work package 持久化、网络编解码、checkpoint 或生产 route，因此没有在线迁移。未来
如改变 key、包大小、拓扑规则或引入 modules，必须新增 schema/ADR 和明确旧数据转换或拒绝策略。回滚只移除
未引用纯 Java DTO，不影响世界状态。

## 验证方式

- 纯 JUnit：65/256 cell 的确定性分区、完整 key 绑定、small blueprint 单包例外、`16..64` 大 blueprint
  约束、16 包边界、精确覆盖/状态漂移/foreign key 拒绝、未知/环 prerequisite 拒绝、稳定 ordinal
  topological order、requirements 的结构性总量与防御性复制；
- 静态边界：`building.construction` 的公开 record component 不含 Minecraft runtime、NBT、ItemStack、
  `BotActionRuntime` 或 `TechniqueActionPermit`；
- Java 21 自动门：对应提交仍必须通过 GitHub Actions 的 `clean build`、JUnit 和
  `runGameTestServer`。本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过；
- 后续实机：本 ADR 没有 Minecraft 生产路径；真实客户端、专用服、保护模组、材料/BlockState、临时结构
  与多 Bot soak 必须等未来 placement route 后单独验收。
