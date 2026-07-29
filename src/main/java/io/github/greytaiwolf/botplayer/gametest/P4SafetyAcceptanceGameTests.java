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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
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
    private static final String AGGRO_DAMAGE_BATCH =
            "p4_aggro_damage";
    private static final String STARVATION_BATCH =
            "p4_starvation";
    private static final int TIMEOUT_TICKS = 300;

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
        try {
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
                                    naked.player()
                                            .damageSources()
                                            .generic(),
                                    8.0F);
                            boolean armoredHurt = armored.player().hurt(
                                    armored.player()
                                            .damageSources()
                                            .generic(),
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void zombieTargetingPreemptsOrdinaryNavigation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        Difficulty previousDifficulty =
                helper.getLevel()
                        .getServer()
                        .getWorldData()
                        .getDifficulty();
        helper.getLevel()
                .getServer()
                .setDifficulty(Difficulty.NORMAL, true);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Aggro",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        Zombie zombie = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "GameTest zombie");
        cleanup.add(() -> zombie.discard());
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
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

            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> {
                        observedSuspended[0] |= bot.manager()
                                .navigationSession(bot.name())
                                .filter(view ->
                                        view.state()
                                                == NavigationState
                                                        .SUSPENDED_BY_SAFETY)
                                .isPresent();
                        return observedSuspended[0]
                                && bot.manager()
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
                    },
                    "P4 did not observe zombie target authority or suspend navigation",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                zombie.getTarget() == bot.player(),
                                "Zombie no longer targeted the real bot body");
                        P2GameTestSupport.require(
                                bot.manager()
                                        .safetyIncident(bot.name())
                                        .isPresent(),
                                "Hostile preemption lacked an incident");
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
        helper.getLevel()
                .getServer()
                .setDifficulty(Difficulty.NORMAL, true);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4MobHit",
                new Vec3(4.5D, 1.0D, 4.2D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        Zombie zombie = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "GameTest attacking zombie");
        cleanup.add(zombie::discard);
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
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
        helper.getLevel()
                .getServer()
                .setDifficulty(Difficulty.HARD, true);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Starve",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            bot.player().getFoodData().setFoodLevel(0);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(6.0F);
            float initialHealth = bot.player().getHealth();
            P2GameTestSupport.awaitCondition(
                    helper,
                    260,
                    () -> bot.player().getHealth()
                            < initialHealth,
                    "FoodData did not apply vanilla starvation damage",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.manager()
                                        .safetyIncident(bot.name())
                                        .filter(incident ->
                                                incident.hazardType()
                                                        == HazardType
                                                                .FOOD_CRITICAL)
                                        .isPresent(),
                                "Starvation damage lacked the critical-food incident");
                        cleanup.run();
                        helper.succeed();
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
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P4 safety GameTest completed"));
        return cleanup;
    }
}
