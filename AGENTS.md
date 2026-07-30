# BotPlayer 开发代理指南

本文件适用于整个仓库。BotPlayer 当前面向 Minecraft Java 1.21.1、NeoForge 21.1.244
和 Java 21。文档、更新日志与提交摘要默认使用中文；代码标识、命令、路径、协议字段和
错误码保留英文。

## 事实优先级

发生冲突时依次以以下内容为准：

1. 当前检出的源码、资源、构建文件和测试；
2. `docs/IMPLEMENTATION_STATUS_CN.md`；
3. 已接受的 `docs/adr/`；
4. `docs/ARCHITECTURE_AND_ROADMAP_CN.md`；
5. README、安装、配置和其他说明。

路线图、目标包结构和复选框不等于已经实现。发现冲突时在同一提交中修正文档，不要选择
其中一份继续扩大偏差。

## 开始任务前

1. 运行 `git status --short --branch` 和 `git log -5 --oneline --decorate`，确认真实基线并
   保留用户已有改动。
2. 先读 `README.md` 与 `docs/IMPLEMENTATION_STATUS_CN.md`。
3. 用下表定位真实源码，阅读目标类及直接调用者后再修改。
4. 涉及长期边界、数据格式、Mixin、AI、凭据、权限、线程或依赖时，先读
   `docs/adr/README.md` 和总架构对应章节。改变已接受决定必须新增 ADR。
5. 涉及构建、测试或提交时再读 `docs/DEVELOPMENT_CN.md` 与 `CONTRIBUTING.md`。
6. 开始前明确代码、测试、文档三类交付；不要创建未使用的空包或占位类来伪装阶段完成。

优先使用：

```bash
rg --files
rg -n '<类名|命令|配置键|能力 ID|需求关键词>' src docs README.md
rg -n '^#{1,4} ' docs/ARCHITECTURE_AND_ROADMAP_CN.md
```

## 需求到文件

| 需求 | 先读源码/配置 | 同步检查 |
|---|---|---|
| 版本、依赖、JAR、运行配置 | `gradle.properties`、`build.gradle`、`settings.gradle`、`gradle/wrapper/` | `.github/workflows/build.yml`、模组元数据 |
| 模组启动与配置注册 | `BotPlayer.java`、`config/` | `docs/CONFIGURATION_CN.md` |
| `/botplayer` 命令 | `command/BotPlayerCommands.java` | 生命周期、权限、安装文档、语言资源 |
| roster、botId、UUID、名字、owner | `identity/`、`profile/`、`persistence/`、`kernel/BotRuntimeHandle.java`、生命周期管理器 | 架构 §4.2、§17.2，迁移与恢复测试 |
| 生成、移除、重启恢复、停服 | `lifecycle/`、`event/BotPlayerEvents.java` | 实现状态、生命周期测试 |
| 登录、连接、包监听 | `kernel/BotConnection.java`、`BotGamePacketListener.java`、`BotServerPlayer.java` | `PlayerListMixin`、架构 §4.5 |
| 死亡、重生、维度、区块 | `BotServerPlayer.java`、生命周期管理器、事件类 | 三个 Mixin、架构 §4.7–§4.9 |
| Mixin 或映射敏感行为 | `mixin/`、`botplayer.mixins.json` | ADR、精确 descriptor、真人路径回归 |
| 客户端 UI、本地凭据、网络 | `client/`、`network/` 及注册入口 | ADR-0012、SECURITY、配置/安装/开发文档、双端验证 |
| AI Provider、对话、智能体状态 | 当前实现状态列出的 AI 包；目标边界见架构 §8、§11 | 不得把“保存 Key”写成“AI 已接通” |
| 动作、背包、感知、导航、安全、技能、记忆 | 对应功能包；未建立时先读架构 §5–§12 | 能力矩阵、阶段门和测试证据 |
| 客户端文字 | `assets/botplayer/lang/zh_cn.json`、`en_us.json` | UI 不硬编码用户可见文本 |
| 当前能力与缺口 | `docs/IMPLEMENTATION_STATUS_CN.md` | README、CHANGELOG |

新增或移动实际包时，同步更新本表和 `docs/DEVELOPMENT_CN.md`。

## 架构不变量

- bot 主体必须是 `BotServerPlayer extends ServerPlayer`；不得改成自定义 Mob、专用
  `EntityType` 或 NeoForge `FakePlayer`。
- `BotLifecycleManager` 是创建、卸载、死亡/重生绑定和运行时生命周期的唯一入口。
- roster 是 bot 身份、稳定 player UUID、持久 owner 与 `serverInstanceId` 的权威索引；
  原版 playerdata 仍只保存玩家身体与背包。autoload 尚未实现，客户端绑定不能改变 owner。
- 重生会替换 `ServerPlayer`。业务系统持有 botId/handle/generation，不长期缓存旧玩家对象。
- Minecraft 活动对象只在服务器主线程访问；异步线程只持有不可变 DTO、ID 和快照。
- 服务端始终权威决定身份、owner/ACL、世界事实、动作许可和结果；客户端本地数据不能
  授予权限。
- LLM 只提出受限高层计划；Java 动作/技能层校验并执行。模型文本不是事实、权限或成功证据。
- 普通世界变化必须经过玩家动作、原版/NeoForge 校验与结果验证；不得直接改方块、背包、
  NBT 或传送来伪造完成。
- Mixin 保持最小、版本精确且 `require = 1`。新增行为注入需先新增 ADR 和测试。
- `PlayerListMixin` 当前还精确包装 `PlayerList.remove` 内的一次 `save(player)`：只有
  P5 异常隔离设置的一次性 no-save 门闩可以抑制该次保存；正常真人与正常卸载必须调用
  原版保存。
- 所有队列、扫描、重试、请求、路径、上下文、时间和世界改动量必须有上限、取消与失败路径。
- 导航只在主线程采样已加载世界并把不可变快照交给异步 planner；路线执行必须走 P2
  输入/交互和真实玩家物理，不得传送或直接改位置/速度。
- L0 安全每 Tick 使用权威玩家身体与有界近场，不能依赖降频感知；危险可抢占普通动作、
  关闭菜单并挂起导航。
- Bot 必须继承原版/NeoForge 伤害、护甲、饥饿、效果和属性链；不得复制数值或给予特殊免疫。
- Terrain Assist 默认关闭，必须请求与服务端双门控，并通过 P2 动作、保护事件、结果复核
  和单次预算；请求允许不代表强制修改世界。

## P5 当前开发基线

- 有界 Skill/DAG/TTL 预留、背包到快捷栏交换与主动进食是已编码开发切片；Java 21/
  NeoForge 运行验证尚未执行，不计入 P5A 退出门。
- `/botplayer skill inspect <name>` 是权限等级 `2` 的只读诊断，只查看 run，不启动技能。
- P5 GameTest 通过 `P5GameTestSupport` 显式传入固定 Bot 名字，以便在同一持久测试世界
  复用 roster 身份与 playerdata；统一 cleanup 只卸载活动 Bot，不删除 roster/profile。
- 真正的测试 profile 清理仍是测试债。不得为测试向生产 roster 增加永久删除后门；修改
  `AUTO_RESPAWN`、`keepInventory` 等全局状态的场景必须放入独立 batch 并恢复原值。

## 客户端 API Key 边界

- 当前凭据功能只是客户端本地创建/替换 credential profile、按 bot 绑定/解绑的基础设施；
  profile 删除尚未实现。它不调用 DeepSeek、不创建 AI Provider，也不会使 bot 获得聊天、
  规划或游戏能力。
- 原始 Key 及可还原值不得进入服务端命令参数、聊天、Minecraft payload、SERVER 配置、
  世界 NBT/SavedData、playerdata、SQLite、普通日志、崩溃报告、诊断导出或 Git。
- 不提供 `/botplayer apikey <key>`。Key 只能在客户端本地 Screen 输入；不含 secret 的
  服务端消息可以列出已授权 bot、打开界面、建立会话或报告状态。
- 普通 `CLIENT` TOML 只保存非敏感偏好。Key 使用独立 client-local store；当前明文落盘
  优先原子替换（不支持时退化为同目录覆盖）并尽力收紧文件权限，不能宣传为加密、系统
  密钥库或能抵御本机恶意软件。
- 本地结构分离 `credentialProfileId`、每 bot 独立 `agentId` 和服务器权威 `botId`。绑定键
  是 `(serverInstanceId, ownerUuid, botId)`。一个
  credential profile 可供多个 bot 使用，但每个 bot 的 agentId、对话、目标、预算和状态
  必须隔离；不得用 Key 或 Key 指纹充当智能体身份。
- 只有 roster 中持久 owner 与当前玩家一致的 bot 才能配置。服务器提供的
  `serverInstanceId` 用于隔离不同服务器上的同 UUID/botId；篡改客户端文件不能获得服务端
  会话或动作权限。
- Key 永不经过服务端意味着未来使用该 Key 的 Provider HTTP 也必须在客户端执行。客户端
  返回的模型结果仍是不可信输入，服务端必须重新校验会话、owner、botId、revision、时限、
  schema、风险和权限。
- owner 离线时，未来的 client-sponsored LLM 不可用；只能继续已经存在且获准的服务端
  确定性逻辑。除非新 ADR 引入另一凭据模式，不得宣称支持离线 LLM 自治。
- 服务端 active agent binding 只存在于当前运行时，owner 退出、bot 卸载或停服时清除；
  客户端本地 binding/agentId 可保留，重新打开界面并绑定后才恢复活动关系。
- 自动测试只用明显的 fake key 和 mock provider，绝不调用真实账户。任何真实或疑似 Key
  泄漏都按 `SECURITY.md` 立即撤销。

## 编码与验证

- Java 21、UTF-8，只使用仓库 Gradle Wrapper；功能提交不顺便升级 Minecraft、NeoForge、
  映射或构建插件。
- 生命周期操作必须幂等并可回滚；异步结果回主线程后复核 generation 和 revision。
- 未知、过期、越权、超限和解析失败默认拒绝。用户错误信息不得泄露路径、凭据、
  Authorization、完整上下文或私聊。
- 新增公开 DTO、配置、payload 或持久 schema 时，写明版本、大小/频率上限、迁移和不兼容
  行为。

每次提交至少运行：

```bash
git diff --check
./gradlew --no-daemon clean build
```

存在相关 GameTest 后再运行：

```bash
./gradlew --no-daemon runGameTestServer
```

客户端凭据变更还应覆盖 owner/非 owner、不同 `serverInstanceId`、重连、profile
创建/替换、绑定/解绑、损坏文件、多个 bot 共享 credential profile 但 agent 状态隔离，
并断言命令、payload、服务端、世界与日志没有 Key。构建成功只表示编译打包；未执行的
客户端、专用服、GameTest、网络和 soak 验证必须写为“未验证”。

## 文档同步

| 变化 | 必须检查/更新 |
|---|---|
| 用户能力、限制或命令 | README、实现状态、安装文档、CHANGELOG |
| 配置、客户端存储或凭据 | 配置文档、SECURITY、安装文档、实现状态、CHANGELOG |
| AI、客户端/服务端信任边界、owner/ACL、网络或持久格式 | 总架构、新 ADR、ADR 索引、开发文档、SECURITY |
| 生命周期、Mixin 或包结构 | 实现状态、开发指南、总架构；必要时 ADR |
| 能力成熟度 | 能力矩阵，并提供测试证据 |
| 新依赖或第三方内容 | `THIRD_PARTY_NOTICES.md`、构建文件、相关 ADR |
| 分支、版本或构建方式 | README、安装、开发、贡献、安全支持表、实现状态 |

`CHANGELOG.md` 只记录已经进入仓库的变化；路线图只写目标。凭据存储进入代码不代表 P6
完成，也不代表 DeepSeek 已连接。

## Git 与交付

- 保留并避开用户已有改动，不使用破坏性 reset/checkout。
- 改动范围单一，提交摘要使用 `类型: 中文摘要`。
- 推送前复查 `git status --short`、完整 diff、验证结果和文档一致性。
- 最终说明分别列出实现内容、验证证据、未验证/已知限制、提交与推送目标。
