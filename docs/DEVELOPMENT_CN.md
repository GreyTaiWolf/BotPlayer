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

P2 已加入生命周期、移动、交互和库存 GameTest；P3 加入有限感知与世界事实场景；P4
加入导航、安全、玩家规则兼容与 Terrain Assist 场景。涉及 Minecraft 行为的提交必须运行：

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
  lifecycle/                     在线实例状态机、generation、动作/会话装配和管理器
  action/                        动作契约、FSM、mailbox/ledger/仲裁、输入与 Minecraft backend
  inventory/                     77 槽 menu、session、写锁与 mutation gate
  perception/                    P3 预算、快照、事件收集/投影与 generation 编排
    event/                       有界 AuthorityEvent/PerceivedEvent 双平面
    sensor/                      只读已加载世界的有限传感器
  navigation/                    P4 请求/session、运动快照、A*、follower 与 Terrain Assist
  safety/                        P4 每 Tick SafetyFrame、incident FSM、威胁探针与抢占
  worldmodel/                    scoped revision、短期事实与确定性活动推断
  gametest/                      P2–P4 NeoForge GameTest
  network/                       界面打开与 agentId 绑定 payload；永不传 Key
  client/
    BotPlayerClient.java         CLIENT 物理端装配本地 store 与 payload 实现
    ClientPayloadHandlers.java   common-safe facade，不引用 net.minecraft.client
    PhysicalClientPayloadHandler.java 真实客户端 Screen/payload 处理
    credential/                  profile、binding、严格 JSON 与原子保存
    screen/                      Key GUI 与 bot 自身背包 screen
  mixin/                         三个最小版本接入类

src/main/resources/
  assets/botplayer/lang/         客户端文本
  data/botplayer/structure/      GameTest structure fixture
  botplayer.mixins.json          Mixin 清单

src/gameTestFixtures/
  java/                          只参与 GameTest 的动态 DamageType/PlayerTick 兼容 fixture
  resources/                     只参与 GameTest 的数据驱动伤害类型

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
15. 全服 `AuthorityEvent` 不得自动成为任何 bot 的 `PerceivedEvent`；
16. 传感器不得用 `getChunk` 或 ticket 为感知强制加载区块，未知不等于空气；
17. P3 DTO、事件环、声音候选、事实、revision scope、扫描和证据都必须有硬上限；
18. 完全未感知的变化不得触碰该 bot 的事实、认知水位或公开传感器预算；只有已投影但
    结果不确定的事件或 TTL 才能令事实 `STALE_UNKNOWN`；authority coverage gap 也只能
    进入管理员私有诊断并快进内部 cursor；
19. P3 不读取 `BlockEntity` NBT/menu slot 获取容器内容；世界容器仍属于 P5A/P5B/P8。
20. 导航搜索只读取服务器线程生成的不可变、已加载世界快照；异步 planner 不得持有
    `Level`、`Entity`、`BlockState` 或其他活动 Minecraft 对象；
21. 路线 follower 只通过有界 P2 输入动作驱动真实玩家物理，不得传送、直接改位置/速度
    或跳过碰撞来伪造到达；
22. L0 安全每 Tick 读取权威近场，不能依赖可能降频的 P3 快照；危险必须能够关闭菜单、
    挂起导航并以 `SURVIVAL/EMERGENCY` 抢占普通输入；
23. Bot 使用原版/NeoForge 玩家伤害、护甲、饥饿、效果和属性链；不得建立第二套数值、
    特殊免疫或重复扣血；
24. Terrain Assist 默认关闭，必须请求 policy 与服务器配置同时允许；所有破坏/放置
    必须经过 P2 动作、保护事件、精确结果验证和单次预算，成功后重新采样和规划。

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

## 当前 P2 动作层规则

P2 候选中的普通世界变化必须经过：

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

## 当前 P3 感知层规则

P3 的固定顺序是：

```text
P2 ActionOutcome / NeoForge 候选 / 定向声音包
→ post-state 验证与 AuthorityEvent
→ 维度/目标/距离/视线/预算投影
→ (botId, generation) PerceivedEvent
→ 有界不可变 ObservationSnapshot
→ scoped revision 与短期 WorldFact
→ 有证据、带置信度的 ActivityHypothesis
```

修改感知时至少检查：

- `PerceivedEvent` 不嵌入完整 `AuthorityEvent` 或未授权 delta，只保留 opaque
  `authorityEventId`；其 `perceivedSeq` 必须按 generation-local stream 递增；
- 普通 authority audit、独立 spatial authority projection 与 routed sound audit 三环
  共享唯一 `eventSeq`；声音审计不得进入空间投影环，`SELF/DIRECT` 洪泛不得挤出
  `VISUAL/AUDIBLE` 候选；每 generation 的声音/非声音认知分环并共享 local
  `perceivedSeq`，声音洪泛不得逐出普通语义证据；
- AI-safe 快照不暴露 authority session/seq、全局 `worldRevision` 或全服预算计数；
- `VISUAL/AUDIBLE` 只做 same-tick 投影；空间事件超预算时只选独立 spatial ring 的最新
  有界窗口并计管理员 coverage，积压、预算延迟、gap 和晚到声音 fail-closed；
- 动作终态 sink 同步、隔离失败且不重复发布 replay/alias；
- 可取消事件在世界结果验证前不写 `COMMITTED`；
- break/place/toss 待验证候选在捕获时冻结 bot generation；`routing.*` 只供内部
  generation 定向，不得进入 `PerceivedEvent`；
- critical action-outcome ingress 与 P2 最大 canonical 吞吐对齐；成功 break 按两个
  发布单位计，其他终态按一个单位计；
- 射线在未加载边界停止，方块/实体读取前检查已加载；
- 实体索引每次原始回调先扣 `ENTITY_SCAN`，selector 匹配后再扣 `ENTITY_READ`，达到任一
  上限立即中止，不能先构造无界候选 `List`；
- 局部方块只有实际支撑块可免 LoS，其余邻域/焦点必须防 X-ray；
- `BlockState.hasBlockEntity()` 只能输出 opaque 标记，不能调用 `getBlockEntity`；
- 对已读取候选使用稳定排序；超出读取上限必须显式 `truncated`，不承诺密集场景的入选
  子集完全独立于底层实体迭代顺序；
- `NORMAL/DEGRADED/CRITICAL` 下自身关键观察仍有明确频率；
- 权威扫描与公开传感器分池计费；快照只暴露公开传感器预算，未感知权威事件不能改变它；
- `globalWorkPerTick` 不得低于 64，否则 3/4 公开池无法原子读取完整 41 槽背包；
- 预算耗尽和未加载通过本 bot limits/截断暴露；权威环淘汰只在服务器内部 fail-closed；
- 死亡、重生、换维度、卸载、回滚和停服关闭旧 generation；
- 完全未感知的世界变化不触碰该 bot 的世界模型；已投影 `COMMITTED` 变化令 scope
  `STALE`，已投影但结果不确定的变化或 TTL 才令事实 `STALE_UNKNOWN`；authority gap
  不改变事实、认知水位或公开预算；
- 诊断命令有界，不输出全服权威载荷、authority/global counters 或任意容器内容；
- 容器事实只能是 opaque 位置/方块类型与失效，不得生成内容 digest。
- 活动窗口每 Tick 严格剔除过期证据，推断单次按 actor 聚合并按窗口内新近证据优先；
  actor 上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，不得把 `use_on_block`
  单独解释为 building。

设计依据见
[P3 调研设计](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md) 和
[ADR-0013](adr/0013-finite-perception-two-plane-world-model.md)。

## 当前 P4 导航与安全规则

P4 主线程/异步边界是：

```text
主线程采样已加载世界
→ 不可变 MotionSnapshot
→ 有界 planner executor
→ generation/revision 复核
→ 短租约 P2 输入动作
→ 真实玩家物理与结果验证
```

修改导航或安全时至少检查：

- 未加载区块保持未知，不调用加载/ticket API，不把未知当空气；
- planner 的节点、距离、队列、并发、结果 inbox、deadline 和取消全部有硬上限；
- 终点必须以真实身体位置、速度和可站立面确认，不以“路线已算出”当作到达；
- 方块 revision、动作后状态、偏航和 stuck 会使旧路线失效；恢复次数有限且失败码诚实；
- L0 安全在任何 P3 压力档位继续每 Tick 运行，并只使用停止、后退/侧移、安全邻格、
  上浮、闪避和远离威胁等 P4 动作；
- 低生命/食物可以阻塞普通远行，低食物禁止 sprint；寻找/食用食物和主动用药仍属于 P5；
- 伤害与效果观察以真实身体最终值为准，不通过固定原版枚举拒绝动态 `DamageType` 或
  `MobEffect`；
- Terrain Assist 的请求允许不是强制世界修改；存在纯移动路线时优先纯移动；
- 破坏/放置白名单、工具、支撑、流体、方块实体、库存、远端锚点、保护事件和预算任一
  不满足都要安全拒绝；
- 死亡、重生、换维度、卸载、停服和 generation 变化关闭旧 session、incident 与动作。

完整边界和证据见
[P4 调研设计](AI_PLAYER_RESEARCH_AND_P4_DESIGN_CN.md)与
[P4 完成验收报告](P4_COMPLETION_REPORT_CN.md)。

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

当前 CI 运行 `clean build`、`runGameTestServer` 并上传 JAR。P2 基线由 Build #18
验证；P3 提交 `38851d1791b84e73705b302be8438e441c3f26ff` 的
[Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181)
使用 Temurin Java 21.0.11 执行 `./gradlew --no-daemon clean build runGameTestServer`，
`compileJava`、`compileTestJava`、Gradle `test`、27/27 GameTest、clean build 与 JAR
upload 全部通过，日志明确 `All 27 required tests passed`，P3 batch 为 8 tests。
源码静态 `@Test` 计数是 P3 43、全仓 183，不是 CI 日志直接报告的通过数。日志中的
GameTest 27/P3 batch 8 是实际运行结果。artifact 为 `botplayer-neoforge-1.21.1`
（ID `8702261459`，`653364` bytes，SHA-256
`90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac`）。这些自动化结果
不证明客户端 screen、独立专用服或多 bot soak。

P4 提交 `9fec0388c36870248a204d7ff21b1b663b62bebf` 的
[Build #97](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30445259204) 使用同一
Temurin Java 21.0.11 和完整命令通过严格编译、Gradle `test`、55/55 GameTest、clean
build 与 JAR upload，日志明确 `All 55 required tests passed`，其中 P4 直接场景为 28 个。
源码静态 `@Test` 计数为全仓 200，不是 CI 日志打印的执行数。artifact ID 为
`8721162398`，大小 `838883` bytes，SHA-256
`b36a69f607e4f0e028e2afff15946a03bddd64004638c2d64d479c704706ddcd`。
客户端组合、独立专用服、保护模组矩阵和多 Bot soak 仍需专项验证。

## 每次提交前

```bash
git diff --check
./gradlew --no-daemon clean build
```

存在 GameTest，因此还要执行：

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
- [NeoForge 1.21.1 Events](https://docs.neoforged.net/docs/1.21.1/concepts/events)
- [NeoForge 1.21.1 GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
- [NeoForge 1.21.1 Debug Profiler](https://docs.neoforged.net/docs/1.21.1/misc/debugprofiler/)
- [NeoForge 1.21.1 Configuration](https://docs.neoforged.net/docs/1.21.1/misc/config)
- [P3 感知与世界模型调研设计](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md)
- [架构与路线图](ARCHITECTURE_AND_ROADMAP_CN.md)
- [贡献规范](../CONTRIBUTING.md)
