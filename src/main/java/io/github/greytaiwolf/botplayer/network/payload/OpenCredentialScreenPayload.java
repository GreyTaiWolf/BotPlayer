package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Opens the local credential screen after the server has verified exact bot ownership.
 *
 * <p>This payload contains identity metadata only. It must never contain provider credentials or
 * credential-derived data.
 */
public record OpenCredentialScreenPayload(
        UUID serverInstanceId,
        UUID botId,
        String botName,
        Optional<UUID> activeAgentId)
        implements CustomPacketPayload {
    private static final int MAX_BOT_NAME_LENGTH = 16;

    public static final Type<OpenCredentialScreenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID, "open_credential_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCredentialScreenPayload>
            STREAM_CODEC = StreamCodec.of(
                    OpenCredentialScreenPayload::encode,
                    OpenCredentialScreenPayload::decode);

    public OpenCredentialScreenPayload {
        Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(botName, "botName");
        activeAgentId = Objects.requireNonNull(activeAgentId, "activeAgentId");
        if (botName.isEmpty() || botName.length() > MAX_BOT_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid bot name length");
        }
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, OpenCredentialScreenPayload payload) {
        buffer.writeUUID(payload.serverInstanceId);
        buffer.writeUUID(payload.botId);
        buffer.writeUtf(payload.botName, MAX_BOT_NAME_LENGTH);
        buffer.writeBoolean(payload.activeAgentId.isPresent());
        payload.activeAgentId.ifPresent(buffer::writeUUID);
    }

    private static OpenCredentialScreenPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID serverInstanceId = buffer.readUUID();
        UUID botId = buffer.readUUID();
        String botName = buffer.readUtf(MAX_BOT_NAME_LENGTH);
        Optional<UUID> activeAgentId =
                buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty();
        return new OpenCredentialScreenPayload(
                serverInstanceId, botId, botName, activeAgentId);
    }

    @Override
    public Type<OpenCredentialScreenPayload> type() {
        return TYPE;
    }
}
