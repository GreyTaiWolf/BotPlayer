package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlanBuilder;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoff;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffDecision;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffRequest;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.ArmorUpgradeSelection;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicArmorPlanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * P5A 当前已接入的有界生存纵切：安全面移交的临界进食，以及手动启动的基础盔甲升级。
 *
 * <p>异步动作完成只写入 {@link SkillSignalInbox}；所有世界读取和状态推进都留在服务器主线程。
 */
public final class SurvivalSkillService implements SafetyHandoff {
    public static final SkillId EAT_FOOD =
            new SkillId("botplayer", "eat_food");
    public static final SkillId EQUIP_BASIC_ARMOR =
            new SkillId("botplayer", "equip_basic_armor");
    private static final SkillVersion BUILTIN_VERSION =
            new SkillVersion(1, 0, 0);
    private static final int SIGNAL_CAPACITY = 512;
    private static final int MAXIMUM_SIGNALS_PER_RUN_TICK = 8;
    private static final int MAXIMUM_CLEANUP_ATTEMPTS = 8;
    private static final int EAT_WORK_DEADLINE_TICKS = 240;
    private static final int EAT_CLEANUP_RESERVE_TICKS = 80;
    private static final int EAT_DEADLINE_TICKS =
            EAT_WORK_DEADLINE_TICKS
                    + EAT_CLEANUP_RESERVE_TICKS;
    private static final int ARMOR_WORK_DEADLINE_TICKS = 120;
    private static final int ARMOR_CLEANUP_RESERVE_TICKS = 40;
    private static final int ARMOR_DEADLINE_TICKS =
            ARMOR_WORK_DEADLINE_TICKS
                    + ARMOR_CLEANUP_RESERVE_TICKS;
    private static final int MAXIMUM_ARMOR_CHANGES = 4;
    private static final int VIEW_CAPACITY = 512;
    private static final int MAXIMUM_INCIDENT_ATTEMPTS = 2;
    private static final int INCIDENT_RETRY_BACKOFF_TICKS = 40;

    private final SkillRegistry registry;
    private final Resolver resolver;
    private final ActionSubmitter actionSubmitter;
    private final ActionCanceller actionCanceller;
    private final GenerationLayoutCompensator
            generationLayoutCompensator;
    private final GenerationQuarantiner generationQuarantiner;
    private final Thread ownerThread;
    private final SkillSignalInbox signals =
            new SkillSignalInbox(SIGNAL_CAPACITY);
    private final Map<UUID, ActiveRun> activeRuns =
            new LinkedHashMap<>();
    private final Map<UUID, SurvivalSkillRunView> latestViews =
            new LinkedHashMap<>();
    private final SkillIncidentAttemptLedger incidentAttempts =
            new SkillIncidentAttemptLedger(
                    VIEW_CAPACITY,
                    MAXIMUM_INCIDENT_ATTEMPTS,
                    INCIDENT_RETRY_BACKOFF_TICKS);
    private boolean closed;
    private long lastObservedTick = -1L;

    public SurvivalSkillService(
            SkillRegistry registry,
            Resolver resolver,
            ActionSubmitter actionSubmitter,
            ActionCanceller actionCanceller,
            GenerationLayoutCompensator
                    generationLayoutCompensator,
            GenerationQuarantiner generationQuarantiner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.actionSubmitter =
                Objects.requireNonNull(
                        actionSubmitter, "actionSubmitter");
        this.actionCanceller =
                Objects.requireNonNull(
                        actionCanceller, "actionCanceller");
        this.generationLayoutCompensator =
                Objects.requireNonNull(
                        generationLayoutCompensator,
                        "generationLayoutCompensator");
        this.generationQuarantiner =
                Objects.requireNonNull(
                        generationQuarantiner,
                        "generationQuarantiner");
        this.ownerThread = Thread.currentThread();
        register(new SkillDescriptor(
                EAT_FOOD,
                BUILTIN_VERSION,
                SkillCategory.SURVIVAL,
                SkillParameterSchema.empty(),
                SkillRiskLevel.LOW,
                Set.of(),
                EAT_DEADLINE_TICKS,
                1,
                false));
        register(new SkillDescriptor(
                EQUIP_BASIC_ARMOR,
                BUILTIN_VERSION,
                SkillCategory.SURVIVAL,
                SkillParameterSchema.empty(),
                SkillRiskLevel.LOW,
                Set.of(),
                ARMOR_DEADLINE_TICKS,
                0,
                false));
    }

    @Override
    public SafetyHandoffDecision request(
            SafetyHandoffRequest request) {
        Objects.requireNonNull(request, "request");
        requireOwnerThread();
        observeTick(request.currentTick());
        if (closed) {
            return SafetyHandoffDecision.FALLBACK;
        }
        SurvivalSkillKind requestedKind =
                kindFor(request.hazard().type()).orElse(null);
        if (requestedKind == null) {
            return SafetyHandoffDecision.FALLBACK;
        }

        ActiveRun existing = activeRuns.get(request.botId());
        BotServerPlayer player = resolver
                .resolve(
                        request.botId(),
                        request.botGeneration())
                .orElse(null);
        if (player == null) {
            return SafetyHandoffDecision.FALLBACK;
        }
        if (existing != null
                && existing.generation == request.botGeneration()
                && existing.kind == requestedKind) {
            return SafetyHandoffDecision.ALREADY_DELEGATED;
        } else if (existing != null) {
            return SafetyHandoffDecision.FALLBACK;
        }
        if (incidentAttempts.allow(
                        request.botId(),
                        request.botGeneration(),
                        request.incidentId(),
                        request.currentTick())
                != SkillIncidentAttemptLedger.AllowStatus.ALLOWED) {
            return SafetyHandoffDecision.FALLBACK;
        }
        return beginEating(player, request);
    }

    public void tick(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        if (closed) {
            return;
        }
        for (ActiveRun run :
                List.copyOf(activeRuns.values())) {
            if (run.unsafeFailureCode != null) {
                retryUnsafeFailure(run, currentTick);
                continue;
            }
            if (SkillDeadlinePolicy.beforeSignals(
                            currentTick,
                            run.deadlineTick)
                    == SkillDeadlinePolicy
                            .PreSignalDecision
                            .HARD_TIMEOUT) {
                failUnsafe(
                        run,
                        SkillFailureCode.TIMEOUT,
                        "技能清理超过硬时限",
                        currentTick);
                continue;
            }
            BotServerPlayer player = resolver
                    .resolve(run.botId, run.generation)
                    .orElse(null);
            if (player == null) {
                failUnsafe(
                        run,
                        SkillFailureCode.BOT_NOT_ACTIVE,
                        "BotPlayer 不再处于活动代际",
                        currentTick);
                continue;
            }
            List<SkillSignal> completed = signals.drain(
                    run.runId,
                    run.generation,
                    MAXIMUM_SIGNALS_PER_RUN_TICK);
            for (SkillSignal signal : completed) {
                if (activeRuns.get(run.botId) != run) {
                    break;
                }
                handleSignal(
                        player, run, signal, currentTick);
            }
            if (activeRuns.get(run.botId) != run) {
                continue;
            }
            if (SkillDeadlinePolicy
                    .shouldRequestWorkTimeout(
                            currentTick,
                            run.workDeadlineTick,
                            run.pendingTerminalState
                                    != null,
                            run.operation != null
                                    && run.operation
                                            .cleanup())) {
                requestWorkTimeout(
                        player, run, currentTick);
            }
        }
    }

    public Optional<SurvivalSkillRunView> inspect(UUID botId) {
        requireOwnerThread();
        Objects.requireNonNull(botId, "botId");
        ActiveRun active = activeRuns.get(botId);
        return active == null
                ? Optional.ofNullable(latestViews.get(botId))
                : Optional.of(view(active));
    }

    /**
     * 手动启动一条只使用原生背包菜单的基础盔甲升级运行。
     *
     * <p>候选扫描可携带槽 0..35；热栏候选使用单击交换，主背包候选使用
     * 2/3 步逐 Tick 事务，取消时最多一次点击收敛到安全端点。
     */
    public SurvivalSkillSubmission startBasicArmor(
            BotServerPlayer player, long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        Objects.requireNonNull(player, "player");
        if (closed) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.RUNTIME_CLOSED,
                    "P5 生存技能运行时已关闭");
        }

        UUID botId = player.getUUID();
        long generation =
                player.runtimeHandle().generation();
        if (generation <= 0L
                || resolver.resolve(botId, generation)
                        .filter(candidate -> candidate == player)
                        .isEmpty()) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_NOT_ACTIVE,
                    "BotPlayer 当前不是活动权威代际");
        }
        if (activeRuns.containsKey(botId)) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_BUSY,
                    "BotPlayer 已有一个活动生存技能");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.MENU_UNAVAILABLE,
                    "BotPlayer 原生背包菜单当前不可用");
        }

        ArmorUpgradeSelection selection;
        InventoryMenuSwapPlan plan;
        try {
            selection = MinecraftBasicArmorPlanner
                    .plan(player)
                    .orElse(null);
            if (selection == null) {
                return SurvivalSkillSubmission.rejected(
                        SurvivalSkillSubmission.Status.NO_UPGRADE,
                        "背包中没有可安全装备的基础盔甲升级");
            }
            plan = prepareArmorPlan(
                    player, selection);
        } catch (RuntimeException exception) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.MENU_UNAVAILABLE,
                    "无法冻结原生背包菜单升级快照");
        }

        ActiveRun run = new ActiveRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                botId,
                generation,
                SurvivalSkillKind.EQUIP_BASIC_ARMOR,
                currentTick,
                currentTick
                        + ARMOR_WORK_DEADLINE_TICKS,
                currentTick
                        + ARMOR_DEADLINE_TICKS);
        if (!activate(run, currentTick)) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_BUSY,
                    "BotPlayer 无法绑定新的基础盔甲技能运行");
        }

        try {
            transition(
                    run,
                    SkillRunState.PREPARING,
                    currentTick,
                    "已冻结背包盔甲升级与原生菜单快照");
            transition(
                    run,
                    SkillRunState.RUNNING,
                    currentTick,
                    "准备原生菜单盔甲交换");
            if (!submitArmorUpgrade(
                    player,
                    run,
                    selection,
                    plan,
                    currentTick)) {
                return SurvivalSkillSubmission.rejected(
                        SurvivalSkillSubmission.Status.ACTION_REJECTED,
                        "基础盔甲菜单动作未被接纳");
            }
            return SurvivalSkillSubmission.started(
                    run.runId,
                    "基础盔甲升级技能已启动");
        } catch (RuntimeException exception) {
            if (activeRuns.get(botId) == run) {
                terminateOrRecover(
                        player,
                        run,
                        SkillRunState.FAILED,
                        SkillFailureCode.INTERNAL_ERROR,
                        "基础盔甲技能启动失败",
                        currentTick);
            }
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.ACTION_REJECTED,
                    "基础盔甲技能启动失败");
        }
    }

    public SkillRegistry registry() {
        return registry;
    }

    /**
     * 生命周期必须先同步关闭该 generation 的动作，再调用此方法解绑技能信号。
     *
     * @return {@code true} only when action cleanup and any armed physical
     *     inventory-layout compensation both produced safe receipts
     */
    public boolean closeGeneration(
            UUID botId,
            long generation,
            long currentTick,
            boolean actionCleanupConfirmed) {
        requireOwnerThread();
        observeTick(currentTick);
        Objects.requireNonNull(botId, "botId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive and tick non-negative");
        }
        ActiveRun run = activeRuns.get(botId);
        if (run == null || run.generation != generation) {
            incidentAttempts.closeGeneration(
                    botId, generation);
            generationLayoutCompensator.closeGeneration(
                    botId, generation);
            return actionCleanupConfirmed;
        }
        run.generationCloseConfirmed =
                actionCleanupConfirmed;
        boolean safelyClosed =
                closeRunAfterGenerationCleanup(
                run,
                actionCleanupConfirmed,
                "Bot generation 关闭时未取得动作清理确认",
                "Bot generation 已安全关闭",
                currentTick);
        incidentAttempts.closeGeneration(
                botId, generation);
        generationLayoutCompensator.closeGeneration(
                botId, generation);
        return safelyClosed;
    }

    /**
     * 服务器关闭路径必须先停止动作运行时，再终结这里保留的运行视图。
     */
    public void shutdown(
            long currentTick,
            GenerationSafetyInspector safetyInspector) {
        requireOwnerThread();
        observeTick(currentTick);
        Objects.requireNonNull(
                safetyInspector, "safetyInspector");
        if (closed) {
            return;
        }
        for (ActiveRun run :
                List.copyOf(activeRuns.values())) {
            run.generationCloseConfirmed =
                    safetyInspector.isSafe(
                            run.botId, run.generation);
            closeRunAfterGenerationCleanup(
                    run,
                    run.generationCloseConfirmed,
                    "服务器停止时未取得动作清理确认",
                    "服务器停止已安全关闭未完成技能",
                    currentTick);
        }
        if (!activeRuns.isEmpty()) {
            throw new IllegalStateException(
                    "server shutdown could not confirm generation quarantine");
        }
        generationLayoutCompensator.closeAll();
        signals.close();
        incidentAttempts.clear();
        closed = true;
    }

    private SafetyHandoffDecision beginEating(
            BotServerPlayer player,
            SafetyHandoffRequest request) {
        FoodCandidate candidate =
                chooseFood(player).orElse(null);
        if (candidate == null) {
            return SafetyHandoffDecision.FALLBACK;
        }
        ActiveRun run = new ActiveRun(
                UUID.randomUUID(),
                request.incidentId(),
                request.botId(),
                request.botGeneration(),
                SurvivalSkillKind.EAT_FOOD,
                request.currentTick(),
                request.currentTick()
                        + EAT_WORK_DEADLINE_TICKS,
                request.currentTick()
                        + EAT_DEADLINE_TICKS);
        run.food = candidate;
        run.previousSelected =
                player.getInventory().selected;
        run.layoutLease =
                new InventoryLayoutCleanupLease(
                        run.runId,
                        candidate.inventorySlot >= 9
                                        && candidate.inventorySlot
                                                <= 35
                                ? candidate.inventorySlot
                                : -1,
                        candidate.hotbarSlot,
                        candidate.fingerprint,
                        MinecraftActionSnapshot
                                .inventoryContents(
                                        player),
                        run.previousSelected,
                        candidate.inventorySlot >= 9
                                && candidate.inventorySlot
                                        <= 35,
                        candidate.hand
                                        == WorldInteractionActionSpec
                                                .Hand
                                                .MAIN_HAND
                                && candidate.hotbarSlot >= 0
                                && candidate.hotbarSlot
                                        != run.previousSelected);
        try {
            if (!generationLayoutCompensator.open(
                    run.botId,
                    run.generation,
                    run.layoutLease)) {
                return SafetyHandoffDecision.FALLBACK;
            }
            run.layoutLeaseOpen = true;
        } catch (RuntimeException exception) {
            return SafetyHandoffDecision.FALLBACK;
        }
        if (!activate(run, request.currentTick())) {
            releaseLayoutLease(run);
            return SafetyHandoffDecision.FALLBACK;
        }
        try {
            transition(
                    run,
                    SkillRunState.PREPARING,
                    request.currentTick(),
                    "已选择安全食物");
            transition(
                    run,
                    SkillRunState.RUNNING,
                    request.currentTick(),
                    "准备真实物品使用动作");
            boolean submitted;
            if (candidate.inventorySlot >= 9
                    && candidate.inventorySlot <= 35) {
                submitted = submitSwapFood(
                        player, run, request.currentTick());
            } else if (candidate.hand
                    == WorldInteractionActionSpec.Hand.MAIN_HAND) {
                submitted =
                        candidate.hotbarSlot
                                        == run.previousSelected
                                ? submitUseFood(
                                        player,
                                        run,
                                        request.currentTick())
                                : submitSelectFood(
                                        player,
                                        run,
                                        request.currentTick());
            } else {
                submitted = submitUseFood(
                        player, run, request.currentTick());
            }
            return submitted
                    ? SafetyHandoffDecision.DELEGATED
                    : SafetyHandoffDecision.FALLBACK;
        } catch (RuntimeException exception) {
            if (activeRuns.get(run.botId) == run) {
                if (run.activeActionId != null) {
                    run.pendingTerminalState =
                            SkillRunState.FAILED;
                    run.pendingFailureCode =
                            SkillFailureCode.INTERNAL_ERROR;
                    run.pendingTerminalSummary =
                            "进食技能启动失败";
                    run.activeActionCancellationRequested = true;
                    try {
                        actionCanceller.cancel(
                                run.botId,
                                run.activeActionId);
                    } catch (RuntimeException ignored) {
                        // The child action still has a bounded deadline.
                    }
                } else {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.INTERNAL_ERROR,
                            "进食技能启动失败",
                            request.currentTick());
                }
            }
            return activeRuns.get(run.botId) == run
                    ? SafetyHandoffDecision.DELEGATED
                    : SafetyHandoffDecision.FALLBACK;
        }
    }

    private Optional<FoodCandidate> chooseFood(
            BotServerPlayer player) {
        List<FoodCandidate> candidates = new ArrayList<>();
        List<SurvivalSkillPolicy.FoodOption> options =
                new ArrayList<>();
        int selected = player.getInventory().selected;
        int targetHotbar = chooseHotbarTarget(player, selected);
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            if (slot >= 36 && slot <= 39) {
                continue;
            }
            ItemStack stack =
                    player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties properties;
            try {
                properties = stack.getFoodProperties(player);
            } catch (RuntimeException exception) {
                continue;
            }
            if (properties == null) {
                continue;
            }
            boolean usable = properties.nutrition() > 0
                    && player.canEat(
                            properties.canAlwaysEat())
                    && supportedVanillaFood(
                            stack, properties)
                    && (slot <= 8
                            || slot == 40
                            || targetHotbar >= 0);
            boolean harmful = hasHarmfulEffect(properties);
            WorldInteractionActionSpec.Hand hand =
                    slot == 40
                            ? WorldInteractionActionSpec.Hand.OFF_HAND
                            : WorldInteractionActionSpec.Hand.MAIN_HAND;
            int hotbarSlot = slot <= 8
                    ? slot
                    : slot == 40 ? -1 : targetHotbar;
            FoodCandidate candidate = new FoodCandidate(
                    slot,
                    hotbarSlot,
                    hand,
                    MinecraftActionSnapshot.item(player, stack),
                    properties.nutrition(),
                    properties.saturation(),
                    harmful,
                    usable,
                    slot == selected || slot == 40);
            candidates.add(candidate);
            options.add(candidate.option());
        }
        OptionalInt chosen =
                SurvivalSkillPolicy.chooseFood(options);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate ->
                        candidate.inventorySlot
                                == chosen.orElseThrow())
                .findFirst();
    }

    private static int chooseHotbarTarget(
            BotServerPlayer player, int selected) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selected
                    && player.getInventory()
                            .getItem(slot)
                            .isEmpty()) {
                return slot;
            }
        }
        if (player.getInventory()
                .getItem(selected)
                .isEmpty()) {
            return selected;
        }
        return -1;
    }

    private static boolean hasHarmfulEffect(
            FoodProperties properties) {
        try {
            return properties.effects().stream().anyMatch(effect ->
                    effect.probability() > 0.0F
                            && effect.effect()
                                            .getEffect()
                                            .value()
                                            .getCategory()
                                    == MobEffectCategory.HARMFUL);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /**
     * 首批纵切只接受没有自定义完成逻辑或剩余容器的原版基础食物。
     *
     * <p>可疑炖菜、紫颂果、蜂蜜瓶和模组食物留给后续显式消费语义适配器。
     */
    private static boolean supportedVanillaFood(
            ItemStack stack, FoodProperties properties) {
        try {
            return stack.getItem().getClass() == Item.class
                    && "minecraft".equals(
                            BuiltInRegistries.ITEM
                                    .getKey(stack.getItem())
                                    .getNamespace())
                    && properties.usingConvertsTo().isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean remainsSafeToEat(
            BotServerPlayer player, ItemStack stack) {
        try {
            FoodProperties properties =
                    stack.getFoodProperties(player);
            return properties != null
                    && properties.nutrition() > 0
                    && player.canEat(
                            properties.canAlwaysEat())
                    && supportedVanillaFood(
                            stack, properties)
                    && !hasHarmfulEffect(properties);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static InventoryMenuSwapPlan prepareArmorPlan(
            BotServerPlayer player,
            ArmorUpgradeSelection selection) {
        InventoryMenuSnapshot snapshot =
                MinecraftActionSnapshot.inventoryMenu(player);
        if (!snapshot
                .itemAt(selection.sourceInventorySlot())
                .equals(selection.candidate()
                        .itemFingerprint())) {
            throw new IllegalStateException(
                    "armor source changed before menu planning");
        }
        if (PlayerInventoryMenuLayout
                .isHotbarInventorySlot(
                        selection.sourceInventorySlot())) {
            return InventoryMenuSwapPlanBuilder
                    .hotbarToEquipment(
                            snapshot,
                            selection.sourceInventorySlot(),
                            selection.targetInventorySlot());
        }
        if (PlayerInventoryMenuLayout
                .isMainInventorySlot(
                        selection.sourceInventorySlot())) {
            return InventoryMenuSwapPlanBuilder
                    .mainToEquipment(
                            snapshot,
                            selection.sourceInventorySlot(),
                            selection.targetInventorySlot());
        }
        throw new IllegalStateException(
                "armor source is outside the carried inventory");
    }

    private boolean submitArmorUpgrade(
            BotServerPlayer player,
            ActiveRun run,
            ArmorUpgradeSelection selection,
            InventoryMenuSwapPlan plan,
            long currentTick) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(plan, "plan");
        boolean hotbarPlan =
                PlayerInventoryMenuLayout
                                .isHotbarInventorySlot(
                                        selection
                                                .sourceInventorySlot())
                        && plan.operation()
                                == InventoryMenuSwapPlan.Operation
                                        .HOTBAR_TO_EQUIPMENT
                        && plan.orderedSteps().size() == 1;
        boolean mainInventoryPlan =
                PlayerInventoryMenuLayout
                                .isMainInventorySlot(
                                        selection
                                                .sourceInventorySlot())
                        && plan.operation()
                                == InventoryMenuSwapPlan.Operation
                                        .MAIN_TO_EQUIPMENT
                        && plan.orderedSteps().size() >= 2
                        && plan.orderedSteps().size() <= 3;
        if ((!hotbarPlan && !mainInventoryPlan)
                || !plan.initialSnapshot()
                        .itemAt(selection.sourceInventorySlot())
                        .equals(selection.candidate()
                                .itemFingerprint())) {
            terminateOrRecover(
                    player,
                    run,
                    SkillRunState.FAILED,
                    SkillFailureCode.INTERNAL_ERROR,
                    "基础盔甲菜单计划与选择不一致",
                    currentTick);
            return false;
        }
        run.armorSelection = selection;
        run.armorPlan = plan;
        return submit(
                player,
                run,
                Operation.EQUIP_ARMOR,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec
                                .InventoryMenuSwap(plan)),
                10,
                currentTick);
    }

    private boolean planAndSubmitNextArmorUpgrade(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        ArmorUpgradeSelection selection;
        InventoryMenuSwapPlan plan;
        try {
            selection = MinecraftBasicArmorPlanner
                    .plan(player)
                    .orElse(null);
            if (selection == null) {
                succeedArmor(run, currentTick);
                return true;
            }
            if (run.equippedArmorTargets
                    .contains(selection.targetInventorySlot())) {
                terminateOrRecover(
                        player,
                        run,
                        SkillRunState.FAILED,
                        SkillFailureCode.WORLD_CHANGED,
                        "盔甲规划重复选择已提交的装备槽",
                        currentTick);
                return false;
            }
            plan = prepareArmorPlan(
                    player, selection);
        } catch (RuntimeException exception) {
            terminateOrRecover(
                    player,
                    run,
                    SkillRunState.FAILED,
                    SkillFailureCode.WORLD_CHANGED,
                    "下一件盔甲规划时背包或菜单已变化",
                    currentTick);
            return false;
        }
        return submitArmorUpgrade(
                player,
                run,
                selection,
                plan,
                currentTick);
    }

    private boolean submitSwapFood(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        ItemStackFingerprint target =
                MinecraftActionSnapshot.item(
                        player,
                        player.getInventory()
                                .getItem(food.hotbarSlot));
        if (!target.isEmpty()) {
            fail(
                    run,
                    SkillFailureCode.WORLD_CHANGED,
                    "可用空快捷栏在交换前已经变化",
                    currentTick);
            return false;
        }
        run.swappedSourceSlot = food.inventorySlot;
        run.swappedHotbarSlot = food.hotbarSlot;
        WorldInteractionAction action =
                new WorldInteractionAction(
                        new WorldInteractionActionSpec
                                .SwapInventoryHotbar(
                                food.inventorySlot,
                                food.hotbarSlot,
                                food.fingerprint,
                                target));
        return submit(
                player,
                run,
                Operation.SWAP_FOOD,
                action,
                10,
                currentTick);
    }

    private boolean submitRestoreFoodSlot(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        if (!run.swapApplied
                || run.swappedSourceSlot < 9
                || run.swappedHotbarSlot < 0) {
            return false;
        }
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        ItemStackFingerprint target =
                MinecraftActionSnapshot.item(
                        player,
                        player.getInventory()
                                .getItem(run.swappedHotbarSlot));
        if (target.isEmpty()) {
            if (run.itemConsumptionObserved
                    && food.fingerprint.count() == 1) {
                run.swapApplied = false;
                return restoreSelectionOrSucceed(
                        player, run, currentTick);
            }
            failUnsafe(
                    run,
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "临时快捷栏中的食物意外消失",
                    currentTick);
            return false;
        }
        int expectedCount = run.itemConsumptionObserved
                ? food.fingerprint.count() - 1
                : food.fingerprint.count();
        if (expectedCount < 1
                || !target.sameItemAndComponents(
                        food.fingerprint)
                || target.count() != expectedCount) {
            failUnsafe(
                    run,
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "临时快捷栏不再包含可安全恢复的食物余量",
                    currentTick);
            return false;
        }
        ItemStackFingerprint source =
                MinecraftActionSnapshot.item(
                        player,
                        player.getInventory()
                                .getItem(run.swappedSourceSlot));
        if (!source.isEmpty()) {
            run.swapApplied = false;
            run.pendingTerminalState = SkillRunState.FAILED;
            run.pendingFailureCode =
                    SkillFailureCode.WORLD_CHANGED;
            run.pendingTerminalSummary =
                    "原背包槽被原版外部插入占用；保留全部当前物品并停止整理";
            return restoreSelectionOrSucceed(
                    player, run, currentTick);
        }
        return submit(
                player,
                run,
                Operation.RESTORE_FOOD_SLOT,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec
                                .SwapInventoryHotbar(
                                run.swappedSourceSlot,
                                run.swappedHotbarSlot,
                                source,
                                target)),
                10,
                currentTick);
    }

    private boolean submitSelectFood(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        int hotbarSlot = food.hotbarSlot;
        ItemStackFingerprint actual =
                MinecraftActionSnapshot.item(
                        player,
                        player.getInventory()
                                .getItem(hotbarSlot));
        if (!actual.equals(food.fingerprint)) {
            terminateOrRecover(
                    player,
                    run,
                    SkillRunState.FAILED,
                    SkillFailureCode.WORLD_CHANGED,
                    "所选食物在切换快捷栏前已经变化",
                    currentTick);
            return false;
        }
        return submit(
                player,
                run,
                Operation.SELECT_FOOD,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec
                                .SelectHotbar(
                                hotbarSlot, food.fingerprint)),
                5,
                currentTick);
    }

    private boolean submitUseFood(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        ItemStack stack = food.hand
                        == WorldInteractionActionSpec.Hand.OFF_HAND
                ? player.getOffhandItem()
                : player.getMainHandItem();
        ItemStackFingerprint actual =
                MinecraftActionSnapshot.item(player, stack);
        if (!actual.equals(food.fingerprint)
                || !remainsSafeToEat(player, stack)) {
            terminateOrRecover(
                    player,
                    run,
                    SkillRunState.FAILED,
                    SkillFailureCode.WORLD_CHANGED,
                    "食物在真实使用前已经变化或不再安全",
                    currentTick);
            return false;
        }
        return submit(
                player,
                run,
                Operation.USE_FOOD,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.UseItem(
                                food.hand,
                                food.fingerprint,
                                WorldInteractionActionSpec
                                        .ItemUseMode
                                        .FINISH_NATURALLY,
                                0)),
                200,
                currentTick);
    }

    private boolean submitRestoreSelection(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        int slot = run.previousSelected;
        int current = player.getInventory().selected;
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        if (!run.selectionChangedBySkill) {
            completeSuccessOrPendingTerminal(
                    run, currentTick);
            return true;
        }
        if (current == slot) {
            run.selectionChangedBySkill = false;
            completeSuccessOrPendingTerminal(
                    run, currentTick);
            return true;
        }
        if (food.hand
                        == WorldInteractionActionSpec.Hand.MAIN_HAND
                && current != food.hotbarSlot) {
            failUnsafe(
                    run,
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "快捷栏选择被外部修改，拒绝覆盖未知控制状态",
                    currentTick);
            return false;
        }
        ItemStackFingerprint expected =
                MinecraftActionSnapshot.item(
                        player,
                        player.getInventory().getItem(slot));
        return submit(
                player,
                run,
                Operation.RESTORE_SELECTION,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec
                                .SelectHotbar(slot, expected)),
                5,
                currentTick);
    }

    private boolean submit(
            BotServerPlayer player,
            ActiveRun run,
            Operation operation,
            WorldInteractionAction action,
            int maximumTicks,
            long currentTick) {
        boolean cleanup = operation.cleanup();
        if (cleanup) {
            if (run.cleanupSubmissionAttempts
                    >= MAXIMUM_CLEANUP_ATTEMPTS) {
                failUnsafe(
                        run,
                        SkillFailureCode.DANGER_PREEMPTED,
                        "补偿动作重试次数耗尽",
                        currentTick);
                return false;
            }
        }
        long operationBoundary = cleanup
                ? run.deadlineTick
                : run.workDeadlineTick;
        if (currentTick >= operationBoundary - 1L) {
            if (cleanup) {
                failUnsafe(
                        run,
                        SkillFailureCode.TIMEOUT,
                        "补偿动作剩余时间不足",
                        currentTick);
            } else {
                terminateOrRecover(
                        player,
                        run,
                        SkillRunState.FAILED,
                        SkillFailureCode.TIMEOUT,
                        "业务动作已进入保留的补偿窗口",
                        currentTick);
            }
            return false;
        }
        if (cleanup) {
            run.cleanupSubmissionAttempts++;
        }
        UUID actionId = UUID.randomUUID();
        int sequence = run.operationSequence++;
        long actionDeadline = Math.min(
                operationBoundary - 1L,
                currentTick + maximumTicks + 20L);
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                run.botId,
                run.generation,
                "skill:"
                        + run.runId
                        + ":"
                        + sequence
                        + ":"
                        + operation.id,
                actionDeadline,
                maximumTicks,
                action,
                ActionOrigin.fromSkillRun(run.runId));
        ActionMailbox.Submission submission;
        try {
            submission = Objects.requireNonNull(
                    actionSubmitter.submit(
                            envelope,
                            cleanup
                                    ? ActionPriority
                                            .LIFECYCLE_CLEANUP
                                    : ActionPriority
                                            .SURVIVAL),
                    "action submission");
        } catch (RuntimeException exception) {
            try {
                actionCanceller.cancel(
                        run.botId, actionId);
            } catch (RuntimeException ignored) {
                // The production submitter is atomic; this is a defensive
                // best-effort close for a violated adapter contract.
            }
            if (cleanup) {
                failUnsafe(
                        run,
                        SkillFailureCode.INTERNAL_ERROR,
                        "补偿动作提交异常",
                        currentTick);
            } else {
                terminateOrRecover(
                        player,
                        run,
                        SkillRunState.FAILED,
                        SkillFailureCode.INTERNAL_ERROR,
                        "动作提交异常",
                        currentTick);
            }
            return false;
        }
        if (submission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            if (cleanup) {
                failUnsafe(
                        run,
                        SkillFailureCode.ACTION_REJECTED,
                        "补偿动作提交被拒绝："
                                + submission.status().name(),
                        currentTick);
            } else {
                terminateOrRecover(
                        player,
                        run,
                        SkillRunState.FAILED,
                        SkillFailureCode.ACTION_REJECTED,
                        "动作提交被拒绝："
                                + submission.status().name(),
                        currentTick);
            }
            return false;
        }
        CompletionStage<ActionOutcome> completion =
                submission.completion().orElseThrow();
        run.activeActionId = actionId;
        run.operation = operation;
        transition(
                run,
                operation.waitingState(),
                currentTick,
                "等待动作完成：" + operation.id);
        long actionRevision = run.stateRevision;
        run.activeActionRevision = actionRevision;
        completion.whenComplete((outcome, throwable) ->
                        offerActionSignal(
                                run,
                                actionId,
                                actionRevision,
                                outcome,
                                throwable,
                                currentTick));
        return true;
    }

    private void offerActionSignal(
            ActiveRun run,
            UUID actionId,
            long runRevision,
            ActionOutcome outcome,
            Throwable throwable,
            long submissionTick) {
        SkillSignalStatus status;
        SkillFailureCode failureCode;
        String summary;
        long finishedTick;
        List<ActionEvidence> evidence;
        if (throwable != null
                || outcome == null
                || !outcome.actionId().equals(actionId)) {
            status = SkillSignalStatus.FAILED;
            failureCode = SkillFailureCode.INTERNAL_ERROR;
            summary = throwable != null || outcome == null
                    ? "动作完成回调失败"
                    : "动作完成身份不匹配";
            finishedTick = submissionTick;
            evidence = List.of();
        } else {
            finishedTick = outcome.finishedTick();
            if (!hasUniqueEvidenceKeys(
                    outcome.evidence())) {
                status = SkillSignalStatus.FAILED;
                failureCode =
                        SkillFailureCode.INTERNAL_ERROR;
                summary = "动作完成证据键重复";
                evidence = List.of();
            } else {
                status = signalStatus(outcome.state());
                failureCode = mapActionFailure(
                        outcome.failureCode());
                summary = outcome.safeSummary();
                evidence = outcome.evidence();
            }
        }
        signals.offer(new SkillSignal(
                UUID.randomUUID(),
                run.runId,
                run.botId,
                run.generation,
                runRevision,
                actionId,
                SkillSignalType.ACTION,
                status,
                failureCode,
                evidence,
                summary,
                finishedTick));
    }

    private static boolean hasUniqueEvidenceKeys(
            List<ActionEvidence> evidence) {
        Set<String> keys = new HashSet<>();
        for (ActionEvidence item : evidence) {
            if (!keys.add(item.key())) {
                return false;
            }
        }
        return true;
    }

    private void requestWorkTimeout(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        if (run.pendingTerminalState == null) {
            run.pendingTerminalState =
                    SkillRunState.FAILED;
            run.pendingFailureCode =
                    SkillFailureCode.TIMEOUT;
            run.pendingTerminalSummary =
                    "技能业务动作超过工作时限";
        }
        if (run.activeActionId != null) {
            if (!run.activeActionCancellationRequested) {
                run.activeActionCancellationRequested = true;
                try {
                    actionCanceller.cancel(
                            run.botId,
                            run.activeActionId);
                } catch (RuntimeException ignored) {
                    // The action has its own earlier deadline; retain the
                    // pending timeout and wait for its terminal signal.
                }
            }
            return;
        }
        beginPendingTerminalCleanup(
                player, run, currentTick);
    }

    private static void observeConsumption(
            BotServerPlayer player,
            ActiveRun run,
            SkillSignal signal) {
        FoodCandidate food =
                Objects.requireNonNull(run.food, "food");
        FoodUseEvidence.Observation immutable =
                FoodUseEvidence.observe(
                        signal,
                        food.fingerprint);
        if (signal.status() == SkillSignalStatus.SUCCEEDED) {
            run.itemConsumptionObserved |=
                    immutable.exactItemConsumption();
        } else {
            ItemStack stack = food.hand
                            == WorldInteractionActionSpec.Hand.OFF_HAND
                    ? player.getOffhandItem()
                    : player.getInventory()
                            .getItem(food.hotbarSlot);
            ItemStackFingerprint actual =
                    MinecraftActionSnapshot.item(player, stack);
            run.itemConsumptionObserved |=
                    food.fingerprint.count() == 1
                            ? actual.isEmpty()
                            : actual.sameItemAndComponents(
                                            food.fingerprint)
                                    && actual.count()
                                            == food.fingerprint.count()
                                                    - 1;
        }
        run.foodIncreaseObserved |=
                immutable.foodLevelIncreased();
    }

    private static boolean hasEvidence(
            SkillSignal signal,
            String key,
            String value) {
        return signal.evidence().stream().anyMatch(evidence ->
                evidence.key().equals(key)
                        && evidence.value().equals(value));
    }

    private void handleSignal(
            BotServerPlayer player,
            ActiveRun run,
            SkillSignal signal,
            long currentTick) {
        Operation activeOperation = run.operation;
        if (run.activeActionId == null
                || !run.activeActionId.equals(
                        signal.operationId())
                || signal.type() != SkillSignalType.ACTION
                || activeOperation == null
                || run.state
                        != activeOperation.waitingState()
                || signal.runRevision()
                        != run.activeActionRevision) {
            return;
        }
        Operation completed = activeOperation;
        run.activeActionId = null;
        run.activeActionRevision = -1L;
        run.operation = null;
        run.activeActionCancellationRequested = false;
        if (completed == null) {
            failUnsafe(
                    run,
                    SkillFailureCode.INTERNAL_ERROR,
                    "技能缺少活动操作标识",
                    currentTick);
            return;
        }
        if (completed == Operation.USE_FOOD) {
            observeConsumption(player, run, signal);
        }
        if (signal.failureCode()
                        == SkillFailureCode
                                .UNSAFE_CONTROL_STATE
                || hasEvidence(
                        signal,
                        "runtime.cleanup",
                        "failed")) {
            failUnsafe(
                    run,
                    signal.failureCode()
                                    == SkillFailureCode
                                            .UNSAFE_CONTROL_STATE
                            ? SkillFailureCode
                                    .UNSAFE_CONTROL_STATE
                            : SkillFailureCode.INTERNAL_ERROR,
                    "动作运行时报告未完成的安全清理",
                    currentTick);
            return;
        }
        long actionBoundary = completed.cleanup()
                ? run.deadlineTick
                : run.workDeadlineTick;
        if (signal.gameTick() >= actionBoundary) {
            if (completed == Operation.EQUIP_ARMOR) {
                /*
                 * The runtime may have safely settled a multi-step menu
                 * transaction at FINAL while the business action still
                 * times out. Preserve the timeout terminal, but do not hide
                 * the authoritative equipment change.
                 */
                reconcileArmorAfterNonSuccess(
                        player, run);
            }
            terminateOrRecover(
                    player,
                    run,
                    SkillRunState.FAILED,
                    SkillFailureCode.TIMEOUT,
                    completed.cleanup()
                            ? "补偿动作在技能硬时限之后完成"
                            : "业务动作侵入了保留的补偿窗口",
                    currentTick);
            return;
        }
        if (signal.status()
                != SkillSignalStatus.SUCCEEDED) {
            String summary = "动作失败："
                    + signal.failureCode().name()
                    + " "
                    + signal.safeSummary();
            if (completed.cleanup()) {
                boolean retryable =
                        signal.status()
                                        == SkillSignalStatus
                                                .PREEMPTED
                                || (signal.status()
                                                == SkillSignalStatus
                                                        .FAILED
                                        && mapFailure(signal)
                                                == SkillFailureCode
                                                        .SERVER_OVERLOADED);
                if (retryable
                        && run.cleanupSubmissionAttempts
                                < MAXIMUM_CLEANUP_ATTEMPTS
                        && currentTick
                                < run.deadlineTick - 1L) {
                    transition(
                            run,
                            SkillRunState.RUNNING,
                            currentTick,
                            "高优先级动作结束后重试补偿");
                    retryCleanup(
                            player,
                            run,
                            completed,
                            currentTick);
                    return;
                }
                failUnsafe(
                        run,
                        signal.status()
                                        == SkillSignalStatus.FAILED
                                ? mapFailure(signal)
                                : SkillFailureCode
                                        .DANGER_PREEMPTED,
                        "补偿" + summary,
                        currentTick);
                return;
            }
            SkillRunState terminal = switch (signal.status()) {
                case FAILED -> SkillRunState.FAILED;
                case CANCELLED, STALE ->
                        SkillRunState.CANCELLED;
                case PREEMPTED ->
                        SkillRunState.PREEMPTED;
                case SUCCEEDED ->
                        throw new IllegalStateException(
                                "successful signal entered failure branch");
            };
            SkillFailureCode code =
                    terminal == SkillRunState.FAILED
                            ? mapFailure(signal)
                            : null;
            if (completed == Operation.EQUIP_ARMOR) {
                reconcileArmorAfterNonSuccess(
                        player, run);
            }
            terminateOrRecover(
                    player,
                    run,
                    terminal,
                    code,
                    summary,
                    currentTick);
            return;
        }
        transition(
                run,
                SkillRunState.RUNNING,
                currentTick,
                "动作完成：" + completed.id);
        switch (completed) {
            case SWAP_FOOD -> {
                FoodCandidate food =
                        Objects.requireNonNull(run.food, "food");
                run.swapApplied = true;
                run.food = food.inHotbar();
                if (run.pendingTerminalState != null) {
                    beginPendingTerminalCleanup(
                            player, run, currentTick);
                    return;
                }
                if (run.food.hotbarSlot
                        == run.previousSelected) {
                    submitUseFood(
                            player, run, currentTick);
                } else {
                    submitSelectFood(
                            player, run, currentTick);
                }
            }
            case SELECT_FOOD -> {
                FoodCandidate food =
                        Objects.requireNonNull(run.food, "food");
                run.selectionChangedBySkill =
                        run.previousSelected
                                != food.hotbarSlot;
                if (run.pendingTerminalState != null) {
                    beginPendingTerminalCleanup(
                            player, run, currentTick);
                    return;
                }
                submitUseFood(player, run, currentTick);
            }
            case USE_FOOD -> {
                if (run.pendingTerminalState != null) {
                    beginPendingTerminalCleanup(
                            player, run, currentTick);
                    return;
                }
                if (!run.itemConsumptionObserved
                        || !run.foodIncreaseObserved) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.ACTION_FAILED,
                            "动作完成证据未同时证明精确消耗一份食物且食物值上升",
                            currentTick);
                    return;
                }
                if (run.swapApplied) {
                    submitRestoreFoodSlot(
                            player, run, currentTick);
                    return;
                }
                restoreSelectionOrSucceed(
                        player, run, currentTick);
            }
            case RESTORE_FOOD_SLOT -> {
                run.swapApplied = false;
                restoreSelectionOrSucceed(
                        player, run, currentTick);
            }
            case RESTORE_SELECTION -> {
                run.selectionChangedBySkill = false;
                completeSuccessOrPendingTerminal(
                        run, currentTick);
            }
            case EQUIP_ARMOR -> {
                InventoryMenuSwapPlan completedPlan =
                        Objects.requireNonNull(
                                run.armorPlan,
                                "armorPlan");
                if (!hasEvidence(
                                signal,
                                "menu.click_count",
                                Integer.toString(
                                        completedPlan
                                                .orderedSteps()
                                                .size()))
                        || !hasEvidence(
                                signal,
                                "inventory.multiset_preserved",
                                "true")) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.ACTION_FAILED,
                            "原生菜单回执未证明完整点击计划与物品守恒",
                            currentTick);
                    return;
                }
                ArmorUpgradeSelection selection =
                        Objects.requireNonNull(
                                run.armorSelection,
                                "armorSelection");
                if (!completedPlan.initialSnapshot()
                                .itemAt(selection
                                        .sourceInventorySlot())
                                .equals(selection.candidate()
                                        .itemFingerprint())
                        || !completedPlan.finalSnapshot()
                                .itemAt(selection
                                        .targetInventorySlot())
                                .equals(selection.candidate()
                                        .itemFingerprint())) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.INTERNAL_ERROR,
                            "完成回执与冻结的盔甲菜单计划不一致",
                            currentTick);
                    return;
                }
                InventoryMenuSnapshot currentSnapshot;
                try {
                    currentSnapshot =
                            MinecraftActionSnapshot
                                    .inventoryMenu(player);
                } catch (RuntimeException exception) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.WORLD_CHANGED,
                            "盔甲动作完成后原生背包菜单已不可复核",
                            currentTick);
                    return;
                }
                if (!currentSnapshot
                        .layoutEqualsIgnoringState(
                                completedPlan
                                        .finalSnapshot())) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.WORLD_CHANGED,
                            "盔甲动作完成后背包布局被外部修改",
                            currentTick);
                    return;
                }
                if (!run.equippedArmorTargets.add(
                        selection.targetInventorySlot())) {
                    terminateOrRecover(
                            player,
                            run,
                            SkillRunState.FAILED,
                            SkillFailureCode.WORLD_CHANGED,
                            "同一盔甲槽被重复提交",
                            currentTick);
                    return;
                }
                run.armorChanges++;
                run.armorSelection = null;
                run.armorPlan = null;
                if (run.pendingTerminalState != null) {
                    run.pendingTerminalSummary =
                            requireSummary(
                                    Objects.requireNonNull(
                                                    run.pendingTerminalSummary,
                                                    "pendingTerminalSummary")
                                            + "；已安全提交 "
                                            + run.armorChanges
                                            + " 个盔甲升级");
                    completeSuccessOrPendingTerminal(
                            run, currentTick);
                    return;
                }
                if (run.armorChanges
                        >= MAXIMUM_ARMOR_CHANGES) {
                    succeedArmor(run, currentTick);
                    return;
                }
                planAndSubmitNextArmorUpgrade(
                        player, run, currentTick);
            }
        }
    }

    private static void reconcileArmorAfterNonSuccess(
            BotServerPlayer player, ActiveRun run) {
        ArmorUpgradeSelection selection =
                run.armorSelection;
        InventoryMenuSwapPlan plan = run.armorPlan;
        if (selection == null || plan == null) {
            return;
        }
        try {
            InventoryMenuSnapshot current =
                    MinecraftActionSnapshot
                            .inventoryMenu(player);
            if (current.layoutEqualsIgnoringState(
                            plan.finalSnapshot())
                    && plan.finalSnapshot()
                            .itemAt(selection
                                    .targetInventorySlot())
                            .equals(selection.candidate()
                                    .itemFingerprint())
                    && run.equippedArmorTargets.add(
                            selection.targetInventorySlot())) {
                run.armorChanges++;
            }
        } catch (RuntimeException ignored) {
            // 动作运行时已经给出终态；这里仅补记可精确证明的 final endpoint。
        } finally {
            run.armorSelection = null;
            run.armorPlan = null;
        }
    }

    private void retryCleanup(
            BotServerPlayer player,
            ActiveRun run,
            Operation completed,
            long currentTick) {
        switch (completed) {
            case RESTORE_FOOD_SLOT ->
                    submitRestoreFoodSlot(
                            player, run, currentTick);
            case RESTORE_SELECTION ->
                    submitRestoreSelection(
                            player, run, currentTick);
            case SWAP_FOOD,
                    SELECT_FOOD,
                    USE_FOOD,
                    EQUIP_ARMOR ->
                    throw new IllegalArgumentException(
                            "operation is not a cleanup action");
        }
    }

    private boolean restoreSelectionOrSucceed(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        if (run.selectionChangedBySkill
                && run.previousSelected
                        != player.getInventory().selected) {
            return submitRestoreSelection(
                    player, run, currentTick);
        }
        run.selectionChangedBySkill = false;
        completeSuccessOrPendingTerminal(
                run, currentTick);
        return true;
    }

    private boolean closeRunAfterGenerationCleanup(
            ActiveRun run,
            boolean actionCleanupConfirmed,
            String unsafeSummary,
            String cancelledSummary,
            long currentTick) {
        if (!actionCleanupConfirmed) {
            failUnsafe(
                    run,
                    run.unsafeFailureCode != null
                            ? run.unsafeFailureCode
                            : SkillFailureCode
                                    .UNSAFE_CONTROL_STATE,
                    unsafeSummary,
                    currentTick);
            return false;
        }

        InventoryLayoutCleanupResult layoutResult =
                cleanupGenerationLayout(run);
        if (!successfulLayoutCleanup(layoutResult)) {
            failUnsafe(
                    run,
                    layoutResult
                                    == InventoryLayoutCleanupResult
                                            .UNSAFE
                            ? SkillFailureCode
                                    .ITEM_CONSERVATION_VIOLATION
                            : SkillFailureCode
                                    .UNSAFE_CONTROL_STATE,
                    "生命周期背包补偿未取得安全回执："
                            + layoutResult.name(),
                    currentTick);
            return false;
        }

        acknowledgeGenerationClose(run);
        finish(
                run,
                SkillRunState.CANCELLED,
                layoutResult
                                == InventoryLayoutCleanupResult
                                        .SAFE_LAYOUT_COMMITTED
                        ? cancelledSummary
                                + "；外部布局已作为当前合法状态提交"
                        : cancelledSummary
                                + "；临时背包状态已恢复",
                currentTick);
        return true;
    }

    private InventoryLayoutCleanupResult
            cleanupGenerationLayout(ActiveRun run) {
        if (run.layoutLease == null) {
            return InventoryLayoutCleanupResult.ALREADY_SAFE;
        }
        InventoryLayoutCleanupRequest request =
                new InventoryLayoutCleanupRequest(
                        run.layoutLease);
        try {
            return Objects.requireNonNull(
                    generationLayoutCompensator.cleanup(
                            run.botId,
                            run.generation,
                            request),
                    "generation layout cleanup result");
        } catch (RuntimeException exception) {
            return InventoryLayoutCleanupResult.UNSAFE;
        }
    }

    private static boolean successfulLayoutCleanup(
            InventoryLayoutCleanupResult result) {
        return result == InventoryLayoutCleanupResult.RESTORED
                || result
                        == InventoryLayoutCleanupResult.ALREADY_SAFE
                || result
                        == InventoryLayoutCleanupResult
                                .SAFE_LAYOUT_COMMITTED;
    }

    private static void acknowledgeGenerationClose(
            ActiveRun run) {
        run.activeActionId = null;
        run.activeActionRevision = -1L;
        run.activeActionCancellationRequested = false;
        run.operation = null;
        run.swapApplied = false;
        run.selectionChangedBySkill = false;
        run.pendingTerminalState = null;
        run.pendingFailureCode = null;
        run.pendingTerminalSummary = null;
        run.unsafeFailureCode = null;
        run.unsafeFailureSummary = null;
    }

    private void terminateOrRecover(
            BotServerPlayer player,
            ActiveRun run,
            SkillRunState terminal,
            SkillFailureCode code,
            String summary,
            long currentTick) {
        if (!terminal.isTerminal()
                || terminal == SkillRunState.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "recovery requires a non-success terminal state");
        }
        if (terminal == SkillRunState.FAILED) {
            Objects.requireNonNull(code, "code");
        } else if (code != null) {
            throw new IllegalArgumentException(
                    "non-failed terminal must not carry a failure code");
        }
        String terminalSummary = summary;
        if (run.kind
                        == SurvivalSkillKind
                                .EQUIP_BASIC_ARMOR
                && run.armorChanges > 0) {
            terminalSummary =
                    requireSummary(
                            summary
                                    + "；已安全提交 "
                                    + run.armorChanges
                                    + " 个盔甲升级");
        }
        if (run.pendingTerminalState == null) {
            run.pendingTerminalState = terminal;
            run.pendingFailureCode = code;
            run.pendingTerminalSummary =
                    requireSummary(terminalSummary);
        }
        if (run.swapApplied
                || (run.selectionChangedBySkill
                        && run.previousSelected
                                != player.getInventory().selected)) {
            beginPendingTerminalCleanup(
                    player, run, currentTick);
        } else {
            completeSuccessOrPendingTerminal(
                    run, currentTick);
        }
    }

    private void beginPendingTerminalCleanup(
            BotServerPlayer player,
            ActiveRun run,
            long currentTick) {
        if (run.pendingTerminalState == null) {
            throw new IllegalStateException(
                    "terminal cleanup requires a pending terminal");
        }
        transition(
                run,
                SkillRunState.RECOVERING,
                currentTick,
                "清理未完成进食留下的临时背包状态");
        transition(
                run,
                SkillRunState.RUNNING,
                currentTick,
                "恢复进食前的背包布局与快捷栏选择");
        if (run.swapApplied) {
            submitRestoreFoodSlot(
                    player, run, currentTick);
        } else if (run.selectionChangedBySkill
                && run.previousSelected
                != player.getInventory().selected) {
            submitRestoreSelection(
                    player, run, currentTick);
        } else {
            completeSuccessOrPendingTerminal(
                    run, currentTick);
        }
    }

    private void completeSuccessOrPendingTerminal(
            ActiveRun run, long currentTick) {
        if (run.pendingTerminalState == null) {
            switch (run.kind) {
                case EAT_FOOD ->
                        succeedEating(run, currentTick);
                case EQUIP_BASIC_ARMOR ->
                        succeedArmor(run, currentTick);
            }
            return;
        }
        SkillRunState terminal = run.pendingTerminalState;
        SkillFailureCode code = run.pendingFailureCode;
        String summary = Objects.requireNonNull(
                run.pendingTerminalSummary,
                "pendingTerminalSummary");
        run.pendingTerminalState = null;
        run.pendingFailureCode = null;
        run.pendingTerminalSummary = null;
        if (terminal == SkillRunState.FAILED) {
            fail(
                    run,
                    Objects.requireNonNull(code, "code"),
                    summary,
                    currentTick);
        } else {
            finish(
                    run,
                    terminal,
                    summary,
                    currentTick);
        }
    }

    private void succeedEating(
            ActiveRun run, long currentTick) {
        transition(
                run,
                SkillRunState.VERIFYING,
                currentTick,
                "复核真实食物值");
        finish(
                run,
                SkillRunState.SUCCEEDED,
                "真实物品使用已提高食物值",
                currentTick);
    }

    private void succeedArmor(
            ActiveRun run, long currentTick) {
        if (run.armorChanges <= 0
                || run.armorChanges
                        > MAXIMUM_ARMOR_CHANGES) {
            throw new IllegalStateException(
                    "successful armor run must commit 1..4 upgrades");
        }
        transition(
                run,
                SkillRunState.VERIFYING,
                currentTick,
                "复核已提交的基础盔甲升级");
        finish(
                run,
                SkillRunState.SUCCEEDED,
                "已通过原生菜单完成 "
                        + run.armorChanges
                        + " 个背包盔甲升级",
                currentTick);
    }

    private boolean activate(
            ActiveRun run, long currentTick) {
        SkillSignalInbox.BindStatus binding = signals.bind(
                run.runId, run.botId, run.generation);
        if (binding != SkillSignalInbox.BindStatus.BOUND) {
            return false;
        }
        if (activeRuns.putIfAbsent(run.botId, run) != null) {
            signals.unbind(run.runId);
            return false;
        }
        rememberView(run.botId, view(run));
        run.updatedTick = currentTick;
        return true;
    }

    private void fail(
            ActiveRun run,
            SkillFailureCode code,
            String summary,
            long currentTick) {
        run.failureCode = Objects.requireNonNull(code, "code");
        finish(
                run,
                SkillRunState.FAILED,
                summary,
                currentTick);
    }

    private void failUnsafe(
            ActiveRun run,
            SkillFailureCode code,
            String summary,
            long currentTick) {
        if (run.unsafeFailureCode == null) {
            run.unsafeFailureCode =
                    Objects.requireNonNull(code, "code");
            run.unsafeFailureSummary =
                    requireSummary(summary);
        }
        retryUnsafeFailure(run, currentTick);
    }

    private void retryUnsafeFailure(
            ActiveRun run, long currentTick) {
        SkillFailureCode code = Objects.requireNonNull(
                run.unsafeFailureCode,
                "unsafeFailureCode");
        String summary = Objects.requireNonNull(
                run.unsafeFailureSummary,
                "unsafeFailureSummary");
        boolean confirmed;
        try {
            confirmed = generationQuarantiner.quarantine(
                    run.botId,
                    run.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            return;
        }
        if (!confirmed) {
            return;
        }
        /*
         * 动作权威已隔离不等于临时背包布局已经安全。只有布局补偿器
         * 明确给出安全端点后，才允许解绑 signal 并释放 layout lease。
         */
        InventoryLayoutCleanupResult layoutResult =
                cleanupGenerationLayout(run);
        if (!successfulLayoutCleanup(layoutResult)) {
            return;
        }
        if (run.kind
                == SurvivalSkillKind.EQUIP_BASIC_ARMOR) {
            resolver.resolve(run.botId, run.generation)
                    .ifPresent(player ->
                            reconcileArmorAfterNonSuccess(
                                    player, run));
        }

        String settledSummary = switch (layoutResult) {
            case RESTORED ->
                    summary + "；临时背包布局已恢复";
            case SAFE_LAYOUT_COMMITTED ->
                    summary + "；外部布局已作为安全端点提交";
            case ALREADY_SAFE -> summary;
            case STALE, BLOCKED, UNSAFE ->
                    throw new IllegalStateException(
                            "unsafe layout result passed the settlement gate");
        };
        if (run.kind
                        == SurvivalSkillKind.EQUIP_BASIC_ARMOR
                && run.armorChanges > 0) {
            settledSummary = settledSummary
                    + "；已安全提交 "
                    + run.armorChanges
                    + " 个盔甲升级";
        }
        run.quarantineConfirmed = true;
        run.unsafeFailureCode = null;
        run.unsafeFailureSummary = null;
        fail(
                run,
                code,
                requireSummary(settledSummary),
                currentTick);
    }

    private void finish(
            ActiveRun run,
            SkillRunState terminal,
            String summary,
            long currentTick) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException(
                    "finish requires a terminal skill state");
        }
        boolean unresolved = run.activeActionId != null
                || run.swapApplied
                || run.pendingTerminalState != null
                || run.selectionChangedBySkill
                || run.unsafeFailureCode != null;
        boolean closureConfirmed =
                (terminal == SkillRunState.FAILED
                                && run.quarantineConfirmed)
                        || ((terminal == SkillRunState.FAILED
                                        || terminal
                                                == SkillRunState
                                                        .CANCELLED)
                                && run.generationCloseConfirmed);
        if (unresolved
                && !closureConfirmed) {
            throw new IllegalStateException(
                    "dirty skill terminal requires a confirmed generation closure");
        }
        releaseLayoutLease(run);
        transition(run, terminal, currentTick, summary);
        activeRuns.remove(run.botId, run);
        signals.unbind(run.runId);
        rememberView(run.botId, view(run));
    }

    private void transition(
            ActiveRun run,
            SkillRunState next,
            long currentTick,
            String summary) {
        String safeSummary = requireSummary(summary);
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "skill transition tick must not move backwards");
        }
        run.state.requireTransitionTo(next);
        long nextRevision = Math.incrementExact(
                run.stateRevision);
        run.state = next;
        run.stateRevision = nextRevision;
        run.updatedTick = currentTick;
        run.safeSummary = safeSummary;
        rememberView(run.botId, view(run));
    }

    private void rememberView(
            UUID botId, SurvivalSkillRunView view) {
        latestViews.remove(botId);
        latestViews.put(botId, view);
        while (latestViews.size() > VIEW_CAPACITY) {
            UUID oldest =
                    latestViews.keySet().iterator().next();
            latestViews.remove(oldest);
        }
    }

    private void releaseLayoutLease(ActiveRun run) {
        if (!run.layoutLeaseOpen) {
            return;
        }
        try {
            generationLayoutCompensator.release(
                    run.botId,
                    run.generation,
                    run.runId);
        } catch (RuntimeException ignored) {
            // The lease is an internal one-shot fence, not live world state.
        }
        run.layoutLeaseOpen = false;
    }

    private SurvivalSkillRunView view(ActiveRun run) {
        return new SurvivalSkillRunView(
                run.runId,
                run.botId,
                run.generation,
                run.kind,
                run.state,
                run.stateRevision,
                run.startedTick,
                run.updatedTick,
                run.deadlineTick,
                run.operationSequence,
                Optional.ofNullable(run.failureCode),
                run.safeSummary);
    }

    private void register(SkillDescriptor descriptor) {
        SkillRegistry.RegisterStatus status =
                registry.register(descriptor);
        if (status != SkillRegistry.RegisterStatus.REGISTERED
                && status
                        != SkillRegistry.RegisterStatus
                                .ALREADY_REGISTERED) {
            throw new IllegalStateException(
                    "Cannot register built-in skill "
                            + descriptor.id()
                            + ": "
                            + status);
        }
    }

    private static Optional<SurvivalSkillKind> kindFor(
            HazardType type) {
        return switch (type) {
            case FOOD_CRITICAL ->
                    Optional.of(
                            SurvivalSkillKind.EAT_FOOD);
            case EXPLOSION_IMMINENT,
                    PROJECTILE_IMPACT,
                    HOSTILE_TARGETING,
                    FALL_IMMINENT,
                    UNSAFE_NEXT_STEP,
                    VOID_EXPOSURE,
                    LAVA_CONTACT,
                    FIRE_CONTACT,
                    DROWNING,
                    SUFFOCATING,
                    FREEZING,
                    ONGOING_DAMAGE,
                    HARMFUL_EFFECT,
                    UNKNOWN_DAMAGE,
                    HEALTH_CRITICAL ->
                    Optional.empty();
        };
    }

    private static SkillSignalStatus signalStatus(
            ActionState state) {
        return switch (state) {
            case SUCCEEDED -> SkillSignalStatus.SUCCEEDED;
            case FAILED -> SkillSignalStatus.FAILED;
            case CANCELLED -> SkillSignalStatus.CANCELLED;
            case PREEMPTED -> SkillSignalStatus.PREEMPTED;
            case STALE -> SkillSignalStatus.STALE;
            case QUEUED,
                    VALIDATING,
                    RUNNING,
                    VERIFYING ->
                    throw new IllegalArgumentException(
                            "Action outcome must be terminal");
        };
    }

    private static SkillFailureCode mapActionFailure(
            ActionFailureCode failureCode) {
        return switch (failureCode) {
            case NONE -> SkillFailureCode.NONE;
            case BOT_NOT_ACTIVE ->
                    SkillFailureCode.BOT_NOT_ACTIVE;
            case STALE_GENERATION ->
                    SkillFailureCode.STALE_GENERATION;
            case DEADLINE_EXCEEDED,
                    MAX_TICKS_EXCEEDED ->
                    SkillFailureCode.TIMEOUT;
            case LEDGER_CAPACITY_EXCEEDED,
                    ACTION_ALIAS_CAPACITY_EXCEEDED,
                    RUNTIME_CAPACITY_EXCEEDED,
                    CHANNEL_BUSY ->
                    SkillFailureCode.SERVER_OVERLOADED;
            case PRECONDITION_FAILED ->
                    SkillFailureCode.WORLD_CHANGED;
            case UNSAFE_CONTROL_STATE ->
                    SkillFailureCode.UNSAFE_CONTROL_STATE;
            case PERMISSION_DENIED ->
                    SkillFailureCode.PERMISSION_DENIED;
            case TARGET_UNAVAILABLE ->
                    SkillFailureCode.TARGET_GONE;
            case CANCELLED, PREEMPTED ->
                    SkillFailureCode.DANGER_PREEMPTED;
            case INTERNAL_ERROR,
                    BACKEND_RESULT_MISMATCH ->
                    SkillFailureCode.INTERNAL_ERROR;
            case INVALID_REQUEST,
                    DUPLICATE_IN_PROGRESS,
                    IDEMPOTENCY_CONFLICT,
                    UNSUPPORTED ->
                    SkillFailureCode.ACTION_FAILED;
        };
    }

    private static SkillFailureCode mapFailure(
            SkillSignal signal) {
        SkillFailureCode failureCode =
                signal.failureCode();
        return failureCode == SkillFailureCode.NONE
                ? SkillFailureCode.INTERNAL_ERROR
                : failureCode;
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L
                || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "skill tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "survival skills require the owner server thread");
        }
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.strip();
        if (trimmed.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "skill summary is not safe");
        }
        if (trimmed.length()
                > SurvivalSkillRunView.MAX_SUMMARY_LENGTH) {
            int end = SurvivalSkillRunView.MAX_SUMMARY_LENGTH;
            if (Character.isHighSurrogate(
                    trimmed.charAt(end - 1))) {
                end--;
            }
            trimmed = trimmed.substring(0, end).stripTrailing();
        }
        return trimmed;
    }

    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(
                UUID botId, long generation);
    }

    @FunctionalInterface
    public interface ActionSubmitter {
        ActionMailbox.Submission submit(
                ActionEnvelope envelope, ActionPriority priority);
    }

    @FunctionalInterface
    public interface ActionCanceller {
        void cancel(UUID botId, UUID actionId);
    }

    public interface GenerationLayoutCompensator {
        boolean open(
                UUID botId,
                long generation,
                InventoryLayoutCleanupLease layoutLease);

        InventoryLayoutCleanupResult cleanup(
                UUID botId,
                long generation,
                InventoryLayoutCleanupRequest request);

        void release(
                UUID botId,
                long generation,
                UUID runId);

        void closeGeneration(
                UUID botId, long generation);

        void closeAll();
    }

    @FunctionalInterface
    public interface GenerationQuarantiner {
        boolean quarantine(
                UUID botId,
                long generation,
                long currentTick);
    }

    @FunctionalInterface
    public interface GenerationSafetyInspector {
        boolean isSafe(UUID botId, long generation);
    }

    private enum Operation {
        SWAP_FOOD("swap_food"),
        SELECT_FOOD("select_food"),
        USE_FOOD("use_food"),
        EQUIP_ARMOR("equip_armor"),
        RESTORE_FOOD_SLOT("restore_food_slot"),
        RESTORE_SELECTION("restore_selection");

        private final String id;

        Operation(String id) {
            this.id = id;
        }

        private boolean cleanup() {
            return this == RESTORE_FOOD_SLOT
                    || this == RESTORE_SELECTION;
        }

        private SkillRunState waitingState() {
            return this == EQUIP_ARMOR
                    ? SkillRunState.WAITING_MENU
                    : SkillRunState.WAITING_ACTION;
        }
    }

    private record FoodCandidate(
            int inventorySlot,
            int hotbarSlot,
            WorldInteractionActionSpec.Hand hand,
            ItemStackFingerprint fingerprint,
            int nutrition,
            float saturation,
            boolean harmful,
            boolean usable,
            boolean alreadyInHand) {
        private FoodCandidate {
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        private SurvivalSkillPolicy.FoodOption option() {
            return new SurvivalSkillPolicy.FoodOption(
                    inventorySlot,
                    nutrition,
                    saturation,
                    harmful,
                    usable,
                    alreadyInHand);
        }

        private FoodCandidate inHotbar() {
            return new FoodCandidate(
                    hotbarSlot,
                    hotbarSlot,
                    WorldInteractionActionSpec.Hand.MAIN_HAND,
                    fingerprint,
                    nutrition,
                    saturation,
                    harmful,
                    usable,
                    true);
        }
    }

    private static final class ActiveRun {
        private final UUID runId;
        private final UUID incidentId;
        private final UUID botId;
        private final long generation;
        private final SurvivalSkillKind kind;
        private final long startedTick;
        private final long workDeadlineTick;
        private final long deadlineTick;
        private SkillRunState state =
                SkillRunState.CREATED;
        private long updatedTick;
        private long stateRevision;
        private int operationSequence;
        private int cleanupSubmissionAttempts;
        private UUID activeActionId;
        private long activeActionRevision = -1L;
        private boolean activeActionCancellationRequested;
        private Operation operation;
        private FoodCandidate food;
        private ArmorUpgradeSelection armorSelection;
        private InventoryMenuSwapPlan armorPlan;
        private final Set<Integer> equippedArmorTargets =
                new HashSet<>();
        private int armorChanges;
        private int previousSelected;
        private InventoryLayoutCleanupLease layoutLease;
        private boolean layoutLeaseOpen;
        private int swappedSourceSlot = -1;
        private int swappedHotbarSlot = -1;
        private boolean swapApplied;
        private boolean selectionChangedBySkill;
        private boolean itemConsumptionObserved;
        private boolean foodIncreaseObserved;
        private boolean quarantineConfirmed;
        private boolean generationCloseConfirmed;
        private SkillFailureCode unsafeFailureCode;
        private String unsafeFailureSummary;
        private SkillRunState pendingTerminalState;
        private SkillFailureCode pendingFailureCode;
        private String pendingTerminalSummary;
        private SkillFailureCode failureCode;
        private String safeSummary = "技能运行已创建";

        private ActiveRun(
                UUID runId,
                UUID incidentId,
                UUID botId,
                long generation,
                SurvivalSkillKind kind,
                long startedTick,
                long workDeadlineTick,
                long deadlineTick) {
            this.runId = Objects.requireNonNull(
                    runId, "runId");
            this.incidentId = Objects.requireNonNull(
                    incidentId, "incidentId");
            this.botId = Objects.requireNonNull(
                    botId, "botId");
            if (generation <= 0L
                    || startedTick < 0L
                    || workDeadlineTick <= startedTick
                    || deadlineTick <= workDeadlineTick) {
                throw new IllegalArgumentException(
                        "active skill run bounds are invalid");
            }
            this.generation = generation;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.startedTick = startedTick;
            this.updatedTick = startedTick;
            this.workDeadlineTick = workDeadlineTick;
            this.deadlineTick = deadlineTick;
        }
    }
}
