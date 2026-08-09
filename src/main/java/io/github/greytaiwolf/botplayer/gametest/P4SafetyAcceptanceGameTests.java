package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.safety.EffectSummary;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.safety.SafetyIntervention;
import io.github.greytaiwolf.botplayer.safety.SafetyState;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P4 安全验收直接读取真实玩家身体、原版敌对 target 和 NeoForge 伤害事件。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P4SafetyAcceptanceGameTests {
    private static final String BATCH = "p4_safety";
    private static final String AGGRO_NAVIGATION_BATCH =
            "p4_aggro_navigation";
    private static final String AGGRO_DAMAGE_BATCH =
            "p4_aggro_damage";
    private static final String STARVATION_BATCH =
            "p4_starvation";
    private static final String HUNGER_ACTIVITY_BATCH =
            "p4_hunger_activity";
    private static final String COMPATIBILITY_BATCH =
            "p4_compatibility";
    private static final String ENVIRONMENT_BATCH =
            "p4_environment";
    private static final String FAST_THREAT_BATCH =
            "p4_fast_threats";
    private static final String FIXTURE_ATTRIBUTE_BUFF_TAG =
            "botplayer_p4_fixture_attribute_buff";
    private static final ResourceKey<DamageType>
            COMPATIBILITY_PROBE_DAMAGE = ResourceKey.create(
                    Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(
                            BotPlayer.MOD_ID,
                            "compatibility_probe"));
    private static final int TIMEOUT_TICKS = 300;
    private static final int HOSTILE_FALLBACK_STABLE_TICKS = 8;

    private P4SafetyAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void damageAndDynamicEffectsReachTheRealBody(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Damage",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double baseMovementSpeed = bot.player()
                    .getAttributeValue(Attributes.MOVEMENT_SPEED);
            P2GameTestSupport.awaitCondition(
                    helper,
                    40,
                    () -> bot.manager()
                            .latestSafetyFrame(bot.name())
                            .isPresent(),
                    "P4 did not establish the pre-damage safety baseline",
                    cleanup,
                    () -> helper.runAfterDelay(
                            65L,
                            () -> applyDamageAndAwait(
                                    helper,
                                    bot,
                                    cleanup,
                                    baseMovementSpeed)));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void applyDamageAndAwait(
            GameTestHelper helper,
            TestBot bot,
            P2GameTestSupport.Cleanup cleanup,
            double baseMovementSpeed) {
        bot.player().addEffect(new MobEffectInstance(
                MobEffects.POISON, 120, 0));
        bot.player().addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SPEED, 120, 0));
        float healthBefore = bot.player().getHealth();
        bot.player().invulnerableTime = 0;
        boolean hurt = bot.player().hurt(
                bot.player().damageSources().generic(), 3.0F);
        P2GameTestSupport.require(
                hurt,
                "Vanilla generic damage was unexpectedly rejected");
        P2GameTestSupport.awaitCondition(
                helper,
                80,
                () -> bot.manager()
                        .latestSafetyFrame(bot.name())
                            .filter(frame ->
                                    frame.recentDamage().isPresent()
                                            && frame.authoritativeVitalLoss()
                                                    > 0.0F
                                            && hasEffect(
                                                    frame,
                                                    "minecraft:poison",
                                                    EffectSummary.Category
                                                            .HARMFUL)
                                            && hasEffect(
                                                    frame,
                                                    "minecraft:speed",
                                                    EffectSummary.Category
                                                            .BENEFICIAL))
                            .isPresent(),
                "P4 did not correlate real damage and harmful effect state",
                cleanup,
                () -> {
                    SafetyFrame frame = bot.manager()
                            .latestSafetyFrame(bot.name())
                            .orElseThrow();
                    P2GameTestSupport.require(
                            bot.player().getHealth() < healthBefore,
                            "Damage observation existed without real body damage");
                    P2GameTestSupport.require(
                            frame.recentDamage()
                                    .orElseThrow()
                                    .damageTypeId()
                                    .contains("generic"),
                            "Dynamic damage type ID was not retained");
                    P2GameTestSupport.require(
                            bot.player().getAttributeValue(
                                            Attributes.MOVEMENT_SPEED)
                                    > baseMovementSpeed,
                            "Beneficial effect did not modify the real player attribute");
                    cleanup.run();
                    helper.succeed();
                });
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void timedBuffExpiresAndRestoresTheRealAttribute(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4BuffLife",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double baseMovementSpeed = bot.player()
                    .getAttributeValue(Attributes.MOVEMENT_SPEED);
            bot.player().addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SPEED, 30, 1));
            P2GameTestSupport.awaitCondition(
                    helper,
                    20,
                    () -> bot.player().getAttributeValue(
                                            Attributes.MOVEMENT_SPEED)
                                    > baseMovementSpeed
                            && bot.manager()
                                    .latestSafetyFrame(bot.name())
                                    .filter(frame -> hasEffect(
                                            frame,
                                            "minecraft:speed",
                                            EffectSummary.Category
                                                    .BENEFICIAL))
                                    .isPresent(),
                    "P4 did not observe the live beneficial attribute modifier",
                    cleanup,
                    () -> P2GameTestSupport.awaitCondition(
                            helper,
                            80,
                            () -> bot.player()
                                                    .getEffect(
                                                            MobEffects
                                                                    .MOVEMENT_SPEED)
                                            == null
                                    && Math.abs(
                                                    bot.player()
                                                                    .getAttributeValue(
                                                                            Attributes
                                                                                    .MOVEMENT_SPEED)
                                                            - baseMovementSpeed)
                                            < 1.0E-9D
                                    && bot.manager()
                                            .latestSafetyFrame(
                                                    bot.name())
                                            .filter(frame -> !hasEffect(
                                                    frame,
                                                    "minecraft:speed",
                                                    EffectSummary
                                                            .Category
                                                            .BENEFICIAL))
                                            .isPresent(),
                            "P4 did not observe natural effect expiry and attribute restoration",
                            cleanup,
                            () -> {
                                cleanup.run();
                                helper.succeed();
                            }));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void vanillaArmorReducesDamageOnTheRealBodies(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot naked = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Naked",
                new Vec3(3.5D, 1.0D, 4.5D),
                0.0F);
        TestBot armored = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Armor",
                new Vec3(5.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(naked);
        cleanup.add(() -> P2GameTestSupport.removeBot(
                armored, "P4 armor GameTest completed"));
        Zombie attacker = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "GameTest armor attacker");
        attacker.setNoAi(true);
        attacker.setInvulnerable(true);
        Vec3 attackerPosition = helper.absoluteVec(
                new Vec3(4.5D, 1.0D, 2.5D));
        attacker.moveTo(
                attackerPosition.x,
                attackerPosition.y,
                attackerPosition.z,
                0.0F,
                0.0F);
        cleanup.add(attacker::discard);
        try {
            P2GameTestSupport.require(
                    helper.getLevel().addFreshEntity(attacker),
                    "Armor comparison attacker could not enter the GameTest level");
            armored.player().setItemSlot(
                    EquipmentSlot.CHEST,
                    new ItemStack(Items.DIAMOND_CHESTPLATE));
            armored.player().setItemSlot(
                    EquipmentSlot.HEAD,
                    new ItemStack(Items.DIAMOND_HELMET));
            helper.runAfterDelay(
                    65L,
                    () -> {
                        try {
                            naked.player().invulnerableTime = 0;
                            armored.player().invulnerableTime = 0;
                            boolean nakedHurt = naked.player().hurt(
                                    naked.player().damageSources()
                                            .mobAttack(attacker),
                                    8.0F);
                            boolean armoredHurt = armored.player().hurt(
                                    armored.player().damageSources()
                                            .mobAttack(attacker),
                                    8.0F);
                            P2GameTestSupport.require(
                                    nakedHurt && armoredHurt,
                                    "Vanilla armor comparison damage was rejected");
                            helper.runAfterDelay(
                                    1L,
                                    () -> {
                                        try {
                                            P2GameTestSupport.require(
                                                    armored.player()
                                                                    .getArmorValue()
                                                            > naked.player()
                                                                    .getArmorValue(),
                                                    "Equipped armor was not authoritative on the bot");
                                            P2GameTestSupport.require(
                                                    armored.player()
                                                                    .getHealth()
                                                            > naked.player()
                                                                    .getHealth(),
                                                    "Vanilla armor did not reduce final bot damage");
                                        } finally {
                                            cleanup.run();
                                        }
                                        helper.succeed();
                                    });
                        } catch (RuntimeException | AssertionError exception) {
                            cleanup.run();
                            throw exception;
                        }
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void lowFoodBlocksSprintAndCreatesAStableIncident(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Food",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().setSprinting(true);

            P2GameTestSupport.awaitCondition(
                    helper,
                    60,
                    () -> bot.manager()
                            .safetyIncident(bot.name())
                            .filter(value ->
                                    value.hazardType()
                                            == HazardType.FOOD_CRITICAL)
                            .isPresent()
                            && !bot.player().isSprinting(),
                    "P4 did not stop sprinting at the critical food threshold",
                    cleanup,
                    () -> {
                        SafetyIncidentView incident = bot.manager()
                                .safetyIncident(bot.name())
                                .orElseThrow();
                        P2GameTestSupport.require(
                                incident.botGeneration()
                                        == bot.player()
                                                .runtimeHandle()
                                                .generation(),
                                "Food incident was attached to a stale generation");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cliffReflexPreemptsForwardMovement(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 5; z <= 8; z++) {
                for (int y = -4; y <= 0; y++) {
                    helper.setBlock(
                            new BlockPos(x, y, z),
                            net.minecraft.world.level.block.Blocks.AIR);
                }
            }
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Cliff",
                new Vec3(4.5D, 1.0D, 4.2D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            CompletableFuture<ActionOutcome> movement =
                    P2GameTestSupport.submit(
                                    bot,
                                    new MoveInputAction(
                                            1.0F,
                                            0.0F,
                                            true,
                                            false,
                                            false,
                                            40,
                                            20),
                                    80)
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> movement.isDone()
                            && bot.manager()
                                    .safetyIncident(bot.name())
                                    .filter(incident ->
                                            incident.hazardType()
                                                    == HazardType
                                                            .FALL_IMMINENT)
                                    .isPresent(),
                    "P4 cliff reflex did not preempt forward input",
                    cleanup,
                    () -> {
                        ActionOutcome outcome = movement.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.PREEMPTED
                                        || outcome.state()
                                                == ActionState.CANCELLED,
                                "Forward movement was not preempted by L0");
                        BlockPos edge = helper.absolutePos(
                                new BlockPos(4, 1, 5));
                        P2GameTestSupport.require(
                                bot.player().getZ()
                                        < edge.getZ() + 0.2D,
                                "Bot crossed the prepared cliff edge");
                        P2GameTestSupport.require(
                                !bot.player().isDeadOrDying(),
                                "Cliff reflex allowed a fatal fall");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = AGGRO_NAVIGATION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void zombieTargetingPreemptsOrdinaryNavigation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        Difficulty previousDifficulty =
                helper.getLevel()
                        .getServer()
                        .getWorldData()
                        .getDifficulty();
        P2GameTestSupport.Cleanup cleanup = cleanup();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            helper.getLevel()
                    .getServer()
                    .setDifficulty(Difficulty.NORMAL, true);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P4Aggro",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    0.0F);
            trackBot(cleanup, bot);
            Zombie zombie = Objects.requireNonNull(
                    EntityType.ZOMBIE.create(helper.getLevel()),
                    "GameTest zombie");
            cleanup.add(zombie::discard);
            Vec3 zombiePosition = helper.absoluteVec(
                    new Vec3(4.5D, 1.0D, 3.0D));
            zombie.moveTo(
                    zombiePosition.x,
                    zombiePosition.y,
                    zombiePosition.z,
                    0.0F,
                    0.0F);
            zombie.setPersistenceRequired();
            zombie.setInvulnerable(true);
            zombie.setNoAi(true);
            zombie.setTarget(bot.player());
            P2GameTestSupport.require(
                    helper.getLevel().addFreshEntity(zombie),
                    "Zombie could not enter the GameTest level");

            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission navigation =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    navigation.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "Navigation setup was rejected before hostile preemption");
            boolean[] observedSuspended = {false};
            boolean[] observedRetreat = {false};
            boolean[] everDelegated = {false};
            boolean[] everStartedSurvivalSkill = {false};
            int[] stableFallbackTicks = {0};

            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> {
                        boolean navigationSuspended = bot.manager()
                                .navigationSession(bot.name())
                                .filter(view ->
                                        view.state()
                                                == NavigationState
                                                        .SUSPENDED_BY_SAFETY)
                                .isPresent();
                        observedSuspended[0] |= navigationSuspended;
                        SafetyIncidentView incident = bot.manager()
                                .safetyIncident(bot.name())
                                .orElse(null);
                        boolean delegatedThisTick = incident != null
                                && (incident.state()
                                                == SafetyState.DELEGATED
                                        || incident
                                                .currentIntervention()
                                                .filter(value ->
                                                        value
                                                                == SafetyIntervention
                                                                        .DELEGATE_TO_SURVIVAL_SKILL)
                                                .isPresent());
                        everDelegated[0] |= delegatedThisTick;
                        boolean hostileIncidentActive = incident != null
                                && incident.hazardType()
                                        == HazardType
                                                .HOSTILE_TARGETING;
                        boolean retreatThisTick =
                                hostileIncidentActive
                                        && incident
                                                .currentIntervention()
                                                .filter(value ->
                                                        value
                                                                == SafetyIntervention
                                                                        .RETREAT_FROM_HOSTILE)
                                                .isPresent();
                        observedRetreat[0] |= retreatThisTick;
                        boolean survivalSkillActive = bot.manager()
                                .survivalSkillRun(bot.name())
                                .isPresent();
                        everStartedSurvivalSkill[0] |=
                                survivalSkillActive;
                        boolean targetingThreat = bot.manager()
                                        .latestSafetyFrame(bot.name())
                                        .stream()
                                        .flatMap(frame ->
                                                frame.threats().stream())
                                        .anyMatch(threat ->
                                                threat.entityId()
                                                                .equals(
                                                                        zombie
                                                                                .getUUID())
                                                        && threat
                                                                .targetingBot());
                        boolean stableFallback = observedRetreat[0]
                                && navigationSuspended
                                && hostileIncidentActive
                                && !delegatedThisTick
                                && !survivalSkillActive
                                && targetingThreat
                                && zombie.getTarget()
                                        == bot.player();
                        if (stableFallback) {
                            stableFallbackTicks[0]++;
                        } else if (observedRetreat[0]) {
                            stableFallbackTicks[0] = 0;
                        }
                        return observedSuspended[0]
                                && observedRetreat[0]
                                && stableFallbackTicks[0]
                                        >= HOSTILE_FALLBACK_STABLE_TICKS;
                    },
                    "P4 did not sustain hostile retreat authority after suspending navigation",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                observedRetreat[0]
                                        && stableFallbackTicks[0]
                                                >= HOSTILE_FALLBACK_STABLE_TICKS,
                                "P4 did not reach a stable RETREAT_FROM_HOSTILE window");
                        P2GameTestSupport.require(
                                !everDelegated[0]
                                        && !everStartedSurvivalSkill[0],
                                "Incomplete P5 self-defense intercepted the P4 hostile fallback");
                        P2GameTestSupport.require(
                                zombie.getTarget() == bot.player(),
                                "Zombie no longer targeted the real bot body");
                        P2GameTestSupport.require(
                                bot.manager()
                                        .safetyIncident(bot.name())
                                        .filter(incident ->
                                                incident.hazardType()
                                                                == HazardType
                                                                        .HOSTILE_TARGETING
                                                        && incident
                                                                .currentIntervention()
                                                                .filter(value ->
                                                                        value
                                                                                == SafetyIntervention
                                                                                        .DELEGATE_TO_SURVIVAL_SKILL)
                                                                .isEmpty())
                                        .isPresent(),
                                "Hostile preemption bypassed the P4 fallback");
                        P2GameTestSupport.require(
                                bot.manager()
                                        .survivalSkillRun(bot.name())
                                        .isEmpty(),
                                "Incomplete P5 self-defense intercepted the P4 hostile fallback");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = AGGRO_DAMAGE_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void targetingZombieEventuallyDamagesTheRealBot(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(
                        new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(
                        new BlockPos(x, y, 5), Blocks.STONE);
            }
        }
        for (int z = 2; z <= 5; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(
                        new BlockPos(3, y, z), Blocks.STONE);
                helper.setBlock(
                        new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        Difficulty previousDifficulty =
                helper.getLevel()
                        .getServer()
                        .getWorldData()
                        .getDifficulty();
        P2GameTestSupport.Cleanup cleanup = cleanup();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            helper.getLevel()
                    .getServer()
                    .setDifficulty(Difficulty.NORMAL, true);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P4MobHit",
                    new Vec3(4.5D, 1.0D, 4.2D),
                    0.0F);
            trackBot(cleanup, bot);
            Zombie zombie = Objects.requireNonNull(
                    EntityType.ZOMBIE.create(helper.getLevel()),
                    "GameTest attacking zombie");
            cleanup.add(zombie::discard);
            zombie.setItemSlot(
                    EquipmentSlot.HEAD,
                    new ItemStack(Items.DIAMOND_HELMET));
            zombie.setPersistenceRequired();
            zombie.setInvulnerable(true);
            Vec3 zombiePosition = helper.absoluteVec(
                    new Vec3(4.5D, 1.0D, 3.2D));
            zombie.moveTo(
                    zombiePosition.x,
                    zombiePosition.y,
                    zombiePosition.z,
                    0.0F,
                    0.0F);
            zombie.setTarget(bot.player());
            P2GameTestSupport.require(
                    helper.getLevel().addFreshEntity(zombie),
                    "Attacking zombie could not enter the GameTest level");
            float initialHealth = bot.player().getHealth();
            P2GameTestSupport.awaitCondition(
                    helper,
                    240,
                    () -> zombie.getTarget() == bot.player()
                            && bot.player().getHealth()
                                    < initialHealth
                            && bot.manager()
                                    .latestSafetyFrame(bot.name())
                                    .filter(frame ->
                                            frame.authoritativeVitalLoss()
                                                            > 0.0F
                                                    || frame.recentDamage()
                                                            .isPresent())
                                    .isPresent(),
                    "Targeting zombie never dealt real melee damage to the bot",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.manager()
                                        .latestSafetyFrame(bot.name())
                                        .filter(frame ->
                                                frame.authoritativeVitalLoss()
                                                                > 0.0F
                                                        || frame.recentDamage()
                                                                .isPresent())
                                        .isPresent(),
                                "Real mob damage was absent from the safety plane");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = STARVATION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void zeroFoodCausesVanillaStarvationDamage(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        Difficulty previousDifficulty =
                helper.getLevel()
                        .getServer()
                        .getWorldData()
                        .getDifficulty();
        P2GameTestSupport.Cleanup cleanup = cleanup();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            helper.getLevel()
                    .getServer()
                    .setDifficulty(Difficulty.HARD, true);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P4Starve",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    0.0F);
            trackBot(cleanup, bot);
            bot.player().getFoodData().setFoodLevel(0);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(6.0F);
            float initialHealth = bot.player().getHealth();
            P2GameTestSupport.awaitCondition(
                    helper,
                    40,
                    () -> bot.manager()
                            .safetyIncident(bot.name())
                            .filter(incident ->
                                    incident.hazardType()
                                            == HazardType.FOOD_CRITICAL)
                            .isPresent(),
                    "P4 did not establish a critical-food incident before starvation",
                    cleanup,
                    () -> P2GameTestSupport.awaitCondition(
                            helper,
                            220,
                            () -> bot.player().getHealth()
                                    < initialHealth,
                            "FoodData did not apply vanilla starvation damage",
                            cleanup,
                            () -> {
                                cleanup.run();
                                helper.succeed();
                            }));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = HUNGER_ACTIVITY_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void realSprintConsumesFoodDataExhaustion(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Exhaust",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            bot.player().getFoodData().setFoodLevel(20);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(3.9F);
            double startingZ = bot.player().getZ();
            CompletableFuture<ActionOutcome> sprint =
                    P2GameTestSupport.submit(
                                    bot,
                                    new MoveInputAction(
                                            1.0F,
                                            0.0F,
                                            true,
                                            false,
                                            false,
                                            20,
                                            10),
                                    60)
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> sprint.isDone()
                            && bot.player()
                                            .getFoodData()
                                            .getFoodLevel()
                                    < 20,
                    "Real sprint input did not cross the vanilla FoodData exhaustion threshold",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                sprint.join().state()
                                        == ActionState.SUCCEEDED,
                                "Sprint exhaustion action did not complete");
                        P2GameTestSupport.require(
                                bot.player().getZ()
                                        > startingZ + 0.5D,
                                "Food loss occurred without real sprint displacement");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = ENVIRONMENT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void fireContactCreatesARealEscapeIntervention(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Fire",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        BlockPos firePosition =
                helper.absolutePos(new BlockPos(4, 1, 4));
        boolean[] observedFire = {false};
        boolean[] observedEscape = {false};
        try {
            helper.setBlock(new BlockPos(4, 1, 4), Blocks.FIRE);
            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> {
                        observedFire[0] |= bot.manager()
                                .latestSafetyFrame(bot.name())
                                .filter(SafetyFrame::onFire)
                                .isPresent()
                                && bot.manager()
                                        .safetyIncident(bot.name())
                                        .filter(incident ->
                                                incident.hazardType()
                                                        == HazardType
                                                                .FIRE_CONTACT)
                                        .isPresent();
                        observedEscape[0] |= bot.manager()
                                .safetyIncident(bot.name())
                                .flatMap(
                                        SafetyIncidentView
                                                ::currentIntervention)
                                .filter(intervention ->
                                        intervention
                                                == SafetyIntervention
                                                        .MOVE_TO_SAFE_NEIGHBOR)
                                .isPresent();
                        return observedFire[0]
                                && observedEscape[0]
                                && bot.player()
                                                .blockPosition()
                                                .distManhattan(
                                                        firePosition)
                                        > 0;
                    },
                    "P4 did not turn real fire contact into a physical escape",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.player()
                                                .blockPosition()
                                                .distManhattan(
                                                        firePosition)
                                        > 0,
                                "Fire intervention did not move the real body away");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = ENVIRONMENT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void lowAirWaterColumnTriggersSwimUp(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                for (int y = 1; y <= 2; y++) {
                    helper.setBlock(
                            new BlockPos(x, y, z),
                            Blocks.WATER);
                }
            }
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Drown",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        double startingY = bot.player().getY();
        boolean[] observedDrowning = {false};
        boolean[] observedSwim = {false};
        try {
            bot.player().setAirSupply(20);
            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> {
                        observedDrowning[0] |= bot.manager()
                                .safetyIncident(bot.name())
                                .filter(incident ->
                                        incident.hazardType()
                                                == HazardType.DROWNING)
                                .isPresent();
                        observedSwim[0] |= bot.manager()
                                .safetyIncident(bot.name())
                                .flatMap(
                                        SafetyIncidentView
                                                ::currentIntervention)
                                .filter(intervention ->
                                        intervention
                                                == SafetyIntervention
                                                        .SWIM_UP)
                                .isPresent();
                        return observedDrowning[0]
                                && observedSwim[0]
                                && bot.player().getY()
                                        > startingY + 0.05D;
                    },
                    "P4 did not convert low underwater air into upward movement",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.player().getY()
                                        > startingY + 0.05D,
                                "Drowning intervention did not move the real body upward");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = FAST_THREAT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void approachingArrowPreemptsOrdinaryMovement(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Arrow",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        Arrow arrow = Objects.requireNonNull(
                EntityType.ARROW.create(helper.getLevel()),
                "GameTest arrow");
        cleanup.add(arrow::discard);
        try {
            CompletableFuture<ActionOutcome> movement =
                    P2GameTestSupport.submit(
                                    bot,
                                    new MoveInputAction(
                                            1.0F,
                                            0.0F,
                                            false,
                                            false,
                                            false,
                                            60,
                                            20),
                                    100)
                            .toCompletableFuture();
            Vec3 arrowPosition = helper.absoluteVec(
                    new Vec3(4.5D, 2.2D, 1.5D));
            arrow.moveTo(
                    arrowPosition.x,
                    arrowPosition.y,
                    arrowPosition.z,
                    0.0F,
                    0.0F);
            arrow.setNoGravity(true);
            arrow.setDeltaMovement(0.0D, 0.0D, 0.08D);
            P2GameTestSupport.require(
                    helper.getLevel().addFreshEntity(arrow),
                    "Approaching arrow could not enter the GameTest level");
            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> movement.isDone()
                            && bot.manager()
                                    .safetyIncident(bot.name())
                                    .filter(incident ->
                                            incident.hazardType()
                                                            == HazardType
                                                                    .PROJECTILE_IMPACT
                                                    && incident
                                                            .currentIntervention()
                                                            .filter(
                                                                    intervention ->
                                                                            intervention
                                                                                    == SafetyIntervention
                                                                                            .DODGE_PROJECTILE)
                                                            .isPresent())
                                    .isPresent(),
                    "P4 did not preempt ordinary input for an approaching projectile",
                    cleanup,
                    () -> {
                        ActionOutcome outcome = movement.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.PREEMPTED
                                        || outcome.state()
                                                == ActionState.CANCELLED,
                                "Projectile emergency did not preempt ordinary movement");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = FAST_THREAT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void primedTntTriggersExplosionRetreat(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Tnt",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        PrimedTnt tnt = Objects.requireNonNull(
                EntityType.TNT.create(helper.getLevel()),
                "GameTest primed TNT");
        cleanup.add(tnt::discard);
        try {
            Vec3 tntPosition = helper.absoluteVec(
                    new Vec3(4.5D, 1.0D, 2.5D));
            tnt.moveTo(
                    tntPosition.x,
                    tntPosition.y,
                    tntPosition.z,
                    0.0F,
                    0.0F);
            tnt.setFuse(200);
            P2GameTestSupport.require(
                    helper.getLevel().addFreshEntity(tnt),
                    "Primed TNT could not enter the GameTest level");
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> bot.manager()
                            .safetyIncident(bot.name())
                            .filter(incident ->
                                    incident.hazardType()
                                                    == HazardType
                                                            .EXPLOSION_IMMINENT
                                            && incident
                                                    .currentIntervention()
                                                    .filter(
                                                            intervention ->
                                                                    intervention
                                                                            == SafetyIntervention
                                                                                    .MOVE_AWAY_FROM_EXPLOSION)
                                                    .isPresent())
                            .isPresent(),
                    "P4 did not convert primed TNT into an explosion retreat",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                tnt.isAlive(),
                                "TNT exploded before the safety response was observed");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = COMPATIBILITY_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void dynamicDamageTypeReachesAndIsObservedOnTheRealBot(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4DynDamage",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            helper.runAfterDelay(
                    65L,
                    () -> {
                        try {
                            Registry<DamageType> damageTypes =
                                    helper.getLevel()
                                            .registryAccess()
                                            .registryOrThrow(
                                                    Registries
                                                            .DAMAGE_TYPE);
                            DamageSource source = new DamageSource(
                                    damageTypes.getHolderOrThrow(
                                            COMPATIBILITY_PROBE_DAMAGE));
                            float healthBefore =
                                    bot.player().getHealth();
                            bot.player().invulnerableTime = 0;
                            P2GameTestSupport.require(
                                    bot.player().hurt(source, 4.0F),
                                    "Dynamic GameTest damage type was rejected");
                            P2GameTestSupport.awaitCondition(
                                    helper,
                                    60,
                                    () -> bot.player().getHealth()
                                                    < healthBefore
                                            && bot.manager()
                                                    .latestSafetyFrame(
                                                            bot.name())
                                                    .filter(frame ->
                                                            frame.recentDamage()
                                                                    .filter(
                                                                            damage ->
                                                                                    damage.damageTypeId()
                                                                                            .equals(
                                                                                                    "botplayer:compatibility_probe"))
                                                                    .isPresent()
                                                                    && frame.authoritativeVitalLoss()
                                                                            > 0.0F)
                                                    .isPresent(),
                                    "P4 lost the dynamic damage ID or real body loss",
                                    cleanup,
                                    () -> {
                                        cleanup.run();
                                        helper.succeed();
                                    });
                        } catch (RuntimeException | AssertionError exception) {
                            cleanup.run();
                            throw exception;
                        }
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = COMPATIBILITY_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void playerTickFixtureModifiesAndRestoresTheRealAttribute(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4TickBuff",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double baseMovementSpeed = bot.player()
                    .getAttributeValue(Attributes.MOVEMENT_SPEED);
            P2GameTestSupport.require(
                    bot.player().addTag(
                            FIXTURE_ATTRIBUTE_BUFF_TAG),
                    "PlayerTick fixture marker could not be added");
            P2GameTestSupport.awaitCondition(
                    helper,
                    40,
                    () -> bot.player()
                                    .getAttributeValue(
                                            Attributes.MOVEMENT_SPEED)
                            > baseMovementSpeed,
                    "Standard PlayerTickEvent did not modify the real bot attribute",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.player().removeTag(
                                        FIXTURE_ATTRIBUTE_BUFF_TAG),
                                "PlayerTick fixture marker could not be removed");
                        P2GameTestSupport.awaitCondition(
                                helper,
                                40,
                                () -> Math.abs(
                                                bot.player()
                                                                .getAttributeValue(
                                                                        Attributes
                                                                                .MOVEMENT_SPEED)
                                                        - baseMovementSpeed)
                                        < 1.0E-9D,
                                "PlayerTick fixture attribute did not restore",
                                cleanup,
                                () -> {
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static boolean hasEffect(
            SafetyFrame frame,
            String effectId,
            EffectSummary.Category category) {
        return frame.effects().stream().anyMatch(effect ->
                effect.effectId().equals(effectId)
                        && effect.category() == category);
    }

    private static P2GameTestSupport.Cleanup cleanup(
            TestBot bot) {
        P2GameTestSupport.Cleanup cleanup = cleanup();
        trackBot(cleanup, bot);
        return cleanup;
    }

    private static P2GameTestSupport.Cleanup cleanup() {
        return new P2GameTestSupport.Cleanup();
    }

    private static void trackBot(
            P2GameTestSupport.Cleanup cleanup,
            TestBot bot) {
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P4 safety GameTest completed"));
    }
}
