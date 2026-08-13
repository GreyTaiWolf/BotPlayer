# ADR-0019：Owner 手动只读 AI 审阅往返

- 状态：Accepted
- 日期：2026-08-11
- 相关：ADR-0012、ADR-0013、ADR-0015

## 背景

客户端赞助凭据允许 owner 在本地调用 Provider，但不能把服务器的世界权威、任意 prompt、
工具选择或 P5 执行能力带到该边界。P6-R1 需要一个可验证的最小纵切，用来证明 owner 本地
Provider 的请求、回传、过期和解绑都能失败关闭。

本文记录的 P6-R1 仅为已编码、Java 21/CI 待验证的窄审阅切片，不代表 P6、聊天、通用
Provider 或世界动作已经完成。

## 决策

只提供 `/botplayer ai review <name>`：发起者必须是该活跃 BotPlayer 的真实持久 owner，且
必须存在精确的本地 agent binding。服务端只投影同一 bot generation 的已完成
`ObservationSnapshot`：命令 Tick 本身或紧邻前一 Tick（年龄只能为 `0` 或 `1`，未来或
`>=2` Tick 一律拒绝）。投影保留该不可变快照原有的 `snapshot_id` 与 `tick`，不会为命令
临时读取世界刷新它；固定七行输入为 `snapshot_id`、`tick`、`dimension_id`、`health`、
`food`、`air`、`threat_count`。不发送坐标、背包、实体/方块文本、事件、聊天、记忆或任意
用户 prompt。

请求的 ContextAssembler、空 history/memory、固定 SYSTEM 规则、无 reasoning、40 tick TTL、
1.5 秒 timeout、`deepseek-chat` 和唯一的 `botplayer_review_snapshot` 零参数工具均为代码
常量。S2C DTO 以 `REVIEW_ONLY_V1` purpose 绑定并升级网络协议；物理客户端在读取本地凭据
前重建并验证完整契约。客户端只读取自己的 `review-only-v1.json` 中
`reviewOnly.enabled`（默认 `false`）；启用后才构造固定 `deepseek-chat`、`CHAT|TOOL_CALLS`、
至少 256 output tokens 的 review-only DeepSeek 工厂。该文件没有 endpoint、model、provider、
key、profile、tools 或 prompt 字段，也绝不经服务器同步。服务器不保存 key、endpoint 或客户端
profile。

服务端 gate 保留 owner、agent、generation、snapshot revision、nonce、TTL 和 Firewall。每个
R1 dispatch 同时登记精确 ticket；在完整关联校验后、通用 ToolCallCodec/Firewall 之前，gate
还必须只接受空 `outputText`、唯一 `botplayer_review_snapshot` 和字面量 `{}` 参数。任何自由
prose、第二个/其他工具或非精确参数都以 terminal `REVIEW_CONTRACT_REJECTED` 消费，不生成
proposal；只有 gate 消费完整关联后发出的 terminal receipt 才能删除同一 ticket。替换、过期、
死亡、retire、解绑和 shutdown 都关闭 gate、删除 ticket，并向 owner 发送精确取消。一个接受的
proposal 只产生无 prompt/nonce/arguments 的数字摘要，随后立即丢弃；绝不进入 `SkillRuntime`、
P5 adapter、Action 或世界执行路径。

## 被否决方案

### 方案 A：任意 owner prompt 或完整感知快照

否决。它会把坐标、背包、实体文本、聊天或记忆扩展为新的数据外发面，也无法为本地 Provider
重建固定输入。

### 方案 B：服务端 Provider、endpoint 或 credential profile 配置

否决。凭据必须仅在 owner 的物理客户端本地存储和使用；服务器不应选择 Authorization 目标。

### 方案 C：把 accepted tool proposal 编译为 P5 Skill 或 Action

否决。R1 仅验证只读运输和静态审阅。执行需要独立的 revision、ACL、资源、世界证据和 P5
设计决策，不能由 Provider 回传隐式获得。

## 兼容性、性能、安全与许可证影响

- 兼容性：purpose 新增到 S2C payload，因此协议版本升至 2；旧客户端不会加入该往返。
- 性能：每 bot 最多一个短 ticket；没有服务端 HTTP、AI scheduler、世界扫描或异步执行。
- 安全：未知/畸形 review dispatch、本地 binding 漂移、过期、nonce/revision 不符、自由 prose、
  非精确工具形状和 Firewall 拒绝都失败关闭。保存/解绑 binding 会在取消前推进该 bot 独立的
  本地 binding epoch；已交给 Minecraft 队列的旧完成即使复用相同 agent/profile 也不能再发
  C2S。关闭或重载本地开关会推进 client connection epoch、取消本地 session 并清空 factory，
  且不发送 C2S 控制包。诊断不渲染 nonce、prompt、模型正文或工具参数。
- 许可证：不新增运行时依赖；DeepSeek endpoint 仍固定在现有客户端 Provider 内。

## 迁移和回滚

R1 默认禁用本地 Provider；客户端启动和显式本地重载都会读取 `reviewOnly.enabled`。未启用或
无法读取严格 schema 时，服务器请求只会在客户端拒绝，不会使用凭据；保存/绑定 credential
也不会自动启用。若后续验证发现网络或 Provider 问题，可关闭本地开关、移除命令/dispatch 注册
或不安装工厂；现有 P5 Action/Skill 和 owner credential UI 不受影响。

## 验证方式

- 纯 Java：固定投影 grammar、Context/response 契约、purpose、ticket 精确消费与过期、gate
  terminal receipt（含自由 prose/畸形工具/replay）、默认关闭的本地 opt-in schema、客户端
  loopback fake Provider、review-only DeepSeek fixed allowlist、disable/reload 取消；
- NeoForge：需要在 Java 21 环境验证 payload codec、owner command、`0/1` Tick 已完成快照
  新鲜度与 death/retire/unbind/shutdown 清理；
- 审计：确认 P6-R1 路径没有 `AiRequestScheduler`、`SkillRuntime`、P5 adapter、Action 或世界
  执行调用。
