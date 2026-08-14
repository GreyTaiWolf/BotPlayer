package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 服务端在读取 roster 与当前 transient binding 后传给 AI 提案 gate 的动态权限事实。
 *
 * <p>这些字段绝不来自 C2S payload。{@code senderIsPersistentOwner} 必须由持久 roster 的
 * exact owner 复核得出，而不是由客户端本地 binding 推断。
 */
public record AiProposalAuthority(
        boolean botActive,
        boolean senderIsPersistentOwner,
        Optional<UUID> persistentOwnerId,
        Optional<UUID> activeAgentId,
        OptionalLong activeGeneration) {
    public AiProposalAuthority {
        persistentOwnerId = Objects.requireNonNull(
                persistentOwnerId, "persistentOwnerId");
        persistentOwnerId.ifPresent(ownerId -> requireNonZero(ownerId, "persistentOwnerId"));
        activeAgentId = Objects.requireNonNull(activeAgentId, "activeAgentId");
        activeAgentId.ifPresent(agentId -> requireNonZero(agentId, "activeAgentId"));
        activeGeneration = Objects.requireNonNull(
                activeGeneration, "activeGeneration");
        if (activeGeneration.isPresent() && activeGeneration.getAsLong() <= 0L) {
            throw new IllegalArgumentException("activeGeneration must be positive");
        }
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    @Override
    public String toString() {
        return "AiProposalAuthority[botActive=" + botActive
                + ", senderIsPersistentOwner=" + senderIsPersistentOwner
                + ", hasPersistentOwner=" + persistentOwnerId.isPresent()
                + ", hasActiveAgent=" + activeAgentId.isPresent()
                + ", hasActiveGeneration=" + activeGeneration.isPresent()
                + "]";
    }
}
