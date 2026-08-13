package io.github.greytaiwolf.botplayer.client.credential;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Physical-client-only storage for provider credentials and per-bot bindings.
 *
 * <p>The credential file is plaintext. Restrictive filesystem permissions reduce accidental local
 * disclosure but do not protect against malware or another mod running as the same OS user.
 */
public final class ClientCredentialStore implements AutoCloseable {
    public static final String CREDENTIALS_FILE_NAME = "credentials-v1.json";
    public static final String BINDINGS_FILE_NAME = "bindings-v1.json";
    public static final String DEFAULT_PROFILE_ID = "default";

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PROFILES = 64;
    private static final int MAX_BINDINGS = 2048;
    private static final long MAX_CREDENTIAL_FILE_BYTES = 128L * 1024L;
    private static final long MAX_BINDING_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_SECRET_LENGTH = 512;
    private static final int MIN_SECRET_LENGTH = 8;
    private static final Pattern PROFILE_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<String> CREDENTIAL_ROOT_FIELDS =
            Set.of("schemaVersion", "profiles");
    private static final Set<String> CREDENTIAL_FIELDS =
            Set.of("profileId", "provider", "secret");
    private static final Set<String> BINDING_ROOT_FIELDS =
            Set.of("schemaVersion", "bindings");
    private static final Set<String> BINDING_FIELDS = Set.of(
            "serverInstanceId",
            "ownerUuid",
            "botId",
            "profileId",
            "agentId");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path directory;
    private final Path credentialsPath;
    private final Path bindingsPath;
    private final Map<String, CredentialProfile> profiles;
    private final Map<CredentialBindingKey, BotCredentialBinding> bindings;

    /**
     * @param directory the dedicated BotPlayer client directory; production passes
     *     {@code FMLPaths.CONFIGDIR/botplayer}, while tests can inject a temporary directory
     */
    public ClientCredentialStore(Path directory) throws CredentialStoreException {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        credentialsPath = this.directory.resolve(CREDENTIALS_FILE_NAME);
        bindingsPath = this.directory.resolve(BINDINGS_FILE_NAME);
        ensureDirectory();

        Map<String, CredentialProfile> loadedProfiles = readProfiles();
        Map<CredentialBindingKey, BotCredentialBinding> loadedBindings =
                readBindings(loadedProfiles);
        profiles = new LinkedHashMap<>(loadedProfiles);
        bindings = new LinkedHashMap<>(loadedBindings);
    }

    public synchronized Optional<CredentialProfile> findProfile(String profileId) {
        if (!isValidProfileId(profileId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(profiles.get(profileId));
    }

    public synchronized Optional<BotCredentialBinding> findBinding(
            UUID serverInstanceId, UUID ownerUuid, UUID botId) {
        return Optional.ofNullable(bindings.get(
                new CredentialBindingKey(serverInstanceId, ownerUuid, botId)));
    }

    /**
     * 在同一把 store 锁内完成 binding、profile 与 secret 副本解析。
     *
     * <p>客户端 Provider 只能使用返回的调用方自有副本。这样 {@link #close()} 或 profile
     * 替换不会在两次查询之间留下半清零的 key；任何零字符都按失效副本拒绝。</p>
     */
    public synchronized Optional<char[]> copyBoundSecret(
            UUID serverInstanceId,
            UUID ownerUuid,
            UUID botId,
            String expectedProvider) {
        return copyBoundSecret(
                serverInstanceId,
                ownerUuid,
                botId,
                Optional.empty(),
                expectedProvider);
    }

    /**
     * Resolves a secret only while the exact local binding still has {@code expectedAgentId}.
     *
     * <p>The check and secret copy share this store's monitor. A client-sponsored request can
     * therefore bind its provider to the agent it already verified instead of racing a later local
     * rebind between an authorization check and the HTTP Authorization header construction.
     */
    public synchronized Optional<char[]> copyBoundSecretForAgent(
            UUID serverInstanceId,
            UUID ownerUuid,
            UUID botId,
            UUID expectedAgentId,
            String expectedProvider) {
        Objects.requireNonNull(expectedAgentId, "expectedAgentId");
        return copyBoundSecret(
                serverInstanceId,
                ownerUuid,
                botId,
                Optional.of(expectedAgentId),
                expectedProvider);
    }

    private Optional<char[]> copyBoundSecret(
            UUID serverInstanceId,
            UUID ownerUuid,
            UUID botId,
            Optional<UUID> expectedAgentId,
            String expectedProvider) {
        Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(expectedAgentId, "expectedAgentId");
        Objects.requireNonNull(expectedProvider, "expectedProvider");
        BotCredentialBinding binding = bindings.get(new CredentialBindingKey(
                serverInstanceId, ownerUuid, botId));
        if (binding == null
                || (expectedAgentId.isPresent()
                        && !expectedAgentId.orElseThrow().equals(binding.agentId()))) {
            return Optional.empty();
        }
        CredentialProfile profile = profiles.get(binding.profileId());
        if (profile == null || !expectedProvider.equals(profile.provider())) {
            return Optional.empty();
        }
        char[] secret = profile.copySecret();
        if (secret.length == 0 || containsClearedCharacter(secret)) {
            Arrays.fill(secret, '\0');
            return Optional.empty();
        }
        return Optional.of(secret);
    }

    public synchronized int bindingCount(String profileId) {
        return (int) bindings.values().stream()
                .filter(binding -> binding.profileId().equals(profileId))
                .count();
    }

    private static boolean containsClearedCharacter(char[] secret) {
        for (char value : secret) {
            if (value == '\0') {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves or reuses a credential profile and binds it to one bot.
     *
     * <p>An empty key reuses an existing profile. A non-empty key replaces that profile, including
     * for every other bot that references it. Existing bot bindings retain their persistent agent
     * id. A new binding adopts the server's active id when it is locally unused; otherwise it gets a
     * fresh id.
     */
    public synchronized BindResult bind(
            UUID serverInstanceId,
            UUID ownerUuid,
            UUID botId,
            String profileId,
            String keyInput,
            Optional<UUID> activeAgentId)
            throws CredentialStoreException {
        CredentialBindingKey bindingKey =
                new CredentialBindingKey(serverInstanceId, ownerUuid, botId);
        String checkedProfileId = requireValidProfileId(profileId);
        Objects.requireNonNull(keyInput, "keyInput");
        Objects.requireNonNull(activeAgentId, "activeAgentId");

        boolean replaceProfile = !keyInput.isEmpty();
        CredentialProfile existingProfile = profiles.get(checkedProfileId);
        if (!replaceProfile && existingProfile == null) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.PROFILE_MISSING);
        }
        if (replaceProfile
                && existingProfile == null
                && profiles.size() >= MAX_PROFILES) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.CAPACITY);
        }

        CredentialProfile replacement = null;
        if (replaceProfile) {
            char[] checkedSecret = requireValidSecret(keyInput);
            replacement = new CredentialProfile(
                    checkedProfileId,
                    CredentialProfile.DEEPSEEK_PROVIDER,
                    checkedSecret);
            Arrays.fill(checkedSecret, '\0');
        }

        BotCredentialBinding existingBinding = bindings.get(bindingKey);
        if (existingBinding == null && bindings.size() >= MAX_BINDINGS) {
            if (replacement != null) {
                replacement.clearSecret();
            }
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.CAPACITY);
        }
        UUID agentId = existingBinding == null
                ? chooseAgentId(activeAgentId)
                : existingBinding.agentId();
        BotCredentialBinding newBinding =
                new BotCredentialBinding(bindingKey, checkedProfileId, agentId);

        if (replacement != null) {
            Map<String, CredentialProfile> candidateProfiles =
                    new LinkedHashMap<>(profiles);
            candidateProfiles.put(checkedProfileId, replacement);
            writeProfiles(candidateProfiles);

            CredentialProfile oldProfile = profiles.put(checkedProfileId, replacement);
            if (oldProfile != null) {
                oldProfile.clearSecret();
            }
        }

        Map<CredentialBindingKey, BotCredentialBinding> candidateBindings =
                new LinkedHashMap<>(bindings);
        candidateBindings.put(bindingKey, newBinding);
        writeBindings(candidateBindings);
        bindings.clear();
        bindings.putAll(candidateBindings);

        return new BindResult(newBinding, replaceProfile, bindingCount(checkedProfileId));
    }

    /**
     * Removes only the local bot binding. The possibly shared credential profile is retained.
     */
    public synchronized Optional<BotCredentialBinding> unbind(
            UUID serverInstanceId, UUID ownerUuid, UUID botId)
            throws CredentialStoreException {
        CredentialBindingKey bindingKey =
                new CredentialBindingKey(serverInstanceId, ownerUuid, botId);
        BotCredentialBinding existing = bindings.get(bindingKey);
        if (existing == null) {
            return Optional.empty();
        }

        Map<CredentialBindingKey, BotCredentialBinding> candidate =
                new LinkedHashMap<>(bindings);
        candidate.remove(bindingKey);
        writeBindings(candidate);
        bindings.clear();
        bindings.putAll(candidate);
        return Optional.of(existing);
    }

    public static boolean isValidProfileId(String profileId) {
        return profileId != null && PROFILE_ID_PATTERN.matcher(profileId).matches();
    }

    @Override
    public synchronized void close() {
        profiles.values().forEach(CredentialProfile::clearSecret);
        profiles.clear();
        bindings.clear();
    }

    private UUID chooseAgentId(Optional<UUID> activeAgentId) {
        if (activeAgentId.isPresent() && !isAgentIdInUse(activeAgentId.orElseThrow())) {
            return activeAgentId.orElseThrow();
        }

        UUID candidate;
        do {
            candidate = UUID.randomUUID();
        } while (isAgentIdInUse(candidate));
        return candidate;
    }

    private boolean isAgentIdInUse(UUID candidate) {
        return bindings.values().stream()
                .anyMatch(binding -> binding.agentId().equals(candidate));
    }

    private void ensureDirectory() throws CredentialStoreException {
        try {
            Files.createDirectories(directory);
            applyPosixPermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (IOException exception) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.INITIALIZE, exception);
        }
    }

    private Map<String, CredentialProfile> readProfiles() throws CredentialStoreException {
        if (Files.notExists(credentialsPath)) {
            return Map.of();
        }

        try (Reader reader = Files.newBufferedReader(credentialsPath, StandardCharsets.UTF_8)) {
            rejectOversizedFile(credentialsPath, MAX_CREDENTIAL_FILE_BYTES);
            JsonObject root = requireObject(JsonParser.parseReader(reader));
            requireExactFields(root, CREDENTIAL_ROOT_FIELDS);
            requireSchema(root);
            JsonArray array = requireArray(root.get("profiles"));
            if (array.size() > MAX_PROFILES) {
                throw corruptStore();
            }

            Map<String, CredentialProfile> loaded = new LinkedHashMap<>();
            for (JsonElement element : array) {
                JsonObject entry = requireObject(element);
                requireExactFields(entry, CREDENTIAL_FIELDS);
                String profileId =
                        requireValidProfileId(requireString(entry, "profileId"));
                String provider = requireString(entry, "provider");
                if (!CredentialProfile.DEEPSEEK_PROVIDER.equals(provider)) {
                    throw corruptStore();
                }
                char[] secret = requireValidSecret(requireString(entry, "secret"));
                CredentialProfile profile =
                        new CredentialProfile(profileId, provider, secret);
                Arrays.fill(secret, '\0');
                if (loaded.putIfAbsent(profileId, profile) != null) {
                    profile.clearSecret();
                    throw corruptStore();
                }
            }
            applyPosixPermissions(credentialsPath, FILE_PERMISSIONS);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            throw corruptStore();
        }
    }

    private Map<CredentialBindingKey, BotCredentialBinding> readBindings(
            Map<String, CredentialProfile> loadedProfiles)
            throws CredentialStoreException {
        if (Files.notExists(bindingsPath)) {
            return Map.of();
        }

        try (Reader reader = Files.newBufferedReader(bindingsPath, StandardCharsets.UTF_8)) {
            rejectOversizedFile(bindingsPath, MAX_BINDING_FILE_BYTES);
            JsonObject root = requireObject(JsonParser.parseReader(reader));
            requireExactFields(root, BINDING_ROOT_FIELDS);
            requireSchema(root);
            JsonArray array = requireArray(root.get("bindings"));
            if (array.size() > MAX_BINDINGS) {
                throw corruptStore();
            }

            Map<CredentialBindingKey, BotCredentialBinding> loaded =
                    new LinkedHashMap<>();
            Set<UUID> agentIds = new HashSet<>();
            for (JsonElement element : array) {
                JsonObject entry = requireObject(element);
                requireExactFields(entry, BINDING_FIELDS);
                CredentialBindingKey key = new CredentialBindingKey(
                        parseUuid(requireString(entry, "serverInstanceId")),
                        parseUuid(requireString(entry, "ownerUuid")),
                        parseUuid(requireString(entry, "botId")));
                String profileId =
                        requireValidProfileId(requireString(entry, "profileId"));
                UUID agentId = parseUuid(requireString(entry, "agentId"));
                if (!loadedProfiles.containsKey(profileId)
                        || loaded.containsKey(key)
                        || !agentIds.add(agentId)) {
                    throw corruptStore();
                }
                loaded.put(key, new BotCredentialBinding(key, profileId, agentId));
            }
            applyPosixPermissions(bindingsPath, FILE_PERMISSIONS);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            throw corruptStore();
        }
    }

    private void writeProfiles(Map<String, CredentialProfile> candidate)
            throws CredentialStoreException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray entries = new JsonArray();
        for (CredentialProfile profile : candidate.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("profileId", profile.profileId());
            entry.addProperty("provider", profile.provider());
            char[] secret = profile.copySecret();
            try {
                entry.addProperty("secret", new String(secret));
            } finally {
                Arrays.fill(secret, '\0');
            }
            entries.add(entry);
        }
        root.add("profiles", entries);
        writeAtomically(credentialsPath, root);
    }

    private void writeBindings(
            Map<CredentialBindingKey, BotCredentialBinding> candidate)
            throws CredentialStoreException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray entries = new JsonArray();
        for (BotCredentialBinding binding : candidate.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty(
                    "serverInstanceId",
                    binding.key().serverInstanceId().toString());
            entry.addProperty("ownerUuid", binding.key().ownerUuid().toString());
            entry.addProperty("botId", binding.key().botId().toString());
            entry.addProperty("profileId", binding.profileId());
            entry.addProperty("agentId", binding.agentId().toString());
            entries.add(entry);
        }
        root.add("bindings", entries);
        writeAtomically(bindingsPath, root);
    }

    private void writeAtomically(Path target, JsonObject root)
            throws CredentialStoreException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, target.getFileName() + ".", ".tmp");
            applyPosixPermissions(temporary, FILE_PERMISSIONS);
            try (Writer writer =
                    Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            applyPosixPermissions(target, FILE_PERMISSIONS);
        } catch (IOException exception) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.SAVE, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed best-effort cleanup must not reveal credential data in an error.
                }
            }
        }
    }

    private static String requireValidProfileId(String profileId)
            throws CredentialStoreException {
        if (!isValidProfileId(profileId)) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.INVALID_PROFILE_ID);
        }
        return profileId;
    }

    private static char[] requireValidSecret(String secret)
            throws CredentialStoreException {
        if (secret.length() < MIN_SECRET_LENGTH
                || secret.length() > MAX_SECRET_LENGTH
                || secret.chars().anyMatch(character ->
                        Character.isISOControl(character)
                                || Character.isWhitespace(character))) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.INVALID_KEY);
        }
        return secret.toCharArray();
    }

    private static void requireSchema(JsonObject root)
            throws CredentialStoreException {
        JsonElement schema = root.get("schemaVersion");
        if (schema == null
                || !schema.isJsonPrimitive()
                || !schema.getAsJsonPrimitive().isNumber()
                || !Integer.toString(SCHEMA_VERSION).equals(schema.getAsString())) {
            throw corruptStore();
        }
    }

    private static void requireExactFields(JsonObject object, Set<String> expected)
            throws CredentialStoreException {
        if (!object.keySet().equals(expected)) {
            throw corruptStore();
        }
    }

    private static JsonObject requireObject(JsonElement element)
            throws CredentialStoreException {
        if (element == null || !element.isJsonObject()) {
            throw corruptStore();
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement element)
            throws CredentialStoreException {
        if (element == null || !element.isJsonArray()) {
            throw corruptStore();
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field)
            throws CredentialStoreException {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw corruptStore();
        }
        return element.getAsString();
    }

    private static UUID parseUuid(String value) throws CredentialStoreException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw corruptStore();
        }
    }

    private static void rejectOversizedFile(Path path, long maximumBytes)
            throws IOException, CredentialStoreException {
        if (Files.size(path) > maximumBytes) {
            throw corruptStore();
        }
    }

    private static CredentialStoreException corruptStore() {
        return new CredentialStoreException(
                CredentialStoreException.Reason.CORRUPT_OR_UNSUPPORTED);
    }

    private static void applyPosixPermissions(
            Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems do not support these permissions.
        }
    }

    public record BindResult(
            BotCredentialBinding binding,
            boolean profileReplaced,
            int profileBindingCount) {
        public BindResult {
            Objects.requireNonNull(binding, "binding");
            if (profileBindingCount < 1) {
                throw new IllegalArgumentException(
                        "profileBindingCount must be positive");
            }
        }
    }
}
