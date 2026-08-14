package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.client.credential.CredentialProfile;
import java.util.Objects;
import java.util.UUID;

/**
 * 将一个客户端本地 credential binding 解析为 DeepSeek 请求短暂使用的 secret 副本。
 *
 * <p>绑定三元组由已建立客户端会话的可信代码给出；它不从模型输入、聊天文本或服务器响应中
 * 解析 profile 名称。找不到、类型不符或 store 已关闭时一律不泄露细节地拒绝。</p>
 */
public final class BoundDeepSeekCredentialSupplier
        implements DeepSeekCredentialSupplier {
    private final ClientCredentialStore store;
    private final UUID serverInstanceId;
    private final UUID ownerId;
    private final UUID botId;
    private final UUID expectedAgentId;

    public BoundDeepSeekCredentialSupplier(
            ClientCredentialStore store,
            UUID serverInstanceId,
            UUID ownerId,
            UUID botId) {
        this(store, serverInstanceId, ownerId, botId, null, false);
    }

    /**
     * Creates a supplier that refuses to expose a key if the local binding has been rebound to a
     * different agent before transport authentication begins.
     */
    public BoundDeepSeekCredentialSupplier(
            ClientCredentialStore store,
            UUID serverInstanceId,
            UUID ownerId,
            UUID botId,
            UUID expectedAgentId) {
        this(store, serverInstanceId, ownerId, botId, expectedAgentId, true);
    }

    private BoundDeepSeekCredentialSupplier(
            ClientCredentialStore store,
            UUID serverInstanceId,
            UUID ownerId,
            UUID botId,
            UUID expectedAgentId,
            boolean agentBound) {
        this.store = Objects.requireNonNull(store, "store");
        this.serverInstanceId = Objects.requireNonNull(
                serverInstanceId, "serverInstanceId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.botId = Objects.requireNonNull(botId, "botId");
        this.expectedAgentId = agentBound
                ? Objects.requireNonNull(expectedAgentId, "expectedAgentId")
                : null;
    }

    @Override
    public char[] copySecret() {
        return (expectedAgentId == null
                        ? store.copyBoundSecret(
                                serverInstanceId,
                                ownerId,
                                botId,
                                CredentialProfile.DEEPSEEK_PROVIDER)
                        : store.copyBoundSecretForAgent(
                                serverInstanceId,
                                ownerId,
                                botId,
                                expectedAgentId,
                                CredentialProfile.DEEPSEEK_PROVIDER))
                .orElseThrow(BoundDeepSeekCredentialSupplier::unavailable);
    }

    private static DeepSeekProviderException unavailable() {
        return new DeepSeekProviderException(
                DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE);
    }
}
