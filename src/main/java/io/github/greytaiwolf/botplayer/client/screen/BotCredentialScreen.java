package io.github.greytaiwolf.botplayer.client.screen;

import io.github.greytaiwolf.botplayer.client.ClientPayloadHandlers;
import io.github.greytaiwolf.botplayer.client.credential.BotCredentialBinding;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.client.credential.CredentialStoreException;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingPayload;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingResultPayload;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Pure client screen for choosing a local profile and entering a provider key.
 *
 * <p>The key edit box is never populated from storage. Network messages contain only bot and agent
 * identifiers.
 */
public final class BotCredentialScreen extends Screen {
    private static final int FORM_WIDTH = 300;
    private static final int FIELD_HEIGHT = 20;

    private final OpenCredentialScreenPayload openPayload;
    private final UUID ownerUuid;
    private final ClientCredentialStore store;
    private Optional<BotCredentialBinding> localBinding;

    private EditBox profileIdBox;
    private EditBox keyBox;
    private Button unbindButton;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xA0A0A0;

    public BotCredentialScreen(
            OpenCredentialScreenPayload openPayload,
            UUID ownerUuid,
            ClientCredentialStore store) {
        super(Component.translatable("screen.botplayer.credentials.title"));
        this.openPayload = Objects.requireNonNull(openPayload, "openPayload");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.store = Objects.requireNonNull(store, "store");
        localBinding = store.findBinding(
                openPayload.serverInstanceId(), ownerUuid, openPayload.botId());
    }

    public UUID botId() {
        return openPayload.botId();
    }

    @Override
    protected void init() {
        int left = (width - FORM_WIDTH) / 2;
        int top = Math.max(44, height / 2 - 92);

        profileIdBox = new EditBox(
                font,
                left,
                top + 28,
                FORM_WIDTH,
                FIELD_HEIGHT,
                Component.translatable("screen.botplayer.credentials.profile_id"));
        profileIdBox.setMaxLength(64);
        profileIdBox.setFilter(value ->
                value.isEmpty() || ClientCredentialStore.isValidProfileId(value));
        profileIdBox.setValue(localBinding
                .map(BotCredentialBinding::profileId)
                .orElse(ClientCredentialStore.DEFAULT_PROFILE_ID));
        addRenderableWidget(profileIdBox);

        keyBox = new EditBox(
                font,
                left,
                top + 70,
                FORM_WIDTH,
                FIELD_HEIGHT,
                Component.translatable("screen.botplayer.credentials.key"));
        keyBox.setMaxLength(512);
        keyBox.setFilter(BotCredentialScreen::isPotentialKeyInput);
        keyBox.setFormatter((visibleText, characterOffset) ->
                FormattedCharSequence.forward(
                        "*".repeat(visibleText.length()), Style.EMPTY));
        keyBox.setValue("");
        addRenderableWidget(keyBox);

        int buttonTop = top + 116;
        addRenderableWidget(Button.builder(
                        Component.translatable(
                                        "screen.botplayer.credentials.button.save_and_bind"),
                                button -> saveAndBind())
                .bounds(left, buttonTop, 96, 20)
                .build());
        unbindButton = addRenderableWidget(Button.builder(
                        Component.translatable(
                                        "screen.botplayer.credentials.button.unbind"),
                                button -> unbind())
                .bounds(left + 102, buttonTop, 96, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable(
                                        "screen.botplayer.credentials.button.close"),
                                button -> onClose())
                .bounds(left + 204, buttonTop, 96, 20)
                .build());
        refreshUnbindButton();
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        renderTransparentBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (width - FORM_WIDTH) / 2;
        int top = Math.max(44, height / 2 - 92);
        graphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.botplayer.credentials.bot_title",
                        openPayload.botName()),
                width / 2,
                top - 22,
                0xFFFFFF);
        graphics.drawString(
                font,
                Component.translatable("screen.botplayer.credentials.profile_id"),
                left,
                top + 16,
                0xD0D0D0);
        graphics.drawString(
                font,
                Component.translatable("screen.botplayer.credentials.key"),
                left,
                top + 58,
                0xD0D0D0);

        Component savedState = store.findProfile(profileIdBox.getValue()).isPresent()
                ? Component.translatable(
                        "screen.botplayer.credentials.profile.saved")
                : Component.translatable(
                        "screen.botplayer.credentials.profile.missing");
        graphics.drawString(
                font,
                savedState,
                left,
                top + 94,
                0xB8B8B8);
        graphics.drawCenteredString(
                font, statusMessage, width / 2, top + 143, statusColor);
        graphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.botplayer.credentials.warning.server_isolation"),
                width / 2,
                top + 165,
                0xE0C060);
        graphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.botplayer.credentials.warning.plaintext"),
                width / 2,
                top + 178,
                0xE07070);
    }

    @Override
    public void onClose() {
        if (keyBox != null) {
            keyBox.setValue("");
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void handleBindingResult(AgentBindingResultPayload payload) {
        if (!payload.botId().equals(openPayload.botId())) {
            return;
        }
        statusMessage = ClientPayloadHandlers.bindingResultMessage(payload.status());
        statusColor = ClientPayloadHandlers.isSuccessful(payload.status())
                ? 0x60E080
                : 0xFF6060;
    }

    private void saveAndBind() {
        String profileId = profileIdBox.getValue();
        String enteredKey = keyBox.getValue();
        try {
            ClientCredentialStore.BindResult result = store.bind(
                    openPayload.serverInstanceId(),
                    ownerUuid,
                    openPayload.botId(),
                    profileId,
                    enteredKey,
                    openPayload.activeAgentId());
            localBinding = Optional.of(result.binding());
            keyBox.setValue("");
            refreshUnbindButton();

            PacketDistributor.sendToServer(new AgentBindingPayload(
                    openPayload.botId(), result.binding().agentId(), true));
            statusMessage = Component.translatable(
                            result.profileReplaced()
                                    ? "screen.botplayer.credentials.status.waiting_bind_saved"
                                    : "screen.botplayer.credentials.status.waiting_bind_existing")
                    .withStyle(ChatFormatting.YELLOW);
            statusColor = 0xE0C060;
        } catch (CredentialStoreException exception) {
            keyBox.setValue("");
            statusMessage = Component.translatable(
                            exception.reason().translationKey())
                    .withStyle(ChatFormatting.RED);
            statusColor = 0xFF6060;
        }
    }

    private void unbind() {
        try {
            Optional<BotCredentialBinding> removed = store.unbind(
                    openPayload.serverInstanceId(),
                    ownerUuid,
                    openPayload.botId());
            localBinding = Optional.empty();
            refreshUnbindButton();

            Optional<UUID> agentId =
                    removed.map(BotCredentialBinding::agentId)
                            .or(() -> openPayload.activeAgentId());
            agentId.ifPresent(id -> PacketDistributor.sendToServer(
                    new AgentBindingPayload(openPayload.botId(), id, false)));
            statusMessage = agentId.isPresent()
                    ? Component.translatable(
                            "screen.botplayer.credentials.status.waiting_unbind")
                    : Component.translatable(
                            "screen.botplayer.credentials.status.no_local_binding");
            statusColor = 0xE0C060;
        } catch (CredentialStoreException exception) {
            statusMessage = Component.translatable(
                            exception.reason().translationKey())
                    .withStyle(ChatFormatting.RED);
            statusColor = 0xFF6060;
        }
    }

    private void refreshUnbindButton() {
        if (unbindButton != null) {
            unbindButton.active =
                    localBinding.isPresent() || openPayload.activeAgentId().isPresent();
        }
    }

    private static boolean isPotentialKeyInput(String value) {
        return value.chars().noneMatch(character ->
                Character.isISOControl(character)
                        || Character.isWhitespace(character));
    }
}
