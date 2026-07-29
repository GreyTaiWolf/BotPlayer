package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.safety.EffectSummary;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
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
            bot.player().addEffect(new MobEffectInstance(
                    MobEffects.POISON, 120, 0));
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
                                                            .HARMFUL))
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
