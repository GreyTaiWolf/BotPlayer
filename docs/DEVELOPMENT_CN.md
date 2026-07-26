# BotPlayer 开发指南

本文面向准备修改 BotPlayer 代码、测试或文档的开发者。

## 环境

| 工具 | 要求 |
|---|---|
| JDK | 21 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.244 |
| Gradle | 只使用仓库 Wrapper 9.2.1 |
| 默认编码 | UTF-8 |

不要使用系统 Gradle 替代 `gradlew`，也不要在同一功能提交中顺便升级映射、NeoForge 或
构建插件。

## 获取和构建

```bash
git clone https://github.com/GreyTaiWolf/BotPlayer.git
cd BotPlayer
git switch agent/p0-p1-server-player-kernel
./gradlew --no-daemon clean build
```

当前完整实现仍在该审查分支。PR 合并前，以它作为贡献基线；合并后改为仓库默认
`main`，不要继续从过期的审查分支派生提交。

Windows：

```bat
gradlew.bat --no-daemon clean build
```

常用任务：

```bash
./gradlew runClient
./gradlew runServer
./gradlew generateModMetadata
./gradlew processResources
```

`runGameTestServer` 已有 run configuration，但当前没有测试类和模板。首批 GameTest 加入后
才把以下命令纳入强制验收：

```bash
./gradlew --no-daemon runGameTestServer
```

## 当前源码结构

```text
src/main/java/io/github/greytaiwolf/botplayer/
  BotPlayer.java                 模组入口与 server config 注册
  command/                       当前 /botplayer 命令
  config/                        当前 server 配置 schema
  event/                         NeoForge 生命周期事件入口
  identity/                      临时名字派生 UUID
  kernel/                        ServerPlayer、连接、listener、runtime handle
  lifecycle/                     在线实例状态机和管理器
  mixin/                         三个最小版本接入类

src/main/resources/
  assets/botplayer/lang/         客户端文本
  botplayer.mixins.json          Mixin 清单

src/main/templates/
  META-INF/neoforge.mods.toml    构建时展开的模组元数据
```

完整目标包结构见
[架构文档第 16 节](ARCHITECTURE_AND_ROADMAP_CN.md#16-推荐包结构与类职责)。

## 核心不变量

任何提交都必须保持：

1. bot 主体是 `BotServerPlayer extends ServerPlayer`；
2. 不为 bot 注册自定义 Mob 或专用 `EntityType`；
3. 不通过继承 NeoForge `FakePlayer` 获得捷径；
4. 所有 Minecraft 活动对象只在服务器主线程访问；
5. 重生后不能继续持有旧 `BotServerPlayer`；
6. 世界副作用最终必须经过动作层和结果验证；
7. 模型输出不是权限、事实或成功证据；
8. API Key 不进入客户端、世界、网络包、聊天或日志；
9. 异常生成和卸载不能在 PlayerList、Level 或 manager 留残余；
10. 版本相关 NMS/Mixin 代码集中在平台接入边界。

## 当前三个 Mixin

| 类 | 目的 | 修改行为 |
|---|---|---|
| `ConnectionAccessor` | 为本地连接设置私有 channel | 只暴露字段写入 |
| `PlayerListMixin` | 登录时换 listener；重生时保持 bot 类型 | 两个精确 `NEW` 包装 |
| `ServerPlayerDeathMixin` | 正常死亡完成后通知 manager | TAIL 观察，不改变死亡结果 |

修改 Mixin 时必须：

- 固定精确方法和目标描述符；
- 使用 `require = 1`，让版本漂移在开发期失败；
- 证明真人玩家路径保持原样；
- 增加对应 GameTest；
- 更新 ADR 和架构文档；
- 在目标 NeoForge 版本做干净构建和运行验证。

不要把普通业务逻辑塞进 Mixin。

## 生命周期修改检查

修改生成、死亡、重生、维度或卸载时至少考虑：

- 相同名字和 UUID 已在线；
- playerdata 存在与不存在；
- `placeNewPlayer` 中途抛异常；
- NeoForge 取消死亡；
- 重生创建新的玩家实例；
- 维度转换返回 `null`；
- `/kick`、命令移除、停服；
- 单个登出事件抛异常；
- 零真人玩家；
- 多 bot 顺序和并发请求；
- PlayerList、Level、连接、区块和 runtime handle 残留。

## 计划中的动作层规则

P2 后所有普通世界变化都应经过：

```text
意图/技能
→ ActionEnvelope
→ 权限与安全 Guard
→ ServerPlayer 动作入口
→ NeoForge/原版校验
→ ActionOutcome
→ 世界状态验证
```

不能以这些方式伪造任务完成：

- 直接 `setBlock` 代替合法放置；
- 直接删除方块代替挖掘；
- 直接修改背包代替容器操作；
- 传送代替正常寻路；
- 接受 LLM 的“已完成”文本而不查世界状态。

测试夹具、管理员恢复工具和迁移器可以有受限的直接写入，但必须与普通技能入口隔离并审计。

## 测试层次

| 测试 | 适合内容 |
|---|---|
| 纯 Java 单元测试 | schema、状态机、权限、DAG、失败码、路径成本 |
| NeoForge GameTest | 玩家生命周期、动作、方块、实体、菜单、维度 |
| 集成测试 | HTTP mock、SQLite、配置和适配器 fixture |
| 回放测试 | 感知事件、活动理解、目标和记忆 |
| Chaos/Fuzz | 非法 AI 输出、迟到、重复、超限、取消 |
| 手工客户端 | 玩家外观、Tab、动画、背包 Screen |
| Soak/性能 | 多 bot、内存、MSPT、连接和任务泄漏 |

当前 CI 只运行 `clean build` 并上传 JAR。它证明编译和打包成功，不证明游戏内生命周期正确。

## 每次提交前

```bash
git diff --check
./gradlew --no-daemon clean build
```

有 GameTest 后再执行：

```bash
./gradlew --no-daemon runGameTestServer
```

同时人工检查：

- 没有生成文件、IDE 文件、世界存档或 secret；
- 没有意外修改用户的无关文件；
- 配置、命令、公开 API 和数据变化有文档；
- 当前状态没有把计划写成已完成；
- 新依赖已更新第三方说明；
- 安全边界变化已有 ADR。

## 提交与 PR

- 从当前完整实现所在的最新基线创建 `agent/<简短主题>`：本 PR 合并前是
  `agent/p0-p1-server-player-kernel`，合并后是 `main`；
- 一次提交只覆盖一个可解释范围；
- 推荐提交摘要：`类型: 中文说明`；
- PR 正文写清变化、原因、用户影响、风险和验证；
- 大型或高风险变更先使用 Draft PR；
- CI 和所需测试全绿后才标记 ready；
- 不把“能编译”写成“功能已经在游戏内验证”。

常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`chore`。

## 文档同步

文档职责和事实优先级见 [文档总目录](README_CN.md)。改变以下内容时不得只改代码：

- 命令和权限；
- 配置键、默认值和位置；
- 当前可用能力；
- 生命周期或 Mixin；
- 持久化 schema；
- AI provider、工具和 API Key；
- 新依赖或参考代码；
- 阶段完成状态。

## 关键资料

- [NeoForge 1.21.1 Getting Started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge 1.21.1 GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
- [NeoForge 1.21.1 Configuration](https://docs.neoforged.net/docs/1.21.1/misc/config)
- [架构与路线图](ARCHITECTURE_AND_ROADMAP_CN.md)
- [贡献规范](../CONTRIBUTING.md)
