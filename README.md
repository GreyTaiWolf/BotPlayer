# BotPlayer

面向 Minecraft Java 的真实服务端玩家 AI 框架。

当前目标平台：

- Minecraft 1.21.1
- NeoForge 21.1.244
- Java 21
- 当前开发版本 `0.1.0-alpha.1`

BotPlayer 的主体直接继承 `ServerPlayer`，进入原版 `PlayerList`、玩家 NBT、死亡/重生、
维度、计分板、统计、进度和区块跟踪流程。它不是自定义生物，也不继承 NeoForge
`FakePlayer`。DeepSeek 只负责对话、理解和高层规划；所有世界动作最终由受权限约束、
可验证、可中断的 Java 技能执行。

## 当前进度

仓库已进入 P0/P1：

- NeoForge 1.21.1 MDK 工程基线；
- 真实 `BotServerPlayer`、虚拟连接与本地 packet listener；
- `placeNewPlayer` 和 `respawn` 两个窄 Mixin；
- 服务器线程生命周期管理；
- 原版死亡后延迟重生；
- `/botplayer spawn|remove|list`；
- GitHub Actions 构建基线；
- 完整 P0–P10 架构与任务文档。

当前代码是开发内核，不应直接用于重要存档。持久 roster、完整生命周期 GameTest、
背包 GUI、动作、感知、技能、DeepSeek 和记忆将按路线图依次实现。

## 文档

- [完整架构、代码职责与 P0–P10 路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)
- [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md)
- [架构决策](docs/adr/README.md)

## 开发

```bash
./gradlew build
./gradlew runServer
./gradlew runClient
```

需要 JDK 21。开发和 CI 必须使用仓库内 Gradle Wrapper。

进入测试世界后，拥有配置权限等级的玩家可使用：

```text
/botplayer spawn <name>
/botplayer list
/botplayer remove <name>
```

## 安全边界

- API Key 只允许来自服务端环境变量或外部 secret，不进入世界、客户端、聊天或日志；
- LLM 不逐 Tick 控制，不直接运行代码、命令、脚本或任意 HTTP；
- Bot 的挖掘、放置、交互和物品变化必须经过玩家动作入口及结果验证；
- 未知模组玩法先询问或拒绝，不能用直接改 NBT/方块来伪装成功。

## License

BotPlayer 使用 [MIT License](LICENSE)。参考项目只用于理解公开结构与行为；任何实际引入
的依赖或代码都必须单独通过许可证审查。
