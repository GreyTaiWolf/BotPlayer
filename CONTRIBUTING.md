# 参与 BotPlayer 开发

感谢参与 BotPlayer。当前项目处于真实服务端玩家内核阶段，可靠生命周期和测试优先于快速
堆叠 AI 演示功能。

## 开始之前

1. 阅读 [README](README.md) 和
   [当前实现状态](docs/IMPLEMENTATION_STATUS_CN.md)；
2. 涉及核心设计时阅读
   [架构路线图](docs/ARCHITECTURE_AND_ROADMAP_CN.md) 与
   [ADR](docs/adr/README.md)；
3. 大型功能、数据格式、Mixin、AI 权限或第三方依赖先建立 Issue/设计讨论；
4. 不在 Issue、PR、日志或截图中提交 API Key 和私人世界数据。

构建环境和命令见 [开发指南](docs/DEVELOPMENT_CN.md)。

## 接受的贡献范围

- P0/P1 生命周期、持久化、诊断和 GameTest；
- P2 原子动作与安全背包；
- 文档、测试、错误信息和可观测性；
- 与路线图一致、可以独立验收的后续接口；
- 明确许可证来源的兼容性研究。

不接受：

- 把 bot 改成自定义 Mob 或换皮实体；
- 为了展示效果直接改方块、NBT 或凭空改物品；
- 在 Tick 主线程同步调用 HTTP、数据库或长计算；
- 执行 LLM 生成的代码、脚本、命令或任意 URL；
- 把 API Key 放入聊天命令、客户端或世界文件；
- 未说明来源的复制代码、模型、材质或提示模板；
- 没有上限、取消和失败恢复的自动化。

## 分支与提交

- 从当前完整实现的最新基线创建 `agent/<主题>`；P0/P1 审查 PR 合并前以
  `agent/p0-p1-server-player-kernel` 为基线，合并后统一以 `main` 为基线；
- 保持改动范围单一，不混入无关格式化；
- 推荐提交格式：`类型: 中文摘要`；
- 常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`chore`；
- 大型或高风险功能先开 Draft PR。

示例：

```text
test: 增加服务端玩家死亡重生 GameTest
docs: 补充当前配置与安装边界
```

## 代码要求

- Java 21，UTF-8；
- 保持服务器权威和主线程访问规则；
- 使用 ID/DTO 跨线程，不持有异步 Minecraft 活动对象；
- 生命周期操作必须幂等并考虑失败回滚；
- 重生感知代码通过 runtime handle/generation 获取当前实例；
- 所有集合、扫描、重试、队列和时间必须有上限；
- 世界副作用有类型化结果、失败码和真实状态验证；
- 未知状态应失败或澄清，不能猜测成功；
- 日志不得包含 secret、Authorization、完整私聊或无关个人数据。

Mixin 必须保持最小、精确和版本隔离。新增行为注入前先提交 ADR，说明为什么事件、子类或
访问器无法解决。

## 测试要求

提交前至少执行：

```bash
git diff --check
./gradlew --no-daemon clean build
```

涉及已建立 GameTest 的功能时还要执行：

```bash
./gradlew --no-daemon runGameTestServer
```

PR 中必须区分：

- 已编码；
- 已通过编译/打包；
- 已通过自动测试；
- 已手工在客户端或专用服务器验证。

不能用 GitHub Actions `clean build` 代替游戏行为测试。

## 文档要求

改变用户行为时同步更新：

- `README.md` 的当前能力摘要；
- `docs/IMPLEMENTATION_STATUS_CN.md`；
- 对应安装、配置或开发说明；
- `CHANGELOG.md`；
- 必要时更新总架构和 ADR；
- 新依赖或复制内容更新 `THIRD_PARTY_NOTICES.md`。

所有 Markdown 链接应可用，未来能力必须明确标记为“计划”或“未实现”。

## PR 清单

- [ ] 变化范围清楚，没有无关文件；
- [ ] 说明了原因和用户/开发者影响；
- [ ] 权限、线程、失败和回滚边界明确；
- [ ] 测试覆盖成功、拒绝、取消、重复和异常路径；
- [ ] 构建与相关测试通过；
- [ ] 没有 secret 或个人数据；
- [ ] 文档和 CHANGELOG 已更新；
- [ ] 第三方来源与许可证已记录；
- [ ] 没有把路线图目标描述成当前能力。

## 许可证

提交代码即表示你有权按本项目 [MIT License](LICENSE) 提供该贡献。第三方内容仍遵守其
原始许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
