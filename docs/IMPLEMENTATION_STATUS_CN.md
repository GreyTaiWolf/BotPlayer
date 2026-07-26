# BotPlayer 当前实现状态

更新日期：2026-07-26

本文只记录已经进入代码的事实。完整目标、类职责和阶段验收见
[ARCHITECTURE_AND_ROADMAP_CN.md](ARCHITECTURE_AND_ROADMAP_CN.md)。

## 已落地

- [x] Minecraft 1.21.1 / NeoForge 21.1.244 / Java 21 工程基线
- [x] ModDevGradle 2.0.142 / Gradle Wrapper 9.2.1
- [x] `BotServerPlayer extends ServerPlayer`
- [x] `EmbeddedChannel` 虚拟连接
- [x] 丢弃 bot 专属客户端出站包的 `BotGamePacketListener`
- [x] `PlayerList.placeNewPlayer` 精确构造器包装
- [x] `PlayerList.respawn` 精确构造器包装
- [x] 跨重生复用的 `BotRuntimeHandle`
- [x] 名字不可变阶段的稳定命名空间 UUID（后续迁移到 roster 存储身份）
- [x] 服务器线程生命周期状态与最大在线数
- [x] 原版死亡流程之后，通过 `PERFORM_RESPAWN` 走原版重生
- [x] 程序化瞬移和周期性区块跟踪刷新
- [x] 受权限保护的 `spawn`、`remove`、`list` 命令
- [x] 停服幂等卸载入口
- [x] GitHub Actions Java 21 构建门禁

## 当前明确缺口

这些缺口意味着当前版本仍是开发骨架：

- [ ] 稳定 roster、owner/ACL 与 `SavedData` 持久化
- [ ] 已实现检测既有 playerdata 时不覆盖保存位置；仍待 GameTest 验收
- [ ] 创建/登录失败的完整事务回滚
- [ ] 退出、重复登录、死亡和维度切换的完整 GameTest
- [ ] 生命周期 generation 与所有旧实例引用失效验证
- [ ] 空手主手右键背包、单 viewer 写锁及客户端 screen
- [ ] 原子玩家动作与输入控制器
- [ ] 感知、世界事件和玩家活动理解
- [ ] 导航、安全反射、战斗和建造
- [ ] 内部技能运行时与外部声明式技能包
- [ ] DeepSeek provider、工具防火墙和预算
- [ ] 分层记忆、长期目标和模组适配

## 下一批提交

1. 完成稳定身份、roster SavedData、autoload 和 profile 冲突检测。
2. 增加生命周期 GameTest；验证死亡重生后仍是 `BotServerPlayer`。
3. 验证下界/末地、长距离瞬移、`/kick` 和停服保存。
4. 实现背包会话锁与 1.21.1 menu/screen。
5. 建立动作接口后才开始路径、技能和 AI 接入。

## 当前验收命令

```bash
./gradlew --no-daemon clean build
```

加入首个 GameTest 后追加：

```bash
./gradlew --no-daemon runGameTestServer
```
