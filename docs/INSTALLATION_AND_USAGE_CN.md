# BotPlayer 安装与当前用法

> 适用版本：`0.2.0-alpha.1` P5 Build #137 开发构件
>
> Minecraft：`1.21.1`
>
> NeoForge：`21.1.244`
>
> Java：`21`

当前没有正式 Release。`0.2.0-alpha.1` 已通过 Java 21 自动化构建与 55/55 GameTest，但客户端
手工、独立专用服和多 bot soak 仍未验证；本文用于开发测试，不建议在重要世界中安装。
P5 以 [Draft PR #6](https://github.com/GreyTaiWolf/BotPlayer/pull/6) 作为远端验收载体；
[Build #137](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30713366812) 已通过 Java 21
`clean build`、Gradle `test`、83/83 GameTest 与 JAR 上传。源码静态计数为 380 个 JUnit
`@Test` 方法、26 个 P5 GameTest、353 个 Java 源文件，不是 CI 日志逐项执行数。

## 当前安装拓扑

当前规定的开发测试拓扑是双端安装：

- 集成服务器：安装在游戏客户端的 `mods` 目录；
- 专用服务器：服务端与加入测试的客户端安装同一 JAR；
- 本轮背包 screen 已由用户在真实客户端确认视觉修复有效；专用服务器仍未手工验收；
- 不能把当前构件宣传成已经验证的“纯服务端模组”。

P2 的 bot 自身背包界面需要客户端 screen。API Key 只在 owner 客户端本地保存，
未来使用它的 Provider HTTP 也在该客户端执行；世界判断、owner/ACL、计划接受和动作权威
始终在服务端。

## 获取开发构件

### 从源码构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
./gradlew --no-daemon clean build
```

开发基线是仓库默认 `main`。功能分支从最新 `main` 创建，不再使用已经合并的 P0/P1
审查分支。

Windows：

```bat
gradlew.bat --no-daemon clean build
```

构建成功后，JAR 位于：

```text
build/libs/
```

### 从 GitHub Actions 获取

1. 打开仓库的 **Actions**；
2. 选择目标分支上成功的 **Build**；
3. 在该运行的 **Artifacts** 下载 `botplayer-neoforge-1.21.1`；
4. 解压得到 JAR；
5. 只使用与目标 commit 对应的客户端和服务端构件。

开发构件没有正式版本兼容保证；更新前先备份世界。

## 安装

1. 安装 Minecraft 1.21.1 对应的 NeoForge；
2. 确认运行 Java 21；
3. 停止游戏或服务器；
4. 将 BotPlayer JAR 放入客户端和服务端的 `mods/`；
5. 启动并检查日志中是否加载 `botplayer`；
6. 进入测试世界后使用 `/botplayer` 命令。

首次启动后，NeoForge 会为世界创建 server 配置。具体位置和字段见
[CONFIGURATION_CN.md](CONFIGURATION_CN.md)。

## 当前命令

`spawn`、`list`、`remove` 默认要求原版权限等级 `2`，可通过
`permissions.commandPermissionLevel` 调整；P3 `perception`、P4 `navigation/safety`
与 P5 `skill` 管理命令固定要求等级 `2`，不随该配置降级。`settings` 使用精确 owner
校验，不要求 OP。

### 生成

```text
/botplayer spawn <name>
```

规则：

- 名称只能是 1–16 位 ASCII 字母、数字或下划线；
- 在线玩家和 bot 名称冲突时拒绝，不区分大小写；
- 首次生成的新身份出现在命令执行者的位置和维度；
- 如果同一名字派生的 UUID 已有原版 playerdata，则保留其中保存的位置；
- 首次由真实玩家生成时，该玩家 UUID 持久记录为 owner；控制台、命令方块或 bot 自己生成
  的 profile 没有 owner；
- 同名 profile 以后重新生成时复用规范名字和原 owner，不会被新的命令执行者夺取；
- roster 会持久保存 bot/player 身份、owner 和服务器实例 ID；
- 当前还没有 autoload，服务器重启后需要再次执行同名 `spawn`；
- 控制台生成的位置语义尚未作为正式场景验收，优先在游戏中由玩家执行。

“真实服务端玩家”表示实例和生命周期使用 `ServerPlayer`，不表示它登录了一个真实
Microsoft/Mojang 账号，也不存在远程游戏客户端。

### 列表

```text
/botplayer list
```

只列出本次服务器运行期间由 `BotLifecycleManager` 管理的在线 bot，并显示：

```text
name [spawning|active|dead|respawning|despawning]
```

它不会把离线 roster 条目加入在线列表。

### 移除

```text
/botplayer remove <name>
```

该命令只卸载当前在线 bot：

- 调用原版断开/移除路径，预期由原版保存 playerdata，但尚无 GameTest 验证；
- 不删除原版 `playerdata/<uuid>.dat`；
- 不删除 roster 身份、owner、客户端本地绑定或未来记忆；
- 目前没有永久删除 bot 的命令。

移除活动 bot 时，服务端当前 agent binding 会清除；客户端本地 binding 和 credential
profile 不会自动删除。
P5 的异常隔离 teardown 另有 `PlayerListMixin` 一次性 no-save 包装，只在布局无法安全
落盘的隔离路径抑制那一次 `PlayerList.remove` 内部保存；正常 `/botplayer remove` 和
真人玩家不走该门闩。该异常路径已编码但尚未完成 NeoForge 运行验证。

### 配置客户端凭据

```text
/botplayer settings <name>
```

规则与步骤：

1. 目标 bot 必须当前在线；
2. 执行者必须是真实玩家，并且 UUID 精确等于 roster 中持久 owner；OP 也不能配置别人的
   bot，无 owner bot 当前不能认领；
3. 服务端只发送 `serverInstanceId`、botId、规范名字和可选 active agentId，随后打开本地
   Screen；payload 不包含 Key、profile ID 或 Key 派生信息；
4. profile ID 默认是 `default`，可使用 1–64 位允许字符创建其他本地 profile；
5. 输入 8–512 位、无空白的 Key 后选择“保存并绑定”；Key 输入框显示掩码，打开界面时不会
   从文件回填；
6. 已有 profile 的 Key 输入留空会复用它；输入新 Key 会替换该共享 profile，所有引用它的
   本地 bot 都会使用新值；
7. “解绑”会删除当前 bot 的本地 binding 并请求服务器清除运行时 agentId，但不会删除
   credential profile 或 Key。当前没有 profile 删除功能。

本地文件（`<client-game-dir>` 是当前客户端游戏目录，默认启动目录通常是 `.minecraft`）：

```text
<client-game-dir>/config/botplayer/credentials-v1.json  # 明文 Key
<client-game-dir>/config/botplayer/bindings-v1.json     # 非 secret 绑定
```

一个 profile 可以供多个 bot 使用，但每个 bot 都有独立 agentId。绑定键包含
`serverInstanceId`、owner UUID 和 botId，避免不同服务器/owner 串用。owner 退出、bot 卸载
或停服后，服务端 active binding 会清除；本地 binding 保留，下次 bot 在线后重新打开界面
并保存/绑定即可。

这两个文件采用严格 schema，优先原子替换（不支持时退化为同目录覆盖）并尽力收紧文件
权限，但仍是本机明文。不要把它们上传到 Issue、支持包、云盘或 Git。文件损坏或 schema
不支持时，客户端会拒绝加载和覆盖。

### 检查 P3 感知

```text
/botplayer perception inspect <name>
```

目标必须是活动 bot 且已经产生最新快照。命令只输出有界摘要，包括 snapshot/tick/维度、
MSPT 压力、生命/饥饿、视线类型、实体/威胁/方块/事件数量、本 bot 分类预算、最多 4 个
活动假设及最多 5 条最近事实。它不会输出 authority session/seq、全局 world revision、
全服预算、完整权威事件、任意容器内容或长期记忆。

P3 Build #28 已通过自动化退出门，包括定向声音目标 generation 隔离与已感知方块事实
失效两个直接 GameTest。该命令能返回内容仍不代表所有异常路径、独立专用服或多 bot
压力已经逐项验证。

### 纠正 P3 活动假设

```text
/botplayer perception correct <bot> <actor> <activity>
```

`bot` 必须活动，`actor` 必须是在线玩家。`activity` 允许：

```text
idle moving exploring mining building combat farming crafting smelting none
```

纠正会作为新的 `PLAYER_CORRECTION` 证据事件追加，不删除历史事件。`none` 表示没有这些
可识别活动。这个入口用于管理/测试，不是 owner 聊天接口，也不会修改玩家实际动作或世界。

### P4 导航管理入口

```text
/botplayer navigation go <name> <x> <y> <z>
/botplayer navigation stop <name>
/botplayer navigation inspect <name>
```

三个命令固定要求原版权限等级 `2`。`go` 只接受当前维度的整数方块目标，并使用
`NavigationPolicy.safeDefault()`：

- 只读取已加载区块，未知区域不会被当成空气，也不会主动强制加载；
- 通过短 look/move/jump/swim/climb/use 动作移动真实玩家身体；
- 低生命/食物、错误维度、超距离、无路、预算、过载或 generation 变化会返回结构化失败；
- 遇到 L0 危险会挂起，稳定安全后从真实位置重算；
- 默认不会挖方块或搭桥。Terrain Assist 的显式请求 policy 当前只供服务 API/GameTest，
  尚没有普通用户授权命令。

`inspect` 输出 session 状态、路线节点、segment、replan、recovery、破坏/放置计数与安全
摘要。它是管理诊断，不是聊天或长期任务入口。

### P4 安全诊断

```text
/botplayer safety inspect <name>
```

固定要求权限等级 `2`。命令显示当前 incident，以及真实身体的生命/吸收、食物、空气、
效果数量、近场威胁与最近伤害类型。L0 每 Tick 运行并可抢占普通输入；它能停止、后退、
走向安全邻格、上浮和规避箭/TNT/敌对目标。P4 L0 本身不会吃东西；P5 开发切片可以接收
临界饥饿 handoff，并尝试真实食用背包中的安全原版基础食物。P5 还提供扫描 carried
inventory `0..35` 的基础盔甲升级入口；主动进食，以及热栏和主背包 2～3 步路径已由
Build #137 运行验证。主动用药、工具/副手选择、持盾和反击仍未实现。

### P5 生存技能管理与诊断

```text
/botplayer skill equip-armor <name>
/botplayer skill inspect <name>
```

固定要求原版权限等级 `2`。`equip-armor` 手动启动扫描 carried inventory `0..35` 的
基础盔甲升级；候选按头、胸、腿、脚固定顺序比较原版防御、韧性和剩余耐久，并拒绝绑定
诅咒。热栏候选使用一次原生 `SWAP`；主背包候选通过确定性临时热栏槽形成 2～3 步计划，
并保持独立盔甲路径。底层通用 `InventoryMenu SWAP_SEQUENCE` 可执行 1～16 次点击、最多
8 个槽位，每 Tick 一击；跨 Tick cleanup 保持 `PENDING` 和固定端点，非端点时旧 owner/
新 claimant 都不会完成。通用 equipment/offhand 仍 `UNSUPPORTED`，因此当前不选择工具/
副手，也不提供盾牌格挡。`inspect` 读取活动 bot 当前
或最近一条 P5 生存技能 run 的 generation、状态、revision、操作序号、失败码和安全摘要。
没有 P5 运行记录时会失败；该纵切通过运行门也不表示 P5A 阶段退出门已经完成。

### 查看/编辑 bot 自身背包（P2）

条件：

- viewer 是 roster 中持久 owner 或服务器 OP；
- viewer 与活动 bot 同维度且双方存活；
- 距离不超过 `inventory.viewDistance`，默认 `8.0` 方块；
- 使用主手，主手必须为空；副手和持物品不会打开；
- 同一 bot 当前没有另一个写 viewer，且该 viewer 没有其他 bot 背包会话。

满足条件后，用空主手的主手交互右键 bot。GUI 展示 bot 的 41 格真实玩家库存（盔甲 4、
副手 1、主背包 27、快捷栏 9）和 viewer 自己的 36 格库存。超距、死亡、重生、换维度、
退出、menu 替换或停服会关闭会话。打开期间动作运行时不能并发写 bot 库存。

界面采用 `176×256` 的完整原版玩家背包风格：bot 区在上方，包含盔甲、副手、3D bot
模型、主背包、快捷栏和当前快捷栏高亮；viewer 的主背包与快捷栏在下方。原版 2×2 合成
区域被隐藏且没有对应 menu slot，因此不可交互。背景、槽位和高亮在运行时引用 Minecraft
1.21.1 原版 GUI/HUD 资源，没有随模组复制 Mojang PNG，并会跟随替换这些资源的资源包。

该画布需要至少 256 个逻辑 GUI 像素的垂直空间。如果界面在较小窗口或较高 GUI Scale 下
超出屏幕，请在“选项 → 视频设置”中调低“界面尺寸”。用户已在真实客户端确认本轮
视觉修复有效；目前仍不要据此宣称多语言、所有 GUI Scale 或资源包组合已经验证通过。

这不是通用世界容器功能：箱子/木桶/潜影盒和工作站延期到 P5A/P5B，模组自定义 menu
属于 P8。客户端 screen 和 GameTest 的最终验证状态见
[P2 完成报告](P2_COMPLETION_REPORT_CN.md)。

## 当前能观察到的行为

- bot 使用标准玩家模型和名称；
- 默认显示在 Tab 玩家列表；
- bot 死亡后默认等待 20 Tick，再请求原版重生；
- bot 没有物理客户端；专用 listener 在丢弃客户端包前只提取受支持的定向声音 DTO，
  其余包仍按既有有界诊断路径处理；
- 在线时会刷新原版玩家区块跟踪。
- roster 保存稳定身份、持久 owner 和服务器实例 ID；
- P2 具有 generation 隔离、确定性动作、短程输入、基础世界交互和 bot 自身背包；
- P3 会生成有限、不可变的自身/背包/注视/局部实体/方块/声音快照，并以有证据的
  置信表达推断近期玩家活动；
- P4 可以在已加载世界中进行有界分段导航、动态重算和基础门/跳跃/水域/梯子移动；
- P4 L0 会观察真实生命、饥饿、空气、伤害/效果和近场威胁，并对悬崖、燃烧、溺水、
  来袭箭、TNT 和锁定 Bot 的敌对生物做通用抢占/撤退；
- 原版护甲、伤害、饥饿和状态效果，以及标准动态 `DamageType`/玩家 Tick 扩展，都会
  作用在真实 `BotServerPlayer` 身体上；
- P5 有界 Skill 底座、主动进食、独立基础盔甲路径和通用 `InventoryMenu SWAP_SEQUENCE`
  已由 PR #6 的 Build #137 通过 Java 21 `clean build`、Gradle `test`、83/83 GameTest
  与 JAR 上传；
- owner 客户端可以在本地 GUI 创建/替换 credential profile，并为自己的多个 bot
  绑定/解绑；每个 bot 使用独立 agentId。

这些仍是开发阶段行为。P4 的 [Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204)
使用 Temurin Java 21.0.11 完成严格编译、Gradle `test`、55/55 GameTest、clean build
与 JAR upload，其中 P4 直接场景为 28 个。客户端手工、长时间在线、独立专用服、跨维度
完整矩阵和多 Bot soak 尚无保证。请不要据此假定保护模组、所有维度或大型模组包已经兼容。

Build #137 的 25 个 GameTest batch 静态 Bot 预算均不超过默认 8；Build #133/#135 暴露的
超配已通过拆批修复，没有提高 `server_player.maxBots`。这仍不验证两次服务器启动、独立
专用服或多 Bot soak。

## 当前不能做

当前 bot 不会：

- 通过普通玩家任务、技能或 AI 自主选择并执行 P2/P4 动作；
- 强制加载远方区块、跨维度寻路或维护永久地图/地标；
- 自动寻找或生产食物；主动进食，以及热栏和主背包 2～3 步换甲路径已运行验证；主动
  使用药水/牛奶/模组解药、选择工具/副手或完成正式战斗仍不支持；
- 执行砍树、采矿、制作、熔炼、完整战斗策略或建造技能；
- 操作箱子、工作站或模组自定义 menu；
- 把 `InventoryMenu` 序列扩展为跨 menu 统一事务；当前还没有 `clicked()` 故障注入、
  生命周期 `PENDING` continuation、TaskSensor/Reservation 生产接线、Checkpoint 或
  craft/chest/furnace/DAG；
- 聊天、连接 DeepSeek 或发起任何模型 HTTP 请求；
- 测试 Key 是否有效，或使用已保存 Key 进行规划；
- 通过聊天回答附近事件或自主使用活动理解；P3 当前只有管理诊断候选；
- 保存长期目标、记忆或技能；
- 自动理解其他模组。

P5A 总验证还缺同一持久状态的两次服务器启动、独立专用服和多 Bot soak。

P2 提供可信身体，P3 提供有限运行时认知，P4 提供确定性导航与通用避险；以上技能和
高层功能仍必须按 P5–P10 实现和验证。P3/P4 不读取箱子、工作站或模组 menu 内容。

## 卸载与备份

当前是开发版本。停服后移除 JAR 前：

1. 先执行 `/botplayer remove <name>` 卸载在线 bot；
2. 正常停止服务器；
3. 备份整个世界，尤其是 `playerdata/`；
4. 再移除 JAR。

不要手工删除未知 UUID 的 playerdata。当前还没有安全的 bot 永久删除和身份迁移工具。
世界备份不包含客户端凭据文件；如需保留本地 binding，应单独、私密地备份客户端游戏目录
下的 `config/botplayer/`，并理解其中 `credentials-v1.json` 是明文。

## 常见问题

### 命令不可用

- 检查模组是否加载；
- 检查 Minecraft、NeoForge 和 Java 版本；
- 检查执行者是否达到对应权限等级：`spawn/list/remove` 使用配置值，`perception`、
  `navigation/safety` 与 `skill` 固定要求等级 2；
- 查看服务端日志中的 Mixin 或模组加载错误。

### 名称被拒绝

名称必须匹配：

```regex
[A-Za-z0-9_]{1,16}
```

同名真人或 bot 已在线时也会拒绝。

### 达到 bot 数量上限

默认最多同时在线 8 个 bot。修改
`server_player.maxBots` 后，在服务器停止状态下重新启动测试。

### 为什么保存 Key 后 bot 仍然不会聊天或工作

当前只实现客户端本地 credential profile 和 bot binding。没有 DeepSeek Provider、HTTP、
对话、规划、Tool Firewall 或动作身体。凭据基础不代表 P6 完成，也不能验证 Key 是否有效。
原始 Key 不能通过 `/botplayer` 命令或聊天输入。

### 为什么无法打开凭据界面

- bot 必须当前在线；
- 只有首次创建 profile 时持久记录的 owner 可以打开；
- 控制台或命令方块首次创建的无 owner bot 当前不能认领；
- `/botplayer settings` 不接受 Key，只负责服务端 owner 校验和打开本地 Screen；
- 本地凭据文件损坏时客户端会拒绝覆盖，并显示错误。

### 为什么不能打开背包

空手主手右键背包属于 P2。它需要服务端 menu、客户端 screen、距离/权限校验和单查看者
写锁，不能只打开一个不安全的临时容器。还需确认客户端与服务端安装同一 JAR；如果
`176×256` 界面超出屏幕，应降低 GUI Scale。用户已在真实客户端确认本轮视觉修复有效；
多语言、资源包与全部 GUI Scale 组合仍未专项验证。

### 为什么重启后 bot 没有自动回来

当前已有持久 roster，但没有 autoload。服务器重启后仍需再次执行 `spawn`，管理器会解析
已有 roster 身份并继续使用对应 playerdata。当前没有正式重命名命令和完整迁移测试，
不要通过换名字尝试迁移身份。

## 反馈问题时提供

- BotPlayer commit；
- Minecraft、NeoForge、Java 版本；
- 集成服务器还是专用服务器；
- 最小复现步骤；
- 已脱敏日志；
- 是否安装其他模组。

不要上传 API Key、Authorization header、世界私聊或含私人信息的完整存档。
