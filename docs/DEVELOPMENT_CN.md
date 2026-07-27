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
./gradlew --no-daemon clean build
```

当前贡献基线是仓库默认 `main`。新功能分支从最新 `main` 创建，不要继续从已经合并的
P0/P1 审查分支派生。

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
  profile/                       持久 BotProfile DTO 与 NBT 编解码
  persistence/                   schema v1 roster、owner 与 serverInstanceId
  kernel/                        ServerPlayer、连接、listener、runtime handle
  lifecycle/                     在线实例状态机和管理器
  network/                       界面打开与 agentId 绑定 payload；永不传 Key
  client/
    BotPlayerClient.java         CLIENT 物理端装配本地 store 与 payload 实现
    ClientPayloadHandlers.java   common-safe facade，不引用 net.minecraft.client
    PhysicalClientPayloadHandler.java 真实客户端 Screen/payload 处理
    credential/                  profile、binding、严格 JSON 与原子保存
    screen/                      Key 输入/掩码、保存、绑定与解绑 GUI
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
8. API Key 只进入 owner 客户端的独立本地凭据 store，不进入命令、聊天、Minecraft
   payload、服务端、世界或日志；
9. 异常生成和卸载不能在 PlayerList、Level 或 manager 留残余；
10. 版本相关 NMS/Mixin 代码集中在平台接入边界；
11. roster 是 bot 身份、持久 owner 和 `serverInstanceId` 的服务端权威源；
12. 共用 credential profile 不得合并不同 bot 的 agentId 或状态；
13. 客户端凭据落盘当前是明文与尽力文件权限，不得描述成加密或 keychain；
14. 没有 Provider/HTTP 时不得把凭据 UI 描述成 DeepSeek 已接入。

## Roster 与客户端凭据检查

当前 roster SavedData 名是 `botplayer_roster`，schema v1 保存随机持久
`serverInstanceId`，以及每个 profile 的 botId、规范名字和可选 owner。修改它时必须保证：

- 首次由真实玩家创建才分配 owner；控制台、命令方块和 bot 创建得到无 owner profile；
- 同名大小写归一命中既有 profile，后续 spawn 不覆盖 owner；
- 不支持的 schema、重复 botId/名字和缺失 serverInstanceId 安全失败；
- roster 不保存 Key、credential profile ID、agentId 或客户端 binding；
- autoload、owner claim/transfer、永久删除和正式重命名当前仍未实现。

客户端文件位于当前游戏目录的 `config/botplayer/credentials-v1.json` 和
`bindings-v1.json`（默认启动目录通常是 `.minecraft`）。`ClientCredentialStore` 必须
继续保持：

- credential JSON 与 binding JSON 分离，后者使用
  `(serverInstanceId, ownerUuid, botId)`；
- profile 可创建/替换，bot 可绑定/解绑；当前没有 profile 删除；
- 相同 profile ID 替换 Key 时，共享它的其他 bot binding 不丢失；
- 每个 binding 的 agentId 唯一且稳定，不能被另一个 bot 同时占用；
- 严格字段/schema 校验、临时文件优先原子替换（不支持时同目录覆盖）、POSIX 权限尽力设置；
- 读取损坏文件时拒绝加载和覆盖，错误与 `toString()` 不输出 secret；
- `/botplayer settings <name>` 只对活动 bot 的精确持久 owner 成功；OP 无绕过；
- payload 只包含 serverInstanceId、botId、botName、agentId 和状态，不包含 Key、profile ID
  或 Key 派生信息；
- owner 退出、bot 卸载和停服清除服务端运行时 agent binding。

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
| 客户端凭据单元测试 | `ClientCredentialStoreTest`：round-trip、共享 profile、agent 隔离、替换/解绑、损坏拒绝与脱敏 |
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

- 从最新默认 `main` 创建 `agent/<简短主题>`；
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
- roster schema、owner 和 `serverInstanceId`；
- 客户端 credential profile、agent binding、文件路径和明文风险；
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
