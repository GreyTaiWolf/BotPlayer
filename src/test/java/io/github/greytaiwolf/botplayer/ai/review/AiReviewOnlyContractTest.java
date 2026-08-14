package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiTokenUsage;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolValue;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiReviewOnlyContractTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");
    private static final UUID NONCE = UUID.fromString(
            "00000000-0000-0000-0000-000000000401");

    @Test
    void canonicalDispatchIsReassembledFromOnlyTheFixedSnapshotProjection() {
        AiReviewOnlySnapshotProjection projection = projection();
        AiRequest template = AiReviewOnlyContract.requestTemplate(projection);
        AiClientRequestDispatch dispatch = dispatch(template.messages(),
                AiReviewOnlyContract.PURPOSE);

        Assertions.assertEquals(AiReviewOnlyContract.MODEL, template.model());
        Assertions.assertEquals(AiReviewOnlyContract.MAXIMUM_OUTPUT_TOKENS,
                template.options().maximumOutputTokens());
        Assertions.assertFalse(template.options().reasoningAllowed());
        Assertions.assertTrue(template.options().toolCallsAllowed());
        Assertions.assertEquals(3, template.messages().size(),
                "two fixed SYSTEM rules plus the only dynamic USER projection");
        Assertions.assertEquals(projection.canonicalUserMessage(),
                template.messages().getLast().content());
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalDispatch(dispatch));
        Assertions.assertFalse(dispatch.toString().contains("snapshot_id="));

        List<AiMessage> mutatedMessages = new java.util.ArrayList<>(template.messages());
        mutatedMessages.set(mutatedMessages.size() - 1,
                new AiMessage(AiMessageRole.USER,
                        projection.canonicalUserMessage() + "\ncoordinate=0,64,0"));
        Assertions.assertFalse(AiReviewOnlyContract.isCanonicalDispatch(
                dispatch(mutatedMessages, AiReviewOnlyContract.PURPOSE)));
        Assertions.assertFalse(AiReviewOnlyContract.isCanonicalDispatch(
                dispatch(template.messages(), AiRequestPurpose.UNSPECIFIED_V1)));
    }

    @Test
    void onlyTheEmptyReviewToolCanReachTheReviewProposalBoundary() {
        AiClientRequestDispatch dispatch = dispatch(
                AiReviewOnlyContract.requestTemplate(projection()).messages(),
                AiReviewOnlyContract.PURPOSE);
        AiResponse response = new AiResponse(
                REQUEST_ID,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall("call_1", AiReviewOnlyContract.TOOL_NAME, "{}")),
                AiTokenUsage.empty());
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalToolResponse(dispatch, response));
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalProposal(new ProposedSkillPlan(
                REQUEST_ID,
                List.of(new ProposedToolCall(
                        "call_1", AiReviewOnlyContract.TOOL_NAME, Map.of())))));

        AiResponse prose = new AiResponse(
                REQUEST_ID,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                AiFinishReason.TOOL_CALLS,
                "model prose must not cross the boundary",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall("call_1", AiReviewOnlyContract.TOOL_NAME, "{}")),
                AiTokenUsage.empty());
        Assertions.assertFalse(AiReviewOnlyContract.isCanonicalToolResponse(dispatch, prose));
        Assertions.assertFalse(AiReviewOnlyContract.isCanonicalProposal(new ProposedSkillPlan(
                REQUEST_ID,
                List.of(new ProposedToolCall(
                        "call_1", AiReviewOnlyContract.TOOL_NAME,
                        Map.of("unexpected", new ToolValue.IntegerValue(1L)))))));

        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalProposalPayload(
                new AiProposalPayload(
                        BOT_ID,
                        AGENT_ID,
                        2L,
                        REQUEST_ID,
                        NONCE,
                        7L,
                        "",
                        List.of(new AiProposalToolCallPayload(
                                "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")))));
        Assertions.assertFalse(AiReviewOnlyContract.isCanonicalProposalPayload(
                new AiProposalPayload(
                        BOT_ID,
                        AGENT_ID,
                        2L,
                        REQUEST_ID,
                        NONCE,
                        7L,
                        "forged model prose",
                        List.of(new AiProposalToolCallPayload(
                                "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")))));
    }

    private static AiReviewOnlySnapshotProjection projection() {
        return new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                2);
    }

    private static AiClientRequestDispatch dispatch(
            List<AiMessage> messages, AiRequestPurpose purpose) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                REQUEST_ID,
                NONCE,
                7L,
                purpose,
                100L,
                140L,
                1_000L,
                3_000L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                messages,
                AiReviewOnlyContract.requestTemplate(projection()).options(),
                Optional.empty());
    }
}
