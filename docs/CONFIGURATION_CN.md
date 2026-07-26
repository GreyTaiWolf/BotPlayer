# BotPlayer 配置说明

本文只描述当前代码已经注册的配置。路线图中的 AI、记忆、动作、背包和性能配置尚不可用。

## 配置文件

BotPlayer 当前注册一个 NeoForge `SERVER` 配置，默认文件名：

```text
botplayer-server.toml
```

世界覆盖文件通常位于：

- 客户端单人世界：`.minecraft/saves/<world>/serverconfig/botplayer-server.toml`
- 专用服务器：`<server>/world/serverconfig/botplayer-server.toml`

NeoForge 默认从物理端配置目录加载：客户端为 `.minecraft/config`，专用服务器为
`<server>/config`。`SERVER` 类型还可以被当前世界的 `serverconfig` 文件覆盖，并同步给
客户端。服主也可以使用实例的 `defaultconfigs` 机制为新世界预置文件；它不替代
`<server>/config` 的基线来源。最终以启动日志和当前世界覆盖文件为准。

建议停止服务器后编辑，再重新启动。不要依赖开发阶段的热重载行为。

## 当前配置项

| TOML 键 | 类型 | 默认值 | 范围 | 当前作用 |
|---|---|---:|---:|---|
| `server_player.maxBots` | 整数 | `8` | `1..128` | 本次服务器运行期允许的最大在线 bot 数 |
| `server_player.showInPlayerList` | 布尔 | `true` | `true/false` | `BotServerPlayer.allowsListing()` 是否允许显示在 Tab |
| `server_player.autoRespawn` | 布尔 | `true` | `true/false` | 死亡后是否请求原版重生 |
| `server_player.respawnDelayTicks` | 整数 | `20` | `0..1200` | 自动重生等待 Tick；正常 20 TPS 时 20 Tick 约 1 秒 |
| `server_player.chunkTrackingRefreshTicks` | 整数 | `10` | `1..200` | 刷新连接位置和玩家区块跟踪的间隔 |
| `permissions.commandPermissionLevel` | 整数 | `2` | `0..4` | 使用所有 `/botplayer` 子命令需要的原版权限等级 |

## 当前 TOML 示例

```toml
[server_player]
maxBots = 8
showInPlayerList = true
autoRespawn = true
respawnDelayTicks = 20
chunkTrackingRefreshTicks = 10

[permissions]
commandPermissionLevel = 2
```

键名大小写必须与生成文件一致。

## 使用建议

### `maxBots`

这只是在线数量硬上限，不代表服务器已经通过 128 bot 的性能验证。当前没有多 bot soak、
动作、感知或 AI 压力测试，开发测试建议保持较小数量。

### `showInPlayerList`

关闭后只影响 `allowsListing()`；它不会把 bot 从 `PlayerList` 或世界中移除，也不是隐身、
权限或反作弊绕过功能。

### `autoRespawn`

关闭时，死亡 bot 保持 `DEAD`，当前没有手工重生子命令。重新打开配置后，管理器在后续
Tick 可以重新安排重生，但动态配置更改还没有专门 GameTest。

### `respawnDelayTicks`

`0` 表示达到管理器 Tick 时尽快请求重生，并不保证在死亡调用栈内部立即重生。

### `chunkTrackingRefreshTicks`

较小值会增加服务端工作量。它用于保持无物理客户端玩家的区块跟踪位置，不是独立的强制
区块加载器，也不保证 bot 离线后继续加载区块。

### `commandPermissionLevel`

原版等级大致为：

| 等级 | 常见含义 |
|---:|---|
| 0 | 所有人 |
| 1 | 绕过生成保护等有限权限 |
| 2 | 常用管理命令，当前默认 |
| 3 | 玩家管理 |
| 4 | 最高管理权限 |

生产服务器不要为了方便将它设为 `0`。owner/ACL 尚未实现，当前一个权限值控制所有
`spawn`、`list` 和 `remove`。

## 尚不存在的配置

下列内容只在架构路线图中设计，当前 TOML 中不存在：

- DeepSeek provider、模型、API URL、超时和预算；
- API Key 或 `credentialId`；
- owner、trusted、observer 和动作 ACL；
- 挖掘、放置、PVP、搭桥和高风险确认；
- 感知距离、路径节点和 Tick 预算；
- 背包打开距离和写锁；
- 长期记忆、聊天保存和数据保留；
- 自动加载 roster 和每 bot 独立策略。

不要自行添加这些键并期待生效。

## API Key 规则

DeepSeek 进入 P6 后，Key 也不能写入这个 server 配置。允许方式将是服务端环境变量、
容器 secret 或操作系统密钥管理；配置最多保存不含 secret 的 `credentialId`。

禁止把 Key 放入：

- 聊天或 `/botplayer` 命令；
- `botplayer-server.toml`；
- 客户端配置；
- 世界 NBT、SavedData 或 playerdata；
- 日志、崩溃报告、Issue 和截图。

如果 Key 已经泄漏，应立即在提供商后台撤销并创建新 Key，不能只删除聊天或日志。

## 配置变更规则

开发者新增或修改配置时必须同时更新：

1. `BotPlayerConfig` 的类型、默认值、范围和注释；
2. 本文的配置表和示例；
3. README 或安装文档中的相关行为；
4. `CHANGELOG.md`；
5. 配置加载、边界值和迁移测试。

配置文件位置与 `SERVER` 类型规则可参考
[NeoForge 1.21.1 Configuration 文档](https://docs.neoforged.net/docs/1.21.1/misc/config)。
