# BotPlayer 更新日志

本文件记录已经进入仓库的变化。未来路线、想法和未完成任务不写成已发布功能；它们统一放在
[架构与路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md)。

## Unreleased

### 文档

- 重写 README，明确当前可用与不可用能力；
- 新增文档总目录、安装使用、配置和开发指南；
- 新增逐项原版能力矩阵和 1.0 发布门槛；
- 新增贡献规范和安全策略；
- 重写当前实现状态，区分已实现、部分完成和未实现；
- 记录虚拟连接仍缺发送回调、keepalive/teleport ack 和长时间在线验证；
- 补充当前真实配置键、命令语义、开发构件安装边界和排错；
- 扩充 ADR 索引及第三方研究/许可证边界；
- 明确 DeepSeek、API Key、背包 GUI、动作和记忆均尚未实现。
- 明确临时名称 UUID 的大小写语义、审查分支过渡规则和双端开发测试边界。

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
