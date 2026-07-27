# BotPlayer 文档总目录

本文说明每份文档回答什么问题，以及发生冲突时以什么为准。

## 从哪里开始

| 读者 | 建议顺序 |
|---|---|
| 想了解项目 | 根目录 [README](../README.md) → [当前实现状态](IMPLEMENTATION_STATUS_CN.md) |
| 想安装测试 | [安装与当前用法](INSTALLATION_AND_USAGE_CN.md) → [配置说明](CONFIGURATION_CN.md) |
| 想参与开发 | 根目录 [AGENTS.md](../AGENTS.md) → [开发指南](DEVELOPMENT_CN.md) → [参与开发](../CONTRIBUTING.md) |
| 想理解整体设计 | [架构与 P0–P10 路线图](ARCHITECTURE_AND_ROADMAP_CN.md) |
| 想修改核心边界 | [架构决策记录](adr/README.md) → 总架构相关章节 |
| 想报告安全问题 | [安全策略](../SECURITY.md) |

## 文档职责

| 文件 | 负责 | 不负责 |
|---|---|---|
| [AGENTS.md](../AGENTS.md) | 开发代理阅读顺序、需求到源码路由、验证与文档同步规则 | 代替当前实现状态或总架构 |
| [README.md](../README.md) | 项目入口、当前能力摘要、快速构建 | 完整架构细节 |
| [IMPLEMENTATION_STATUS_CN.md](IMPLEMENTATION_STATUS_CN.md) | 当前代码真实状态、已知缺口、下一批任务 | 描述尚未实现的完整方案 |
| [ARCHITECTURE_AND_ROADMAP_CN.md](ARCHITECTURE_AND_ROADMAP_CN.md) | 最终设计、代码边界、P0–P10 任务与验收 | 宣称路线图已经实现 |
| [VANILLA_CAPABILITY_MATRIX_CN.md](VANILLA_CAPABILITY_MATRIX_CN.md) | 原版玩法逐项能力、成熟度和发布门槛 | 模组专用兼容承诺 |
| [INSTALLATION_AND_USAGE_CN.md](INSTALLATION_AND_USAGE_CN.md) | 当前开发构件的安装、命令和排错 | 正式版承诺 |
| [CONFIGURATION_CN.md](CONFIGURATION_CN.md) | 当前真实配置键、范围和安全要求 | 尚未实现配置的可用性承诺 |
| [DEVELOPMENT_CN.md](DEVELOPMENT_CN.md) | 构建、运行、代码结构、验证和提交要求 | 玩家操作手册 |
| [adr/README.md](adr/README.md) | 长期架构决定及变更方法 | 日常任务列表 |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献流程和完成标准 | 代替技术设计 |
| [SECURITY.md](../SECURITY.md) | 漏洞与密钥事件处理 | 一般功能问题 |
| [CHANGELOG.md](../CHANGELOG.md) | 已进入仓库的版本变化 | 未来承诺 |
| [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | 研究来源和第三方许可证边界 | 完整法律意见 |

## 事实优先级

文档出现冲突时按以下顺序处理：

1. 当前分支的实际代码和构建配置；
2. [当前实现状态](IMPLEMENTATION_STATUS_CN.md)；
3. 已接受 [ADR](adr/README.md)；
4. [总架构路线图](ARCHITECTURE_AND_ROADMAP_CN.md)；
5. README 和其他使用说明。

这不是允许文档长期冲突。发现冲突必须在同一提交中修正，并在
[CHANGELOG.md](../CHANGELOG.md) 记录会影响使用者的变化。

## 状态用语

- **已实现**：代码存在且通过当前构建门禁；
- **部分完成**：主路径存在，但异常场景、持久化或自动测试仍缺失；
- **已验证**：对应单元测试、GameTest 或明确手工场景已经通过；
- **计划/目标**：只在路线图中承诺开发方向，不能当作当前功能；
- **正式支持**：只有发布说明明确列出的版本和环境才能使用该词。

## 文档维护规则

每次功能提交至少检查：

1. README 的当前能力和命令是否仍正确；
2. 实现状态表是否需要移动状态；
3. 配置键、默认值或文件位置是否改变；
4. 路线图清单和验收条件是否需要更新；
5. 是否产生新的长期架构决定；
6. CHANGELOG 是否需要记录；
7. 是否增加第三方依赖、代码、资源或许可证义务；
8. 是否改变安全、隐私或 API Key 边界；
9. 所有相对链接和标题锚点是否有效。
10. 客户端凭据、owner、`serverInstanceId` 与每 bot agent 状态是否仍保持隔离；
11. 是否把凭据保存基础错误描述成 DeepSeek、聊天或 P6 已完成。

文档默认使用中文；代码符号、命令、路径、协议字段和错误码保留原文。
