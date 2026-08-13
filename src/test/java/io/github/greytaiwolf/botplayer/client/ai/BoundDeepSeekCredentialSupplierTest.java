package io.github.greytaiwolf.botplayer.client.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundDeepSeekCredentialSupplierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesOnlyTheExactLocalBindingAndReturnsCallerOwnedCopy()
            throws Exception {
        UUID server = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        char[] first = null;
        char[] second = null;
        try (ClientCredentialStore store = new ClientCredentialStore(temporaryDirectory)) {
            store.bind(server, owner, bot, "private", "sk-local-test-key",
                    Optional.empty());
            BoundDeepSeekCredentialSupplier supplier =
                    new BoundDeepSeekCredentialSupplier(store, server, owner, bot);
            first = supplier.copySecret();
            second = supplier.copySecret();
            assertArrayEquals("sk-local-test-key".toCharArray(), first);
            first[0] = 'x';
            assertArrayEquals("sk-local-test-key".toCharArray(), second);
            DeepSeekProviderException missing = assertThrows(
                    DeepSeekProviderException.class,
                    () -> new BoundDeepSeekCredentialSupplier(
                            store, server, UUID.randomUUID(), bot).copySecret());
            assertEquals(DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE,
                    missing.code());
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    @Test
    void rejectsACredentialCopyAfterTheStoreIsClosed() throws Exception {
        UUID server = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        ClientCredentialStore store = new ClientCredentialStore(temporaryDirectory);
        try {
            store.bind(server, owner, bot, "private", "sk-local-test-key",
                    Optional.empty());
            BoundDeepSeekCredentialSupplier supplier =
                    new BoundDeepSeekCredentialSupplier(store, server, owner, bot);
            store.close();

            DeepSeekProviderException failure = assertThrows(
                    DeepSeekProviderException.class, supplier::copySecret);
            assertEquals(DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE,
                    failure.code());
        } finally {
            store.close();
        }
    }

    @Test
    void sessionBoundSupplierRefusesASecretAfterLocalAgentRebinding()
            throws Exception {
        UUID server = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        UUID firstAgent = UUID.randomUUID();
        UUID replacementAgent = UUID.randomUUID();
        try (ClientCredentialStore store = new ClientCredentialStore(temporaryDirectory)) {
            store.bind(server, owner, bot, "private", "sk-local-test-key",
                    Optional.of(firstAgent));
            BoundDeepSeekCredentialSupplier supplier =
                    new BoundDeepSeekCredentialSupplier(
                            store, server, owner, bot, firstAgent);

            char[] initial = supplier.copySecret();
            try {
                assertArrayEquals("sk-local-test-key".toCharArray(), initial);
            } finally {
                Arrays.fill(initial, '\0');
            }

            store.unbind(server, owner, bot);
            store.bind(server, owner, bot, "private", "",
                    Optional.of(replacementAgent));

            DeepSeekProviderException failure = assertThrows(
                    DeepSeekProviderException.class, supplier::copySecret);
            assertEquals(DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE, failure.code());
        }
    }
}
