package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.CowBreedingInventoryProof;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding.FeedRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding.Phase;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * P5B 原版牛繁殖的窄 action-mailbox handler。
 *
 * <p>每个节点只执行一次现有的 {@link WorldInteractionActionSpec.InteractEntity}。两节点
 * DAG 的第一步必须证明 bot 以默认组件的小麦喂饱第一头牛；第二步必须证明另一头牛消耗第二份
 * 小麦、两头父母进入原版 breeding cooldown，并出现一头新 UUID 的原版幼牛。这个 handler
 * 从不调用 {@code setInLove}、{@code setAge}、{@code addFreshEntity} 或背包写入。
 *
 * <p>它仍不是自动注册的 production feature：调用方必须显式注册
 * {@link VanillaCowBreeding#descriptor()} 与本 handler，才能把它接入具体 lifecycle。
 * 这样可避免在 P5 共享 runtime 合同尚未统一前绕过 lifecycle 的 owner/generation 边界。
 */
public final class MinecraftCowBreedingSkillNodeHandler
        implements SkillNodeHandler {
    /** 每次原版实体交互最多占用 80 tick；通常在一 tick 内完成。 */
    public static final int MAXIMUM_ACTION_TICKS = 80;
    /** 第二次喂食后只允许有限时间等待 vanilla BreedGoal 产生 child。 */
    public static final int MAXIMUM_BIRTH_OBSERVATION_TICKS = 100;
    /** 只扫描双亲附近的有限 box，拥挤环境拒绝而不是无界枚举实体。 */
    public static final int MAXIMUM_COWS_IN_BIRTH_AREA = 32;

    private static final ResourceId VANILLA_COW_TYPE =
            new ResourceId("minecraft:cow");
    private static final String INVENTORY_SCOPE =
            "minecraft.breeding.inventory";
    private static final String COW_SCOPE = "minecraft.breeding.cow";
    private static final double BIRTH_HORIZONTAL_MARGIN = 4.0D;
    private static final double BIRTH_VERTICAL_MARGIN = 2.0D;

    private final Resolver bots;
    private final ActionBackedSkillNodeHandler actions;
    private final Map<UUID, PendingBirth> pendingBirths =
            new LinkedHashMap<>();
    private final Thread ownerThread;

    public MinecraftCowBreedingSkillNodeHandler(
            Resolver bots,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.bots = Objects.requireNonNull(bots, "bots");
        this.actions = new ActionBackedSkillNodeHandler(
                this::planAction,
                Objects.requireNonNull(actions, "actions"),
                Objects.requireNonNull(signals, "signals"));
        ownerThread = Thread.currentThread();
    }

    /**
     * 锁住 bot 原生库存与两头精确 UUID 的实体。第二个节点继续持有同一组三把锁，故其他
     * 已接入同一 reservation service 的任务不能在两次喂食之间抢走伙伴或改写其 inventory。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
        SkillNodeContext required = Objects.requireNonNull(
                context, "context");
        if (!isExactBreedingNode(required)) {
            return List.of();
        }
        FeedRequest request = VanillaCowBreeding.parse(
                required.node().parameters()).orElse(null);
        if (request == null) {
            return List.of();
        }
        return List.of(
                exclusive(ReservationKey.Kind.CONTAINER,
                        INVENTORY_SCOPE,
                        "bot:" + context.botId()),
                exclusive(ReservationKey.Kind.ENTITY,
                        COW_SCOPE,
                        request.firstCowId().toString()),
                exclusive(ReservationKey.Kind.ENTITY,
                        COW_SCOPE,
                        request.secondCowId().toString()));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        return actions.begin(Objects.requireNonNull(context, "context"));
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        if (pendingBirths.containsKey(context.runId())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "等待原版幼牛时收到了不属于该阶段的动作回执");
        }
        return actions.signal(context, signal);
    }

    @Override
    public SkillNodeDirective tick(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        PendingBirth pending = pendingBirths.get(context.runId());
        if (pending == null) {
            return SkillNodeDirective.continueRunning("原版牛繁殖节点继续运行");
        }
        if (!pending.matches(context)) {
            pendingBirths.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "等待幼牛时 Bot 运行身份或节点发生变化");
        }
        Verification verification = verifyBirth(pending, context);
        if (verification.status() == VerificationStatus.VERIFIED) {
            pendingBirths.remove(context.runId(), pending);
            return SkillNodeDirective.complete("已验证原版牛繁殖幼体与父母冷却");
        }
        if (verification.status() == VerificationStatus.INVALID) {
            pendingBirths.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    verification.failureCode(), verification.safeSummary());
        }
        if (context.currentTick() >= pending.deadlineTick()) {
            pendingBirths.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    SkillFailureCode.TIMEOUT,
                    "原版繁殖未在受限观察窗口内产生可验证幼牛");
        }
        return SkillNodeDirective.verify("等待原版牛繁殖幼体出现");
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        pendingBirths.remove(context.runId());
        actions.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        requireOwnerThread();
        if (!isExactBreedingNode(context)) {
            throw planningFailure(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "原版牛繁殖 handler 不能执行不同的技能标识或版本");
        }
        FeedRequest request = VanillaCowBreeding.parse(
                context.node().parameters()).orElseThrow(() ->
                        planningFailure(
                                SkillFailureCode.INVALID_PARAMETERS,
                                "原版牛繁殖节点参数不符合封闭 schema"));
        FeedObservation observation = observe(context, request);
        String operationKey = request.phase() == Phase.FIRST
                ? "feed-cow-first"
                : "feed-cow-second";
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                operationKey,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.InteractEntity(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                observation.target(),
                                Optional.empty(),
                                observation.heldItem())),
                ActionPriority.OWNER_TASK,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_ACTION,
                request.phase() == Phase.FIRST
                        ? "等待原版小麦喂食第一头牛"
                        : "等待原版小麦喂食第二头牛",
                (verificationContext, signal) -> verifyFeed(
                        verificationContext, observation)));
    }

    private FeedObservation observe(
            SkillNodeContext context, FeedRequest request) {
        BotServerPlayer player = activePlayer(context);
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            throw planningFailure(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "原版牛繁殖要求空 cursor 的原生背包菜单");
        }
        if (player.isSecondaryUseActive()) {
            throw planningFailure(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "原版牛繁殖不能在 secondary-use 控制状态下开始");
        }
        if (player.getInventory().selected != request.foodSlot()) {
            throw planningFailure(
                    SkillFailureCode.MISSING_ITEM,
                    "原版牛繁殖要求小麦已在固定主手热栏位");
        }
        ItemStack held = player.getInventory().getItem(request.foodSlot());
        ItemStackFingerprint defaultWheat = MinecraftActionSnapshot.item(
                player, new ItemStack(Items.WHEAT));
        ItemStackFingerprint heldFingerprint = MinecraftActionSnapshot.item(
                player, held);
        int requiredWheat = request.phase() == Phase.FIRST ? 2 : 1;
        if (!held.is(Items.WHEAT)
                || !heldFingerprint.sameItemAndComponents(defaultWheat)
                || heldFingerprint.count() < requiredWheat) {
            throw planningFailure(
                    SkillFailureCode.MISSING_ITEM,
                    "原版牛繁殖需要固定主手槽中的两份默认小麦");
        }
        InventoryMenuSnapshot inventory = nativeInventorySnapshot(player);
        CowBreedingInventoryProof proof;
        try {
            proof = CowBreedingInventoryProof.freeze(
                    inventory, request.foodSlot(), defaultWheat);
        } catch (IllegalArgumentException exception) {
            throw planningFailure(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "原版牛繁殖无法冻结精确原生背包扣款快照");
        }
        Cow target = cow(player, request.targetCowId(), "目标牛");
        Cow partner = cow(player, request.partnerCowId(), "伙伴牛");
        if (!canReachAndSee(player, target)
                || !canReachAndSee(player, partner)) {
            throw planningFailure(
                    SkillFailureCode.OUT_OF_REACH,
                    "两头原版牛必须同时处于可交互且可见范围");
        }
        if (!target.isFood(held)) {
            throw planningFailure(
                    SkillFailureCode.MISSING_ITEM,
                    "固定主手物品不是该原版牛接受的小麦");
        }
        if (request.phase() == Phase.FIRST) {
            if (!readyToBreed(target) || !readyToBreed(partner)) {
                throw planningFailure(
                        SkillFailureCode.WORLD_CHANGED,
                        "原版牛尚未成年、仍在冷却或已经处于求爱状态");
            }
        } else if (!readyToBreed(target)
                || !partner.isInLove()
                || !loveCausedBy(partner, player)) {
            throw planningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "第二次喂食前未能证明第一头牛仍由当前 Bot 以原版方式喂食");
        }
        EntityTargetFingerprint targetFingerprint =
                MinecraftActionSnapshot.entity(player, target);
        EntityTargetFingerprint partnerFingerprint =
                MinecraftActionSnapshot.entity(player, partner);
        BirthSearchArea birthArea = request.phase() == Phase.SECOND
                ? BirthSearchArea.around(target, partner)
                : null;
        Set<UUID> knownCows = request.phase() == Phase.SECOND
                ? cowsIn(player, birthArea)
                : Set.of();
        return new FeedObservation(
                context.botId(),
                context.botGeneration(),
                request,
                targetFingerprint,
                partnerFingerprint,
                heldFingerprint,
                proof,
                birthArea,
                knownCows);
    }

    private SkillNodeDirective verifyFeed(
            SkillNodeContext context, FeedObservation observation) {
        if (!observation.matches(context)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "原版牛喂食回执不再属于当前 Bot 代际");
        }
        BotServerPlayer player;
        try {
            player = activePlayer(context);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return SkillNodeDirective.fail(
                    failure.failureCode(), failure.safeSummary());
        }
        InventoryMenuSnapshot after;
        try {
            after = nativeInventorySnapshot(player);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return SkillNodeDirective.fail(
                    failure.failureCode(), failure.safeSummary());
        }
        CowBreedingInventoryProof.Verification inventory =
                observation.inventoryProof().verifyOneDebit(after);
        if (!inventory.verified()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "原版牛喂食后的背包扣款或控制面不再精确");
        }
        Cow target;
        Cow partner;
        try {
            target = cow(player, observation.target(), "目标牛");
            partner = cow(player, observation.partner(), "伙伴牛");
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return SkillNodeDirective.fail(
                    failure.failureCode(), failure.safeSummary());
        }
        if (observation.request().phase() == Phase.FIRST) {
            if (!target.isInLove()
                    || !loveCausedBy(target, player)
                    || partner.isInLove()
                    || target.getAge() != 0
                    || partner.getAge() != 0) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "第一头原版牛没有进入当前 Bot 可证明的求爱状态");
            }
            return SkillNodeDirective.complete("已验证第一头牛的原版小麦喂食");
        }
        if (!secondFeedHasVanillaProgress(player, target, partner)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "第二次原版喂食没有保留可验证的求爱或繁殖冷却状态");
        }
        PendingBirth pending = new PendingBirth(
                context.runId(),
                context.botId(),
                context.botGeneration(),
                observation.target(),
                observation.partner(),
                after,
                Objects.requireNonNull(observation.birthArea(),
                        "second feed must have a birth area"),
                observation.knownCowIds(),
                boundedBirthDeadline(context));
        Verification verification = verifyBirth(pending, context);
        if (verification.status() == VerificationStatus.VERIFIED) {
            return SkillNodeDirective.complete("已验证原版牛繁殖幼体与父母冷却");
        }
        if (verification.status() == VerificationStatus.INVALID) {
            return SkillNodeDirective.fail(
                    verification.failureCode(), verification.safeSummary());
        }
        PendingBirth existing = pendingBirths.putIfAbsent(
                context.runId(), pending);
        if (existing != null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "原版牛繁殖节点重复登记幼体观察");
        }
        return SkillNodeDirective.verify("第二次原版喂食已完成，等待可验证幼牛");
    }

    private Verification verifyBirth(
            PendingBirth pending, SkillNodeContext context) {
        BotServerPlayer player;
        try {
            player = activePlayer(context);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return Verification.invalid(
                    failure.failureCode(), failure.safeSummary());
        }
        InventoryMenuSnapshot current;
        try {
            current = nativeInventorySnapshot(player);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return Verification.invalid(
                    failure.failureCode(), failure.safeSummary());
        }
        if (!pending.inventoryAfterSecondFeed()
                .layoutEqualsIgnoringState(current)) {
            return Verification.invalid(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "等待原版幼牛期间背包布局或控制面发生变化");
        }
        Cow target;
        Cow partner;
        try {
            target = cow(player, pending.target(), "目标牛");
            partner = cow(player, pending.partner(), "伙伴牛");
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return Verification.invalid(
                    failure.failureCode(), failure.safeSummary());
        }
        ParentBreedingState parentState = parentBreedingState(
                target, partner);
        if (parentState == ParentBreedingState.INVALID) {
            return Verification.invalid(
                    SkillFailureCode.WORLD_CHANGED,
                    "等待原版幼牛期间双亲求爱或冷却状态发生漂移");
        }
        if (parentState == ParentBreedingState.LOVING
                && (!loveCausedBy(target, player)
                || !loveCausedBy(partner, player))) {
            return Verification.invalid(
                    SkillFailureCode.WORLD_CHANGED,
                    "等待原版幼牛期间双亲求爱归属发生漂移");
        }
        if (parentState == ParentBreedingState.LOVING) {
            return Verification.pending();
        }
        for (Cow candidate : cowsInArea(player, pending.birthArea())) {
            if (!pending.knownCowIds().contains(candidate.getUUID())
                    && candidate.isBaby()
                    && candidate.getAge() < 0) {
                return Verification.verified();
            }
        }
        return Verification.pending();
    }

    private BotServerPlayer activePlayer(SkillNodeContext context) {
        BotServerPlayer player = bots.resolve(
                context.botId(), context.botGeneration()).orElseThrow(() ->
                        planningFailure(
                                SkillFailureCode.BOT_NOT_ACTIVE,
                                "原版牛繁殖的 Bot 代际不再活动"));
        if (!context.botId().equals(player.getUUID())
                || player.runtimeHandle().generation()
                        != context.botGeneration()) {
            throw planningFailure(
                    SkillFailureCode.STALE_GENERATION,
                    "原版牛繁殖解析到的 Bot body 不属于当前代际");
        }
        return player;
    }

    private static InventoryMenuSnapshot nativeInventorySnapshot(
            BotServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            throw planningFailure(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "原版牛繁殖要求空 cursor 的原生背包菜单");
        }
        try {
            return MinecraftActionSnapshot.inventoryMenu(player);
        } catch (RuntimeException exception) {
            throw planningFailure(
                    SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "无法重新捕获原版背包菜单快照");
        }
    }

    private static Cow cow(
            BotServerPlayer player,
            UUID entityId,
            String safeRole) {
        Entity entity = player.serverLevel().getEntity(entityId);
        if (!(entity instanceof Cow cow)
                || cow.getClass() != Cow.class
                || cow.getType() != EntityType.COW
                || cow.isRemoved()
                || !cow.isAlive()
                || cow.level() != player.serverLevel()) {
            throw planningFailure(
                    SkillFailureCode.TARGET_GONE,
                    "原版牛繁殖的" + safeRole + "已消失或不再是精确原版牛");
        }
        EntityTargetFingerprint fingerprint =
                MinecraftActionSnapshot.entity(player, cow);
        if (!VANILLA_COW_TYPE.equals(fingerprint.entityType())) {
            throw planningFailure(
                    SkillFailureCode.MOD_UNSUPPORTED,
                    "原版牛繁殖拒绝非 minecraft:cow 的实体类型");
        }
        return cow;
    }

    private static Cow cow(
            BotServerPlayer player,
            EntityTargetFingerprint expected,
            String safeRole) {
        Cow cow = cow(player, expected.entityId(), safeRole);
        EntityTargetFingerprint actual = MinecraftActionSnapshot.entity(
                player, cow);
        if (!expected.equals(actual)) {
            throw planningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版牛繁殖的" + safeRole + "实体指纹或维度发生漂移");
        }
        return cow;
    }

    private static boolean readyToBreed(Cow cow) {
        return !cow.isBaby()
                && cow.getAge() == 0
                && !cow.isInLove()
                && cow.canFallInLove();
    }

    /**
     * 第二次交互完成时，原版可能仍在 Love 状态，也可能已经在同一 tick 由 BreedGoal 生成
     * child 并把双亲推进冷却。两种状态都可观察；任何一边半完成、owner 丢失或年龄不一致
     * 都不是可以继续等待的成功前提。
     */
    private static boolean secondFeedHasVanillaProgress(
            BotServerPlayer player, Cow target, Cow partner) {
        ParentBreedingState state = parentBreedingState(target, partner);
        return state == ParentBreedingState.COOLED_DOWN
                || state == ParentBreedingState.LOVING
                        && loveCausedBy(target, player)
                        && loveCausedBy(partner, player);
    }

    private static ParentBreedingState parentBreedingState(
            Cow first, Cow second) {
        boolean bothLoving = first.isInLove() && second.isInLove()
                && first.getAge() == 0 && second.getAge() == 0;
        if (bothLoving) {
            return ParentBreedingState.LOVING;
        }
        boolean bothCooled = !first.isInLove() && !second.isInLove()
                && first.getAge() > 0 && second.getAge() > 0;
        return bothCooled
                ? ParentBreedingState.COOLED_DOWN
                : ParentBreedingState.INVALID;
    }

    /**
     * {@code Animal#getLoveCause()} resolves a live {@code ServerPlayer}, not
     * merely its UUID.  Requiring the exact current bot body prevents a
     * recreated or stale-generation player with the same profile identity
     * from satisfying the breeding proof.
     */
    private static boolean loveCausedBy(Cow cow, BotServerPlayer player) {
        return cow.getLoveCause() == player;
    }

    private static boolean canReachAndSee(
            BotServerPlayer player, Cow cow) {
        return player.canInteractWithEntity(cow, 1.0D)
                && player.hasLineOfSight(cow);
    }

    private static Set<UUID> cowsIn(
            BotServerPlayer player, BirthSearchArea area) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Cow cow : cowsInArea(player, area)) {
            ids.add(cow.getUUID());
        }
        return Set.copyOf(ids);
    }

    private static List<Cow> cowsInArea(
            BotServerPlayer player, BirthSearchArea area) {
        List<Cow> candidates = player.serverLevel().getEntitiesOfClass(
                Cow.class,
                area.bounds(),
                candidate -> candidate.getClass() == Cow.class
                        && candidate.getType() == EntityType.COW
                        && candidate.isAlive()
                        && !candidate.isRemoved());
        if (candidates.size() > MAXIMUM_COWS_IN_BIRTH_AREA) {
            throw planningFailure(
                    SkillFailureCode.SERVER_OVERLOADED,
                    "原版牛繁殖附近候选实体超过受限观察上限");
        }
        return List.copyOf(candidates);
    }

    private static long boundedBirthDeadline(SkillNodeContext context) {
        try {
            return Math.min(
                    context.deadlineTick() - 1L,
                    Math.addExact(context.currentTick(),
                            MAXIMUM_BIRTH_OBSERVATION_TICKS));
        } catch (ArithmeticException exception) {
            throw planningFailure(
                    SkillFailureCode.TIMEOUT,
                    "原版牛繁殖幼体观察截止时间超出安全范围");
        }
    }

    private static ReservationRequest exclusive(
            ReservationKey.Kind kind, String scope, String subject) {
        return new ReservationRequest(
                new ReservationKey(kind, scope, subject),
                ReservationMode.EXCLUSIVE);
    }

    private static boolean isExactBreedingNode(SkillNodeContext context) {
        return VanillaCowBreeding.ID.equals(context.node().skillId())
                && VanillaCowBreeding.VERSION.equals(
                        context.node().skillVersion());
    }

    private static ActionBackedSkillNodeHandler.PlanningFailure
            planningFailure(
                    SkillFailureCode code, String safeSummary) {
        return new ActionBackedSkillNodeHandler.PlanningFailure(
                code, safeSummary);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "vanilla cow breeding handler requires server thread");
        }
    }

    /** 生命周期只以 botId/generation 解析当前 body，handler 不缓存 player 实例。 */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    private record FeedObservation(
            UUID botId,
            long generation,
            FeedRequest request,
            EntityTargetFingerprint target,
            EntityTargetFingerprint partner,
            ItemStackFingerprint heldItem,
            CowBreedingInventoryProof inventoryProof,
            BirthSearchArea birthArea,
            Set<UUID> knownCowIds) {
        private FeedObservation {
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "breeding generation must be positive");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(partner, "partner");
            Objects.requireNonNull(heldItem, "heldItem");
            Objects.requireNonNull(inventoryProof, "inventoryProof");
            knownCowIds = Set.copyOf(Objects.requireNonNull(
                    knownCowIds, "knownCowIds"));
        }

        private boolean matches(SkillNodeContext context) {
            return botId.equals(context.botId())
                    && generation == context.botGeneration();
        }
    }

    private record PendingBirth(
            UUID runId,
            UUID botId,
            long generation,
            EntityTargetFingerprint target,
            EntityTargetFingerprint partner,
            InventoryMenuSnapshot inventoryAfterSecondFeed,
            BirthSearchArea birthArea,
            Set<UUID> knownCowIds,
            long deadlineTick) {
        private PendingBirth {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0L || deadlineTick < 0L) {
                throw new IllegalArgumentException(
                        "pending birth identity or deadline is invalid");
            }
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(partner, "partner");
            Objects.requireNonNull(
                    inventoryAfterSecondFeed, "inventoryAfterSecondFeed");
            Objects.requireNonNull(birthArea, "birthArea");
            knownCowIds = Set.copyOf(Objects.requireNonNull(
                    knownCowIds, "knownCowIds"));
        }

        private boolean matches(SkillNodeContext context) {
            return runId.equals(context.runId())
                    && botId.equals(context.botId())
                    && generation == context.botGeneration();
        }
    }

    private record BirthSearchArea(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        private BirthSearchArea {
            if (!Double.isFinite(minX)
                    || !Double.isFinite(minY)
                    || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX)
                    || !Double.isFinite(maxY)
                    || !Double.isFinite(maxZ)
                    || minX > maxX
                    || minY > maxY
                    || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "birth search bounds are invalid");
            }
        }

        private static BirthSearchArea around(Cow first, Cow second) {
            return new BirthSearchArea(
                    Math.min(first.getX(), second.getX())
                            - BIRTH_HORIZONTAL_MARGIN,
                    Math.min(first.getY(), second.getY())
                            - BIRTH_VERTICAL_MARGIN,
                    Math.min(first.getZ(), second.getZ())
                            - BIRTH_HORIZONTAL_MARGIN,
                    Math.max(first.getX(), second.getX())
                            + BIRTH_HORIZONTAL_MARGIN,
                    Math.max(first.getY(), second.getY())
                            + BIRTH_VERTICAL_MARGIN,
                    Math.max(first.getZ(), second.getZ())
                            + BIRTH_HORIZONTAL_MARGIN);
        }

        private AABB bounds() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private record Verification(
            VerificationStatus status,
            SkillFailureCode failureCode,
            String safeSummary) {
        private Verification {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(safeSummary, "safeSummary");
            if (status == VerificationStatus.INVALID
                    && failureCode == SkillFailureCode.NONE) {
                throw new IllegalArgumentException(
                        "invalid verification requires a concrete failure code");
            }
            if (status != VerificationStatus.INVALID
                    && failureCode != SkillFailureCode.NONE) {
                throw new IllegalArgumentException(
                        "non-invalid verification cannot carry a failure code");
            }
        }

        private static Verification verified() {
            return new Verification(
                    VerificationStatus.VERIFIED,
                    SkillFailureCode.NONE,
                    "已验证原版幼牛");
        }

        private static Verification pending() {
            return new Verification(
                    VerificationStatus.PENDING,
                    SkillFailureCode.NONE,
                    "等待原版幼牛");
        }

        private static Verification invalid(
                SkillFailureCode code, String safeSummary) {
            return new Verification(
                    VerificationStatus.INVALID, code, safeSummary);
        }
    }

    private enum VerificationStatus {
        VERIFIED,
        PENDING,
        INVALID
    }

    private enum ParentBreedingState {
        LOVING,
        COOLED_DOWN,
        INVALID
    }
}
