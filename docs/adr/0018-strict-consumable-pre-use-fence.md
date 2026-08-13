# ADR-0018：严格消耗品的原版使用前围栏

- 状态：Accepted
- 日期：2026-08-11
- 相关：ADR-0003、ADR-0015、ADR-0017

## 背景

严格消耗品 Action（当前为 `drink_milk_for_poison`）在真正使用原版物品前冻结原生背包
菜单、游标、完整库存、主副手指纹和批准的状态效果集合。普通生命周期 Post tick 只能在
`BotServerPlayer` 已经执行原版 `doTick()` 后观察这些条件。

这留下最终 Tick 时序：其他正常 `PlayerTickEvent.Pre` 监听器可以在牛奶最后一次原版更新前
加入未批准效果；原版牛奶随后消费并清除它，Post tick 仅看到“效果为空”，会把未经批准的
状态清除误报为成功。事件监听器顺序无法保证在全部第三方 Pre 监听器之后、原版消耗之前执行。

## 决策

新增一个仅限 1.21.1 的窄 Mixin：

- 目标精确描述符为
  `LivingEntity.updateUsingItem(Lnet/minecraft/world/item/ItemStack;)V` 的 `HEAD`；
- 仅当对象是 `BotServerPlayer` 且存在活动的严格 `UseItem` Action 时，委托服务端主线程的
  `MinecraftWorldInteractionBackend` 复核冻结条件；真人和普通/旧版 `UseItem` 保持原版路径；
- 若使用中的 hand、物品指纹、精确原生背包菜单、游标、41 槽快照或批准效果集合漂移，先走
  既有原版 `RELEASE_USE_ITEM`/`stopUsingItem()` 清理，再取消本次 `updateUsingItem`；
- 围栏无 manager、线程或检查异常时，停止 Bot 的原生使用并取消该次更新，绝不继续消费；
- Mixin 只阻止这一次原版物品消费；不会直接写库存、效果、方块或 Skill 状态，终态仍由既有
  Action runtime 在 Post tick 读取已记录的失败证据。

`updateUsingItem` 是内层实际消费方法。不得取消外层 `updatingUsingItem`，以保留原版外层的
收尾 bookkeeping。使用精确 descriptor 和 `require = 1`，让映射或字节码漂移在加载期失败。

## 被否决方案

### 方案 A：只在生命周期 Post tick 验证

否决。Post tick 已晚于原版消费，最终 Tick 的未批准效果可能已被牛奶清掉。

### 方案 B：使用 `PlayerTickEvent.Pre` 监听器

否决。无法要求所有第三方 Pre 监听器在本模组围栏之前运行；之后加入的效果仍可落入漏洞。

### 方案 C：直接修改库存或效果回滚

否决。这会绕过原版使用、事件和模组语义，也无法可靠回滚未知副作用。

## 兼容性、性能、安全与许可证影响

- 兼容性：只触碰 `BotServerPlayer`；精确描述符/`require = 1` 使版本漂移显式失败。其它 Mixin
  或模组若重定向同一内层方法，需要在目标 NeoForge 版本实测组合兼容性。
- 性能：仅在 Bot 正在使用物品且存在严格 Action 时读取有界菜单、库存和至多 16 个效果；不做
  世界扫描或异步工作。
- 安全：漂移、未知 manager 和异常均停止使用；无成功证据时 Action 失败关闭。不会记录或传输
  物品内容之外的凭据或模型数据。
- 许可证：不引入第三方运行时依赖。

## 迁移和回滚

严格 `UseItemPreconditions` 保持可选；旧四参 `UseItem` 构造和普通使用不受此 Mixin 约束。若
目标版本的干净 NeoForge 验证发现注入冲突，可移除该 Mixin 与严格消耗品自动注册；既有 Action
合同和普通物品使用保持可用，但不得再宣称严格全程状态保护。

## 验证方式

- 纯 Java：`UseItemPreconditions` 的 canonical effect、菜单、游标和 inventory 限界测试；
- NeoForge GameTest：成功、排队取消、发包前效果/游标漂移、使用中效果/游标漂移，以及最后
  一次使用 Tick 的 `PlayerTickEvent.Pre` 注入未批准效果；
- 干净 Java 21/NeoForge 构建必须验证精确 Mixin 注入并确保真人玩家路径没有变化。
