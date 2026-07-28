package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionOutcomeSink;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.AuthorityEvent;
import io.github.greytaiwolf.botplayer.perception.event.EventReadWindow;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionProjection;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventBus;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventDraft;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventSource;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.perception.sensor.BotSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.InventorySensor;
import io.github.greytaiwolf.botplayer.perception.sensor.LocalBlockSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.LocalEntitySensor;
import io.github.greytaiwolf.botplayer.perception.sensor.NearbyThreatSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.SelfStateSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorContext;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorResult;
import io.github.greytaiwolf.botplayer.perception.sensor.SoundEventSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.VisionRaySensor;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityHypothesis;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityInferenceService;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityType;
import io.github.greytaiwolf.botplayer.worldmodel.EvidenceRef;
import io.github.greytaiwolf.botplayer.worldmodel.FactKey;
import io.github.greytaiwolf.botplayer.worldmodel.FactSource;
import io.github.greytaiwolf.botplayer.worldmodel.FactStatus;
import io.github.greytaiwolf.botplayer.worldmodel.FactValue;
import io.github.greytaiwolf.botplayer.worldmodel.InvalidationRule;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionKind;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionScope;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionStamp;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFactDraft;
import io.github.greytaiwolf.botplayer.worldmodel.WorldModelService;
import io.github.greytaiwolf.botplayer.worldmodel.WorldRevisionTracker;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 在服务器主线程编排有限感知、认知事件、短期事实和活动推断。
 *
 * <p>该服务只跨 Tick 保存不可变 DTO 与 generation key；Minecraft 活动对象只在一次
 * {@link #tick(long)} 调用栈内存在。
 */
public final class PerceptionService {
    private static final int ACTIVITY_EVENT_LIMIT = 8_192;
    private static final int FACT_TTL_TICKS = 100;
    private static final int ENTITY_FACT_TTL_TICKS = 40;
    private static final int INVENTORY_FRESHNESS_TICKS = 20;
    private static final int MAX_ACTIVITY_ACTORS = 64;
    private static final UUID UNKNOWN_ACTOR_ID = new UUID(0L, 1L);

    private final MinecraftServer server;
    private final PerceptionSettings settings;
    private final BiFunction<UUID, Long, Optional<BotServerPlayer>> resolver;
    private final SemanticEventBus eventBus;
    private final WorldRevisionTracker revisions;
    private final AuthorityEventCollector collector;
    private final EventPerceptionProjector projector =
            new EventPerceptionProjector();
    private final PerceptionLoadController loadController;
    private final ActivityInferenceService activityInference;
    private final List<BotSensor> sensors;
    private final int pendingSoundCapacity;
    private final Map<RuntimeKey, Deque<SoundObservationCandidate>>
            pendingSounds = new LinkedHashMap<>();
    private final int soundDeliveriesPerTick;
    private final Map<RuntimeKey, RuntimeState> runtimes =
            new LinkedHashMap<>();
    private final Map<UUID, RuntimeKey> activeRuntimeByBot =
            new LinkedHashMap<>();
    private final AtomicLong droppedSounds = new AtomicLong();
    private long isolatedFailures;
    private int roundRobinOffset;
    private int soundRoundRobinOffset;
    private int pendingSoundCount;

    public PerceptionService(
            MinecraftServer server,
            PerceptionSettings settings,
            BiFunction<UUID, Long, Optional<BotServerPlayer>> resolver) {
        this.server = Objects.requireNonNull(server, "server");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.eventBus = new SemanticEventBus(
                settings.authorityEventCapacity(),
                settings.eventCapacityPerBot());
        this.revisions =
                new WorldRevisionTracker(settings.revisionScopeCapacity());
        this.collector = new AuthorityEventCollector(
                server,
                eventBus,
                revisions,
                settings.pendingEventCapacity(),
                this::routeReliableAuthority);
        this.loadController = new PerceptionLoadController(
                settings.degradeMspt(),
                settings.criticalMspt(),
                settings.recoverMspt(),
                settings.criticalRecoverMspt());
        this.activityInference = new ActivityInferenceService(
                settings.activityWindowTicks(),
                ACTIVITY_EVENT_LIMIT);
        this.sensors = createSensors(settings);
        this.pendingSoundCapacity =
                settings.pendingEventCapacity();
        this.soundDeliveriesPerTick = Math.min(
                settings.pendingEventCapacity(),
                Math.max(
                        8,
                        Math.min(
                                1_024,
                                settings.globalWorkPerTick() / 4)));
    }

    public ActionOutcomeSink actionOutcomeSink() {
        return collector::onActionOutcome;
    }

    public AuthorityEventCollector authorityCollector() {
        return collector;
    }

    public SemanticEventBus eventBus() {
        return eventBus;
    }

    public WorldRevisionTracker revisions() {
        return revisions;
    }

    public void activate(UUID botId, long generation) {
        requireServerThread();
        RuntimeKey key = new RuntimeKey(botId, generation);
        RuntimeKey previous =
                activeRuntimeByBot.put(botId, key);
        if (previous != null && !previous.equals(key)) {
            runtimes.remove(previous);
            eventBus.clearGeneration(
                    previous.botId(), previous.generation());
            removePendingSounds(previous);
        }
        runtimes.computeIfAbsent(
                key,
                ignored -> new RuntimeState(
                        botId,
                        generation,
                        eventBus.currentAuthoritySeq(),
                        settings.factCapacityPerBot(),
                        settings.visualRange()));
    }

    public void closeGeneration(UUID botId, long generation) {
        requireServerThread();
        RuntimeKey key = new RuntimeKey(botId, generation);
        runtimes.remove(key);
        activeRuntimeByBot.remove(botId, key);
        eventBus.clearGeneration(botId, generation);
        removePendingSounds(key);
    }

    public void closeBot(UUID botId) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        runtimes.keySet().removeIf(key -> key.botId().equals(botId));
        activeRuntimeByBot.remove(botId);
        eventBus.clearBot(botId);
        List<RuntimeKey> pendingKeys = pendingSounds.keySet()
                .stream()
                .filter(key -> key.botId().equals(botId))
                .toList();
        pendingKeys.forEach(this::removePendingSounds);
    }

    public void shutdown() {
        requireServerThread();
        runtimes.clear();
        activeRuntimeByBot.clear();
        pendingSounds.clear();
        pendingSoundCount = 0;
    }

    /**
     * listener 可在服务器线程的包发送路径提交不可变候选；异步或失效 generation
     * 默认丢弃。每个 generation 受公平份额约束，不能独占全局声音队列。
     */
    public void offerSound(SoundObservationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!server.isSameThread()) {
            droppedSounds.incrementAndGet();
            return;
        }
        RuntimeKey key = new RuntimeKey(
                candidate.botId(),
                candidate.botGeneration());
        if (!runtimes.containsKey(key)) {
            droppedSounds.incrementAndGet();
            return;
        }
        Deque<SoundObservationCandidate> queue =
                pendingSounds.computeIfAbsent(
                        key, ignored -> new ArrayDeque<>());
        int fairShare = Math.max(
                1,
                pendingSoundCapacity
                        / Math.max(1, runtimes.size()));
        while (queue.size() >= fairShare) {
            queue.removeFirst();
            pendingSoundCount--;
            droppedSounds.incrementAndGet();
        }
        if (pendingSoundCount >= pendingSoundCapacity) {
            evictLargestSoundQueue();
        }
        pendingSounds.putIfAbsent(key, queue);
        queue.addLast(candidate);
        pendingSoundCount++;
    }

    public void recordTickDurationNanos(long durationNanos) {
        requireServerThread();
        loadController.recordTickDurationNanos(durationNanos);
    }

    public void tick(long currentTick) {
        requireServerThread();
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        try {
            collector.flush(currentTick);
        } catch (RuntimeException exception) {
            isolateFailure("权威候选复核", exception);
        }
        try {
            drainSounds(currentTick);
        } catch (RuntimeException exception) {
            isolateFailure("定向声音归并", exception);
        }
        if (runtimes.isEmpty()) {
            return;
        }

        PerceptionPressure pressure = loadController.pressure();
        int projectionWork = settings.globalWorkPerTick() / 4;
        GlobalPerceptionBudget projectionBudget =
                new GlobalPerceptionBudget(projectionWork);
        GlobalPerceptionBudget sensorBudget =
                new GlobalPerceptionBudget(
                        settings.globalWorkPerTick() - projectionWork);
        List<RuntimeState> ordered = orderedRuntimes();
        for (RuntimeState runtime : ordered) {
            try {
                BotServerPlayer player = resolver
                        .apply(runtime.botId, runtime.generation)
                        .orElse(null);
                if (player == null) {
                    /*
                     * generation 仍登记但实体解析失败时，旧快照不再代表当前世界，
                     * 必须立即撤下，等待同一 generation 的实体重新可解析。
                     */
                    runtime.latest = null;
                    continue;
                }
                sampleRuntime(
                        runtime,
                        player,
                        currentTick,
                        pressure,
                        sensorBudget,
                        projectionBudget);
            } catch (RuntimeException exception) {
                /*
                 * resolver、投影或快照管线出现未预期异常时，上一 Tick 快照不再
                 * 可声明为当前可用；先撤下再隔离，等待后续 Tick 完整重建。
                 */
                runtime.latest = null;
                isolateFailure(
                        "bot 感知采样 " + runtime.botId,
                        exception);
            }
        }
        roundRobinOffset = Math.floorMod(
                roundRobinOffset + 1, Math.max(1, runtimes.size()));
    }

    public Optional<ObservationSnapshot> latest(
            UUID botId, long generation) {
        requireServerThread();
        RuntimeState runtime =
                runtimes.get(new RuntimeKey(botId, generation));
        return runtime == null
                ? Optional.empty()
                : Optional.ofNullable(runtime.latest);
    }

    public List<WorldFact> recentFacts(
            UUID botId, long generation, int limit) {
        requireServerThread();
        RuntimeState runtime =
                runtimes.get(new RuntimeKey(botId, generation));
        if (runtime == null) {
            return List.of();
        }
        return runtime.worldModel.recent(
                Math.min(Math.max(limit, 0), settings.factCapacityPerBot()));
    }

    public long coverageGapCount(
            UUID botId, long generation) {
        requireServerThread();
        RuntimeState runtime =
                runtimes.get(new RuntimeKey(botId, generation));
        return runtime == null ? 0L : runtime.coverageGapCount;
    }

    public AuthorityEvent recordActivityCorrection(
            UUID targetBotId,
            long targetGeneration,
            UUID actorId,
            String actorName,
            UUID correctorId,
            String correctorName,
            String correctedActivity,
            long gameTick) {
        requireServerThread();
        RuntimeKey target =
                new RuntimeKey(targetBotId, targetGeneration);
        if (!runtimes.containsKey(target)) {
            throw new IllegalArgumentException(
                    "target bot generation is not active");
        }
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correctorId, "correctorId");
        String normalized =
                normalizeCorrection(correctedActivity);
        ActorRef actor = new ActorRef(
                actorId,
                "minecraft:player",
                requireText(actorName, "actorName", 64));
        AuthorityEvent authority =
                eventBus.publishAuthority(new SemanticEventDraft(
                UUID.randomUUID(),
                resolver.apply(targetBotId, targetGeneration)
                        .map(player -> player.serverLevel()
                                .dimension()
                                .location()
                                .toString())
                        .orElse("minecraft:overworld"),
                gameTick,
                Instant.now(),
                SemanticEventType.PLAYER_CORRECTION,
                SemanticEventOutcome.COMMITTED,
                Optional.empty(),
                List.of(actor),
                List.of(normalized),
                Map.of(
                        "target.bot_id",
                        targetBotId.toString(),
                        "target.bot_generation",
                        Long.toString(targetGeneration),
                        "corrected_activity",
                        normalized,
                        "audit.corrector_id",
                        correctorId.toString(),
                        "audit.corrector_name",
                        requireText(
                                correctorName,
                                "correctorName",
                                64)),
                SemanticEventSource.PLAYER_CORRECTION,
                Set.of(PerceptionChannel.DIRECT),
                1.0F,
                Set.of("correction:player_activity"),
                false));
        routeReliableAuthority(authority);
        return authority;
    }

    public long droppedSounds() {
        return droppedSounds.get();
    }

    public long isolatedFailures() {
        requireServerThread();
        return isolatedFailures;
    }

    public PerceptionPressure pressure() {
        return loadController.pressure();
    }

    public double smoothedMspt() {
        return loadController.smoothedMspt();
    }

    private void sampleRuntime(
            RuntimeState runtime,
            BotServerPlayer player,
            long currentTick,
            PerceptionPressure pressure,
            GlobalPerceptionBudget sensorGlobalBudget,
            GlobalPerceptionBudget projectionGlobalBudget) {
        PerceptionBudget budget = new PerceptionBudget(
                sensorGlobalBudget, settings.budgetLimits(pressure));
        PerceptionBudget projectionBudget = new PerceptionBudget(
                projectionGlobalBudget, settings.budgetLimits(pressure));
        /*
         * 权威流扫描使用独立且不进入快照的预算，避免未感知事件改变公开传感器预算。
         */
        projectAuthority(
                runtime,
                player,
                currentTick,
                projectionBudget);
        List<PerceivedEvent> recentEvents = eventBus.recentSemanticEvents(
                runtime.botId,
                runtime.generation,
                settings.recentEventLimit());
        List<PerceivedEvent> recentSounds = eventBus.recentSounds(
                runtime.botId,
                runtime.generation,
                settings.recentEventLimit());
        List<BlockPos> focusPositions =
                focusPositions(recentEvents);
        SensorContext context = new SensorContext(
                player,
                currentTick,
                Optional.empty(),
                focusPositions,
                recentSounds);
        EnumSet<SensorId> sampled = EnumSet.noneOf(SensorId.class);
        EnumSet<SensorId> successful = EnumSet.noneOf(SensorId.class);
        EnumSet<SensorId> truncated = EnumSet.noneOf(SensorId.class);

        for (BotSensor sensor : sensors) {
            long lastSampleTick =
                    runtime.lastSampleTicks.getOrDefault(sensor.id(), -1L);
            if (!sensor.schedule()
                    .due(lastSampleTick, currentTick, pressure)) {
                continue;
            }
            runtime.lastSampleTicks.put(sensor.id(), currentTick);
            sampled.add(sensor.id());
            try {
                SensorResult result = sensor.sample(context, budget);
                if (result.truncated()) {
                    truncated.add(sensor.id());
                }
                applyResult(runtime, result);
                if (!(result
                        instanceof SensorResult.Unavailable)) {
                    successful.add(sensor.id());
                    runtime.lastSuccessfulSampleTicks.put(
                            sensor.id(), currentTick);
                }
            } catch (RuntimeException exception) {
                truncated.add(sensor.id());
                isolateFailure(
                        "传感器 " + sensor.id().name(),
                        exception);
            }
        }

        if (runtime.self == null
                || runtime.inventory == null
                || runtime.lastSuccessfulSampleTicks.getOrDefault(
                                SensorId.SELF_STATE,
                                -1L)
                        != currentTick
                || isOlderThan(
                        runtime,
                        SensorId.INVENTORY,
                        currentTick,
                        INVENTORY_FRESHNESS_TICKS)) {
            /*
             * self 必须来自当前 Tick，背包必须仍在完整采样 TTL 内；关键输入失效时
             * 直接撤下快照，不能把旧位置、生命或半份背包伪装成当前实值。
             */
            runtime.latest = null;
            return;
        }
        String dimension = player.serverLevel()
                .dimension()
                .location()
                .toString();
        expireStaleSensorData(runtime, currentTick);
        recentEvents = eventBus.recentSemanticEvents(
                runtime.botId,
                runtime.generation,
                settings.recentEventLimit());
        List<ActivityHypothesis> activities =
                inferActivities(
                        runtime,
                        recentEvents,
                        currentTick,
                        pressure);
        long perceivedWatermark = eventBus.currentPerceivedSeq(
                runtime.botId,
                runtime.generation);
        runtime.worldModel.expire(currentTick);
        long nextSnapshotId = Math.incrementExact(runtime.snapshotId);
        ObservationSnapshot nextSnapshot = new ObservationSnapshot(
                runtime.streamId,
                nextSnapshotId,
                perceivedWatermark,
                runtime.botId,
                runtime.generation,
                player.serverLevel().dimension().location().toString(),
                currentTick,
                runtime.self,
                runtime.inventory,
                runtime.vision,
                runtime.entities,
                runtime.threats,
                runtime.blocks,
                runtime.sounds,
                recentEvents,
                activities,
                new PerceptionLimits(
                        pressure,
                        budget.report(),
                        sampled,
                        truncated,
                        runtime.lastSuccessfulSampleTicks,
                        false));
        /*
         * 先完整构造下一份快照，再写入引用它的事实；关键输入门禁失败或快照
         * 构造失败时，不会留下指向未发布 snapshotId 的 EvidenceRef。
         */
        if (successful.contains(SensorId.LOCAL_ENTITY)) {
            try {
                observeEntities(
                        runtime,
                        runtime.entities,
                        dimension,
                        currentTick);
            } catch (RuntimeException exception) {
                isolateFailure("实体事实写入", exception);
            }
        }
        if (successful.contains(SensorId.LOCAL_BLOCK)) {
            try {
                observeBlocks(
                        runtime,
                        runtime.blocks,
                        currentTick);
            } catch (RuntimeException exception) {
                isolateFailure("方块事实写入", exception);
            }
        }
        runtime.snapshotId = nextSnapshotId;
        runtime.latest = nextSnapshot;
    }

    private void projectAuthority(
            RuntimeState runtime,
            BotServerPlayer player,
            long currentTick,
            PerceptionBudget budget) {
        int readable = Math.min(
                settings.eventReadsPerBot(),
                budget.remaining(BudgetKind.EVENT_READ));
        if (readable <= 0) {
            return;
        }
        EventReadWindow<AuthorityEvent> diagnosticWindow =
                eventBus.authoritySince(runtime.authorityCursor, 1);
        if (diagnosticWindow.truncatedBefore()) {
            /*
             * 全局权威环缺口只进入管理员诊断；它不能改变 bot 认知，否则会泄露
             * 远处或其他维度的隐藏活动。旧事实继续依靠 TTL 和再次观察收敛。
             */
            runtime.coverageGapCount++;
        }
        long newestSequence =
                eventBus.currentAuthoritySeq();
        int tailLimit = Math.min(
                settings.authorityEventCapacity(),
                readable + 1);
        List<AuthorityEvent> spatialTail =
                eventBus.recentSpatialAuthority(tailLimit);
        if (spatialTail.size() > readable) {
            AuthorityEvent omitted =
                    spatialTail.getFirst();
            if (omitted.eventSeq() > runtime.authorityCursor
                    && omitted.event().gameTick() == currentTick) {
                /*
                 * 有限预算只能选取同 Tick 最新窗口；更早空间事件不会回放，
                 * 但必须进入管理员 coverage 诊断，不能静默丢失。
                 */
                runtime.coverageGapCount++;
            }
            spatialTail = spatialTail.subList(
                    spatialTail.size() - readable,
                    spatialTail.size());
        }
        for (AuthorityEvent authority : spatialTail) {
            if (authority.eventSeq()
                    <= runtime.authorityCursor) {
                continue;
            }
            if (budget.remaining(BudgetKind.EVENT_READ) <= 0) {
                break;
            }
            try {
                Optional<PerceptionProjection> projection =
                        projector.projectSpatial(
                                player,
                                authority,
                                budget,
                                settings.visualRange(),
                                settings.hearingRange(),
                                currentTick);
                projection.ifPresent(value ->
                        eventBus.publishPerceived(
                                runtime.botId,
                                runtime.generation,
                                authority,
                                value));
                if (projection.isPresent()) {
                    invalidateChangedFact(
                            runtime,
                            authority,
                            authority.event().outcome()
                                    != SemanticEventOutcome.COMMITTED);
                }
            } catch (RuntimeException exception) {
                runtime.coverageGapCount++;
                isolateFailure("权威事件投影", exception);
            }
        }
        /*
         * 空间通道只在事件发生 Tick 可投影，故每轮直接追到尾部；SELF/DIRECT
         * 已由可靠定向路径保序投递，不能让历史隐藏事件 backlog 饿死当前视觉。
         */
        runtime.authorityCursor = newestSequence;
    }

    /**
     * SELF 与 DIRECT 是 generation 定向的可靠通道，不依赖全局空间扫描 backlog。
     */
    private void routeReliableAuthority(
            AuthorityEvent authority) {
        SemanticEventDraft event =
                Objects.requireNonNull(authority, "authority").event();
        Map<RuntimeKey, PerceptionChannel> targets =
                new LinkedHashMap<>();
        if (event.channels().contains(PerceptionChannel.DIRECT)) {
            String targetId =
                    event.delta().get("target.bot_id");
            String targetGeneration =
                    event.delta().get("target.bot_generation");
            if (targetId != null && targetGeneration != null) {
                try {
                    RuntimeKey target = new RuntimeKey(
                            UUID.fromString(targetId),
                            Long.parseLong(targetGeneration));
                    if (runtimes.containsKey(target)) {
                        targets.put(
                                target,
                                PerceptionChannel.DIRECT);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 非法定向元数据默认不投递。
                }
            }
        }
        if (event.channels().contains(PerceptionChannel.SELF)) {
            String explicitTarget =
                    event.delta().get("target.id");
            String generationText =
                    event.delta().get("action.generation");
            if (generationText == null) {
                generationText = event.delta().get(
                        "routing.bot_generation");
            }
            Long requiredGeneration = null;
            boolean selfRoutingValid = true;
            if (generationText != null) {
                try {
                    long parsed =
                            Long.parseLong(generationText);
                    if (parsed <= 0L) {
                        selfRoutingValid = false;
                    } else {
                        requiredGeneration = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    selfRoutingValid = false;
                }
            }
            if (selfRoutingValid) {
                for (ActorRef actor : event.actors()) {
                    if (explicitTarget != null
                            && !explicitTarget.equals(
                                    actor.actorId().toString())) {
                        continue;
                    }
                    RuntimeKey target =
                            activeRuntimeByBot.get(actor.actorId());
                    if (target != null
                            && (requiredGeneration == null
                                    || requiredGeneration
                                            == target.generation())) {
                        targets.putIfAbsent(
                                target,
                                PerceptionChannel.SELF);
                    }
                }
            }
        }
        for (Map.Entry<RuntimeKey, PerceptionChannel> entry :
                targets.entrySet()) {
            RuntimeState runtime =
                    runtimes.get(entry.getKey());
            if (runtime == null) {
                continue;
            }
            PerceptionProjection projection =
                    entry.getValue() == PerceptionChannel.SELF
                            ? PerceptionProjection.self(
                                    authority,
                                    runtime.botId,
                                    event.gameTick(),
                                    event.confidence())
                            : PerceptionProjection.full(
                                    authority,
                                    event.gameTick(),
                                    PerceptionChannel.DIRECT,
                                    event.confidence());
            eventBus.publishPerceived(
                    runtime.botId,
                    runtime.generation,
                    authority,
                    projection);
            invalidateChangedFact(
                    runtime,
                    authority,
                    event.outcome()
                            != SemanticEventOutcome.COMMITTED);
        }
    }

    private void drainSounds(long currentTick) {
        Map<RoutedSoundKey, LinkedHashMap<RuntimeKey, SoundObservationCandidate>>
                grouped = new LinkedHashMap<>();
        for (SoundObservationCandidate candidate :
                drainSoundCandidates(soundDeliveriesPerTick)) {
            try {
                RuntimeKey runtimeKey = new RuntimeKey(
                        candidate.botId(),
                        candidate.botGeneration());
                RuntimeState runtime = runtimes.get(runtimeKey);
                BotServerPlayer player = resolver
                        .apply(candidate.botId(), candidate.botGeneration())
                        .orElse(null);
                if (runtime == null
                        || player == null
                        || candidate.gameTick() != currentTick
                        || !candidate.dimension()
                                .equals(player.serverLevel()
                                        .dimension()
                                        .location()
                                        .toString())) {
                    droppedSounds.incrementAndGet();
                    continue;
                }
                RoutedSoundKey soundKey =
                        RoutedSoundKey.from(candidate);
                grouped.computeIfAbsent(
                                soundKey,
                                ignored -> new LinkedHashMap<>())
                        .putIfAbsent(runtimeKey, candidate);
            } catch (RuntimeException exception) {
                droppedSounds.incrementAndGet();
                isolateFailure("定向声音候选", exception);
            }
        }
        int discarded = pendingSoundCount;
        if (discarded > 0) {
            pendingSounds.clear();
            pendingSoundCount = 0;
            droppedSounds.addAndGet(discarded);
        }

        for (Map.Entry<
                        RoutedSoundKey,
                        LinkedHashMap<RuntimeKey, SoundObservationCandidate>>
                entry : grouped.entrySet()) {
            RoutedSoundKey sound = entry.getKey();
            AuthorityEvent authority =
                    publishSoundAuthority(sound, currentTick);
            for (SoundObservationCandidate delivery :
                    entry.getValue().values()) {
                eventBus.publishPerceived(
                        delivery.botId(),
                        delivery.botGeneration(),
                        authority,
                        new PerceptionProjection(
                                currentTick,
                                SemanticEventType.SOUND_PLAYED,
                                SemanticEventOutcome.COMMITTED,
                                Optional.of(delivery.position()),
                                List.of(),
                                List.of(delivery.soundId()),
                                soundDelta(delivery),
                                SemanticEventSource.VANILLA_CLIENTBOUND,
                                PerceptionChannel.AUDIBLE,
                                1.0F,
                                Set.of("sound:vanilla_routed")));
            }
        }
    }

    private List<SoundObservationCandidate> drainSoundCandidates(
            int limit) {
        List<RuntimeKey> keys = pendingSounds.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(
                                (RuntimeKey key) ->
                                        key.botId().toString())
                        .thenComparingLong(RuntimeKey::generation))
                .toList();
        if (keys.isEmpty() || limit <= 0) {
            return List.of();
        }
        int offset = Math.floorMod(
                soundRoundRobinOffset, keys.size());
        List<RuntimeKey> ordered =
                new ArrayList<>(keys.size());
        ordered.addAll(keys.subList(offset, keys.size()));
        ordered.addAll(keys.subList(0, offset));

        List<SoundObservationCandidate> drained =
                new ArrayList<>(Math.min(limit, pendingSoundCount));
        boolean progressed = true;
        while (drained.size() < limit && progressed) {
            progressed = false;
            for (RuntimeKey key : ordered) {
                Deque<SoundObservationCandidate> queue =
                        pendingSounds.get(key);
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                drained.add(queue.removeFirst());
                pendingSoundCount--;
                progressed = true;
                if (drained.size() == limit) {
                    break;
                }
            }
        }
        pendingSounds.entrySet().removeIf(
                entry -> entry.getValue().isEmpty());
        soundRoundRobinOffset = Math.floorMod(
                soundRoundRobinOffset + 1,
                Math.max(1, keys.size()));
        return List.copyOf(drained);
    }

    private void evictLargestSoundQueue() {
        Map.Entry<RuntimeKey, Deque<SoundObservationCandidate>> largest =
                pendingSounds.entrySet()
                        .stream()
                        .filter(entry -> !entry.getValue().isEmpty())
                        .max(Comparator.comparingInt(
                                        (Map.Entry<
                                                        RuntimeKey,
                                                        Deque<
                                                                SoundObservationCandidate>>
                                                entry) ->
                                                entry.getValue().size())
                                .thenComparing(entry ->
                                        entry.getKey()
                                                .botId()
                                                .toString())
                                .thenComparingLong(entry ->
                                        entry.getKey().generation()))
                        .orElse(null);
        if (largest == null) {
            return;
        }
        largest.getValue().removeFirst();
        pendingSoundCount--;
        droppedSounds.incrementAndGet();
        if (largest.getValue().isEmpty()) {
            pendingSounds.remove(largest.getKey());
        }
    }

    private void removePendingSounds(RuntimeKey key) {
        Deque<SoundObservationCandidate> removed =
                pendingSounds.remove(key);
        if (removed != null) {
            pendingSoundCount -= removed.size();
        }
    }

    private AuthorityEvent publishSoundAuthority(
            RoutedSoundKey sound, long currentTick) {
        return eventBus.publishRoutedSoundAudit(
                SemanticEventDraft.create(
                sound.dimension(),
                currentTick,
                SemanticEventType.SOUND_PLAYED,
                SemanticEventOutcome.COMMITTED,
                Optional.of(sound.position()),
                List.of(),
                List.of(sound.soundId()),
                soundDelta(sound),
                SemanticEventSource.VANILLA_CLIENTBOUND,
                Set.of(PerceptionChannel.AUDIBLE),
                1.0F,
                Set.of("sound:vanilla_routed"),
                false));
    }

    private static Map<String, String> soundDelta(
            SoundObservationCandidate candidate) {
        return Map.of(
                "sound.source",
                candidate.source(),
                "sound.volume",
                Float.toString(candidate.volume()),
                "sound.pitch",
                Float.toString(candidate.pitch()));
    }

    private static Map<String, String> soundDelta(
            RoutedSoundKey sound) {
        return Map.of(
                "sound.source",
                sound.source(),
                "sound.volume",
                Float.toString(sound.volume()),
                "sound.pitch",
                Float.toString(sound.pitch()));
    }

    private void invalidateChangedFact(
            RuntimeState runtime,
            AuthorityEvent authority,
            boolean hidden) {
        SemanticEventDraft event = authority.event();
        if (!event.changesWorld()) {
            return;
        }
        String kindText = event.delta().get("scope.kind");
        String target = event.delta().get("scope.target");
        if (kindText == null || target == null) {
            return;
        }
        try {
            RevisionKind kind = RevisionKind.valueOf(
                    kindText.toUpperCase(Locale.ROOT));
            RevisionStamp stamp = new RevisionStamp(
                    parseRevision(event, "revision.global"),
                    parseRevision(event, "revision.dimension"),
                    parseRevision(event, "revision.target"));
            runtime.worldModel.invalidateScope(
                    new RevisionScope(event.dimension(), kind, target),
                    stamp,
                    event.gameTick(),
                    hidden);
            if (kind == RevisionKind.BLOCK
                    && event.delta().containsKey(
                            "container.revision.target")) {
                RevisionStamp containerStamp = new RevisionStamp(
                        parseRevision(event, "container.revision.global"),
                        parseRevision(event, "container.revision.dimension"),
                        parseRevision(event, "container.revision.target"));
                runtime.worldModel.invalidateScope(
                        new RevisionScope(
                                event.dimension(),
                                RevisionKind.CONTAINER,
                                target),
                        containerStamp,
                        event.gameTick(),
                        hidden);
            }
        } catch (IllegalArgumentException ignored) {
            runtime.coverageGapCount++;
        }
    }

    private static void applyResult(
            RuntimeState runtime, SensorResult result) {
        switch (result) {
            case SensorResult.SelfState self ->
                    runtime.self = self.observation();
            case SensorResult.Inventory inventory ->
                    runtime.inventory = inventory.observation();
            case SensorResult.Vision vision ->
                    runtime.vision = vision.observation();
            case SensorResult.Entities entities ->
                    runtime.entities = entities.observations();
            case SensorResult.Threats threats ->
                    runtime.threats = threats.observations();
            case SensorResult.Blocks blocks ->
                    runtime.blocks = blocks.observations();
            case SensorResult.Sounds sounds ->
                    runtime.sounds = sounds.observations();
            case SensorResult.Unavailable ignored -> {
                // 保留上一份不可变结果，并通过 limits.truncatedSensors 暴露本次降级。
            }
        }
    }

    private void expireStaleSensorData(
            RuntimeState runtime, long currentTick) {
        if (isOlderThan(
                runtime,
                SensorId.VISION_RAY,
                currentTick,
                20L)) {
            runtime.vision =
                    VisionObservation.stale(settings.visualRange());
        }
        if (isOlderThan(
                runtime,
                SensorId.NEARBY_THREAT,
                currentTick,
                10L)) {
            runtime.threats = List.of();
        }
        if (isOlderThan(
                runtime,
                SensorId.LOCAL_ENTITY,
                currentTick,
                20L)) {
            runtime.entities = List.of();
        }
        if (isOlderThan(
                runtime,
                SensorId.LOCAL_BLOCK,
                currentTick,
                40L)) {
            runtime.blocks = List.of();
        }
        if (isOlderThan(
                runtime,
                SensorId.SOUND_EVENT,
                currentTick,
                20L)) {
            runtime.sounds = List.of();
        }
    }

    private static boolean isOlderThan(
            RuntimeState runtime,
            SensorId sensor,
            long currentTick,
            long maximumAge) {
        Long successful =
                runtime.lastSuccessfulSampleTicks.get(sensor);
        return successful == null
                || currentTick - successful > maximumAge;
    }

    private void observeBlocks(
            RuntimeState runtime,
            List<BlockObservation> blocks,
            long currentTick) {
        long evidenceSequence = runtime.snapshotId + 1;
        for (BlockObservation block : blocks) {
            RevisionScope scope = new RevisionScope(
                    block.dimension(),
                    RevisionKind.BLOCK,
                    block.targetId());
            runtime.worldModel.observe(new WorldFactDraft(
                    new FactKey(
                            "block_state",
                            block.dimension(),
                            block.targetId()),
                    blockFactValue(block),
                    scope,
                    cognitiveRevision(),
                    currentTick,
                    1.0F,
                    block.lineOfSight()
                            ? FactSource.VISUAL
                            : FactSource.SELF,
                    List.of(new EvidenceRef(
                            "sensor",
                            runtime.streamId,
                            evidenceSequence,
                            "local_block:" + block.targetId())),
                    FactStatus.ACTIVE,
                    new InvalidationRule(
                            FACT_TTL_TICKS, true)));
            if ("true".equals(block.properties().get(
                    "botplayer.opaque_block_entity"))) {
                RevisionScope containerScope = new RevisionScope(
                        block.dimension(),
                        RevisionKind.CONTAINER,
                        block.targetId());
                runtime.worldModel.observe(new WorldFactDraft(
                        new FactKey(
                                "container_location",
                                block.dimension(),
                                block.targetId()),
                        new FactValue(Map.of(
                                "block_id", block.blockId(),
                                "opaque", "true")),
                        containerScope,
                        cognitiveRevision(),
                        currentTick,
                        1.0F,
                        block.lineOfSight()
                                ? FactSource.VISUAL
                                : FactSource.SELF,
                        List.of(new EvidenceRef(
                                "sensor",
                                runtime.streamId,
                                evidenceSequence,
                                "opaque_container_location:"
                                        + block.targetId())),
                        FactStatus.ACTIVE,
                        new InvalidationRule(
                                FACT_TTL_TICKS, true)));
            }
        }
    }

    private void observeEntities(
            RuntimeState runtime,
            List<EntityObservation> entities,
            String dimension,
            long currentTick) {
        long evidenceSequence = runtime.snapshotId + 1;
        for (EntityObservation entity : entities) {
            String targetId = entity.entityId().toString();
            RevisionScope scope = new RevisionScope(
                    dimension,
                    RevisionKind.ENTITY,
                    targetId);
            runtime.worldModel.observe(new WorldFactDraft(
                    new FactKey(
                            "entity_state",
                            dimension,
                            targetId),
                    new FactValue(Map.of(
                            "type_id",
                            entity.typeId(),
                            "relation",
                            entity.relation(),
                            "position_x",
                            Double.toString(entity.position().x()),
                            "position_y",
                            Double.toString(entity.position().y()),
                            "position_z",
                            Double.toString(entity.position().z()),
                            "velocity_x",
                            Double.toString(entity.velocity().x()),
                            "velocity_y",
                            Double.toString(entity.velocity().y()),
                            "velocity_z",
                            Double.toString(entity.velocity().z()),
                            "alive",
                            Boolean.toString(entity.alive()))),
                    scope,
                    cognitiveRevision(),
                    currentTick,
                    1.0F,
                    FactSource.VISUAL,
                    List.of(new EvidenceRef(
                            "sensor",
                            runtime.streamId,
                            evidenceSequence,
                            "local_entity:" + targetId)),
                    FactStatus.ACTIVE,
                    new InvalidationRule(
                            ENTITY_FACT_TTL_TICKS, true)));
        }
    }

    private List<ActivityHypothesis> inferActivities(
            RuntimeState runtime,
            List<PerceivedEvent> events,
            long currentTick,
            PerceptionPressure pressure) {
        int maximumActors = switch (pressure) {
            case NORMAL -> MAX_ACTIVITY_ACTORS;
            case DEGRADED -> 16;
            case CRITICAL -> 4;
        };
        LinkedHashSet<UUID> actorIds = new LinkedHashSet<>();
        actorIds.add(runtime.botId);
        events.stream()
                .filter(event ->
                        event.observedAtTick() <= currentTick
                                && currentTick - event.observedAtTick()
                                        <= settings.activityWindowTicks())
                /*
                 * actor 候选按新近证据优先，避免已过窗口的旧玩家长期占满
                 * MAX_ACTIVITY_ACTORS 并挤掉当前玩家。
                 */
                .sorted(Comparator.comparingLong(
                                PerceivedEvent::observedAtTick)
                        .reversed()
                        .thenComparing(
                                Comparator.comparingLong(
                                                PerceivedEvent::perceivedSeq)
                                        .reversed()))
                .flatMap(event -> event.actors().stream())
                .filter(actor -> actor.typeId()
                        .equals("minecraft:player"))
                .map(ActorRef::actorId)
                .filter(actorId -> !actorId.equals(UNKNOWN_ACTOR_ID))
                .filter(actorId -> !actorId.equals(runtime.botId))
                .distinct()
                .limit(maximumActors - 1L)
                .forEach(actorIds::add);
        Map<UUID, ActivityHypothesis> inferred =
                activityInference.inferAll(
                        actorIds, events, currentTick);
        /*
         * 每 Tick 在有界 recentEventLimit 上重算，严格遵守滑动窗口到期语义；
         * 不能在 watermark 不变时把已过期证据额外缓存 19 Tick。
         */
        return inferred.values().stream()
                .sorted(Comparator.comparing(
                        hypothesis -> hypothesis.actorId().toString()))
                .toList();
    }

    private List<RuntimeState> orderedRuntimes() {
        List<RuntimeState> ordered =
                new ArrayList<>(runtimes.values());
        ordered.sort(Comparator.comparing(
                        (RuntimeState runtime) ->
                                runtime.botId.toString())
                .thenComparingLong(runtime -> runtime.generation));
        if (ordered.size() > 1) {
            int offset =
                    Math.floorMod(roundRobinOffset, ordered.size());
            List<RuntimeState> rotated =
                    new ArrayList<>(ordered.size());
            rotated.addAll(ordered.subList(offset, ordered.size()));
            rotated.addAll(ordered.subList(0, offset));
            return rotated;
        }
        return ordered;
    }

    private static List<BotSensor> createSensors(
            PerceptionSettings settings) {
        int entityReads = settings.entityReadsPerBot();
        int threatReads = Math.max(1, entityReads / 3);
        int visionEntityReads = Math.max(1, entityReads / 4);
        int localEntityReads = Math.max(
                1,
                entityReads - threatReads - visionEntityReads);
        int nearbyObservations = Math.min(
                ObservationSnapshot.MAX_ENTITIES,
                localEntityReads);
        int threatObservations = Math.min(
                ObservationSnapshot.MAX_THREATS,
                threatReads);
        int blockObservations = Math.min(
                ObservationSnapshot.MAX_BLOCKS,
                settings.blockReadsPerBot());
        int focusPositions = Math.min(
                SensorContext.MAX_FOCUS_POSITIONS,
                settings.blockReadsPerBot());
        return List.of(
                new SelfStateSensor(),
                new InventorySensor(),
                new NearbyThreatSensor(
                        Math.min(32.0D, settings.entityRadius()),
                        threatObservations,
                        threatReads,
                        NearbyThreatSensor.DEFAULT_SCHEDULE),
                new VisionRaySensor(
                        settings.visualRange(),
                        visionEntityReads,
                        VisionRaySensor.DEFAULT_SCHEDULE),
                new LocalEntitySensor(
                        settings.entityRadius(),
                        nearbyObservations,
                        localEntityReads,
                        LocalEntitySensor.DEFAULT_SCHEDULE),
                new LocalBlockSensor(
                        settings.localBlockRadius(),
                        focusPositions,
                        blockObservations,
                        Math.min(32.0D, settings.visualRange()),
                        LocalBlockSensor.DEFAULT_SCHEDULE),
                new SoundEventSensor(
                        settings.recentEventLimit(),
                        SoundEventSensor.DEFAULT_SCHEDULE));
    }

    private static List<BlockPos> focusPositions(
            List<PerceivedEvent> events) {
        LinkedHashSet<BlockPos> positions =
                new LinkedHashSet<>();
        events.stream()
                .sorted(Comparator.comparingLong(
                        PerceivedEvent::perceivedSeq)
                        .reversed())
                .map(PerceivedEvent::position)
                .flatMap(Optional::stream)
                .map(point -> BlockPos.containing(
                        point.x(), point.y(), point.z()))
                .distinct()
                .limit(SensorContext.MAX_FOCUS_POSITIONS)
                .forEach(positions::add);
        return List.copyOf(positions);
    }

    private static FactValue blockFactValue(
            BlockObservation block) {
        LinkedHashMap<String, String> fields =
                new LinkedHashMap<>();
        fields.put("block_id", block.blockId());
        List<Map.Entry<String, String>> properties =
                block.properties().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .limit(FactValue.MAX_FIELDS - 2L)
                        .toList();
        for (int index = 0;
                index < properties.size();
                index++) {
            Map.Entry<String, String> entry =
                    properties.get(index);
            fields.put(
                    "property." + index,
                    entry.getKey() + "=" + entry.getValue());
        }
        if (block.properties().size()
                > FactValue.MAX_FIELDS - 2) {
            fields.put("properties_truncated", "true");
        }
        return new FactValue(fields);
    }

    private static long parseRevision(
            SemanticEventDraft event, String key) {
        String value = event.delta().get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing revision value " + key);
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "revision must not be negative");
        }
        return parsed;
    }

    private static RevisionStamp cognitiveRevision() {
        /*
         * 权威 revision 只用于内部失效判断。认知事实不携带全局/维度/目标计数，
         * 否则即使没有事件投影也能通过数字增量推断隐藏世界活动。
         */
        return new RevisionStamp(0L, 0L, 0L);
    }

    private static String normalizeCorrection(String value) {
        String normalized = requireText(
                        value, "correctedActivity", 32)
                .toLowerCase(Locale.ROOT);
        if (normalized.equals("none")) {
            return normalized;
        }
        try {
            ActivityType type = ActivityType.valueOf(
                    normalized.toUpperCase(Locale.ROOT));
            if (type == ActivityType.UNKNOWN) {
                throw new IllegalArgumentException(
                        "correction cannot use unknown");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "corrected activity must be one of "
                            + "idle, moving, exploring, mining, building, "
                            + "combat, farming, crafting, smelting, none",
                    exception);
        }
    }

    private static String requireText(
            String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "perception service requires the server thread");
        }
    }

    private void isolateFailure(
            String stage, RuntimeException exception) {
        isolatedFailures++;
        /*
         * 异常可能来自模组扩展对象。感知层必须 fail-closed，但保留低频诊断线索；
         * 每 64 次只输出一次，避免异常实体制造日志洪泛。
         */
        if ((isolatedFailures & 63L) == 1L) {
            BotPlayer.LOGGER.warn(
                    "BotPlayer P3 已隔离感知异常：{}（累计 {} 次）",
                    stage,
                    isolatedFailures,
                    exception);
        }
    }

    private record RuntimeKey(UUID botId, long generation) {
        private RuntimeKey {
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "generation must be positive");
            }
        }
    }

    private record RoutedSoundKey(
            String dimension,
            long gameTick,
            SpatialPoint position,
            String soundId,
            String source,
            float volume,
            float pitch) {
        private RoutedSoundKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(soundId, "soundId");
            Objects.requireNonNull(source, "source");
        }

        private static RoutedSoundKey from(
                SoundObservationCandidate candidate) {
            return new RoutedSoundKey(
                    candidate.dimension(),
                    candidate.gameTick(),
                    candidate.position(),
                    candidate.soundId(),
                    candidate.source(),
                    candidate.volume(),
                    candidate.pitch());
        }
    }

    private static final class RuntimeState {
        private final UUID botId;
        private final long generation;
        private final UUID streamId = UUID.randomUUID();
        private final WorldModelService worldModel;
        private final EnumMap<SensorId, Long> lastSampleTicks =
                new EnumMap<>(SensorId.class);
        private final EnumMap<SensorId, Long> lastSuccessfulSampleTicks =
                new EnumMap<>(SensorId.class);
        private long authorityCursor;
        private long snapshotId;
        private long coverageGapCount;
        private SelfObservation self;
        private InventoryObservation inventory;
        private VisionObservation vision;
        private List<EntityObservation> entities = List.of();
        private List<ThreatObservation> threats = List.of();
        private List<BlockObservation> blocks = List.of();
        private List<PerceivedEvent> sounds = List.of();
        private ObservationSnapshot latest;

        private RuntimeState(
                UUID botId,
                long generation,
                long authorityCursor,
                int factCapacity,
                double visualRange) {
            this.botId = botId;
            this.generation = generation;
            this.authorityCursor = authorityCursor;
            this.worldModel =
                    new WorldModelService(factCapacity);
            this.vision = VisionObservation.stale(visualRange);
        }
    }
}
