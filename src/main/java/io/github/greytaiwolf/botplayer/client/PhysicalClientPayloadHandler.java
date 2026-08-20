package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.client.screen.BotCredentialScreen;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingResultPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptOfferPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptStartGrantPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * The physical-client implementation behind the dedicated-server-safe payload facade.
 */
final class PhysicalClientPayloadHandler
        implements ClientPayloadHandlers.ClientPayloadSink {
    @Override
    public void openCredentialScreen(OpenCredentialScreenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }

            BotPlayerClient.credentialStore().ifPresentOrElse(
                    store -> minecraft.setScreen(new BotCredentialScreen(
                            payload, player.getUUID(), store)),
                    () -> player.displayClientMessage(
                            Component.translatable(
                                            "screen.botplayer.credentials.error.store_unavailable")
                                    .withStyle(ChatFormatting.RED),
                            false));
        });
    }

    @Override
    public void showBindingResult(AgentBindingResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof BotCredentialScreen screen
                    && screen.botId().equals(payload.botId())) {
                screen.handleBindingResult(payload);
                return;
            }

            LocalPlayer player = minecraft.player;
            if (player != null) {
                player.displayClientMessage(
                        ClientPayloadHandlers.bindingResultMessage(payload.status()),
                        false);
            }
        });
    }

    @Override
    public void handleAiPhysicalAttemptOffer(AiPhysicalAttemptOfferPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        long ingressConnectionEpoch = BotPlayerClient.currentAiConnectionEpoch();
        minecraft.execute(() -> {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            BotPlayerClient.handleAiPhysicalAttemptOffer(
                    payload, player.getUUID(), ingressConnectionEpoch);
        });
    }

    @Override
    public void handleAiPhysicalAttemptStartGrant(
            AiPhysicalAttemptStartGrantPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        long ingressConnectionEpoch = BotPlayerClient.currentAiConnectionEpoch();
        minecraft.execute(() -> {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            BotPlayerClient.handleAiPhysicalAttemptStartGrant(
                    payload, player.getUUID(), ingressConnectionEpoch);
        });
    }

    @Override
    public void handleAiRequestCancellation(AiRequestCancellationPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        long ingressConnectionEpoch = BotPlayerClient.currentAiConnectionEpoch();
        minecraft.execute(() -> {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            BotPlayerClient.handleAiRequestCancellation(
                    payload, player.getUUID(), ingressConnectionEpoch);
        });
    }
}
