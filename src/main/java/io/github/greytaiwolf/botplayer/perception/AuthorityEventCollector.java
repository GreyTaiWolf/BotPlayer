package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.AuthorityEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventBus;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventDraft;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventSource;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionKind;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionScope;
import io.github.greytaiwolf.botplayer.worldmodel.RevisionStamp;
import io.github.greytaiwolf.botplayer.worldmodel.WorldRevisionTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把 NeoForge 候选和 P2 动作终态收敛为已复核、去重的权威事件。
 */
public final class AuthorityEventCollector {
    public static final int MAX_PENDING_EVENTS = 8_192;
    private static final int RECENT_DEDUPLICATION_TICKS = 2;
    private static final int MAX_INGRESS_PER_TICK = 2_048;
    /*
     * 与 P2 单 Tick 最坏 canonical 终态吞吐对齐；每个成功破坏最多发布
     * ACTION_COMPLETED 与 BLOCK_BROKEN 两个事件。
     */
    private static final int MAX_CRITICAL_INGRESS_PER_TICK =
            (BotActionRuntime.MAX_ACTIVE_ACTIONS
                            + BotActionRuntime.MAX_COMMANDS_PER_TICK)
                    * 2;
    private static final int MAX_STATE_PROPERTIES = 32;

    private final MinecraftServer server;
    private final SemanticEventBus eventBus;
    private final WorldRevisionTracker revisions;
    private final Consumer<AuthorityEvent> reliableRouter;
    private final int pendingCapacity;
    private final int regularIngressLimit;
    private final int criticalIngressLimit;
    private final Deque<PendingMutation> pending = new ArrayDeque<>();
    private final LinkedHashMap<MutationKey, CommittedMutation>
            committedMutations =
            new LinkedHashMap<>();
    private long droppedPending;
    private long droppedIngress;
    private long malformedIngress;
    private long ingressTick = -1L;
    private int regularIngress;
    private int criticalIngress;

    public AuthorityEventCollector(
            MinecraftServer server,
            SemanticEventBus eventBus,
            WorldRevisionTracker revisions,
            int pendingCapacity,
            Consumer<AuthorityEvent> reliableRouter) {
        this.server = Objects.requireNonNull(server, "server");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.reliableRouter =
                Objects.requireNonNull(reliableRouter, "reliableRouter");
        if (pendingCapacity < 1 || pendingCapacity > MAX_PENDING_EVENTS) {
            throw new IllegalArgumentException(
                    "pendingCapacity must be between 1 and " + MAX_PENDING_EVENTS);
        }
        this.pendingCapacity = pendingCapacity;
        this.regularIngressLimit = Math.min(
                pendingCapacity, MAX_INGRESS_PER_TICK);
        this.criticalIngressLimit =
                MAX_CRITICAL_INGRESS_PER_TICK;
    }

    public void onActionOutcome(ActionEnvelope envelope, ActionOutcome outcome) {
        requireServerThread();
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(outcome, "outcome");
        boolean publishesVerifiedBreak =
                outcome.state() == ActionState.SUCCEEDED
                        && envelope.action()
                                instanceof WorldInteractionAction worldAction
                        && worldAction.spec()
                                instanceof WorldInteractionActionSpec.BreakBlock;
        if (!tryAcquireIngress(
                true, publishesVerifiedBreak ? 2 : 1)) {
            return;
        }
        try {
            collectActionOutcome(envelope, outcome);
        } catch (RuntimeException exception) {
            malformedIngress++;
        }
    }

    private void collectActionOutcome(
            ActionEnvelope envelope, ActionOutcome outcome) {
        Entity actor = server.getPlayerList().getPlayer(envelope.botId());
        ActorRef actorRef = actor == null
                ? new ActorRef(envelope.botId(), "minecraft:player", envelope.botId().toString())
                : actor(actor);
        Optional<SpatialPoint> position =
                Optional.ofNullable(actor).map(AuthorityEventCollector::position);
        Map<String, String> delta = new LinkedHashMap<>();
        delta.put(
                "action.kind",
                envelope.action().kind().name().toLowerCase(Locale.ROOT));
        delta.put("action.id", envelope.actionId().toString());
        delta.put("action.generation", Long.toString(envelope.botGeneration()));
        delta.put(
                "action.state",
                outcome.state().name().toLowerCase(Locale.ROOT));
        delta.put(
                "action.failure_code",
                outcome.failureCode().name().toLowerCase(Locale.ROOT));

        boolean succeeded = outcome.state() == ActionState.SUCCEEDED;
        SemanticEventOutcome semanticOutcome = switch (outcome.state()) {
            case SUCCEEDED -> SemanticEventOutcome.COMMITTED;
            case CANCELLED, PREEMPTED, STALE ->
                    SemanticEventOutcome.CANCELLED;
            case FAILED -> SemanticEventOutcome.FAILED;
            case QUEUED, VALIDATING, RUNNING, VERIFYING ->
                    throw new IllegalArgumentException(
                            "action outcome must be terminal");
        };
        publish(
                SemanticEventDraft.create(
                        dimension(actor),
                        outcome.finishedTick(),
                        succeeded
                                ? SemanticEventType.ACTION_COMPLETED
                                : outcome.failureCode() == ActionFailureCode.PERMISSION_DENIED
                                        ? SemanticEventType.PERMISSION_DENIED
                                        : SemanticEventType.ACTION_FAILED,
                        semanticOutcome,
                        position,
                        List.of(actorRef),
                        List.of(envelope.action().kind().name().toLowerCase(Locale.ROOT)),
                        delta,
                        SemanticEventSource.ACTION_RESULT,
                        Set.of(PerceptionChannel.SELF),
                        1.0F,
                        Set.of("action:terminal"),
                        false),
                Optional.empty());

        if (succeeded
                && envelope.action() instanceof WorldInteractionAction worldAction
                && worldAction.spec()
                        instanceof WorldInteractionActionSpec.BreakBlock breakBlock) {
            publishVerifiedBreak(
                    envelope, outcome.finishedTick(), actorRef, breakBlock);
        }
    }

    public void recordBlockBreak(
            ServerLevel level,
            BlockPos position,
            BlockState before,
            Player actor) {
        requireServerThread();
        if (!tryAcquireIngress(false, 1)) {
            return;
        }
        try {
            enqueue(new PendingMutation(
                    PendingKind.BREAK,
                    level.dimension(),
                    position.immutable(),
                    blockId(before),
                    Map.copyOf(stateProperties(before)),
                    actor(actor),
                    botGeneration(actor),
                    server.getTickCount(),
                    null,
                    null));
        } catch (RuntimeException exception) {
            malformedIngress++;
        }
    }

    public void recordBlockPlace(
            ServerLevel level,
            BlockPos position,
            BlockState before,
            BlockState expectedAfter,
            Entity actor) {
        requireServerThread();
        if (!tryAcquireIngress(false, 1)) {
            return;
        }
        try {
            enqueue(new PendingMutation(
                    PendingKind.PLACE,
                    level.dimension(),
                    position.immutable(),
                    blockId(before),
                    Map.copyOf(stateProperties(before)),
                    actor == null ? null : actor(actor),
                    botGeneration(actor),
                    server.getTickCount(),
                    blockId(expectedAfter),
                    Map.copyOf(stateProperties(expectedAfter))));
        } catch (RuntimeException exception) {
            malformedIngress++;
        }
    }

    public Optional<AuthorityEvent> recordDamage(
            Entity target, Entity attacker, float damage, long gameTick) {
        requireServerThread();
        Objects.requireNonNull(target, "target");
        if (!Float.isFinite(damage) || damage <= 0.0F) {
            return Optional.empty();
        }
        if (!tryAcquireIngress(false, 1)) {
            return Optional.empty();
        }
        try {
            return Optional.of(collectDamage(
                    target, attacker, damage, gameTick));
        } catch (RuntimeException exception) {
            malformedIngress++;
            return Optional.empty();
        }
    }

    private AuthorityEvent collectDamage(
            Entity target, Entity attacker, float damage, long gameTick) {
        List<ActorRef> actors = new ArrayList<>();
        if (attacker != null) {
            actors.add(actor(attacker));
        }
        actors.add(actor(target));
        RevisionScope scope = new RevisionScope(
                dimension(target),
                RevisionKind.ENTITY,
                target.getUUID().toString());
        return publish(
                SemanticEventDraft.create(
                        dimension(target),
                        gameTick,
                        SemanticEventType.ENTITY_DAMAGED,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(position(target)),
                        actors,
                        List.of(typeId(target)),
                        Map.of(
                                "target.id",
                                target.getUUID().toString(),
                                "scope.kind",
                                RevisionKind.ENTITY.name()
                                        .toLowerCase(Locale.ROOT),
                                "scope.target",
                                target.getUUID().toString(),
                                "damage",
                                Float.toString(Math.max(0.0F, damage))),
                        SemanticEventSource.NEOFORGE_EVENT,
                        Set.of(PerceptionChannel.SELF, PerceptionChannel.VISUAL),
                        1.0F,
                        Set.of("activity:combat"),
                        true),
                Optional.of(scope));
    }

    public Optional<AuthorityEvent> recordPickup(
            Player player, ItemStack original, long gameTick) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(original, "original");
        if (!tryAcquireIngress(false, 1)) {
            return Optional.empty();
        }
        try {
            return Optional.of(collectPickup(
                    player, original, gameTick));
        } catch (RuntimeException exception) {
            malformedIngress++;
            return Optional.empty();
        }
    }

    private AuthorityEvent collectPickup(
            Player player, ItemStack original, long gameTick) {
        String itemId = itemId(original);
        Set<String> tags = itemActivityTags(itemId);
        return publish(
                SemanticEventDraft.create(
                        dimension(player),
                        gameTick,
                        SemanticEventType.ITEM_PICKED_UP,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(position(player)),
                        List.of(actor(player)),
                        List.of(itemId),
                        Map.of("item.count", Integer.toString(original.getCount())),
                        SemanticEventSource.NEOFORGE_EVENT,
                        Set.of(PerceptionChannel.SELF, PerceptionChannel.VISUAL),
                        1.0F,
                        tags,
                        false),
                Optional.empty());
    }

    public void recordToss(Player player, ItemEntity item, long gameTick) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(item, "item");
        if (!tryAcquireIngress(false, 1)) {
            return;
        }
        try {
            enqueue(new PendingMutation(
                    PendingKind.TOSS,
                    player.level().dimension(),
                    item.blockPosition().immutable(),
                    itemId(item.getItem()),
                    Map.of(
                            "item.count",
                            Integer.toString(
                                    item.getItem().getCount())),
                    actor(player),
                    botGeneration(player),
                    gameTick,
                    item.getUUID().toString(),
                    null));
        } catch (RuntimeException exception) {
            malformedIngress++;
        }
    }

    public void flush(long currentTick) {
        requireServerThread();
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        int verified = 0;
        while (verified < regularIngressLimit
                && !pending.isEmpty()) {
            PendingMutation mutation = pending.removeFirst();
            verified++;
            try {
                ServerLevel level =
                        server.getLevel(mutation.dimension());
                if (level == null
                        || !level.hasChunkAt(mutation.position())) {
                    continue;
                }
                switch (mutation.kind()) {
                    case BREAK ->
                            verifyBreak(level, mutation, currentTick);
                    case PLACE ->
                            verifyPlace(level, mutation, currentTick);
                    case TOSS ->
                            verifyToss(level, mutation, currentTick);
                }
            } catch (RuntimeException exception) {
                malformedIngress++;
            }
        }
        committedMutations.entrySet().removeIf(
                entry -> currentTick
                                - entry.getValue().committedTick()
                        > RECENT_DEDUPLICATION_TICKS);
    }

    public long droppedPending() {
        return droppedPending;
    }

    public long droppedIngress() {
        return droppedIngress;
    }

    public long malformedIngress() {
        return malformedIngress;
    }

    private void publishVerifiedBreak(
            ActionEnvelope envelope,
            long finishedTick,
            ActorRef actor,
            WorldInteractionActionSpec.BreakBlock action) {
        BlockCoordinates coordinates = action.target().target().position();
        String dimension = action.target().target().dimension().value();
        String target = targetId(coordinates.x(), coordinates.y(), coordinates.z());
        MutationKey key = new MutationKey(
                SemanticEventType.BLOCK_BROKEN,
                actor.actorId(),
                dimension,
                target,
                action.target()
                                .target()
                                .state()
                                .blockId()
                                .value()
                        + "->minecraft:air");
        if (isDuplicate(
                key,
                finishedTick,
                finishedTick,
                SemanticEventSource.ACTION_RESULT)) {
            return;
        }
        RevisionScope scope =
                new RevisionScope(dimension, RevisionKind.BLOCK, target);
        publish(
                SemanticEventDraft.create(
                        dimension,
                        finishedTick,
                        SemanticEventType.BLOCK_BROKEN,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(new SpatialPoint(
                                coordinates.x() + 0.5D,
                                coordinates.y() + 0.5D,
                                coordinates.z() + 0.5D)),
                        List.of(actor),
                        List.of(action.target()
                                .target()
                                .state()
                                .blockId()
                                .value()),
                        Map.of(
                                "scope.kind",
                                RevisionKind.BLOCK.name().toLowerCase(Locale.ROOT),
                                "scope.target",
                                target,
                                "after",
                                "minecraft:air",
                                "action.generation",
                                Long.toString(envelope.botGeneration()),
                                "cause.action_id",
                                envelope.actionId().toString()),
                        SemanticEventSource.ACTION_RESULT,
                        Set.of(PerceptionChannel.SELF, PerceptionChannel.VISUAL),
                        1.0F,
                        activityTagsForBlock(
                                action.target().target().state().blockId().value(), true),
                        true),
                Optional.of(scope));
        rememberMutation(
                key,
                finishedTick,
                finishedTick,
                SemanticEventSource.ACTION_RESULT);
    }

    private void verifyBreak(
            ServerLevel level, PendingMutation mutation, long currentTick) {
        BlockState current = level.getBlockState(mutation.position());
        String after = blockId(current);
        if (after.equals(mutation.beforeId())) {
            return;
        }
        ActorRef actor = Objects.requireNonNull(mutation.actor(), "break actor");
        String dimension = mutation.dimension().location().toString();
        String target = targetId(mutation.position());
        boolean broken = current.isAir();
        SemanticEventType type = broken
                ? SemanticEventType.BLOCK_BROKEN
                : SemanticEventType.BLOCK_CHANGED;
        MutationKey key = new MutationKey(
                type,
                broken ? actor.actorId() : new UUID(0L, 1L),
                dimension,
                target,
                mutation.beforeId()
                        + "->"
                        + after);
        if (isDuplicate(
                key,
                currentTick,
                mutation.tick(),
                SemanticEventSource.NEOFORGE_EVENT)) {
            return;
        }
        RevisionScope scope =
                new RevisionScope(dimension, RevisionKind.BLOCK, target);
        publish(
                SemanticEventDraft.create(
                        dimension,
                        mutation.tick(),
                        type,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(position(mutation.position())),
                        broken ? List.of(actor) : List.of(),
                        List.of(mutation.beforeId(), after),
                        withRoutingGeneration(
                                Map.of(
                                        "scope.kind",
                                        RevisionKind.BLOCK.name()
                                                .toLowerCase(Locale.ROOT),
                                        "scope.target",
                                        target,
                                        "before",
                                        mutation.beforeId(),
                                        "after",
                                        after,
                                        "verified.tick",
                                        Long.toString(currentTick)),
                                mutation.actorBotGeneration()),
                        SemanticEventSource.NEOFORGE_EVENT,
                        broken
                                ? Set.of(
                                        PerceptionChannel.SELF,
                                        PerceptionChannel.VISUAL)
                                : Set.of(PerceptionChannel.VISUAL),
                        1.0F,
                        broken
                                ? activityTagsForBlock(
                                        mutation.beforeId(), true)
                                : Set.of("block:changed"),
                        true),
                Optional.of(scope));
        rememberMutation(
                key,
                currentTick,
                mutation.tick(),
                SemanticEventSource.NEOFORGE_EVENT);
    }

    private void verifyPlace(
            ServerLevel level, PendingMutation mutation, long currentTick) {
        BlockState current = level.getBlockState(mutation.position());
        String after = blockId(current);
        Map<String, String> afterProperties =
                stateProperties(current);
        if (!after.equals(mutation.expectedAfterId())
                || !afterProperties.equals(
                        mutation.expectedAfterProperties())) {
            return;
        }
        ActorRef actor = mutation.actor();
        UUID actorId = actor == null
                ? new UUID(0L, 1L)
                : actor.actorId();
        String dimension = mutation.dimension().location().toString();
        String target = targetId(mutation.position());
        MutationKey key = new MutationKey(
                SemanticEventType.BLOCK_PLACED,
                actorId,
                dimension,
                target,
                mutation.beforeId() + "->" + after);
        if (isDuplicate(
                key,
                currentTick,
                mutation.tick(),
                SemanticEventSource.NEOFORGE_EVENT)) {
            return;
        }
        RevisionScope scope =
                new RevisionScope(dimension, RevisionKind.BLOCK, target);
        publish(
                SemanticEventDraft.create(
                        dimension,
                        mutation.tick(),
                        SemanticEventType.BLOCK_PLACED,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(position(mutation.position())),
                        actor == null ? List.of() : List.of(actor),
                        List.of(after),
                        withRoutingGeneration(
                                Map.of(
                                        "scope.kind",
                                        RevisionKind.BLOCK.name()
                                                .toLowerCase(Locale.ROOT),
                                        "scope.target",
                                        target,
                                        "before",
                                        mutation.beforeId(),
                                        "verified.tick",
                                        Long.toString(currentTick)),
                                mutation.actorBotGeneration()),
                        SemanticEventSource.NEOFORGE_EVENT,
                        Set.of(PerceptionChannel.SELF, PerceptionChannel.VISUAL),
                        1.0F,
                        activityTagsForBlock(after, false),
                        true),
                Optional.of(scope));
        rememberMutation(
                key,
                currentTick,
                mutation.tick(),
                SemanticEventSource.NEOFORGE_EVENT);
    }

    private void verifyToss(
            ServerLevel level, PendingMutation mutation, long currentTick) {
        UUID itemEntityId;
        try {
            itemEntityId = UUID.fromString(
                    Objects.requireNonNull(mutation.expectedAfterId(), "item entity id"));
        } catch (IllegalArgumentException exception) {
            return;
        }
        Entity entity = level.getEntity(itemEntityId);
        if (!(entity instanceof ItemEntity) || entity.isRemoved()) {
            return;
        }
        publish(
                SemanticEventDraft.create(
                        mutation.dimension().location().toString(),
                        mutation.tick(),
                        SemanticEventType.ITEM_DROPPED,
                        SemanticEventOutcome.COMMITTED,
                        Optional.of(position(entity)),
                        mutation.actor() == null
                                ? List.of()
                                : List.of(mutation.actor()),
                        List.of(mutation.beforeId()),
                        withRoutingGeneration(
                                withVerifiedTick(
                                        mutation.beforeProperties(),
                                        currentTick),
                                mutation.actorBotGeneration()),
                        SemanticEventSource.NEOFORGE_EVENT,
                        Set.of(PerceptionChannel.SELF, PerceptionChannel.VISUAL),
                        1.0F,
                        itemActivityTags(mutation.beforeId()),
                        false),
                Optional.empty());
    }

    private AuthorityEvent publish(
            SemanticEventDraft draft, Optional<RevisionScope> scope) {
        if (draft.changesWorld()) {
            RevisionScope changedScope = scope.orElseThrow();
            RevisionStamp revision = revisions.advance(changedScope);
            Map<String, String> delta = new LinkedHashMap<>(draft.delta());
            delta.put("revision.global", Long.toString(revision.global()));
            delta.put("revision.dimension", Long.toString(revision.dimension()));
            delta.put("revision.target", Long.toString(revision.target()));
            if (changedScope.kind() == RevisionKind.BLOCK) {
                RevisionStamp containerRevision = revisions.advanceRelated(
                        new RevisionScope(
                                changedScope.dimension(),
                                RevisionKind.CONTAINER,
                                changedScope.targetId()),
                        revision);
                delta.put(
                        "container.revision.global",
                        Long.toString(containerRevision.global()));
                delta.put(
                        "container.revision.dimension",
                        Long.toString(containerRevision.dimension()));
                delta.put(
                        "container.revision.target",
                        Long.toString(containerRevision.target()));
            }
            draft = new SemanticEventDraft(
                    draft.eventId(),
                    draft.dimension(),
                    draft.gameTick(),
                    draft.wallTime(),
                    draft.type(),
                    draft.outcome(),
                    draft.position(),
                    draft.actors(),
                    draft.objects(),
                    delta,
                    draft.source(),
                    draft.channels(),
                    draft.confidence(),
                    draft.tags(),
                    true);
        }
        AuthorityEvent authority =
                eventBus.publishAuthority(draft);
        try {
            reliableRouter.accept(authority);
        } catch (RuntimeException exception) {
            malformedIngress++;
        }
        return authority;
    }

    private void enqueue(PendingMutation mutation) {
        if (pending.size() == pendingCapacity) {
            pending.removeFirst();
            droppedPending++;
        }
        pending.addLast(mutation);
    }

    private boolean tryAcquireIngress(
            boolean critical, int units) {
        if (units < 1) {
            throw new IllegalArgumentException(
                    "ingress units must be positive");
        }
        long currentTick = server.getTickCount();
        if (ingressTick != currentTick) {
            ingressTick = currentTick;
            regularIngress = 0;
            criticalIngress = 0;
        }
        if (critical) {
            if (units > criticalIngressLimit - criticalIngress) {
                droppedIngress += units;
                return false;
            }
            criticalIngress += units;
            return true;
        }
        if (units > regularIngressLimit - regularIngress) {
            droppedIngress += units;
            return false;
        }
        regularIngress += units;
        return true;
    }

    private boolean isDuplicate(
            MutationKey key,
            long currentTick,
            long eventTick,
            SemanticEventSource source) {
        CommittedMutation previous =
                committedMutations.get(key);
        if (previous == null) {
            return false;
        }
        if (previous.source() == source) {
            return previous.eventTick() == eventTick;
        }
        long age = currentTick - previous.committedTick();
        return age >= 0L
                && age <= RECENT_DEDUPLICATION_TICKS;
    }

    private void rememberMutation(
            MutationKey key,
            long committedTick,
            long eventTick,
            SemanticEventSource source) {
        committedMutations.put(
                key,
                new CommittedMutation(
                        committedTick,
                        eventTick,
                        source));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "authority event collection must run on the server thread");
        }
    }

    private static String dimension(Entity entity) {
        return entity == null
                ? Level.OVERWORLD.location().toString()
                : entity.level().dimension().location().toString();
    }

    private static ActorRef actor(Entity entity) {
        return new ActorRef(
                entity.getUUID(),
                typeId(entity),
                entity.getScoreboardName());
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty()
                ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static SpatialPoint position(Entity entity) {
        return new SpatialPoint(entity.getX(), entity.getY(), entity.getZ());
    }

    private static SpatialPoint position(BlockPos position) {
        return new SpatialPoint(
                position.getX() + 0.5D,
                position.getY() + 0.5D,
                position.getZ() + 0.5D);
    }

    private static Map<String, String> stateProperties(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> entry.getKey().getName()))
                .limit(MAX_STATE_PROPERTIES)
                .forEach(entry -> properties.put(
                        entry.getKey().getName(),
                        entry.getValue().toString()));
        return Map.copyOf(properties);
    }

    private static Map<String, String> withVerifiedTick(
            Map<String, String> values, long verifiedTick) {
        Map<String, String> result =
                new LinkedHashMap<>(values);
        result.put("verified.tick", Long.toString(verifiedTick));
        return Map.copyOf(result);
    }

    private static Map<String, String> withRoutingGeneration(
            Map<String, String> values, long generation) {
        if (generation <= 0L) {
            return values;
        }
        Map<String, String> result =
                new LinkedHashMap<>(values);
        result.put(
                "routing.bot_generation",
                Long.toString(generation));
        return Map.copyOf(result);
    }

    private static long botGeneration(Entity actor) {
        return actor instanceof BotServerPlayer bot
                ? bot.runtimeHandle().generation()
                : 0L;
    }

    private static Set<String> activityTagsForBlock(String blockId, boolean broken) {
        Set<String> tags = new LinkedHashSet<>();
        if (isCrop(blockId)) {
            tags.add("activity:farming");
        } else {
            tags.add(broken ? "activity:mining" : "activity:building");
        }
        return Set.copyOf(tags);
    }

    private static Set<String> itemActivityTags(String itemId) {
        if (isCrop(itemId)) {
            return Set.of("item:crop");
        }
        if (itemId.contains("_ore")
                || itemId.contains("raw_")
                || itemId.contains("_ingot")) {
            return Set.of("item:ore");
        }
        return Set.of("item:other");
    }

    private static boolean isCrop(String id) {
        return id.contains("wheat")
                || id.contains("carrot")
                || id.contains("potato")
                || id.contains("beetroot")
                || id.contains("nether_wart")
                || id.contains("cocoa")
                || id.contains("melon")
                || id.contains("pumpkin")
                || id.contains("sugar_cane");
    }

    private static String targetId(BlockPos position) {
        return targetId(position.getX(), position.getY(), position.getZ());
    }

    private static String targetId(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private enum PendingKind {
        BREAK,
        PLACE,
        TOSS
    }

    private record PendingMutation(
            PendingKind kind,
            ResourceKey<Level> dimension,
            BlockPos position,
            String beforeId,
            Map<String, String> beforeProperties,
            ActorRef actor,
            long actorBotGeneration,
            long tick,
            String expectedAfterId,
            Map<String, String> expectedAfterProperties) {
        private PendingMutation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(beforeId, "beforeId");
            beforeProperties = Map.copyOf(
                    Objects.requireNonNull(beforeProperties, "beforeProperties"));
            expectedAfterProperties = expectedAfterProperties == null
                    ? null
                    : Map.copyOf(expectedAfterProperties);
            if (actorBotGeneration < 0L || tick < 0L) {
                throw new IllegalArgumentException(
                        "actor generation/tick must not be negative");
            }
        }
    }

    private record MutationKey(
            SemanticEventType type,
            UUID actorId,
            String dimension,
            String target,
            String signature) {
        private MutationKey {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(signature, "signature");
        }
    }

    private record CommittedMutation(
            long committedTick,
            long eventTick,
            SemanticEventSource source) {
        private CommittedMutation {
            if (committedTick < 0L || eventTick < 0L) {
                throw new IllegalArgumentException(
                        "mutation ticks must not be negative");
            }
            Objects.requireNonNull(source, "source");
        }
    }
}
