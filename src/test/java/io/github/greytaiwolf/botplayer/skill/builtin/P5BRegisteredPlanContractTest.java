package io.github.greytaiwolf.botplayer.skill.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.VanillaMilkBucketRecovery;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VanillaVillagerTrade;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure contract coverage for the exact P5B descriptors that the lifecycle
 * installs at server start.  It deliberately proves only registry/plan
 * compatibility: live Minecraft observations remain the responsibility of
 * their node handlers and GameTests.
 */
class P5BRegisteredPlanContractTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555");

    @Test
    void canonicalP5BPlansValidateAgainstTheFixedRegistry() {
        SkillRegistry registry = new SkillRegistry();
        WheatFarmingPlanCompiler.descriptors().forEach(descriptor ->
                assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                        registry.register(descriptor)));
        SugarCaneFarmingPlanCompiler.descriptors().forEach(descriptor ->
                assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                        registry.register(descriptor)));
        assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(VanillaCowBreeding.descriptor()));
        assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(VanillaVillagerTrade.descriptor()));
        assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(VanillaMilkBucketRecovery.descriptor()));

        SkillPlanValidator validator = new SkillPlanValidator(
                registry, SkillPlanLimits.defaults());
        assertTrue(validator.validate(WheatFarmingPlanCompiler.compile(
                BOT_ID, 1L, new BlockCoordinates(12, 64, -8))).valid());
        assertTrue(validator.validate(SugarCaneFarmingPlanCompiler.compile(
                BOT_ID, 2L, new BlockCoordinates(12, 65, -8))).valid());
        assertTrue(validator.validate(VanillaCowBreeding.compile(
                BOT_ID,
                3L,
                new VanillaCowBreeding.CowPairRequest(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                        4))).valid());
        assertTrue(validator.validate(VanillaVillagerTrade.compile(
                BOT_ID,
                4L,
                new VanillaVillagerTrade.TradeRequest(
                        UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                        2,
                        9,
                        0))).valid());
        assertTrue(validator.validate(VanillaMilkBucketRecovery.compile(
                BOT_ID,
                5L,
                new VanillaMilkBucketRecovery.MilkRequest(4))).valid());
    }
}
