# ADR-0029：有界蓝图数据契约

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0015、ADR-0017、ADR-0023、ADR-0025

## 背景

P5D 的设计要求从可版本化蓝图、材料清单、施工图和有界工作包开始，但当前仓库没有
`GroundPlace`、placement candidate、施工 route、材料预留、站位、checkpoint 或 human override。
直接把 AI 或调用方给出的任意方块数组、`BlockEntity` NBT 或物品映射接到已有 P2 放置动作，会跳过
材料、世界差异、原版消费时序和精确安全 handoff。

因此先建立一个可单独审阅的纯 Java 输入边界。它必须能为后续安全地表达小蓝图的目标状态，
又不能把“已保存的描述”伪装成“已获准建造”。

## 决策

### 1. 只引入 schema-v1 的有界、不可变 Blueprint DTO

新增 `building.blueprint` 的 `Blueprint`、`BlueprintCell`、`BlueprintOffset`、`BlueprintBounds`、
`BlueprintContentHash` 与 planned-block requirement DTO。当前 schema 只接受 `1`，并且强制：

- 非零 `blueprintId`、正 `revision`、1–256 个 cell；
- 每个 relative offset 的每轴绝对值至多 64，派生 bounds 的每轴 span 至多 32；
- 每个 offset 在同一 Blueprint 中唯一，输入 cell 按 `(x, y, z)` 排序并防御性复制；
- `BlockStateFingerprint` 继续提供受限、规范排序的 block id/property 表达；
- placement role、replace policy 与 permanent/temporary class 都是枚举元数据，未知值不能静默
  作为现有 schema-v1 处理。

schema-v1 刻意不含 design document 中完整 PT4 Blueprint 的 modules 或
`PostPlacementSemantic`；两者都需要独立 schema/adapter 决策，不能作为未类型化扩展塞入 cell。

这些限制拒绝无限、远距或坐标溢出的方块数组；较大模块只能在将来的 schema/work-package
合同中显式增加，而不能绕开本阶段上限。

### 2. content hash 只表示规范内容

`Blueprint.contentHash()` 对 schema、canonical cell 顺序、offset、block id、排序 properties、role、
replace policy 和 material class 做带长度前缀 UTF-8 编码的 SHA-256。它不依赖 `toString()`、默认
字符集或输入 List/Map 顺序。

`blueprintId`、`revision`、派生 bounds 和派生 requirements 有意不参与 content hash：相同 schema
内容可跨 identity/revision 去重；revision 本身仍是 future site/work-package recheck 的独立版本围栏。

### 3. planned-block requirements 不是 inventory reservation

`plannedBlockRequirements()` 仅按 `(desired blockId, PERMANENT|TEMPORARY)` 聚合目标 cell。block id
不是 item id：有些方块没有可直接手持的 `BlockItem`，放置状态也可能依赖上下文。当前 DTO 不推导、
不预留也不消耗任何 `ItemStack`。将来的 registry-aware resolver、真实背包/容器预留和原版消费验证
必须单独获得合同与测试。

### 4. 绝不在 normal Blueprint 内写入 NBT 或世界

普通 cell 只包含 offset、`BlockStateFingerprint` 与静态元数据；没有 `BlockEntity`、NBT、任意 payload、
post-placement callback、Minecraft runtime object 或 world position。门、箱子文本、容器填充和机器配置
若被支持，必须在未来通过已注册的语义 adapter 和真实菜单/交互单独处理。

本 ADR 不注册 Technique route，不调用 `AimAndPlaceBlock`，不读写 Minecraft 世界，不选择 site，
不解析网络/AI 输入，不创建 work package，也不接 Action、Skill、Navigation、Safety、Lifecycle、
Network、Client、Scheduler 或红石执行。

## 被否决方案

- **接受任意 `List<BlockPos, BlockState>`**：没有体积、坐标或重复约束，无法作为未来异步编译和
  work-package 的安全输入。
- **把 block id 当成材料 item id**：会把 registry/`BlockItem` 语义、特殊方块和背包验证错误地
  简化为字符串相等。
- **将 BlockEntity NBT 或任意 post-placement JSON 塞进 cell**：会形成未审计的世界/容器写入通道。
- **现在接现有 `AimAndPlaceBlock`**：站位、支撑、材料、保护、ownership、checkpoint、human override
  和 manager-owned safety handoff 尚未存在。

## 兼容性、性能、安全与许可证影响

- 兼容性：新增独立纯 Java package，不更改 Minecraft payload、持久化格式、配置或既有动作接口。
- 性能：每个 Blueprint 最多 256 cell；排序、hash 和聚合均有固定上界，不新增线程、队列或扫描。
- 安全：未知 schema、重复/超限 offset、非正 revision 与非规范 hash 全部 fail-closed；DTO 不包含
  world authority、NBT、inventory runtime 或 Action ingress。
- 许可证：仅使用已有项目 DTO 与 JDK 标准库，不引入第三方代码或依赖。

## 迁移和回滚

当前没有 Blueprint 持久化、网络编解码或已运行 construction state，因此没有在线数据迁移。未来如需
添加字段、扩大上限或改变 hash 语义，必须引入新 schema version、明确旧版本转换或拒绝策略，并新增
ADR；不得把未知 schema 当作 v1 猜测处理。回滚本提交只移除未引用的纯数据合同，不影响任何世界状态。

## 验证方式

- 纯 JUnit：无序 cell/property 输入的 canonical hash；每个语义字段的 hash drift；bounds 和
  planned-block aggregation；zero UUID、schema/revision、null/empty/duplicate、offset/span/cell 上限与
  hash 格式拒绝；List 防御复制；公开 DTO 不含 Minecraft/NBT/ItemStack runtime 类型。
- 静态边界：新 package 不引用 `BotActionRuntime`、`WorldInteractionAction`、`GroundPlace` 或
  `net.minecraft` 类型。
- Java 21 自动门：对应提交仍必须通过 GitHub Actions 的 `clean build`、JUnit 和
  `runGameTestServer`；本地缺少可下载 Gradle/Java 21 时不得把静态检查写成自动通过。
- 后续实机：本 ADR 本身没有 Minecraft 生产路径。只有未来实现真实 placement 后，才需分别验收
  客户端、专用服、保护模组、多 Bot soak 和材料/BlockState/临时结构故障矩阵。
