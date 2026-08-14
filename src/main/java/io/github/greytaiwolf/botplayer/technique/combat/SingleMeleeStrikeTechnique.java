package io.github.greytaiwolf.botplayer.technique.combat;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueContext;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueDirective;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One pre-authorized, already-bound melee strike.
 *
 * <p>This technique deliberately performs no target selection, movement,
 * equipment change, aim control, retry, or follow-up attack.  Its only child
 * is an opaque action port that the lifecycle binds to one exact
 * {@code AttackEntity} action from limited self-defense.
 */
public final class SingleMeleeStrikeTechnique implements PlayerTechnique {
    public static final TechniqueId ID = new TechniqueId("botplayer",
            "combat/single_melee_strike");
    public static final TechniqueVersion VERSION = new TechniqueVersion(1, 0,
            0);
    public static final String OPERATION_KEY = "combat.single_melee.attack";
    public static final Set<ActionChannel> CHANNELS = Set.of(
            ActionChannel.MAIN_HAND, ActionChannel.INTERACT);
    private static final TechniqueDescriptor DESCRIPTOR =
            new TechniqueDescriptor(ID, VERSION, TechniqueRiskLevel.MODERATE,
                    40, 2, 1, 1, 0);

    @Override
    public TechniqueDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public TechniqueDirective start(TechniqueContext context,
            TechniqueParameters parameters) {
        Objects.requireNonNull(context, "context");
        TechniqueParameters required = Objects.requireNonNull(parameters,
                "parameters");
        if (!required.equals(TechniqueParameters.empty())) {
            return TechniqueDirective.fail(TechniqueFailureCode.INVALID_REQUEST,
                    "Single melee strike does not accept parameters");
        }
        return TechniqueDirective.awaitChildren("attack", List.of(
                new TechniqueChildRequest(OPERATION_KEY, CHANNELS)));
    }

    @Override
    public TechniqueDirective tick(TechniqueContext context,
            TechniqueRunView run) {
        Objects.requireNonNull(context, "context");
        TechniqueRunView required = Objects.requireNonNull(run, "run");
        if (required.submittedChildren() != 1
                || required.childTickets().size() != 1
                || !OPERATION_KEY.equals(required.childTickets().get(0)
                        .operationKey())
                || !CHANNELS.equals(required.childTickets().get(0)
                        .channels())) {
            return TechniqueDirective.fail(TechniqueFailureCode.INTERNAL_ERROR,
                    "Single melee strike child contract changed");
        }
        return TechniqueDirective.complete("Single melee strike completed");
    }

    @Override
    public TechniqueDirective verify(TechniqueContext context,
            TechniqueRunView run) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(run, "run");
        return TechniqueDirective.fail(TechniqueFailureCode.INTERNAL_ERROR,
                "Single melee strike has no verification phase");
    }
}
