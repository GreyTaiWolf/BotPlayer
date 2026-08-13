package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.network.payload.AgentBindingResultPayload;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestDispatchPayload;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Dedicated-server-safe clientbound payload entry points.
 *
 * <p>This class deliberately contains no {@code net.minecraft.client} references because common
 * payload registration loads it on a physical dedicated server. The physical-client mod entrypoint
 * installs the actual sink.
 */
public final class ClientPayloadHandlers {
    private static final ClientPayloadSink NO_OP = new ClientPayloadSink() {
        @Override
        public void openCredentialScreen(OpenCredentialScreenPayload payload) {}

        @Override
        public void showBindingResult(AgentBindingResultPayload payload) {}

        @Override
        public void handleAiRequestDispatch(AiRequestDispatchPayload payload) {}

        @Override
        public void handleAiRequestCancellation(AiRequestCancellationPayload payload) {}
    };

    private static volatile ClientPayloadSink sink = NO_OP;

    private ClientPayloadHandlers() {}

    public static void install(ClientPayloadSink clientSink) {
        sink = Objects.requireNonNull(clientSink, "clientSink");
    }

    public static void handleOpenCredentialScreen(
            OpenCredentialScreenPayload payload, IPayloadContext context) {
        sink.openCredentialScreen(payload);
    }

    public static void handleAgentBindingResult(
            AgentBindingResultPayload payload, IPayloadContext context) {
        sink.showBindingResult(payload);
    }

    /**
     * Delivers a server-generated, secret-free request only to the physical-client session
     * controller. The common facade never invokes a Provider or touches local credentials.
     */
    public static void handleAiRequestDispatch(
            AiRequestDispatchPayload payload, IPayloadContext context) {
        sink.handleAiRequestDispatch(payload);
    }

    /** Delivers a correlation-only server cancellation to the physical client session controller. */
    public static void handleAiRequestCancellation(
            AiRequestCancellationPayload payload, IPayloadContext context) {
        sink.handleAiRequestCancellation(payload);
    }

    public static Component bindingResultMessage(AgentBindingStatus status) {
        return switch (status) {
            case BOUND -> success("message.botplayer.agent_binding.bound");
            case REPLACED -> success("message.botplayer.agent_binding.replaced");
            case UNBOUND -> success("message.botplayer.agent_binding.unbound");
            case ALREADY_UNBOUND ->
                    neutral("message.botplayer.agent_binding.already_unbound");
            case STALE_AGENT_ID ->
                    failure("message.botplayer.agent_binding.stale_agent_id");
            case AGENT_ID_IN_USE ->
                    failure("message.botplayer.agent_binding.agent_id_in_use");
            case BOT_NOT_ACTIVE ->
                    failure("message.botplayer.agent_binding.bot_not_active");
            case NOT_OWNER -> failure("message.botplayer.agent_binding.not_owner");
            case INTERNAL_ERROR ->
                    failure("message.botplayer.agent_binding.internal_error");
        };
    }

    public static boolean isSuccessful(AgentBindingStatus status) {
        return switch (status) {
            case BOUND, REPLACED, UNBOUND, ALREADY_UNBOUND -> true;
            default -> false;
        };
    }

    private static Component success(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.GREEN);
    }

    private static Component neutral(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.YELLOW);
    }

    private static Component failure(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.RED);
    }

    public interface ClientPayloadSink {
        void openCredentialScreen(OpenCredentialScreenPayload payload);

        void showBindingResult(AgentBindingResultPayload payload);

        void handleAiRequestDispatch(AiRequestDispatchPayload payload);

        void handleAiRequestCancellation(AiRequestCancellationPayload payload);
    }
}
