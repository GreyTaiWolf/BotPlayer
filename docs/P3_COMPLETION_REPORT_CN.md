# BotPlayer P3 完成验收报告

> 报告状态：P3 自动化退出门已通过
>
> 更新日期：2026-07-28
>
> 验收载体：[PR #4](https://github.com/GreyTaiWolf/BotPlayer/pull/4)
>
> 验收提交：`38851d1791b84e73705b302be8438e441c3f26ff`
>
> GitHub Actions：
> [Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181)
> 使用 Temurin Java 21.0.11 通过完整自动化门禁；客户端手工、独立专用服与多 bot soak
> 仍未验证

本文把“源码静态计数”“CI 任务成功”“GameTest 日志报告”分开报告。Build #28 证明
当前提交的编译、Gradle `test`、27 个 GameTest、clean build 与 JAR upload 全部通过；
这仍不等于客户端、独立专用服或长时间性能已经验收。

设计和调研依据见
[P3 感知与世界模型调研设计](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md) 与
[ADR-0013](adr/0013-finite-perception-two-plane-world-model.md)。

## 1. 验收范围

P3 目标是让 bot 获得有界、可溯源、可失效的局部认知，而不是全服全知。本次验收包含：

- 权威事件与每 bot generation 认知事件双平面；
- `ActionOutcome`、NeoForge 事件与原版定向声音包的事件收集；
- 距离、维度、视线、目标和预算投影；
- 自身、背包、注视、附近实体、威胁、局部方块和声音传感器；
- 有界不可变 `ObservationSnapshot`；
- 服务器内部 scoped revision 与短期事实失效；
- 确定性滑动窗口玩家活动推断、generation-local 认知证据序号和中文置信表达；
- 管理诊断与活动纠正命令；
- generation、卸载和停服清理；
- `perception.*` 服务端配置。

不包含通用容器内容、导航、技能、DeepSeek、聊天或长期记忆。

## 2. 已编码生产路径及自动化状态

### 2.1 双事件平面

| 能力 | 状态 | 代码证据与边界 |
|---|---|---|
| 权威事件环 | 已编码；Build #28 编译/测试通过 | 普通 audit、独立 spatial projection、routed sound audit 三环共享 runtime session 和唯一递增 `eventSeq`；定向/声音洪泛不挤出空间候选，游标不进入 AI-safe 快照 |
| 每 bot 认知事件环 | 已编码；Build #28 编译/测试通过 | 每 `(botId, generation)` 的非声音语义/声音分环有界保存并共享从 1 开始的 local `perceivedSeq`；声音洪泛不逐出 action/block/damage/activity 证据 |
| 动作终态入口 | 已编码；Build #28 编译/测试通过 | `ActionOutcomeSink` 在 canonical 终态处同步送入 `AuthorityEventCollector` |
| NeoForge 事件入口 | 已编码；Build #28 编译/测试通过 | 放置要求完整预期 `BlockState` 匹配；破坏只有变空气才归因 actor；break/place/toss 候选捕获 generation，验证后用不公开的 `routing.*` 定向原 generation |
| 去重与容量 | 已编码；Build #28 编译/测试通过 | 待验证候选有界；动作与事件描述同一变化时按 mutation key 去重 |
| 投影新鲜度与可靠路由 | 已编码；Build #28 编译/测试通过 | `SELF/DIRECT` 在发布时可靠投递；`VISUAL/AUDIBLE` 从独立 spatial ring 读取 same-tick 候选，超预算只选最新窗口并计管理员 coverage，旧 backlog 不回放 |
| critical outcome ingress | 已编码；Build #28 编译/测试通过 | 上限与 P2 最大 canonical 终态吞吐对齐；成功 break 按 2 个发布单位计，其他终态按 1 个单位计 |
| actor 身份最小披露 | 已编码；Build #28 编译/测试通过 | `SELF` 只保留 bot 自身 actor；`VISUAL` 对每个 actor 单独执行范围、视锥、已加载与遮挡复核；两者删除通用身份 delta |
| 历史感知关闭 | 已编码；Build #28 编译/测试通过 | `VISUAL/AUDIBLE` 只允许同 Tick 投影；积压、预算延迟与声音迟到 fail-closed |
| 世界 revision | 已编码；Build #28 编译/测试通过 | 服务器内部维度和 block/entity/container-location target scope；container 仅使 opaque block-entity 位置事实随方块变化失效，不表示内容 revision；scope 表有界，AI-safe 快照不暴露全局 revision |

可取消事件回调不直接等于成功。候选收集器在同一服务器 Tick 的 Post/感知阶段于世界逻辑
之后检查结果，仅对验证后的状态发布 `COMMITTED`。

### 2.2 感知与快照

| 能力 | 状态 | 代码证据与边界 |
|---|---|---|
| 自身状态 | 已编码；Build #28 编译/测试通过 | 生命、饥饿、空气、状态、位置、速度、视角与姿态 DTO；必须在当前 Tick 成功采样，否则撤下最新快照 |
| 自身背包 | 已编码；Build #28 编译/测试通过 | 一次完整读取原版 41 槽并生成有界物品摘要/digest；不保存 `ItemStack`，超过 20 Tick 未成功刷新时撤下快照 |
| 注视感知 | 已编码；Build #28 编译/测试通过 | 已加载范围内方块/实体射线，遮挡优先，未加载返回未知；预算或第三方读取异常返回 `Unavailable`，不伪造 miss |
| 附近实体/威胁 | 已编码；Build #28 编译/测试通过 | 实体索引每次原始回调先扣 `ENTITY_SCAN`，匹配后再扣 `ENTITY_READ`；威胁用 relevant selector、独立 raw cap 和读取子配额，普通中立实体不会成为未记账工作 |
| 局部方块 | 已编码；Build #28 编译/测试通过 | 注视点、脚下小邻域与事件焦点；仅实际支撑块免 LoS，其余防 X-ray |
| 定向声音 | 已编码；Build #28 编译/测试通过 | 从发往具体 bot listener 的原版声音包生成候选；每 generation 独立 FIFO，按活动 generation 数计算公平份额并 round-robin 抽取；历史声音 ring 在快照预算不足时优先最新事件，再按序输出；仅 same-tick |
| 不可变快照 | 已编码；Build #28 编译/测试通过 | 只包含 stream/snapshot/local perceived 水位、观察、事件、活动与当前 bot 分类预算 |
| 压力降级 | 已编码；Build #28 编译/测试通过 | EWMA MSPT + 恢复滞回；权威投影与公开传感器分池计费，bot 采样起点逐 Tick 轮转，快照只暴露本 bot 公开传感器分类预算 |

快照不携带 `Level`、`Entity`、`ItemStack`、`BlockEntity` 或 `Menu` 活动引用。所有扫描只
读取已加载位置，不以感知为理由加载远方区块；也不携带 authority session/seq、
`worldRevision` 或全局预算计数。

### 2.3 世界模型与活动理解

| 能力 | 状态 | 代码证据与边界 |
|---|---|---|
| 短期事实 | 已编码；Build #28 编译/测试通过 | `WorldModelService` 有界保存来源、证据、revision、TTL 与状态 |
| 冲突/失效 | 已编码；Build #28 编译/测试通过 | 新观察 supersede 冲突值；已投影 `COMMITTED` 变化 stale；已投影但结果不确定或 TTL 才 `STALE_UNKNOWN`；未感知变化与 authority gap 不触碰事实 |
| 活动推断 | 已编码；Build #28 编译/测试通过 | 每 Tick 严格剔除窗口外事件，单次按 actor 聚合并按新近证据选取；候选上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，`use_on_block` 单独不产生 building 证据 |
| 置信表达 | 已编码；Build #28 编译/测试通过 | `正在/看起来正在/可能正在/我不确定是否正在` |
| 玩家纠正 | 已编码；Build #28 编译/测试通过 | 纠正追加为 `PLAYER_CORRECTION`，不篡改历史证据 |

当前事实只存在于服务器运行时，不是 P7 长期记忆。活动枚举包含合成/冶炼不代表已经接入
容器或工作站事件。P3 对容器最多保存 opaque 位置/方块事实和 scope 失效，不生成内容
digest，不读取 `BlockEntity`、槽位或 menu 状态。

### 2.4 lifecycle 与诊断

| 能力 | 状态 | 代码证据与边界 |
|---|---|---|
| generation 激活/关闭 | 已编码；Build #28 编译/测试通过 | 生成、死亡、重生、换维度、卸载与异常回滚路径已接线 |
| Tick 顺序 | 已编码；Build #28 编译/测试通过 | P2 动作运行时完成后执行 P3，能观察同 Tick 已验证结果 |
| 停服清理 | 已编码；Build #28 编译/测试通过 | 清空感知 runtime 和声音候选 |
| 快照诊断 | 已编码；Build #28 编译/测试通过 | `/botplayer perception inspect <name>` 有界输出快照、活动和最近事实 |
| 活动纠正 | 已编码；Build #28 编译/测试通过 | `/botplayer perception correct <bot> <actor> <activity>` |

两个 P3 子命令固定要求原版权限等级 `2`，不随可降低的
`permissions.commandPermissionLevel` 配置降级。`actor` 必须是在线玩家；`activity`
允许 `idle / moving / exploring / mining / building / combat / farming / crafting /
smelting / none`。这些是管理与测试入口，不是对话能力。

## 3. 自动测试来源

当前纯 Java 测试来源及执行状态：

| 测试类 | 覆盖意图 | 执行状态 |
|---|---|---|
| `BotActionRuntimeTest`（P3 新增 3 个用例） | canonical 终态单次通知、replay 不重复、sink 异常隔离、容量拒绝也进入 sink | Gradle `test` 通过 |
| `PerceptionSettingsTest` | 完整 41 槽、最近事件容量、MSPT 滞回、全局最小工作量和 `ENTITY_SCAN` 降级 | Gradle `test` 通过 |
| `PerceptionDtoTest` | DTO 长度、集合上限与不可变复制 | Gradle `test` 通过 |
| `PerceptionBudgetTest` | 每 bot 分类预算、服务器内部全局上限与本地报告 | Gradle `test` 通过 |
| `PerceptionLoadControllerTest` | EWMA 压力转换与恢复滞回 | Gradle `test` 通过 |
| `PerceptionProjectionTest` | authority 元数据与 SELF 身份字段脱敏 | Gradle `test` 通过 |
| `SemanticEventBusTest` | 单调序号、双平面隔离、容量/gap 与定向洪泛不逐出 spatial ring | Gradle `test` 通过 |
| `WorldRevisionTrackerTest` | scope LRU 重入单调性与 related scope 纪元 | Gradle `test` 通过 |
| `WorldModelServiceTest` | 观察、冲突、TTL、scope 与不确定失效 | Gradle `test` 通过 |
| `ActivityInferenceServiceTest` | 稳定回放、严格窗口、置信度、纠正、`use_on_block` 保守推断，以及 mining/building/combat/farming canonical evidence | Gradle `test` 通过 |

按当前源码中的 `@Test` 方法静态计数，P3 新增 43 个纯 Java 用例：`perception` 24 个、
`worldmodel` 16 个、`BotActionRuntimeTest` 新增 3 个；加上 P2 基线 140 个后完整源码为
183 个。这是源码静态计数，不能把 183 冒充测试日志直接报告的通过数；CI 证据是
Gradle `test` 任务成功。

`P3PerceptionAcceptanceGameTests` 的 8 个 NeoForge GameTest 已由 Build #28 运行：

- 自身/背包快照与服务端权威状态一致；
- 注视先看到实体、加入墙体后改为命中遮挡方块；
- 远方全服权威事件 ID 与 actor 不会进入 bot 的 local cognitive stream；
- 定向声音只进入目标 bot generation；日志中的 `P3SoundTarget` / `P3SoundOther`
  场景成功；
- 已感知的 committed 方块变化使旧 block fact 变为 stale；`P3FactStale` 成功；
- generation 轮换产生新 stream 且旧纠正事件不残留；
- 超远方块焦点被截断且不会加载其区块；
- 未提交的破坏、放置和丢弃候选不会进入 authority stream。

“完全未感知变化及 authority gap 不触碰事实或公开预算”仍主要由生产路径/纯 Java
测试支撑，尚无独立运行期 GameTest。

Build #28 日志明确报告 `All 27 required tests passed`，并显示 P3 batch 为 8 tests；
因此该运行是 P2 既有 19 个与 P3 的 8 个全部通过，不是仅按注解进行的源码推算。

## 4. 当前验证证据

| 门禁 | 当前结果 | 说明 |
|---|---|---|
| Java / 命令 | Build #28 已验证 | Temurin Java 21.0.11；`./gradlew --no-daemon clean build runGameTestServer` |
| `compileJava` / `compileTestJava` | 通过 | 当前验收提交严格编译成功 |
| Gradle `test` | 通过 | P3 43、全仓 183 是源码静态 `@Test` 计数，不是日志直接报告数 |
| NeoForge GameTest | 27/27 通过 | 日志明确 `All 27 required tests passed`；P3 batch 8 |
| `clean build` / JAR | 通过 | `BUILD SUCCESSFUL in 50s`，JAR upload 成功 |
| GitHub Actions | 通过 | [Build #28](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181) |
| 构件 | 已上传 | [`botplayer-neoforge-1.21.1`](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181/artifacts/8702261459)，ID `8702261459`，`653364` bytes，SHA-256 `90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac` |
| 客户端手工测试 | 未验证 | P3 主要是服务端路径，但声音包与命令仍需真实客户端观察 |
| 独立专用服 | 未验证 | 尚无专项结果 |
| 多 bot soak / 性能 | 未验证 | 预算存在不等于性能门已通过 |

验收载体为 [PR #4](https://github.com/GreyTaiWolf/BotPlayer/pull/4)。Build #28 已重新
执行 P2 回归与 P3 新增路径，不复用 Build #18 作为 P3 证据。

## 5. P3 退出门

自动化退出门结果：

- [x] Build #28 在 Temurin Java 21.0.11 下通过 `compileJava` 与 `compileTestJava`；
- [x] Build #28 的完整 Gradle `test` 任务通过；
- [x] mining/building/combat/farming canonical evidence 用例通过；
- [x] P2 回归与 P3 GameTest 全部通过：27/27，P3 batch 8；
- [x] 相同认知事件回放的确定活动输出有纯 Java 测试覆盖；
- [x] 视觉遮挡、全服/认知隔离、generation 与不强制加载有 GameTest 覆盖；
- [x] Build #28 的 `./gradlew --no-daemon clean build runGameTestServer` 成功；
- [x] Build #28 取得可追溯绿色终态并上传 JAR；
- [x] 定向声音隔离与方块事实失效两个新增直接 GameTest 通过；
- [x] README、实现状态、能力矩阵、配置、路线图和 CHANGELOG 回写最终结果；
- [x] 客户端、独立专用服和 soak 继续明确标为未验证。

## 6. 已知限制

- P3 只提供局部运行时认知，没有用户对话接口；`inspect` 是管理诊断命令；
- 没有长期持久化事实、记忆检索、遗忘、迁移或隐私删除；
- 没有管理员全知模式产品配置；
- 没有完整聊天、区域、天气、进度、制作或熔炼事件来源；
- 没有导航、安全反射、任务规划或自主技能；
- 不读取任何世界容器内容，不操作 menu；这些能力延期到 P5A/P5B/P8；
- 当前听觉覆盖已路由的位置与实体绑定声音包；其他包型和模组自定义声音需要后续兼容矩阵；
- 声音公平份额/round-robin、关键 SELF/背包新鲜度、实体预算子配额和可靠路由是源码
  契约；部分有纯 Java 测试保护，但尚未取得多 bot soak 或完整运行期故障注入证据；
- 定向声音目标 generation 隔离已有直接 GameTest；声音候选队列的动态公平份额和
  round-robin 仍没有直接运行期压力场景；
- 已感知方块事实失效已有直接 GameTest；完全未感知变化不触碰事实与公开预算仍缺独立
  运行期场景；
- 配置上限、降级算法和有界结构尚未经过多 bot soak；
- 保护模组、反作弊、代理服和大型模组包尚未验证。

## 7. 实际 CI 结果

```text
验收：
- PR: #4 https://github.com/GreyTaiWolf/BotPlayer/pull/4
- commit: 38851d1791b84e73705b302be8438e441c3f26ff
- GitHub Actions: Build #28 https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30394484181
- Java: Temurin 21.0.11

验证：
- 命令: ./gradlew --no-daemon clean build runGameTestServer
- compileJava/compileTestJava: 通过
- test: 通过；源码静态 @Test 计数 P3 43、全仓 183
- GameTest: 27/27；日志明确 All 27 required tests passed；P3 batch 8
- clean build: BUILD SUCCESSFUL in 50s
- artifact: botplayer-neoforge-1.21.1，ID 8702261459，653364 bytes
- sha256: 90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac

仍未验证：
- 客户端手工
- 独立专用服务器
- 多 bot soak
```

P3 当前的准确表述是：

> 有限感知、双事件平面、短期世界模型和活动推断已通过 Java 21 自动化退出门；客户端
> 手工、独立专用服与多 bot soak 仍未验证。
