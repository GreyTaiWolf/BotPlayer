# ADR-0016：原版死亡消费的耐久交接与失败关闭

- 状态：Accepted
- 日期：2026-08-04
- 关联：ADR-0011、ADR-0015

## 背景

`keepInventory=false` 时，原版死亡会把玩家背包转换为世界中的掉落实体；经验是否掉落还受
玩家模式和 NeoForge 事件影响。BotPlayer 同时拥有原版 `playerdata`、生命周期重生、
generation 清理和技能布局补偿，因此“原版方法已经返回”不足以证明这次消费已经安全落盘。

如果世界先保存掉落实体，而 `<uuid>.dat` 或 `<uuid>.dat_old` 仍保留死亡前背包，崩溃后
旧玩家数据会把已掉落物品再次恢复。相反，如果只清空内存而没有可重放记录，服务器在死亡
与重生之间崩溃时也无法区分“尚未消费”和“已经消费但玩家数据尚未提交”。旧 body、同步
保存重入或同 UUID replacement 还可能在 successor 提交后覆盖新数据。

本决策只处理 BotPlayer 的原版死亡消费持久性，不改变真人玩家保存逻辑，也不承诺掉落实体
的 exactly-once 投递。

## 决策

### 1. 以真实原版消费回执分流

生命周期不根据游戏规则或死亡原因推测背包是否已消费。只有 `BotServerPlayer.dropEquipment()`
实际进入 `keepInventory=false` 分支、耐久票据发布成功且原版方法正常返回，才产生
`VANILLA_DEATH_CONSUMED` 回执；正常到达死亡 TAIL 但未取得该回执时按 `PRESERVED` 收口。

每次消费使用不可变 `VanillaDeathTicket`，绑定：

- 非零 `transactionId`；
- `botId + generation + createdTick`；
- 是否保留经验、冻结的原版经验奖励；
- successor 应取得的精确 `level + total + progress` 经验快照。

死亡前已经是旁观者时，原版可能完全跳过背包消费回调，此时必须按 `PRESERVED` 收口，不得
仅凭 `keepInventory=false` 创建票据。若原版实际进入消费回调，物品消费、经验球发放和
successor 经验恢复必须共享同一张冻结票据；票据选择保留经验时禁止整个经验发放阶段，选择
消费经验时仍允许原版与 NeoForge 事件按冻结奖励运行。`dropEquipment()` 创建并发布票据
之后的模式变化不能改写该决策。

### 2. 在原版背包消费前发布耐久 tombstone

票据必须在调用原版 `dropEquipment()` 前写入：

```text
<world>/playerdata/botplayer-death-tombstones/<bot-uuid>.death-v2
```

V2 marker 最多 512 bytes，包含 magic `0x42504457`、版本 `2`、完整票据和 CRC32。临时
文件固定为同目录 `<bot-uuid>.death-v2.tmp`；写入使用文件 `force(true)`、原子替换、目标
文件刷盘和目录刷盘，首次创建目录时还要刷盘父目录。
读取使用 `NOFOLLOW_LINKS` 与固定上限，未知版本、损坏、超限、非普通文件或身份不匹配都
失败关闭。

marker 发布失败时禁止进入 `super.dropEquipment()` 的原版背包/装备掉落；该保证不覆盖
更早的模组回调或其他自定义掉落。相同票据允许幂等重入；已有不同事务 marker 时拒绝覆盖。
删除只接受精确 `botId + transactionId`，并在 marker 已缺失的幂等分支同样验证并刷盘目录。

### 3. 先提交规范死亡体，再清 tombstone

原版消费完成后，旧 body 在保存前被规范为：背包和 cursor 为空、生命与吸收为零、经验与
票据精确一致，并携带 `BotPlayerDeathHandoffV2` NBT。提交顺序固定为：

1. 证明当前 runtime、handle、generation、listener、`PlayerList` 与所有维度中只有这一个
   同 UUID 权威 body；
2. 通过一次性精确保存许可执行第一次 `PlayerList.save`；
3. 再次验权并执行第二次精确保存，使主副本和 `.dat_old` 都推进；
4. 对 `<uuid>.dat`、`<uuid>.dat_old` 与 `playerdata` 目录执行 durability barrier；
5. 以 4 MiB 上限回读两份 NBT，要求二者都是同一票据绑定的规范死亡体；
6. 再次验权后删除并刷盘 tombstone 目录。

步骤 1～5 或清票前的验权失败都不能清 marker、释放保存 fence 或开放重生。步骤 6 删除
marker 后若最终验权失败，不回造旧 marker，而是依靠已经耐久提交的 canonical-dead
playerdata handoff 失败关闭。精确保存许可只允许一次外层 `PlayerList.save` 到达序列化
入口；同步递归保存必须被抑制，不能继承省略 handoff 的权限。

### 4. 用 playerdata handoff 跨越 marker 清除后的崩溃窗口

外部 tombstone 清除后，主副本和 `.dat_old` 中的 `BotPlayerDeathHandoffV2` 仍是一次性、
可重放的权威记录。Bot 激活前检查两份 playerdata 中所有实际存在的副本；缺失副本在发现
阶段跳过，票据损坏、身份不符或主副本与备份冲突时失败关闭。存在 handoff 时先从输入 NBT
构造规范死亡副本，再交给原版加载，旧背包或旧经验不得短暂进入 ACTIVE body；后续正式
提交仍要求主副本和备份都存在并通过验证。

重生 successor 必须先应用精确经验快照，并在继承的保存 fence 内再次执行
`save × 2 → force files/directory → bounded readback`。两份 NBT 都必须证明 successor
存活、背包为空、经验精确且 handoff 已消失，才允许绑定新 generation 并进入 ACTIVE。

### 5. 旧 body 永久禁存，失败重试有界

所有提交都扫描 `PlayerList` 与全部维度的同 UUID body。predecessor 从身份拓扑移除后保留
永久 no-save poison；只有已验证的 authoritative successor 可以释放继承 fence。迟到的
旧 `die()`、保存或断线回调只能隔离旧对象，不能覆盖或拆除当前 successor。

死亡体和 successor 的 playerdata 提交都使用独立重试时钟：最多 4 次，名义重试间隔为
20 Tick；接近最长 256 Tick 的原有 retirement deadline 时，下一次机会向 deadline 收敛，
所以间隔可能缩短，也不保证总能获得四次机会。预算耗尽、权威漂移或 I/O 永久失败时：

- 保留当前阶段已经耐久的唯一证据：可能是 tombstone、canonical-dead playerdata handoff，
  或 successor 已完成 OMIT 双写后的 canonical-alive NBT；
- 所有已知同 UUID body 进入 no-save 隔离并卸载；
- 不继续每 Tick 写盘，也不让 successor 提前 ACTIVE；
- 下一次显式生成 Bot 时重新执行恢复协议。

正常伤害和 `kill()` 会在死亡入口前把生命降到零；公开直接调用 `die()` 不保证这一点。
因此只有在死亡 TAIL 已证明原版正常完成后，才把 body 的生命规范为零，再交给权威门检查；
完整背包、cursor、吸收和经验仍由上述持久化顺序收口。NeoForge 取消死亡不会到达该 TAIL。

## 被否决方案

### 只保存一次或只检查主 `.dat`

原版轮换可能让 `.dat_old` 保留死亡前背包，重启回退后产生复制。两份文件都必须存在、刷盘
并通过同一规范验证。

### 先清 marker，再异步保存 playerdata

这会重新打开“世界已有掉落、玩家仍有旧背包且没有恢复票据”的崩溃窗口。

### 只用游戏模式推断经验恢复

`force-gamemode`、回调内模式切换或模组经验事件会使该推断失真。精确经验必须属于不可变
票据和 NBT handoff。

### 重试直到成功

永久 I/O 故障会让服务器线程无限写盘。固定预算和 retirement deadline 比无限可用性重试
更符合失败关闭原则。

### 删除或解除旧 body 的保存 poison

旧对象仍可能被迟到回调持有；解除后可覆盖已经提交的 successor。永久 poison 只绑定旧
对象，不阻塞权威 successor 的独立提交。

## 兼容性、性能、安全与许可证影响

- 只覆写 BotPlayer 的原版死亡边界；真人玩家路径和原版文件名保持不变。
- 每次持久化尝试执行两次精确保存；死亡体与 successor 各最多 4 次尝试，即每阶段最多
  8 次、完整消费到重生最多 16 次保存及对应刷盘/回读。它不是普通 Tick 热路径，失败重试
  也有硬上限。
- marker 和 NBT 均固定版本、固定大小/字段与身份；符号链接、损坏、未知版本和跨事务清理
  一律拒绝。CRC32 只发现意外损坏，不提供防篡改认证，也没有多进程文件锁。
- 该协议优先防止物品复制。marker 已耐久发布、但掉落实体尚未被世界保存时崩溃，可能
  造成物品丢失；当前没有 world-level 掉落实体日志，因此不能宣称 exactly-once 交付。
- 实现只使用 JDK、Minecraft/NeoForge 现有 API 和项目自有代码，不引入新的第三方许可证。
- I/O 与刷盘同步发生在服务器线程；每份 playerdata 的读取上限为 4 MiB，大型模组 NBT
  可能被保守拒绝。
- `ATOMIC_MOVE` 与目录 `FileChannel.force(true)` 依赖目标平台支持；不支持或失败时按
  持久化失败关闭，不降级为“调用 save 即成功”。

## 迁移和回滚

- 当前只接受 V2 `.death-v2` marker 与 V2 `BotPlayerDeathHandoffV2`；未知、损坏或相互冲突
  的记录不会自动降级或覆盖。
- `generation` 与 `createdTick` 记录事务来源，不是跨进程 epoch、TTL 或新鲜度证明。
- 运维人员不得通过手工删除 marker、handoff、`.dat_old` 或保存 fence 来“解锁”Bot；这
  会丢失事务身份和防复制证据。应保留世界备份并通过后续受审计恢复工具处理。
- 回滚代码前必须先证明所有 Bot 不存在 tombstone/handoff，或提供能够完成 V2 事务的迁移
  工具。直接回滚到不识别 V2 的版本不是安全恢复方案。
- 本 ADR 不实现 roster autoload；恢复仍在下一次显式生成该 Bot 时触发。

## 验证方式

- 纯 Java 测试覆盖 marker 编解码/CRC/大小/精确事务清理、4 次/20 Tick 重试时钟、一次性
  保存许可的递归抑制，以及动作与布局消费回执。
- NeoForge GameTest 直接覆盖 `keepInventory=true` 的跨 Tick 布局收口和 unsafe 隔离、
  `keepInventory=false` 的真实物品/经验消费与主副本/备份空布局提交、死亡前旁观者的
  `PRESERVED` 分流、nested `die()` 只消费一次，以及旧 predecessor 迟到死亡/保存不能
  扰动 ACTIVE successor。恢复代码和文件级回读已经接线，但没有把单进程夹具描述成真实
  第二次服务器启动。
- [Build #163](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30897970406) 使用 Temurin
  Java 21 执行 `./gradlew --no-daemon clean build runGameTestServer`；Gradle `test`、
  91/91 NeoForge GameTest、clean build 与 JAR 上传均通过。
- 本证据不替代两次独立服务器启动、平台断电测试、独立专用服和多 Bot soak。
