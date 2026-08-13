package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldVillagerTradeActionSpecTest {
    @Test
    void exposesOnlyTheBoundedMainHandSingleOfferContract() {
        WorldInteractionActionSpec.WorldVillagerTrade trade = valid();

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        WorldInteractionActionSpec.Kind.WORLD_VILLAGER_TRADE,
                        trade.kind()),
                () -> Assertions.assertEquals(ActionKind.WORLD_VILLAGER_TRADE,
                        new WorldInteractionAction(trade).kind()),
                () -> Assertions.assertEquals(8,
                        trade.limits().maxClicks()),
                () -> Assertions.assertTrue(trade.channels().containsAll(
                        java.util.Set.of(
                                io.github.greytaiwolf.botplayer.action
                                        .ActionChannel.INVENTORY,
                                io.github.greytaiwolf.botplayer.action
                                        .ActionChannel.MAIN_HAND,
                                io.github.greytaiwolf.botplayer.action
                                        .ActionChannel.INTERACT))));
    }

    @Test
    void rejectsOffhandWrongEntityShortSourceLevelAndClickBudget() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> create(WorldInteractionActionSpec.Hand.OFF_HAND,
                                villager(), source(7), 1,
                                new MenuTransactionLimits(8, 120))),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> create(WorldInteractionActionSpec.Hand.MAIN_HAND,
                                new EntityTargetFingerprint(
                                        new ResourceId("minecraft:overworld"),
                                        UUID.fromString(
                                                "00000000-0000-0000-0000-000000000001"),
                                        new ResourceId("minecraft:pig")),
                                source(7), 1,
                                new MenuTransactionLimits(8, 120))),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> create(WorldInteractionActionSpec.Hand.MAIN_HAND,
                                villager(), source(5), 1,
                                new MenuTransactionLimits(8, 120))),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> create(WorldInteractionActionSpec.Hand.MAIN_HAND,
                                villager(), source(7), 0,
                                new MenuTransactionLimits(8, 120))),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> create(WorldInteractionActionSpec.Hand.MAIN_HAND,
                                villager(), source(7), 1,
                                new MenuTransactionLimits(7, 120))));
    }

    @Test
    void rejectsOfferStateWhoseFrozenBasePriceDoesNotMatchThePaymentIdentity() {
        WorldInteractionActionSpec.WorldVillagerTrade trade = valid();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorldInteractionActionSpec.WorldVillagerTrade(
                        trade.hand(),
                        trade.villager(),
                        trade.offerIndex(),
                        trade.sourceInventorySlot(),
                        trade.outputInventorySlot(),
                        trade.expectedSource(),
                        trade.expectedCost(),
                        trade.expectedResult(),
                        new WorldInteractionActionSpec.WorldVillagerTrade
                                .MerchantOfferState(
                                        stack("minecraft:carrot", 5, 'd'),
                                        0,
                                        0,
                                        Float.floatToIntBits(0.05F),
                                        1,
                                        true),
                        trade.expectedVillagerLevel(),
                        trade.expectedVillagerXp(),
                        trade.expectedOfferUses(),
                        trade.expectedOfferMaxUses(),
                        trade.limits()));
    }

    private static WorldInteractionActionSpec.WorldVillagerTrade valid() {
        return create(WorldInteractionActionSpec.Hand.MAIN_HAND, villager(),
                source(7), 1, new MenuTransactionLimits(8, 120));
    }

    private static WorldInteractionActionSpec.WorldVillagerTrade create(
            WorldInteractionActionSpec.Hand hand,
            EntityTargetFingerprint target,
            ItemStackFingerprint source,
            int level,
            MenuTransactionLimits limits) {
        return new WorldInteractionActionSpec.WorldVillagerTrade(
                hand,
                target,
                0,
                9,
                0,
                source,
                stack("minecraft:wheat", 5, 'a'),
                stack("minecraft:emerald", 1, 'b'),
                offerState(),
                level,
                0,
                0,
                4,
                limits);
    }

    private static WorldInteractionActionSpec.WorldVillagerTrade
            .MerchantOfferState offerState() {
        return new WorldInteractionActionSpec.WorldVillagerTrade
                .MerchantOfferState(
                        stack("minecraft:wheat", 5, 'a'),
                        0,
                        0,
                        Float.floatToIntBits(0.05F),
                        1,
                        true);
    }

    private static EntityTargetFingerprint villager() {
        return new EntityTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceId("minecraft:villager"));
    }

    private static ItemStackFingerprint source(int count) {
        return stack("minecraft:wheat", count, 'a');
    }

    private static ItemStackFingerprint stack(
            String itemId, int count, char digest) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0,
                String.valueOf(digest).repeat(64));
    }
}
