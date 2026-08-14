package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import java.util.List;
import java.util.Objects;

/** Bounded next-step result returned by a server-registered technique. */
public sealed interface TechniqueDirective permits TechniqueDirective.Continue,
        TechniqueDirective.AwaitChildren, TechniqueDirective.Verify,
        TechniqueDirective.Recover, TechniqueDirective.Complete,
        TechniqueDirective.Fail {
    static Continue continueRunning(String phase) {
        return new Continue(phase);
    }

    static AwaitChildren awaitChildren(String phase,
            List<TechniqueChildRequest> children) {
        return new AwaitChildren(phase, children);
    }

    static Verify verify(String phase) {
        return new Verify(phase);
    }

    static Recover recover(String phase) {
        return new Recover(phase);
    }

    static Complete complete(String summary) {
        return new Complete(summary);
    }

    static Fail fail(TechniqueFailureCode code, String summary) {
        return new Fail(code, summary);
    }

    record Continue(String phase) implements TechniqueDirective {
        public Continue {
            phase = TechniqueText.phase(phase);
        }
    }

    record AwaitChildren(String phase,
            List<TechniqueChildRequest> children) implements TechniqueDirective {
        public AwaitChildren {
            phase = TechniqueText.phase(phase);
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (children.isEmpty()
                    || children.size() > 3) {
                throw new IllegalArgumentException(
                        "technique directive requires 1-3 child requests");
            }
        }
    }

    record Verify(String phase) implements TechniqueDirective {
        public Verify {
            phase = TechniqueText.phase(phase);
        }
    }

    record Recover(String phase) implements TechniqueDirective {
        public Recover {
            phase = TechniqueText.phase(phase);
        }
    }

    record Complete(String summary) implements TechniqueDirective {
        public Complete {
            summary = TechniqueText.summary(summary);
        }
    }

    record Fail(TechniqueFailureCode failureCode,
            String summary) implements TechniqueDirective {
        public Fail {
            Objects.requireNonNull(failureCode, "failureCode");
            if (failureCode == TechniqueFailureCode.NONE
                    || failureCode == TechniqueFailureCode.CANCELLED
                    || failureCode == TechniqueFailureCode.PREEMPTED) {
                throw new IllegalArgumentException(
                        "technique failure directive requires an operational failure code");
            }
            summary = TechniqueText.summary(summary);
        }
    }
}
