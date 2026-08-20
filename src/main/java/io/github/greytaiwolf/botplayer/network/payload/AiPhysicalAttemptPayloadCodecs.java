package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Shared fixed-width codec for the redacted identity of a server-owned physical attempt.
 *
 * <p>The wire form intentionally contains every immutable correlation component required to
 * reject a replay or a drifted ACK. It contains no request content, credential, endpoint, model
 * response, token amount, usage, or factual Provider outcome. The containing S2C offer separately
 * serializes the already bounded request dispatch through {@link AiRequestDispatchPayload}.
 */
final class AiPhysicalAttemptPayloadCodecs {
    private AiPhysicalAttemptPayloadCodecs() {}

    static void writeIdentity(
            RegistryFriendlyByteBuf buffer, AiPhysicalAttemptIdentity identity) {
        buffer.writeUUID(identity.serverInstanceId());
        buffer.writeUUID(identity.ownerId());
        AiRequestDispatchReceipt receipt = identity.dispatchReceipt();
        buffer.writeUUID(receipt.botId());
        buffer.writeUUID(receipt.agentId());
        buffer.writeLong(receipt.generation());
        buffer.writeUUID(receipt.requestId());
        buffer.writeLong(receipt.revision());
        buffer.writeLong(receipt.expiresAtTick());
        buffer.writeEnum(receipt.purpose());
        buffer.writeUUID(identity.attemptId());
        buffer.writeUUID(identity.nonce());
        buffer.writeLong(identity.clientNotAfterEpochMillis());
        buffer.writeLong(identity.physicalStartNotAfterEpochMillis());
    }

    static AiPhysicalAttemptIdentity readIdentity(RegistryFriendlyByteBuf buffer) {
        return new AiPhysicalAttemptIdentity(
                buffer.readUUID(),
                buffer.readUUID(),
                new AiRequestDispatchReceipt(
                        buffer.readUUID(),
                        buffer.readUUID(),
                        buffer.readLong(),
                        buffer.readUUID(),
                        buffer.readLong(),
                        buffer.readLong(),
                        buffer.readEnum(AiRequestPurpose.class)),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readLong(),
                buffer.readLong());
    }
}
