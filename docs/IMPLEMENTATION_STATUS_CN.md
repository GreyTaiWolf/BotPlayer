# BotPlayer 当前实现状态

> 更新日期：2026-07-27
>
> 当前开发基线：`main`
>
> 当前阶段：P0/P1 开发中
>
> 发布状态：尚未发布，不建议用于重要存档

本文只记录已经进入代码并能够核对的事实。最终目标和未来任务见
[ARCHITECTURE_AND_ROADMAP_CN.md](ARCHITECTURE_AND_ROADMAP_CN.md)。

## 状态含义

| 标记 | 含义 |
|---|---|
| 已完成 | 代码已进入分支，并通过当前构建门禁 |
| 部分完成 | 主路径已实现，但持久化、异常场景或自动测试仍缺失 |
| 未实现 | 只有设计，不能在游戏中使用 |

## 工程基线

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| Minecraft 1.21.1 / NeoForge 21.1.244 / Java 21 | 已完成 | `gradle.properties`、Java toolchain |
| ModDevGradle 2.0.142 / Gradle 9.2.1 | 已完成 | `build.gradle`、Wrapper |
| 模组元数据和 Mixin 配置 | 已完成 | `neoforge.mods.toml` 模板、`botplayer.mixins.json` |
| GitHub Actions 构建门禁 | 已完成 | Java 21 `clean build` 与 JAR artifact |
| 客户端凭据单元测试 | 部分完成 | 已加入 `ClientCredentialStoreTest`；仍需干净 Gradle 环境确认 |
| GameTest 源集与首个测试 | 未实现 | 只有 run configuration |
| 静态检查、格式化和依赖锁 | 未实现 | 当前 CI 只有 `clean build`，没有独立格式任务 |
| 正式发布包 | 未实现 | 当前只有开发构件 |

## 真实玩家内核

| 能力 | 状态 | 证据或边界 |
|---|---|---|
| `BotServerPlayer extends ServerPlayer` | 已完成 | 没有自定义 `EntityType`，不继承 `FakePlayer` |
| `EmbeddedChannel` 虚拟连接构造 | 已完成 | `BotConnection` 满足首版非空 channel |
| bot 专用 packet listener | 部分完成 | 出站包丢弃已实现；发送回调、keepalive/teleport ack 和指标待补 |
| 登录保持 bot listener | 已完成 | `PlayerList.placeNewPlayer` 精确包装 |
| 重生保持 bot 类型 | 已完成 | `PlayerList.respawn` 精确包装 |
| 死亡完成确认 | 已完成 | `ServerPlayer.die` TAIL 只观察正常完成路径 |
| 跨重生 runtime handle | 部分完成 | 已保持 handle；generation 和旧引用检测未实现 |
| 稳定身份 | 部分完成 | roster 已持久保存 botId/player UUID；旧名字派生身份迁移、重命名和冲突回滚测试待补 |
| owner 记录 | 部分完成 | 创建者已持久化并用于客户端凭据配置；owner 管理、trusted/observer ACL 待补 |
| 真人/名字/UUID 冲突 | 部分完成 | 在线与 roster 冲突已检查；离线 profile/白名单/封禁冲突未完成 |
| 原版 playerdata | 部分完成 | 已检测旧数据并保留保存位置；完整回归测试未完成 |
| 生成事务回滚 | 部分完成 | 当前异常路径可清理；缺少故障注入 GameTest |
| 生命周期状态机 | 部分完成 | 具备生成、活动、死亡、重生、卸载；诊断和 generation 待补 |
| 持久 roster | 部分完成 | schema v1 保存规范名字、稳定 bot/player UUID、owner 与 `serverInstanceId`；autoload、自动恢复和迁移故障测试待补 |
| 原版死亡后自动重生 | 已完成 | 通过 `PERFORM_RESPAWN` 进入原版重生路径 |
| 维度切换 | 部分完成 | 代码路径已处理；三维度 GameTest 未完成 |
| 区块跟踪 | 部分完成 | 作为在线玩家并周期刷新；残留与长期负载未验收 |
| 停服清理 | 部分完成 | 幂等和逐 bot 异常隔离已实现；保存/恢复测试未完成 |
| 自动加载 roster | 未实现 | roster 已持久化，但重启后仍需管理员重新执行 spawn |

## 当前可用操作

| 操作 | 状态 | 说明 |
|---|---|---|
| `/botplayer spawn <name>` | 已完成 | 新 bot 出现在执行者位置；既有 playerdata 使用保存位置 |
| `/botplayer list` | 已完成 | 列出本次服务器运行期内的 bot 和生命周期状态 |
| `/botplayer remove <name>` | 已完成 | 从在线运行时卸载，不删除 playerdata |
| `/botplayer credentials <name>` | 已完成 | 只允许活动 bot 的持久 owner；不要求 OP，OP 也不能绕过 owner |
| 服务端配置 | 已完成 | 最大数量、Tab、自动重生、重生延迟、区块刷新、命令权限 |
| 客户端 API Key 管理 | 已完成 | owner 可在本地 GUI 创建/替换 credential profile、绑定/解绑 bot；profile 删除未实现 |
| 多 bot 共用一个 Key | 已完成 | 多个 bot 可引用同一 credential profile，但每个 bot 使用独立 agentId；当前没有模型状态 |
| 服务端 agent binding | 部分完成 | 只保存 botId↔agentId 运行时关系；owner 退出、bot 卸载或停服时清除 |
| 空手右键查看背包 | 未实现 | 已完成详细 P2 设计，代码尚未进入 |
| 移动、挖掘、放置、攻击 | 未实现 | 不应把生命周期内核误认为行动 AI |
| 聊天与 DeepSeek | 未实现 | 客户端只保存/绑定 Key；没有 Provider、HTTP、对话、规划或工具调用 |

当前配置项与实际键名见 [CONFIGURATION_CN.md](CONFIGURATION_CN.md)。

## 当前明确缺口

这些项目是进入 P2 前的阻断项：

1. 完成虚拟连接发送回调、keepalive、teleport acknowledge 和长时间在线验证；
2. roster schema 迁移、owner 管理和 trusted/observer ACL；
3. 启动自动恢复、每 Tick 生成限流和 profile 冲突检测；
4. generation 机制，确保重生后的旧实例引用全部失效；
5. 创建、重复生成、退出、保存、死亡、重生、维度和停服 GameTest；
6. PlayerList、Level、连接、区块跟踪和 playerdata 的残留诊断；
7. 代码格式、静态检查及测试进入 CI。

## 后续阶段状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P2 | 原子动作、玩家输入、容器事务、背包 GUI | 未实现 |
| P3 | 感知、语义事件、世界模型、玩家活动理解 | 未实现 |
| P4 | 导航、安全反射、动态重规划 | 未实现 |
| P5 | 技能运行时和第一条生存闭环 | 未实现 |
| P6 | DeepSeek、聊天、Tool Firewall、预算 | 未实现；客户端凭据基础不等于 Provider |
| P7 | 长期记忆、目标、承诺和恢复 | 未实现 |
| P8 | 模组 C0–C3 适配 | 未实现 |
| P9 | 多 bot 协作 | 未实现 |
| P10 | 性能、兼容、安全和正式发布硬化 | 未实现 |

## 下一批提交

1. 补齐虚拟连接协议闭环和诊断；
2. 加固 roster 身份迁移、owner 管理与服务器实例隔离；
3. 加入自动加载、生成限流和冲突拒绝；
4. 建立生命周期 GameTest fixture；
5. 验证 100 次生成—卸载和死亡—重生；
6. 再进入 P2 动作契约和背包 GUI。

## 当前验证方式

已经可用：

```bash
./gradlew --no-daemon clean build
```

开发运行：

```bash
./gradlew runClient
./gradlew runServer
```

待首个 GameTest 加入后：

```bash
./gradlew --no-daemon runGameTestServer
```

手工测试和构建不等于生命周期验收。只有相应 GameTest 与故障注入通过后，表中的
“部分完成”才能改为“已完成”。

## 客户端凭据的准确边界

- Key 只写入 owner 客户端的独立本地明文文件，优先原子替换并尽力收紧权限；
- 凭据文件是客户端游戏目录下的 `config/botplayer/credentials-v1.json`（默认启动目录
  通常是 `.minecraft`）；非 secret binding 文件是同目录 `bindings-v1.json`；
- Key 不进入命令、聊天、Minecraft payload、服务端、世界数据或日志；
- `(serverInstanceId, ownerUuid, botId)` 隔离不同服务器、owner 与 bot 的绑定；
- 一个 credential profile 可以被多个 bot 引用，但 agentId 按 bot 独立；
- 只有 roster 中持久 owner 可以配置；修改客户端文件不能改变服务端 owner；
- 当前支持创建/替换 profile 以及绑定/解绑；不支持删除 credential profile；
- owner 离线时，未来 client-sponsored LLM 不可用；
- 当前没有任何真实 API 请求，不能用这项功能测试 Key 是否有效，也不能让 bot 变智能。
