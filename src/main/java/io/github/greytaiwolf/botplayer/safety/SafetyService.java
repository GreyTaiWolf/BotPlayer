package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.action.JumpAction;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 每 Tick 读取真实玩家身体和固定近场，使用 P2 动作通道执行 L0 安全干预。
 */
public final class SafetyService {
    private static final int MAXIMUM_EFFECTS = 64;
    private static final int ACTION_RESULT_CAPACITY = 512;
    private static final long RECENT_DAMAGE_TICKS = 2L;

    private final SafetySettings settings;
    private final HazardEvaluator evaluator;
    private final NavigationService navigationService;
    private final ActionSubmitter actionSubmitter;
    private final InventoryCloser inventoryCloser;
    private final SafetyHandoff safetyHandoff;
    private final Map<UUID, BotSafetyState> states =
            new LinkedHashMap<>();
    private final ArrayBlockingQueue<CompletedAction> actionResults =
            new ArrayBlockingQueue<>(ACTION_RESULT_CAPACITY);

    public SafetyService(
            SafetySettings settings,
            NavigationService navigationService,
            ActionSubmitter actionSubmitter,
            InventoryCloser inventoryCloser) {
        this(
                settings,
                navigationService,
                actionSubmitter,
                inventoryCloser,
                SafetyHandoff.unavailable());
    }

    public SafetyService(
            SafetySettings settings,
            NavigationService navigationService,
            ActionSubmitter actionSubmitter,
            InventoryCloser inventoryCloser,
            SafetyHandoff safetyHandoff) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.evaluator = new HazardEvaluator(settings);
        this.navigationService =
                Objects.requireNonNull(
                        navigationService, "navigationService");
        this.actionSubmitter =
                Objects.requireNonNull(actionSubmitter, "actionSubmitter");
        this.inventoryCloser =
                Objects.requireNonNull(inventoryCloser, "inventoryCloser");
        this.safetyHandoff =
                Objects.requireNonNull(safetyHandoff, "safetyHandoff");
    }

    public void recordDamage(DamageCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        BotSafetyState state = states.computeIfAbsent(
                candidate.botId(),
                ignored -> new BotSafetyState(
                        candidate.botId(), candidate.botGeneration()));
        if (state.generation == candidate.botGeneration()) {
            state.recentDamage = candidate;
        }
    }

    public void tickBot(
            BotServerPlayer player,
            long generation,
            long currentTick) {
        Objects.requireNonNull(player, "player");
        drainActionResults();
        BotSafetyState state = states.compute(
                player.getUUID(),
                (ignored, existing) ->
                        existing == null
                                        || existing.generation != generation
                                ? new BotSafetyState(
                                        player.getUUID(), generation)
                                : existing);
        SafetyFrame frame = buildFrame(
                player, generation, currentTick, state);
        state.latestFrame = frame;
        Optional<HazardAssessment> primary = evaluator.primary(frame);
        if (primary.isPresent()) {
            observeHazard(
                    player,
                    state,
                    frame,
                    primary.orElseThrow(),
                    currentTick);
        } else {
            preemptUnsafeClearFrame(state, frame, currentTick);
            observeClear(state, currentTick);
        }
        state.previousVital =
                frame.health() + frame.absorption();
    }

    public Optional<SafetyIncidentView> inspect(UUID botId) {
        BotSafetyState state = states.get(
                Objects.requireNonNull(botId, "botId"));
        return state == null || state.incident == null
                ? Optional.empty()
                : Optional.of(state.incident.view());
    }

    public Optional<SafetyFrame> latestFrame(UUID botId) {
        BotSafetyState state = states.get(
                Objects.requireNonNull(botId, "botId"));
        return state == null
                ? Optional.empty()
                : Optional.ofNullable(state.latestFrame);
    }

    public void closeGeneration(
            UUID botId, long generation, long currentTick) {
        BotSafetyState state = states.get(
                Objects.requireNonNull(botId, "botId"));
        if (state == null || state.generation != generation) {
            return;
        }
        if (state.incident != null) {
            state.incident.state = SafetyState.FAILED;
            state.incident.lastObservedTick = currentTick;
        }
        states.remove(botId, state);
    }

    public void shutdown() {
        states.clear();
        actionResults.clear();
    }

    private SafetyFrame buildFrame(
            BotServerPlayer player,
            long generation,
            long currentTick,
            BotSafetyState state) {
        List<EffectSummary> effects = new ArrayList<>();
        boolean effectsTruncated = false;
        int effectReads = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effectReads++ >= MAXIMUM_EFFECTS) {
                effectsTruncated = true;
                break;
            }
            try {
                effects.add(new EffectSummary(
                        BuiltInRegistries.MOB_EFFECT
                                .getKey(effect.getEffect().value())
                                .toString(),
                        category(
                                effect.getEffect()
                                        .value()
                                        .getCategory()),
                        effect.getAmplifier(),
                        effect.getDuration(),
                        effect.isAmbient(),
                        effect.isVisible()));
            } catch (RuntimeException exception) {
                effectsTruncated = true;
            }
        }
        effects.sort(Comparator.comparing(EffectSummary::effectId));
        ThreatRead threats = readThreats(player);
        float totalVital = player.getHealth()
                + player.getAbsorptionAmount();
        float loss = Float.isFinite(state.previousVital)
                ? Math.max(0.0F, state.previousVital - totalVital)
                : 0.0F;
        DamageCandidate recent = state.recentDamage;
        if (recent != null
                && currentTick - recent.gameTick()
                        > RECENT_DAMAGE_TICKS) {
            recent = null;
            state.recentDamage = null;
        }
        Vec3 velocity = player.getDeltaMovement();
        SafetyFrame frame = new SafetyFrame(
                player.getUUID(),
                generation,
                currentTick,
                player.serverLevel()
                        .dimension()
                        .location()
                        .toString(),
                GridPoint.from(player.blockPosition()),
                velocity.x,
                velocity.y,
                velocity.z,
                player.onGround(),
                player.fallDistance,
                player.getHealth(),
                player.getMaxHealth(),
                player.getAbsorptionAmount(),
                player.getArmorValue(),
                player.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                player.getAttributeValue(
                        Attributes.KNOCKBACK_RESISTANCE),
                player.getAttributeValue(Attributes.MOVEMENT_SPEED),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(),
                player.getAirSupply(),
                player.getMaxAirSupply(),
                player.isOnFire(),
                player.isInLava(),
                player.isUnderWater(),
                player.isInWall(),
                player.getTicksFrozen(),
                unsafeForwardSupport(player),
                player.getY()
                        <= player.serverLevel().getMinBuildHeight() + 2.0D,
                effects,
                effectsTruncated,
                threats.threats(),
                threats.incomplete(),
                Optional.empty(),
                Optional.ofNullable(recent),
                loss);
        return frame.withSafeRetreat(confirmedRetreat(player, frame));
    }

    private ThreatRead readThreats(BotServerPlayer player) {
        ServerLevel level = player.serverLevel();
        AABB bounds = player.getBoundingBox()
                .inflate(settings.entityRadius());
        List<ThreatSummary> threats = new ArrayList<>();
        int[] rawReads = {0};
        boolean[] incomplete = {false};
        try {
            level.getEntities().get(
                    EntityTypeTest.forClass(Entity.class),
                    bounds,
                    entity -> {
                        if (rawReads[0]++
                                >= settings.maximumRawEntityReads()) {
                            incomplete[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.ABORT;
                        }
                        if (entity == null
                                || entity == player
                                || !isThreat(entity)) {
                            return AbortableIterationConsumer
                                    .Continuation.CONTINUE;
                        }
                        if (threats.size()
                                >= settings.maximumEntityReads()) {
                            incomplete[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.ABORT;
                        }
                        try {
                            threats.add(summarize(player, entity));
                        } catch (RuntimeException exception) {
                            incomplete[0] = true;
                        }
                        return AbortableIterationConsumer
                                .Continuation.CONTINUE;
                    });
        } catch (RuntimeException exception) {
            incomplete[0] = true;
        }
        threats.sort(
                Comparator.comparingDouble(ThreatSummary::distance)
                        .thenComparing(value ->
                                value.entityId().toString()));
        return new ThreatRead(threats, incomplete[0]);
    }

    private static boolean isThreat(Entity entity) {
        return entity instanceof Enemy
                || entity instanceof Projectile
                || entity instanceof PrimedTnt;
    }

    private static ThreatSummary summarize(
            BotServerPlayer player, Entity entity) {
        ThreatSummary.Kind kind;
        boolean targeting = false;
        double approach = 0.0D;
        if (entity instanceof PrimedTnt) {
            kind = ThreatSummary.Kind.EXPLOSIVE;
        } else if (entity instanceof Projectile projectile) {
            kind = ThreatSummary.Kind.PROJECTILE;
            if (projectile.getOwner() == player) {
                approach = -1.0D;
            } else {
                Vec3 velocity = projectile.getDeltaMovement();
                Vec3 toward =
                        player.getEyePosition()
                                .subtract(projectile.position());
                if (velocity.lengthSqr() > 1.0E-6D
                        && toward.lengthSqr() > 1.0E-6D) {
                    approach =
                            velocity.normalize().dot(toward.normalize());
                }
            }
        } else {
            kind = ThreatSummary.Kind.HOSTILE;
            targeting =
                    entity instanceof Mob mob
                            && mob.getTarget() == player;
        }
        return new ThreatSummary(
                entity.getUUID(),
                kind,
                GridPoint.from(entity.blockPosition()),
                Math.sqrt(player.distanceToSqr(entity)),
                Math.clamp(approach, -1.0D, 1.0D),
                targeting);
    }

    private boolean unsafeForwardSupport(BotServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() <= 1.0E-6D) {
            return false;
        }
        Vec3 velocity = player.getDeltaMovement();
        boolean movingForward =
                player.zza > 0.05F
                        || new Vec3(velocity.x, 0.0D, velocity.z)
                                        .dot(horizontal.normalize())
                                > 0.03D;
        if (!movingForward) {
            return false;
        }
        BlockPos ahead = BlockPos.containing(
                player.position().add(horizontal.normalize().scale(0.8D)));
        ServerLevel level = player.serverLevel();
        for (int drop = 1;
                drop <= settings.maximumSafeDrop() + 1;
                drop++) {
            BlockPos support = ahead.below(drop);
            if (!level.isLoaded(support)) {
                return true;
            }
            if (!level.getBlockState(support)
                    .getCollisionShape(level, support)
                    .isEmpty()) {
                return drop > settings.maximumSafeDrop();
            }
        }
        return true;
    }

    private void observeHazard(
            BotServerPlayer player,
            BotSafetyState state,
            SafetyFrame frame,
            HazardAssessment hazard,
            long currentTick) {
        SafetyIncident incident = state.incident;
        if (incident == null) {
            incident = new SafetyIncident(
                    UUID.randomUUID(),
                    player.getUUID(),
                    state.generation,
                    hazard,
                    currentTick);
            state.incident = incident;
        } else {
            if (incident.hazard.type() != hazard.type()
                    || incident.hazard.severity()
                            != hazard.severity()
                    || !incident.hazard.sourceEntityId().equals(
                            hazard.sourceEntityId())) {
                incident.hazard = hazard;
                incident.aligned = false;
                incident.escapeTarget = null;
            }
            incident.lastObservedTick = currentTick;
            incident.clearStableTicks = 0;
        }
        navigationService.suspendForSafety(
                player.getUUID(), state.generation, currentTick);
        if (!incident.inventoryClosed) {
            inventoryCloser.close(
                    player.getUUID(), state.generation);
            incident.inventoryClosed = true;
        }
        SafetyHandoffRequest handoff = new SafetyHandoffRequest(
                incident.incidentId,
                state.botId,
                state.generation,
                currentTick,
                incident.hazard,
                frame);
        preemptUnsafeHandoff(handoff);
        if (incident.activeActionId != null) {
            return;
        }
        if (!incident.stopIssued) {
            submit(
                    state,
                    incident,
                    new StopAction(),
                    ActionPurpose.STOP,
                    SafetyIntervention.STOP_AND_CLEAR_INPUT,
                    hazard.severity(),
                    currentTick,
                    5);
            return;
        }
        if (offerSkillHandoff(state, incident, handoff)) {
            return;
        }
        if (incident.interventions
                >= settings.maximumInterventions()) {
            incident.state = SafetyState.BLOCKED;
            incident.currentIntervention =
                    SafetyIntervention.HOLD_POSITION;
            return;
        }
        ActionPlan plan =
                chooseIntervention(player, frame, incident);
        if (plan == null) {
            incident.state = SafetyState.BLOCKED;
            incident.currentIntervention =
                    SafetyIntervention.HOLD_POSITION;
            return;
        }
        submit(
                state,
                incident,
                plan.action(),
                plan.purpose(),
                plan.intervention(),
                hazard.severity(),
                currentTick,
                plan.maximumTicks());
    }

    private boolean offerSkillHandoff(
            BotSafetyState state,
            SafetyIncident incident,
            SafetyHandoffRequest handoff) {
        HazardType type = incident.hazard.type();
        boolean recoveryOnly = type == HazardType.FOOD_CRITICAL
                || type == HazardType.HEALTH_CRITICAL
                || type == HazardType.HARMFUL_EFFECT;
        if (!recoveryOnly && type != HazardType.HOSTILE_TARGETING) {
            return false;
        }

        SafetyHandoffDecision decision;
        try {
            decision = Objects.requireNonNull(
                    safetyHandoff.request(handoff),
                    "safetyHandoff result");
        } catch (RuntimeException exception) {
            decision = SafetyHandoffDecision.FALLBACK;
        }
        if (decision.delegated()) {
            incident.state = SafetyState.DELEGATED;
            incident.currentIntervention =
                    SafetyIntervention.DELEGATE_TO_SURVIVAL_SKILL;
            return true;
        }
        if (recoveryOnly) {
            incident.state = SafetyState.BLOCKED;
            incident.currentIntervention =
                    SafetyIntervention.HOLD_POSITION;
            return true;
        }
        return false;
    }

    /** L0 的新帧先撤销已不满足边界的交接；回调失败时仍继续本地安全干预。 */
    private void preemptUnsafeHandoff(SafetyHandoffRequest handoff) {
        try {
            safetyHandoff.preempt(handoff);
        } catch (RuntimeException ignored) {
            // 交接端口不可信；L0 不能因撤销失败而放弃本地恢复。
        }
    }

    /**
     * 即使本 Tick 没有评出 primary hazard，也要把完整性丢失交给已有的有限自卫会话处理。
     * 没有这一跳，攻击动作可能在空的或截断的威胁帧上继续执行。
     */
    private void preemptUnsafeClearFrame(
            BotSafetyState state,
            SafetyFrame frame,
            long currentTick) {
        SafetyIncident incident = state.incident;
        if (incident == null) {
            return;
        }
        preemptUnsafeHandoff(new SafetyHandoffRequest(
                incident.incidentId,
                state.botId,
                state.generation,
                currentTick,
                incident.hazard,
                frame));
    }

    private ActionPlan chooseIntervention(
            BotServerPlayer player,
            SafetyFrame frame,
            SafetyIncident incident) {
        if (incident.hazard.type() == HazardType.FOOD_CRITICAL
                || incident.hazard.type()
                        == HazardType.HEALTH_CRITICAL
                || incident.hazard.type()
                        == HazardType.HARMFUL_EFFECT) {
            return null;
        }
        if (incident.hazard.type() == HazardType.DROWNING) {
            return new ActionPlan(
                    new JumpAction(0.2F, 0.0F, false, 4),
                    ActionPurpose.ESCAPE,
                    SafetyIntervention.SWIM_UP,
                    12);
        }
        GridPoint target = incident.escapeTarget;
        if (target == null) {
            target = safestNeighbor(player, frame);
            incident.escapeTarget = target;
            incident.aligned = false;
        }
        if (target == null) {
            if (incident.hazard.type() == HazardType.FALL_IMMINENT
                    || incident.hazard.type()
                            == HazardType.UNSAFE_NEXT_STEP) {
                return new ActionPlan(
                        new MoveInputAction(
                                -1.0F,
                                0.0F,
                                false,
                                true,
                                false,
                                settings.retreatInputTicks(),
                                settings.retreatInputTicks()),
                        ActionPurpose.ESCAPE,
                        SafetyIntervention.BACK_AWAY,
                        12);
            }
            return null;
        }
        if (!incident.aligned) {
            return new ActionPlan(
                    new LookAtAction(
                            target.x() + 0.5D,
                            target.y() + 0.75D,
                            target.z() + 0.5D),
                    ActionPurpose.ALIGN,
                    SafetyIntervention.MOVE_TO_SAFE_NEIGHBOR,
                    5);
        }
        return new ActionPlan(
                new MoveInputAction(
                        1.0F,
                        0.0F,
                        false,
                        incident.hazard.type()
                                == HazardType.FALL_IMMINENT,
                        false,
                        settings.retreatInputTicks(),
                        settings.retreatInputTicks()),
                ActionPurpose.ESCAPE,
                interventionFor(incident.hazard.type()),
                12);
    }

    private GridPoint safestNeighbor(
            BotServerPlayer player, SafetyFrame frame) {
        GridPoint origin = frame.position();
        List<GridPoint> candidates = new ArrayList<>();
        for (Direction direction :
                List.of(
                        Direction.NORTH,
                        Direction.EAST,
                        Direction.SOUTH,
                        Direction.WEST)) {
            GridPoint candidate = new GridPoint(
                    origin.x() + direction.getStepX(),
                    origin.y(),
                    origin.z() + direction.getStepZ());
            if (safeCell(player.serverLevel(), candidate)) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<ThreatSummary> relevantThreats = frame.threats().stream()
                .filter(SafetyService::isRelevantRetreatThreat)
                .toList();
        candidates.sort(
                Comparator.comparingLong(
                                (GridPoint point) ->
                                        minimumThreatDistanceSquared(
                                                point, relevantThreats))
                        .reversed()
                        .thenComparingInt(GridPoint::x)
                        .thenComparingInt(GridPoint::z));
        return candidates.getFirst();
    }

    /** 仅把会直接增加当前撤退风险的观察对象用于安全邻格评分。 */
    private static boolean isRelevantRetreatThreat(ThreatSummary threat) {
        return threat.kind() == ThreatSummary.Kind.EXPLOSIVE
                || threat.kind() == ThreatSummary.Kind.HOSTILE
                || (threat.kind() == ThreatSummary.Kind.PROJECTILE
                        && threat.approachScore() > 0.0D);
    }

    private static long minimumThreatDistanceSquared(
            GridPoint point, List<ThreatSummary> threats) {
        return threats.stream()
                .mapToLong(threat -> point.horizontalDistanceSquared(
                        threat.position()))
                .min()
                .orElse(0L);
    }

    /**
     * 只从完整威胁观测和 {@link #safeCell(ServerLevel, GridPoint)} 已验证的相邻格生成短输入。
     */
    private Optional<SafetyRetreat> confirmedRetreat(
            BotServerPlayer player, SafetyFrame frame) {
        if (frame.threatCoverageIncomplete()
                || frame.threats().stream().noneMatch(threat ->
                        threat.kind() == ThreatSummary.Kind.HOSTILE
                                && threat.targetingBot())) {
            return Optional.empty();
        }
        GridPoint target = safestNeighbor(player, frame);
        if (target == null) {
            return Optional.empty();
        }
        double deltaX = target.x() + 0.5D - player.getX();
        double deltaZ = target.z() + 0.5D - player.getZ();
        double horizontalLength = Math.hypot(deltaX, deltaZ);
        if (!Double.isFinite(horizontalLength)
                || horizontalLength <= 1.0E-6D) {
            return Optional.empty();
        }
        double yaw = Math.toRadians(player.getYRot());
        if (!Double.isFinite(yaw)) {
            return Optional.empty();
        }
        double sine = Math.sin(yaw);
        double cosine = Math.cos(yaw);
        float strafe = boundedInput(
                (deltaX * cosine + deltaZ * sine) / horizontalLength);
        float forward = boundedInput(
                (-deltaX * sine + deltaZ * cosine) / horizontalLength);
        if (forward == 0.0F && strafe == 0.0F) {
            return Optional.empty();
        }
        return Optional.of(new SafetyRetreat(
                target,
                forward,
                strafe,
                settings.retreatInputTicks()));
    }

    private static float boundedInput(double value) {
        return (float) Math.max(-1.0D, Math.min(1.0D, value));
    }

    private boolean safeCell(
            ServerLevel level, GridPoint point) {
        BlockPos feet = point.toBlockPos();
        BlockPos head = feet.above();
        if (!level.isLoaded(feet)
                || !level.isLoaded(head)
                || !level.getBlockState(feet)
                        .getCollisionShape(level, feet)
                        .isEmpty()
                || !level.getBlockState(head)
                        .getCollisionShape(level, head)
                        .isEmpty()
                || level.getFluidState(feet).is(FluidTags.LAVA)) {
            return false;
        }
        for (int drop = 1;
                drop <= settings.maximumSafeDrop();
                drop++) {
            BlockPos support = feet.below(drop);
            if (!level.isLoaded(support)) {
                return false;
            }
            if (!level.getBlockState(support)
                    .getCollisionShape(level, support)
                    .isEmpty()) {
                return !hazardous(level.getBlockState(support).getBlock());
            }
        }
        return false;
    }

    private static boolean hazardous(
            net.minecraft.world.level.block.Block block) {
        return block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.LAVA
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POWDER_SNOW
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE;
    }

    private void observeClear(
            BotSafetyState state, long currentTick) {
        SafetyIncident incident = state.incident;
        if (incident == null) {
            return;
        }
        incident.clearStableTicks++;
        incident.state = SafetyState.COOLDOWN;
        incident.lastObservedTick = currentTick;
        if (incident.clearStableTicks
                < settings.clearStableTicks()) {
            return;
        }
        navigationService.resumeAfterSafety(
                state.botId, state.generation, currentTick);
        state.lastIncident = incident.view();
        state.incident = null;
    }

    private void submit(
            BotSafetyState state,
            SafetyIncident incident,
            ActionRequest action,
            ActionPurpose purpose,
            SafetyIntervention intervention,
            HazardSeverity severity,
            long currentTick,
            int maximumTicks) {
        UUID actionId = UUID.randomUUID();
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                state.botId,
                state.generation,
                "safety:"
                        + incident.incidentId
                        + ":"
                        + incident.actionSequence++,
                currentTick + maximumTicks + 10L,
                maximumTicks,
                action,
                ActionOrigin.fromController(
                        ControllerKind.SAFETY,
                        incident.incidentId));
        ActionMailbox.Submission submission =
                actionSubmitter.submit(
                        envelope,
                        severity == HazardSeverity.EMERGENCY
                                ? ActionPriority.EMERGENCY
                                : ActionPriority.SURVIVAL);
        if (submission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            incident.interventions++;
            incident.state = SafetyState.ESCALATING;
            return;
        }
        incident.activeActionId = actionId;
        incident.activeActionPurpose = purpose;
        incident.currentIntervention = intervention;
        incident.state = SafetyState.INTERVENING;
        submission.completion()
                .orElseThrow()
                .whenComplete((outcome, throwable) ->
                        actionResults.offer(new CompletedAction(
                                state.botId,
                                state.generation,
                                incident.incidentId,
                                actionId,
                                purpose,
                                Optional.ofNullable(outcome),
                                throwable != null)));
    }

    private void drainActionResults() {
        CompletedAction result;
        while ((result = actionResults.poll()) != null) {
            BotSafetyState state = states.get(result.botId());
            if (state == null
                    || state.generation != result.generation()
                    || state.incident == null
                    || !state.incident.incidentId.equals(
                            result.incidentId())
                    || !Objects.equals(
                            state.incident.activeActionId,
                            result.actionId())) {
                continue;
            }
            SafetyIncident incident = state.incident;
            incident.activeActionId = null;
            incident.activeActionPurpose = null;
            boolean succeeded =
                    !result.callbackFailed()
                            && result.outcome().isPresent()
                            && result.outcome()
                                            .orElseThrow()
                                            .state()
                                    == ActionState.SUCCEEDED;
            if (!succeeded) {
                incident.interventions++;
                incident.state = SafetyState.ESCALATING;
                incident.aligned = false;
                incident.escapeTarget = null;
                continue;
            }
            switch (result.purpose()) {
                case STOP -> incident.stopIssued = true;
                case ALIGN -> incident.aligned = true;
                case ESCAPE -> {
                    incident.interventions++;
                    incident.aligned = false;
                    incident.escapeTarget = null;
                }
            }
            incident.state = SafetyState.VERIFYING;
        }
    }

    private static SafetyIntervention interventionFor(
            HazardType type) {
        return switch (type) {
            case EXPLOSION_IMMINENT ->
                    SafetyIntervention.MOVE_AWAY_FROM_EXPLOSION;
            case PROJECTILE_IMPACT ->
                    SafetyIntervention.DODGE_PROJECTILE;
            case HOSTILE_TARGETING ->
                    SafetyIntervention.RETREAT_FROM_HOSTILE;
            case FALL_IMMINENT,
                    UNSAFE_NEXT_STEP,
                    VOID_EXPOSURE ->
                    SafetyIntervention.BACK_AWAY;
            case DROWNING -> SafetyIntervention.SWIM_UP;
            case LAVA_CONTACT,
                    FIRE_CONTACT,
                    SUFFOCATING,
                    FREEZING,
                    ONGOING_DAMAGE,
                    HARMFUL_EFFECT,
                    UNKNOWN_DAMAGE,
                    HEALTH_CRITICAL,
                    FOOD_CRITICAL ->
                    SafetyIntervention.MOVE_TO_SAFE_NEIGHBOR;
        };
    }

    private static EffectSummary.Category category(
            MobEffectCategory category) {
        return switch (category) {
            case BENEFICIAL -> EffectSummary.Category.BENEFICIAL;
            case HARMFUL -> EffectSummary.Category.HARMFUL;
            case NEUTRAL -> EffectSummary.Category.NEUTRAL;
        };
    }

    @FunctionalInterface
    public interface ActionSubmitter {
        ActionMailbox.Submission submit(
                ActionEnvelope envelope, ActionPriority priority);
    }

    @FunctionalInterface
    public interface InventoryCloser {
        void close(UUID botId, long generation);
    }

    private enum ActionPurpose {
        STOP,
        ALIGN,
        ESCAPE
    }

    private record ActionPlan(
            ActionRequest action,
            ActionPurpose purpose,
            SafetyIntervention intervention,
            int maximumTicks) {}

    private record ThreatRead(
            List<ThreatSummary> threats, boolean incomplete) {
        private ThreatRead {
            threats = List.copyOf(threats);
        }
    }

    private record CompletedAction(
            UUID botId,
            long generation,
            UUID incidentId,
            UUID actionId,
            ActionPurpose purpose,
            Optional<ActionOutcome> outcome,
            boolean callbackFailed) {}

    private static final class BotSafetyState {
        private final UUID botId;
        private final long generation;
        private float previousVital = Float.NaN;
        private DamageCandidate recentDamage;
        private SafetyFrame latestFrame;
        private SafetyIncident incident;
        private SafetyIncidentView lastIncident;

        private BotSafetyState(UUID botId, long generation) {
            this.botId = botId;
            this.generation = generation;
        }
    }

    private static final class SafetyIncident {
        private final UUID incidentId;
        private final UUID botId;
        private final long generation;
        private final long firstObservedTick;
        private HazardAssessment hazard;
        private long lastObservedTick;
        private SafetyState state = SafetyState.OBSERVING;
        private int interventions;
        private int clearStableTicks;
        private long actionSequence;
        private UUID activeActionId;
        private ActionPurpose activeActionPurpose;
        private SafetyIntervention currentIntervention;
        private GridPoint escapeTarget;
        private boolean inventoryClosed;
        private boolean stopIssued;
        private boolean aligned;

        private SafetyIncident(
                UUID incidentId,
                UUID botId,
                long generation,
                HazardAssessment hazard,
                long currentTick) {
            this.incidentId = incidentId;
            this.botId = botId;
            this.generation = generation;
            this.hazard = hazard;
            this.firstObservedTick = currentTick;
            this.lastObservedTick = currentTick;
        }

        private SafetyIncidentView view() {
            return new SafetyIncidentView(
                    incidentId,
                    botId,
                    generation,
                    state,
                    hazard.type(),
                    hazard.severity(),
                    firstObservedTick,
                    lastObservedTick,
                    interventions,
                    clearStableTicks,
                    Optional.ofNullable(currentIntervention),
                    Optional.ofNullable(escapeTarget),
                    hazard.evidence());
        }
    }
}
