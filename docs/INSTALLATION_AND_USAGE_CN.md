# BotPlayer 安装与当前用法

> 适用版本：`0.1.0-alpha.2` P2 验收分支
>
> Minecraft：`1.21.1`
>
> NeoForge：`21.1.244`
>
> Java：`21`

当前没有正式 Release。本文用于开发测试，不建议在重要世界中安装。

## 当前安装拓扑

当前规定的开发测试拓扑是双端安装：

- 集成服务器：安装在游戏客户端的 `mods` 目录；
- 专用服务器：服务端与加入测试的客户端安装同一 JAR；
- 客户端与专用服务器行为尚无自动验收；
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

`spawn`、`list` 和 `remove` 默认要求原版权限等级 `2`；`settings` 改为精确 owner
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

这不是通用世界容器功能：箱子/木桶/潜影盒和工作站延期到 P5A/P5B，模组自定义 menu
属于 P8。客户端 screen 和 GameTest 的最终验证状态见
[P2 完成报告](P2_COMPLETION_REPORT_CN.md)。

## 当前能观察到的行为

- bot 使用标准玩家模型和名称；
- 默认显示在 Tab 玩家列表；
- bot 死亡后默认等待 20 Tick，再请求原版重生；
- bot 没有物理客户端，服务端发给它的客户端包会被专用 listener 丢弃；
- 在线时会刷新原版玩家区块跟踪。
- roster 保存稳定身份、持久 owner 和服务器实例 ID；
- P2 具有 generation 隔离、确定性动作、短程输入、基础世界交互和 bot 自身背包；
- owner 客户端可以在本地 GUI 创建/替换 credential profile，并为自己的多个 bot
  绑定/解绑；每个 bot 使用独立 agentId。

这些仍是开发阶段行为。P2 本地已通过 140/140 单测和连续两轮 19/19 GameTest；客户端
手工、长时间在线、独立专用服、跨维度完整矩阵和多 bot soak 尚无保证。请不要据此假定
保护模组、所有维度或大型模组包已经兼容。

## 当前不能做

当前 bot 不会：

- 通过用户命令、技能或 AI 自主选择并执行 P2 动作；
- 跟随、长距离寻路、动态重规划或加载远方任务路线；
- 执行砍树、采矿、制作、熔炼、完整战斗策略或建造技能；
- 操作箱子、工作站或模组自定义 menu；
- 聊天、连接 DeepSeek 或发起任何模型 HTTP 请求；
- 测试 Key 是否有效，或使用已保存 Key 进行规划；
- 感知附近事件、理解玩家活动；
- 保存长期目标、记忆或技能；
- 自动理解其他模组。

P2 只提供可信身体；以上高层功能必须按 P3–P10 实现和验证。

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
- 检查执行者是否达到配置的权限等级；
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
写锁，不能只打开一个不安全的临时容器。

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
