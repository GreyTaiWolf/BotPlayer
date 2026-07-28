# BotPlayer 当前实现状态

> 更新日期：2026-07-28
>
> 验收载体：[PR #4](https://github.com/GreyTaiWolf/BotPlayer/pull/4)
>
> 当前阶段：P2-A～P2-E 与 P3 自动化退出门已通过
>
> 发布状态：尚未发布，不建议用于重要存档

本文只记录能够从当前源码、资源、构建文件和测试来源核对的事实。路线图目标见
[ARCHITECTURE_AND_ROADMAP_CN.md](ARCHITECTURE_AND_ROADMAP_CN.md)，P2 的调研和重新定界见
[AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md](AI_PLAYER_RESEARCH_AND_P2_REBASELINE_CN.md)，
P2 最终验证结果见 [P2_COMPLETION_REPORT_CN.md](P2_COMPLETION_REPORT_CN.md)。P3 的设计
依据与自动化验收、剩余缺口分别见
[AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md](AI_PLAYER_RESEARCH_AND_P3_DESIGN_CN.md) 和
[P3_COMPLETION_REPORT_CN.md](P3_COMPLETION_REPORT_CN.md)。

## 状态含义

| 标记 | 含义 |
|---|---|
| 已编码 | 生产代码或测试来源存在，已做源码级核对；不自动表示构建或 GameTest 已通过 |
| 已编码；Build #28 编译/测试通过 | 生产路径已编译，相关自动测试任务已通过；不自动表示逐项 GameTest 或手工验证 |
| 本地已验证 | 对应严格编译、自动测试或构建已在当前分支实际通过 |
| 远端已验证 | 对应严格编译、自动测试或构建已由可追溯 CI 运行通过 |
| 待主线验证 | 已接入候选分支，最终本地命令或远端 CI 终态尚待回写 |
| 部分完成 | 主路径存在，但广度、异常、迁移或专项测试仍缺失 |
| 未实现 | 只有设计，不能在游戏中使用 |

本轮不使用单一“已完成”掩盖不同验证层。严格编译、单元测试、GameTest、客户端、专用服和
soak 必须分别报告。

## 工程与验证基线

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| Minecraft 1.21.1 / NeoForge 21.1.244 / Java 21 | 已编码 | `gradle.properties`、Java toolchain |
| ModDevGradle 2.0.142 / Gradle 9.2.1 | 已编码 | `build.gradle`、Wrapper |
| 开发版本 `0.2.0-alpha.1` | 自动化构建已验证 | 尚未正式发布；Build #28 已上传验收构件 |
| 模组元数据和 Mixin 配置 | 已编码 | `neoforge.mods.toml` 模板、`botplayer.mixins.json` |
| 严格 Java 编译 | 本地已验证 | `compileJava` / `compileTestJava` 在 `-Xlint:all -Werror` 下通过 |
| 纯 Java 单元测试 | 本地已验证 | 140/140 通过，0 failed、0 skipped |
| NeoForge GameTest | 本地已验证 | 同一持久世界连续两次 19/19 通过 |
| GitHub Actions | 远端已验证 | Build #18 执行 `clean build runGameTestServer` 并上传 JAR |
| P3 严格编译与单元测试 | 远端已验证 | Build #28 的 Temurin Java 21.0.11 编译与 Gradle `test` 通过；P3 43、全仓 183 是源码静态 `@Test` 计数 |
| P3 NeoForge GameTest | 远端已验证 | Build #28 日志明确 `All 27 required tests passed`、P3 batch 8；`P3SoundTarget/Other` 与 `P3FactStale` 成功 |
| P3 clean build / JAR | 远端已验证 | `BUILD SUCCESSFUL in 50s`，JAR upload 通过；artifact `botplayer-neoforge-1.21.1`，ID `8702261459`，`653364` bytes，SHA-256 `90ddf753c58a3f81a4a5d407a6beafd30c08c208345b01dea51fd241156224ac` |
| 客户端 screen 手工测试 | 基础场景已验证 | 用户已在真实客户端确认 `176×256` 原版玩家风格视觉修复有效；多语言、资源包与全部 GUI Scale 组合仍未专项验证 |
| 独立专用服务器 | 未验证 | 当前不宣称纯服务端或版本不一致兼容 |
| 多 bot soak / 性能 | 未实现 | 没有长时间 MSPT、内存、队列与区块残留证据 |
| 正式发布包 | 未实现 | 当前仍是未发布开发候选 |

P2 与 P3 的完整数字、命令结果和 CI 链接分别见
[P2 完成报告](P2_COMPLETION_REPORT_CN.md) 与
[P3 完成验收报告](P3_COMPLETION_REPORT_CN.md)。

## P3：有限感知、事件与世界模型

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 权威/认知双事件平面 | 已编码；Build #28 编译/测试通过 | `AuthorityEvent` 与每 `(botId, generation)` 的 `PerceivedEvent` 分离；认知侧只保留 opaque `authorityEventId` |
| 单调序号与有界事件环 | 已编码；Build #28 编译/测试通过 | 普通 audit、独立 spatial projection、routed-sound audit 三个 authority ring 共享唯一内部序号；每 generation 的非声音/声音认知分环共享从 1 开始的 local `perceivedSeq` |
| 动作结果事件 | 已编码；Build #28 编译/测试通过 | P2 canonical 终态同步进入 `AuthorityEventCollector`；sink 异常隔离计数 |
| NeoForge 事件收集 | 已编码；Build #28 编译/测试通过 | 放置要求完整预期方块状态；破坏只在变空气时归因 actor，同 ID 属性变化不算破坏，非空气替换泛化为无 actor 的 `BLOCK_CHANGED`；break/place/toss 候选在捕获时冻结 generation，复核后用不公开的 `routing.*` 完成 SELF 路由 |
| 权威事件投影 | 已编码；Build #28 编译/测试通过 | `SELF/DIRECT` 在发布时可靠路由；`VISUAL/AUDIBLE` 读取独立 spatial projection ring，定向洪泛不挤占；同 Tick 超预算只选最新窗口并计管理员 coverage，历史空间事件 fail-closed |
| 视觉事件投影 | 已编码；Build #28 编译/测试通过 | 同维度、距离、FOV/射线与已加载检查；事件位置可见不等于所有 actor 可见，actor 逐个复核且身份 delta 脱敏；仅 same-tick |
| 定向声音感知 | 已编码；Build #28 编译/测试通过 | 只从发往具体 bot listener 的位置/实体绑定声音包生成候选；每 generation 独立 FIFO、动态公平份额与 round-robin；历史声音 ring 在快照预算不足时优先最新事件，再按序输出；仅 same-tick |
| 自身/背包传感器 | 已编码；Build #28 编译/测试通过 | 输出不可变状态与 41 槽完整摘要，不保留 `ItemStack`；SELF 必须为当前 Tick，完整背包超过 20 Tick 未成功刷新时撤下快照 |
| 注视/实体/威胁/局部方块传感器 | 已编码；Build #28 编译/测试通过 | 有界可中止枚举、稳定排序和 chunk DDA；威胁优先并有 relevant selector、raw scan 上限与独立读取子配额；视觉异常返回 `Unavailable`；事件焦点按最新证据优先 |
| 感知预算 | 已编码；Build #28 编译/测试通过 | 权威投影/公开传感器分池；每池有每 bot 六类预算并共享全服工作份额；`ENTITY_SCAN` 对实体索引每次原始回调计费，`ENTITY_READ` 只对匹配候选计费；`globalWorkPerTick` 最小 64 |
| MSPT 降级 | 已编码；Build #28 编译/测试通过 | EWMA 与恢复滞回，`NORMAL/DEGRADED/CRITICAL` 调度 |
| 不可变 `ObservationSnapshot` | 已编码；Build #28 编译/测试通过 | 只含 generation-local stream/snapshot/perceived 水位、观察、活动与本 bot 分类预算 |
| AI-safe 快照脱敏 | 已编码；Build #28 编译/测试通过 | 不暴露 authority session/seq、全局 `worldRevision` 或全服预算计数；`SELF` 仅保留 bot 自身 actor，`VISUAL` 仅保留逐个可见 actor，两者删除通用身份 delta |
| scoped world revision | 已编码；Build #28 编译/测试通过 | 服务器内部维度/target scope；精确 scope 容量有界 |
| 短期 `WorldModelService` | 已编码；Build #28 编译/测试通过 | 来源、证据、TTL、冲突、`STALE/STALE_UNKNOWN/SUPERSEDED`；不持久化 |
| 隐藏变化侧信道隔离 | 已编码；Build #28 编译/测试通过 | 未投影 authority 与 authority gap 不改变事实、local perceived watermark 或公开传感器预算；gap 只进入管理员私有诊断 |
| 玩家活动推断 | 已编码；Build #28 编译/测试通过 | 每 Tick 严格剔除窗口外证据并单次按 actor 聚合，新近 actor 优先；候选上限为 `NORMAL 64 / DEGRADED 16 / CRITICAL 4`，只接受相关 actor 的 `COMMITTED` 证据，`use_on_block` 单独不足以证明 building |
| 玩家纠正 | 已编码；Build #28 编译/测试通过 | 追加 `PLAYER_CORRECTION` 事件，不改写历史证据 |
| generation/lifecycle 清理 | 已编码；Build #28 编译/测试通过 | 死亡、重生、换维度、卸载、断开、回滚和停服关闭旧代际认知 |
| P3 诊断命令 | 已编码；Build #28 编译/测试通过 | `perception inspect` 有界输出快照/活动/事实；`correct` 记录活动纠正 |
| 纯 Java 测试来源 | Gradle `test` 通过 | 源码静态计数：perception 24、worldmodel 16、`BotActionRuntimeTest` 新增 3，即 P3 43、全仓 183；不是日志直接报告数 |
| P3 GameTest | 8/8 通过 | 全仓 27/27；定向声音目标 generation 隔离与已感知 committed 方块变化使旧事实 stale 的新增场景已通过 |
| 通用世界容器内容 | 未实现 | P3 只维护 opaque 位置/方块事实与失效，不生成内容 digest、不读 `BlockEntity` NBT/menu slot；最小/广泛原版容器分别在 P5A/P5B，模组 menu 在 P8 |

P3 的生产路径已编码并通过 Build #28 自动化退出门；这表示提交 `38851d1791b84e73705b302be8438e441c3f26ff`
的编译、Gradle `test`、27 项 GameTest、clean build 和构件上传成功。它仍不表示每个
行为都有直接 GameTest，或覆盖客户端手工、独立专用服、多 bot soak。

## 真实玩家内核

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| `BotServerPlayer extends ServerPlayer` | 已编码 | 没有自定义 `EntityType`，不继承 `FakePlayer` |
| `EmbeddedChannel` 虚拟连接 | 已编码 | `BotConnection` 提供合法本地 channel 与幂等关闭 |
| bot 专用 packet listener | 部分完成 | callback 隔离、teleport ack、进程内 keepalive 记账和常量空间诊断已编码；长时间在线待验证 |
| 登录保持 bot listener | 已编码 | `PlayerList.placeNewPlayer` 精确包装 |
| 重生保持 bot 类型 | 已编码 | `PlayerList.respawn` 精确包装 |
| 死亡完成确认 | 已编码 | `ServerPlayer.die` TAIL 只观察正常完成路径 |
| 稳定 runtime handle / generation | 本地已验证 | 同服务器会话稳定 handle；重生新实例递增代际，旧动作/会话失效 |
| 权威动作实例解析 | 已编码 | 同时复核 runtime、handle、`PlayerList`、维度与 listener 当前实例 |
| 两阶段无客户端玩家 Tick | 本地已验证 | 输入/玩家物理/连接基线由生命周期管理器统一调度，防重复 Tick |
| 稳定身份 | 部分完成 | roster 持久保存 botId/player UUID；重命名、旧身份迁移和离线冲突仍缺 |
| owner 记录 | 部分完成 | 创建者持久化并用于凭据与背包授权；owner 管理、trusted/observer ACL 待补 |
| 真人/名字/UUID 冲突 | 部分完成 | 在线与 roster 冲突已检查；离线 profile/白名单/封禁冲突未完成 |
| 原版 playerdata | 部分完成 | 已检测旧数据并保留保存位置；完整保存/恢复回归仍缺 |
| 生成事务回滚 | 部分完成 | 当前异常路径可清理；完整故障点注入与残留诊断未完成 |
| 生命周期状态机 | 已编码，待完整验收 | 中央转换表和有界历史；autoload/持久 OFFLINE/FAILED 仍缺 |
| 持久 roster | 部分完成 | schema v1 保存身份、owner 与 `serverInstanceId`；autoload/迁移故障测试待补 |
| 原版死亡后自动重生 | 本地已验证 | 通过原版重生路径；100 次死亡—重生 GameTest 连续两轮通过 |
| 维度切换 | 部分完成 | 代码路径和会话关闭已处理；三维度往返 GameTest 未完成 |
| 区块跟踪 | 部分完成 | 按在线玩家位置刷新；长期 ticket/残留/负载未验收 |
| 停服清理 | 部分完成 | 动作、输入、会话、连接和 manager 清理已接线；重启恢复/故障测试未完成 |
| 自动加载 roster | 未实现 | roster 已持久化，重启后仍需管理员重新执行 spawn |

## P2-A：确定性动作脊柱

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 动作契约 | 已编码 | ID、botId、generation、幂等键、deadline、`maxTicks`、typed action |
| 动作 FSM | 已编码 | `QUEUED → VALIDATING → RUNNING → VERIFYING → 终态` |
| 结构化结果 | 已编码 | 唯一终态、失败码、安全摘要、有界 evidence |
| 有界 mailbox | 已编码 | 提交/取消入口、每 Tick drain 预算、排队取消原子摘除 |
| 幂等 ledger | 已编码 | canonical envelope、别名加入、冲突拒绝、终态重放、有界淘汰 |
| 控制仲裁 | 已编码 | `MOVE/LOOK/MAIN_HAND/OFF_HAND/INVENTORY/INTERACT/CHAT` 通道 |
| 优先级与抢占 | 已编码 | 高优先级原子抢占、被抢占动作 cleanup 和唯一终态 |
| deadline / `maxTicks` | 已编码 | 排队与运行时限分别检查 |
| completion dispatcher | 已编码 | 有界队列、callback 不在主线程、入口背压、取消保留容量 |
| cleanup / reset / quarantine | 已编码 | cleanup 失败不能把不安全结果写成成功；无法恢复时隔离 generation |
| 生命周期同步关闭 | 已编码 | 死亡/卸载/停服与重入 callback 可在 Tick 返回前关闭同 generation |
| `WAIT / LOOK_AT / STOP` | 已编码 | Minecraft backend 最小纵切片 |
| 动作运行时测试 | 本地已验证 | 重复、别名、取消、抢占、容量、重入、背压和清理故障已纳入 140 项单测 |

## P2-B：输入与短程移动

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 输入 owner/lease | 已编码 | 精确 action/generation owner、过期主动清零、旧 cleanup 不覆盖新 owner |
| 有界移动输入 | 已编码 | 前后/横向、跑、蹲；有限值与互斥组合校验 |
| 跳跃 | 已编码 | 使用普通玩家输入与物理；空中无因果跳跃拒绝 |
| 视角 | 已编码 | `LOOK_AT` 使用玩家视角并验证 yaw/pitch 与目标 |
| 停止 | 已编码 | 清理输入和控制状态；不冒充“释放所有持续物品”的万能动作 |
| 一 Tick 一次 | 已编码 | 每绝对服务器 Tick 输入变更/应用有去重 |
| 原版物理证据 | 已编码 | 位置、速度、姿态、碰撞、水中与跳跃因果 |
| 移动 GameTest | 本地已验证 | 一格跳跃、静止蹲姿、浅水前移、撞墙、空中跳跃拒绝连续两轮通过 |
| 长距离寻路 | 未实现 | A*、动态重算、stuck 恢复与安全成本属于 P4 |
| 高级移动 | 未实现 | 梯子/脚手架/门、船、矿车、坐骑、鞘翅不在 P2 |

## P2-C：基础世界交互

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 选择快捷栏 | 已编码 | 精确槽位与选中物品指纹 |
| 使用/持续使用/释放物品 | 已编码 | 明确主副手、模式、hold Tick 和预期物品 |
| 使用方块/放置 | 已编码 | 明确维度、方块状态、命中面/局部坐标、视线与物品 |
| 分阶段破坏与中止 | 已编码 | 玩家 game mode 入口、进度 Tick、STOP/ABORT、结果证据 |
| 攻击 | 已编码 | 实体 UUID/类型、距离/视线/冷却检查和伤害证据 |
| 实体交互 | 已编码 | 区分 generic/specific interaction 与主副手 |
| 丢弃 | 已编码 | 丢一个/整栈与 ItemEntity/库存数量守恒 |
| 拾取等待 | 已编码 | 有界等待，可绑定明确 ItemEntity UUID |
| 交互前置条件 | 已编码 | generation、维度、区块/目标、指纹、距离、LOS、冷却和手持物 |
| 世界交互 GameTest | 本地已验证 | 放置、破坏、保护拒绝、攻击、丢弃、指定 UUID 拾取连续两轮通过 |
| 保护模组兼容 | 部分完成 | 使用普通玩家路径可接收取消；具体领地/PVP/反作弊矩阵未验证 |
| 通用世界容器 | 未实现 | 最小原版驱动在 P5A，广泛原版容器/工作站在 P5B |
| 模组/自定义 menu | 未实现 | C1/C3 适配在 P8 |

## P2-D：bot 自身背包 GUI

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| 空主手、主手入口 | 已编码 | NeoForge `EntityInteract`；bot、副手和持物品不触发 |
| 权威库存 | 已编码 | 直接绑定 bot 原版 41 格 `Inventory`，没有第二份服务端库存 |
| 77 槽 menu | 已编码 | bot 41 槽 + viewer 36 槽；盔甲反向映射、副手和快捷栏明确 |
| 客户端 screen / MenuType | 已编码，用户已验证 | 自定义 screen 与 `IMenuTypeExtension` 注册；`176×256` 上下堆叠布局，上方 bot、下方 viewer |
| 原版玩家视觉 | 已编码，用户已验证 | 上方按原版玩家背包排列盔甲、副手、主背包和快捷栏，并渲染真实 bot 的 3D 玩家模型 |
| 合成区与快捷栏高亮 | 已编码，用户已验证 | 原版 2×2 合成区域被背景覆盖且没有 menu slot；服务端同步 bot 当前快捷栏索引并只读高亮 |
| GUI 资源边界 | 已编码，未专项验证资源包 | 运行时引用 `inventory.png`、`generic_54.png` 与原版 HUD 选中框；未复制或打包 Mojang PNG，可跟随资源包替换 |
| GUI Scale 边界 | 已编码，基础场景已验证 | 画布逻辑高度为 256；窗口或显示高度不足时需要降低游戏 GUI Scale，全部比例组合仍未专项验证 |
| 会话 FSM | 已编码 | `OPENING → OPEN → CLOSING → CLOSED` |
| generation/nonce token | 已编码 | 旧代际、错误 nonce 和关闭墓碑拒绝 |
| 权限 | 已编码 | 持久 owner 或服务器 OP；trusted/observer 尚未实现 |
| 距离/维度/存活 | 已编码 | `inventory.viewDistance`，每 Tick 复核并失败关闭 |
| 单 viewer 写锁 | 已编码 | 每 bot 单 viewer、每 viewer 单会话 |
| 动作互斥 | 已编码 | 打开前同步排空；打开期间 mutation gate 拒绝动作库存写 |
| Shift 移动与守恒 | 已编码 | bot/viewer 区间、合法装备槽和总数量检查 |
| 生命周期关闭 | 已编码 | 死亡、重生、换维度、超距、退出、menu 替换和停服 |
| 库存 GameTest | 本地已验证 | 入口/副手、权限、双 viewer、距离/生命周期、77 槽、mutation gate 连续两轮通过 |
| 通用世界容器自动化 | 未实现 | P2-D 只处理 bot 自身玩家背包 |

## 当前用户可见操作

| 操作 | 状态 | 说明 |
|---|---|---|
| `/botplayer spawn <name>` | 已编码 | 新 bot 出现在执行者位置；既有 playerdata 使用保存位置 |
| `/botplayer list` | 已编码 | 列出本次服务器运行期内 bot 和生命周期状态 |
| `/botplayer remove <name>` | 已编码 | 从在线运行时卸载，不删除 playerdata |
| `/botplayer settings <name>` | 已编码 | 只允许活动 bot 的持久 owner；不要求 OP，OP 也不能绕过凭据 owner |
| `/botplayer perception inspect <name>` | 已编码；Build #28 编译/测试通过 | 固定要求原版权限等级 2；有界显示活动 bot 的快照、generation-local 活动证据序号和最近事实 |
| `/botplayer perception correct <bot> <actor> <activity>` | 已编码；Build #28 编译/测试通过 | 固定要求原版权限等级 2；actor 必须在线，纠正作为新证据事件 |
| 空主手、主手右键 bot | 已编码，待客户端验收 | owner 或 OP 在范围内打开 bot 自身背包；不是世界容器自动化 |
| 客户端 API Key 管理 | 已编码 | 创建/替换 profile、绑定/解绑；profile 删除未实现 |
| 服务端 agent binding | 部分完成 | 只保存 botId↔agentId 运行时关系；owner 离线/卸载/停服时清除 |
| 通过命令直接下动作 | 未实现 | 当前没有面向普通用户的动作调试命令或技能/AI 调用入口 |
| 聊天与 DeepSeek | 未实现 | 没有 Provider、HTTP、对话、规划或工具调用 |

## 当前 server 配置

现有配置分为：

- `server_player.*`：最大 bot 数、Tab、自动重生、重生延迟、区块跟踪刷新；
- `actions.*`：mailbox、ledger、每 Tick 命令、活跃动作和 completion 五个容量；
- `inventory.viewDistance`：bot 自身背包查看/编辑距离；
- `perception.*`：范围、彼此分离的权威投影与公开传感器预算（传感器侧含每 bot/全局
  限额）、事件/事实/revision 容量、活动窗口和 MSPT 降级/恢复阈值；
- `permissions.commandPermissionLevel`：`spawn/list/remove` 的原版权限等级；P3
  `perception` 管理命令固定要求等级 2，不随该值降级。

准确键、默认值、范围和建议见 [CONFIGURATION_CN.md](CONFIGURATION_CN.md)。

## P2-E 验收状态

| 门禁 | 当前 |
|---|---|
| 严格 `compileJava` / `compileTestJava` | 通过，`-Xlint:all -Werror` |
| 完整 `test` | 140/140 通过 |
| P2 `runGameTestServer` | 19/19，连续两轮通过 |
| `clean build` / JAR | 通过，产出 `botplayer-0.1.0-alpha.2.jar` |
| 推送后 GitHub Actions | 通过；[Build #18](https://github.com/GreyTaiWolf/BotPlayer/actions/runs/30352199722) |
| 客户端 screen 手工测试 | 本轮基础场景已验证 |
| 独立专用服 | 未验证 |
| 多 bot soak / 性能 | 未验证 |

本地与远端自动化退出门均已通过，因此整体 P2 的实现与自动化验收判定为完成。客户端
screen、独立专用服和长时间 soak 是明确保留的专项验证，不随自动化门一起冒充完成。

## P2 之后的阶段状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P3 | 感知、语义事件、世界模型、玩家活动理解 | 自动化退出门已通过；客户端、独立专用服与 soak 未验证 |
| P4 | 导航、安全反射、动态重规划 | 未实现 |
| P5A | 技能 FSM、首条生存闭环、最小原版世界容器驱动 | 未实现 |
| P5B | 广泛原版容器/工作站、制作、生产和日常生活 | 未实现 |
| P5C | 运输、游戏进程和高级战斗 | 未实现 |
| P5D | 建筑与红石 | 未实现 |
| P6 | DeepSeek、聊天、Tool Firewall、预算 | 未实现；客户端凭据不等于 Provider |
| P7 | 长期记忆、目标、承诺和恢复 | 未实现 |
| P8 | 模组 C0–C3、标准容器与自定义 menu 适配 | 未实现 |
| P9 | 多 bot 协作 | 未实现 |
| P10 | 性能、兼容、安全和正式发布硬化 | 未实现 |

## 下一道门

1. 补充客户端 screen 的多语言、资源包与全部 GUI Scale 组合专项验收；
2. 补充隐藏变化 no-touch 的独立运行期场景；
3. 继续证明保护模组、异常注入和高密度压力下的感知边界；
4. 不把 Build #28 的自动化结果扩大成客户端、专用服或 soak 已验证；
5. 在 P4 之前不把 P2 短程输入宣传成长距离导航；
6. 在 P5 之前不把 P3 方块观察宣传成容器内容读取；
7. 在 P6 之前不把客户端凭据宣传成 DeepSeek 已接通。

## 验证命令

```bash
./gradlew --no-daemon clean test
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon clean build
```

开发运行：

```bash
./gradlew runClient
./gradlew runServer
```

构建成功只证明编译、测试和打包任务成功，不等于客户端、专用服、多 bot 或长时间运行通过。

## 客户端凭据的准确边界

- Key 只写入 owner 客户端的独立本地明文文件，优先原子替换并尽力收紧权限；
- 凭据文件位于客户端游戏目录 `config/botplayer/credentials-v1.json`；非 secret binding
  文件是同目录 `bindings-v1.json`；
- Key 不进入命令、聊天、Minecraft payload、服务端、世界数据或日志；
- `(serverInstanceId, ownerUuid, botId)` 隔离不同服务器、owner 与 bot 的绑定；
- 一个 credential profile 可以被多个 bot 引用，但 agentId 按 bot 独立；
- 只有 roster 中持久 owner 可以配置；修改客户端文件不能改变服务端 owner；
- 当前支持创建/替换 profile 以及绑定/解绑；不支持删除 credential profile；
- owner 离线时，未来 client-sponsored LLM 不可用；
- 当前没有任何真实 API 请求，不能用这项功能测试 Key，也不能让 bot 变智能。
