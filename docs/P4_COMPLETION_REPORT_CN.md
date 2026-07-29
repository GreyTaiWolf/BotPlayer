# BotPlayer P4 完成验收报告

> 更新日期：2026-07-29
>
> 验收分支：`agent/p4-navigation-safety`
>
> 验收载体：[PR #5](https://github.com/GreyTaiWolf/BotPlayer/pull/5)
>
> 自动化状态：P4 生产代码与 55/55 GameTest 已由 Build #97 验证；P4 自动化退出门已通过
>
> 发布状态：开发候选，不是正式 Release

## 1. 结论

P4 已建立两个相互协调、但生命周期和状态独立的确定性闭环：

- L2 导航：不可变已加载世界快照、有界分段 A*、真实玩家输入 follower、动态重算、
  stuck 有限恢复、门/跳跃/水域/梯子，以及默认关闭的受限 Terrain Assist；
- L0 安全：每 Tick 读取真实 `ServerPlayer` 身体和有界近场，识别环境、弹射物、爆炸、
  敌对目标、生命、饥饿、空气、伤害和效果，并以 `SURVIVAL/EMERGENCY` 动作抢占导航。

Bot 仍然没有第二套生命、饥饿、护甲、效果、伤害或移动系统。位置变化来自普通玩家输入和
碰撞；伤害、药水/效果、属性和模组扩展落在真实 `BotServerPlayer` 身体上；Terrain Assist
只通过 P2 世界交互动作改变世界，并在每次成功后重新采样和规划。

## 2. 已交付生产能力

### 2.1 导航

| 能力 | 结果 | 边界 |
|---|---|---|
| `NavigationRequest/Goal/Policy` | 已实现 | 同维度、距离、资源、deadline、generation 全部显式 |
| 不可变运动快照 | 已实现 | 服务器线程分时读取；未知/未加载保持未知，不强制加载 |
| 有界分段 A* | 已实现 | 节点扩展、并发、队列、结果 inbox 和目标距离都有硬上限 |
| 真实输入 follower | 已实现 | 只签发短租约 look/move/jump/swim/climb/use；禁止传送和直接改速度 |
| 动态重算 | 已实现 | 方块 revision、动作后状态、偏航和未到达终点会使旧路线失效 |
| stuck 恢复 | 已实现 | 停止、重新对齐、重采样和重算次数有限；失败诚实终止 |
| 特殊移动 | 已实现基础场景 | 一格跳跃、木门、浅水横渡、梯子上行已有直接 GameTest |
| 低补给策略 | 已实现 | 低生命/食物拒绝普通远行；低食物不发 sprint |
| 管理命令 | 已实现 | `navigation go/stop/inspect` 固定要求权限等级 2 |

### 2.2 L0 安全

| 能力 | 结果 | 边界 |
|---|---|---|
| `SafetyFrame` | 已实现 | 每 Tick 有界读取生命、吸收、护甲、食物、空气、环境、效果、伤害和威胁 |
| 安全 incident FSM | 已实现 | STOP、对齐、逃生、验证、冷却、恢复/阻塞均有次数和 Tick 上限 |
| 抢占 | 已实现 | 危险关闭 bot 背包会话、挂起导航并抢占普通 MOVE/LOOK |
| 环境危险 | 已实现基础场景 | 悬崖、燃烧、低空气水下上浮已有直接 GameTest |
| 快速威胁 | 已实现基础场景 | 朝向 Bot 的箭与已点燃 TNT 触发闪避/撤离 |
| 敌对仇恨 | 已实现并验证 | 僵尸的原版 `target` 指向 Bot、真实近战伤害与撤退抢占已验证 |
| 伤害/效果 | 已实现并验证 | 原版伤害、护甲减免、效果/属性时效、动态 `DamageType` 和玩家 Tick fixture |
| 饥饿 | 已实现并验证 | 真实 sprint 跨过 exhaustion 阈值、低食物停跑、零食物饥饿伤害 |
| generation 隔离 | 已实现并验证 | 死亡关闭旧导航/安全状态；新身体只使用新 generation |

### 2.3 Terrain Assist

Terrain Assist 默认关闭。必须同时满足请求 policy 与服务器配置，且受单次预算限制。

- 破坏只允许短身体通道、精确方块指纹、合适工具和原版/P2 破坏入口；
- 拒绝容器、方块实体、流体、下落方块、不可破坏方块和未知模组方块；
- 搭桥只允许 `cobblestone/stone/dirt`，要求近端支撑、已知非流体深度和远端稳定锚点；
- 默认单次最多破坏 4 格、放置 4 格；策略硬上限为 8；
- 保护事件或世界结果不匹配时返回动作失败，不在拒绝后自动重试；
- 每次真实修改成功后丢弃旧路线，重新取快照和规划；
- 管理命令 `navigation go` 使用 `safeDefault()`，不会开启破坏或搭桥。显式辅助策略当前
  只通过服务 API 和 GameTest 使用，留给 P5 技能层做用户授权入口。

## 3. 玩家规则与模组兼容结论

P4 验证的是标准玩家接口兼容，不是“兼容所有模组”：

| 类型 | 当前结论 |
|---|---|
| 原版伤害、护甲和吸收 | 使用真实玩家最终结算；P4 只观察，不重复扣血 |
| 原版 `MobEffect` 和属性 | 添加、Tick、到期、移除和最终属性作用于真实身体 |
| 动态 `DamageType` | 非 `minecraft` ID 通过数据驱动注册表进入伤害事件和安全摘要 |
| NeoForge 玩家 Tick | GameTest-only fixture 的 `PlayerTickEvent.Post` 能修改并恢复 Bot 属性 |
| 未知伤害/有害效果 | 不获得特殊免疫；以权威生命变化/有害分类触发保守 incident |
| 专用模组语义 | 解药、装备选择、特殊维度规则和自定义 menu 仍属于 P8 适配 |

动态伤害与玩家 Tick fixture 位于独立 `gameTestFixtures` source set，不进入正式 JAR。

## 4. 自动化证据

[Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204) 对提交
`9fec0388c36870248a204d7ff21b1b663b62bebf` 执行：

```text
Temurin Java 21.0.11+10
./gradlew --no-daemon clean build runGameTestServer
55 GAME TESTS COMPLETE IN 10.30 s
All 55 required tests passed :)
BUILD SUCCESSFUL in 50s
```

同一运行还完成严格 `compileJava/compileTestJava`、Gradle `test`、clean build 和 JAR
上传。源码静态计数为 200 个 JUnit `@Test` 方法；这是源码计数，不是 CI 日志直接打印的
执行数。P4 直接 GameTest 为 28 个，全仓为 55 个。

上传构件：

- 名称：`botplayer-neoforge-1.21.1`
- artifact ID：`8721162398`
- ZIP 大小：`838883` bytes
- SHA-256：`b36a69f607e4f0e028e2afff15946a03bddd64004638c2d64d479c704706ddcd`

## 5. 直接 P4 GameTest 覆盖

导航与世界执行：

- 平地真实位移、封闭柱绕行、动态墙重算；
- 木门交互、一格真实跳跃、浅水、梯子；
- 完全封闭 `NO_PATH`、低补给拒绝；
- 显式双格通道挖掘、显式四格短桥、服务端 policy 拒绝；
- 死亡重生关闭旧 generation 导航和安全状态。

安全与玩家规则：

- 原版伤害与动态效果、效果自然到期；
- 护甲真实减伤；
- 低食物停止 sprint、真实 sprint exhaustion、零食物饥饿伤害；
- 悬崖抢占、燃烧逃离、低空气上浮；
- 僵尸目标与真实近战伤害；
- 来袭箭抢占、TNT 撤离；
- 动态 `DamageType`、标准 `PlayerTickEvent` 属性 Buff。

## 6. 明确保留的边界

以下内容没有被本报告冒充为已完成：

- 自动寻找、选择和食用食物；主动喝药、喝奶、选择模组解药；
- 完整反击、盾牌、弓弩、装备选择、团队战斗；
- 船、矿车、坐骑、鞘翅、跨维度传送门路线；
- 脚手架、藤蔓、复杂水流/深水出口、跑酷和任意建筑式搭桥；
- 容器/工作站自动化、制作、采集和长期技能恢复；
- 跨未加载区块的强制旅行、永久地图与 P7 地标记忆；
- 特定领地/PVP/反作弊模组兼容矩阵；
- 客户端组合、独立专用服、多 Bot 长时间 MSPT/内存 soak；
- 正式 Release 和重要存档生产承诺。

P4 证明的是确定性导航、安全反射和真实玩家规则兼容底座。把这些底座组合成“会生存、
会战斗、会采集”的任务闭环属于 P5A 以后。
