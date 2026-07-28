# BotPlayer 更新日志

本文件记录已经进入仓库的变化。未来路线、想法和未完成任务不写成已发布功能；它们统一放在
[架构与路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## Unreleased

### 新增

- 新增 schema v1 持久 bot roster，保存规范名字、稳定 bot/player UUID、owner 和
  `serverInstanceId`；
- 新增 `/botplayer settings <name>`：只有 roster 中精确 owner 可以打开本地界面，OP
  也不能越过 owner 检查；
- 新增客户端本地 API Key 管理界面，可创建/替换 credential profile、绑定/解绑 bot；当前
  不提供 profile 删除；
- 同一个 credential profile 可以供 owner 的多个 bot 使用，但 agentId 与后续状态按 bot
  隔离；
- owner 退出、bot 卸载或停服时清除服务端运行时 agent binding，Key 始终只在客户端；
- 新增根目录 `AGENTS.md`，按需求指引开发者读取源码、架构、安全和同步文档。
- 新增 P2 确定性动作运行时：有界 mailbox、幂等 ledger、动作状态机、控制通道仲裁、
  deadline、`maxTicks`、取消、抢占、shutdown 和结构化结果/证据；
- 新增 generation 绑定的 `WAIT / LOOK_AT / MOVE_INPUT / JUMP / STOP`，使用普通玩家输入、
  碰撞和物理完成短程移动；
- 新增选择快捷栏、使用/释放物品、使用方块、分阶段破坏、攻击/实体交互、丢弃与指定
  ItemEntity UUID 拾取等待；
- 新增 bot 自身背包 GUI：空主手、主手右键入口，41 个 bot 真实库存槽位与 36 个 viewer
  槽位组成 77 槽 menu；
- 新增 generation/nonce 背包会话、一人写锁、owner/OP 权限、距离/生命周期校验、
  Shift 移动、关闭确认与动作侧 `InventoryMutationGate`；
- 新增虚拟连接常量空间 telemetry，记录丢弃/拒绝包计数、callback、keepalive/teleport
  处理与首个关闭原因，不记录包内容。

### 加固

- runtime handle 在同一服务器会话内按 bot 稳定保留；重生新实例递增 generation，旧代际
  动作和菜单不能重定向到新身体；
- 生命周期关闭可同步排空同 generation 的已排队/活跃动作，清理失败进入安全 reset 或
  代际隔离；
- completion callback 使用有界 dispatcher 与入口背压，并为取消通知保留容量；
- 无客户端玩家采用受管理的两阶段 Tick，避免重复玩家 Tick 或把新位置回写成旧网络位置；
- 死亡、卸载、换维度、viewer 退出、超距、menu 替换和停服会关闭相关背包会话；
- 普通世界交互使用服务端玩家路径，并以方块、实体和物品前后状态验证，不以调用成功代替
  世界成功。

### 配置

- 新增 `actions.mailboxCapacity`、`actions.ledgerCapacity`、
  `actions.commandsPerTick`、`actions.activeCapacity` 和
  `actions.completionCapacity` 五个有界动作运行时配置；
- 新增 `inventory.viewDistance`，默认 `8.0` 方块，只控制 bot 自身背包 GUI 的同维度
  查看/编辑距离。

### 安全

- API Key 只写入 owner 客户端的独立本地明文存储，优先原子替换并尽力收紧文件权限；
- Key 不进入命令、聊天、Minecraft payload、服务端、世界数据、日志或诊断；
- 只有 roster 中持久 owner 可以配置对应 bot；服务器实例 ID 防止不同服务器错误复用绑定；
- 明确当前没有 DeepSeek Provider 或 HTTP 请求，凭据保存不会让 bot 变智能；
- 接受 ADR-0012，以客户端赞助凭据模式取代 ADR-0004 的服务端 secret 部署决定；ADR-0010
  仍然有效。

### 测试

- 新增 `ClientCredentialStoreTest`，覆盖 round-trip、共享 profile 与独立 agentId、
  server/owner 隔离、共享 Key 替换、解绑保留 profile、损坏/未知/超限 schema 拒绝、非法
  输入、错误脱敏，以及 Key 只进入 credential 文件。
- 新增动作契约、状态表、幂等、仲裁、运行时重入/背压/故障、输入、移动、交互指纹/前置
  条件、库存布局/会话和连接 telemetry 的纯 Java 测试来源；
- 新增生命周期、短程移动、放置/破坏/保护拒绝/攻击/丢弃/拾取以及 77 槽背包会话的
  NeoForge GameTest 来源和 structure fixture；
- Java 编译启用 `-Xlint:all -Werror`，CI 在 `clean build` 后运行
  `runGameTestServer`；
- 本轮本地严格编译、140/140 单元测试、同一持久世界连续两轮 19/19 GameTest 与
  `clean build` 已通过；远端 CI 终态在分支推送后回写。

### 文档

- 重写 README，明确当前可用与不可用能力；
- 新增文档总目录、安装使用、配置和开发指南；
- 新增逐项原版能力矩阵和 1.0 发布门槛；
- 新增贡献规范和安全策略；
- 重写当前实现状态，区分已实现、部分完成和未实现；
- 记录虚拟连接仍缺发送回调、keepalive/teleport ack 和长时间在线验证；
- 补充当前真实配置键、命令语义、开发构件安装边界和排错；
- 扩充 ADR 索引及第三方研究/许可证边界；
- 明确 DeepSeek、感知、技能和记忆仍未实现；P2 动作与 bot 自身背包按实际自动化验证
  状态报告；客户端 API Key 仍只完成本地管理基础。
- 明确临时名称 UUID 的大小写语义、审查分支过渡规则和双端开发测试边界。
- 将已经合并的 P0/P1 审查分支说明改为默认 `main` 开发基线；
- 同步客户端凭据、owner、服务器实例隔离、离线限制和明文存储风险。
- 新增 AI 玩家外部调研与 P2 重新基线，确定生命周期、控制协调、原子动作、背包会话、
  技能和 Goal/计划六层可组合状态机；
- 新增 P2 完成验收报告，分别记录已编码、实际验证和未覆盖边界；
- 将 P2 重排为 P2-A～P2-E，并明确 bot 自身背包属于 P2-D；最小原版世界容器、广泛原版
  容器/工作站和模组自定义 menu 分别延期到 P5A、P5B 和 P8。

## 0.1.0-alpha.1 — 开发基线（2026-07-26，尚未正式发布）

### 新增

- Minecraft 1.21.1、NeoForge 21.1.244、Java 21 项目；
- `BotServerPlayer extends ServerPlayer`；
- 本地虚拟连接和 bot packet listener；
- 登录、重生和死亡完成观察的最小 Mixin；
- 在线生命周期管理、死亡延迟重生和停服清理；
- 临时名字派生 UUID 与原版 playerdata 读取路径；
- `/botplayer spawn|remove|list`；
- server 配置和 GitHub Actions Java 21 构建。

### 加固

- 生成失败时回滚 PlayerList、Level、连接和 runtime；
- 维度切换失败不错误更新 handle；
- 重生完成后再绑定新玩家实例；
- 被取消的死亡不会错误安排重生；
- 停服逐 bot 隔离异常，并保证 manager 引用移除。

### 已知限制

- 没有 roster、autoload、owner/ACL 和 generation；
- 没有单元测试或 GameTest；
- 没有背包 GUI、动作、感知、技能、DeepSeek 或记忆；
- 仅用于开发，不适合重要世界和公网生产服务器。
