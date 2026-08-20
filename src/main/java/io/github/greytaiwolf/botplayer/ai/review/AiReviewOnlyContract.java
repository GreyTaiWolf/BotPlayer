package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiConversationHistory;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.ContextAssembler;
import io.github.greytaiwolf.botplayer.ai.ContextAssembly;
import io.github.greytaiwolf.botplayer.ai.ContextAssemblyInput;
import io.github.greytaiwolf.botplayer.ai.ContextBudget;
import io.github.greytaiwolf.botplayer.ai.ContextPolicy;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiReviewOnlyProposalShape;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fixed P6-R1 request, context, tool and response contract.
 *
 * <p>This is deliberately not a chat template. The only dynamic text is the seven-line numeric /
 * resource-ID projection created by {@link AiReviewOnlySnapshotProjection}; history and memory are
 * always empty. A successful tool call is only a review acknowledgement and is dropped after
 * server-side static review.
 */
public final class AiReviewOnlyContract {
    public static final AiRequestPurpose PURPOSE = AiRequestPurpose.REVIEW_ONLY_V1;
    public static final String PROVIDER_ID = "deepseek";
    public static final String MODEL = "deepseek-chat";
    public static final String TOOL_NAME = AiReviewOnlyProposalShape.TOOL_NAME;
    public static final int REQUEST_TTL_TICKS = 40;
    public static final long REQUEST_TIMEOUT_MILLIS = 1_500L;
    /** Conservative server-owned input reservation for the fixed R1 context shape. */
    public static final int MAXIMUM_INPUT_TOKENS = 2_048;
    public static final int MAXIMUM_OUTPUT_TOKENS = 256;

    private static final UUID TEMPLATE_REQUEST_ID = new UUID(0L, 7L);
    private static final ContextBudget CONTEXT_BUDGET = new ContextBudget(
            MAXIMUM_INPUT_TOKENS, 3, 4_096);
    private static final ContextPolicy CONTEXT_POLICY = ContextPolicy.fixedRules(List.of(
            "You are reviewing one fixed BotPlayer observation snapshot. "
                    + "Do not infer hidden world state, issue instructions, or claim execution. "
                    + "Call exactly botplayer_review_snapshot with an empty object. "
                    + "The call is an acknowledgement only and never authorizes a game action."));
    private static final AiRequestOptions REQUEST_OPTIONS = new AiRequestOptions(
            MAXIMUM_OUTPUT_TOKENS,
            REQUEST_TIMEOUT_MILLIS,
            AiResponseFormat.TEXT,
            false,
            true,
            Optional.empty());
    private static final ToolDefinition TOOL_DEFINITION = new ToolDefinition(
            TOOL_NAME, ToolRiskLevel.SAFE, Map.of());
    private static final ToolFirewallPolicy FIREWALL_POLICY = new ToolFirewallPolicy(
            Map.of(TOOL_NAME, TOOL_DEFINITION),
            Set.of(TOOL_NAME),
            ToolRiskLevel.SAFE,
            1);

    private AiReviewOnlyContract() {}

    public static AiRequest requestTemplate(AiReviewOnlySnapshotProjection projection) {
        AiReviewOnlySnapshotProjection checked = Objects.requireNonNull(projection, "projection");
        ContextAssembly context = assemble(checked.canonicalUserMessage());
        return new AiRequest(
                TEMPLATE_REQUEST_ID,
                MODEL,
                context.messages(),
                REQUEST_OPTIONS,
                Optional.empty());
    }

    public static ToolDefinition toolDefinition() {
        return TOOL_DEFINITION;
    }

    public static ToolFirewallPolicy firewallPolicy() {
        return FIREWALL_POLICY;
    }

    /**
     * Client-side fail-closed validation before a local credential may create a Provider. It
     * recreates the fixed assembler output instead of trusting the S2C message list.
     */
    public static boolean isCanonicalDispatch(AiClientRequestDispatch dispatch) {
        try {
            AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
            if (checked.purpose() != PURPOSE
                    || !PROVIDER_ID.equals(checked.providerId())
                    || !MODEL.equals(checked.model())
                    || checked.expiresAtTick() - checked.issuedAtTick()
                            != REQUEST_TTL_TICKS
                    || checked.expiresAtEpochMillis() - checked.issuedAtEpochMillis()
                            != REQUEST_TTL_TICKS * 50L
                    || !REQUEST_OPTIONS.equals(checked.options())
                    || checked.responseSchemaJson().isPresent()
                    || checked.messages().isEmpty()) {
                return false;
            }
            String userMessage = checked.messages().getLast().content();
            if (!AiReviewOnlySnapshotProjection.isCanonicalUserMessage(userMessage)) {
                return false;
            }
            return assemble(userMessage).messages().equals(checked.messages());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Validates the fixed reply shape before the client serializes a C2S proposal. */
    public static boolean isCanonicalToolResponse(
            AiClientRequestDispatch dispatch, AiResponse response) {
        try {
            AiClientRequestDispatch checkedDispatch = Objects.requireNonNull(dispatch, "dispatch");
            AiResponse checkedResponse = Objects.requireNonNull(response, "response");
            return isCanonicalDispatch(checkedDispatch)
                    && checkedDispatch.requestId().equals(checkedResponse.requestId())
                    && PROVIDER_ID.equals(checkedResponse.providerId())
                    && MODEL.equals(checkedResponse.model())
                    && checkedResponse.finishReason()
                            == io.github.greytaiwolf.botplayer.ai.AiFinishReason.TOOL_CALLS
                    && checkedResponse.outputText().isEmpty()
                    && checkedResponse.reasoningText().isEmpty()
                    && checkedResponse.structuredOutputJson().isEmpty()
                    && isCanonicalToolCalls(checkedResponse.toolCalls());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Rechecks decoded server-only calls before an accepted plan is summarized and dropped. */
    public static boolean isCanonicalProposal(ProposedSkillPlan proposal) {
        try {
            ProposedSkillPlan checked = Objects.requireNonNull(proposal, "proposal");
            return checked.toolCalls().size() == 1
                    && TOOL_NAME.equals(checked.toolCalls().getFirst().toolName())
                    && checked.toolCalls().getFirst().arguments().isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Defense-in-depth recheck of the untrusted wire shape after the gate has consumed its exact
     * correlation. The transport gate performs the same check before generic decoding, but this
     * prevents a future lifecycle refactor from turning a forged payload into a review summary.
     */
    public static boolean isCanonicalProposalPayload(AiProposalPayload payload) {
        try {
            return AiReviewOnlyProposalShape.matches(
                    Objects.requireNonNull(payload, "payload"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isCanonicalToolCalls(List<AiRawToolCall> toolCalls) {
        if (toolCalls.size() != 1) {
            return false;
        }
        AiRawToolCall call = toolCalls.getFirst();
        return TOOL_NAME.equals(call.name()) && "{}".equals(call.argumentsJson());
    }

    private static ContextAssembly assemble(String userMessage) {
        return new ContextAssembler(CONTEXT_BUDGET).assemble(new ContextAssemblyInput(
                CONTEXT_POLICY,
                AiConversationHistory.empty(),
                new AiMessage(AiMessageRole.USER, userMessage),
                Optional.empty()));
    }
}
