package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.plan.AiPlanBinding;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmission;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmissionStatus;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntime;
import java.util.Objects;

/**
 * P5-side runtime handoff for an already issued opaque AI token.
 *
 * <p>The caller is the P5 adapter, not P6.  It derives bot identity and body generation solely from
 * the server binding and deliberately maps detailed runtime rejection data to the small non-secret
 * AI port vocabulary.
 */
public final class SkillRuntimeAiPlanSubmitter implements P5AiPlanSubmitter {
    private final SkillRuntime runtime;

    public SkillRuntimeAiPlanSubmitter(SkillRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public AiPlanSubmission submit(
            AiPlanBinding binding, SkillPlan plan, long currentTick) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(plan, "plan");
        SkillRunSubmission submission = runtime.submit(new SkillRunRequest(
                binding.botId(), binding.botGeneration(), plan, currentTick));
        return switch (submission.status()) {
            case ACCEPTED -> AiPlanSubmission.submitted(
                    submission.runId().orElseThrow());
            case BOT_BUSY, RUNTIME_CAPACITY_EXCEEDED ->
                    AiPlanSubmission.rejected(AiPlanSubmissionStatus.CAPACITY_EXHAUSTED);
            case RUNTIME_CLOSED ->
                    AiPlanSubmission.rejected(AiPlanSubmissionStatus.PORT_NOT_READY);
            default -> AiPlanSubmission.rejected(
                    AiPlanSubmissionStatus.EXECUTION_REJECTED);
        };
    }
}
