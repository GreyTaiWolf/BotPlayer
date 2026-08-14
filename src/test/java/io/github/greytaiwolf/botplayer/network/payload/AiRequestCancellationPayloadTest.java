package io.github.greytaiwolf.botplayer.network.payload;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiRequestCancellationPayloadTest {
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
    void streamCodecRoundTripsExactCorrelationWithoutLeakingTheNonce() {
        AiRequestCancellationPayload original = payload();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
        try {
            AiRequestCancellationPayload.STREAM_CODEC.encode(buffer, original);
            AiRequestCancellationPayload decoded =
                    AiRequestCancellationPayload.STREAM_CODEC.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertFalse(decoded.toString().contains(NONCE.toString()));
            Assertions.assertFalse(decoded.toString().contains(OWNER_ID.toString()));
        } finally {
            buffer.release();
        }
    }

    private static AiRequestCancellationPayload payload() {
        return new AiRequestCancellationPayload(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                REQUEST_ID,
                NONCE,
                3L);
    }
}
