# ADR-0023：原子瞄准并放置方块动作合同

- 状态：Accepted
- 日期：2026-08-14
- 相关：ADR-0015、ADR-0017、ADR-0018

## 背景

现有 `PlaceBlock` 是 P5A 工作站放置的窄动作：它冻结预期落点状态并占用 `LOOK`，但不会在同一
动作内执行瞄准，也不冻结“放置前目标必须是精确空气”的完整事实。将它就地收紧会改变已经验收的
P5A 合同；将一个独立 `LookAt` 接在其前又会在两个动作之间释放 `LOOK`，无法关闭观察到原版包
发出之间的视线、方块、菜单和主手漂移窗口。

后续 P5D 的受限地面放置需要一个可复用、可审计的最小原子边界，但当前没有全局 Technique
coordinator，也没有批准把建筑能力接入 lifecycle、Skill 或 AI。

## 决策

### 1. 新增独立 P2 动作，不改变 `PlaceBlock`

新增 `WorldInteractionActionSpec.AimAndPlaceBlock`。它的不可变输入为：

```text
anchor                 冻结的原版命中方块、面与命中点
targetBefore           落点放置前的完整指纹
expectedPlaced         放置后的完整指纹
expectedHeldItem       主手物品完整指纹
```

它拥有 `MAIN_HAND + INTERACT + LOOK` 三个 channel，并独立映射为
`ActionKind.AIM_AND_PLACE_BLOCK`。全局 world-interaction schema 升至 v2；新动作自身为 v1。
旧 `PlaceBlock` 的字段、射线语义和行为保持不变。

### 2. 构造与验证均失败关闭

构造器必须拒绝：空主手、任何非精确 `minecraft:air{}` 的 `targetBefore`、任何 vanilla air
作为 `expectedPlaced`、跨维度或不同落点、非 anchor 指定面的相邻格、`inside=true`，以及不在
声明面上的命中点。

动作开始、瞄准后和验证时都必须重新检查：

- 原生 `InventoryMenu` 且 cursor 为空；
- anchor、精确 `targetBefore`、主手与可达性；
- 射线命中同一方块、同一面和同一冻结命中点；
- 放置后完整目标状态、未变化的 anchor、原生菜单，以及主手恰好减少一个方块。

旧交互的“同一方块坐标即可”的射线语义不被收紧；严格面与点匹配只属于该新动作。

### 3. 最终包围栏的顺序

在同一个 action 租约内，后端依次执行：

1. 捕获后的完整重验；
2. 原版 `lookAt(EYES, frozenHitPoint)`；
3. 再次完整重验；
4. 视线与冻结命中点的夹角不超过 0.5 度；
5. 紧邻原版 `UseItemOn` 包才标记副作用并发包；
6. 精确验证或统一 cleanup。

任一步失败都不得发包；已经可能发包的路径继续遵守现有原版 menu cleanup 与 fail-closed
终态约束。

### 4. 这不是 P5D 建筑功能

本 ADR 不创建 Technique、Skill、生命周期注册、玩家命令、AI 输入、导航、蹲伏、边缘/柱状
放置、脚手架、蓝图、批量建造或红石。后续任何 Technique→Action prebinding、全局 Technique
协调和具体建筑能力都必须单独决策、接线并验收，不能把本合同记作 P5D 完成。

## 被否决方案

- **收紧或替换旧 `PlaceBlock`**：会静默改变已验收的 P5A 工作站行为。
- **`LookAt` 后跟旧 `PlaceBlock`**：两个 action 之间释放 `LOOK`，无法保证发包前事实仍成立。
- **仅检查命中方块坐标**：同一方块的反面或不同命中点会越过冻结的原版交互语义。
- **先接入 P5D Technique/lifecycle**：当前只有自卫专用 runtime；提前注册第二个前台 runtime 会违反
  ADR-0017 的单 Technique 边界。

## 兼容性、性能、安全与许可证影响

新动作是加法，不改变已有动作的 opcode、字段或射线行为。其额外工作仅为同一服务器主线程上的
有界指纹/射线重验和一次瞄准；不引入网络协议、持久化格式、外部依赖或许可证义务。

它只调用已有的原版服务器交互入口，且在任何不确定状态下拒绝发包或以现有 cleanup 收束，不把
客户端、AI 或任意调用方赋予世界写权限。

## 迁移和回滚

现有调用者继续使用 `PlaceBlock`，无需迁移。未来受限 Technique 只能显式选择
`AimAndPlaceBlock`，并在移除前完成该动作的终态、取消和 generation cleanup。若需回滚，停止
创建新动作即可；旧 P5A 路径不受影响。

## 验证方式

- 纯 Java：构造器边界、schema/channel、旧/新射线语义分离、post-look final fence 顺序和
  start revalidation；
- NeoForge GameTest：原子转向并精确放置、`cave_air` target-before 漂移拒绝、隐藏相反面拒绝；
- 当前提交必须通过 GitHub Java 21 构建、单测和全部 GameTest 后才可标记为自动验证通过。
