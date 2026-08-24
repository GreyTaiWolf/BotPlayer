package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchStatus;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptOfferPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game P6-R1 acceptance tests for the server-owned offer/ACK/grant session repair boundary.
 *
 * <p>These tests deliberately retain NeoForge's configured mock connection. They observe the
 * actual S2C custom-payload packets and inject the exact C2S ACK through the real server packet
 * listener, rather than calling the lifecycle acknowledgement method directly.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P6ReviewOnlyPhysicalAttemptGameTests {
    private static final String BATCH = "p6_review_physical_attempt";
    private static final int TIMEOUT_TICKS = 300;
    private static final int NO_GRANT_CONFIRMATION_TICKS = 4;

    private P6ReviewOnlyPhysicalAttemptGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void exactPrepareAckEmitsReplayStableGrant(
            GameTestHelper helper) {
        openFixture(helper, "P6AckReplay", fixture -> requestOffer(
                fixture,
                offer -> {
                    P6GameTestSupport.acknowledge(fixture.owner(), offer);
                    awaitGrantCount(fixture, offer, 1, () -> {
                        P6GameTestSupport.acknowledge(fixture.owner(), offer);
                        awaitGrantCount(fixture, offer, 2, () -> {
                            AiPhysicalAttemptIdentity identity = offer.offer().identity();
                            P2GameTestSupport.require(
                                    fixture.capture().grantsFor(identity).size() == 2,
                                    "Repeated exact P6 ACK did not emit two grants");
                            finish(fixture);
                        });
                    });
                }));
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void replacedOfferAckCannotGrantNewReviewSession(
            GameTestHelper helper) {
        openFixture(helper, "P6ReplaceAck", fixture -> requestOffer(
                fixture,
                oldOffer -> awaitCurrentReviewSnapshot(
                        fixture,
                        () -> requestOffer(fixture, replacementOffer -> {
                            P2GameTestSupport.require(
                                    !oldOffer.offer().identity().equals(
                                            replacementOffer.offer().identity()),
                                    "Replacement review reused its physical attempt identity");
                            P6GameTestSupport.acknowledge(fixture.owner(), oldOffer);
                            assertNoAdditionalGrant(
                                    fixture,
                                    oldOffer,
                                    0,
                                    () -> {
                                        P2GameTestSupport.require(
                                                fixture.capture().grantCount(
                                                                replacementOffer.offer()
                                                                        .identity())
                                                        == 0,
                                                "Stale P6 ACK granted the replacement review");
                                        P6GameTestSupport.acknowledge(
                                                fixture.owner(), replacementOffer);
                                        awaitGrantCount(
                                                fixture,
                                                replacementOffer,
                                                1,
                                                () -> {
                                                    P2GameTestSupport.require(
                                                            fixture.capture().grantCount(
                                                                            oldOffer.offer()
                                                                                    .identity())
                                                                    == 0,
                                                            "Superseded P6 offer gained a grant");
                                                    finish(fixture);
                                                });
                                    });
                        }))));
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void unboundOfferAckCannotGrant(
            GameTestHelper helper) {
        openFixture(helper, "P6UnbindAck", fixture -> requestOffer(
                fixture,
                offer -> {
                    AgentBindingStatus unbound = fixture.bot().manager().updateAgentBinding(
                            fixture.owner(),
                            fixture.bot().player().getUUID(),
                            fixture.agentId(),
                            false);
                    P2GameTestSupport.require(
                            unbound == AgentBindingStatus.UNBOUND,
                            "P6 review fixture did not unbind its exact agent");
                    awaitCancellation(fixture, offer, () -> {
                        P6GameTestSupport.acknowledge(fixture.owner(), offer);
                        assertNoAdditionalGrant(
                                fixture,
                                offer,
                                0,
                                () -> finish(fixture));
                    });
                }));
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void expiredOfferIsCancelledAndCannotGrant(
            GameTestHelper helper) {
        openFixture(helper, "P6ExpireAck", fixture -> requestOffer(
                fixture,
                offer -> P2GameTestSupport.awaitCondition(
                        fixture.helper(),
                        P6GameTestSupport.SNAPSHOT_WAIT_TICKS,
                        () -> fixture.helper().getLevel().getServer().getTickCount()
                                        > offer.dispatch().expiresAtTick() + 1L
                                && fixture.capture().cancellationCountFor(offer) > 0,
                        "Expired P6 offer did not close and notify the mock owner",
                        fixture.cleanup(),
                        () -> {
                            P6GameTestSupport.acknowledge(fixture.owner(), offer);
                            assertNoAdditionalGrant(
                                    fixture,
                                    offer,
                                    0,
                                    () -> finish(fixture));
                        })));
    }

    private static void openFixture(
            GameTestHelper helper,
            String requestedName,
            Consumer<ReviewFixture> continuation) {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(requestedName, "requestedName");
        Objects.requireNonNull(continuation, "continuation");
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup = new P2GameTestSupport.Cleanup();
        try {
            ServerPlayer owner = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 2.2D));
            cleanup.add(() -> P2GameTestSupport.disconnectViewer(owner));
            P6GameTestSupport.OutboundPayloadCapture capture =
                    P6GameTestSupport.captureOutbound(owner, cleanup);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    owner,
                    requestedName,
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            cleanup.add(() -> P2GameTestSupport.removeBot(
                    bot, "P6 review physical-attempt GameTest completed"));
            UUID agentId = UUID.randomUUID();
            AgentBindingStatus bound = bot.manager().updateAgentBinding(
                    owner, bot.player().getUUID(), agentId, true);
            P2GameTestSupport.require(
                    bound == AgentBindingStatus.BOUND,
                    "P6 review fixture could not bind its exact agent");
            ReviewFixture fixture = new ReviewFixture(
                    helper, owner, bot, agentId, capture, cleanup);
            awaitCurrentReviewSnapshot(fixture, () -> continuation.accept(fixture));
        } catch (RuntimeException | AssertionError exception) {
            fail(helper, cleanup, exception);
        }
    }

    private static void awaitCurrentReviewSnapshot(
            ReviewFixture fixture, Runnable continuation) {
        P2GameTestSupport.awaitCondition(
                fixture.helper(),
                P6GameTestSupport.SNAPSHOT_WAIT_TICKS,
                () -> fixture.bot().manager()
                        .latestPerception(fixture.bot().name())
                        .map(snapshot -> {
                            long currentTick = fixture.helper()
                                    .getLevel()
                                    .getServer()
                                    .getTickCount();
                            return snapshot.gameTick() <= currentTick
                                    && currentTick - snapshot.gameTick() <= 1L;
                        })
                        .orElse(false),
                "P6 review fixture did not receive a completed current/previous snapshot",
                fixture.cleanup(),
                continuation);
    }

    private static void requestOffer(
            ReviewFixture fixture,
            Consumer<AiPhysicalAttemptOfferPayload> continuation) {
        int offerIndex = fixture.capture().offerCount();
        AiReviewOnlyDispatchReceipt review = fixture.bot().manager().requestAiReview(
                fixture.owner(), fixture.bot().name());
        P2GameTestSupport.require(
                review.status() == AiReviewOnlyDispatchStatus.DISPATCHED,
                "P6 review request was not dispatched: " + review.status());
        P2GameTestSupport.awaitCondition(
                fixture.helper(),
                P6GameTestSupport.PACKET_WAIT_TICKS,
                () -> fixture.capture().offerCount() > offerIndex,
                "P6 review dispatch emitted no S2C physical-attempt offer",
                fixture.cleanup(),
                () -> {
                    AiPhysicalAttemptOfferPayload offer = fixture.capture().offerAt(offerIndex);
                    P2GameTestSupport.require(
                            review.dispatch().orElseThrow().equals(
                                    offer.offer().identity().dispatchReceipt()),
                            "S2C P6 offer did not preserve its dispatch receipt");
                    P2GameTestSupport.require(
                            offer.offer().identity().matches(offer.dispatch()),
                            "S2C P6 offer did not preserve its full dispatch correlation");
                    continuation.accept(offer);
                });
    }

    private static void awaitGrantCount(
            ReviewFixture fixture,
            AiPhysicalAttemptOfferPayload offer,
            int expectedCount,
            Runnable continuation) {
        AiPhysicalAttemptIdentity identity = offer.offer().identity();
        P2GameTestSupport.awaitCondition(
                fixture.helper(),
                P6GameTestSupport.PACKET_WAIT_TICKS,
                () -> fixture.capture().grantCount(identity) >= expectedCount,
                "Exact P6 ACK did not emit its S2C start grant",
                fixture.cleanup(),
                () -> {
                    P2GameTestSupport.require(
                            fixture.capture().grantCount(identity) == expectedCount,
                            "P6 start-grant replay count was not exact");
                    continuation.run();
                });
    }

    private static void awaitCancellation(
            ReviewFixture fixture,
            AiPhysicalAttemptOfferPayload offer,
            Runnable continuation) {
        P2GameTestSupport.awaitCondition(
                fixture.helper(),
                P6GameTestSupport.PACKET_WAIT_TICKS,
                () -> fixture.capture().cancellationCountFor(offer) > 0,
                "Closing the P6 review session emitted no exact S2C cancellation",
                fixture.cleanup(),
                continuation);
    }

    private static void assertNoAdditionalGrant(
            ReviewFixture fixture,
            AiPhysicalAttemptOfferPayload offer,
            int expectedCount,
            Runnable continuation) {
        fixture.helper().runAfterDelay(
                NO_GRANT_CONFIRMATION_TICKS,
                () -> {
                    try {
                        P2GameTestSupport.require(
                                fixture.capture().grantCount(offer.offer().identity())
                                        == expectedCount,
                                "Stale P6 ACK emitted an S2C start grant");
                        continuation.run();
                    } catch (RuntimeException | AssertionError exception) {
                        fail(fixture.helper(), fixture.cleanup(), exception);
                    }
                });
    }

    private static void finish(ReviewFixture fixture) {
        fixture.cleanup().run();
        fixture.helper().succeed();
    }

    private static void fail(
            GameTestHelper helper,
            P2GameTestSupport.Cleanup cleanup,
            Throwable failure) {
        String message = failure.getMessage();
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            if (message == null || message.isBlank()) {
                message = cleanupFailure.getMessage();
            }
        }
        helper.fail(message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message);
    }

    private record ReviewFixture(
            GameTestHelper helper,
            ServerPlayer owner,
            TestBot bot,
            UUID agentId,
            P6GameTestSupport.OutboundPayloadCapture capture,
            P2GameTestSupport.Cleanup cleanup) {}
}
