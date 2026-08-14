package io.github.greytaiwolf.botplayer.network;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.client.ClientPayloadHandlers;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingPayload;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingResultPayload;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestDispatchPayload;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Common payload registration and server-side handlers.
 */
public final class BotPlayerNetwork {
    /** Purpose is part of the S2C review dispatch wire shape, so P6-R1 requires protocol v2. */
    private static final String PROTOCOL_VERSION = "2";

    private BotPlayerNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar
                .playToClient(
                        OpenCredentialScreenPayload.TYPE,
                        OpenCredentialScreenPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleOpenCredentialScreen)
                .playToServer(
                        AgentBindingPayload.TYPE,
                        AgentBindingPayload.STREAM_CODEC,
                        BotPlayerNetwork::handleAgentBinding)
                .playToServer(
                        AiProposalPayload.TYPE,
                        AiProposalPayload.STREAM_CODEC,
                        BotPlayerNetwork::handleAiProposal)
                .playToClient(
                        AiRequestDispatchPayload.TYPE,
                        AiRequestDispatchPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleAiRequestDispatch)
                .playToClient(
                        AiRequestCancellationPayload.TYPE,
                        AiRequestCancellationPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleAiRequestCancellation)
                .playToClient(
                        AgentBindingResultPayload.TYPE,
                        AgentBindingResultPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleAgentBindingResult);
    }

    private static void handleAgentBinding(
            AgentBindingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        AgentBindingStatus status = AgentBindingStatus.BOT_NOT_ACTIVE;
        MinecraftServer server = sender.getServer();
        if (server != null) {
            try {
                status = BotPlayerManagers.find(server)
                        .map(manager -> manager.updateAgentBinding(
                                sender,
                                payload.botId(),
                                payload.agentId(),
                                payload.active()))
                        .orElse(AgentBindingStatus.BOT_NOT_ACTIVE);
            } catch (RuntimeException exception) {
                status = AgentBindingStatus.INTERNAL_ERROR;
                BotPlayer.LOGGER.error(
                        "Failed to update client-agent binding for bot {}",
                        payload.botId(),
                        exception);
            }
        }

        context.reply(new AgentBindingResultPayload(
                payload.botId(), payload.agentId(), status));
    }

    /**
     * Receives an untrusted P6 model proposal and asks the lifecycle-owned server gate to review it.
     *
     * <p>The lifecycle rechecks the fixed review ticket and can produce only a numeric/enumerated
     * summary before dropping the proposal. This handler never invokes a world action or logs
     * model text, tool arguments, nonce, or prompt content.
     */
    private static void handleAiProposal(
            AiProposalPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        try {
            BotPlayerManagers.find(server).ifPresent(
                    manager -> manager.reviewAiProposal(sender, payload));
        } catch (RuntimeException exception) {
            // Never include payload/exception text, model output, arguments, nonce, or prompt.
            BotPlayer.LOGGER.warn("P6-R1 AI proposal review failed");
        }
    }
}
