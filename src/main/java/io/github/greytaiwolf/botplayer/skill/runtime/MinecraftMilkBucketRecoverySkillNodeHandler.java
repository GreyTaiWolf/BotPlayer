package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ResourceIdEvidence;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ActiveEffectFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ActiveEffectObservation;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.UseItemPreconditions;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.MilkBucketRecoveryProof;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.VanillaMilkBucketRecovery;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.VanillaMilkBucketRecovery.MilkRequest;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Bounded P5B handler for one real vanilla milk-bucket use against poison.
 *
 * <p>All mutation is delegated to one existing {@code USE_ITEM} action. The
 * handler never calls {@code removeAllEffects}, writes an inventory slot or
 * manipulates a player menu. It freezes a native 41-slot menu/cursor snapshot,
 * fixed selected hotbar stack, generation and the complete active-effect identity
 * set. The strict action DTO makes the backend compare that snapshot both before
 * validation and immediately before packet dispatch. Completion then proves the
 * native milk-to-bucket transition, stable menu/inventory and cleared poison.
 *
 * <p>The lifecycle registers this approved descriptor and handler, but it does
 * not automatically turn every harmful effect into a milk request. A caller
 * must still submit an explicit, approved recovery plan; L0 safety remains a
 * separate fail-closed handoff policy.
 */
public final class MinecraftMilkBucketRecoverySkillNodeHandler
        implements SkillNodeHandler {
    /** A normal milk use is 32 ticks; leave bounded scheduler/verification room. */
    public static final int MAXIMUM_ACTION_TICKS = 80;
    /**
     * The live poison must still outlast the entire permitted native action;
     * otherwise its ordinary tick-down could look like a successful cure.
     */
    public static final int MINIMUM_REMAINING_POISON_TICKS =
            MAXIMUM_ACTION_TICKS + 1;

    private static final String NATIVE_INVENTORY_SUBJECT =
            "native_inventory";

    private final Resolver bots;
    private final ActionBackedSkillNodeHandler actions;
    private final Thread ownerThread;

    public MinecraftMilkBucketRecoverySkillNodeHandler(
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
     * Reuses the canonical per-bot native inventory reservation key used by
     * equipment actions. The action's own control channels remain the final
     * arbiter for unrelated work that is not part of this local runtime.
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
        SkillNodeContext required = Objects.requireNonNull(context,
                "context");
        if (!isExactRecoveryNode(required)
                || VanillaMilkBucketRecovery.parse(required.node()
                        .parameters()).isEmpty()) {
            return List.of();
        }
        return List.of(new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.CONTAINER,
                        "bot:" + required.botId(),
                        NATIVE_INVENTORY_SUBJECT),
                ReservationMode.EXCLUSIVE));
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
        return actions.signal(Objects.requireNonNull(context, "context"),
                Objects.requireNonNull(signal, "signal"));
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        /* The shared action runtime stops a partially used item through vanilla. */
        actions.cancelled(Objects.requireNonNull(context, "context"), reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Prepared prepared = prepare(context);
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "drink-milk-poison",
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.UseItem(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                prepared.milkBefore(),
                                WorldInteractionActionSpec.ItemUseMode
                                        .FINISH_NATURALLY,
                                0,
                                prepared.strictPreconditions())),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待原版牛奶桶清除中毒效果",
                (verificationContext, signal) -> verify(
                        prepared, verificationContext, signal)));
    }

    private Prepared prepare(SkillNodeContext context) {
        requireOwnerThread();
        if (!isExactRecoveryNode(context)) {
            throw planningFailure(SkillFailureCode.INVALID_PARAMETERS,
                    "牛奶恢复 handler 不能执行不同的技能标识或版本");
        }
        MilkRequest request = VanillaMilkBucketRecovery.parse(
                context.node().parameters()).orElseThrow(() ->
                        planningFailure(SkillFailureCode.INVALID_PARAMETERS,
                                "牛奶恢复节点参数不符合封闭 schema"));
        BotServerPlayer player = activePlayer(context);
        if (player.isSecondaryUseActive()) {
            throw planningFailure(SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "牛奶恢复不能在 secondary-use 控制状态下开始");
        }
        InventoryMenuSnapshot inventory = nativeInventorySnapshot(player);
        if (player.getInventory().selected != request.hotbarSlot()) {
            throw planningFailure(SkillFailureCode.MISSING_ITEM,
                    "牛奶恢复要求固定热栏槽已选为主手");
        }
        ItemStack held = player.getInventory().getItem(request.hotbarSlot());
        ItemStackFingerprint defaultMilk = MinecraftActionSnapshot.item(
                player, new ItemStack(Items.MILK_BUCKET));
        ItemStackFingerprint defaultBucket = MinecraftActionSnapshot.item(
                player, new ItemStack(Items.BUCKET));
        ItemStackFingerprint heldFingerprint = MinecraftActionSnapshot.item(
                player, held);
        if (!held.is(Items.MILK_BUCKET)
                || !heldFingerprint.equals(defaultMilk)) {
            throw planningFailure(SkillFailureCode.MISSING_ITEM,
                    "牛奶恢复要求固定主手槽中的一桶默认原版牛奶");
        }
        EffectCapture effects = activeEffects(player);
        if (effects.observations().size() != 1
                || !MilkBucketRecoveryProof.POISON_EFFECT_ID.equals(
                        effects.observations().get(0).effectId())
                || effects.poisonDuration() < MilkBucketRecoveryProof
                        .MINIMUM_POISON_DURATION_TICKS) {
            throw planningFailure(SkillFailureCode.WORLD_CHANGED,
                    "牛奶恢复只接受持续足够久且没有其他效果的 minecraft:poison");
        }
        MilkBucketRecoveryProof proof;
        try {
            proof = MilkBucketRecoveryProof.freeze(
                    inventory,
                    request.hotbarSlot(),
                    defaultMilk,
                    defaultBucket,
                    effects.fingerprints(),
                    effects.poisonDuration());
        } catch (IllegalArgumentException exception) {
            throw planningFailure(SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "牛奶恢复无法冻结原生背包或效果证明");
        }
        List<ActiveEffectFingerprint> strictEffects = effects.observations()
                .stream()
                .map(effect -> new ActiveEffectFingerprint(
                        effect.effectId(),
                        effect.amplifier(),
                        MINIMUM_REMAINING_POISON_TICKS,
                        effect.ambient(),
                        effect.visible()))
                .toList();
        return new Prepared(
                context.botId(),
                context.botGeneration(),
                request,
                heldFingerprint,
                defaultBucket,
                proof,
                new UseItemPreconditions(inventory, strictEffects));
    }

    private SkillNodeDirective verify(
            Prepared prepared,
            SkillNodeContext context,
            SkillSignal signal) {
        if (!prepared.matches(context)) {
            return SkillNodeDirective.fail(SkillFailureCode.STALE_GENERATION,
                    "牛奶恢复回执不再属于当前 Bot 代际");
        }
        if (!hasExpectedUseEvidence(signal, prepared.milkBefore(),
                prepared.emptyBucketAfter())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "原版牛奶使用回执不证明精确的牛奶桶到空桶转换");
        }
        BotServerPlayer player;
        InventoryMenuSnapshot inventory;
        EffectCapture effects;
        try {
            player = activePlayer(context);
            if (player.isUsingItem()) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.UNSAFE_CONTROL_STATE,
                        "原版牛奶使用完成后 Bot 仍处于持续使用状态");
            }
            inventory = nativeInventorySnapshot(player);
            effects = activeEffects(player);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            return SkillNodeDirective.fail(failure.failureCode(),
                    failure.safeSummary());
        }
        return switch (prepared.proof().verify(inventory,
                effects.fingerprints())) {
            case VERIFIED -> SkillNodeDirective.complete(
                    "已验证原版牛奶桶清除中毒并保留精确背包转换");
            case MENU_CHANGED, SELECTION_CHANGED, CURSOR_CHANGED ->
                    SkillNodeDirective.fail(
                            SkillFailureCode.UNSAFE_CONTROL_STATE,
                            "原版牛奶恢复后的原生菜单、cursor 或选择槽发生漂移");
            case MILK_TRANSITION_MISMATCH, UNRELATED_INVENTORY_CHANGED ->
                    SkillNodeDirective.fail(
                            SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                            "原版牛奶恢复后的背包转换不再精确守恒");
            case EFFECTS_NOT_CLEARED -> SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版牛奶恢复后仍存在活动效果");
        };
    }

    private BotServerPlayer activePlayer(SkillNodeContext context) {
        BotServerPlayer player = bots.resolve(context.botId(),
                context.botGeneration()).orElseThrow(() -> planningFailure(
                        SkillFailureCode.BOT_NOT_ACTIVE,
                        "牛奶恢复的 Bot 代际不再活动"));
        if (!context.botId().equals(player.getUUID())
                || player.runtimeHandle().generation()
                        != context.botGeneration()) {
            throw planningFailure(SkillFailureCode.STALE_GENERATION,
                    "牛奶恢复解析到的 Bot body 不属于当前代际");
        }
        return player;
    }

    private static InventoryMenuSnapshot nativeInventorySnapshot(
            BotServerPlayer player) {
        try {
            InventoryMenu menu = player.inventoryMenu;
            if (player.containerMenu != menu
                    || menu.getClass() != InventoryMenu.class
                    || !menu.stillValid(player)
                    || menu.slots.size()
                            != MenuFamily.INVENTORY_2X2.slotCount()
                    || !menu.getCarried().isEmpty()) {
                throw planningFailure(SkillFailureCode.UNSAFE_CONTROL_STATE,
                        "牛奶恢复要求精确、有效且 cursor 为空的原生背包菜单");
            }
            InventoryMenuSnapshot snapshot =
                    MinecraftActionSnapshot.inventoryMenu(player);
            if (!snapshot.cursor().isEmpty()) {
                throw planningFailure(SkillFailureCode.UNSAFE_CONTROL_STATE,
                        "牛奶恢复的原生背包 cursor 在快照期间发生漂移");
            }
            return snapshot;
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw planningFailure(SkillFailureCode.UNSAFE_CONTROL_STATE,
                    "无法重新捕获原生背包菜单快照");
        }
    }

    private static EffectCapture activeEffects(BotServerPlayer player) {
        List<ActiveEffectObservation> effects = new ArrayList<>();
        int poisonDuration = -1;
        try {
            for (MobEffectInstance effect : player.getActiveEffects()) {
                if (effects.size()
                        >= UseItemPreconditions.MAXIMUM_ACTIVE_EFFECTS) {
                    throw planningFailure(SkillFailureCode.SERVER_OVERLOADED,
                            "牛奶恢复的活动效果超过受限上限");
                }
                net.minecraft.resources.ResourceLocation id =
                        BuiltInRegistries.MOB_EFFECT.getKey(
                                effect.getEffect().value());
                if (id == null || effect.getDuration() < 0) {
                    throw planningFailure(SkillFailureCode.MOD_UNSUPPORTED,
                            "牛奶恢复无法冻结非标准活动效果");
                }
                ResourceId effectId = new ResourceId(id.toString());
                effects.add(new ActiveEffectObservation(
                        effectId,
                        effect.getAmplifier(),
                        effect.getDuration(),
                        effect.isAmbient(),
                        effect.isVisible()));
                if (MilkBucketRecoveryProof.POISON_EFFECT_ID.equals(effectId)) {
                    poisonDuration = effect.getDuration();
                }
            }
            effects.sort(null);
            for (int index = 1; index < effects.size(); index++) {
                if (effects.get(index - 1).effectId().equals(
                        effects.get(index).effectId())) {
                    throw planningFailure(SkillFailureCode.MOD_UNSUPPORTED,
                            "牛奶恢复不能处理重复活动效果标识");
                }
            }
            return new EffectCapture(List.copyOf(effects), poisonDuration);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw planningFailure(SkillFailureCode.MOD_UNSUPPORTED,
                    "牛奶恢复无法读取活动效果快照");
        }
    }

    private static boolean hasExpectedUseEvidence(
            SkillSignal signal,
            ItemStackFingerprint milk,
            ItemStackFingerprint bucket) {
        return hasOneEvidence(signal, "item.before_id", identityId(milk))
                && hasOneEvidence(signal, "item.before_damage",
                        Integer.toString(milk.damage()))
                && hasOneEvidence(signal, "item.before_components",
                        milk.componentsDigest().orElseThrow())
                && hasOneEvidence(signal, "item.before_count", "1")
                && hasOneEvidence(signal, "item.after_id", identityId(bucket))
                && hasOneEvidence(signal, "item.after_damage",
                        Integer.toString(bucket.damage()))
                && hasOneEvidence(signal, "item.after_components",
                        bucket.componentsDigest().orElseThrow())
                && hasOneEvidence(signal, "item.after_count", "1")
                && hasOneEvidence(signal, "item.use_started", "true");
    }

    private static String identityId(ItemStackFingerprint fingerprint) {
        return ResourceIdEvidence.encode(fingerprint.itemId().orElseThrow());
    }

    private static boolean hasOneEvidence(
            SkillSignal signal, String key, String expected) {
        int matches = 0;
        for (ActionEvidence evidence : signal.evidence()) {
            if (!key.equals(evidence.key())) {
                continue;
            }
            if (!expected.equals(evidence.value())) {
                return false;
            }
            matches++;
        }
        return matches == 1;
    }

    private static boolean isExactRecoveryNode(SkillNodeContext context) {
        return VanillaMilkBucketRecovery.ID.equals(context.node().skillId())
                && VanillaMilkBucketRecovery.VERSION.equals(
                        context.node().skillVersion());
    }

    private static ActionBackedSkillNodeHandler.PlanningFailure
            planningFailure(SkillFailureCode code, String safeSummary) {
        return new ActionBackedSkillNodeHandler.PlanningFailure(code,
                safeSummary);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "milk bucket recovery handler requires server thread");
        }
    }

    /** Lifecycle resolves only the current bot body for this exact generation. */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    private record Prepared(
            UUID botId,
            long generation,
            MilkRequest request,
            ItemStackFingerprint milkBefore,
            ItemStackFingerprint emptyBucketAfter,
            MilkBucketRecoveryProof proof,
            UseItemPreconditions strictPreconditions) {
        private Prepared {
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "milk recovery generation must be positive");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(milkBefore, "milkBefore");
            Objects.requireNonNull(emptyBucketAfter, "emptyBucketAfter");
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(strictPreconditions, "strictPreconditions");
        }

        private boolean matches(SkillNodeContext context) {
            return botId.equals(context.botId())
                    && generation == context.botGeneration();
        }
    }

    private record EffectCapture(
            List<ActiveEffectObservation> observations,
            int poisonDuration) {
        private EffectCapture {
            observations = List.copyOf(Objects.requireNonNull(observations,
                    "observations"));
            if (poisonDuration < -1) {
                throw new IllegalArgumentException(
                        "poisonDuration must be -1 or non-negative");
            }
        }

        private List<ActiveEffectFingerprint> fingerprints() {
            return observations.stream().map(effect ->
                    new ActiveEffectFingerprint(
                            effect.effectId(),
                            effect.amplifier(),
                            0,
                            effect.ambient(),
                            effect.visible())).toList();
        }
    }
}
