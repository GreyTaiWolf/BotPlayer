package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.safety.SafetyIntervention;
import io.github.greytaiwolf.botplayer.safety.SafetyState;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseReason;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseState;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A 有限自卫的真实纵切验收。
 *
 * <p>场景刻意先启动一条普通导航，再由真实 {@link Zombie} target 触发 hostile
 * handoff。它要求 L0 将导航挂起并把控制权移交给 {@link SelfDefenseSkillService}，而不允许
 * 回退为 P4 的 {@link SafetyIntervention#RETREAT_FROM_HOSTILE}。最终的僵尸死亡必须来自真实
 * {@code AttackEntity} 动作，并由自卫服务的运行视图以已完成状态收口。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5SelfDefenseAcceptanceGameTests {
    private static final String BATCH = "p5_self_defense";
    private static final int TIMEOUT_TICKS = 320;

    private P5SelfDefenseAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void hostileTargetingUsesSelfDefenseAndClosesAfterMelee(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(
                        helper, "self_defense_melee_completion");
        TestBot bot = fixture.spawn("guard");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Difficulty previousDifficulty = helper.getLevel()
                .getServer()
                .getWorldData()
                .getDifficulty();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        Zombie zombie = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "P5A self-defense zombie");
        cleanup.add(zombie::discard);
        try {
            helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
            prepareDefender(bot);
            configureTarget(helper, zombie);
            float targetHealthBefore = zombie.getHealth();

            P2GameTestSupport.awaitCondition(
                    helper,
                    60,
                    () -> bot.player()
                                    .getAttackStrengthScale(0.5F)
                            >= 1.0F,
                    "Self-defense fixture did not recover the real attack cooldown",
                    cleanup,
                    () -> beginDefenseAndAwaitCompletion(
                            helper,
                            bot,
                            zombie,
                            targetHealthBefore,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void lowHealthMultipleHostilesNeverDispatchMelee(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(
                        helper, "self_defense_low_health_multiple_hostiles");
        TestBot bot = fixture.spawn("guard");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Zombie first = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "P5A first low-health hostile");
        Zombie second = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "P5A second low-health hostile");
        cleanup.add(first::discard);
        cleanup.add(second::discard);
        Difficulty previousDifficulty = helper.getLevel()
                .getServer()
                .getWorldData()
                .getDifficulty();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
            prepareDefender(bot);
            bot.player().setHealth(3.0F);
            configureTarget(
                    helper, first, new Vec3(3.0D, 1.0D, 4.5D));
            configureTarget(
                    helper, second, new Vec3(6.0D, 1.0D, 4.5D));
            first.setInvulnerable(true);
            second.setInvulnerable(true);
            float firstHealth = first.getHealth();
            float secondHealth = second.getHealth();
            activateTarget(helper, bot, first);
            activateTarget(helper, bot, second);

            boolean[] observedBothThreats = {false};
            boolean[] observedSelfDefenseRun = {false};
            boolean[] observedMelee = {false};
            int[] completeThreatFrames = {0};
            P2GameTestSupport.awaitCondition(
                    helper,
                    60,
                    () -> {
                        boolean bothTargeting = bot.manager()
                                .latestSafetyFrame(bot.name())
                                .map(frame -> frame.threats().stream()
                                        .filter(threat -> threat.targetingBot())
                                        .map(threat -> threat.entityId())
                                        .collect(java.util.stream.Collectors.toSet()))
                                .map(ids -> ids.contains(first.getUUID())
                                        && ids.contains(second.getUUID()))
                                .orElse(false);
                        observedBothThreats[0] |= bothTargeting;
                        if (bothTargeting) {
                            completeThreatFrames[0]++;
                        }
                        long generation = bot.player().runtimeHandle()
                                .generation();
                        observedSelfDefenseRun[0] |= bot.manager()
                                .selfDefenseRun(bot.player().getUUID())
                                .filter(view -> view.generation() == generation
                                        && (view.targetId().equals(first.getUUID())
                                                || view.targetId().equals(
                                                        second.getUUID())))
                                .isPresent();
                        observedMelee[0] |= bot.manager()
                                .selfDefenseRun(bot.player().getUUID())
                                .filter(view -> view.generation() == generation
                                        && (view.targetId().equals(first.getUUID())
                                                || view.targetId().equals(
                                                        second.getUUID()))
                                        && view.status()
                                                == SelfDefenseSkillService
                                                        .RunStatus.ACTIVE
                                        && view.decision().state()
                                                == DefenseState.ATTACK_IN_FLIGHT
                                        && view.decision().action()
                                                .filter(action -> action.kind()
                                                        == DefenseActionKind
                                                                .MELEE_ATTACK)
                                                .isPresent())
                                .isPresent();
                        return completeThreatFrames[0] >= 5;
                    },
                    "P0-2 fixture never observed both targeting hostiles",
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    observedBothThreats[0]
                                            && !observedSelfDefenseRun[0]
                                            && !observedMelee[0],
                                    "Low health plus multiple hostiles started self-defense");
                            P2GameTestSupport.require(
                                    first.getHealth() == firstHealth
                                            && second.getHealth() == secondHealth,
                                    "Low health plus multiple hostiles changed hostile health");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void prepareDefender(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(
                0, new ItemStack(Items.IRON_SWORD));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        bot.player().setHealth(bot.player().getMaxHealth());
        bot.player().invulnerableTime = 0;
    }

    private static void configureTarget(GameTestHelper helper, Zombie zombie) {
        /* Match the already-proven hostile handoff geometry exactly. */
        configureTarget(helper, zombie, new Vec3(4.5D, 1.0D, 3.0D));
    }

    private static void configureTarget(
            GameTestHelper helper, Zombie zombie, Vec3 relativePosition) {
        Vec3 position = helper.absoluteVec(relativePosition);
        zombie.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.HEAD,
                new ItemStack(Items.DIAMOND_HELMET));
        zombie.setHealth(1.0F);
    }

    private static void beginDefenseAndAwaitCompletion(
            GameTestHelper helper,
            TestBot bot,
            Zombie zombie,
            float targetHealthBefore,
            P2GameTestSupport.Cleanup cleanup) {
        NavigationSubmission navigation = bot.manager().startNavigation(
                bot.name(),
                GridPoint.from(helper.absolutePos(new BlockPos(4, 1, 7))));
        P2GameTestSupport.require(
                navigation.status() == NavigationSubmission.Status.ENQUEUED,
                "Navigation setup was rejected before self-defense handoff: "
                        + navigation.status());
        activateTarget(helper, bot, zombie);

        boolean[] observedTargetingThreat = {false};
        boolean[] observedSuspendedNavigation = {false};
        boolean[] observedDelegation = {false};
        boolean[] observedLegacyRetreat = {false};
        boolean[] observedMeleeDispatch = {false};
        UUID[] runId = {null};

        P2GameTestSupport.awaitCondition(
                helper,
                180,
                () -> {
                    observedSuspendedNavigation[0] |= bot.manager()
                            .navigationSession(bot.name())
                            .filter(view -> view.state()
                                    == NavigationState.SUSPENDED_BY_SAFETY)
                            .isPresent();
                    observedTargetingThreat[0] |= bot.manager()
                            .latestSafetyFrame(bot.name())
                            .stream()
                            .flatMap(frame -> frame.threats().stream())
                            .anyMatch(threat -> threat.entityId()
                                            .equals(zombie.getUUID())
                                    && threat.targetingBot());

                    SafetyIncidentView incident = bot.manager()
                            .safetyIncident(bot.name())
                            .orElse(null);
                    if (incident != null
                            && incident.hazardType()
                                    == HazardType.HOSTILE_TARGETING) {
                        observedDelegation[0] |= incident.state()
                                        == SafetyState.DELEGATED
                                && incident.currentIntervention()
                                        .filter(value -> value
                                                == SafetyIntervention
                                                        .DELEGATE_TO_SURVIVAL_SKILL)
                                        .isPresent();
                        observedLegacyRetreat[0] |= incident
                                .currentIntervention()
                                .filter(value -> value
                                        == SafetyIntervention
                                                .RETREAT_FROM_HOSTILE)
                                .isPresent();
                    }

                    SelfDefenseSkillService.RunView view = bot.manager()
                            .selfDefenseRun(bot.player().getUUID())
                            .orElse(null);
                    if (view != null && view.targetId().equals(zombie.getUUID())) {
                        P2GameTestSupport.require(
                                view.botId().equals(bot.player().getUUID())
                                        && view.generation()
                                                == bot.player()
                                                        .runtimeHandle()
                                                        .generation(),
                                "Self-defense view was not bound to the active bot generation");
                        if (runId[0] == null) {
                            runId[0] = view.runId();
                        } else {
                            P2GameTestSupport.require(
                                    runId[0].equals(view.runId()),
                                    "Self-defense replaced its active run during one hostile incident");
                        }
                        if (view.status() != SelfDefenseSkillService.RunStatus.ACTIVE
                                && view.status()
                                        != SelfDefenseSkillService.RunStatus.COMPLETED) {
                            throw new IllegalStateException(
                                    "Self-defense run terminated before a verified melee completion: "
                                            + view);
                        }
                        observedMeleeDispatch[0] |= view.status()
                                        == SelfDefenseSkillService.RunStatus.ACTIVE
                                && view.decision().state()
                                        == DefenseState.ATTACK_IN_FLIGHT
                                && view.decision().action()
                                        .filter(action -> action.kind()
                                                        == DefenseActionKind
                                                                .MELEE_ATTACK
                                                && action.targetId().equals(
                                                        zombie.getUUID()))
                                        .isPresent()
                                && view.activeActionId().isPresent();
                    }
                    return observedTargetingThreat[0]
                            && observedSuspendedNavigation[0]
                            && observedDelegation[0]
                            && observedMeleeDispatch[0]
                            && terminallyCompleted(bot, zombie, runId[0]);
                },
                "P5A self-defense did not complete a delegated real melee response",
                cleanup,
                () -> {
                    try {
                        verifyCompletion(
                                bot,
                                zombie,
                                targetHealthBefore,
                                runId[0],
                                observedTargetingThreat[0],
                                observedSuspendedNavigation[0],
                                observedDelegation[0],
                                observedLegacyRetreat[0],
                                observedMeleeDispatch[0]);
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                });
    }

    /**
     * Activate the hostile only after navigation and all observation probes are
     * ready. Otherwise the safety loop can legitimately complete its bounded
     * response during the initial cooldown wait, before this test records it.
     */
    private static void activateTarget(
            GameTestHelper helper, TestBot bot, Zombie zombie) {
        zombie.setTarget(bot.player());
        P2GameTestSupport.require(
                helper.getLevel().addFreshEntity(zombie),
                "Self-defense zombie could not enter the GameTest level");
        P2GameTestSupport.require(
                zombie.getTarget() == bot.player(),
                "Self-defense zombie did not retain the native bot target");
        P2GameTestSupport.require(
                bot.player().distanceToSqr(zombie) <= 9.0D,
                "Self-defense zombie is outside the bounded melee range");
    }

    private static boolean terminallyCompleted(
            TestBot bot, Zombie zombie, UUID runId) {
        if (runId == null || zombie.isAlive()) {
            return false;
        }
        return bot.manager()
                .selfDefenseRun(bot.player().getUUID())
                .filter(view -> view.runId().equals(runId)
                        && view.status()
                                == SelfDefenseSkillService.RunStatus.COMPLETED
                        && view.decision().state() == DefenseState.COMPLETED
                        && view.decision().reason()
                                == DefenseReason.TARGET_ELIMINATED
                        && view.activeActionId().isEmpty()
                        && view.failure().isEmpty())
                .isPresent();
    }

    private static void verifyCompletion(
            TestBot bot,
            Zombie zombie,
            float targetHealthBefore,
            UUID runId,
            boolean observedTargetingThreat,
            boolean observedSuspendedNavigation,
            boolean observedDelegation,
            boolean observedLegacyRetreat,
            boolean observedMeleeDispatch) {
        P2GameTestSupport.require(
                observedTargetingThreat
                        && observedSuspendedNavigation
                        && observedDelegation
                        && observedMeleeDispatch,
                "Self-defense did not establish every required handoff proof");
        P2GameTestSupport.require(
                !observedLegacyRetreat,
                "Hostile handoff fell back to P4 RETREAT_FROM_HOSTILE");
        P2GameTestSupport.require(
                runId != null,
                "Self-defense never exposed a run identity");
        P2GameTestSupport.require(
                zombie.isRemoved()
                        || !zombie.isAlive()
                        || zombie.getHealth() < targetHealthBefore,
                "Self-defense completed without a real target health effect");
        SelfDefenseSkillService.RunView view = bot.manager()
                .selfDefenseRun(bot.player().getUUID())
                .filter(candidate -> candidate.runId().equals(runId))
                .orElseThrow(() -> new IllegalStateException(
                        "Self-defense did not retain the completed run view"));
        P2GameTestSupport.require(
                view.status() == SelfDefenseSkillService.RunStatus.COMPLETED
                        && view.decision().state() == DefenseState.COMPLETED
                        && view.decision().reason()
                                == DefenseReason.TARGET_ELIMINATED
                        && view.activeActionId().isEmpty()
                        && view.failure().isEmpty()
                        && view.updatedTick() >= view.startedTick()
                        && view.updatedTick() < view.deadlineTick(),
                "Self-defense did not close as a bounded successful run: " + view);
    }
}
