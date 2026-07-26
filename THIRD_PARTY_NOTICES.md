# Third-Party Notices

更新日期：2026-07-26

## 当前代码来源声明

BotPlayer 当前分支没有复制或内嵌下列研究项目的源代码、资源、模型、提示模板或数据集。
这些项目仅用于理解公开行为、划分能力和设计测试场景。

用户指定的旧 `FakeAiPlayer` 仓库只允许参考“空手右键打开 bot 背包”的产品交互；
其他 AI 架构和实现不作为 BotPlayer 的代码来源。

## 研究参考

| 项目 | 固定 commit 或参考入口 | 本项目研究内容 | 当前代码处理 |
|---|---|---|---|
| [GreyTaiWolf/FakeAiPlayer](https://github.com/GreyTaiWolf/FakeAiPlayer) | `b1a0597a21a26f054784b5d1284343aae28c59f9` | 仅空手右键背包交互 | 未复制代码 |
| [Fabric Carpet](https://github.com/gnembon/fabric-carpet) | `6f607be9f353f0244e1c0f2053f319b99affada6` | 服务端玩家与动作包思路 | 未复制代码 |
| [SiliconeDolls](https://github.com/Anvil-Dev/SiliconeDolls) | `439d9aae7665df99bfd4a742afc928d72aff0ae0` | NeoForge 生命周期与驻留问题 | 未复制代码 |
| [Mineflayer](https://github.com/PrismarineJS/mineflayer) | 2026-07-26 访问默认分支 | 能力分类、插件和任务边界 | Node.js 代码不进入核心 |
| [Baritone](https://github.com/cabaletta/baritone) | 2026-07-26 访问默认分支 | 分层寻路、成本和动态重算 | 不内嵌其源码 |
| [Voyager](https://github.com/MineDojo/Voyager) | 2026-07-26 访问论文与公开仓库 | 技能库、反馈和验证循环 | 不执行模型生成脚本 |
| [Mindcraft](https://github.com/mindcraft-bots/mindcraft) | 2026-07-26 访问默认分支 | 对话、代理循环和模型抽象 | 不采用任意代码执行 |
| [CraftAssist](https://github.com/facebookresearch/craftassist) | 2026-07-26 访问默认分支 | Dialogue、Task、Memory 分层 | 以 Java 独立设计 |
| [Project Malmo](https://github.com/microsoft/malmo) | 2026-07-26 访问默认分支 | 观察—动作—成功条件 | 仅作测试思想参考 |
| [MineDojo](https://github.com/MineDojo/MineDojo) | 2026-07-26 访问默认分支 | 环境观察、任务定义与评测场景 | 仅作测试设计参考 |

完整研究边界见
[架构文档第 22 节](docs/ARCHITECTURE_AND_ROADMAP_CN.md#22-许可证与参考边界)。

## 构建与运行依赖

工程文件以
[NeoForge 1.21.1 ModDevGradle MDK `3e2e23df8e18c7e39c0bcb007fae8b3f423246e1`](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/commit/3e2e23df8e18c7e39c0bcb007fae8b3f423246e1)
为基线生成并在本仓库修改。MDK 属于实际工程模板来源，和上表“只研究、未复制代码”的
项目分开记录。

直接版本声明位于 `build.gradle`、`settings.gradle` 和 `gradle.properties`。当前主要基线为：

- Minecraft 1.21.1；
- NeoForge 21.1.244；
- ModDevGradle 2.0.142；
- Foojay Toolchains Resolver 1.0.0；
- Gradle Wrapper 9.2.1；
- Parchment mappings 2024.11.17 for Minecraft 1.21.1。

这些组件及其传递依赖继续服从各自的许可证。本文件不是完整的传递依赖许可证清单。
正式发布前必须生成、人工核对并随发布物提供完整清单。

## 引入第三方内容的门槛

新增依赖、复制代码、资源、数据集、模型文件或提示模板前必须：

1. 记录来源 URL、版本或 commit；
2. 确认许可证允许当前使用和分发方式；
3. 记录复制、修改或仅研究的边界；
4. 保留要求的版权头、许可证文本和 NOTICE；
5. 评估与 MIT 主项目的兼容性；
6. 更新本文件、构建清单和相关 ADR；
7. 在 CI 或发布流程中生成依赖报告。

无明确许可证不等于可以复制；这类项目只能用于观察公开行为和独立设计。
