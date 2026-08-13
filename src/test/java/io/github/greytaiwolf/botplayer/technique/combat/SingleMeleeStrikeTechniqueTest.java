package io.github.greytaiwolf.botplayer.technique.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
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

class SingleMeleeStrikeTechniqueTest {
    private static final UUID TECHNIQUE_RUN = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID SELF_DEFENSE_RUN = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID BOT = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    @Test
    void descriptorAndStartContractAreNarrowAndImmutable() {
        SingleMeleeStrikeTechnique technique = new SingleMeleeStrikeTechnique();

        assertEquals(SingleMeleeStrikeTechnique.ID,
                technique.descriptor().id());
        assertEquals(SingleMeleeStrikeTechnique.VERSION,
                technique.descriptor().version());
        assertEquals(TechniqueRiskLevel.MODERATE,
                technique.descriptor().risk());
        assertEquals(40, technique.descriptor().maximumTicks());
        assertEquals(1, technique.descriptor().maximumSubmittedChildren());
        assertEquals(1, technique.descriptor().maximumActiveChildren());
        assertEquals(0, technique.descriptor().maximumRecoveryAttempts());

        TechniqueDirective started = technique.start(context(),
                TechniqueParameters.empty());
        assertTrue(started instanceof TechniqueDirective.AwaitChildren);
        TechniqueDirective.AwaitChildren directive =
                (TechniqueDirective.AwaitChildren) started;
        assertEquals("attack", directive.phase());
        assertEquals(1, directive.children().size());
        TechniqueChildRequest child = directive.children().get(0);
        assertEquals(SingleMeleeStrikeTechnique.OPERATION_KEY,
                child.operationKey());
        assertEquals(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT),
                child.channels());
    }

    @Test
    void parametersAreRejectedRatherThanChangingTheBoundStrike() {
        SingleMeleeStrikeTechnique technique = new SingleMeleeStrikeTechnique();

        TechniqueDirective result = technique.start(context(),
                new TechniqueParameters(Map.of("target", "not-permitted")));
        assertTrue(result instanceof TechniqueDirective.Fail);
        TechniqueDirective.Fail directive = (TechniqueDirective.Fail) result;
        assertEquals(TechniqueFailureCode.INVALID_REQUEST,
                directive.failureCode());
    }

    @Test
    void successfulChildCompletesOnlyOnTheFollowingTechniqueTick() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = new TechniqueRuntime(dispatcher);
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(new SingleMeleeStrikeTechnique()));

        runtime.start(request(0L));
        TechniqueChildTicket ticket = dispatcher.onlySubmitted();
        runtime.finishTick(BOT, 1L, 0L);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(new TechniqueSignal(ticket.techniqueRunId(),
                        ticket.ticketId(), BOT, 1L, ticket.revision(),
                        TechniqueChildState.SUCCEEDED,
                        TechniqueFailureCode.NONE, "attack completed"), 1L));
        assertEquals(TechniqueState.RUNNING,
                runtime.inspect(BOT).orElseThrow().state());

        runtime.finishTick(BOT, 1L, 1L);
        runtime.tick(BOT, 1L, 2L);

        assertTrue(runtime.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.NONE,
                runtime.latestOutcome(BOT).orElseThrow().failureCode());
        assertEquals(1, dispatcher.submitted.size());
    }

    @Test
    void failedChildIsTerminalAndNeverSubmitsARetry() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TechniqueRuntime runtime = new TechniqueRuntime(dispatcher);
        assertEquals(TechniqueRegistrationStatus.REGISTERED,
                runtime.register(new SingleMeleeStrikeTechnique()));

        runtime.start(request(0L));
        TechniqueChildTicket ticket = dispatcher.onlySubmitted();
        runtime.finishTick(BOT, 1L, 0L);

        assertEquals(TechniqueSignalStatus.ACCEPTED,
                runtime.offerSignal(new TechniqueSignal(ticket.techniqueRunId(),
                        ticket.ticketId(), BOT, 1L, ticket.revision(),
                        TechniqueChildState.FAILED,
                        TechniqueFailureCode.ACTION_FAILED, "attack failed"),
                        1L));
        runtime.tick(BOT, 1L, 2L);

        assertTrue(runtime.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.ACTION_FAILED,
                runtime.latestOutcome(BOT).orElseThrow().failureCode());
        assertEquals(1, dispatcher.submitted.size());
    }

    private static TechniqueContext context() {
        return new TechniqueContext(TECHNIQUE_RUN, SELF_DEFENSE_RUN, BOT, 1L,
                SingleMeleeStrikeTechnique.ID,
                SingleMeleeStrikeTechnique.VERSION, 0L, 1L);
    }

    private static TechniqueStartRequest request(long currentTick) {
        return new TechniqueStartRequest(SELF_DEFENSE_RUN, BOT, 1L,
                SingleMeleeStrikeTechnique.ID,
                SingleMeleeStrikeTechnique.VERSION,
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
            // The single-strike tests only exercise terminal child outcomes.
        }

        private TechniqueChildTicket onlySubmitted() {
            assertEquals(1, submitted.size());
            return submitted.get(0);
        }
    }
}
