package io.github.greytaiwolf.botplayer.client.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Small physical-client-only store for the explicit P6-R1 local opt-in.
 *
 * <p>The exact schema is deliberately limited to {@code reviewOnly.enabled}. Unknown fields are
 * rejected rather than treated as future endpoint/model/provider configuration. A missing file is
 * materialized as {@code false}; corrupt content is never overwritten and the caller must disable
 * the local Provider.
 */
public final class ReviewOnlyClientSettingsStore {
    public static final String FILE_NAME = "review-only-v1.json";
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 1024L;

    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "reviewOnly");
    private static final Set<String> REVIEW_ONLY_FIELDS = Set.of("enabled");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
            .create();

    private final Path directory;
    private final Path path;

    public ReviewOnlyClientSettingsStore(Path directory)
            throws ReviewOnlyClientSettingsException {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        path = this.directory.resolve(FILE_NAME);
        ensureDirectory();
    }

    /** Returns the persisted setting, creating the explicit fail-closed default once if absent. */
    public synchronized ReviewOnlyClientSettings load()
            throws ReviewOnlyClientSettingsException {
        if (!Files.exists(path)) {
            save(ReviewOnlyClientSettings.DEFAULT);
            return ReviewOnlyClientSettings.DEFAULT;
        }
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                throw corrupt();
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw corrupt();
                }
                JsonObject root = parsed.getAsJsonObject();
                requireExactFields(root, ROOT_FIELDS);
                requireSchema(root);
                JsonObject reviewOnly = requireObject(root.get("reviewOnly"));
                requireExactFields(reviewOnly, REVIEW_ONLY_FIELDS);
                return new ReviewOnlyClientSettings(requireBoolean(reviewOnly, "enabled"));
            }
        } catch (ReviewOnlyClientSettingsException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt(exception);
        }
    }

    /** Atomically persists only the one local enabled bit. */
    public synchronized void save(ReviewOnlyClientSettings settings)
            throws ReviewOnlyClientSettingsException {
        ReviewOnlyClientSettings checked = Objects.requireNonNull(settings, "settings");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject reviewOnly = new JsonObject();
        reviewOnly.addProperty("enabled", checked.enabled());
        root.add("reviewOnly", reviewOnly);
        writeAtomically(root);
    }

    /** Persists a local explicit toggle; no network packet or credential operation is involved. */
    public synchronized ReviewOnlyClientSettings setEnabled(boolean enabled)
            throws ReviewOnlyClientSettingsException {
        ReviewOnlyClientSettings settings = new ReviewOnlyClientSettings(enabled);
        save(settings);
        return settings;
    }

    public Path path() {
        return path;
    }

    private void ensureDirectory() throws ReviewOnlyClientSettingsException {
        try {
            Files.createDirectories(directory);
            applyPosixPermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (IOException exception) {
            throw new ReviewOnlyClientSettingsException(
                    ReviewOnlyClientSettingsException.Reason.INITIALIZE, exception);
        }
    }

    private void writeAtomically(JsonObject root)
            throws ReviewOnlyClientSettingsException {
        Path temporary = null;
        try {
            ensureDirectory();
            temporary = Files.createTempFile(directory, path.getFileName() + ".", ".tmp");
            applyPosixPermissions(temporary, FILE_PERMISSIONS);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            applyPosixPermissions(path, FILE_PERMISSIONS);
        } catch (IOException exception) {
            throw new ReviewOnlyClientSettingsException(
                    ReviewOnlyClientSettingsException.Reason.SAVE, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // This file has no secret; a failed best-effort cleanup is still non-fatal.
                }
            }
        }
    }

    private static void requireSchema(JsonObject root)
            throws ReviewOnlyClientSettingsException {
        JsonElement schema = root.get("schemaVersion");
        if (schema == null
                || !schema.isJsonPrimitive()
                || !schema.getAsJsonPrimitive().isNumber()
                || !Integer.toString(SCHEMA_VERSION).equals(schema.getAsString())) {
            throw corrupt();
        }
    }

    private static boolean requireBoolean(JsonObject object, String field)
            throws ReviewOnlyClientSettingsException {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw corrupt();
        }
        return value.getAsBoolean();
    }

    private static JsonObject requireObject(JsonElement value)
            throws ReviewOnlyClientSettingsException {
        if (value == null || !value.isJsonObject()) {
            throw corrupt();
        }
        return value.getAsJsonObject();
    }

    private static void requireExactFields(JsonObject object, Set<String> expected)
            throws ReviewOnlyClientSettingsException {
        if (!object.keySet().equals(expected)) {
            throw corrupt();
        }
    }

    private static ReviewOnlyClientSettingsException corrupt() {
        return new ReviewOnlyClientSettingsException(
                ReviewOnlyClientSettingsException.Reason.CORRUPT_OR_UNSUPPORTED);
    }

    private static ReviewOnlyClientSettingsException corrupt(Throwable cause) {
        return new ReviewOnlyClientSettingsException(
                ReviewOnlyClientSettingsException.Reason.CORRUPT_OR_UNSUPPORTED,
                cause);
    }

    private static void applyPosixPermissions(
            Path target, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(target, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems do not support POSIX permission hints.
        }
    }
}
