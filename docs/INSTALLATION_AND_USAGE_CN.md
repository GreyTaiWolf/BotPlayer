# BotPlayer 安装与当前用法

> 适用版本：`0.1.0-alpha.1` 开发分支
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

未来 P2 背包界面明确需要客户端 screen；AI、密钥、世界判断和动作权威逻辑始终只在服务端。

## 获取开发构件

### 从源码构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
git switch agent/p0-p1-server-player-kernel
./gradlew --no-daemon clean build
```

当前实现仍在该审查分支；对应 PR 合并后应直接使用仓库默认 `main`，不再切换这个临时
分支。

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

默认要求原版权限等级 `2`。

### 生成

```text
/botplayer spawn <name>
```

规则：

- 名称只能是 1–16 位 ASCII 字母、数字或下划线；
- 在线玩家和 bot 名称冲突时拒绝，不区分大小写；
- 首次生成的新身份出现在命令执行者的位置和维度；
- 如果同一名字派生的 UUID 已有原版 playerdata，则保留其中保存的位置；
- 当前还没有 roster，服务器重启后需要再次执行同名 `spawn`；
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

它不会搜索离线 playerdata，也不会列出尚未实现的持久 roster。

### 移除

```text
/botplayer remove <name>
```

该命令只卸载当前在线 bot：

- 调用原版断开/移除路径，预期由原版保存 playerdata，但尚无 GameTest 验证；
- 不删除原版 `playerdata/<uuid>.dat`；
- 不删除未来的记忆或业务数据；
- 目前没有永久删除 bot 的命令。

## 当前能观察到的行为

- bot 使用标准玩家模型和名称；
- 默认显示在 Tab 玩家列表；
- bot 死亡后默认等待 20 Tick，再请求原版重生；
- bot 没有物理客户端，服务端发给它的客户端包会被专用 listener 丢弃；
- 在线时会刷新原版玩家区块跟踪。

这些是开发内核行为，还没有完整 GameTest。当前只适合短时内核测试；长时间在线、
keepalive、传送确认、跨维度和连接超时尚无自动化保证。请不要据此假定保护模组、所有
维度或大型模组包已经兼容。

## 当前不能做

当前 bot 不会：

- 自主走路、跟随、寻路或加载远方任务路线；
- 挖矿、砍树、放置、制作、战斗或建造；
- 聊天或连接 DeepSeek；
- 接收、测试或保存 API Key；
- 空手右键打开背包；
- 感知附近事件、理解玩家活动；
- 保存长期目标、记忆或技能；
- 自动理解其他模组。

以上功能已经设计，但必须按 P2–P8 顺序实现和验证。

## 卸载与备份

当前是开发版本。停服后移除 JAR 前：

1. 先执行 `/botplayer remove <name>` 卸载在线 bot；
2. 正常停止服务器；
3. 备份整个世界，尤其是 `playerdata/`；
4. 再移除 JAR。

不要手工删除未知 UUID 的 playerdata。当前还没有安全的 bot 永久删除和身份迁移工具。

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

### 为什么不能配置 DeepSeek Key

DeepSeek 计划在 P6 接入。当前代码完全没有 provider、Key 输入或聊天功能；任何声称当前
可以通过命令设置 Key 的说明都是错误的。

### 为什么不能打开背包

空手主手右键背包属于 P2。它需要服务端 menu、客户端 screen、距离/权限校验和单查看者
写锁，不能只打开一个不安全的临时容器。

### 为什么重启后 bot 没有自动回来

当前没有 roster 和 autoload。用相同名称重新执行 `spawn` 可以得到相同的临时名字派生
UUID，并尝试读取对应原版 playerdata。UUID 使用名称的小写形式派生，所以只改变字母
大小写仍指向同一临时身份，其他改名会产生不同身份。当前没有重命名约束或迁移工具，
不要把任何改名方式当作受支持的管理操作。

## 反馈问题时提供

- BotPlayer commit；
- Minecraft、NeoForge、Java 版本；
- 集成服务器还是专用服务器；
- 最小复现步骤；
- 已脱敏日志；
- 是否安装其他模组。

不要上传 API Key、Authorization header、世界私聊或含私人信息的完整存档。
