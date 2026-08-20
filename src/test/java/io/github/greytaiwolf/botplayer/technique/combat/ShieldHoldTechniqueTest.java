package io.github.greytaiwolf.botplayer.technique.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueContext;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueDirective;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRegistrationStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRuntime;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignal;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignalStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShieldHoldTechniqueTest {
    private static final UUID SKILL = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID BOT = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");

    @Test
    void descriptorAndStartContractHaveOneFixedOffHandChild() {
        ShieldHoldTechnique technique = new ShieldHoldTechnique();

        assertEquals(ShieldHoldTechnique.ID, technique.descriptor().id());
        assertEquals(ShieldHoldTechnique.VERSION,
                technique.descriptor().version());
        assertEquals(TechniqueRiskLevel.MODERATE,
                technique.descriptor().risk());
        assertEquals(40, technique.descriptor().maximumTicks());
        assertEquals(1, technique.descriptor().maximumSubmittedChildren());
        assertEquals(1, technique.descriptor().maximumActiveChildren());
        assertEquals(0, technique.descriptor().maximumRecoveryAttempts());

        TechniqueDirective directive = technique.start(newContext(),
                TechniqueParameters.empty());
        assertTrue(directive instanceof TechniqueDirective.AwaitChildren);
        var children = ((TechniqueDirective.AwaitChildren) directive).children();
        assertEquals(1, children.size());
        TechniqueChildRequest child = children.getFirst();
        assertEquals(ShieldHoldTechnique.OPERATION_KEY, child.operationKey());
        assertEquals(Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT),
                child.channels());
    }

    @Test
    void parametersAreRejectedRatherThanChangingTheFixedHold() {
        ShieldHoldTechnique technique = new ShieldHoldTechnique();

        TechniqueDirective result = technique.start(newContext(),
                new TechniqueParameters(Map.of("hold_ticks", "1")));
        assertTrue(result instanceof TechniqueDirective.Fail);
        assertEquals(TechniqueFailureCode.INVALID_REQUEST,
                ((TechniqueDirective.Fail) result).failureCode());
    }

    @Test
    void successfulChildCompletesOnlyOnTheFollowingTechniqueTick() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = new TechniqueRuntime(dispatcher);
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(new ShieldHoldTechnique()));

        runtime.start(request(0L));
        TechniqueChildTicket ticket = dispatcher.onlySubmitted();
        runtime.finishTick(BOT, 1L, 0L);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(new TechniqueSignal(ticket.techniqueRunId(),
                        ticket.ticketId(), BOT, 1L, ticket.revision(),
                        TechniqueChildState.SUCCEEDED,
                        TechniqueFailureCode.NONE, "shield released"), 1L));
        assertEquals(TechniqueState.RUNNING,
                runtime.inspect(BOT).orElseThrow().state());

        runtime.finishTick(BOT, 1L, 1L);
        runtime.tick(BOT, 1L, 2L);

        assertTrue(runtime.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.NONE,
                runtime.latestOutcome(BOT).orElseThrow().failureCode());
        assertEquals(1, dispatcher.submitted.size());
    }

    private static TechniqueContext newContext() {
        return new TechniqueContext(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333"), SKILL, BOT,
                        1L, ShieldHoldTechnique.ID, ShieldHoldTechnique.VERSION,
                        0L, 1L);
    }

    private static TechniqueStartRequest request(long currentTick) {
        return new TechniqueStartRequest(SKILL, BOT, 1L,
                ShieldHoldTechnique.ID, ShieldHoldTechnique.VERSION,
                TechniqueParameters.empty(), currentTick);
    }

    private static final class RecordingDispatcher
            implements TechniqueChildDispatcher {
        private final List<TechniqueChildTicket> submitted = new ArrayList<>();

        @Override
        public Submission submit(TechniqueChildTicket ticket) {
            submitted.add(ticket);
            return Submission.accepted("recorded");
        }

        @Override
        public void cancel(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            // This contract test only supplies a successful terminal child.
        }

        private TechniqueChildTicket onlySubmitted() {
            assertEquals(1, submitted.size());
            return submitted.getFirst();
        }
    }
}
