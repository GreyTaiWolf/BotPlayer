# ADR-0038：已加载世界候选施工站点调查适配器

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0013、ADR-0017、ADR-0023、ADR-0029、ADR-0030、ADR-0033、ADR-0035、ADR-0036

## 背景

ADR-0033/0035 已有 `ConstructionSiteBinding` 与 `ConstructionSiteSurvey` 的纯 Java data Contract：caller 必须为
每个 exact Blueprint target 提供 `UNKNOWN`、`EMPTY` 或 full-state `OCCUPIED` evidence，assessment 再从该
complete immutable survey fail-closed 地推导。它有意不读取 Minecraft world，故无法替代一个真正受主线程和
已加载区块约束的采样点。

P5D 后续需要将 candidate binding 与真实已加载 world 的**瞬时**事实对接，但不能因为调查而加载 chunk、持有
live `ServerLevel`、绕过玩家动作、创建 site/material lease，或把一次 read-only snapshot 误写成施工许可。

## 决策

### 1. 新增窄的 server-thread sampler

新增：

```java
ConstructionSiteSurvey MinecraftConstructionSiteSurveySampler.sample(
    ServerLevel level,
    ConstructionSiteBinding binding)
```

调用一开始必须取得 `level.getServer()`，并要求 `server.isSameThread()`。缺少 server 或 off-thread 调用抛出
`IllegalStateException`；在任何 world-state read 之前，binding 的 `anchor.dimension()` 必须精确匹配
`level.dimension().location()`，不匹配抛出 `IllegalArgumentException`。sampler 使用当前
`server.getTickCount()` 作为 `observedTick`，不缓存 level、state、chunk 或任何 Minecraft 活动对象。

### 2. 只采样 binding 的 canonical Blueprint cell

sampler 只遍历 `binding.workPlan().blueprint().cells()` 的 canonical list；现有 Blueprint Contract 已限制它为
1–256 个唯一 cell。每一项必须经 `binding.targetPosition(cell.offset())` 做 exact translation，不能接收或扫描
任意邻近坐标、work package 外 offset、半径、chunk、entity、BlockEntity/NBT 或玩家背包。

每个 target 的 read 顺序固定为：

```text
server thread + exact dimension
-> target coordinate
-> build-height guard
-> level.isLoaded(position)
-> level.getBlockState(position)
-> immutable TargetObservation
```

`getBlockState(...)` 前绝不调用 `getChunk`、ticket/force-load API 或其他会加载 chunk 的 API。build-height 外或
未加载位置直接成为 `UNKNOWN`，并且不能因调查改变 `isLoaded` 结果。

### 3. 只把可安全编码的完整 native state 作为 evidence

对已加载且在 build-height 内的 target：`BlockState.isAir()` 产生 `EMPTY`；其他 state 产生
`OCCUPIED(BlockStateFingerprint)`。fingerprint 由 block registry key 与完整 properties map 构成，并以
`Property#getName(value)` 的 Minecraft serialized value 写入 canonical `TreeMap`；不得用 enum/`Comparable`
的 `toString()`，不得只用 block ID，也不得读取 NBT/component。

读取、registry key、property serialization 或 fingerprint validation 的任一 `RuntimeException` 都只使**该 target**
成为 `UNKNOWN`，不猜测 empty/occupied，也不让一个第三方 mod state 中止其他最多 255 个 target 的采样。
最终 `ConstructionSiteSurvey` 仍以已有 exact-cover validation canonicalize 所有 observations。

### 4. 本 snapshot 没有执行授权或持久 freshness

sampler 返回的是某一 server tick 的 immutable observation，不创建 site acceptance、protection/ownership/human
confirmation、material availability/reservation、construction checkpoint 或 placement candidate。`survey.assess()` 的
`ACCEPTED_CANDIDATE` 仍只表示这一份 supplied evidence 与 Blueprint replacement policy 结构相容，绝不是
区块已保留、未来仍 loaded、允许破坏/放置、能够到达、材料足够或真实施工成功。

future lifecycle/Technique/Skill/Action route 必须重新验证 binding identity、generation/revision、dimension、loaded
state、protection、安全、材料 lease、原版 player interaction 与真实结果；不得把本 adapter 的 `observedTick` 当作
跨 Tick/重启 lease 或 universal world revision。

### 5. 本阶段明确不接线的内容

本 ADR 不实现或调用：

- chunk loading/ticket、world write、`BlockEntity`、NBT、container/inventory、registry item mapping、材料库存、
  reservation、production、lease、human override、checkpoint 或 persistence；
- navigation、perception sensor、safety、`GroundPlace`/其他 Action、Technique、Skill、bot lifecycle、network、
  AI/Provider 或 redstone logic。

因此 P5D-A5 是 narrow read-only world adapter，不是 P5D 建造、红石或 P5 总退出门。

## 被否决方案

### 方案 A：对未加载 target 调用 `getBlockState` 或主动加载 chunk

否决。调查本身不能扩大世界加载、性能或保护影响范围；未加载状态必须是显式 `UNKNOWN`。

### 方案 B：把未知 state 当作 air，或只比较 block ID

否决。两者都会把缺乏可信 state evidence 的位置误作可施工位置；完整 properties 和 `UNKNOWN` 都是
fail-closed 语义的一部分。

### 方案 C：复用 P3 `LocalBlockSensor` 或直接接 Action/Technique

否决。P3 有自身 budget/focus/projection 生命周期，Action/Technique 是副作用边界。P5D-A5 只需要一份
binding-exact snapshot；复用或接线会扩大能力并混淆候选调查与施工授权。

### 方案 D：把 `observedTick` 当作长期锁或 revision proof

否决。server tick 只记录 observation 时刻；玩家、模组或 chunk 状态可在下一 tick 改变。长期 freshness/lease
必须由未来权威 runtime 单独设计。

## 兼容性、性能、安全与许可证影响

- 兼容性：新增 `building/site/minecraft/` 的一个 stateless adapter 和 GameTest，不修改 Blueprint、site DTO、
  Action、Technique、Skill、lifecycle、payload 或 persistence；
- 性能：最多采样 256 个已加载 block state；不做半径扫描、chunk load、ticket、缓存、异步任务或写入；
- 安全：off-thread/wrong dimension fail closed，unloaded/out-of-height/codec failure 都保留 `UNKNOWN`，full native
  state 以稳定 immutable value 交给 DTO；
- 许可证：只使用 JDK、Minecraft/NeoForge 已有开发依赖和项目现有值对象，不新增依赖。

## 迁移和回滚

future construction session 必须在 server owner thread 调用 sampler，并将 snapshot 作为短寿命输入；在每个可能
副作用的 dispatch/placement 前重新做自己的 authoritative validation。若 future 需要 block entity semantics、
registry/item availability、protection or lease、batch budgeting、player edits 或 chunk policy，必须另建版本化 Contract。

回滚只删除这一未接线 adapter/GameTest；不会迁移 world、roster、inventory、reservation、network 或 checkpoint data。

## 验证方式

- NeoForge GameTest 覆盖 loaded air、matching/mismatching complete native properties、read-only invariance、far
  unloaded 与 out-of-build-height `UNKNOWN`、256 canonical cells、wrong dimension 和 off-thread rejection；
- 静态检查确认 production sampler 的唯一 native state read 是 `isLoaded` 后的 `getBlockState`，不含 chunk/ticket/
  world write、Action/Technique/Skill/perception/navigation/lifecycle integration；
- 对应提交仍必须通过 Java 21 `compileJava`、`clean build` 与 `runGameTestServer`。真实 dedicated server、
  multi-bot、玩家/保护模组在 survey 后修改方块、第三方 block-state codec failure 与后续 construction E2E 仍须
  单独实机验证；P5D/P5 总退出门仍未关闭。
