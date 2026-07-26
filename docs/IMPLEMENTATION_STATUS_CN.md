# BotPlayer 当前实现状态

> 更新日期：2026-07-26
>
> 当前分支：`agent/p0-p1-server-player-kernel`
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
| 稳定身份 | 部分完成 | UUID 由小写名称确定性派生；仅大小写变化保持 UUID，其他改名产生新身份；没有重命名约束或迁移 |
| owner 记录 | 部分完成 | 仅在本次 runtime handle 临时保存，未持久化也未用于 ACL |
| 真人/名字/UUID 冲突 | 部分完成 | 在线冲突已检查；离线 roster/profile 冲突未完成 |
| 原版 playerdata | 部分完成 | 已检测旧数据并保留保存位置；完整回归测试未完成 |
| 生成事务回滚 | 部分完成 | 当前异常路径可清理；缺少故障注入 GameTest |
| 生命周期状态机 | 部分完成 | 具备生成、活动、死亡、重生、卸载；诊断和 generation 待补 |
| 原版死亡后自动重生 | 已完成 | 通过 `PERFORM_RESPAWN` 进入原版重生路径 |
| 维度切换 | 部分完成 | 代码路径已处理；三维度 GameTest 未完成 |
| 区块跟踪 | 部分完成 | 作为在线玩家并周期刷新；残留与长期负载未验收 |
| 停服清理 | 部分完成 | 幂等和逐 bot 异常隔离已实现；保存/恢复测试未完成 |
| 自动加载 roster | 未实现 | 重启后需管理员重新执行 spawn |

## 当前可用操作

| 操作 | 状态 | 说明 |
|---|---|---|
| `/botplayer spawn <name>` | 已完成 | 新 bot 出现在执行者位置；既有 playerdata 使用保存位置 |
| `/botplayer list` | 已完成 | 列出本次服务器运行期内的 bot 和生命周期状态 |
| `/botplayer remove <name>` | 已完成 | 从在线运行时卸载，不删除 playerdata |
| 服务端配置 | 已完成 | 最大数量、Tab、自动重生、重生延迟、区块刷新、命令权限 |
| 空手右键查看背包 | 未实现 | 已完成详细 P2 设计，代码尚未进入 |
| 移动、挖掘、放置、攻击 | 未实现 | 不应把生命周期内核误认为行动 AI |
| 聊天与 DeepSeek | 未实现 | 当前没有任何 API Key 读取或外部请求 |

当前配置项与实际键名见 [CONFIGURATION_CN.md](CONFIGURATION_CN.md)。

## 当前明确缺口

这些项目是进入 P2 前的阻断项：

1. 完成虚拟连接发送回调、keepalive、teleport acknowledge 和长时间在线验证；
2. `BotRosterSavedData`、稳定 `botId`、owner/ACL 和 schema 迁移；
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
| P6 | DeepSeek、聊天、Tool Firewall、预算 | 未实现 |
| P7 | 长期记忆、目标、承诺和恢复 | 未实现 |
| P8 | 模组 C0–C3 适配 | 未实现 |
| P9 | 多 bot 协作 | 未实现 |
| P10 | 性能、兼容、安全和正式发布硬化 | 未实现 |

## 下一批提交

1. 补齐虚拟连接协议闭环和诊断；
2. 实现 roster `SavedData`、身份迁移和 owner 基础字段；
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
