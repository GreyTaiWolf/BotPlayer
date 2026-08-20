package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VanillaVillagerTrade;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftVillagerTradeSkillNodeHandlerTest {
    private static final UUID RUN = UUID.fromString(
            "00000000-0000-0000-0000-000000000011");
    private static final UUID BOT = UUID.fromString(
            "00000000-0000-0000-0000-000000000012");
    private static final UUID NODE = UUID.fromString(
            "00000000-0000-0000-0000-000000000013");

    @Test
    void parsesOnlyTheExactCanonicalVillagerTradeSchema() {
        MinecraftVillagerTradeSkillNodeHandler.TradeRequest request =
                MinecraftVillagerTradeSkillNodeHandler.parseParameters(
                        parameters("00000000-0000-0000-0000-000000000001",
                                3, 9, 0)).orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        "00000000-0000-0000-0000-000000000001",
                        request.villagerId().toString()),
                () -> Assertions.assertEquals(3, request.offerIndex()),
                () -> Assertions.assertEquals(9,
                        request.sourceInventorySlot()),
                () -> Assertions.assertEquals(0,
                        request.outputInventorySlot()));
    }

    @Test
    void rejectsAliasesCoercionsAndUnsafeStorageRequests() {
        Map<String, Object> extra = values(
                "00000000-0000-0000-0000-000000000001", 0, 9, 0);
        extra.put("villager.dimension", "minecraft:overworld");
        Map<String, Object> coerced = values(
                "00000000-0000-0000-0000-000000000001", 0, 9, 0);
        coerced.put(MinecraftVillagerTradeSkillNodeHandler
                .OFFER_INDEX_PARAMETER, 0L);

        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(new SkillParameters(extra))
                                .isEmpty()),
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(new SkillParameters(coerced))
                                .isEmpty()),
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(parameters(
                                        "00000000-0000-0000-0000-000000000000",
                                        0, 9, 0)).isEmpty()),
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(parameters(
                                        "00000000-0000-0000-0000-000000000001",
                                        0, 36, 0)).isEmpty()),
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(parameters(
                                        "00000000-0000-0000-0000-000000000001",
                                        0, 9, 9)).isEmpty()),
                () -> Assertions.assertTrue(
                        MinecraftVillagerTradeSkillNodeHandler
                                .parseParameters(parameters(
                                        "00000000-0000-0000-0000-000000000001",
                                        -1, 9, 0)).isEmpty()));
    }

    @Test
    void reservesTheExactVillagerAndTheBotNativeInventoryExclusively() {
        MinecraftVillagerTradeSkillNodeHandler handler = newHandler();
        List<ReservationRequest> reservations = handler.requiredReservations(
                context(parameters(
                        "00000000-0000-0000-0000-000000000001",
                        3, 9, 0)));

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, reservations.size()),
                () -> Assertions.assertEquals(ReservationKey.Kind.ENTITY,
                        reservations.get(0).key().kind()),
                () -> Assertions.assertEquals("minecraft.villager_trade",
                        reservations.get(0).key().scope()),
                () -> Assertions.assertEquals(
                        "00000000-0000-0000-0000-000000000001",
                        reservations.get(0).key().subject()),
                () -> Assertions.assertEquals(ReservationMode.EXCLUSIVE,
                        reservations.get(0).mode()),
                () -> Assertions.assertEquals(ReservationKey.Kind.CONTAINER,
                        reservations.get(1).key().kind()),
                () -> Assertions.assertEquals(
                        "minecraft.villager_trade.inventory",
                        reservations.get(1).key().scope()),
                () -> Assertions.assertEquals("bot:" + BOT,
                        reservations.get(1).key().subject()),
                () -> Assertions.assertEquals(ReservationMode.EXCLUSIVE,
                        reservations.get(1).mode()));
    }

    private static MinecraftVillagerTradeSkillNodeHandler newHandler() {
        return new MinecraftVillagerTradeSkillNodeHandler(
                (botId, generation) -> Optional.empty(),
                new ActionBackedSkillNodeHandler.ActionGateway() {
                    @Override
                    public ActionMailbox.Submission submit(
                            ActionEnvelope envelope, ActionPriority priority) {
                        throw new AssertionError("reservation test must not submit an action");
                    }

                    @Override
                    public void cancel(
                            UUID botId,
                            UUID actionId,
                            ActionCancellationReason reason) {
                        throw new AssertionError("reservation test must not cancel an action");
                    }

                    @Override
                    public void cancelStrictNaturalUse(
                            ActionEnvelope envelope,
                            ActionCancellationReason reason) {
                        throw new AssertionError(
                                "reservation test must not cancel a strict natural item use");
                    }
                },
                signal -> {
                    throw new AssertionError("reservation test must not emit a signal");
                });
    }

    private static SkillNodeContext context(SkillParameters parameters) {
        return new SkillNodeContext(
                RUN,
                BOT,
                1L,
                1L,
                0L,
                1L,
                new SkillPlanNode(
                        NODE,
                        VanillaVillagerTrade.ID,
                        VanillaVillagerTrade.VERSION,
                        parameters),
                0,
                10L,
                100L,
                new ResourceReservationService(16, 100));
    }

    private static SkillParameters parameters(
            String villagerId, int offerIndex, int sourceSlot, int outputSlot) {
        return new SkillParameters(values(
                villagerId, offerIndex, sourceSlot, outputSlot));
    }

    private static Map<String, Object> values(
            String villagerId, int offerIndex, int sourceSlot, int outputSlot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(MinecraftVillagerTradeSkillNodeHandler
                .VILLAGER_ID_PARAMETER, villagerId);
        values.put(MinecraftVillagerTradeSkillNodeHandler
                .OFFER_INDEX_PARAMETER, offerIndex);
        values.put(MinecraftVillagerTradeSkillNodeHandler
                .SOURCE_INVENTORY_SLOT_PARAMETER, sourceSlot);
        values.put(MinecraftVillagerTradeSkillNodeHandler
                .OUTPUT_INVENTORY_SLOT_PARAMETER, outputSlot);
        return values;
    }
}
