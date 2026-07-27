# BotPlayer

[![Build](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/GreyTaiWolf/BotPlayer/actions/workflows/build.yml)

BotPlayer 是面向 Minecraft Java 的真实服务端玩家 AI 框架。项目首先支持
Minecraft 1.21.1 + NeoForge，后续版本在 1.21.1 架构稳定后再迁移。

> **当前状态：早期开发内核，不是可用于重要存档的正式版本。**
>
> 现在已经可以生成、列出和移除一个真实的 `BotServerPlayer`，并有持久 roster/owner 与
> 客户端本地 API Key 管理基础；但它还没有移动、采集、聊天、DeepSeek、记忆和背包 GUI
> 等完整能力。保存 Key 不代表 AI 已经接通。请以
> [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md) 为准，不要把路线图中的目标当成已完成。

## 设计目标

BotPlayer 最终要成为由 AI 控制的长期服务器伙伴，而不是换皮生物或只会执行命令的 NPC：

- 主体直接继承原版 `ServerPlayer`，不注册自定义玩家实体；
- 使用原版玩家背包、装备、生命、饥饿、经验、死亡、重生、维度和 playerdata；
- 真人客户端看到标准玩家模型、动作、装备和名称；
- DeepSeek 负责聊天、意图理解和高层规划；
- Java 动作与技能系统负责真正移动、挖掘、放置、战斗、制作和验证结果；
- 通过感知、世界模型和记忆理解玩家正在做什么、附近发生了什么、以前发生过什么；
- 通过内部 Java 技能和受审核的外部声明式技能包逐步理解原版及模组玩法；
- 所有动作服从服务器权限、保护事件、安全策略和资源预算。

完整定义见
[架构、编码规范与 P0–P10 路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## 当前技术基线

| 项目 | 当前值 |
|---|---|
| Minecraft | `1.21.1` |
| NeoForge | `21.1.244` |
| Java | `21` |
| ModDevGradle | `2.0.142` |
| Gradle Wrapper | `9.2.1` |
| 模组 ID | `botplayer` |
| 开发版本 | `0.1.0-alpha.1` |
| 发布状态 | 尚未发布，仅开发构件 |

版本号是开发标识，不代表 P1/P2 已全部验收。

## 已经实现

- `BotServerPlayer extends ServerPlayer`；
- 本地 `EmbeddedChannel` 虚拟连接；
- bot 专用 `BotGamePacketListener`；
- 登录时替换 packet listener 的窄 Mixin；
- 重生时保持 `BotServerPlayer` 类型的窄 Mixin；
- 只在原版死亡真正完成后进入重生流程；
- 临时、确定性的名字派生 UUID；
- schema v1 持久 roster、规范名字、稳定 bot/player UUID、owner 和服务器实例 ID；
- 只有持久 owner 可进入客户端凭据配置；
- `/botplayer credentials <name>` 打开客户端本地 API Key GUI；
- 客户端可创建/替换凭据 profile、绑定/解绑 bot；每 bot 使用独立 agentId，profile 删除
  尚未实现；
- 既有 playerdata 检测与保存位置保留；
- 服务器线程生命周期管理；
- 自动重生、维度切换基础路径和区块跟踪刷新；
- 生成失败回滚、幂等断开和停服异常隔离清理；
- `/botplayer spawn|remove|list`；
- NeoForge server 配置；
- GitHub Actions Java 21 完整构建。

## 尚未实现

- 自动恢复、trusted/observer ACL 与完整数据迁移；
- 生命周期 GameTest；
- 空手主手右键打开 bot 背包；
- 玩家输入、移动、挖掘、放置、攻击和容器动作；
- 感知、世界事件、玩家活动理解和世界模型；
- 寻路、安全反射、战斗、建造和生存技能；
- DeepSeek Provider/HTTP、聊天、工具防火墙和预算；
- 分层长期记忆、目标恢复和模组适配；
- 多 bot 协作与正式发布级性能验证。

## 快速开发验证

### 前置条件

- 64 位 JDK 21；
- Git；
- 能下载 Gradle、NeoForge 和 Maven 依赖的网络环境。

### 构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
./gradlew --no-daemon clean build
```

Windows PowerShell/CMD 使用：

```bat
gradlew.bat --no-daemon clean build
```

构件位于 `build/libs/`。在当前开发阶段，规定的测试拓扑是客户端、服务端安装同一 JAR；
客户端与专用服务器行为尚无自动验收，纯服务端安装也尚未验证。未来 P2 背包 screen
需要客户端代码。

开发基线是仓库默认 `main`。功能分支应从最新 `main` 创建。

### 开发运行

```bash
./gradlew runClient
./gradlew runServer
```

`runGameTestServer` 已配置，但仓库还没有首批 GameTest；加入测试前不把它视为有效验收。

更完整的步骤见：

- [安装与当前用法](docs/INSTALLATION_AND_USAGE_CN.md)
- [开发指南](docs/DEVELOPMENT_CN.md)
- [配置说明](docs/CONFIGURATION_CN.md)

## 当前命令

`spawn`、`list` 和 `remove` 需要达到 `permissions.commandPermissionLevel`，默认是 `2`。
`credentials` 不要求 OP 等级，但只能由 roster 中记录的精确 owner 对活动 bot 执行；OP
也不能配置别人的 bot。

```text
/botplayer spawn <name>
/botplayer list
/botplayer remove <name>
/botplayer credentials <name>
```

名称必须是 1–16 位 ASCII 字母、数字或下划线。现阶段 UUID 由名称的小写形式派生：只改
字母大小写仍得到同一临时 UUID，其他改名会得到新身份；当前没有重命名约束或迁移工具，
因此不要把改名当作受支持操作。

## 文档导航

- [文档总目录与维护规则](docs/README_CN.md)
- [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md)
- [完整架构与 P0–P10 路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)
- [原版玩法能力矩阵与发布门槛](docs/VANILLA_CAPABILITY_MATRIX_CN.md)
- [安装与当前用法](docs/INSTALLATION_AND_USAGE_CN.md)
- [当前配置](docs/CONFIGURATION_CN.md)
- [开发与测试](docs/DEVELOPMENT_CN.md)
- [架构决策记录](docs/adr/README.md)
- [参与开发](CONTRIBUTING.md)
- [安全策略](SECURITY.md)
- [更新日志](CHANGELOG.md)
- [第三方研究与许可证边界](THIRD_PARTY_NOTICES.md)

## AI 与安全边界

DeepSeek 尚未接入。当前客户端可以本地保存和绑定 API Key，但没有 Provider 或 HTTP
请求；bot 不会因此聊天、规划或行动。凭据边界是：

- Key 只在 owner 客户端游戏目录的 `config/botplayer/credentials-v1.json` 保存（默认启动
  目录通常是 `.minecraft`）；
  `(serverInstanceId, ownerUuid, botId) → profileId/agentId` 绑定写在同目录
  `bindings-v1.json`；当前是明文落盘，优先原子替换（不支持时退化为同目录覆盖）并尽力
  收紧文件权限，不宣称加密或系统密钥库；
- Key 不进入聊天或命令参数、Minecraft payload、服务端、世界 NBT/SavedData、playerdata、
  普通日志、崩溃报告或 Git；
- 一个本地 credential profile 可以绑定多个 bot，但每个 bot 使用独立 agentId 和状态；
- 只有持久 owner 可以配置；未来 client-sponsored LLM 在 owner 离线时不可用；
- 服务端 active agent binding 在 owner 退出、bot 卸载或停服时清除；客户端本地 binding
  保留，重新打开界面后可以再次绑定；
- LLM 不逐 Tick 控制，不直接运行代码、命令、脚本或任意 HTTP；
- 模型只能提出结构化计划，不能直接改变方块、物品或玩家状态；
- 每个有副作用的动作都要经过权限、风险、范围、幂等和结果验证；
- 未知模组玩法先询问、适配或拒绝，不能用直接改数据来伪装成功。

发现密钥泄漏或安全问题时请先阅读 [SECURITY.md](SECURITY.md)。

## 开发路线

```text
P0 工程基线
→ P1 真实服务端玩家内核
→ P2 原子动作与背包 GUI
→ P3 感知与玩家活动理解
→ P4 导航与安全反射
→ P5 生存技能闭环
→ P6 DeepSeek 与聊天
→ P7 长期记忆与目标
→ P8 模组适配
→ P9 多 bot 协作
→ P10 硬化与发布
```

当前正在完成 P0/P1。持久 roster、owner、服务器实例 ID 和客户端凭据基础已经进入代码；
下一批先补齐虚拟连接协议闭环、自动恢复、ACL 和生命周期 GameTest，再按阶段建立可靠身体。

## License

BotPlayer 自有代码使用 [MIT License](LICENSE)。参考项目只用于理解公开结构和行为；
任何实际引入的依赖、代码、资源或提示模板都必须经过单独的许可证审查，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
