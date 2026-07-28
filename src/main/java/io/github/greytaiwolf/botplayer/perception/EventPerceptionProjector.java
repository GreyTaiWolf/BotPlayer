package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.AuthorityEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionProjection;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventDraft;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorSupport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 把全服权威事件的空间通道按维度、距离、视锥和遮挡投影给单个 bot。
 *
 * <p>SELF 与 DIRECT 只允许由 PerceptionService 的 generation 定向可靠路径投递。
 */
public final class EventPerceptionProjector {
    private static final double MINIMUM_FOV_DOT = 0.35D;
    private static final double CLOSE_RANGE_SQUARED = 4.0D;

    public Optional<PerceptionProjection> projectSpatial(
            BotServerPlayer player,
            AuthorityEvent authority,
            PerceptionBudget budget,
            double visualRange,
            double hearingRange,
            long currentTick) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(budget, "budget");
        if (!Double.isFinite(visualRange)
                || visualRange <= 0.0D
                || !Double.isFinite(hearingRange)
                || hearingRange <= 0.0D
                || currentTick < 0) {
            throw new IllegalArgumentException("projection limits are invalid");
        }
        if (!budget.tryConsume(BudgetKind.EVENT_READ)) {
            return Optional.empty();
        }

        SemanticEventDraft event = authority.event();
        if (!event.dimension()
                .equals(player.serverLevel().dimension().location().toString())
                || event.position().isEmpty()) {
            return Optional.empty();
        }
        /*
         * 视觉与普通声学事件没有保存观察者的历史姿态。积压后若用当前姿态重放，
         * 会让 bot 追溯性看见或听见过去，因此只在发生 Tick 内做空间投影。
         */
        if (event.gameTick() != currentTick) {
            return Optional.empty();
        }

        SpatialPoint point = event.position().orElseThrow();
        SpatialPoint eye = new SpatialPoint(
                player.getEyePosition().x,
                player.getEyePosition().y,
                player.getEyePosition().z);
        double distanceSquared = eye.distanceSquared(point);
        if (event.channels().contains(PerceptionChannel.VISUAL)
                && distanceSquared <= visualRange * visualRange
                && inView(player, point, distanceSquared)
                && visible(player, point, budget)) {
            return Optional.of(visualProjection(
                    player,
                    authority,
                    budget,
                    visualRange,
                    currentTick,
                    event.confidence()));
        }
        if (event.channels().contains(PerceptionChannel.AUDIBLE)
                && distanceSquared <= hearingRange * hearingRange) {
            return Optional.of(audibleProjection(
                    authority, currentTick, event.confidence()));
        }
        return Optional.empty();
    }

    private static boolean inView(
            BotServerPlayer player,
            SpatialPoint point,
            double distanceSquared) {
        if (distanceSquared <= CLOSE_RANGE_SQUARED) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 direction = new Vec3(point.x(), point.y(), point.z())
                .subtract(eye)
                .normalize();
        return player.getLookAngle().dot(direction) >= MINIMUM_FOV_DOT;
    }

    private static boolean visible(
            BotServerPlayer player,
            SpatialPoint point,
            PerceptionBudget budget) {
        BlockPos target = BlockPos.containing(point.x(), point.y(), point.z());
        if (!player.serverLevel().isLoaded(target)
                || !budget.tryConsume(BudgetKind.RAYCAST)) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = new Vec3(point.x(), point.y(), point.z());
        if (SensorSupport.loadedRay(
                        player.serverLevel(), eye, end)
                .unloadedPoint()
                .isPresent()) {
            return false;
        }
        HitResult hit = player.serverLevel().clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.MISS
                || (hit instanceof BlockHitResult blockHit
                        && blockHit.getBlockPos().equals(target));
    }

    private static PerceptionProjection visualProjection(
            BotServerPlayer player,
            AuthorityEvent authority,
            PerceptionBudget budget,
            double visualRange,
            long currentTick,
            float confidence) {
        PerceptionProjection base = PerceptionProjection.full(
                authority,
                currentTick,
                PerceptionChannel.VISUAL,
                confidence);
        List<ActorRef> visibleActors = authority.event()
                .actors()
                .stream()
                .filter(actor -> visibleActor(
                        player,
                        actor,
                        authority.event(),
                        budget,
                        visualRange))
                .toList();
        /*
         * 事件位置可见并不代表远处攻击者等其他参与者也可见；视觉投影只保留
         * 逐个通过视距、视锥和遮挡复核的 actor，并删除通用身份字段。
         */
        Map<String, String> visibleDelta = base.delta()
                .entrySet()
                .stream()
                .filter(entry -> !isIdentityField(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
        return new PerceptionProjection(
                base.observedAtTick(),
                base.type(),
                base.outcome(),
                base.position(),
                visibleActors,
                base.objects(),
                visibleDelta,
                base.source(),
                base.channel(),
                base.confidence(),
                base.tags());
    }

    private static boolean visibleActor(
            BotServerPlayer player,
            ActorRef actor,
            SemanticEventDraft event,
            PerceptionBudget budget,
            double visualRange) {
        if (actor.actorId().equals(player.getUUID())) {
            return generationMatches(player, event);
        }
        Entity entity =
                player.serverLevel().getEntity(actor.actorId());
        if (entity == null
                || entity.isRemoved()
                || !generationMatches(entity, event)
                || !player.serverLevel()
                        .isLoaded(entity.blockPosition())) {
            return false;
        }
        Vec3 center = entity.getBoundingBox().getCenter();
        SpatialPoint point =
                new SpatialPoint(center.x, center.y, center.z);
        double distanceSquared = new SpatialPoint(
                        player.getEyePosition().x,
                        player.getEyePosition().y,
                        player.getEyePosition().z)
                .distanceSquared(point);
        return distanceSquared <= visualRange * visualRange
                && inView(player, point, distanceSquared)
                && unobstructed(player, center, budget);
    }

    private static boolean generationMatches(
            Entity entity, SemanticEventDraft event) {
        if (!(entity instanceof BotServerPlayer bot)) {
            return true;
        }
        String generationText =
                event.delta().get("action.generation");
        if (generationText == null) {
            generationText = event.delta().get(
                    "routing.bot_generation");
        }
        if (generationText == null) {
            return true;
        }
        try {
            long generation = Long.parseLong(generationText);
            return generation > 0L
                    && generation
                            == bot.runtimeHandle().generation();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean unobstructed(
            BotServerPlayer player,
            Vec3 end,
            PerceptionBudget budget) {
        if (!budget.tryConsume(BudgetKind.RAYCAST)) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        if (SensorSupport.loadedRay(
                        player.serverLevel(), eye, end)
                .unloadedPoint()
                .isPresent()) {
            return false;
        }
        HitResult hit = player.serverLevel().clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isIdentityField(String key) {
        String normalized =
                key.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("target.id")
                || normalized.equals("actor.id")
                || normalized.equals("attacker.id")
                || normalized.equals("victim.id")
                || normalized.equals("entity.id")
                || normalized.equals("action.id")
                || normalized.endsWith(".action_id")
                || normalized.endsWith(".actor_id")
                || normalized.endsWith(".target_id")
                || normalized.endsWith(".attacker_id")
                || normalized.endsWith(".victim_id")
                || normalized.contains("uuid");
    }

    private static PerceptionProjection audibleProjection(
            AuthorityEvent authority, long currentTick, float confidence) {
        SemanticEventDraft event = authority.event();
        Map<String, String> safeDelta = event.delta().entrySet().stream()
                .filter(entry -> entry.getKey().equals("sound.source")
                        || entry.getKey().equals("sound.volume")
                        || entry.getKey().equals("sound.pitch"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
        return new PerceptionProjection(
                currentTick,
                event.type(),
                event.outcome(),
                event.position(),
                List.of(),
                event.objects(),
                safeDelta,
                event.source(),
                PerceptionChannel.AUDIBLE,
                confidence,
                event.tags());
    }
}
