# BotPlayer 安全策略

## 支持状态

BotPlayer 当前没有正式稳定版本。

| 版本 | 安全更新 | 用途 |
|---|---|---|
| 默认 `main` 与当前功能分支 | 尽力修复 | 仅开发测试 |
| `0.1.0-alpha.1` | 尽力修复开发中的严重问题 | 尚未正式发布；不用于重要存档或公网生产服 |
| 未来正式 Release | 发布时说明 | 以对应发布说明为准 |

开发构件通过编译不代表已经完成安全审计、权限兼容或长期运行验证。

## 报告安全问题

不要在公开 Issue、Discussion、PR、聊天或截图中发布：

- API Key、Authorization header 或环境变量值；
- 可直接利用的未修复漏洞细节；
- 包含私人聊天、玩家身份或世界秘密的完整数据；
- 服务器地址、访问令牌或私有日志。

优先使用仓库 **Security** 页面提供的私密漏洞报告入口（如果仓库已启用）。如果没有私密
入口，请通过仓库所有者的 GitHub 资料请求一个私密联系方式；首次消息只说明问题类别和
受影响版本，不发送 secret。

安全报告建议包含：

- 受影响 commit/版本；
- Minecraft、NeoForge、Java 和服务端类型；
- 最小复现条件；
- 影响范围；
- 已脱敏日志或最小测试；
- 建议修复方向（可选）。

一般崩溃、功能缺失和文档问题可以使用公开 Issue，但仍需先脱敏。

## 凭据泄漏处理

如果 DeepSeek 或其他 Key 已经出现在聊天、日志、截图、提交历史或 Issue：

1. 立即在对应提供商后台撤销 Key；
2. 创建权限和额度尽可能小的新 Key；
3. 清理公开内容，但不要把“删除内容”当成撤销凭据；
4. 检查调用记录和异常费用；
5. 报告泄漏路径，以便修复脱敏和输入边界。

当前没有把 Key 放进命令参数的合法入口。客户端本地凭据 Screen 是唯一的 Key 写入入口；
默认关闭的 P6-R1 owner 只读审阅路径只有在本地显式启用时才会构造固定 Provider，不能聊天、
规划或执行世界动作，且仍待 Java 21/CI 验证。Key 只写入客户端游戏目录的
`config/botplayer/credentials-v1.json`（默认启动目录通常是 `.minecraft`）。bot/agent 绑定写入
同目录的 `bindings-v1.json`，后者不包含 Key。
凭据文件当前是明文存储，写入时优先原子替换（文件系统不支持时退化为同目录覆盖）并
尽力收紧文件权限；它不是加密或操作系统密钥库。不要将这两个文件加入支持包、云同步、
截图、仓库或世界配置。

服务器、服主和其他玩家不会通过 BotPlayer payload 得到 Key，但本机恶意软件、同一系统
账户、错误备份或主动分享本地文件仍可能造成泄漏。`.gitignore` 不是 secret 管理，也不能
撤销已经泄漏的凭据。

## 永久安全边界

后续 AI 功能必须遵守：

- Key 只在持久 owner 客户端的独立本地凭据存储中输入、读取、创建或替换；当前界面支持
  bot 绑定/解绑，但不支持删除 credential profile；
- Key 不进入聊天或命令参数、Minecraft payload、服务端、世界 NBT、SavedData、playerdata、
  记忆库、普通日志或诊断；
- 普通客户端 TOML 只保存非 secret 偏好；不得把明文本地文件宣传为加密；
- 一个 credential profile 可以由多个 bot 引用，但每个 bot 使用独立 agentId 和状态；
- 本地绑定不授予权限，只有服务端 roster 的持久 owner 可以配置或建立未来 AI 会话；
- 未来使用该 Key 的 Provider HTTP 在客户端执行；服务端把返回结果视为不可信并重新校验；
- owner 离线时 client-sponsored LLM 不可用；
- 服务端 active agent binding 在 owner 退出、bot 卸载或停服时清除；
- LLM 只能提出受 schema 限制的高层计划；
- LLM 不能运行代码、脚本、服务器命令、文件访问或任意 HTTP；
- bot 身份、owner、ACL、风险上限和工具白名单由服务器绑定；
- 通用 C2S proposal 必须先匹配 server-held 的完整 immutable ledger correlation，才可进入 gate；
  gate terminal 只可 exact-close 同一 binding，分歧保持 fail-closed，不能按 botId 猜测清理；
- 所有破坏、放置、攻击、容器和物品动作重新做权限和世界状态校验；
- 迟到、重复、越权、未知和超范围调用默认拒绝；
- 告示牌、书本、聊天、物品名和模组文本都视为不可信输入；
- 敏感数据遵循最少收集、最短保留和可删除原则。

完整威胁模型见
[架构文档第 21 节](docs/ARCHITECTURE_AND_ROADMAP_CN.md#21-安全滥用与隐私威胁模型)。

## 当前已知安全缺口

- 已有持久 owner，但 trusted/observer ACL 尚未实现；
- 一个原版权限等级仍控制基础管理命令；凭据配置额外要求持久 owner；
- 没有保护模组兼容矩阵；
- 严格 Tool Firewall 已编码，但尚未接通 AI→技能计划或世界动作执行；
- 客户端凭据文件没有加密或系统 keychain 保护；
- 已编码的 P6-R1 固定本地 Provider/HTTP 往返默认关闭，尚无 Java 21/NeoForge 端到端模型响应安全验证；通用聊天、计划和世界执行尚未接通；
- ADR-0028 的通用 proposal review 仅是未接线 coordinator 的本地合同；没有 generic network/client/
  scheduler lifecycle，也未触及 world execution；
- 没有生命周期故障注入 GameTest；
- 没有正式依赖漏洞扫描和发布签名；
- 没有生产环境支持承诺。

这些限制也是当前版本禁止用于重要存档和公网生产服务器的原因。
