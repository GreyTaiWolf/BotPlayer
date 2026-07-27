package io.github.greytaiwolf.botplayer.client.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientCredentialStoreTest {
    private static final String FIRST_KEY = "sk-local-first-secret";
    private static final String REPLACEMENT_KEY = "sk-local-replacement-secret";

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSeparateCredentialAndBindingFiles() throws Exception {
        UUID serverId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        UUID agentId;

        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            ClientCredentialStore.BindResult result = store.bind(
                    serverId,
                    ownerId,
                    botId,
                    "main",
                    FIRST_KEY,
                    Optional.empty());
            agentId = result.binding().agentId();
        }

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                ClientCredentialStore.CREDENTIALS_FILE_NAME)));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                ClientCredentialStore.BINDINGS_FILE_NAME)));

        try (ClientCredentialStore reloaded =
                new ClientCredentialStore(temporaryDirectory)) {
            BotCredentialBinding binding =
                    reloaded.findBinding(serverId, ownerId, botId).orElseThrow();
            assertEquals("main", binding.profileId());
            assertEquals(agentId, binding.agentId());
            assertArrayEquals(
                    FIRST_KEY.toCharArray(),
                    reloaded.findProfile("main").orElseThrow().copySecret());
        }
    }

    @Test
    void oneProfileCanServeManyBotsWithDistinctPersistentAgents()
            throws Exception {
        UUID serverId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID firstBotId = UUID.randomUUID();
        UUID secondBotId = UUID.randomUUID();

        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            BotCredentialBinding first = store.bind(
                            serverId,
                            ownerId,
                            firstBotId,
                            "shared",
                            FIRST_KEY,
                            Optional.empty())
                    .binding();
            BotCredentialBinding second = store.bind(
                            serverId,
                            ownerId,
                            secondBotId,
                            "shared",
                            "",
                            Optional.empty())
                    .binding();

            assertNotEquals(first.agentId(), second.agentId());
            assertEquals(2, store.bindingCount("shared"));

            UUID originalFirstAgent = first.agentId();
            UUID unrelatedActiveId = UUID.randomUUID();
            BotCredentialBinding rebound = store.bind(
                            serverId,
                            ownerId,
                            firstBotId,
                            "shared",
                            "",
                            Optional.of(unrelatedActiveId))
                    .binding();
            assertEquals(originalFirstAgent, rebound.agentId());
        }
    }

    @Test
    void sameBotIdIsIsolatedByServerAndOwnerScope() throws Exception {
        UUID firstServerId = UUID.randomUUID();
        UUID secondServerId = UUID.randomUUID();
        UUID firstOwnerId = UUID.randomUUID();
        UUID secondOwnerId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();

        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            BotCredentialBinding first = store.bind(
                            firstServerId,
                            firstOwnerId,
                            botId,
                            "shared",
                            FIRST_KEY,
                            Optional.empty())
                    .binding();
            BotCredentialBinding otherServer = store.bind(
                            secondServerId,
                            firstOwnerId,
                            botId,
                            "shared",
                            "",
                            Optional.empty())
                    .binding();
            BotCredentialBinding otherOwner = store.bind(
                            firstServerId,
                            secondOwnerId,
                            botId,
                            "shared",
                            "",
                            Optional.empty())
                    .binding();

            assertNotEquals(first.agentId(), otherServer.agentId());
            assertNotEquals(first.agentId(), otherOwner.agentId());
            assertNotEquals(otherServer.agentId(), otherOwner.agentId());
            assertEquals(
                    first.agentId(),
                    store.findBinding(firstServerId, firstOwnerId, botId)
                            .orElseThrow()
                            .agentId());
            assertEquals(
                    otherServer.agentId(),
                    store.findBinding(secondServerId, firstOwnerId, botId)
                            .orElseThrow()
                            .agentId());
            assertEquals(
                    otherOwner.agentId(),
                    store.findBinding(firstServerId, secondOwnerId, botId)
                            .orElseThrow()
                            .agentId());
        }
    }

    @Test
    void secretAppearsOnlyInCredentialFileNotBindingFile() throws Exception {
        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            store.bind(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "private",
                    FIRST_KEY,
                    Optional.empty());
        }

        String credentialJson = Files.readString(
                temporaryDirectory.resolve(
                        ClientCredentialStore.CREDENTIALS_FILE_NAME),
                StandardCharsets.UTF_8);
        String bindingJson = Files.readString(
                temporaryDirectory.resolve(
                        ClientCredentialStore.BINDINGS_FILE_NAME),
                StandardCharsets.UTF_8);
        assertTrue(credentialJson.contains(FIRST_KEY));
        assertFalse(bindingJson.contains(FIRST_KEY));
        assertFalse(bindingJson.contains("\"secret\""));
    }

    @Test
    void nonEmptyKeyReplacesSharedProfileWithoutChangingAgents()
            throws Exception {
        UUID serverId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID firstBotId = UUID.randomUUID();
        UUID secondBotId = UUID.randomUUID();

        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            BotCredentialBinding first = store.bind(
                            serverId,
                            ownerId,
                            firstBotId,
                            "shared",
                            FIRST_KEY,
                            Optional.empty())
                    .binding();
            BotCredentialBinding second = store.bind(
                            serverId,
                            ownerId,
                            secondBotId,
                            "shared",
                            "",
                            Optional.empty())
                    .binding();

            ClientCredentialStore.BindResult replaced = store.bind(
                    serverId,
                    ownerId,
                    firstBotId,
                    "shared",
                    REPLACEMENT_KEY,
                    Optional.empty());

            assertTrue(replaced.profileReplaced());
            assertEquals(2, replaced.profileBindingCount());
            assertEquals(first.agentId(), replaced.binding().agentId());
            assertEquals(
                    second.agentId(),
                    store.findBinding(serverId, ownerId, secondBotId)
                            .orElseThrow()
                            .agentId());
            assertArrayEquals(
                    REPLACEMENT_KEY.toCharArray(),
                    store.findProfile("shared").orElseThrow().copySecret());
        }
    }

    @Test
    void unbindRetainsPossiblySharedCredentialProfile() throws Exception {
        UUID serverId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();

        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            store.bind(
                    serverId,
                    ownerId,
                    botId,
                    "shared",
                    FIRST_KEY,
                    Optional.empty());

            assertTrue(store.unbind(serverId, ownerId, botId).isPresent());
            assertTrue(store.findBinding(serverId, ownerId, botId).isEmpty());
            assertTrue(store.findProfile("shared").isPresent());
        }
    }

    @Test
    void unknownOrCorruptSchemaFailsClosedWithoutOverwrite()
            throws IOException {
        Path credentials = temporaryDirectory.resolve(
                ClientCredentialStore.CREDENTIALS_FILE_NAME);
        String unsupported =
                "{\"schemaVersion\":99,\"profiles\":[],\"sentinel\":\""
                        + FIRST_KEY
                        + "\"}";
        Files.writeString(credentials, unsupported, StandardCharsets.UTF_8);

        CredentialStoreException exception = assertThrows(
                CredentialStoreException.class,
                () -> new ClientCredentialStore(temporaryDirectory));

        assertFalse(exception.toString().contains(FIRST_KEY));
        assertEquals(
                unsupported,
                Files.readString(credentials, StandardCharsets.UTF_8));

        Files.writeString(credentials, "{not-json", StandardCharsets.UTF_8);
        assertThrows(
                CredentialStoreException.class,
                () -> new ClientCredentialStore(temporaryDirectory));
        assertEquals(
                "{not-json",
                Files.readString(credentials, StandardCharsets.UTF_8));

        Files.writeString(
                credentials,
                "{\"schemaVersion\":1.5,\"profiles\":[]}",
                StandardCharsets.UTF_8);
        assertThrows(
                CredentialStoreException.class,
                () -> new ClientCredentialStore(temporaryDirectory));
    }

    @Test
    void oversizedCredentialFileFailsClosedBeforeParsing() throws IOException {
        Path credentials = temporaryDirectory.resolve(
                ClientCredentialStore.CREDENTIALS_FILE_NAME);
        String oversized = "x".repeat(128 * 1024 + 1);
        Files.writeString(credentials, oversized, StandardCharsets.UTF_8);

        CredentialStoreException exception = assertThrows(
                CredentialStoreException.class,
                () -> new ClientCredentialStore(temporaryDirectory));

        assertEquals(
                CredentialStoreException.Reason.CORRUPT_OR_UNSUPPORTED,
                exception.reason());
        assertEquals(Files.size(credentials), (long) oversized.length());
    }

    @Test
    void secretBearingRepresentationsAreRedacted() throws Exception {
        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            store.bind(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "private",
                    FIRST_KEY,
                    Optional.empty());
            CredentialProfile profile =
                    store.findProfile("private").orElseThrow();

            assertFalse(profile.toString().contains(FIRST_KEY));
            assertTrue(profile.toString().contains("[REDACTED]"));
        }
    }

    @Test
    void rejectsInvalidProfileIdsAndKeysWithoutEchoingSecret()
            throws Exception {
        try (ClientCredentialStore store =
                new ClientCredentialStore(temporaryDirectory)) {
            CredentialStoreException profileFailure = assertThrows(
                    CredentialStoreException.class,
                    () -> store.bind(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "bad profile!",
                            FIRST_KEY,
                            Optional.empty()));
            assertFalse(profileFailure.toString().contains(FIRST_KEY));

            String invalidKey = "sk-invalid key";
            CredentialStoreException keyFailure = assertThrows(
                    CredentialStoreException.class,
                    () -> store.bind(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "valid",
                            invalidKey,
                            Optional.empty()));
            assertFalse(keyFailure.toString().contains(invalidKey));
        }
    }
}
