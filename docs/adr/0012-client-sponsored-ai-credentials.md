# ADR-0012：客户端赞助的 AI 凭据与每 bot 独立智能体

- 状态：Accepted
- 日期：2026-07-27
- 取代：ADR-0004

## 背景

ADR-0004 原本规定“客户端只负责 UI，AI 与 secret 在服务端”。这适合无人值守的专用
服务器，但要求服主能够接触并保管玩家的 API Key，也不能满足“玩家自己的 Key 不经过
服务端”的产品要求。

BotPlayer 还需要同时满足：

- 只有 bot 的持久 owner 可以为它选择凭据；
- 一个 Key 可以供同一玩家的多个 bot 使用；
- 多个 bot 仍然是彼此独立的智能体；
- 服务端继续权威控制身份、权限、世界事实和动作；
- 当前 P0/P1 阶段不能把保存 Key 写成已经接入 DeepSeek。

## 决策

### 凭据位置

API Key 只保存在运行玩家客户端的独立本地凭据存储中。当前文件是
`config/botplayer/credentials-v1.json`；不含 Key 的绑定在同目录 `bindings-v1.json`。
实现使用本地明文文件，优先原子替换（文件系统不支持时退化为同目录覆盖），并尽力收紧
POSIX 目录 0700/文件 0600 权限；它不声称提供加密、操作系统密钥库或抵御本机恶意软件。

原始 Key 及任何可还原值不得进入：

- `/botplayer` 命令参数或聊天；
- Minecraft 自定义 payload；
- 服务端内存中的凭据对象；
- SERVER 配置；
- 世界 NBT、SavedData、playerdata 或未来 SQLite；
- 普通日志、崩溃报告、诊断导出或版本控制。

Key 只通过客户端本地 Screen 输入。服务端与客户端之间只交换完成授权和命名空间隔离所需
的非 secret 标识与状态。

### 身份与所有权

服务端 roster 是 `botId`、稳定 player UUID、持久 owner 与 `serverInstanceId` 的权威源。
客户端不得通过编辑本地文件改变 owner。

客户端绑定以 `(serverInstanceId, ownerUuid, botId)` 为隔离键。只有服务端确认当前玩家是
活动 bot 的持久 owner 时，界面才允许创建或修改绑定。`serverInstanceId` 防止两个不同
服务器碰巧使用同一个 botId 时错误复用绑定。

客户端本地模型分离：

- `credentialProfileId`：用户选择、经格式校验且不含 secret 的本地凭据引用；
- `agentId`：每个 bot 独立、由客户端生成或安全复用的随机智能体身份；
- `botId`：服务端权威业务身份。

一个 `credentialProfileId` 可以被多个 bot 绑定，但每个 bot 拥有不同的 `agentId`，以后
的对话、目标、预算、请求序列和状态不得因共用 Key 而合并。Key、Key 指纹和末四位都不能
充当 agentId。

### 当前范围与未来请求流

> 本节记录本 ADR 最初的 credential/binding 基线。后续 P6-R1 的默认关闭、本地 opt-in
> review-only HTTPS 例外由 [ADR-0019](0019-owner-manual-review-only-ai-round-trip.md) 定义；
> 它不改变本节对通用 bridge 和世界权威的约束。

本次只建立持久 owner/`serverInstanceId`、客户端 GUI、本地凭据 profile 和每 bot 绑定
基础设施：

- 不创建 DeepSeek Provider；
- 不发送 HTTP 请求；
- 不聊天、不规划、不调用工具；
- 不使 bot 获得新的游戏能力；
- 不改变 ADR-0010“P0–P2 通过前不接 DeepSeek”。

当前 GUI 支持创建/替换 credential profile、绑定和解绑 bot。相同 profile ID 下输入新 Key
会替换所有引用它的 bot 共用的本地 Key；解绑只删除该 bot 的 binding，保留 profile 和
Key。当前不提供 credential profile 删除。

服务端只保存当前在线期间的 botId↔agentId active binding。owner 退出、bot 卸载或停服时
清除；客户端 binding 与 agentId 可以保留，下一次重新授权绑定。

未来使用这个 Key 时，Provider HTTP 必须在 owner 客户端执行。服务端只可发送经过最小化
的请求 DTO；客户端返回的模型结果一律是不可信建议。服务端必须重新校验会话、owner、
botId、nonce、snapshot/plan/world revision、deadline、schema、风险和工具权限，才能将其
交给确定性计划与动作系统。

owner 客户端离线时，client-sponsored LLM 不可用。服务端可以继续已经存在且获准的
确定性安全或任务逻辑，但不能继续发起需要该客户端 Key 的模型请求。若未来需要无人值守的
服务端托管凭据模式，必须另建 ADR，不能让本决策暗中扩张。

ADR-0004 中“客户端不是世界权威、普通动作由服务端校验”的原则继续保留；被取代的是
“AI 与 secret 必须只在服务端”的部署决定。

## 被否决方案

### 把 Key 放进 `/botplayer apikey <key>`

命令会经过服务端和聊天/命令处理链，还可能进入历史、日志与截图，违反 Key 不经过服务端
的要求。

### 把 Key 发给服务端后只保存内存

即使不落盘，Key 仍已经经过服务端、网络 payload 和可能的异常路径，不符合需求。

### 在每个 bot 配置中复制一份 Key

复制会扩大泄漏面，也错误地把凭据与智能体身份绑定。使用共享 credential profile 和
独立 agentId。

### 把普通明文文件称为安全加密存储

没有独立密钥或系统密钥库时，这只是混淆。当前实现诚实声明本机文件风险，并把目标限定为
“服务端永远得不到 Key”。

### 让客户端直接决定并执行世界动作

客户端和模型输出均不可信。所有身份、权限、计划接受和世界副作用继续由服务端权威校验。

## 兼容性、性能、安全与许可证影响

- 专用服务器仍要求参与配置与未来 AI 请求的 owner 安装客户端模组；
- 不安装客户端功能的玩家不能使用 client-sponsored AI，但服务端生命周期内核不应受影响；
- owner 离线意味着未来模型能力降级，这是隐私选择带来的明确取舍；
- 本地凭据文件应优先使用原子写入、最小权限和损坏安全失败；支持包不得自动收集它；当前
  尚未实现 credential profile 删除；
- 日志与异常必须做二次脱敏，测试使用明显的 fake key；
- Provider URL 必须受控，禁止把 Authorization 发送到任意地址；
- 若以后引入系统 keychain、加密库或 HTTP 依赖，必须单独审查许可证并更新
  `THIRD_PARTY_NOTICES.md`。

## 迁移和回滚

- 旧版本没有客户端凭据数据，不需要导入服务端 secret；
- 客户端绑定必须带 schema 版本与 `serverInstanceId`，未知版本安全拒绝，不能猜测迁移；
- owner 改变后，旧 owner 的本地文件可以保留私人 credential profile，但服务端必须拒绝
  旧 `(serverInstanceId, ownerUuid, botId)` 绑定继续建立会话；
- 删除 bot、解绑和未来的 credential profile 删除是三个不同操作。当前只实现解绑，且
  解绑保留 profile；以后实现 profile 删除时不得静默破坏其他 bot 的引用；
- 回滚到不支持本功能的版本时，服务端世界数据不包含 Key；客户端本地凭据文件由玩家显式
  保留或删除。

## 验证方式

- roster 保存/重启后 owner 和 `serverInstanceId` 不漂移，以及 owner/非 owner/无 owner
  的服务端拒绝路径，仍需 GameTest 或集成测试；
- 两个服务器上的同 botId 不会交叉绑定；
- 一个 credential profile 可以供多个 bot 使用，且 agentId 与状态彼此独立；
- 客户端单元测试覆盖保存/读取、创建/替换 profile、共享、绑定/解绑、跨服务器与 owner
  隔离、损坏/超限拒绝，以及 Key 不进入 binding 文件；
- 命令、聊天、payload、服务端配置、世界文件、日志和崩溃信息均不含 Key；
- 本 ADR 最初的 credential/binding 基线没有真实 HTTP 请求；后续 P6-R1 是单独、默认关闭的
  本地 opt-in HTTPS 例外。自动测试不使用真实 API Key。
