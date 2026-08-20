# ADR-0040：严格消耗品的原版提交边界与精确终态优先级

- 状态：Accepted
- 日期：2026-08-20
- 取代：ADR-0018
- 相关：ADR-0003、ADR-0015、ADR-0017

## 背景

ADR-0018 的 `LivingEntity.updateUsingItem(ItemStack)` `HEAD` 围栏能在原版更新开始前
拒绝已观察到的严格消耗品漂移，却不能覆盖该调用内部的全部可重入点：NeoForge
`LivingEntityUseItemEvent.Tick` 与物品的 use-tick 实现都可能在 `HEAD` 后、原版
`completeUsingItem()` 前改变条件。另一方面，Finish 监听器和 `PlayerTickEvent.Post` 可以在
原版已消费牛奶后、生命周期 Action mailbox drain 前请求取消。

把后一类取消仍当作“消费前取消”会让 Action mailbox 的取消优先级覆盖已经发生的物理结果，
产生“牛奶已消费却报告取消”的矛盾。把它直接改报成功同样错误：请求的 Skill 终态和真实
Action receipt 必须各自保留。

## 决策

严格 `UseItem` 只在同一个版本精确的 Mixin 中使用两个 `require = 1` 注入点，但二者范围不同：

- `LivingEntity.updateUsingItem(Lnet/minecraft/world/item/ItemStack;)V` 的 `HEAD` 对活动、已开始且
  带 strict preconditions 的 `UseItem`（任何 native use mode）做消费前快照和时间围栏；
- 同一方法中对 `LivingEntity.completeUsingItem()V` 调用的 `BEFORE` 注入只处理 exact strict
  `FINISH_NATURALLY`，在最终原版提交点再次复核。只有这次复核通过，活动 strict-natural state
  才单向进入 `ENTERED`；随后不得允许嵌套 `updateUsingItem` 使用外层保存的 stale stack 再次消费。

两个点均从 authoritative server tick 复核同一 `ActionEnvelope`：`currentTick >= deadlineTick` 或
`currentTick - startedTick >= maxTicks` 时必须在原版消费前拒绝。这样原生 `doTick()` 早于同 tick 的
`BotActionRuntime.tick()` 时不会先物理消费、再被 runtime 报为 timeout；时间读取异常、倒退或状态异常也
失败关闭。hand、物品指纹、原生 inventory menu/cursor/41 槽快照、批准效果或时间围栏失败时，只走已有
原版 `RELEASE_USE_ITEM`/`stopUsingItem()` 清理并取消当次调用；不得直接写库存、效果、方块或 Skill
状态，终态仍由后续 Action runtime 按 deadline/maxTicks 结算。真人、非 strict 使用，以及 completion
点的非自然完成使用保持原版路径。

一旦 state 已进入 `ENTERED`，同一完整 immutable `ActionEnvelope` 的取消返回“等待精确
Action 终态”而不是写入 cancellation-first mailbox。`SkillRuntime` 保留该 action 的 completion
identity，禁止推进下一 DAG 节点，并按以下顺序结算：

1. `SUCCEEDED` 必须先经过节点的既有 success verifier，验证成功后才应用请求的
   `CANCELLED`、`PREEMPTED` 或 `PAUSED`；
2. `FAILED` 和 `STALE` 保持真实失败，绝不被请求终态改写；
3. `CANCELLED`/`PREEMPTED` receipt 可以结算已请求的终态；
4. 已入 inbox 的精确 receipt 在同 tick 的 plan deadline 前优先 drain。为此
   `SkillNodeContext` 允许 `currentTick == deadlineTick` 仅用于此类已入队回执；普通
   `SkillRuntime.tick` 仍在启动新工作前先执行 deadline 检查。

该 plan receipt 的优先级例外不放宽 `ActionEnvelope` 的 native deadline/maxTicks 围栏：仍在使用中的
物品不能在 action 的同 tick timeout 边界发生物理消费。

严格 release 标记必须在发送原版 release packet 前写入，防止 packet 回调重入。生命周期的
generation close/停服 teardown 是例外：它们隔离并关闭该 generation，不承诺等待延迟 receipt，
因为原来的 authoritative body 已被撤销。

## 被否决方案

### 方案 A：只增加 Event 或 Post tick 监听器

否决。监听器不能在全部第三方 use-tick 修改之后、原版完成之前形成稳定顺序；Post 更已晚于
物理消费。

### 方案 B：在 `completeUsingItem()` 返回后再标记提交

否决。Finish callback 可以在返回前重入取消，仍会落入 cancellation-first mailbox。

### 方案 C：已提交时直接把 Skill 标记成功或取消

否决。前者忽略调用者终态，后者抹掉真实 Action evidence；两者都破坏可审计性。

### 方案 D：让 deadline 优先于已排队回执

否决。receipt 已证明动作在期限内完成，timeout 覆盖它会把确定事实改写为调度时序。

## 兼容性、性能、安全与许可证影响

- 兼容性：目标方法和 invocation descriptor 均精确固定；映射或字节码漂移会因 `require = 1`
  在加载期失败。其他模组若重定向相同 invocation，必须在目标 NeoForge 版本实测。
- 性能：只对当前 bot generation 的有界活动 strict state 复核；不扫描世界、不异步、不新增
  常驻队列。
- 安全：未知 manager、线程错误、状态漂移、时间读取异常/倒退、fence 不匹配和 cancellation ingress
  失败都失败关闭；未提交的使用会停止，已提交的使用只接受 exact receipt。没有新增凭据、网络或世界写入面。
- 许可证：不新增依赖。

## 迁移和回滚

`UseItemPreconditions` 仍为可选，旧构造、非 strict 使用和普通 Mixin 路径不变。若目标版本的
Java 21/NeoForge 验证发现 invocation 冲突，可一并移除两个 strict 注入和对应 strict handler；
不得只保留会把已消费动作伪报为取消的半套延迟终态逻辑。普通 Action/Skill 合同无需迁移。

## 验证方式

- 纯 Java：延迟取消的 verified success、failed/stale 保留、L0 pause/resume、receipt 与 deadline
  同 tick 的优先级，以及 native 围栏在 deadline/maxTicks 前一 tick 允许、边界 tick 拒绝和
  invalid/backwards timing fail-closed；
- NeoForge GameTest：最后 Tick `LivingEntityUseItemEvent.Tick` 的效果漂移与取消、Finish
  callback 取消、`PlayerTickEvent.Post` 取消，以及最终 native tick 恰在 action deadline/maxTicks
  边界时不消费牛奶/不清效果、随后走正常 timeout outcome；
- Java 21 `clean build`、常规测试和 `runGameTestServer` 必须验证 Mixin 注入、真人路径及目标
  NeoForge 时序；真实客户端/专用服仍需要验证 mod 组合与 packet/事件排序。

最终 native tick 的 deadline/maxTicks 两条 GameTest 已作为源码加入当前分支；它们在 Java 21
NeoForge runner 实际通过前仍只属于待验证回归，不能作为已运行证据。
