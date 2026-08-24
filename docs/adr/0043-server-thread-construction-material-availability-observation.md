# ADR-0043：服务端线程施工材料可用性观察

- 状态：Accepted
- 日期：2026-08-24
- 相关：ADR-0015、ADR-0017、ADR-0025、ADR-0029、ADR-0033、ADR-0035、ADR-0036、ADR-0038、ADR-0039、ADR-0042

## 背景

ADR-0036 的 A4 只将每个完整 Blueprint target state 显式声明为 item ID，并按
`(itemId, permanent/temporary)` 导出 declared quantity。ADR-0042 的 A4-R1 只在 server thread
验证这些 item 是 non-air `BlockItem`，且 default full state 等于声明。两者都故意不读 Bot 背包，
不能把 registry candidate 错写为可用库存。

未来 construction Skill 仍缺材料 reservation、容器/仓库、运输、站位、保护、人类覆盖、checkpoint 与真实
原版放置结果。不过在这些副作用路径之前，需要一个 narrow server-thread observation：从活动 Bot **自己的**
原生玩家背包读取已通过 A4-R1 的 complete declaration 所需默认 item stack 数量。它必须保持“不确定不等于零”
的失败语义，不能把 armor、副手、cursor、世界容器或有不同 component/damage 的同 ID stack 当成默认材料。

## 决策

### 1. 使用独立不可变结果，不修改 A4 DTO

新增纯数据：

```java
ConstructionMaterialAvailability(
  botId,
  botGeneration,
  BlueprintPlaceableItemEvidence declaredEvidence,
  observedTick,
  status,
  Optional<InventoryMenuFence>,
  List<Finding>)
```

`declaredEvidence` 保留 exact Blueprint id/revision/content identity；结果不持有 `BotServerPlayer`、
`ItemStack`、`Item`、`InventoryMenu`、`Level` 或 reservation token。usable result 的
`InventoryMenuFence` 只包含本次 `containerId`、`stateId` 和 selected hotbar，不是 live menu handle、
lease 或未来 transaction 授权。

status 固定为：

```text
AVAILABLE             所有 item-level aggregate requirement 当前足量
SHORTAGE              已安全观察背包，但至少一个 aggregate item 缺少数量
UNAVAILABLE_MENU      当前 native player inventory/cursor 不能安全观察
UNAVAILABLE_REGISTRY  A4-R1 或 default-stack registry encoding 无法成立
```

`UNAVAILABLE_MENU` 和 `UNAVAILABLE_REGISTRY` 不得带 finding 或 menu fence；它们不是
`available=0`、shortage 或 procurement 结论。usable `Finding` 的 `shortage` 必须严格等于
`max(0, required - available)`，而 `AVAILABLE`/`SHORTAGE` 必须与全部 finding 的短缺状态一致。

### 2. 同一 explicit item 的 permanent/temporary quantity 必须先聚合

`BlueprintPlaceableItemEvidence.declaredItemRequirements()` 仍按 `(itemId, materialClass)` 表达声明。
本观察在计数前按 explicit `itemId` 聚合所有类别的 required quantity，只产生一个 canonical item-level
finding。例如 permanent stone=2、temporary stone=2 而背包有 3 stone 时，结果必须是
`required=4, available=3, shortage=1`；不能让两条 class 各自拿同一个 `available=3` 而错误报告足量。

这个聚合只避免双计，不表示 3 个 item 已在 permanent/temporary work package 间分配、锁定、移动或预留。
future material reservation 仍需要独立版本化 Contract。

### 3. 只在 authoritative server thread 读取 exact native bot inventory

新增：

```java
ConstructionMaterialAvailability
MinecraftConstructionMaterialAvailabilitySampler.sample(
    BotServerPlayer player,
    BlueprintPlaceableItemEvidence declaredEvidence)
```

调用一开始要求 `player.getServer()` 存在且 `server.isSameThread()`；off-thread 调用在 registry/menu/inventory
访问前抛 `IllegalStateException`。sampler 读取当前 non-negative server tick、Bot runtime 的 `botId` 与 positive
generation，并要求 `runtimeHandle.player()` 仍精确指向传入的同一个 `BotServerPlayer` body；stale/replaced body
在 registry/menu/inventory 访问前抛 `IllegalStateException`。它们只绑定 observation identity，future 调用方仍必须
重新解析 lifecycle 当前 body/generation。

固定顺序为：

```text
authoritative server thread + bot runtime identity + observed tick
-> ADR-0042 exact registry/default-state preflight
-> default ItemStack fingerprint for every explicit item
-> exact active native InventoryMenu / stillValid / 46-slot shape / empty cursor
-> immutable 41-slot snapshot
-> only inventory slots 0..35 (hotbar + main) exact fingerprint count
-> immutable AVAILABLE / SHORTAGE / UNAVAILABLE result
```

registry/default-state failure 必须先于 menu 或 slot count 返回 `UNAVAILABLE_REGISTRY`，坏 declaration 不能被
错误表示为“库存为零”。只有 `containerMenu == inventoryMenu`、精确 `InventoryMenu.class`、`stillValid(player)`、
46-slot 形状与 empty cursor 同时成立，才读取 snapshot。active menu 被替换、subclass/形状漂移、`stillValid` 失败、
snapshot exception 或 cursor 非空时返回 `UNAVAILABLE_MENU`，不关闭 menu、不清 cursor、也不猜测数值。

每个 explicit item 以 current registry canonical key 解析，并用 `new ItemStack(item)` 经过既有
`MinecraftActionSnapshot.item(...)` 得到 default fingerprint。slot 只在
`ItemStackFingerprint.sameItemAndComponents(...)` 成立时累计 count，故同 item ID 但 damage 或 components
不同的 stack 不计入。armor `36..39`、offhand `40`、cursor、世界容器、末影箱或任意邻近 inventory 全部排除。

### 4. observation 不是材料或施工授权

`AVAILABLE` 只表示这一个 tick 的接受菜单快照中，默认 stack 的 aggregate count 不小于声明；它不表示：

- 任何 item 已被 reservation、slot lock、work package、temporary/permanent class、container、仓库或玩家
  运输计划分配；
- target site 已 loaded、empty、accepted、fresh、leased、可达，或保护、ownership、human confirmation、安全
  与站位已成立；
- A4 declaration 的 `BlockItem` 会在真实 `useOn` context 中产出完整 target state，或 item 一定会被消耗；
- `PlacementCandidate`、`GroundPlace`、Action、Technique、Skill、lifecycle、AI、network、checkpoint 或 redstone
  logic 已被调用。

`observedTick` 和 `InventoryMenuFence` 只说明观察时刻。任何 future side-effecting construction route 必须在自己的
server owner thread 重新验证 current bot generation、registry/default stack、native menu/cursor、inventory、
reservation、site/world、protection、安全、原版 player interaction 和真实结果；不得跨 tick/restart 重放本结果。

### 5. 本阶段明确不接线

本 ADR 不读取/写入 `ServerLevel`、chunk、ticket、BlockEntity、NBT、world container、仓库、末影箱或其他玩家
inventory；不移动/扣除/生成/预留/释放材料；不修改 Blueprint/site/lease DTO；不注册命令、配置、payload、
Action、Technique、Skill、lifecycle、AI 或 redstone 路径。它也不引入 cache、async task、persistence 或新的
`ResourceReservationService` authority。

因此 P5D-A7 是 narrow own-inventory observation，不是 material-ready proof、施工许可或 P5D/P5 完成。

## 被否决方案

### 方案 A：将每个 `(itemId, materialClass)` 与同一个 observed count 分别比较

否决。这会将同一 stack 重复用于 permanent 与 temporary 材料，产生错误的 `AVAILABLE`。A7 只能先做
item-level aggregate，且不能假装已经完成类别分配。

### 方案 B：只比较 item ID，或计入任意同 ID 的 damage/component stack

否决。默认 `BlockItem` stack identity 仍可能被 damage、custom component、BlockEntity/NBT 类 component 改变；
只比较 ID 会把未知语义的 stack 当成材料。A7 必须使用既有 canonical fingerprint 的
item+damage+components equality。

### 方案 C：将 armor、副手、cursor、容器或未知 menu 当作普通材料来源

否决。它们分别是装备/持手语义、正在进行的 transaction 或未经审查的容器语义。A7 只接受 empty cursor 的
active native player inventory，并且只读取 slots `0..35`。

### 方案 D：将 unavailable 映射为 `available=0` 或顺便创建 reservation

否决。menu/registry failure 只说明缺少可信 observation，不证明材料不存在；reservation 又需要 owner-thread
TTL、exact binding、release/renew、slot/容器竞争和失败恢复，不能由只读 sampler 猜测。

### 方案 E：在本 ADR 接入 `useOn`、GroundPlace 或直接方块写入

否决。真实 placement 还缺站位、支撑、视线、保护、结果验证、human override、checkpoint、Technique/Skill
分层和材料 lease。A7 不越过 `Skill → Technique → Action` 边界。

## 兼容性、性能、安全与许可证影响

- 兼容性：新增 `building/material/` immutable result 与 `building/material/minecraft/` stateless sampler、JUnit
  和 GameTest；既有 A4 DTO、site/lease、inventory transaction、Action、Technique、Skill、lifecycle、payload 与
  persistence 不变；
- 性能：最多聚合 256 个 declared cell/explicit item，读取一次 existing 41-slot native snapshot，只计其中
  36 个 main/hotbar slot；不扫描 container/world、不加载 chunk、不创建线程或 cache；
- 安全：off-thread fail closed，A4-R1 registry preflight 优先，exact menu/cursor fence、default fingerprint 和
  item-level aggregation 阻止无证据、双计或 component drift 被提升为材料事实；
- 许可证：只使用 JDK 与既有 Minecraft/NeoForge 开发依赖，不引入新依赖或第三方内容。

## 迁移和回滚

future construction owner thread 可以将本观察作为短寿命输入之一，但必须重新采样并取得自己版本化的
material/temporary reservation、site/protection/ownership/safety proof 与原版动作结果。若 future 需要替代 item、
container/warehouse sourcing、per-class allocation、slot lock、partial fulfillment、procurement 或 cross-restart
recovery，必须新增独立 Contract。

回滚只删除未接线 result/sampler/test/documentation；不会迁移或改变 world、inventory、roster、reservation、
payload、checkpoint 或 playerdata。

## 验证方式

- 纯 Java 测试覆盖 immutable/canonical item-level findings、permanent/temporary aggregate quantity、shortage
  arithmetic、forged `AVAILABLE`/unavailable zero result rejection 和 DTO runtime-authority purity；
- NeoForge GameTest 覆盖 default stone/default north stairs 分散于 main/hotbar 的足量无 mutation（含完整 menu
  state equality）、跨 material class aggregate shortage、armor/offhand/custom-component stack 排除、non-empty
  cursor 与通过 `openMenu` 打开的 native `ChestMenu` 的 `UNAVAILABLE_MENU`、non-`BlockItem` registry declaration 的
  `UNAVAILABLE_REGISTRY`，以及 valid declaration 的 off-thread fence；
- 静态复核确认 sampler 只调用 A4-R1、default ItemStack fingerprint 与 native player inventory snapshot，不含
  world/container/lease/reservation/Action/Technique/Skill/lifecycle integration 或 write；
- 对应提交仍须在 Java 21 环境执行 `clean build` 与 `runGameTestServer`。真实客户端、独立专用服、多 Bot、
  物品 mod component、玩家/menu 在观察后修改、container/warehouse/reservation、contextual placement 与完整
  construction E2E 仍须独立验证；P5D/P5 总退出门不因此关闭。
