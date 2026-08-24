# ADR-0042：服务端线程蓝图默认方块物品声明验证器

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0017、ADR-0025、ADR-0029、ADR-0033、ADR-0035、ADR-0036、ADR-0038、ADR-0039

## 背景

ADR-0036 的 A4 已把每一种完整 Blueprint target state 与 caller 显式 item ID 的映射固定为纯 Java
declaration。它故意不读取 Minecraft registry，也不将相同 block/item ID、partial properties 或 inventory
snapshot 当作“这个物品能放出该 state”的证明。因此它既不能过滤不存在或非方块物品的声明，也不应在 DTO
中塞入 live registry/world authority。

后续施工仍缺材料 availability/reservation、站位/支撑/视线候选、protection、human override、checkpoint 和
真实原版放置结果。不过在这些路径接线前，可以增加一个更窄的运行时观察：核对已完整声明的 item 是否确实
解析为 native `BlockItem`，并且该 item 所属 block 的**默认完整状态**是否严格等于声明的完整 fingerprint。
这不是 click context 的可放置证明，特别不能猜测 stairs/slab/waterlogged 等由上下文决定的状态。

## 决策

### 1. 独立、无状态的 server-thread registry adapter

新增：

```java
boolean MinecraftBlueprintPlaceableItemEvidenceValidator
    .matchesRegisteredDefaultBlockItems(
        MinecraftServer server,
        BlueprintPlaceableItemEvidence declaredEvidence)
```

输入必须已经是 ADR-0036 构造并 exact-cover 的完整 immutable `BlueprintPlaceableItemEvidence`；adapter 不接受
单个/partial mapping，也不从 target block ID 推导 item ID。调用开始要求 `server.isSameThread()`；off-thread
调用在任何 registry lookup 前抛出 `IllegalStateException`。adapter 不缓存 `MinecraftServer`、registry entry、
`Item`、`Block` 或 `BlockState`。

### 2. 只认可显式 item 的 non-air default-state exact match

对每一项已声明 `PlaceableItemEvidence`，固定执行：

```text
explicit item ResourceId
-> current BuiltInRegistries.ITEM exact lookup
-> BlockItem
-> its Block.defaultBlockState()
-> registry block ID + every serialized Property value
-> exact BlockStateFingerprint equality
```

missing item、non-`BlockItem`、air block、registry/property encoding failure，或任一 block ID/properties 不同，都返回
`false`。item lookup 后还必须以 `BuiltInRegistries.ITEM.getKey(item)` 与 declaration 的 resource location 完全
相等；registry alias 不是 canonical explicit item declaration。properties 必须使用 `Property#getName(value)`，不能使用
enum/`Comparable.toString()`；target fingerprint 缺少一个默认 property 也是不相等。此处“默认 state”只是 native
registry 的静态 candidate，而不是 item 的 `useOn` 结果或可放置性结论。

adapter 允许 runtime registry 中已登记的 `BlockItem`，并不以 namespace 猜测或限制映射；原版 GameTest 只用
vanilla fixture。未来若需要 mod-specific context、alternate item、tag/recipe、BlockEntity/NBT 或 placement-result
proof，必须另建有界、版本化 Contract。

### 3. 返回 true 不具有材料或施工授权

`true` 只说明本次 server-thread lookup 中，**每一条已有显式声明**与对应 `BlockItem` 的 default fingerprint
相等。它不表示：

- item 在 bot/玩家背包、容器或仓库中存在，数量正确、可移动、已预留或会被消耗；
- target 已 loaded/empty、site assessment 已 accepted、spatial/material lease、保护/ownership/human confirmation
  或 navigation/safety 已成立；
- 原版 click context 会形成该 state，或任何 placement、Action、Technique、Skill、lifecycle、AI、network、
  checkpoint 或 redstone logic 已被调用。

每一条未来会产生副作用的 construction dispatch 仍须重新验证完整 binding/revision、当前 registry 与物品、
site/world、leases、保护、安全、原版 interaction 和真实结果。这个布尔结果不能跨 tick/restart 当作 registry
revision、lease 或 replay proof。

### 4. 本阶段明确不接线

本 ADR 不修改 A4 records，也不读取 `ServerLevel` block/chunk、NBT、container/inventory、materials reservation、
site survey/lease 或任何 world write；不注册命令、`GroundPlace`、Action、Technique、Skill 或 lifecycle route。
它只是 P5D-A4 的 runtime consistency observation，而不是 P5D 建造能力。

## 被否决方案

### 方案 A：在 A4 DTO 内直接查询 registry

否决。会把纯 immutable declaration 变成 live runtime authority，破坏 ADR-0036 的可测试、可序列化输入边界。

### 方案 B：只比较 block ID 或接受 partial/default properties

否决。这样会把 east-facing stairs、top slab 或 waterlogged state 等上下文差异误判为已验证。必须完整 fingerprint
严格相等。

### 方案 C：调用 `BlockItem.useOn`、构造假玩家或直接放置以取得证明

否决。真实 use 会引入世界、背包、player interaction、保护和结果验证副作用；这些是尚未设计的未来施工路径，
不能由材料 adapter 越过 `Skill → Technique → Action` 边界。

### 方案 D：把 true 当作 inventory/material reservation 或 construction permit

否决。registry identity 与 item 可用性、材料 lease 和 site permission 是相互独立的权威；混合它们会使过期或
玩家修改后的声明被错误重放。

## 兼容性、性能、安全与许可证影响

- 兼容性：只新增 `building/material/minecraft/` stateless adapter、一个 GameTest 和本 ADR；既有 A4 DTO、
  Blueprint、site、reservation、Action、Technique、Skill、lifecycle、payload 与 persistence 不变；
- 性能：至多检查 A4 exact-cover 的 256 个 distinct states；不做 cache、异步任务、world read/load/ticket 或 write；
- 安全：server-thread fence、explicit-only item lookup、non-air `BlockItem` 与 full serialized state equality 均
  fail-closed；第三方 registry/property 异常只返回 false；
- 许可证：只用 JDK 与已有 Minecraft/NeoForge 开发依赖，不新增依赖。

## 迁移和回滚

future construction owner thread 可将它作为短寿命、再次核验的输入之一；若需要 registry revision、contextual
state、alternative item ranking 或 real placement result，必须增加独立 Contract。回滚只删除未接线 adapter/
GameTest/文档，不迁移世界、inventory、reservation、network 或 checkpoint 数据。

## 验证方式

- NeoForge GameTest 覆盖 exact stone/default stairs positive case、non-`BlockItem`、missing ID、air、完整非默认
  stairs 与 partial fingerprint rejection、server-thread fence，以及 Blueprint quantity 与一个 world witness block
  均不变；
- 静态复核确认 adapter 的唯一 Minecraft access 是 `BuiltInRegistries.ITEM/BLOCK` 与 `BlockItem` default state，
  不含 `ServerLevel` read、chunk/ticket、inventory/reservation、Action/Technique/Skill/lifecycle 或 world write；
- 对应提交仍须在 Java 21 环境通过 `clean build` 与 `runGameTestServer`。第三方 registry、真实 dedicated server、
  multi-bot、保护模组、contextual placement 和完整 construction E2E 仍需独立验证；P5D/P5 总退出门不因此关闭。
