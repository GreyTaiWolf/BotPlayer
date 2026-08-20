# ADR-0039：施工站点空间租约适配器

- 状态：Accepted
- 日期：2026-08-20
- 相关：ADR-0015、ADR-0017、ADR-0029、ADR-0030、ADR-0033、ADR-0035、ADR-0036、ADR-0038

## 背景

ADR-0033/0035/0038 已将 exact `ConstructionSiteBinding`、有界 target evidence 与已加载 world 的只读 snapshot
分开。它们都不阻止两个 Skill 在同一施工区域并发运行；而建筑设计要求施工区、临时结构区和材料各自有
TTL 预留。

现有 `ResourceReservationService` 已是服务器 owner thread 的有界 TTL 权威表，负责容量、原子 multi-key
申请、过期、`releaseRun(...)` 与 `closeGeneration(...)`。它只按纯数据键比较，不能自行从一个 construction
bounds 推导保守的空间冲突。因此 P5D 需要一个很窄的 site adapter，而不能复制第二套租约生命周期或提前
接入 Action/Technique/Skill。

## 决策

### 1. exact binding 映射为最多八个固定空间 tile

新增 `building.site.ConstructionSiteLeaseService` 与不透明的 `ConstructionSiteLease`。它只接受完整
`(botId, botGeneration, skillRunId, ConstructionSiteBinding)`，并从 binding 已派生的 inclusive bounds 映射为
边长 32（当前 `Blueprint.MAX_AXIS_SPAN`）的固定 tile：每一轴用 `Math.floorDiv`，负坐标不截断为零。一个
最多跨两个 tile 的轴与其他两轴相乘，故一份 binding 最多申请 8 个 tile。

每个 tile 以现有 `ReservationKey.Kind.WORK_AREA` 和 `ReservationMode.EXCLUSIVE` 通过同一个
`ResourceReservationService.acquireAll(...)` 原子申请。若两个同 dimension bounds 相交，它们必共享至少一个
tile，因此不能同时取得该 adapter 的 lease；同 tile 但不相交的区域可能保守冲突，优先保证不漏锁。

dimension 保持原样作为 reservation key 的 `scope`，tile 坐标在 `subject`。不能截断、hash 或替换 dimension；
由于 `ResourceId` 的最大长度大于 `ReservationKey.scope` 的长度上限，超过该上限时 `acquire(...)` 明确返回
`DIMENSION_KEY_TOO_LONG`，不写任何 reservation。

### 2. cache 不是租约权威

adapter 只保存它亲自成功申请且所有 entry 都是 fresh `ACQUIRED` 的 opaque lease。相同 owner identity 重复申请
必须带 value-equal 的完整 binding（包含 siteId、full WorkPlan、anchor、derived bounds）才返回同一 lease；任何
漂移返回 `BINDING_MISMATCH`。如果共享表已经被外部相同 owner 占用、但 adapter 没有自己的 exact cache，则返回
`RESERVATION_STATE_UNTRACKED`，不把 raw token 猜成这一 binding 的许可。

每个 `isCurrent(...)`、`release(...)`、`renew(...)` 与后续正常 `acquire(...)` 都在 owner thread 懒检查所有
底层 token。TTL 过期，或外部 P5 runtime 调用已有 `releaseRun(...)`/`closeGeneration(...)` 后，cache 会被移除；
它不能凭 cache 继续授予使用权。只有 issuing adapter 的 exact-binding `renew(...)` 可以把其当前 cache 中的全部
token 交给 `ResourceReservationService.renew(...)`：所有底层 renewal 成功后才发布 replacement opaque lease，旧
lease 立即 stale。foreign/stale lease 或 binding drift 会在任何 raw renewal 前拒绝；若其他调用者绕过 adapter
直接 raw renew，旧 token 与 cache 不再相等，adapter 会懒失效并拒绝采用或再次 renew 那个 replacement。任何
unexpected raw renewal state 也只会丢弃 adapter cache，不会猜测或重建 binding。TTL、capacity、token ID、raw
renew/release、run 与 generation 清理仍由 `ResourceReservationService` 决定，本 adapter 不增加 persistence 或新的
lifecycle hook。

### 3. 这不是施工执行许可

本适配器不读取或加载 Minecraft world，不创建 `ConstructionSiteSurvey`、accepted site、freshness/protection/
ownership/human confirmation 结论，也不预留材料、临时脚手架区或 inventory quantity。它不调用或暴露
Action、Technique、Skill、navigation、lifecycle、AI/network 或任何方块写入。

未来 `ConstructWorkPackageSkill` 若实现，必须在自己的 owner-thread 安全边界持有并重新核验该 lease，然后仍按
`Skill → registered Technique → TechniqueActionPermit/Port → P2 Action` 经过独立的材料、候选、保护、玩家交互和
结果验证。该 lease 不能直接授权放置或覆盖真人方块。

## 被否决方案

### 方案 A：为每个 Blueprint target 单独申请普通 BLOCK key

否决。虽然能发现同一 target 的冲突，却不能覆盖 target 之间的施工体积，也会把最多 256 个 cell 直接放大为
256 个共享租约。固定 tile 以最多 8 个 key 给出无漏锁的保守 construction-area fence。

### 方案 B：截断或 hash 长 dimension 以适配 ReservationKey

否决。任何碰撞都会把不同 dimension 当作同一资源，或让同一 dimension 在不同调用中不稳定。超长 dimension
必须显式失败关闭，直到共享 key 合同有单独的兼容性决策。

### 方案 C：复制 ResourceReservationService 或直接让 Technique 持有 token

否决。复制会分叉 TTL、generation cleanup 与容量权威；Technique 又不能拥有任务级 reservation。adapter 只复用
既有服务，未来由 Skill runtime 在 run/generation 边界统一释放。

### 方案 D：在本 ADR 同时做 GroundPlace 或 world placement

否决。施工仍缺材料预留、站位/支撑/视线候选、保护和真人修改处理、checkpoint，以及注册 Skill/Technique
链路。将空间锁直接接 P2 会绕过 `Action → Technique → Skill` 分层。

## 兼容性、性能、安全与许可证影响

- 兼容性：新增两个 `building.site` 纯 Java 类和 JUnit contract tests；不改 Blueprint、work plan、Action、
  Technique、Skill、lifecycle、reservation service、Minecraft adapter、payload 或 persistence；
- 性能：一次申请最多 8 个既有 reservation key；每个 adapter 调用至多检查同样数量 token，不扫描 world/chunk；
- 安全：owner-thread、nonzero identity、positive generation、单调 tick、exact binding、untracked raw state 和
  overlong dimension 都 fail closed；
- 许可证：仅使用项目已有 JDK 值对象及 `ResourceReservationService`，不引入新依赖。

## 迁移和回滚

未来 construction Skill 必须与现有 P5 `ResourceReservationService` 使用同一 owner-thread 实例，并在 run/
generation 关闭时沿用已有 release 路径。若 future 需要精确非重叠并发、临时区、材料数量、跨重启或保护 ACL，
必须新增独立版本化合同。

回滚只删除这条未接线 adapter 与测试；不会迁移或改变 world、roster、inventory、reservation table、network 或
checkpoint 数据。

## 验证方式

- JUnit/isolated pure-Java model tests 覆盖同维度重叠、负坐标 fixed tile、最大 8 tile、dimension 隔离、原子冲突
  无半租约、exact idempotence/identity drift、overlong dimension、TTL、external `releaseRun`/
  `closeGeneration` lazy invalidation、exact-binding renewal 的 replacement/旧 lease fencing、foreign/stale/drift
  不触发 raw renewal、out-of-band raw renewal 的 lazy fail-closed，以及 wrong-thread/monotonic-tick rejection；
- 静态复核确认 adapter 只依赖 immutable site DTO 与现有 `skill.reservation`，不引用 Minecraft、Action、Technique、
  Skill、lifecycle 或 P6；
- 对应提交仍须在 Java 21 环境通过 `clean build`；不需要 GameTest（没有 Minecraft API），但未来真实 construction
  仍需独立 GameTest、dedicated-server、multi-bot、玩家/保护模组和 end-to-end 验证。P5D/P5 总退出门仍未关闭。
